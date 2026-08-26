package net.ded3ec.models;

import net.ded3ec.network.RedisManager;
import net.ded3ec.util.Logger;

import com.google.gson.JsonObject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.ded3ec.AuthCoreServer;
import net.ded3ec.util.Database;
import net.ded3ec.security.Encrypter;
import net.ded3ec.network.McApiManager;
import net.ded3ec.security.Security;
import net.ded3ec.util.TaskScheduler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;

/**
 * The in-memory model of one player account.
 *
 * <p>Every online player has one User instance that tracks their state (premium or standard,
 * in lobby or not, session timestamps, lock state, risk data) and mirrors the account to the
 * database. Users are loaded lazily from the database on first touch and cached, so a server
 * with 100k+ accounts only keeps the online and recently used ones in memory.
 */
public class User {

  /** Collection of users (thread-safe; accessed from netty and server threads). */
  public static Map<UUID, User> users = new ConcurrentHashMap<>();

  /** Lowercase-username index (O(1) lookups instead of scanning {@link #users}). */
  private static final Map<String, User> byLowerName = new ConcurrentHashMap<>();

  /** Precomputed lowercase username (set at construction; avoids per-lookup toLowerCase). */
  public transient String lowerName = null;

  /** UUID value of the minecraft player. */
  public UUID uuid;

  /** Username of the minecraft player. */
  public String username;

  /** Password of the user authenticated in authCore. */
  public String password;

  /** 2FA Secret for each user generated while registering. */
  public String authSecret;

  /** IPv4 Address of the minecraft player joined in the server. */
  public String ipAddress;

  /** Server Game Packet Listener of the minecraft player joined in the server. */
  public ServerGamePacketListenerImpl connection;

  /** Password encryption used to encrypt password in the db. */
  public String passwordEncryption;

  /** Backup recovery codes (comma separated) for account recovery. */
  public String recoveryCodes;

  /** Email address used for login alerts and password recovery. */
  public String email;

  /** Display nickname chosen by the player (/account nickname). */
  public String nickname;

  /** Network-based device fingerprint (hash of IP + country) for anti account-sharing. */
  public String deviceFingerprint;

  /** Timestamp until which the account is "trusted" (skips captcha). */
  public volatile long trustedUntilMs = 0;

  /** Linked Discord account id (Discord account linking). */
  public String discordId;

  /** Timestamp until which the account is locked after too many failed logins. */
  public volatile long lockUntilMs = 0;

  /** Last country the account was used from (login intelligence). */
  public String lastLoginCountry;

  /** Last IP the account was used from (login intelligence). */
  public String lastLoginIp;

  /** Transient login risk score (0-100) computed at join. */
  public int riskScore = 0;

  /** Transient flag: player completed the captcha challenge for this session. */
  public boolean captchaVerified = false;

  /** Timestamp of user creation for authCore. */
  public long userCreatedMs;

  /** Timestamp of last authentication of user. */
  public volatile long lastAuthenticatedMs = 0;

  /** Timestamp of last damage received to user. */
  public volatile long lastCombatDetectMs = 0;

  /** Combat Detection of the user. */
  public volatile boolean isInCombatPenalty = false;

  /** Timestamp of last registered by the user. */
  public long registeredAtMs;

  /** Timestamp of last kicked by the server. */
  public volatile long lastKickedMs = 0;

  /** Count of kicked user by the server. */
  public volatile int kickAttempts = 0;

  /** Lobby restriction violations this limbo session (resets on login/register/lock). */
  public volatile int violationCount = 0;

  /** Timestamp of the last movement-warning message (throttles the chat spam). */
  public volatile long lastMovementWarningMs = 0;

  /** Increments and returns the lobby violation counter. */
  public int incrementViolations() {
    return ++violationCount;
  }

  /** Json data from GeoIP search via api. */
  public volatile JsonObject geoIpData;

  /** Lobby Instance for the user model; Queue mode. */
  public Lobby lobby = new Lobby(this);

  /**
   * Supplier edition for minecraft server. Null-safe: the world supplier can resolve to null
   * while the player is mid-join (loader-dependent), so fall back to the reflective player
   * server lookup before giving up.
   */
  public Supplier<MinecraftServer> server =
      () -> {
        ServerLevel level = this.world != null ? this.world.get() : null;
        if (level != null) {
          MinecraftServer ms = level.getServer();
          if (ms != null) return ms;
        }
        ServerPlayer p = this.player != null ? this.player.get() : null;
        return p != null ? net.ded3ec.compat.Compat.getServer(p) : null;
      };

  /** Supplier edition for if user is active in the server! */
  public volatile boolean isActive = false;

  /** Supplier edition for the world in the server! */
  public Supplier<ServerLevel> world =
      () ->
          this.connection != null
              ? net.ded3ec.compat.Compat.playerLevel(this.connection.player)
              : null;

  /** Supplier edition for the player data in the server! */
  public Supplier<ServerPlayer> player =
      () -> this.connection != null ? this.connection.player : null;

  /** Supplier edition for if the user is registered successfully in authCore! */
  public Supplier<Boolean> isRegistered = () -> !StringUtils.isBlank(this.password);

  /** Supplier edition for if the user is online-mode! */
  public boolean isPremium;

  /** Supplier edition for if the user is in lobby/queue! */
  public Supplier<Boolean> isInLobby = () -> Lobby.users.get(this.username) != null;

  /** Supplier edition for if the user is a bedrock/java! */
  public Supplier<Boolean> isBedrock = () -> McApiManager.isBedrockPlayer(uuid);

