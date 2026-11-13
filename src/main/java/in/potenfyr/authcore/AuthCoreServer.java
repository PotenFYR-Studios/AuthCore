package in.potenfyr.authcore;

import in.potenfyr.authcore.network.EmailSender;
import in.potenfyr.authcore.network.WebPanel;
import in.potenfyr.authcore.security.Security;
import in.potenfyr.authcore.util.Database;
import in.potenfyr.authcore.util.Registry;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import in.potenfyr.authcore.models.Config;
import in.potenfyr.authcore.models.Messages;
import in.potenfyr.authcore.util.Logger;
import net.minecraft.server.MinecraftServer;

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
  public static final String MOD_VERSION = "1.0.3";
  public static final Logger LOGGER = new Logger(MOD_ID);
  public static final Path configPath =
      in.potenfyr.authcore.compat.Compat.getConfigDir().resolve("authcore");

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
    public static volatile MinecraftServer server;

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
    //
    // NOT when AuthCore verifies Mojang sessions itself (offline server + premium auto-login, no
    // proxy): the flags on such a server were set by a genuine verification, and clearing them on
    // every boot sent every returning premium player back to the register prompt after the first
    // restart ("it says verified but I am still in the limbo"). A flag can no longer bypass
    // anything there - auto-login needs a verified session on THAT join.
    if (!online && !premiumFlagMigrationDone && !isPremiumVerificationEnabled()) {
      premiumFlagMigrationDone = true;
      try {
        int cleared = in.potenfyr.authcore.util.Database.downgradePremiumAccounts();
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

  public static boolean isServerDetected() {
    return serverOnlineMode != null;
  }

  /**
   * UUIDs whose Minecraft profile was verified by the server's OWN Mojang session
   * authentication during login (captured by the login mixin). AuthCore performs no Mojang
   * API calls for premium detection - this marker is the single source of truth, and it is
   * only ever set on online-mode servers where vanilla authenticated the profile.
   */
  /**
   * Premium-verification markers: profile uuid -> the network Connection the verification belongs
   * to. A marker is CONNECTION-BOUND: on an offline server every client claiming a name gets the
   * same offline uuid, so a uuid-only marker set by a genuine premium player's handshake could be
   * consumed by a DIFFERENT (cracked) connection racing the same name to the join - a premium
   * auto-login account takeover. {@link #UNBOUND} marks the vanilla online-mode path, where the
   * join itself is already session-verified by the server.
   */
  private static final java.util.Map<java.util.UUID, java.lang.ref.WeakReference<Object>>
      PREMIUM_VERIFIED = new java.util.concurrent.ConcurrentHashMap<>();

  private static final Object UNBOUND = new Object();

  /** Records that the server's own (online-mode) session authentication verified this profile. */
  public static void markPremiumVerified(java.util.UUID uuid) {
    markPremiumVerifiedBound(uuid, UNBOUND);
  }

  /** Records a verification that belongs to exactly this network connection. */
  public static void markPremiumVerified(java.util.UUID uuid, Object connection) {
    if (connection == null) return; // unbound offline verification: never trusted (fail safe)
    markPremiumVerifiedBound(uuid, connection);
  }

  private static void markPremiumVerifiedBound(java.util.UUID uuid, Object binding) {
    if (uuid == null) return;
    // Bounded: the marker is consumed on login, but a flood of join handshakes must not
    // accumulate markers for connections that never complete.
    if (PREMIUM_VERIFIED.size() > 4096) PREMIUM_VERIFIED.clear();
    PREMIUM_VERIFIED.put(uuid, new java.lang.ref.WeakReference<>(binding));
  }

  /** Legacy form: consumes an UNBOUND (vanilla online-mode) marker only. */
  public static boolean consumePremiumVerified(java.util.UUID uuid) {
    return consumePremiumVerified(uuid, null);
  }

  /**
   * Atomically CONSUMES the verification marker for this profile: true only when it was set for
   * THIS connection (or by the vanilla online-mode path). Consuming makes it single-use, so a second
   * client joining with the same name can never inherit it.
   */
  public static boolean consumePremiumVerified(java.util.UUID uuid, Object connection) {
    if (uuid == null) return false;
    java.lang.ref.WeakReference<Object> ref = PREMIUM_VERIFIED.remove(uuid);
    if (ref == null) return false;
    Object bound = ref.get();
    if (bound == UNBOUND) return true;
    return bound != null && connection != null && bound == connection;
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

  /**
   * The login encryption handshake could not be STARTED on this runtime (a server-side shape
   * problem, not a client's answer). Nobody can be verified then, so the "unverified session for a
   * premium account" rejection is switched off instead of locking every premium player out.
   */
  public static volatile boolean premiumHandshakeUnavailable = false;

  /** premium-real-uuid: verified premium players join an offline server with their Mojang UUID. */
  public static boolean isPremiumRealUuidEnabled() {
    return isPremiumVerificationEnabled() && config.session.authentication.premiumRealUuid;
  }

  /** premium-skins: verified premium players keep the offline UUID but get their Mojang skin. */
  public static boolean isPremiumSkinsEnabled() {
    return isPremiumVerificationEnabled() && config.session.authentication.premiumSkins;
  }

  /** Boots the mod - called by every loader entrypoint. Idempotent for safety. */
  public static void start() {
    if (started) return;
    started = true;
    long start = System.currentTimeMillis();

    LOGGER.debug(false, "AuthCoreServer.start() - boot sequence begins");

    Registry.register();

    // Load the per-server companion attestation key (generates on first boot, persists to disk)
    in.potenfyr.authcore.security.AttestationKeyManager.ensureLoaded();

    // Start the optional web admin panel (no-op when disabled or no token configured)
    in.potenfyr.authcore.network.WebPanel.start();

    // Start the cross-server security event bus (no-op when Redis is disabled)
    in.potenfyr.authcore.network.RedisManager.startEventSubscriber();

    // Start the honeypot listener (no-op when disabled)
    in.potenfyr.authcore.security.Honeypot.start();

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
      in.potenfyr.authcore.util.TaskScheduler.getInstance()
          .setInterval(
              () -> in.potenfyr.authcore.security.Backups.runAutomaticBackup(),
              config.session.backup.intervalHours * 3600_000L);

    // Rotating server announcements
    if (config.lobby.announcements != null && !config.lobby.announcements.isEmpty())
      in.potenfyr.authcore.util.TaskScheduler.getInstance()
          .setInterval(
              () -> in.potenfyr.authcore.security.Announcements.rotateAndBroadcast(),
              Math.max(config.lobby.announcementIntervalSec, 10) * 1000L);
  }

  /** Prints a structured startup banner (version, environment and security summary). */
  private static void printStartupBanner(long startedAtMs) {
    String mcVersion = in.potenfyr.authcore.compat.Compat.getGameVersion();
    String loaderVersion = in.potenfyr.authcore.compat.Compat.getLoaderVersion();
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
        config != null && in.potenfyr.authcore.network.EmailSender.isEnabled() ? "enabled" : "disabled";

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
      String gameVersion = in.potenfyr.authcore.compat.Compat.getGameVersion();
      // FUTURE-SNAPSHOT AWARE: any 1.x line and any modern (>=26) major is part of the
      // tested set - a future "27.0" or "26.9" snapshot must not be flagged untested just
      // because a hardcoded prefix list was never extended.
      boolean tested = isTestedMinecraftVersion(gameVersion);
      if (!tested && config.logging.showUntestedVersionWarning)
        LOGGER.warn(
            false,
            "Minecraft {} is not in the officially tested set (1.16-1.21 / 26.1-26.3). The "
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

      // Forwarding invariants are enforced on every config load, before hooks register.
    }
  }

  /**
   * Whether a Minecraft version string belongs to the tested set: every {@code 1.x} line
   * plus every modern major ({@code >= 26}, covering all future 26.x/27.x+ snapshots of the
   * unobfuscated era).
   */
  public static boolean isTestedMinecraftVersion(String gameVersion) {
    if (gameVersion == null || gameVersion.isBlank()) return false;
    if (gameVersion.startsWith("1.")) return true;

    // Weekly snapshots (e.g. 26w09a, 24w45a, 23w12a)
    java.util.regex.Matcher snap =
        java.util.regex.Pattern.compile("^(\\d{2})w\\d{2}[a-z]$").matcher(gameVersion);
    if (snap.find()) {
      int year = Integer.parseInt(snap.group(1));
      return year >= 23;
    }

    // Modern major and semantic/pre/rc snapshots (e.g. 26.1, 26.2, 26.2-pre1, 26.1-rc2, 27.x)
    java.util.regex.Matcher major =
        java.util.regex.Pattern.compile("^(\\d+)").matcher(gameVersion);
    if (major.find()) {
      try {
        return Integer.parseInt(major.group(1)) >= 23;
      } catch (NumberFormatException ignored) {
      }
    }
    return false;
  }

  /** Graceful shutdown of background I/O workers. */
  public static void shutdown() {    IO_EXECUTOR.shutdownNow();
    try {
      IO_EXECUTOR.awaitTermination(2, TimeUnit.SECONDS);
    } catch (InterruptedException err) {
      Thread.currentThread().interrupt();
    }
  }
}
