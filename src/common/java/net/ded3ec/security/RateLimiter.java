package net.ded3ec.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in-memory per-key rate limiter (e.g. per-IP join/login limits). Expired entries are
 * purged when the map grows large to keep memory bounded.
 */
public final class RateLimiter {

  private static final int MAX_ENTRIES = 10_000;

  /** Key -> (count, windowStartMs). */
  private static final Map<String, Window> counters = new ConcurrentHashMap<>();

  private RateLimiter() {}

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

    long now = System.currentTimeMillis();

    Window w = counters.computeIfAbsent(key, k -> new Window(now, windowMs));
    synchronized (w) {
      if (now - w.startMs >= windowMs) {
        w.startMs = now;
        w.count = 0;
      }
      if (w.count >= maxAllowed) return false;
      w.count++;
    }

    purgeIfLarge(now);
    return true;
  }

  /** Removes expired windows when the map grows too large (bounds memory). */
  private static void purgeIfLarge(long now) {
    if (counters.size() <= MAX_ENTRIES) return;
    counters.entrySet().removeIf(e -> now - e.getValue().startMs >= e.getValue().windowMs);
    if (counters.size() > MAX_ENTRIES) counters.clear();
  }

  private static final class Window {
    long startMs;
    final long windowMs;
    int count;

    Window(long startMs, long windowMs) {
      this.startMs = startMs;
      this.windowMs = windowMs;
    }
  }
}