  /**
   * Supplier edition for if the user's session is active! Checks if sessions are enabled, last
   * authentication is recent, and within timeout.
   */
  public Supplier<Boolean> isActiveSession =
      () ->
          (this.lastAuthenticatedMs > 0)
              && (AuthCoreServer.config.session.enableSessions)
              && ((System.currentTimeMillis() - this.lastAuthenticatedMs)
                  < AuthCoreServer.config.session.timeoutMs);

  /**
   * Supplier edition for if the user is authenticated! True if online and not in lobby, or offline
   * but has active session.
   */
  public Supplier<Boolean> isAuthenticated =
      () ->
          ((this.isActive && !this.isInLobby.get())
              || (!this.isActive && this.isActiveSession.get()));

  /**
   * Supplier edition for if the user joined via proxy! Checks GeoIP organization against known
   * proxy/VPN keywords.
   */
  public Supplier<Boolean> isProxy =
      () -> {
        if (!(this.geoIpData != null && this.geoIpData.get("org") != null)) return false;

        String[] keywords = {
          "vpn",
          "proxy",
          "hosting",
          "datacenter",
          "tor",
          "cloud",
          "network",
          "m247",
          "digitalocean",
          "hetzner",
          "ovh",
          "amazon",
          "google cloud",
          "linode"
        };

        String value = this.geoIpData.get("org").getAsString();

        for (String keyword : keywords) if (value.contains(keyword)) return true;

        return false;
      };

  /** Supplier edition for the user joined from country! */
  public Supplier<String> country =
      () ->
          (this.geoIpData != null && this.geoIpData.get("CountryName") != null)
              ? this.geoIpData.get("CountryName").getAsString()
              : null;

  /** Supplier edition for the user joined from country's code! */
  public Supplier<String> countryCode =
      () ->
          (this.geoIpData != null && this.geoIpData.get("continentCode") != null)
              ? this.geoIpData.get("continentCode").getAsString()
              : null;

  /** Login attempts count on authentication! */
  public volatile int loginAttempts = 0;

  /** Session Timeout timer task instance! */
  private int sessionTimeoutId;

  /**
   * User Instance creation with starter values.
   *
   * @param uuid the player's UUID
   * @param username the player's username
   * @param userCreatedMs timestamp of user creation
   * @param premium whether the user is premium
   */
  public User(UUID uuid, String username, long userCreatedMs, boolean premium) {
    this.uuid = uuid;
    this.username = username;
    this.lowerName = username != null ? username.toLowerCase(Locale.ROOT) : null;
    this.userCreatedMs = userCreatedMs;
    this.isPremium = premium;

    User.users.put(this.uuid, this);
    if (this.lowerName != null) byLowerName.put(this.lowerName, this);
  }

  /**
   * Load user configuration and initialization of user model. Users are loaded LAZILY from the
   * database on demand (join/login/whois) and cached in a bounded in-memory cache - a server
   * with 100k+ registered accounts only keeps the online + recently touched users in memory.
   */
  public static void load() {
    if (!Database.connect()) return;
    Database.load();
    // No bulk fetch - users are loaded on demand to keep memory flat for large databases.
    AuthCoreServer.LOGGER.info(
        true, "User cache initialized (lazy loading - users are fetched from the DB on demand).");
  }

  /** Maximum in-memory users before the cache prunes idle entries (configurable). */
  private static int CACHE_MAX_USERS = 20_000;

  /** Last access timestamp per cached user (for LRU-style eviction). */
  private static final Map<UUID, Long> lastAccess = new ConcurrentHashMap<>();

  /** Serializes cache-miss DB fetches so only one canonical User instance exists per account. */
  private static final Object CACHE_LOCK = new Object();

  /** Applies the configured cache size (called after config load). */
  public static void applyCacheLimit() {
    if (AuthCoreServer.config != null && AuthCoreServer.config.cacheMaxUsers > 0)
      CACHE_MAX_USERS = AuthCoreServer.config.cacheMaxUsers;
  }

  /** Throttled last-access touch: the map put (allocation) happens at most once/minute. */
  private static void touch(UUID uuid) {
    long now = System.currentTimeMillis();
    Long last = lastAccess.get(uuid);
    if (last == null || now - last > 60_000L) {
      lastAccess.put(uuid, now);
      if (users.size() > CACHE_MAX_USERS) pruneCache();
    }
  }

  /** Evicts offline users that have not been touched for 30 minutes (bounds memory). */
  private static void pruneCache() {
    long cutoff = System.currentTimeMillis() - 30 * 60 * 1000L;
    users.entrySet().removeIf(
        e -> {
          User u = e.getValue();
          if (u == null) return true;
          if (u.isActive || u.isInLobby.get()) return false; // never evict online users
          Long last = lastAccess.get(e.getKey());
          return last == null || last < cutoff;
        });
    lastAccess.entrySet().removeIf(e -> !users.containsKey(e.getKey()));
  }

  /**
   * O(1) hot-path lookup by UUID only - no strings, no scans, no DB. The per-packet mixin
   * guards (movement, clicks, chat, ticks) use this; the throttled touch keeps the
   * last-access map from allocating on every packet.
   */
  public static @Nullable User getUser(UUID uuid) {
    if (uuid == null) return null;
    return User.users.get(uuid);
  }

  /** O(1) hot-path lookup from the player object (no name string allocation). */
  public static @Nullable User getUser(net.minecraft.server.level.ServerPlayer player) {
    return player == null ? null : getUser(player.getUUID());
  }

