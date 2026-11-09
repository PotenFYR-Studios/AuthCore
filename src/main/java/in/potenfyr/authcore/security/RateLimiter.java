package in.potenfyr.authcore.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory per-key rate limiter (per-IP joins, per-account login/2FA budgets, command cooldowns).
 *
 * <p>Design (security pass 2026-11-09):
 *
 * <ul>
 *   <li><b>Isolated buckets.</b> Keys are partitioned by their namespace (the text before the first
 *       {@code ':'}). Join-flood keys (bare IPs, {@code rejoin:}) and security budgets ({@code mfa:},
 *       {@code sec:}, {@code cmd:} ...) used to share ONE bounded map: a botnet joining from 10k
 *       fresh addresses filled it and the oldest-window eviction then dropped the very counters
 *       that throttle 2FA / recovery guessing - flooding reset every brute-force budget.
 *   <li><b>Exhausted windows are evicted last.</b> When a bucket is full of live windows, a window
 *       that is currently BLOCKING (count &ge; limit) is only evicted when nothing else is left.
 *   <li><b>Sliding window.</b> A fixed window allowed 2x the limit in a burst straddling the window
 *       boundary; the previous window's count is now weighted into the decision.
 *   <li><b>IPv6 aggregation.</b> {@link #ipKey} collapses IPv6 addresses to their /64 so a single
 *       client cannot rotate through its 2^64 addresses to get a fresh budget per request.
 * </ul>
 */
public final class RateLimiter {

  /** Upper bound of tracked windows PER namespace bucket. */
  public static final int MAX_ENTRIES = 10_000;

  /** Upper bound of distinct namespace buckets (namespaces are code-defined, this is a guard). */
  private static final int MAX_BUCKETS = 64;

  private static final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

  private RateLimiter() {}

  /** A shared budget for TOTP, recovery-code and email-OTP submissions. */
  public static boolean tryMfa(String ip, java.util.UUID account) {
    if (ip == null || ip.isBlank() || account == null) return false;
    // Account budget first: an attacker rotating IPs must still hit the per-account cap, and a
    // rejected account check must not burn the shared per-IP budget of a NAT neighbour.
    return tryAcquire("mfa:account:" + account, 5, 60000)
        && tryAcquire("mfa:ip:" + ipKey(ip), 5, 60000);
  }

  /**
   * Normalizes an address for rate-limit keys: IPv4 unchanged, IPv6 collapsed to its /64 prefix
   * (IPv4-mapped IPv6 to the IPv4 form). Non-literals are returned trimmed.
   */
  public static String ipKey(String ip) {
    if (ip == null) return "";
    String s = ip.trim();
    if (s.indexOf(':') < 0) return s;
    try {
      String literal = s;
      if (literal.startsWith("[")) {
        int close = literal.indexOf(']');
        literal = close > 0 ? literal.substring(1, close) : literal.substring(1);
      }
      int zone = literal.indexOf('%');
      if (zone > 0) literal = literal.substring(0, zone);
      if (!in.potenfyr.authcore.network.ProxySupport.isValidIp(literal)) return s;
      byte[] b = java.net.InetAddress.getByName(literal).getAddress();
      if (b.length == 4) return (b[0] & 0xFF) + "." + (b[1] & 0xFF) + "." + (b[2] & 0xFF) + "." + (b[3] & 0xFF);
      StringBuilder sb = new StringBuilder(24);
      for (int i = 0; i < 8; i += 2) {
        if (i > 0) sb.append(':');
        sb.append(Integer.toHexString(((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF)));
      }
      return sb.append("::/64").toString();
    } catch (Exception err) {
      return s;
    }
  }

  /**
   * Attempts to record one event for the key within the sliding window.
   *
   * @param key identifier (e.g. IP address)
   * @param maxAllowed maximum events allowed within the window
   * @param windowMs window length in milliseconds
   * @return {@code true} if the event is within the limit, {@code false} if rate-limited
   */
  public static boolean tryAcquire(String key, int maxAllowed, long windowMs) {
    if (key == null || maxAllowed <= 0 || windowMs <= 0) return true;
    return bucketFor(key).tryAcquire(key, maxAllowed, windowMs, System.currentTimeMillis());
  }

  /** Number of tracked windows across all buckets (self-test/diagnostics; never exposes keys). */
  public static int trackedKeys() {
    int total = 0;
    for (Bucket bucket : buckets.values()) total += bucket.windows.size();
    return total;
  }

  /** Hard upper bound of {@link #trackedKeys()}. */
  public static int maxTrackedKeys() {
    return MAX_ENTRIES * MAX_BUCKETS;
  }

  private static Bucket bucketFor(String key) {
    int colon = key.indexOf(':');
    String ns = colon > 0 && colon <= 24 ? key.substring(0, colon) : "";
    // Bare IPv6 keys contain ':' - an IPv6 literal must land in the default (join) bucket.
    if (!ns.isEmpty() && !ns.chars().allMatch(c -> Character.isLetter(c) || c == '-' || c == '_'))
      ns = "";
    Bucket bucket = buckets.get(ns);
    if (bucket != null) return bucket;
    if (buckets.size() >= MAX_BUCKETS) return buckets.computeIfAbsent("", k -> new Bucket());
    return buckets.computeIfAbsent(ns, k -> new Bucket());
  }

  private static final class Bucket {
    final Map<String, Window> windows = new ConcurrentHashMap<>();
    long lastPruneMs;

    synchronized boolean tryAcquire(String key, int maxAllowed, long windowMs, long now) {
      Window w = windows.get(key);
      if (w == null) {
        if (windows.size() >= MAX_ENTRIES) {
          purgeExpired(now);
          if (windows.size() >= MAX_ENTRIES) evictOne();
        }
        w = new Window(now, windowMs);
        windows.put(key, w);
      }
      w.roll(now, windowMs);
      // Sliding-window estimate: the part of the previous window that still overlaps counts.
      double overlap = 1.0 - Math.min(1.0, (now - w.startMs) / (double) windowMs);
      double estimate = w.previous * overlap + w.count;
      if (estimate >= maxAllowed) {
        w.blocked = true;
        return false;
      }
      w.count++;
      w.blocked = w.previous * overlap + w.count >= maxAllowed;

      if (windows.size() >= MAX_ENTRIES && now - lastPruneMs >= 1000) {
        lastPruneMs = now;
        purgeExpired(now);
      }
      return true;
    }

    /** Drops windows whose current AND previous frame have elapsed. */
    private void purgeExpired(long now) {
      windows.values().removeIf(w -> now - w.startMs >= 2 * w.windowMs);
    }

    /** Evicts the oldest non-blocking window; a blocking one only when nothing else is left. */
    private void evictOne() {
      String oldestFree = null;
      long oldestFreeStart = Long.MAX_VALUE;
      String oldestAny = null;
      long oldestAnyStart = Long.MAX_VALUE;
      for (Map.Entry<String, Window> entry : windows.entrySet()) {
        Window w = entry.getValue();
        if (w.startMs < oldestAnyStart) {
          oldestAnyStart = w.startMs;
          oldestAny = entry.getKey();
        }
        if (!w.blocked && w.startMs < oldestFreeStart) {
          oldestFreeStart = w.startMs;
          oldestFree = entry.getKey();
        }
      }
      String victim = oldestFree != null ? oldestFree : oldestAny;
      if (victim != null) windows.remove(victim);
    }
  }

  private static final class Window {
    long startMs;
    final long windowMs;
    int count;
    int previous;
    boolean blocked;

    Window(long startMs, long windowMs) {
      this.startMs = startMs;
      this.windowMs = windowMs;
    }

    /** Advances the frame: the finished frame becomes {@code previous}. */
    void roll(long now, long windowMs) {
      long elapsed = now - startMs;
      if (elapsed < windowMs) return;
      if (elapsed < 2 * windowMs) {
        previous = count;
        startMs += windowMs;
      } else {
        previous = 0;
        startMs = now;
      }
      count = 0;
    }
  }
}
