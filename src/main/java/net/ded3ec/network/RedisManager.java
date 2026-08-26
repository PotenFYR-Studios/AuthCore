package net.ded3ec.network;

import net.ded3ec.models.Config;
import net.ded3ec.util.Database;
import net.ded3ec.util.Logger;

import java.util.UUID;
import net.ded3ec.AuthCoreServer;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * Optional Redis integration for network-wide session & ban synchronization. When enabled, active
 * sessions are published with a short TTL so duplicate logins across a server network are
 * detected, and a shared ban list can be enforced.
 *
 * <p>All operations degrade gracefully to "no data" when Redis is disabled or unreachable - the
 * mod keeps working with its local database.
 */
public final class RedisManager {

  private static final String SESSION_PREFIX = "authcore:session:";
  private static final String BAN_PREFIX = "authcore:ban:";
  private static final long SESSION_TTL_MS = 30 * 60 * 1000L;

  /** Unique id of this server instance (used to distinguish own sessions in a network). */
  public static final String SERVER_ID =
      UUID.randomUUID().toString().substring(0, 8);

  private static volatile JedisPool pool;

  private RedisManager() {}

  /** Whether Redis integration is configured and connected. */
  public static boolean isEnabled() {
    return pool != null && AuthCoreServer.config != null && AuthCoreServer.config.database.redis.enabled;
  }

  /** Connects the shared Jedis pool (lazy; called on first use). */
  private static synchronized void ensureConnected() {
    if (pool != null || AuthCoreServer.config == null) return;

    var cfg = AuthCoreServer.config.database.redis;
    if (!cfg.enabled) return;

    try {
      JedisPoolConfig poolConfig = new JedisPoolConfig();
      poolConfig.setMaxTotal(8);
      poolConfig.setMaxIdle(4);

      if (cfg.password != null && !cfg.password.isEmpty())
        pool = new JedisPool(poolConfig, cfg.host, cfg.port, 2000, cfg.password, cfg.database);
      else pool = new JedisPool(poolConfig, cfg.host, cfg.port, 2000, null, cfg.database);

      // Validate the connection
      try (Jedis jedis = pool.getResource()) {
        jedis.ping();
      }
      AuthCoreServer.LOGGER.info(true, "Redis connected at {}:{}", cfg.host, cfg.port);
    } catch (Exception err) {
      pool = null;
      AuthCoreServer.LOGGER.error(
          false,
          "["
              + net.ded3ec.util.ErrorCodes.code(
                  net.ded3ec.util.ErrorCodes.Module.REDIS, net.ded3ec.util.ErrorCodes.Kind.CONNECTION, 1)
              + "] Redis connection failed - session sync disabled:",
          err);
    }
  }

  /** Publishes the player's active session (called on successful login). */
  public static void publishSession(UUID uuid, String username) {
    if (!isEnabled()) return;
    try (Jedis jedis = pool.getResource()) {
      jedis.setex(
          SESSION_PREFIX + uuid,
          (int) (SESSION_TTL_MS / 1000),
          SERVER_ID + ":" + username);
    } catch (Exception err) {
      AuthCoreServer.LOGGER.debug(null, "Redis publishSession failed:", err);
    }
  }

  /**
   * Checks whether a remote server holds an active session for the player.
   *
   * @return {@code true} if another server in the network has the session
   */
  public static boolean hasRemoteSession(UUID uuid) {
    ensureConnected();
    if (!isEnabled()) return false;

    try (Jedis jedis = pool.getResource()) {
      String value = jedis.get(SESSION_PREFIX + uuid);
      return value != null && !value.startsWith(SERVER_ID);
    } catch (Exception err) {
      return false;
    }
  }

  /** Removes the player's session key (called on logout). */
  public static void removeSession(UUID uuid) {
    if (!isEnabled()) return;
    try (Jedis jedis = pool.getResource()) {
      jedis.del(SESSION_PREFIX + uuid);
    } catch (Exception err) {
      AuthCoreServer.LOGGER.debug(null, "Redis removeSession failed:", err);
    }
  }

  // ------------------------------------------------------------------
  // Network-wide single sign-on (SSO)
  //
  // On login the session token is mirrored to Redis with a TTL. A player joining another
  // server of the network can present that token (companion echo) and be trusted without
  // entering the password again - true single sign-on across the server network.
  // ------------------------------------------------------------------

  private static final String SSO_PREFIX = "authcore:sso:";

  /** Publishes the SSO ticket (session token) after a successful login. */
  public static void publishSso(UUID uuid, String token, int ttlMin) {
    ensureConnected();
    if (!isEnabled() || uuid == null || token == null) return;
    try (Jedis jedis = pool.getResource()) {
      jedis.setex(SSO_PREFIX + uuid, Math.max(1, ttlMin * 60), token);
    } catch (Exception err) {
      AuthCoreServer.LOGGER.debug(null, "Redis publishSso failed:", err);
    }
  }

