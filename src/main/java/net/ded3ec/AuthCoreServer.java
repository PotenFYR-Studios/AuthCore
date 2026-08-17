package net.ded3ec;

import net.ded3ec.models.Lobby;
import net.ded3ec.network.EmailSender;
import net.ded3ec.network.ProxySupport;
import net.ded3ec.network.WebPanel;
import net.ded3ec.security.Security;
import net.ded3ec.util.Database;
import net.ded3ec.util.Registry;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import net.ded3ec.models.Config;
import net.ded3ec.models.Messages;
import net.ded3ec.util.Logger;

/**
 * AuthCore core, shared by every loader.
 *
 * <p>Loader entrypoints (Fabric, Forge, NeoForge) all call {@link #start()}; everything the
 * mod actually does lives here or in the packages below. This class also tracks the two
 * facts the whole mod depends on: the loaded config and the real server mode (detected from
 * server.properties instead of trusting the config, so cracked and premium servers both work
 * without setup).
 */
public class AuthCoreServer {
  public static final String MOD_ID = "authcore";
  public static final String MOD_VERSION = "1.0.0";
  public static final Logger LOGGER = new Logger(MOD_ID);
  public static final Path configPath =
      net.ded3ec.compat.Compat.getConfigDir().resolve("authcore");

  /**
   * Dedicated daemon thread pool for off-main-thread I/O (GeoIP lookups etc.). Daemon threads do
   * not keep the JVM alive and never block the server tick loop. Bounded (fixed size) so a burst
   * of lookups can never spawn unbounded threads on a production server.
   */
  public static final ExecutorService IO_EXECUTOR =
      Executors.newFixedThreadPool(
          4,
          runnable -> {
            Thread thread = new Thread(runnable, "AuthCore-IO");
            thread.setDaemon(true);
            return thread;
          });

  public static volatile Config config;
  public static volatile Messages messages;

  /**
   * The real online-mode of the server (server.properties), detected lazily from the running
   * {@link net.minecraft.server.MinecraftServer}. {@code null} until the first detection.
   *
   * <p>The mod ALWAYS follows the real server.properties mode - there is no config override.
   * Trusting only the config previously made online-mode players get kicked with a bogus
   * "Authentication Token is invalid" (premium-UUID mismatch against their offline-mode UUID)
   * and offline-mode players silently auto-registered as premium on offline servers.
   */
  private static volatile Boolean serverOnlineMode = null;

  /** Records the detected server online-mode (idempotent; logs the mismatch only once). */
  public static void detectServerOnlineMode(boolean online) {
    if (serverOnlineMode != null && serverOnlineMode == online) return;

    serverOnlineMode = online;

    // Auto-migration: on a server without Mojang session verification no account can be
    // premium - stale premium flags from earlier builds are bulk-cleared once per boot so
    // they cannot keep bypassing registration. Genuinely online-mode accounts are re-flagged
    // automatically when the server verifies Mojang sessions again.
    if (!online && !premiumFlagMigrationDone) {
      premiumFlagMigrationDone = true;
      try {
        int cleared = net.ded3ec.util.Database.downgradePremiumAccounts();
        if (cleared > 0)
          LOGGER.info(
              true,
              "Auto-migration: {} account(s) marked as online-mode were moved to offline-mode "
                  + "authentication - this server does not verify Mojang sessions, so online-mode "
                  + "auto-login is unavailable here.",
              cleared);
      } catch (Exception err) {
        LOGGER.debug(false, "Online-mode flag migration failed (best-effort):", err);
      }
    }

    LOGGER.info(
        true,
        "Server session-authentication detected: {}",
        online
            ? "online-mode (Mojang session checks active)"
            : "offline-mode (no Mojang session checks)");
  }

  /** Guards the one-per-boot premium-flag bulk migration. */
  private static volatile boolean premiumFlagMigrationDone = false;

  /**
   * Effective server mode for premium decisions: the REAL server.properties mode, detected
   * from the running server. There is no config override - the mod always follows the
   * Minecraft server. Before the first detection it defaults to online (the safest
   * assumption - a detection failure must never weaken an online-mode server).
   *
   * @return {@code true} when the server is (or is assumed to be) in online mode
   */
  public static boolean isServerOnline() {
    return serverOnlineMode == null || serverOnlineMode;
  }

  /**
   * UUIDs whose Minecraft profile was verified by the server's OWN Mojang session
   * authentication during login (captured by the login mixin). AuthCore performs no Mojang
   * API calls for premium detection - this marker is the single source of truth, and it is
   * only ever set on online-mode servers where vanilla authenticated the profile.
   */
  private static final java.util.Set<java.util.UUID> PREMIUM_VERIFIED =
      java.util.concurrent.ConcurrentHashMap.newKeySet();