  /** O(1) hot-path lookup from a base player entity (mixin guards on Player). */
  public static @Nullable User getUser(net.minecraft.world.entity.player.Player player) {
    return player == null ? null : getUser(player.getUUID());
  }

  /**
   * Retrieves a {@link User} by username or UUID, depending on the authentication config.
   *
   * <p>With {@code lookUpByUsername} enabled, the search is case-insensitive (offline-mode
   * names are). Otherwise the user is looked up by {@code uuid} in the {@code User.users}
   * map, falling back to a case-insensitive username search on a miss.
   *
   * <p>Cache misses load lazily from the database, so the in-memory cache stays small even
   * for databases with 100k+ registered users.
   *
   * @param username the username string to match against {@link User#username}
   * @param uuid the unique identifier used as a key in {@code User.users}
   * @return the matching {@link User} if found; otherwise {@code null}
   */
  public static @Nullable User getUser(String username, UUID uuid) {
    if (migrationBlockedGuard()) return null;
    if (AuthCoreServer.config.session.authentication.lookUpByUsername)
      return getUserByUsername(username);
    if (uuid != null) {
      User tempUser = User.users.get(uuid);
      if (tempUser != null) {
        touch(uuid);
        return tempUser;
      }
    }
    return getUserByUsername(username);
  }

  /**
   * Searches through all registered users and returns the one whose username matches the provided
   * {@code username} (case-insensitive). Cache misses are resolved lazily from the database.
   *
   * @param username the username string to match against {@link User#username}
   * @return the matching {@link User} if found; otherwise {@code null}
   */
  public static @Nullable User getUserByUsername(String username) {
    if (username == null) return null;

    String lower = username.toLowerCase(Locale.ROOT);

    // O(1) index hit (the index is maintained on insert/rename/delete and lazily validated).
    User indexed = byLowerName.get(lower);
    if (indexed != null) {
      if (users.containsKey(indexed.uuid) && lower.equals(indexed.lowerName)) {
        touch(indexed.uuid);
        return indexed;
      }
      byLowerName.remove(lower, indexed);
    }

    // Index miss -> scan (rare) then lazily load from the database.
    for (User user : User.users.values())
      if (user != null && lower.equals(user.lowerName)) {
        byLowerName.put(lower, user);
        touch(user.uuid);
        return user;
      }

    // Cache miss -> fetch from the database (lazy loading keeps memory flat for 100k+ users).
    // The lock guarantees a single canonical User instance per account under concurrent access.
    synchronized (CACHE_LOCK) {
      // double-check under the lock (another thread may have loaded it meanwhile)
      for (User user : User.users.values())
        if (user != null && lower.equals(user.lowerName)) {
          byLowerName.put(lower, user);
          touch(user.uuid);
          return user;
        }
      User loaded = db.fetchByUsername(lower);
      if (loaded != null) byLowerName.put(lower, loaded);
      return loaded;
    }
  }

  /**
   * Re-keys this user in the in-memory map after its UUID changed (offline clients can join with
   * different UUIDs between sessions).
   */
  private void remapUuidKey(UUID oldUuid) {
    if (oldUuid == null || oldUuid.equals(this.uuid)) return;
    User.users.remove(oldUuid);
    User.users.put(this.uuid, this);
    if (this.lowerName != null) byLowerName.put(this.lowerName, this);
  }

  /**
   * Register user's connection details within minecraft server.
   *
   * @param connection the server play network handler
   */
  public void connect(ServerGamePacketListenerImpl connection) {
    if (connection == null || connection.player == null) return;

    this.connection = connection;

    AuthCoreServer.LOGGER.debug(
        true,
        "username: {} | Type: {} | Mode: {} | IP: {} | UUID: {} | Country: {}",
        this.username,
        this.isBedrock.get() ? "Bedrock" : "Java",
        this.isPremium ? "online-mode" : "offline-mode",
        connection.player.getIpAddress(),
        this.uuid,
        this.country.get());

    this.isActive = true;

    // GeoIP lookup runs asynchronously off the main thread (and is skipped entirely for
    // private/localhost addresses - no external calls for LAN servers). The country shown
    // in the join log above may still be null here - it resolves when this lookup finishes.
    if (!StringUtils.isBlank(this.ipAddress)) {
      String ip = this.ipAddress;
      AuthCoreServer.IO_EXECUTOR.execute(
          () -> {
            JsonObject json = McApiManager.geoIp(ip);
            if (json != null
                && json.has("status")
                && "success".equalsIgnoreCase(json.get("status").getAsString())) {
              this.geoIpData = json;
              AuthCoreServer.LOGGER.debug(
                  false,
                  "{} | GeoIP resolved: country={}, org={}",
                  this.username,
                  this.country.get(),
                  json.has("org") ? json.get("org").getAsString() : "?");
            }
          });
    }
  }

