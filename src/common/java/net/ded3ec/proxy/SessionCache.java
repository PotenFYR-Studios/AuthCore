package net.ded3ec.proxy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Proxy-side session cache: remembers which players authenticated on a backend
 * (from AuthCore's AUTH_CHANGED interop messages) so other proxy plugins can query
 * the network-wide authentication state without a database.
 */
public final class SessionCache {

  private static final Map<String, Long> LAST_AUTH = new ConcurrentHashMap<>();

  private SessionCache() {}

  /** Records an auth-state change: uuid -> authenticated? */
  public static void update(String uuid, boolean authenticated) {
    if (uuid == null || uuid.isBlank()) return;
    if (authenticated) {
      LAST_AUTH.put(uuid, System.currentTimeMillis());
    } else {
      LAST_AUTH.remove(uuid);
    }
  }

  /** Whether the player authenticated recently (within the given timeout). */
  public static boolean isAuthenticated(String uuid, long timeoutMs) {
    Long ts = LAST_AUTH.get(uuid);
    if (ts == null) return false;
    if (System.currentTimeMillis() - ts > timeoutMs) {
      LAST_AUTH.remove(uuid);
      return false;
    }
    return true;
  }

  /** Number of currently tracked sessions (for status reporting). */
  public static int size() {
    return LAST_AUTH.size();
  }

  /** Removes expired entries (bounded memory - never grows unbounded). */
  public static void prune(long timeoutMs) {
    long cutoff = System.currentTimeMillis() - timeoutMs;
    LAST_AUTH.entrySet().removeIf(e -> e.getValue() < cutoff);
  }
}