  /** Records that the server's Mojang session authentication verified this profile. */
  public static void markPremiumVerified(java.util.UUID uuid) {
    if (uuid != null) PREMIUM_VERIFIED.add(uuid);
  }

  /** Whether the server's own Mojang session authentication verified this profile this boot. */
  public static boolean isPremiumVerified(java.util.UUID uuid) {
    return uuid != null && PREMIUM_VERIFIED.contains(uuid);
  }

  /** Drops the per-join verification marker (called on player leave). */
  public static void clearPremiumVerified(java.util.UUID uuid) {
    if (uuid != null) PREMIUM_VERIFIED.remove(uuid);
  }

  /**
   * Whether the login mixin should run the server-side Mojang session verification for a
   * player (premium auto-login on offline-mode servers). Requires an offline-mode server,
   * premium auto-login enabled and no proxy IP-forwarding (proxy backends receive real
   * profiles through the proxy and must not re-verify).
   */
  public static boolean isPremiumVerificationEnabled() {
    if (config == null) return false;
    if (isServerOnline()) return false;
    if (!config.session.authentication.premiumAutoLogin) return false;
    if (config.session.proxySupport.enabled) return false;
    return true;
  }

  /** Boots the mod - called by every loader entrypoint. Idempotent for safety. */
  public static void start() {
    if (started) return;
    started = true;
    long start = System.currentTimeMillis();

    LOGGER.debug(false, "AuthCoreServer.start() - boot sequence begins");

    Registry.register();

    // Start the optional web admin panel (no-op when disabled or no token configured)
    net.ded3ec.network.WebPanel.start();

    // Start the cross-server security event bus (no-op when Redis is disabled)
    net.ded3ec.network.RedisManager.startEventSubscriber();

    // Start the honeypot listener (no-op when disabled)
    net.ded3ec.security.Honeypot.start();

    // Start automated maintenance tasks (backups + rotating announcements)
    startMaintenanceTasks();

    printStartupBanner(start);
    LOGGER.debug(
        false, "AuthCoreServer.start() - boot completed in {}ms", System.currentTimeMillis() - start);
  }

  private static boolean started = false;

  /** Starts the automated backup and rotating-announcement tasks (server tick based). */
  private static void startMaintenanceTasks() {
    if (config == null) return;

    // Automated database backups
    if (config.session.backup.intervalHours > 0)
      net.ded3ec.util.TaskScheduler.getInstance()
          .setInterval(
              () -> net.ded3ec.security.Backups.runAutomaticBackup(),
              config.session.backup.intervalHours * 3600_000L);

    // Rotating server announcements
    if (config.lobby.announcements != null && !config.lobby.announcements.isEmpty())
      net.ded3ec.util.TaskScheduler.getInstance()
          .setInterval(
              () -> net.ded3ec.security.Announcements.rotateAndBroadcast(),
              Math.max(config.lobby.announcementIntervalSec, 10) * 1000L);
  }

