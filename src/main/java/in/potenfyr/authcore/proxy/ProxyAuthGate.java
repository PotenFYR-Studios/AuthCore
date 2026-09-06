package in.potenfyr.authcore.proxy;

import java.util.UUID;

/**
 * Shared proxy-side full-auth logic (used by both the BungeeCord and Velocity entries).
 *
 * <p>When {@code block-unauthenticated=true} in {@code authcore-proxy.properties}, a player
 * may only reach a backend if Redis holds a valid {@code authcore:session:<uuid>} entry
 * (written by AuthCore backends with Redis enabled).
 *
 * <p><b>Fail-closed (default):</b> if Redis is unreachable, the player is DENIED so a Redis
 * outage cannot bypass authentication entirely. Set {@code fail-closed=false} for the old
 * fail-open behavior (allow through during outages).
 */
public final class ProxyAuthGate {

  private ProxyAuthGate() {}

  /**
   * Whether the player has a valid network session (Redis-backed).
   *
   * @param warn consumer for warning messages (logger-agnostic: Bungee uses JUL, Velocity slf4j)
   * @return {@code true} when a valid session exists. Returns {@code false} when fail-closed
   *     and Redis is unreachable.
   */
  public static boolean hasValidSession(UUID uuid, ProxyConfig config, java.util.function.Consumer<String> warn) {
    if (uuid == null || config == null) return false;
    String key = "authcore:session:" + uuid;
    try (RedisClient redis =
        RedisClient.connect(config.redisHost, config.redisPort, config.redisPassword, config.redisDatabase)) {
      if (redis == null) {
        if (warn != null) {
          if (config.failClosed) {
            warn.accept("AuthCore proxy auth: Redis unreachable - BLOCKING connection (fail-closed).");
          } else {
            warn.accept("AuthCore proxy auth: Redis unreachable - allowing connections (fail-open).");
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
