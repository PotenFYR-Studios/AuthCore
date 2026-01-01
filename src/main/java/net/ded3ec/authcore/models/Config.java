package net.ded3ec.authcore.models;

import java.util.Set;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

public class Config {

  @Comment(
      """
                  Internal config version used for automated migrations.
                  • Do NOT edit this manually unless specifically instructed by the developer.
                  • Default: "1.0.0\"""")
  public String version = "1.0.0";

  @Comment(
      """
                  Enables detailed debug logging to the console.
                  • Useful for troubleshooting issues.
                  • Recommended: false in production to reduce log spam.
                  • Default: false""")
  public boolean debugMode = false;

  @Comment(
      """
                  Settings for player sessions, authentication flow, and security thresholds.
                  • Changes here directly affect core authentication behaviour.
                  • Handle with care.""")
  public Session session = new Session();

  @Comment(
      """
                  Password complexity and security policy rules.
                  • Enforced on registration and password changes.
                  • Strong rules are recommended for better account security.""")
  public PasswordRules passwordRules = new PasswordRules();

  @Comment(
      """
                  Database backend configuration.
                  • SQLite is recommended for small-to-medium servers (simple, no external setup).
                  • MySQL is available for larger or multi-server setups.""")
  public Database database = new Database();

  @Comment(
      """
                  Restrictions applied to unauthenticated ("lobby") players.
                  • Strong restrictions improve security by limiting what unauthentic players can do.
                  • Recommended to keep most options disabled.""")
  public Lobby lobby = new Lobby();

  @Comment(
      """
                  Permission settings for all AuthCore commands.
                  • Supports both LuckPerms nodes and vanilla permission levels (op-level).""")
  public Commands commands = new Commands();

  @ConfigSerializable
  public static class Commands {
    public UserCommands user = new UserCommands();

    public AdminCommands admin = new AdminCommands();
  }

  @ConfigSerializable
  public static class UserCommands {

    @Comment(
        """
                      Permissions for the /login command.
                      • Usually required for all players.""")
    public CommandPermissions login =
        new CommandPermissions() {
          {
            luckPermsNode = "authcore.user.login";
            permissionsLevel = 0;
          }
        };

    @Comment(
        """
                      Permissions for the /account logout command.
                      • Allows authenticated players to log out.""")
    public CommandPermissions logout =
        new CommandPermissions() {
          {
            luckPermsNode = "authcore.user.logout";
            permissionsLevel = 0;
          }
        };

    @Comment(
        """
                      Permissions for the /register command.
                      • Required for new/cracked players to create an account.""")
    public CommandPermissions register =
        new CommandPermissions() {
          {
            luckPermsNode = "authcore.user.load";
            permissionsLevel = 0;
          }
        };

    @Comment(
        """
                      Permissions for the /account unregister command.
                      • Allows players to delete their own account.""")
    public CommandPermissions unregister =
        new CommandPermissions() {
          {
            luckPermsNode = "authcore.user.unregister";
            permissionsLevel = 0;
          }
        };

    @Comment(
        """
                      Permissions for the /account set-password <new-password> command.
                      • Allows authenticated players to change their password.""")
    public CommandPermissions changepassword =
        new CommandPermissions() {
          {
            luckPermsNode = "authcore.user.changepassword";
            permissionsLevel = 0;
          }
        };
  }

  @ConfigSerializable
  public static class AdminCommands {

    @Comment(
        """
                      Permissions for /authcore reload.
                      • Reloads the plugin configuration without restarting.""")
    public CommandPermissions reload =
        new CommandPermissions() {
          {
            luckPermsNode = "authcore.admin.reload";
            permissionsLevel = 3;
          }
        };

    @Comment(
        """
                      Permissions for /authcore delete player <player>.
                      • Permanently removes a player's account from the database.""")
    public CommandPermissions deletePlayer =
        new CommandPermissions() {
          {
            luckPermsNode = "authcore.admin.deleteplayer";
            permissionsLevel = 3;
          }
        };