  /** Prints a structured startup banner (version, environment and security summary). */
  private static void printStartupBanner(long startedAtMs) {
    String mcVersion = net.ded3ec.compat.Compat.getGameVersion();
    String loaderVersion = net.ded3ec.compat.Compat.getLoaderVersion();
    boolean showBanner = config == null || config.logging.showBanner;
    boolean showSummary = config == null || config.logging.showSummary;
    String dbType =
        config != null && config.database.postgres.enabled
            ? "PostgreSQL"
            : config != null && config.database.mysql.enabled ? "MySQL" : "SQLite";
    String redis =
        config != null && config.database.redis.enabled ? "enabled" : "disabled";
    String captcha =
        config != null && config.lobby.captcha.enabled ? "enabled" : "disabled";
    String proxy =
        config != null && config.session.proxySupport.enabled
            ? config.session.proxySupport.protocol
            : "off";
    String panel =
        config != null && config.session.webPanel.enabled && !config.session.webPanel.token.isBlank()
            ? "http://" + config.session.webPanel.host + ":" + config.session.webPanel.port + "/"
            : "disabled";
    String email =
        config != null && net.ded3ec.network.EmailSender.isEnabled() ? "enabled" : "disabled";

    LOGGER.info(null, " ");
    if (showBanner)
      LOGGER.info(null, "  AuthCore - The Fortress Framework for Minecraft Servers");
    if (showSummary) {
      String serverMode =
          serverOnlineMode == null
              ? "detecting..."
              : (serverOnlineMode ? "online-mode" : "offline-mode");

      LOGGER.info(null, "==============================================================");
      LOGGER.info(null, "  Version          : {}", MOD_VERSION);
      LOGGER.info(null, "  Minecraft        : {}", mcVersion);
      LOGGER.info(null, "  Loader Platform  : {}", loaderVersion);
      LOGGER.info(null, "  Server Mode      : {}", serverMode);
      LOGGER.info(null, "  Language         : {}", config != null ? config.language : "en");
      LOGGER.info(null, "  Database         : {}", dbType);
      LOGGER.info(null, "  Redis Sync       : {}", redis);
      LOGGER.info(null, "  Captcha          : {}", captcha);
      LOGGER.info(null, "  Proxy Support    : {}", proxy);
      LOGGER.info(null, "  Web Panel        : {}", panel);
      LOGGER.info(null, "  Email (SMTP)     : {}", email);
      LOGGER.info(null, "  User Cache       : {} (lazy, DB-backed)", config != null ? config.cacheMaxUsers : "?");
      LOGGER.info(null, "  Config Directory : {}", configPath.toAbsolutePath());
      LOGGER.info(null, "  ----------------------------------------------------------");
      LOGGER.info(null, "  Security Summary:");
    LOGGER.info(null, "    - Password Hashing        : {}",
        config != null ? config.passwordRules.passwordHashAlgorithm : "?");
    LOGGER.info(null, "    - 2FA (TOTP)              : {}",
        config != null && config.session.authentication.allowTOTPSupport ? "ENABLED" : "disabled");
    LOGGER.info(null, "    - Sessions                : {}",
        config != null && config.session.enableSessions ? "enabled" : "disabled");
    LOGGER.info(null, "    - Session IP Lock         : {}",
        config != null && config.session.sessionFromSameIPOnly ? "enabled" : "disabled");
    LOGGER.info(null, "    - Proxy/VPN Blocking      : {}",
        config != null && !config.session.authentication.allowProxyUsers ? "enabled" : "disabled");
    LOGGER.info(null, "    - Max Login Attempts      : {}",
        config != null ? config.session.authentication.maxLoginAttempts : "?");
    LOGGER.info(null, "    - Account Locking         : {}",
        config != null && config.session.accountLock.enabled ? "enabled" : "disabled");
    LOGGER.info(null, "    - Login Intelligence      : {}",
        config != null && config.session.intelligence.enabled ? "enabled" : "disabled");
    LOGGER.info(null, "    - Combat Log Punishment   : {}",
        config != null && config.session.combatLog.enabled ? "enabled" : "disabled");
    LOGGER.info(null, "    - Safe Operators (limbo)  : {}",
        config != null && config.lobby.safeOperators ? "enabled" : "disabled");
      LOGGER.info(null, "  ----------------------------------------------------------");
      LOGGER.info(null, "  AuthCore started in {} ms", System.currentTimeMillis() - startedAtMs);
      LOGGER.info(null, "==============================================================");
      LOGGER.info(null, " ");
    }

    // Startup validation warnings (visible early so misconfiguration is obvious)
    if (config != null) {
      String gameVersion = net.ded3ec.compat.Compat.getGameVersion();
      boolean tested =
          gameVersion.startsWith("1.16.")
              || gameVersion.startsWith("1.17.")
              || gameVersion.startsWith("1.18.")
              || gameVersion.startsWith("1.19.")
              || gameVersion.startsWith("1.20.")
              || gameVersion.startsWith("1.21.")
              || gameVersion.startsWith("26.");
      if (!tested && config.logging.showUntestedVersionWarning)
        LOGGER.warn(
            false,
            "Minecraft {} is not in the officially tested set (1.16-1.21 / 26.1-26.2). The "
                + "mod uses version-agnostic APIs, so it should work - but please report any "
                + "issue!",
            gameVersion);

      if ("md5".equalsIgnoreCase(config.passwordRules.passwordHashAlgorithm))
        LOGGER.warn(
            false,
            "password-hash-algorithm is 'md5' which is cryptographically broken! "
                + "Strongly recommend switching to 'argon2' or 'bcrypt'.");

      // Online-mode servers must keep the secure chat profile disabled so clients without
      // one (cracked / modded / hybrid offline players) can still join and chat - the secure
      // profile requirement otherwise kicks them right after login. Only shown once the real
      // server mode was detected as online (at boot the mode is not known yet).
      if (serverOnlineMode != null && serverOnlineMode)
        LOGGER.warn(
            false,
            "server.properties online-mode=true - keep enable-secure-profile=false in "
                + "server.properties so players without a secure chat profile (offline/"
                + "modded clients) can still join and chat.");

      if (config.debugMode)
        LOGGER.warn(false, "debug-mode is enabled - this increases console log spam!");
    }
  }

  /** Graceful shutdown of background I/O workers. */
  public static void shutdown() {
    IO_EXECUTOR.shutdownNow();
    try {
      IO_EXECUTOR.awaitTermination(2, TimeUnit.SECONDS);
    } catch (InterruptedException err) {
      Thread.currentThread().interrupt();
    }
  }
}
