package net.ded3ec.proxy;

import java.util.UUID;

/**
 * Shared proxy-side full-auth logic (used by both the BungeeCord and Velocity entries).
 *
 * <p>When {@code block-unauthenticated=true} in {@code authcore-proxy.properties}, a player
 * may only reach a backend if Redis holds a valid {@code authcore:session:<uuid>} entry
 * (written by AuthCore backends with Redis enabled). The check is FAIL-OPEN: if Redis is
 * unreachable, the player is allowed through so a Redis outage never locks out a network.
 */
public final class ProxyAuthGate {

  private ProxyAuthGate() {}

  /**
   * Whether the player has a valid network session (Redis-backed).
   *
   * @param warn consumer for warning messages (logger-agnostic: Bungee uses JUL, Velocity slf4j)
   * @return {@code true} when a valid session exists OR Redis is unavailable (fail-open)
   */
  public static boolean hasValidSession(UUID uuid, ProxyConfig config, java.util.function.Consumer<String> warn) {
    if (uuid == null) return true;
    String key = "authcore:session:" + uuid;
    try (RedisClient redis =
        RedisClient.connect(config.redisHost, config.redisPort, config.redisPassword, config.redisDatabase)) {
      if (redis == null) {
        if (warn != null)
          warn.accept("AuthCore proxy auth: Redis unreachable - allowing connections (fail-open).");
        return true;
      }
      String session = redis.get(key);
      return session != null;
    }
  }
}