    @Comment(
        """
                      Permissions for /authcore list players.
                      • Lists all registered accounts.""")
    public CommandPermissions listPlayers =
        new CommandPermissions() {
          {
            luckPermsNode = "authcore.admin.listPlayers";
            permissionsLevel = 3;
          }
        };

    @Comment(
        """
                      Permissions for /authcore list online-players.
                      • Lists currently connected premium/online-mode players.""")
    public CommandPermissions listOnlineModePlayers =
        new CommandPermissions() {
          {
            luckPermsNode = "authcore.admin.listOnlineModePlayers";
            permissionsLevel = 3;
          }
        };

    @Comment(
        """
                      Permissions for /authcore list offline-players.
                      • Lists currently connected cracked/offline-mode players.""")
    public CommandPermissions listOfflineModePlayers =
        new CommandPermissions() {
          {
            luckPermsNode = "authcore.admin.listOfflineModePlayers";
            permissionsLevel = 3;
          }
        };

    @Comment(
        """
                      Permissions for /authcore destroy-session <player>.
                      • Forces a player to re-authenticate by destroying their active session.""")
    public CommandPermissions destroyPlayerSession =
        new CommandPermissions() {
          {
            luckPermsNode = "authcore.admin.destroyPlayerSession";
            permissionsLevel = 3;
          }
        };

    @Comment(
        """
                      Permissions for /authcore set-password <player> <new-password>.
                      • Admin-forced password change for a player.""")
    public CommandPermissions setPlayerPassword =
        new CommandPermissions() {
          {
            luckPermsNode = "authcore.admin.setPlayerPassword";
            permissionsLevel = 3;
          }
        };

    @Comment(
        """
                      Permissions for /authcore whois [<username>] [<uuid>] [<player>].
                      • Shows detailed account information for a player.""")
    public CommandPermissions whoisUsername =
        new CommandPermissions() {
          {
            luckPermsNode = "authcore.admin.whoisUsername";
            permissionsLevel = 3;
          }
        };

    @Comment(
        """
                      Permissions for /authcore set-mode offline <player> <new-password>.
                      • Forces a player into cracked/offline authentication mode.""")
    public CommandPermissions setOfflineModePlayer =
        new CommandPermissions() {
          {
            luckPermsNode = "authcore.admin.setOfflineModePlayer";
            permissionsLevel = 3;
          }
        };

    @Comment(
        """
                      Permissions for /authcore set-mode online <player>.
                      • Forces a player into premium/online authentication mode.""")
    public CommandPermissions setOnlineModePlayer =
        new CommandPermissions() {
          {
            luckPermsNode = "authcore.admin.setOnlineModePlayer";
            permissionsLevel = 3;
          }
        };

    @Comment(
        """
                      Permissions for /authcore set-spawn <dimension-key> <x-cord> <y-cord> <z-cord>.
                      • Sets the default spawn location used by lobby teleport configs.""")
    public CommandPermissions setSpawnLocation =
        new CommandPermissions() {
          {
            luckPermsNode = "authcore.admin.setSpawnLocation";
            permissionsLevel = 3;
          }
        };
  }

  @ConfigSerializable
  public static class CommandPermissions {

    @Comment(
        """
                      LuckPerms permission node for this command.
                      • Example: "authcore.admin.reload"
                      • Ignored if LuckPerms is not present.""")
    public String luckPermsNode = "";

    @Comment(
        """
                      Vanilla/OP permission level required (0-4).
                      • Used when LuckPerms is not loaded.
                      • 0 = all players, 1 = moderators, 2 = gamemasters, 3 = admins, 4 = owners""")
    public int permissionsLevel = 0;
  }

  @ConfigSerializable
  public static class Session {

    @Comment(
        """
                      Authentication behaviour and security options.
                      • Most options should stay at secure defaults unless you have a specific reason to change them.""")
    public Authentication authentication = new Authentication();

    @Comment(
        """
                      How long an authenticated session remains valid (in milliseconds).
                      • Default: 3600000 ms (60 minutes)
                      • Reasonable range: 1200000–10800000 ms""")
    public int timeoutMs = 60 * 60 * 1000;