  /** Verifies an SSO ticket token against the network value (constant-time). */
  public static boolean verifySso(UUID uuid, String token) {
    ensureConnected();
    if (!isEnabled() || uuid == null || token == null) return false;
    try (Jedis jedis = pool.getResource()) {
      String stored = jedis.get(SSO_PREFIX + uuid);
      if (stored == null || stored.length() != token.length()) return false;
      int diff = 0;
      for (int i = 0; i < stored.length(); i++) diff |= stored.charAt(i) ^ token.charAt(i);
      return diff == 0;
    } catch (Exception err) {
      return false;
    }
  }

  /** Whether a remote server currently holds an SSO ticket for the player. */
  public static boolean hasSso(UUID uuid) {
    ensureConnected();
    if (!isEnabled() || uuid == null) return false;
    try (Jedis jedis = pool.getResource()) {
      return jedis.exists(SSO_PREFIX + uuid);
    } catch (Exception err) {
      return false;
    }
  }

  /** Removes the SSO ticket (logout / session transfer). */
  public static void removeSso(UUID uuid) {
    if (!isEnabled() || uuid == null) return;
    try (Jedis jedis = pool.getResource()) {
      jedis.del(SSO_PREFIX + uuid);
    } catch (Exception err) {
      AuthCoreServer.LOGGER.debug(null, "Redis removeSso failed:", err);
    }
  }

  /** Checks whether the username is banned across the network (shared ban list). */
  public static boolean isBannedRemotely(String username) {
    ensureConnected();
    if (!isEnabled() || username == null) return false;

    try (Jedis jedis = pool.getResource()) {
      return jedis.exists(BAN_PREFIX + username.toLowerCase());
    } catch (Exception err) {
      return false;
    }
  }

  /** Bans the username network-wide with a TTL (0 = permanent). */
  public static void banRemotely(String username, long ttlMs) {
    if (!isEnabled() || username == null) return;
    try (Jedis jedis = pool.getResource()) {
      if (ttlMs > 0) jedis.setex(BAN_PREFIX + username.toLowerCase(), (int) (ttlMs / 1000), "1");
      else jedis.set(BAN_PREFIX + username.toLowerCase(), "1");
    } catch (Exception err) {
      AuthCoreServer.LOGGER.debug(null, "Redis banRemotely failed:", err);
    }
  }

  /** Unbans the username network-wide. */
  public static void unbanRemotely(String username) {
    if (!isEnabled() || username == null) return;
    try (Jedis jedis = pool.getResource()) {
      jedis.del(BAN_PREFIX + username.toLowerCase());
    } catch (Exception err) {
      AuthCoreServer.LOGGER.debug(null, "Redis unbanRemotely failed:", err);
    }
  }

  /** Reads the distributed config overrides (HOCON snippet) if any. */
  public static String getConfigOverrides() {
    ensureConnected();
    if (!isEnabled()) return null;
    try (Jedis jedis = pool.getResource()) {
      return jedis.get("authcore:config:overrides");
    } catch (Exception err) {
      return null;
    }
  }

  /** Stores distributed config overrides (for network administrators). */
  public static void setConfigOverrides(String hocon) {
    ensureConnected();
    if (!isEnabled()) return;
    try (Jedis jedis = pool.getResource()) {
      if (hocon == null || hocon.isBlank()) jedis.del("authcore:config:overrides");
      else jedis.set("authcore:config:overrides", hocon);
    } catch (Exception err) {
      AuthCoreServer.LOGGER.debug(null, "Redis config override failed:", err);
    }
  }

  // ------------------------------------------------------------------
  // Cross-server security event bus (Redis pub/sub)
  // ------------------------------------------------------------------

  /** Pub/sub channel used for cross-server security events. */
  public static final String EVENT_CHANNEL = "authcore:events";

  private static volatile boolean subscriberStarted = false;

  /**
   * Publishes a security event to every server in the network. Receivers log it, forward it to
   * their webhook and execute local actions (e.g. remote kicks).
   *
   * @param type event type (login, logout, register, kick, brute-force, account-locked, ban...)
   * @param username affected player
   * @param detail human-readable detail
   */
  public static void publishEvent(String type, String username, String detail) {
    ensureConnected();
    if (!isEnabled()) return;
    try (Jedis jedis = pool.getResource()) {
      // Gson-built payload (never string-concatenated): usernames/details are escaped
      // correctly no matter what characters they contain.
      com.google.gson.JsonObject evt = new com.google.gson.JsonObject();
      evt.addProperty("type", type);
      evt.addProperty("username", username == null ? "" : username);
      evt.addProperty("detail", detail == null ? "" : detail);
      evt.addProperty("server", SERVER_ID);
      evt.addProperty("ts", System.currentTimeMillis());
      jedis.publish(EVENT_CHANNEL, evt.toString());
    } catch (Exception err) {
      AuthCoreServer.LOGGER.debug(null, "Redis publishEvent failed:", err);
    }
  }

