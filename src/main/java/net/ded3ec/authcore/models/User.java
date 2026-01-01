package net.ded3ec.authcore.models;

import com.google.gson.JsonObject;
import io.netty.util.internal.StringUtil;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.ded3ec.authcore.AuthCore;
import net.ded3ec.authcore.utils.Database;
import net.ded3ec.authcore.utils.Logger;
import net.ded3ec.authcore.utils.Misc;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;

/** User model class for the AuthCore! */
public class User {

  /** Collection of users. */
  public static Map<UUID, User> users = new HashMap<>();

  /** UUID value of the minecraft player. */
  public UUID uuid;

  /** Username of the minecraft player. */
  public String username;

  /** Password of the user authenticated in authCore. */
  public String password;

  /** IPv4 Address of the minecraft player joined in the server. */
  public String ipAddress;

  /** Password encryption used to encrypt password in the db. */
  public String passwordEncryption;

  /** Timestamp of user creation for authCore. */
  public long userCreatedMs;

  /** Timestamp of last authentication of user. */
  public long lastAuthenticatedMs = 0;

  /** Timestamp of last damage received to user. */
  public long lastCombactDetectMs = 0;

  /** Combact Detection of the user. */
  public boolean isInCombactPenalty = false;

  /** Timestamp of last registered by the user. */
  public long registeredAtMs;

  /** Timestamp of last kicked by the server. */
  public long lastKickedMs = 0;

  /** Count of kicked user by the server. */
  public int kickAttempts = 0;

  /** Network handler managed in minecraft api. */
  public ServerPlayNetworkHandler handler;

  /** Json data from GeoIP search via api. */
  public JsonObject geoIpData;

  /** Lobby Instance for the user model; Queue mode. */
  public Lobby lobby = new Lobby(this);

  /** Supplier edition for minecraft server. */
  public Supplier<MinecraftServer> server =
      () -> this.handler != null ? this.handler.player.getEntityWorld().getServer() : null;

  /** Supplier edition for if user is active in the server! */
  public boolean isActive = false;

  /** Supplier edition for the world in the server! */
  public Supplier<ServerWorld> world =
      () -> this.handler != null ? this.handler.player.getEntityWorld() : null;

  /** Supplier edition for the player data in the server! */
  public Supplier<ServerPlayerEntity> player =
      () -> this.handler != null ? this.handler.player : null;

  /** Supplier edition for if the username is online-mode! */
  public Supplier<Boolean> isPremiumUsername = () -> Misc.getPreimumUuid(this.username) != null;

  /** Supplier edition for if the uuid is online-mode! */
  public Supplier<Boolean> isPremiumUuid = () -> Misc.getPremiumUsername(this.uuid) != null;

  /** Supplier edition for if the user is registered successfully in authCore! */
  public Supplier<Boolean> isRegistered = () -> !StringUtils.isBlank(this.password);

  /** Supplier edition for if the user is online-mode! */
  public boolean isPremium;

  /** Supplier edition for if the user is in lobby/queue! */
  public Supplier<Boolean> isInLobby = () -> Lobby.users.get(this.username) != null;

  /** Supplier edition for if the user is a bedrock/java! */
  public Supplier<Boolean> isBedrock = () -> Misc.isBedrockPlayer(uuid);

  /**
   * Supplier edition for if the user's session is active! Checks if sessions are enabled, last
   * authentication is recent, and within timeout.
   */
  public Supplier<Boolean> isActiveSession =
      () ->
          (this.lastAuthenticatedMs > 0)
              && (AuthCore.config.session.enableSessions)
              && ((System.currentTimeMillis() - this.lastAuthenticatedMs)
                  < AuthCore.config.session.timeoutMs);

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
  public int loginAttempts = 0;

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

  /** Load user configuration and initialization of user model! */
  public static void load() {

    if (!Database.connect()) return;
    else Database.load();

    ArrayList<User> dbUsers = db.fetchAll();

    if (dbUsers != null && !dbUsers.isEmpty())
      dbUsers.forEach(ctx -> User.users.put(ctx.uuid, ctx));
  }

  /**
   * Retrieves a {@link User} instance based on either username or UUID, depending on the
   * authentication configuration.
   *
   * <p>If {@code lookUpByUsername} is enabled, this method searches through all registered users
   * and returns the one whose internal {@code username} field matches the provided {@code
   * username}. This enforces uniqueness by username rather than UUID.
   *
   * <p>If {@code lookUpByUsername} is disabled, the method first looks up the user by their {@code
   * uuid} key in the {@code User.users} map. If no user is found for the given UUID, it falls back
   * to searching by username.
   *
   * @param username the username string to match against {@link User#username}
   * @param uuid the unique identifier used as a key in {@code User.users}
   * @return the matching {@link User} if found; otherwise {@code null}
   */
  public static @Nullable User getUser(String username, UUID uuid) {
    if (AuthCore.config.session.authentication.lookUpByUsername) return getUserByUsername(username);

    User tempUser = User.users.get(uuid);
    return (tempUser == null) ? getUserByUsername(username) : tempUser;
  }