    @Comment(
        """
                      Cooldown period after a player is kicked for too many failed login attempts (ms).
                      • Helps prevent brute-force attacks.
                      • Default: 120000 ms (2 minute)""")
    public int cooldownAfterKickMs = 2 * 60 * 1000;

    @Comment(
        """
                    Skip combat detection for unauthorized players.
                    • If true, players can disconnect without being flagged.
                    • Default: false
                    """)
    public boolean skipCombatDetection = false;

    @Comment(
        """
                    Combat detection timeout (milliseconds).
                    • Defines how long a player is considered in combat after damage.
                    • Default: 3000 ms (3 seconds)
                    """)
    public long combatTimeout = 3 * 1000;

    @Comment(
        """
                      Interval between login reminder messages sent to unauthenticated players (ms).
                      • Default: 8000 ms (8 seconds)
                      • Suggested: 10000–30000 ms to avoid being too spammy""")
    public int messageReminderIntervalMs = 8 * 1000;

    @Comment(
        """
                      Persist sessions across reconnects.
                      • Strongly recommended to keep enabled.
                      • Default: true""")
    public boolean enableSessions = true;

    @Comment(
        """
                      Immediately kick players when their session expires while online.
                      • false = more user-friendly (they can re-login instantly).
                      • Default: false""")
    public boolean kickAfterSessionTimeout = false;

    @Comment(
        """
                      Require new sessions to come from the same IP as the original login.
                      • Protects against session hijacking.
                      • Strongly recommended to keep enabled.
                      • Default: true""")
    public boolean sessionFromSameIPOnly = true;

    @ConfigSerializable
    public static class Authentication {

      @Comment(
          """
                              Maximum failed login attempts before the player is kicked.
                              • Default: 8
                              • Adjust lower for stricter security.""")
      public int maxLoginAttempts = 8;

      @Comment(
          """
                              Allow Bedrock/Geyser/Floodgate players to join and authenticate.
                              • Default: false (enable only if you support Bedrock clients)""")
      public boolean allowBedrockPlayers = false;

      @Comment(
          """
                              Allow Unique Usernames only into the Server
                              • Default: false (Server supports multiple same username with different uuid)""")
      public boolean lookUpByUsername = false;

      @Comment(
          """
                              Automatically log in players with legitimate premium (Mojang) accounts without requiring a password (Note: premiumAutoRegister needs to be enabled for smooth auth for 'new' premium members!).
                              • Recommended for hybrid online/offline servers.
                              • Default: true""")
      public boolean premiumAutoLogin = true;

      @Comment(
          """
                              Generate Random Password for legitimate premium (Mojang) accounts.
                              • Recommended for hybrid online/offline servers.
                              • Default: true""")
      public boolean premiumAutoRegister = true;

      @Comment(
          """
                              Allow connections from known proxy/VPN services.
                              • Disabling improves security by blocking many bot attacks.
                              • Default: false""")
      public boolean allowProxyUsers = false;

      @Comment(
          """
                              Treat unknown usernames as cracked/offline-mode by default.
                              • Typical setting for cracked or hybrid servers.
                              • Default: true""")
      public boolean offlineModeByDefault = true;

      @Comment(
          """
                              Require password confirmation during registration (prevents typos).
                              • Strongly recommended.
                              • Default: true""")
      public boolean registerPasswordConfirmation = true;

      @Comment(
          """
                              Automatically log the player in immediately after successful registration.
                              • Improves user experience.
                              • Default: true""")
      public boolean allowLoginAfterRegistration = true;

      @Comment(
          """
                              Allow cracked players to load using a premium (paid) username.
                              • Disabling prevents username squatting.
                              • Default: false""")
      public boolean allowCrackedPremiumNames = false;

      @Comment(
          """
                              Prevent the same account name from being registered from multiple locations at once.
                              • Recommended for security.
                              • Default: true""")
      public boolean blockDuplicateRegister = true;

      @Comment(
          """
                               Prevent the same account name from being authenticated from multiple locations at once.
                               • Recommended for security.
                               • Default: true""")
      public boolean blockDuplicateSession = true;
    }
  }

  @ConfigSerializable
  public static class PasswordRules {