  /**
   * Register new user for authentication with password!
   *
   * @param player the server player entity
   * @param password the password to load
   */
  public void register(ServerPlayer player, String password) {
    this.passwordEncryption = AuthCoreServer.config.passwordRules.passwordHashAlgorithm;
    this.password = Encrypter.hash(this.passwordEncryption, password);

    if (this.password == null) {
      AuthCoreServer.LOGGER.error(
          false,
          "Failed to hash the password for '{}' with algorithm '{}'!",
          this.username,
          this.passwordEncryption);
      // Security fallback: never leave the player authenticated-but-unregistered in free roam.
      // Lock them into the lobby so they can retry /register - the premium auto-login paths
      // call register() and return without locking otherwise.
      if (this.isActive && !this.isInLobby.get()) this.lobby.lock();
      return;
    }

    if (player != null) this.ipAddress = player.getIpAddress();

    if (player != null && !this.uuid.equals(player.getUUID())) {
      AuthCoreServer.LOGGER.debug(
          true,
          "{}'s UUID has been changed from {} to {}!",
          this.username,
          this.uuid,
          player.getUUID());

      UUID oldUuid = this.uuid;
      this.uuid = player.getUUID();
      this.remapUuidKey(oldUuid);
    }

    this.registeredAtMs = System.currentTimeMillis();

    // Generate backup recovery codes
    this.recoveryCodes = Security.RecoveryCodes.generate(8);

    // Auto-whitelist support: add the registered account to the vanilla whitelist
    if (AuthCoreServer.config.session.autoWhitelist.enabled
        && this.server.get() != null) {
      boolean added =
          net.ded3ec.compat.Compat.addToWhitelist(
              this.server.get(), new com.mojang.authlib.GameProfile(this.uuid, this.username));
      AuthCoreServer.LOGGER.info(
          true,
          "Auto-whitelist: {} was {} to the whitelist.",
          this.username,
          added ? "added" : "NOT added (API unavailable)");
    }

    db.insert(this);

    if (AuthCoreServer.config.session.authentication.allowLoginAfterRegistration
        || (this.isPremium && AuthCoreServer.config.session.authentication.premiumAutoLogin))
      this.login(player);
    else if (this.isInLobby.get()) {
      this.lobby.unlock();
      AuthCoreServer.LOGGER.toKick(
          false, this.connection, AuthCoreServer.messages.promptUserReJoinAfterRegister);
    }
  }

  /**
   * Login function for authenticate user after successful result!
   *
   * @param player the server player entity
   */
  public void login(ServerPlayer player) {
    if (player == null) return;

    AuthCoreServer.LOGGER.debug(
        false,
        "{} | login() - path: {}",
        this.username,
        player.getClass().getSimpleName());

    this.loginAttempts = 0;
    this.lockUntilMs = 0;
    this.lastAuthenticatedMs = System.currentTimeMillis();
    this.lastKickedMs = 0;
    this.kickAttempts = 0;
    this.violationCount = 0;

    // Mark the account as trusted (reduces the human-verification score on rejoins -
    // it does NOT bypass verification; every login is still scored independently).
    if (AuthCoreServer.config.session.trusted.enabled
        && AuthCoreServer.config.session.trusted.bypassCaptchaHours > 0) {
      this.trustedUntilMs =
          System.currentTimeMillis()
              + AuthCoreServer.config.session.trusted.bypassCaptchaHours * 3600_000L;
      AuthCoreServer.LOGGER.debug(
          false,
          "{} | account marked trusted until {} (trusted signal)",
          this.username,
          this.trustedUntilMs);
    }

    // Server announcement for authenticated players
    if (AuthCoreServer.config.lobby.announcement.enabled
        && AuthCoreServer.config.lobby.announcement.text != null
        && !AuthCoreServer.config.lobby.announcement.text.isBlank()
        && this.connection != null) {
      String announcement =
          AuthCoreServer.config.lobby.announcement.text.replace("%player%", this.username);
      AuthCoreServer.LOGGER.toUser(
          true,
          this.connection,
          new net.ded3ec.models.Messages.ColTemplate() {
            {
              message.text = announcement;
              message.color = "YELLOW";
            }
          });
    }

    if (this.isInLobby.get()) this.lobby.unlock();

    if (!this.uuid.equals(player.getUUID())) {
      AuthCoreServer.LOGGER.debug(
          true,
          "{}'s UUID has been changed from {} to {}!",
          this.username,
          this.uuid,
          player.getUUID());

      UUID oldUuid = this.uuid;
      this.uuid = player.getUUID();
      this.remapUuidKey(oldUuid);
    }

    // Refresh the stored IP on EVERY successful login: the same-IP session check compares
    // against THIS value, so leaving it at the REGISTRATION address would permanently lock
    // out any player whose ISP rotates their IP after their first day.
    if (player.getIpAddress() != null && !player.getIpAddress().equals(this.ipAddress)) {
      this.ipAddress = player.getIpAddress();
      this.update("Last-login IP refreshed");
    }

    this.isInCombatPenalty = false;
    this.lastCombatDetectMs = 0;

    // Rotate the session token on every login (anti session-fixation); the raw token is
    // handed to the client companion which echoes it on the next join to resume the session.
    String freshToken = issueSessionToken();
    if (this.connection != null && freshToken != null)
      net.ded3ec.network.AuthInterop.sendCompanion(
          this.connection.player,
          net.ded3ec.security.ClientGuard.MSG_SESSION_TOKEN + "|" + freshToken);

    // Network-wide SSO: mirror the token to Redis so other servers trust this login.
    if (AuthCoreServer.config.session.sso.enabled && freshToken != null)
      net.ded3ec.network.RedisManager.publishSso(
          this.uuid, freshToken, AuthCoreServer.config.session.sso.sessionTtlMin);

    db.insert(this);

    // Network-wide session publication + cross-server event (no-op when Redis is disabled)
    net.ded3ec.network.RedisManager.publishEvent(
        "login", this.username, "logged in (server " + net.ded3ec.network.RedisManager.SERVER_ID + ")");
    net.ded3ec.network.RedisManager.publishSession(this.uuid, this.username);

    if (AuthCoreServer.config.session.enableSessions) {
      // Stop the PREVIOUS session's timeout first: a session resume calls login() again,
      // and without this the old timer stayed armed - it fired at the ORIGINAL deadline
      // and kicked freshly-authenticated players long before their real timeout.
      TaskScheduler.getInstance().stopTask(this.sessionTimeoutId);
      this.sessionTimeoutId =
          TaskScheduler.getInstance()
              .setTimeout(
                  () -> {
                    if (AuthCoreServer.config.session.enableSessions
                        && AuthCoreServer.config.session.kickAfterSessionTimeout
                        && this.isAuthenticated.get()
                        && this.isActive
                        && this.connection != null)
                      AuthCoreServer.LOGGER.toKick(
                          false, this.connection, AuthCoreServer.messages.promptUserSessionExpired);
                  },
                  AuthCoreServer.config.session.timeoutMs);
    }

    AuthCoreServer.LOGGER.debug(true, "{} have been logged in successfully!", this.username);

    // Tell other mods / the proxy that this player is now authenticated. Centralized here so
    // EVERY login path broadcasts (command login/register, premium auto-login, session resume,
    // deferred premium verification, SSO) - previously the non-command paths stayed silent.
    net.ded3ec.network.AuthInterop.broadcast(player, true);

    // Single human verification: observe the player's behavior after login; only
    // challenge when it is clearly bot-like (ghost pattern / high risk). Genuine players
    // are never bothered. Centralized here so every login path is covered.
    net.ded3ec.security.ActionCaptcha.onLogin(player);

    // Optional third-party integrations (DiscordSRV link import etc.) - best-effort no-ops
    // when the mods are not installed.
    net.ded3ec.integration.ModIntegrations.onAuthSuccess(player, this);

    // Record the login in the login history (centralized so every path is accounted for).
    try {
      logLogin(this, player.getIpAddress(), this.country.get(), "success", this.riskScore);
    } catch (Exception ignored) {
      // login history is best-effort
    }
  }