  /**
   * Searches through all registered users and returns the one whose internal {@code username} field
   * matches the provided {@code username}.
   *
   * <p>This method is typically used when {@code lookUpByUsername} is enabled, or as a fallback
   * when UUID lookup fails.
   *
   * @param username the username string to match against {@link User#username}
   * @return the matching {@link User} if found; otherwise {@code null}
   */
  public static @Nullable User getUserByUsername(String username) {
    for (User user : User.users.values())
      if (user != null && username.equals(user.username)) return user;

    return null;
  }

  /**
   * Register user's connection details within minecraft server.
   *
   * @param handler the server play network handler
   */
  public void connect(ServerPlayNetworkHandler handler) {
    this.handler = handler;

    Logger.debug(
        true,
        "{} joined the server | Type: {} | Mode: {} | IP: {} | UUID: {} | Country: {}",
        this.username,
        this.isBedrock.get() ? "Bedrock" : "Java",
        this.isPremium ? "Online" : "Offline",
        handler.player.getIp(),
        this.uuid,
        this.country);

    this.isActive = true;

    if (!(StringUtil.isNullOrEmpty(this.ipAddress))) {
      JsonObject json = Misc.geoIp(this.ipAddress);

      if ((json != null && (json.get("status").getAsString().equalsIgnoreCase("success"))))
        this.geoIpData = json;
    }
  }

  /**
   * Register new user for authentication with password!
   *
   * @param player the server player entity
   * @param password the password to load
   */
  public void register(ServerPlayerEntity player, String password) {
    this.passwordEncryption = AuthCore.config.passwordRules.passwordHashAlgorithm;
    this.password = Misc.HashManager.hash(this.passwordEncryption, password);

    this.ipAddress = player.getIp();

    if (!this.uuid.equals(player.getUuid())) {
      Logger.debug(
          true,
          "{}'s UUID has been changed from {} to {}!",
          this.username,
          this.uuid,
          player.getUuid());

      this.uuid = player.getUuid();
      this.player.get().setUuid(player.getUuid());
    }

    this.registeredAtMs = System.currentTimeMillis();

    db.insert(this);

    if (AuthCore.config.session.authentication.allowLoginAfterRegistration
        || (this.isPremium && AuthCore.config.session.authentication.premiumAutoLogin))
      this.login(player);
    else if (this.isInLobby.get()) {
      this.lobby.unlock();
      Logger.toKick(false, this.handler, AuthCore.messages.promptUserReJoinAfterRegister);
    }
  }

  /**
   * Login function for authenticate user after successful result!
   *
   * @param player the server player entity
   */
  public void login(ServerPlayerEntity player) {

    this.loginAttempts = 0;
    this.lastAuthenticatedMs = System.currentTimeMillis();
    this.lastKickedMs = 0;
    this.kickAttempts = 0;

    if (this.isInLobby.get()) this.lobby.unlock();

    if (!this.uuid.equals(player.getUuid())) {
      Logger.debug(
          true,
          "{}'s UUID has been changed from {} to {}!",
          this.username,
          this.uuid,
          player.getUuid());

      this.uuid = player.getUuid();
      this.player.get().setUuid(player.getUuid());
    }

    this.isInCombactPenalty = false;
    this.lastCombactDetectMs = 0;

    db.insert(this);

    if (AuthCore.config.session.enableSessions)
      this.sessionTimeoutId =
          Misc.TaskScheduler.getInstance()
              .setTimeout(
                  () -> {
                    if (AuthCore.config.session.enableSessions
                        && AuthCore.config.session.kickAfterSessionTimeout
                        && this.isAuthenticated.get())
                      Logger.toKick(
                          false, this.handler, AuthCore.messages.promptUserSessionExpired);
                  },
                  AuthCore.config.session.timeoutMs);

    Logger.debug(true, "{} have been logged in successfully!", this.username);
  }