    @Comment(
        """
                      Uppercase letter (A-Z) requirements.
                      • Default: enabled = true, min = 1""")
    public PasswordRule upperCase =
        new PasswordRule() {
          {
            min = 1;
            max = 10;
          }
        };

    @Comment(
        """
                      Lowercase letter (a-z) requirements.
                      • Default: enabled = true, min = 3""")
    public PasswordRule lowerCase =
        new PasswordRule() {
          {
            min = 3;
            max = 10;
          }
        };

    @Comment(
        """
                      Digit (0-9) requirements.
                      • Default: enabled = true, min = 4""")
    public PasswordRule digits =
        new PasswordRule() {
          {
            min = 4;
            max = 10;
          }
        };

    @Comment(
        """
                      Overall password length requirements.
                      • Automatically calculated from the sum of other rules' min/max by default.
                      • You can override the defaults if needed.""")
    public PasswordRule length =
        new PasswordRule() {
          {
            min =
                PasswordRules.this.upperCase.min
                    + PasswordRules.this.lowerCase.min
                    + PasswordRules.this.digits.min;
            max =
                PasswordRules.this.upperCase.max
                    + PasswordRules.this.lowerCase.max
                    + PasswordRules.this.digits.max;
          }
        };

    @Comment(
        """
                      Allow players to reuse a previously used password when changing it.
                      • Disabling improves security.
                      • Default: false""")
    public boolean allowReuse = false;

    @Comment(
        """
                      Password hashing algorithm.
                      • Recommended: "argon2" (most secure modern option)
                      • Available options:
                        - "argon2"   : Memory-hard, highly resistant to GPU cracking (default)
                        - "bcrypt"   : Proven adaptive hash
                        - "scrypt"   : Memory-hard alternative
                        - "pbkdf2"   : Standard key-derivation function
                        - "sha-512" / "sha-256" : Fast cryptographic hashes (less ideal for passwords)
                        - "md5"      : Insecure – do not use""")
    public String passwordHashAlgorithm = "argon2";

    @ConfigSerializable
    public static class PasswordRule {

      @Comment(
          """
                              Enable or disable enforcement of this rule.
                              • Default: true for most rules""")
      public boolean enabled = true;

      @Comment(
          """
                              Minimum count required for this character type.
                              • Default varies per rule""")
      public int min = 1;

      @Comment(
          """
                              Maximum count allowed for this character type.
                              • Prevents excessively long passwords in one category.
                              • Default: 10""")
      public int max = 10;
    }
  }

  @ConfigSerializable
  public static class Database {

    @Comment(
        """
                      SQLite database file name (stored in the plugin config directory).
                      • Simple flat-file database – no external server required.
                      • Default: "authCore-db.sqlite\"""")
    public String sqlite = "authCore-db.sqlite";

    @Comment(
        """
                      MySQL/MariaDB remote database configuration.
                      • Use for larger servers or when sharing data across multiple instances.""")
    public mysqlDatabase mysql = new mysqlDatabase();
  }

  @ConfigSerializable
  public static class mysqlDatabase {

    @Comment(
        """
                      Enable MySQL instead of SQLite.
                      • Requires correct credentials and reachable server.
                      • Default: false""")
    public boolean enabled = false;

    @Comment(
        """
                      MySQL server host address.
                      • Example: "localhost" or "db.example.com\"""")
    public String host = "";

    @Comment(
        """
                      MySQL server port.
                      • Standard MySQL port is 3306.
                      • Default here: 3364 (change if needed)""")
    public int port = 3364;

    @Comment(
        """
                      Database/schema name.""")
    public String database = "";

    @Comment(
        """
                      Database username.""")
    public String username = "";

    @Comment(
        """
                      Database password (stored in plain text – protect the config file).""")
    public String password = "";

    @Comment(
        """
                      Use SSL/TLS encryption for the database connection.
                      • Recommended for remote databases.
                      • Default: false""")
    public boolean ssl = false;
  }

  @ConfigSerializable
  public static class Lobby {