  /**
   * Logout the user session from the AuthCoreServer and Minecraft server!
   *
   * @param payload the kick message template
   */
  public void logout(net.ded3ec.models.Messages.KickTemplate payload) {
    this.lastAuthenticatedMs = 0;

    // Per-session secrets must NOT survive a logout: a satisfied second factor and the
    // companion session token belong to THIS authenticated session only. Keeping them
    // would let the next (unauthenticated) session inherit MFA step-up privileges or
    // token-based resume without any proof of identity.
    this.mfaVerified = false;
    this.sessionTokenHash = null;
    this.loginAttempts = 0;

    TaskScheduler.getInstance().stopTask(this.sessionTimeoutId);
    net.ded3ec.network.RedisManager.removeSession(this.uuid);
    net.ded3ec.network.RedisManager.publishEvent("logout", this.username, "logged out");

    // Notify other mods / the proxy that this player is no longer authenticated
    // (idempotent - the leave handler also broadcasts on disconnect).
    net.ded3ec.network.AuthInterop.broadcast(this.player.get(), false);

    AuthCoreServer.LOGGER.debug(true, "{}'s session has been terminated!", this.username);

    if (this.isActive) AuthCoreServer.LOGGER.toKick(false, this.connection, payload);
  }

  /**
   * Kick user from the server! (alt for logout)
   *
   * @param payload the kick message template
   * @param args additional arguments for the message
   */

  /** Hash of the current session token (raw token is only ever handed to the client). */
  public transient volatile String sessionTokenHash = null;

  /** True when the current session satisfied a second factor (MFA step-up). */
  public transient volatile boolean mfaVerified = false;

  /**
   * Wrong-second-factor attempts since the last success (per session, transient). A small
   * cap destroys the session - without it the ~1M six-digit code space is brute-forceable
   * because in-memory intelligence counters do not survive restarts.
   */
  public transient volatile int failed2faAttempts = 0;

  /**
   * Issues a fresh 256-bit session token, stores its hash and returns the raw token.
   * The previous token is invalidated (session-fixation protection).
   */
  public String issueSessionToken() {
    byte[] raw = new byte[32];
    new java.security.SecureRandom().nextBytes(raw);
    String token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    this.sessionTokenHash = sha256(token);
    return token;
  }

  /** Constant-time verification of a claimed session token. */
  public boolean verifySessionToken(String claimed) {
    if (claimed == null || this.sessionTokenHash == null) return false;
    return constantTimeEquals(this.sessionTokenHash, sha256(claimed));
  }

  private static String sha256(String value) {
    try {
      byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      return sb.toString();
    } catch (Exception e) {
      return "";
    }
  }

  private static boolean constantTimeEquals(String a, String b) {
    if (a == null || b == null || a.length() != b.length()) return false;
    int diff = 0;
    for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
    return diff == 0;
  }
  public void kick(net.ded3ec.models.Messages.KickTemplate payload, Object... args) {
    this.lastKickedMs = System.currentTimeMillis();
    ++this.kickAttempts;

    AuthCoreServer.LOGGER.debug(true, "{} has been kicked/logout from the Server", this.username);

    if (this.isActive) AuthCoreServer.LOGGER.toKick(false, this.connection, payload, args);
  }

  /**
   * Update user data in the database!
   *
   * @param reason description of the update reason
   */
  public void update(String reason) {
    db.insert(this);
    AuthCoreServer.LOGGER.debug(
        true, "{} has been updated in the database for '{}'", this.username, reason);
  }