  /**
   * Logout the user session from the AuthCore and Minecraft server!
   *
   * @param payload the kick message template
   */
  public void logout(Messages.KickTemplate payload) {
    this.lastAuthenticatedMs = 0;

    Misc.TaskScheduler.getInstance().stopTask(this.sessionTimeoutId);
    Logger.debug(true, "{}'s session has been terminated!", this.username);

    if (this.isActive) Logger.toKick(false, this.handler, payload);
  }

  /**
   * Kick user from the server! (alt for logout)
   *
   * @param payload the kick message template
   * @param args additional arguments for the message
   */
  public void kick(Messages.KickTemplate payload, Object... args) {
    this.lastKickedMs = System.currentTimeMillis();
    ++this.kickAttempts;

    Logger.debug(true, "{} has been kicked/logout from the Server", this.username);

    if (this.isActive) Logger.toKick(false, this.handler, payload, args);
  }

  /**
   * Register new user for authentication with password!
   *
   * @param reason description of the update reason
   */
  public void update(String reason) {
    db.insert(this);

    Logger.debug(true, "{} has been updated in the database for '{}'", this.username, reason);
  }

  /**
   * Delete user from cache and database!
   *
   * @param reason description of the delete reason
   */
  public void delete(String reason, boolean delFromServer) {
    if (this.isInLobby.get()) this.lobby.unlock();

    if (this.isActive)
      this.kick(AuthCore.messages.promptUserDataDeleted, delFromServer ? "Server" : "Database");

    User.users.remove(this.uuid);
    db.remove(this);

    if (delFromServer) {
      this.server.get().getPlayerManager().remove(this.player.get());
      Logger.debug(
          true, "{}'s Data has been deleted from the Server for '{}'", this.username, reason);
    } else
      Logger.debug(
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
    public static User fetch(String username) {
      try {
        if (!Database.connect()) return null;

        PreparedStatement statement =
            Database.connection.prepareStatement("SELECT * FROM users WHERE username = ?");
        statement.setString(1, username);
        ResultSet rs = statement.executeQuery();

        return rs.next() ? parse(rs) : null;
      } catch (SQLException err) {
        return Logger.error(null, "User's SQLite database is facing an error:", err);
      }
    }

    /**
     * Insert user data with the help of username in database!
     *
     * @param user the user to insert
     */
    public static void insert(User user) {
      try {
        if (!Database.connect()) return;

        PreparedStatement statement =
            Database.connection.prepareStatement(
                """
                        INSERT OR REPLACE INTO USERS(
                                username,
                                uuid,
                                password,
                                mode,
                                ipAddress,
                                passwordEncryption,
                                userCreatedMs,
                                registeredMs
                        )
                        VALUES(?,?,?,?,?,?,?,?)
                        """);

        statement.setString(1, user.username);
        statement.setString(2, user.uuid.toString());
        statement.setString(3, user.password);
        statement.setString(4, user.isPremium ? "online-mode" : "offline-mode");
        statement.setString(5, user.ipAddress);
        statement.setString(6, user.passwordEncryption);
        statement.setLong(7, user.userCreatedMs);
        statement.setLong(8, user.registeredAtMs);

        statement.execute();

        Logger.debug(true, "{} has been added into the database!", user.username);
      } catch (SQLException err) {
        Logger.error(null, "User's SQLite database is facing an error:", err);
      }
    }

    /**
     * Remove user data with the help of username from database!
     *
     * @param user the user to remove
     */
    public static void remove(User user) {
      try {
        if (!Database.connect()) return;

        PreparedStatement statement =
            Database.connection.prepareStatement("DELETE FROM USERS WHERE username = ?");

        statement.setString(1, user.username);

        statement.execute();

        Logger.debug(true, "{} has been removed from the database!", user.username);
      } catch (SQLException err) {
        Logger.error(null, "User's SQLite database is facing an error:", err);
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

        while (rs.next()) users.add(parse(rs));

        return Logger.debug(users, "{} users has been fetched from the database!", users.size());
      } catch (SQLException err) {
        return Logger.error(null, "User's SQLite database is facing an error:", err);
      }
    }

    /**
     * Parse raw database user data to user model class instance.
     *
     * @param rs the result set
     * @return the parsed user
     * @throws SQLException if parsing fails
     */
    private static User parse(ResultSet rs) throws SQLException {
      User user =
          new User(
              UUID.fromString(rs.getString("uuid")),
              rs.getString("username"),
              rs.getLong("userCreatedMs"),
              rs.getString("mode").equalsIgnoreCase("online-mode"));

      user.ipAddress = rs.getString("ipAddress");
      user.password = rs.getString("password");
      user.passwordEncryption = rs.getString("passwordEncryption");
      user.registeredAtMs = rs.getLong("registeredMs");

      return user;
    }
  }
}
