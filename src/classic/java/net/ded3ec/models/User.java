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
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;

/** User model class for the AuthCoreServer! */
public class User {

  /** Collection of users (thread-safe; accessed from netty and server threads). */
  public static Map<UUID, User> users = new ConcurrentHashMap<>();

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
  public ServerPlayNetworkHandler connection;

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

  /** Json data from GeoIP search via api. */
  public volatile JsonObject geoIpData;

  /** Lobby Instance for the user model; Queue mode. */
  public Lobby lobby = new Lobby(this);

  /** Supplier edition for minecraft server. */
  public Supplier<MinecraftServer> server =
      () -> this.world != null ? this.world.get().getServer() : null;

  /** Supplier edition for if user is active in the server! */
  public volatile boolean isActive = false;

  /** Supplier edition for the world in the server! */
  public Supplier<ServerWorld> world =
      () -> this.connection != null ? (net.minecraft.server.world.ServerWorld) this.connection.player.getEntityWorld() : null;

  /** Supplier edition for the player data in the server! */
  public Supplier<ServerPlayerEntity> player =
      () -> this.connection != null ? this.connection.player : null;

  /** Supplier edition for if the username is online-mode! */
  public Supplier<Boolean> isPremiumUsername = () -> McApiManager.getPremiumUuid(this.username) != null;

  /** Supplier edition for if the uuid is online-mode! */
  public Supplier<Boolean> isPremiumUuid = () -> McApiManager.getPremiumUsername(this.uuid) != null;

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
    this.userCreatedMs = userCreatedMs;
    this.isPremium = premium;

    User.users.put(this.uuid, this);
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

