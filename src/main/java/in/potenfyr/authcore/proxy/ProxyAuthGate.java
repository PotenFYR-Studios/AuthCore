package in.potenfyr.authcore.proxy;

import java.util.UUID;

/**
 * Shared proxy-side full-auth logic (used by both the BungeeCord and Velocity entries).
 *
 * <p>When {@code block-unauthenticated=true} in {@code authcore-proxy.properties}, a player may
 * only connect to a GAME server once they have authenticated on a backend. The check is made
 * when the player is about to connect to a backend - NOT at proxy login, where it could never be
 * satisfied because the player must first reach a backend to run /login or /register.
 *
 * <p>A session is confirmed by the proxy-local {@link SessionCache} (fed by the backends'
 * {@code AUTH_CHANGED} plugin messages on every login/logout) or, for multi-proxy networks, by a
 * Redis {@code authcore:session:<uuid>} entry written by AuthCore backends with Redis enabled.
 *
 * <p><b>Fail-closed (default):</b> if no session can be confirmed, the player is DENIED so a Redis
 * outage cannot bypass authentication entirely. Set {@code fail-closed=false} for the old
 * fail-open behavior (allow through during outages).
 */
public final class ProxyAuthGate {

  private ProxyAuthGate() {}

  /**
   * Whether the given backend server is the configured auth/bootstrap server that unauthenticated
   * players are allowed to join.
   */
  public static boolean isAuthServer(String serverName, ProxyConfig config) {
    if (serverName == null || config == null || config.authServer == null) return false;
    String wanted = config.authServer.trim();
    return !wanted.isEmpty() && wanted.equalsIgnoreCase(serverName.trim());
  }

  /**
   * Whether the player has a valid network session.
   *
   * <p>Checks the proxy-local interop session cache FIRST: it needs no Redis round-trip and is
   * updated by the backends' AUTH_CHANGED messages, which is what makes the gate satisfiable in
   * the first place (the old implementation only checked Redis, which is only written after an
   * in-game login the login-time gate had already blocked).
   *
   * @param warn consumer for warning messages (logger-agnostic: Bungee uses JUL, Velocity slf4j)
   * @return {@code true} when a valid session exists. Returns {@code false} when fail-closed
   *     and no session can be confirmed.
   */
  public static boolean hasValidSession(UUID uuid, ProxyConfig config, java.util.function.Consumer<String> warn) {
    if (uuid == null || config == null) return false;

    // Proxy-local session cache (interop) - authoritative and Redis-independent.
    if (SessionCache.isAuthenticated(uuid.toString(), config.sessionTimeoutMs)) return true;

    // Cross-proxy / cross-restart sessions (Redis). Only needed when the local cache misses.
    if (config.redisHost == null || config.redisHost.isBlank()) return !config.failClosed;

    String key = "authcore:session:" + uuid;
    try (RedisClient redis =
        RedisClient.connect(config.redisHost, config.redisPort, config.redisPassword, config.redisDatabase)) {
      if (redis == null) {
        if (warn != null) {
          if (config.failClosed) {
            warn.accept("AuthCore proxy auth: no local session and Redis unreachable - BLOCKING connection (fail-closed).");
          } else {
            warn.accept("AuthCore proxy auth: no local session and Redis unreachable - allowing connections (fail-open).");
          }
        }
        return !config.failClosed;
      }
      String session = redis.get(key);
      if (!redis.isAvailable()) {
        if (warn != null) {
          warn.accept(
              config.failClosed
                  ? "AuthCore proxy auth: Redis became unreachable - BLOCKING connection (fail-closed)."
                  : "AuthCore proxy auth: Redis became unreachable - allowing connection (fail-open).");
        }
        return !config.failClosed;
      }
      return session != null && !session.isBlank();
    } catch (RuntimeException err) {
      if (warn != null)
        warn.accept(
            config.failClosed
                ? "AuthCore proxy auth: Redis check failed - BLOCKING connection (fail-closed)."
                : "AuthCore proxy auth: Redis check failed - allowing connection (fail-open).");
      return !config.failClosed;
    }
  }
}