  /**
   * Delete user from cache and database! Callers are responsible for kicking/disconnecting the
   * player first if needed.
   *
   * @param reason description of the delete reason
   * @param delFromServer also remove the player entity from the running server
   */
  public void delete(String reason, boolean delFromServer) {
    if (this.isInLobby.get()) this.lobby.unlock();

    User.users.remove(this.uuid);
    if (this.lowerName != null) byLowerName.remove(this.lowerName, this);
    db.remove(this);

    if (delFromServer && this.server.get() != null && this.player.get() != null) {
      this.server.get().getPlayerList().remove(this.player.get());
      AuthCoreServer.LOGGER.debug(
          true, "{}'s Data has been deleted from the Server for '{}'", this.username, reason);
    } else
      AuthCoreServer.LOGGER.debug(
          true, "{} has been deleted from the database & caches for '{}'", this.username, reason);
  }

  /** Database Manager for users. */
  private static class db {

    /**
     * Fetch user data with the help of username from database!
     *
     * @param username the username to fetch
     * @return the user or null if not found
     */
    public static synchronized User fetch(String username) {
      try {
        if (!Database.connect()) return null;

        PreparedStatement statement =
            Database.connection.prepareStatement("SELECT * FROM users WHERE username = ?");
        statement.setString(1, username);
        ResultSet rs = statement.executeQuery();

        return rs.next() ? parse(rs) : null;
      } catch (SQLException err) {
        return AuthCoreServer.LOGGER.error(null, "User's database is facing an error:", err);
      }
    }

    /** Case-insensitive username lookup (lazy loading path). */
    public static synchronized User fetchByUsername(String lowerUsername) {
      try {
        if (!Database.connect()) return null;

        PreparedStatement statement =
            Database.connection.prepareStatement(
                "SELECT * FROM USERS WHERE LOWER(username) = LOWER(?) LIMIT 1");
        statement.setString(1, lowerUsername);
        ResultSet rs = statement.executeQuery();

        User user = rs.next() ? parse(rs) : null;
        if (user != null) User.touch(user.uuid);
        return user;
      } catch (SQLException err) {
        return AuthCoreServer.LOGGER.error(null, "User's database is facing an error:", err);
      }
    }

    /**
     * Insert user data with the help of username in database! Uses the dialect-aware upsert SQL
     * (works on SQLite, MySQL and PostgreSQL).
     *
     * @param user the user to insert
     */
    public static synchronized void insert(User user) {
      try {
        if (!Database.connect()) return;

        PreparedStatement statement = Database.connection.prepareStatement(Database.usersUpsertSql());

        statement.setString(1, user.uuid.toString());
        statement.setString(2, user.username);
        statement.setString(3, user.password);
        statement.setString(4, user.authSecret);
        statement.setString(5, user.isPremium ? "online-mode" : "offline-mode");
        statement.setString(6, user.ipAddress);
        statement.setString(7, user.passwordEncryption);
        statement.setLong(8, user.userCreatedMs);
        statement.setLong(9, user.registeredAtMs);

        statement.execute();

        // Persist the extended columns (recovery codes / account lock / email) on the same row
        try (PreparedStatement extra =
            Database.connection.prepareStatement(
                "UPDATE USERS SET recoveryCodes = ?, lockUntilMs = ?, email = ?, nickname = ?, deviceFingerprint = ?, trustedUntilMs = ?, discordId = ? WHERE uuid = ?")) {
          extra.setString(1, user.recoveryCodes);
          extra.setLong(2, user.lockUntilMs);
          extra.setString(3, user.email);
          extra.setString(4, user.nickname);
          extra.setString(5, user.deviceFingerprint);
          extra.setLong(6, user.trustedUntilMs);
          extra.setString(7, user.discordId);
          extra.setString(8, user.uuid.toString());

          extra.execute();
        }

        AuthCoreServer.LOGGER.debug(true, "{} has been added into the database!", user.username);
      } catch (SQLException err) {
        AuthCoreServer.LOGGER.error(null, "User's database is facing an error:", err);
      }
    }

    /**
     * Records a login attempt in the LOGIN_HISTORY table (dialect-aware, no-op on failure).
     *
     * @param user the user that attempted to log in
     * @param ip the IP the attempt came from
     * @param country the GeoIP country (may be null)
     * @param result "success" / "failed" / "blocked"
     * @param riskScore computed risk score (0-100)
     */
    public static synchronized void logLogin(User user, String ip, String country, String result, int riskScore) {
      try {
        if (!Database.connect()) return;

        PreparedStatement statement =
            Database.connection.prepareStatement(
                "INSERT INTO LOGIN_HISTORY(uuid, username, ip, country, mode, result, riskScore, ts)"
                    + " VALUES(?,?,?,?,?,?,?,?)");

        statement.setString(1, user.uuid.toString());
        statement.setString(2, user.username);
        statement.setString(3, ip);
        statement.setString(4, country);
        statement.setString(5, user.isPremium ? "online-mode" : "offline-mode");
        statement.setString(6, result);
        statement.setInt(7, riskScore);
        statement.setLong(8, System.currentTimeMillis());

        statement.execute();
      } catch (SQLException err) {
        AuthCoreServer.LOGGER.debug(null, "Failed to record login history:", err);
      }
    }

    /**
     * Remove user data with the help of username from database!
     *
     * @param user the user to remove
     */
    public static synchronized void remove(User user) {
      try {
        if (!Database.connect()) return;

        PreparedStatement statement =
            Database.connection.prepareStatement("DELETE FROM USERS WHERE username = ?");

        statement.setString(1, user.username);

        statement.execute();

        AuthCoreServer.LOGGER.debug(true, "{} has been removed from the database!", user.username);
      } catch (SQLException err) {
        AuthCoreServer.LOGGER.error(null, "User's database is facing an error:", err);
      }
    }