  private static void touch(UUID uuid) {
    lastAccess.put(uuid, System.currentTimeMillis());
    if (users.size() > CACHE_MAX_USERS) pruneCache();
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
   * Retrieves a {@link User} instance based on either username or UUID, depending on the
   * authentication configuration.
   *
   * <p>If {@code lookUpByUsername} is enabled, this method searches through all registered users
   * and returns the one whose username matches the provided {@code username} (case-insensitive, as
   * Minecraft offline-mode names are case-insensitive).
   *
   * <p>If {@code lookUpByUsername} is disabled, the method first looks up the user by their {@code
   * uuid} key in the {@code User.users} map. If no user is found for the given UUID, it falls back
   * to a case-insensitive username search.
   *
   * <p>Cache misses are resolved lazily from the database, so the in-memory cache stays small
   * even for databases with 100k+ registered users.
   *
   * @param username the username string to match against {@link User#username}
   * @param uuid the unique identifier used as a key in {@code User.users}
   * @return the matching {@link User} if found; otherwise {@code null}
   */
  public static @Nullable User getUser(String username, UUID uuid) {
    if (migrationBlockedGuard()) return null;
    if (AuthCoreServer.config.session.authentication.lookUpByUsername)
      return getUserByUsername(username);

    User tempUser = User.users.get(uuid);
    if (tempUser != null) {
      touch(uuid);
      return tempUser;
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
    for (User user : User.users.values())
      if (user != null && lower.equals(user.username.toLowerCase(Locale.ROOT))) {
        touch(user.uuid);
        return user;
      }

    // Cache miss -> fetch from the database (lazy loading keeps memory flat for 100k+ users).
    // The lock guarantees a single canonical User instance per account under concurrent access.
    synchronized (CACHE_LOCK) {
      // double-check under the lock (another thread may have loaded it meanwhile)
      for (User user : User.users.values())
        if (user != null && lower.equals(user.username.toLowerCase(Locale.ROOT))) {
          touch(user.uuid);
          return user;
        }
      return db.fetchByUsername(lower);
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
  }

  /**
   * Register user's connection details within minecraft server.
   *
   * @param connection the server play network handler
   */
  public void connect(ServerPlayNetworkHandler connection) {
    if (connection == null || connection.player == null) return;

    this.connection = connection;

    AuthCoreServer.LOGGER.debug(
        true,
        "username: {} | Type: {} | Mode: {} | IP: {} | UUID: {} | Country: {}",
        this.username,
        this.isBedrock.get() ? "Bedrock" : "Java",
        this.isPremium ? "Online" : "Offline",
        connection.player.getIp(),
        this.uuid,
        this.country.get());

    this.isActive = true;

    // GeoIP lookup runs asynchronously off the main thread (and is skipped entirely for
    // private/localhost addresses - no external calls for LAN servers).
    if (!StringUtils.isBlank(this.ipAddress)) {
      String ip = this.ipAddress;
      AuthCoreServer.IO_EXECUTOR.execute(
          () -> {
            JsonObject json = McApiManager.geoIp(ip);
            if (json != null
                && json.has("status")
                && "success".equalsIgnoreCase(json.get("status").getAsString()))
              this.geoIpData = json;
          });
    }
  }

  /**
   * Register new user for authentication with password!
   *
   * @param player the server player entity
   * @param password the password to load
   */
  public void register(ServerPlayerEntity player, String password) {
    this.passwordEncryption = AuthCoreServer.config.passwordRules.passwordHashAlgorithm;
    this.password = Encrypter.hash(this.passwordEncryption, password);

    if (this.password == null) {
      AuthCoreServer.LOGGER.error(
          false,
          "Failed to hash the password for '{}' with algorithm '{}'!",
          this.username,
          this.passwordEncryption);
      return;
    }

    if (player != null) this.ipAddress = player.getIp();

    if (player != null && !this.uuid.equals(player.getUuid())) {
      AuthCoreServer.LOGGER.debug(
          true,
          "{}'s UUID has been changed from {} to {}!",
          this.username,
          this.uuid,
          player.getUuid());

      UUID oldUuid = this.uuid;
      this.uuid = player.getUuid();
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
  public void login(ServerPlayerEntity player) {
    if (player == null) return;

    this.loginAttempts = 0;
    this.lockUntilMs = 0;
    this.lastAuthenticatedMs = System.currentTimeMillis();
    this.lastKickedMs = 0;
    this.kickAttempts = 0;

    // Mark the account as trusted (skips captcha on rejoins)
    if (AuthCoreServer.config.session.trusted.enabled
        && AuthCoreServer.config.session.trusted.bypassCaptchaHours > 0)
      this.trustedUntilMs =
          System.currentTimeMillis()
              + AuthCoreServer.config.session.trusted.bypassCaptchaHours * 3600_000L;

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

    if (!this.uuid.equals(player.getUuid())) {
      AuthCoreServer.LOGGER.debug(
          true,
          "{}'s UUID has been changed from {} to {}!",
          this.username,
          this.uuid,
          player.getUuid());

      UUID oldUuid = this.uuid;
      this.uuid = player.getUuid();
      this.remapUuidKey(oldUuid);
    }

    this.isInCombatPenalty = false;
    this.lastCombatDetectMs = 0;

    db.insert(this);

    // Network-wide session publication + cross-server event (no-op when Redis is disabled)
    net.ded3ec.network.RedisManager.publishEvent(
        "login", this.username, "logged in (server " + net.ded3ec.network.RedisManager.SERVER_ID + ")");
    net.ded3ec.network.RedisManager.publishSession(this.uuid, this.username);

    if (AuthCoreServer.config.session.enableSessions)
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

    AuthCoreServer.LOGGER.debug(true, "{} have been logged in successfully!", this.username);
  }

  /**
   * Logout the user session from the AuthCoreServer and Minecraft server!
   *
   * @param payload the kick message template
   */
  public void logout(net.ded3ec.models.Messages.KickTemplate payload) {
    this.lastAuthenticatedMs = 0;

    TaskScheduler.getInstance().stopTask(this.sessionTimeoutId);
    net.ded3ec.network.RedisManager.removeSession(this.uuid);
    net.ded3ec.network.RedisManager.publishEvent("logout", this.username, "logged out");
    AuthCoreServer.LOGGER.debug(true, "{}'s session has been terminated!", this.username);

    if (this.isActive) AuthCoreServer.LOGGER.toKick(false, this.connection, payload);
  }

  /**
   * Kick user from the server! (alt for logout)
   *
   * @param payload the kick message template
   * @param args additional arguments for the message
   */
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
    db.remove(this);

    if (delFromServer && this.server.get() != null && this.player.get() != null) {
      this.server.get().getPlayerManager().remove(this.player.get());
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
    return db.countWhere("mode = '" + mode.replace("'", "''") + "'");
  }

  /** Records a login attempt in the login history (public wrapper). */
  public static void logLogin(
      User user, String ip, String country, String result, int riskScore) {
    db.logLogin(user, ip, country, result, riskScore);
  }

  /** Fetches the recent login history for a user (public wrapper). */
  public static java.util.ArrayList<String> fetchLoginHistory(UUID uuid, int limit) {
    return db.fetchLoginHistory(uuid, limit);
  }
}
