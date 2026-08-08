package net.ded3ec;

import net.ded3ec.AuthCoreServer;
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
import net.ded3ec.util.Registry;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.loader.api.FabricLoader;

public class AuthCoreServer implements DedicatedServerModInitializer {
  public static final String MOD_ID = "authcore";
  public static final String MOD_VERSION = "1.0.0";
  public static final Logger LOGGER = new Logger(MOD_ID);
  public static final Path configPath =
      FabricLoader.getInstance().getConfigDir().resolve("authcore");

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

  @Override
  public void onInitializeServer() {
    long start = System.currentTimeMillis();

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
  }

  /** Starts the automated backup and rotating-announcement tasks (server tick based). */
  private void startMaintenanceTasks() {
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
  private void printStartupBanner(long startedAtMs) {
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
      LOGGER.info(null, "  AuthCore - The Fortress Framework for Fabric Servers");
    if (showSummary) {
      LOGGER.info(null, "==============================================================");
      LOGGER.info(null, "  Version          : {}", MOD_VERSION);
      LOGGER.info(null, "  Minecraft        : {}", mcVersion);
      LOGGER.info(null, "  Fabric Loader    : {}", loaderVersion);
      LOGGER.info(null, "  Server Mode      : {}", config != null ? config.session.serverMode : "?");
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
              || gameVersion.startsWith("1.21.");
      if (!tested && config.logging.showUntestedVersionWarning)
        LOGGER.warn(
            false,
            "Minecraft {} is not in the officially tested set (1.16-1.21). The mod uses "
                + "version-agnostic APIs, so it should work - but please report any issue!",
            gameVersion);

      if ("online".equalsIgnoreCase(config.session.serverMode))
        LOGGER.warn(
            false,
            "serverMode is 'online'. Players will be auto-authenticated as premium accounts - "
                + "set session.server-mode to 'offline' on cracked/offline servers!");

      if ("md5".equalsIgnoreCase(config.passwordRules.passwordHashAlgorithm))
        LOGGER.warn(
            false,
            "password-hash-algorithm is 'md5' which is cryptographically broken! "
                + "Strongly recommend switching to 'argon2' or 'bcrypt'.");

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