    @Comment(
        """
                      Teleport settings for unauthenticated players on first join or registration (limbo).
                      • Commonly used to force players into a registration area.""")
    public TeleportConfig limboConfig = new TeleportConfig();

    @Comment(
        """
                      Dynamic login timeout adjustments based on player latency/ping.
                      • Gives high-ping players more time to authenticate.""")
    public Timeout timeout = new Timeout();

    @Comment(
        """
                      Maximum number of simultaneously lobby (unauthenticated) players.
                      • Helps prevent server overload from many unauthentic connections.
                      • Default: 50""")
    public int maxLobbyUsers = 50;

    @Comment(
        """
              Enforce operator safety.
              • Temporarily removes OP status until authentication succeeds.
              • Restores OP status after successful login if originally OP.
              • Default: true
              """)
    public boolean safeOperators = true;

    @Comment(
        """
                      Allow lobby players to use global chat.
                      • Usually disabled to prevent spam.
                      • Default: false""")
    public boolean allowChat = false;

    @Comment(
        """
                      Allow lobby players to execute commands (except auth commands).
                      • Default: false""")
    public boolean allowCommands = false;

    @Comment(
        """
                      List of commands affected by the whitelist/blacklist logic below.
                      • Always includes core auth commands by default.""")
    public Set<String> whitelistedCommands = Set.of("login", "account", "register");

    @Comment(
        """
                      Behaviour of the command list above:
                      • true  → listed commands are BLOCKED (blacklist mode)
                      • false → ONLY listed commands are ALLOWED (whitelist mode)
                      • Default: false (whitelist mode)""")
    public boolean useWhitelistAsBlacklist = false;

    @Comment(
        """
                      Allow basic movement (walking, jumping, sprinting) while lobby.
                      • Default: false""")
    public boolean allowMovement = false;

    @Comment(
        """
                      Allow right-click block interaction (doors, chests, buttons, etc.).
                      • Default: false""")
    public boolean allowBlockInteraction = false;

    @Comment(
        """
                      Allow breaking or placing blocks.
                      • Default: false""")
    public boolean allowBlockBreaking = false;

    @Comment(
        """
                      Allow attacking other players.
                      • Default: false""")
    public boolean allowAttackingPlayer = false;

    @Comment(
        """
                      Allow attacking hostile mobs.
                      • Default: false""")
    public boolean allowAttackingHostileMobs = false;

    @Comment(
        """
                      Allow attacking passive animals.
                      • Default: false""")
    public boolean allowAttackingAnimals = false;

    @Comment(
        """
                      Allow attacking villagers/traders.
                      • Default: false""")
    public boolean allowAttackingFriendlyMobs = false;

    @Comment(
        """
                      Allow attacking neutral mobs (endermen, piglins, etc.).
                      • Default: false""")
    public boolean allowAttackNeutralMobs = false;

    @Comment(
        """
                      Allow attacking mountable entities (horses, boats, etc.).
                      • Default: false""")
    public boolean allowAttackMountableEntity = false;

    @Comment(
        """
                      Allow attacking other entities (item frames, armor stands, etc.).
                      • Default: false""")
    public boolean allowAttackEntity = false;

    @Comment(
        """
                      Allow dropping items from inventory.
                      • Default: false""")
    public boolean allowItemDrop = false;

    @Comment(
        """
                      Allow picking up items from the ground.
                      • Default: false""")
    public boolean allowItemPickup = false;

    @Comment(
        """
                      Allow moving/rearranging items inside the inventory.
                      • Default: false""")
    public boolean allowItemMoving = false;

    @Comment(
        """
                      Allow using items (eating, shooting bows, etc.).
                      • Default: false""")
    public boolean allowItemUse = false;

    @Comment(
        """
                      Allow right-click interaction with other players.
                      • Default: false""")
    public boolean allowPlayerInteractWith = false;

    @Comment(
        """
                      Allow right-click interaction with animals.
                      • Default: false""")
    public boolean allowAnimalInteractWith = false;

    @Comment(
        """
                      Allow right-click interaction with mountable entities.
                      • Default: false""")
    public boolean allowMountableInteractWith = false;