    /** Counts users matching a WHERE clause (database-backed). */
    public static synchronized long countWhere(String where) {
      try {
        if (!Database.connect()) return 0;
        try (Statement statement = Database.connection.createStatement();
            ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM USERS WHERE " + where)) {
          return rs.next() ? rs.getLong(1) : 0;
        }
      } catch (SQLException err) {
        AuthCoreServer.LOGGER.error(null, "User's database is facing an error:", err);
        return 0;
      }
    }

    /**
     * Counts users matching a parameterized WHERE clause (database-backed).
     *
     * <p>Every {@code ?} placeholder in {@code where} is bound positionally from
     * {@code params} through {@link java.sql.PreparedStatement}, so user-controlled values
     * (nicknames, emails...) can never alter the query - quote-doubling alone is NOT safe
     * on MySQL, where a trailing backslash escapes the closing quote.
     */
    public static synchronized long countWhereParams(String where, String... params) {
      try {
        if (!Database.connect()) return 0;
        try (java.sql.PreparedStatement statement =
            Database.connection.prepareStatement("SELECT COUNT(*) FROM USERS WHERE " + where)) {
          for (int i = 0; i < params.length; i++)
            statement.setString(i + 1, params[i]);
          try (ResultSet rs = statement.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
          }
        }
      } catch (SQLException err) {
        AuthCoreServer.LOGGER.error(null, "User's database is facing an error:", err);
        return 0;
      }
    }

    /**
     * Fetch all the users from the database.
     *
     * @return list of all users
     */
    public static ArrayList<User> fetchAll() {
      ArrayList<User> users = new ArrayList<>();

      if (!Database.connect()) return null;

      try (Statement statement = Database.connection.createStatement();
          ResultSet rs = statement.executeQuery("SELECT * FROM USERS")) {

        while (rs.next()) {
          User user = parse(rs);
          if (user != null) users.add(user);
        }

        return AuthCoreServer.LOGGER.debug(
            users, "{} users has been fetched from the database!", users.size());
      } catch (SQLException err) {
        return AuthCoreServer.LOGGER.error(null, "User's database is facing an error:", err);
      }
    }

    /** Fetches all registered usernames (used by admin list commands - memory friendly). */
    public static ArrayList<String> fetchAllUsernames() {
      ArrayList<String> names = new ArrayList<>();
      if (!Database.connect()) return names;
      try (Statement statement = Database.connection.createStatement();
          ResultSet rs = statement.executeQuery("SELECT username FROM USERS ORDER BY username")) {
        while (rs.next()) {
          String name = rs.getString(1);
          if (name != null) names.add(name);
        }
      } catch (SQLException err) {
        AuthCoreServer.LOGGER.error(null, "User's database is facing an error:", err);
      }
      return names;
    }

    /** Fetches usernames filtered by mode ("online-mode" / "offline-mode"). */
    public static ArrayList<String> fetchUsernamesByMode(String mode) {
      ArrayList<String> names = new ArrayList<>();
      if (!Database.connect()) return names;
      try (PreparedStatement statement =
          Database.connection.prepareStatement(
              "SELECT username FROM USERS WHERE mode = ? ORDER BY username")) {
        statement.setString(1, mode);
        try (ResultSet rs = statement.executeQuery()) {
          while (rs.next()) {
            String name = rs.getString(1);
            if (name != null) names.add(name);
          }
        }
      } catch (SQLException err) {
        AuthCoreServer.LOGGER.error(null, "User's database is facing an error:", err);
      }
      return names;
    }

    /**
     * Fetches a bounded, searchable player list for the web panel (memory friendly for 100k+
     * user databases).
     *
     * @param limit maximum rows (capped at 1000)
     * @param search optional username prefix filter (may be null)
     */
    public static ArrayList<User> fetchPlayers(int limit, String search) {
      ArrayList<User> players = new ArrayList<>();
      if (!Database.connect()) return players;

      int capped = Math.min(Math.max(limit, 1), 1000);
      try {
        String sql = "SELECT * FROM USERS";
        if (search != null && !search.isBlank()) sql += " WHERE LOWER(username) LIKE LOWER(?)";
        sql += " ORDER BY username LIMIT " + capped;

        PreparedStatement statement = Database.connection.prepareStatement(sql);
        if (search != null && !search.isBlank()) statement.setString(1, search.toLowerCase(Locale.ROOT) + "%");

        try (ResultSet rs = statement.executeQuery()) {
          while (rs.next()) {
            User user = parse(rs);
            if (user != null) players.add(user);
          }
        }
      } catch (SQLException err) {
        AuthCoreServer.LOGGER.error(null, "User's database is facing an error:", err);
      }
      return players;
    }

    /**
     * Parse raw database user data to user model class instance. Rows with missing/invalid data
     * are skipped instead of crashing the whole load.
     *
     * @param rs the result set
     * @return the parsed user, or {@code null} if the row is invalid
     * @throws SQLException if the result set cannot be read
     */
    private static User parse(ResultSet rs) throws SQLException {
      String uuidStr = rs.getString("uuid");
      String name = rs.getString("username");
      if (uuidStr == null || name == null) return null;

      UUID uuid;
      try {
        uuid = UUID.fromString(uuidStr);
      } catch (IllegalArgumentException err) {
        AuthCoreServer.LOGGER.warn(false, "Skipping user '{}' with invalid UUID '{}'", name, uuidStr);
        return null;
      }

      String mode = rs.getString("mode");
      boolean premium = mode != null && mode.equalsIgnoreCase("online-mode");

      User user = new User(uuid, name, rs.getLong("userCreatedMs"), premium);

      user.ipAddress = rs.getString("ipAddress");
      user.password = rs.getString("password");
      user.authSecret = rs.getString("authSecret");
      user.passwordEncryption = rs.getString("passwordEncryption");
      user.registeredAtMs = rs.getLong("registeredMs");
      user.recoveryCodes = rs.getString("recoveryCodes");
      user.lockUntilMs = rs.getLong("lockUntilMs");
      user.email = rs.getString("email");
      user.discordId = rs.getString("discordId");
      user.nickname = rs.getString("nickname");
      user.deviceFingerprint = rs.getString("deviceFingerprint");
      user.trustedUntilMs = rs.getLong("trustedUntilMs");

      return user;
    }