  /** Starts the background pub/sub subscriber (daemon thread, one per network). */
  public static synchronized void startEventSubscriber() {
    ensureConnected();
    if (subscriberStarted || !isEnabled()) return;
    subscriberStarted = true;

    Thread thread =
        new Thread(
            () -> {
              try (Jedis jedis = pool.getResource()) {
                jedis.subscribe(
                    new redis.clients.jedis.JedisPubSub() {
                      @Override
                      public void onMessage(String channel, String message) {
                        if (EVENT_CHANNEL.equals(channel)) handleEvent(message);
                      }
                    },
                    EVENT_CHANNEL);
              } catch (Exception err) {
                subscriberStarted = false;
                AuthCoreServer.LOGGER.debug(null, "Redis event subscriber stopped:", err);
              }
            },
            "AuthCore-RedisEvents");
    thread.setDaemon(true);
    thread.start();
    AuthCoreServer.LOGGER.info(true, "Redis security event bus subscribed on '{}'", EVENT_CHANNEL);
  }

  /** Handles an incoming cross-server event. */
  private static void handleEvent(String json) {
    try {
      com.google.gson.JsonObject o =
          new com.google.gson.Gson().fromJson(json, com.google.gson.JsonObject.class);
      if (o == null || !o.has("type") || !o.has("server")) return;

      // Ignore our own events
      if (SERVER_ID.equals(o.get("server").getAsString())) return;

      String type = o.get("type").getAsString();
      String username = o.has("username") && !o.get("username").isJsonNull()
          ? o.get("username").getAsString() : "";
      String detail = o.has("detail") && !o.get("detail").isJsonNull()
          ? o.get("detail").getAsString() : "";

      net.ded3ec.security.SecurityLog.log(
          "EVENT_BUS_" + type.toUpperCase(java.util.Locale.ROOT), username + " | " + detail);
      net.ded3ec.network.Webhook.sendEmbed(
          "Cross-Server Event (" + type + ")",
          "**" + (username.isEmpty() ? "?" : username) + "** " + detail,
          0x55AAFF);

      // Local actions for remote events
      if ("kick".equals(type) || "logout".equals(type)) {
        net.ded3ec.models.User user = net.ded3ec.models.User.getUserByUsername(username);
        if (user != null && user.isActive)
          user.kick(AuthCoreServer.messages.promptUserKickedByAdmin);
      }
    } catch (Exception ignored) {
      // malformed event - ignore
    }
  }

  // ------------------------------------------------------------------
  // Discord account linking (link codes)
  // ------------------------------------------------------------------

  private static final String DISCORD_LINK_PREFIX = "authcore:discordlink:";
  private static final String DISCORD_PREFIX = "authcore:discord:";

  /** Stores a pending Discord link code for the given username (10 minute TTL). */
  public static void storeDiscordLinkCode(String code, String username) {
    ensureConnected();
    if (!isEnabled() || code == null) return;
    try (Jedis jedis = pool.getResource()) {
      jedis.setex(DISCORD_LINK_PREFIX + code, 600, username);
    } catch (Exception err) {
      AuthCoreServer.LOGGER.debug(null, "Redis storeDiscordLinkCode failed:", err);
    }
  }

  /** Resolves a pending Discord link code to a username (consumed on use). */
  public static String consumeDiscordLinkCode(String code) {
    ensureConnected();
    if (!isEnabled() || code == null) return null;
    try (Jedis jedis = pool.getResource()) {
      String username = jedis.get(DISCORD_LINK_PREFIX + code);
      if (username != null) jedis.del(DISCORD_LINK_PREFIX + code);
      return username;
    } catch (Exception err) {
      return null;
    }
  }

  /** Publishes the Discord ↔ Minecraft mapping for bots to read. */
  public static void publishDiscordLink(String discordId, String username) {
    ensureConnected();
    if (!isEnabled() || discordId == null) return;
    try (Jedis jedis = pool.getResource()) {
      jedis.setex(DISCORD_PREFIX + discordId, 30L * 24 * 3600, username);
    } catch (Exception err) {
      AuthCoreServer.LOGGER.debug(null, "Redis publishDiscordLink failed:", err);
    }
  }
}