    @Comment(
        """
                      Allow right-click interaction with miscellaneous entities.
                      • Default: false""")
    public boolean allowEntityInteractWith = false;

    @Comment(
        """
                      Allow right-click interaction with hostile mobs.
                      • Default: false""")
    public boolean allowHostileMobsInteractWith = false;

    @Comment(
        """
                      Allow right-click interaction with friendly/villager mobs.
                      • Default: false""")
    public boolean allowFriendlyMobsInteractWith = false;

    @Comment(
        """
                      Allow right-click interaction with neutral mobs.
                      • Default: false""")
    public boolean allowNeutralMobsInteractWith = false;

    @Comment(
        """
                      Make unauthenticated players invisible to authenticated players.
                      • Reduces visual clutter in spawn areas.
                      • Default: true""")
    public boolean invisibleUnauthorized = true;

    @Comment(
        """
                      Apply permanent blindness effect to lobby players.
                      • Forces focus on the login/registration prompt.
                      • Default: true""")
    public boolean applyBlindnessEffect = true;

    @Comment(
        """
                      Completely hide the player's inventory UI while lobby.
                      • Default: false""")
    public boolean hideInventory = false;

    @Comment(
        """
                      Allow mobs to damage lobby players.
                      • Usually disabled to prevent unfair deaths.
                      • Default: false""")
    public boolean allowMobDamage = false;

    @Comment(
        """
                      Force lobby players into Adventure mode.
                      • Prevents accidental block breaking/placing.
                      • Default: true""")
    public boolean forceAdventureMode = true;

    @Comment(
        """
                      Prevent all damage to lobby players from items/projectiles.
                      • Default: true""")
    public boolean preventDamage = true;

    @Comment(
        """
                      Block status effects from being applied to lobby players.
                      • Default: true""")
    public boolean preventStatusEffect = true;

    @Comment(
        """
                      Protect lobby players from damage by authenticated players.
                      • Default: true""")
    public boolean preventPlayerDamage = true;

    @ConfigSerializable
    public static class TeleportConfig {

      @Comment(
          """
                              Enable teleportation to the configured location.
                              • For limboConfig: usually true
                              • For hubConfig: optional""")
      public boolean enabled = true;

      @Comment("Only teleport the player on their first join (if enabled is true).")
      public boolean onlyOnFirstTime = true;

      @Comment(
          """
                              Destination location for the teleport.""")
      public Location location = new Location();

      @ConfigSerializable
      public static class Location {

        @Comment(
            """
                                    Target dimension/resource key.
                                    • Format: "minecraft:overworld", "minecraft:the_nether", etc.
                                    • Default: "minecraft:overworld\"""")
        public String dimension = "minecraft:overworld";

        @Comment(
            """
                                    X coordinate.
                                    • Default: 0.0""")
        public double x = 0;

        @Comment(
            """
                                    Y coordinate.
                                    • Default: 64.0""")
        public double y = 64;

        @Comment(
            """
                                    Z coordinate.
                                    • Default: 0.0""")
        public double z = 0;
      }
    }

    @ConfigSerializable
    public static class Timeout {

      @Comment(
          """
                              Enable dynamic login timeout based on player ping.
                              • Gives more time to players with higher latency.
                              • Default: true""")
      public boolean enabled = true;

      @Comment(
          """
                              Base login timeout for players with ping ≤ 200 ms.
                              • Default: 60000 ms (1 minute)""")
      public int timeInMs = 2 * 60 * 1000;

      @Comment(
          """
                              Login timeout for players with ping > 200 ms.
                              • Default: 120000 ms (2 minutes)""")
      public int timeoutAbove200LatencyMs = 3 * 60 * 1000;

      @Comment(
          """
                              Login timeout for players with ping > 400 ms.
                              • Default: 240000 ms (4 minutes)""")
      public int timeoutAbove400LatencyMs = 4 * 60 * 1000;

      @Comment(
          """
                              Login timeout for players with ping > 600 ms.
                              • Default: 480000 ms (8 minutes)""")
      public int timeoutAbove600LatencyMs = 5 * 60 * 1000;
    }
  }
}