    /**
     * Fetches the most recent login history for a user (most recent first).
     *
     * @param uuid the user's UUID
     * @param limit maximum number of entries
     * @return formatted lines, or an empty list
     */
    public static ArrayList<String> fetchLoginHistory(UUID uuid, int limit) {
      ArrayList<String> lines = new ArrayList<>();
      try {
        if (!Database.connect()) return lines;

        PreparedStatement statement =
            Database.connection.prepareStatement(
                "SELECT ip, country, result, riskScore, ts FROM LOGIN_HISTORY WHERE uuid = ?"
                    + " ORDER BY ts DESC LIMIT ?");
        statement.setString(1, uuid.toString());
        statement.setInt(2, Math.min(Math.max(limit, 1), 50));

        try (ResultSet rs = statement.executeQuery()) {
          while (rs.next())
            lines.add(
                java.time.Instant.ofEpochMilli(rs.getLong("ts"))
                        + " | "
                        + rs.getString("result")
                        + " | risk "
                        + rs.getInt("riskScore")
                        + " | "
                        + rs.getString("ip")
                        + " | "
                        + rs.getString("country"));
        }
      } catch (SQLException err) {
        AuthCoreServer.LOGGER.debug(null, "Failed to fetch login history:", err);
      }
      return lines;
    }
  }

  /**
   * Checks whether the account is currently locked after too many failed logins.
   *
   * @return {@code true} if locked and the lock has not expired yet
   */
  public boolean isLocked() {
    return lockUntilMs > System.currentTimeMillis();
  }

  /**
   * Locks the account until now + duration. Persists to the database.
   *
   * @param durationMs how long to lock the account for
   */
  public void lock(long durationMs) {
    this.lockUntilMs = System.currentTimeMillis() + Math.max(durationMs, 1_000);
    net.ded3ec.network.RedisManager.publishEvent(
        "account-locked", this.username, "locked for " + durationMs + " ms");
    this.update("Account locked after repeated failed logins");
  }

  /** Unlocks the account and clears the failure counter. Persists to the database. */
  public void unlock() {
    if (this.lockUntilMs == 0) return;
    this.lockUntilMs = 0;
    this.loginAttempts = 0;
    this.update("Account unlocked");
  }

  /** True when the database schema could not be migrated - login/register are suspended. */
  private static volatile boolean blockedWarned = false;

  /** Short-circuit guard while the database migration is blocked. */
  private static boolean migrationBlockedGuard() {
    if (Database.migrationBlocked) {
      if (!blockedWarned) {
        blockedWarned = true;
        AuthCoreServer.LOGGER.error(
            false,
            "AuthCore suspended: database migration failed - fix the DB and restart the server.");
      }
      return true;
    }
    return false;
  }

  /** Fetches a bounded, searchable player list for admin/web panel use (public wrapper). */
  public static java.util.ArrayList<User> fetchPlayersPublic(int limit, String search) {
    return db.fetchPlayers(limit, search);
  }

  /** Total registered account count (database-backed - safe for 100k+ users). */
  public static long countRegistered() {
    return db.countWhere("mode IS NOT NULL");
  }

  /** Account count for the given mode (database-backed). */
  public static long countByMode(String mode) {
    return db.countWhereParams("mode = ?", mode);
  }

  /**
   * Whether another account already uses the given display nickname (case-insensitive).
   * Checks the in-memory cache first (online users), then the database.
   *
   * @param nickname the nickname to check
   * @param excludeUuid the player's own UUID (their own nickname never conflicts)
   */
  public static boolean isNicknameTaken(String nickname, UUID excludeUuid) {
    if (nickname == null || nickname.isBlank()) return false;

    for (User u : User.users.values())
      if (u != null
          && u.nickname != null
          && u.nickname.equalsIgnoreCase(nickname)
          && !u.uuid.equals(excludeUuid))
        return true;

    // Parameterized (never string-built): nicknames are player-controlled and MySQL
    // treats a trailing backslash as an escape even inside single quotes, so
    // quote-doubling alone is exploitable there.
    return db.countWhereParams(
            "LOWER(nickname) = LOWER(?) AND uuid <> ?",
            nickname,
            excludeUuid != null ? excludeUuid.toString() : "")
        > 0;
  }

  /** Records a login attempt in the login history (public wrapper). */
  public static void logLogin(
      User user, String ip, String country, String result, int riskScore) {
    db.logLogin(user, ip, country, result, riskScore);
  }

  /** Inserts an already-populated user into the database (used by external imports). */
  public static void importUser(User user) {
    if (user == null) return;
    db.insert(user);
  }

  /** Fetches the recent login history for a user (public wrapper). */
  public static java.util.ArrayList<String> fetchLoginHistory(UUID uuid, int limit) {
    return db.fetchLoginHistory(uuid, limit);
  }
}
