package net.ded3ec.authcore.models;

import java.util.Set;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

public class Configs {

  @Comment(
      """

            Internal config version for automated migrations.
            • NEVER edit this manually unless instructed.
            • Default: 1.0.0""")
  public String version = "1.0.0";

  @Comment(
      """

            Enables verbose debug output in the console.
            • Helpful when diagnosing issues.
            • Default: false (keep disabled in production)""")
  public boolean debugMode = false;

  @Comment(
      """

            Settings related to player sessions, authentication flow, and security thresholds.
            • Adjust with care – impacts core functionality.
            • Severity: High""")
  public Session session = new Session();

  @Comment(
      """

            Rules enforcing password complexity and security policies.
            • Applied during registration and password changes.
            • Severity: High""")
  public PasswordRules passwordRules = new PasswordRules();

  @Comment(
      """

            Configuration for the chosen database backend.
            • SQLite is suggested for most small-to-medium servers.
            • Severity: Medium""")
  public Database database = new Database();

  @Comment(
      """

            Restrictions imposed on unauthenticated or jailed players.
            • Strong restrictions are advised for better security.
            • Severity: High""")
  public Lobby lobby = new Lobby();

  public Commands commands = new Commands();

  @ConfigSerializable
  public static class Commands {

    @Comment(
        """

                Base /login command permissions""")
    public Permissions login =
        new Permissions() {
          {
            luckPermsNode = "authCore.lobby.login";
            permissionsLevel = 0;
          }
        };

    @Comment(
        """

                Base /logout command permissions""")
    public Permissions logout =
        new Permissions() {
          {
            luckPermsNode = "authCore.lobby.logout";
            permissionsLevel = 0;
          }
        };

    @Comment(
        """

                Base /register command permissions""")
    public Permissions register =
        new Permissions() {
          {
            luckPermsNode = "authCore.lobby.register";
            permissionsLevel = 0;
          }
        };

    @Comment(
        """

                Base /password set <new-password> command permissions""")
    public Permissions changepassword =
        new Permissions() {
          {
            luckPermsNode = "authCore.user.changepassword";
            permissionsLevel = 0;
          }
        };

    @Comment(
        """

            Base /password set <target> <new-password> command permissions""")
    public Permissions changePasswordTarget =
        new Permissions() {
          {
            luckPermsNode = "authCore.admin.changepassword";
            permissionsLevel = 4;
          }
        };

    @Comment(
        """

            Base /transfer [cracked/premium] <new-password> command permissions""")
    public Permissions transferAccount =
        new Permissions() {
          {
            luckPermsNode = "authCore.user.transferaccount";
            permissionsLevel = 0;
          }
        };

    @Comment(
        """

                Base /reloadauthCore command permissions""")
    public Permissions reload =
        new Permissions() {
          {
            luckPermsNode = "authCore.admin.reload";
            permissionsLevel = 3;
          }
        };

    @ConfigSerializable
    public static class Permissions {

      @Comment("Base LuckPerms Group Node string (eg: authCore.admin.reload)")
      public String luckPermsNode = "";

      @Comment(
          """

                    Base Vanilla Permissions level (0-4)
                    (use if LuckPerms is not loaded)
                    • Owners: 4
                    • Admins: 3
                    • Game masters: 2
                    • Moderators: 1
                    • All: 0""")
      public int permissionsLevel = 0;
    }
  }

  @ConfigSerializable
  public static class Session {

    @Comment(
        """

                Authentication handling options (premium detection, proxy support, etc.).
                • Stick to secure defaults unless you have a specific need.
                • Severity: High""")
    public Authentication authentication = new Authentication();

    @Comment(
        """

                How long a session remains valid after authentication.
                • Default: 300000 ms (5 minutes)
                • Suggested range: 300000–600000 ms""")
    public int timeoutMs = 30 * 60 * 1000;

    @Comment(
        """

                Cooldown period after a player is kicked for failed login attempts.
                • Mitigates brute-force attacks.
                • Default: 60000 ms (1 minute)""")
    public int cooldownAfterKickMs = 60 * 1000;

    @Comment(
        """

                Frequency of login reminder messages sent to unauthenticated players.
                • Default: 8000 ms (8 seconds)
                • Suggested: 10000–30000 ms to avoid spam""")
    public int loginReminderIntervalMs = 8 * 1000;

    @Comment(
        """

                Persist sessions across player reconnects.
                • Default: true (highly recommended)""")
    public boolean enableSessions = true;

    @Comment(
        """

                Immediately kick players when their session expires while still online.
                • Default: false (gentler user experience)""")
    public boolean kickAfterSessionTimeout = false;

    @Comment(
        """

                Require subsequent sessions to originate from the same IP as the initial login.
                • Protects against session theft.
                • Default: true (strongly recommended)""")
    public boolean sessionFromSameIPOnly = true;

    @ConfigSerializable
    public static class Authentication {

      @Comment(
          """

                    Maximum failed login attempts before kicking the player.
                    • Default: 8
                    • Suggested: 3–10 depending on desired strictness""")
      public int maxLoginAttempts = 8;

      @Comment(
          """

                    Allow bedrock players via floodgate.
                    • Default: true (recommended for mixed-mode servers)""")
      public boolean allowBedrockPlayers = false;

      @Comment(
          """

                    Automatically authenticate legitimate premium (Mojang) accounts without requiring a password.
                    • Default: true (recommended for mixed-mode servers)""")
      public boolean premiumAutoLogin = true;

      @Comment(
          """

                    Permit connections originating from known proxies or VPN services.
                    • Default: false (better security)""")
      public boolean allowProxyUsers = false;

      @Comment(
          """

                    Treat unknown usernames as cracked/offline-mode by default.
                    • Default: true (typical for cracked servers)""")
      public boolean offlineModeByDefault = true;

      @Comment(
          """

                    Ask for password confirmation during registration.
                    • Avoids mistakes due to typos.
                    • Default: true (recommended)""")
      public boolean registerPasswordConfirmation = true;

      @Comment(
          """

                    Log the player in automatically right after successful registration.
                    • Default: true (improves UX)""")
      public boolean allowLoginAfterRegistration = true;

      @Comment(
          """

                    Allow cracked players to register using premium usernames.
                    • Default: false (prevents username squatting)""")
      public boolean allowCrackedPremiumNames = false;

      @Comment(
          """

                    Prevent the same account from being logged in from multiple locations simultaneously.
                    • Default: true (recommended)""")
      public boolean blockDuplicateLogin = true;
    }
  }

  @ConfigSerializable
  public static class PasswordRules {

    @Comment(
        """

                Requirements for uppercase letters (A-Z).
                • Default: enabled=true, min=1""")
    public PasswordRule upperCase =
        new PasswordRule() {
          {
            min = 1;
            max = 10;
          }
        };

    @Comment(
        """

                Requirements for lowercase letters (a-z).
                • Default: enabled=true, min=1""")
    public PasswordRule lowerCase =
        new PasswordRule() {
          {
            min = 3;
            max = 10;
          }
        };

    @Comment(
        """

                Requirements for numeric digits (0-9).
                • Default: enabled=true, min=1""")
    public PasswordRule digits =
        new PasswordRule() {
          {
            min = 4;
            max = 10;
          }
        };

    @Comment(
        """

                Overall password length constraints.
                • Default: enabled=true, min=8–12 is a good balance""")
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

                Permit reusing a previously set password.
                • Default: false (enhances security)""")
    public boolean allowReuse = false;

    @Comment(
        """

             Algorithm used for hashing passwords.
             • Default: "argon2" (modern and secure)
             • "argon2": Uses Argon2 hashing, a memory-hard function that's modern and highly secure.
             • "bcrypt": Uses Bcrypt hashing, an adaptive function that's robust against brute-force attacks.
             • "pbkdf2": Uses PBKDF2 hashing, a standard key derivation function that's robust and configurable.
             • "sha-256": Uses SHA-256 hashing, a fast cryptographic hash that's robust but not ideal for passwords.
             • "sha-512": Uses SHA-512 hashing, a fast cryptographic hash that's robust but not ideal for passwords.
             • "md5": Uses MD5 hashing, a fast hash that's robust but insecure for passwords.
             • "scrypt": Uses Scrypt hashing, a memory-hard function that's robust and efficient.
             """)
    public String passwordHashAlgorithm = "argon2";

    @ConfigSerializable
    public static class PasswordRule {

      @Comment(
          """

                    Whether this specific rule is enforced.
                    • Default: true for most rules""")
      public boolean enabled = true;

      @Comment(
          """

                    Minimum number required to satisfy the rule.
                    • Default: 1""")
      public int min = 1;

      @Comment(
          """

                    Maximum number allowed (helps prevent overly complex passwords).
                    • Default: 20""")
      public int max = 10;
    }
  }

  @ConfigSerializable
  public static class Database {

    @Comment(
        """

                File name for the SQLite database (stored in the mod config folder).
                • Default: 'authCore-db.sqlite'""")
    public String sqlite = "authCore-db.sqlite";

    @Comment(
        """

                Configuration for a remote/local MySQL backend.
                • Suitable for larger servers or shared hosting.""")
    public mysqlDatabase mysql = new mysqlDatabase();
  }

  @ConfigSerializable
  public static class mysqlDatabase {

    @Comment(
        """

                Enable usage of the MySQL database.
                • Default: false (requires proper setup)""")
    public boolean enabled = false;

    @Comment(
        """

                Database host address • Default: empty (must be filled when enabled)""")
    public String host = "";

    @Comment(
        """

                Database port • Default: 3364 (change to 3306 if using standard MySQL)""")
    public int port = 3364;

    @Comment(
        """

                Name of the database/schema • Default: empty""")
    public String database = "";

    @Comment(
        """

                Username for database authentication • Default: empty""")
    public String username = "";

    @Comment(
        """

                Password for database authentication • Default: empty""")
    public String password = "";

    @Comment(
        """

                Use SSL/TLS for the connection • Default: false""")
    public boolean ssl = false;
  }

  @ConfigSerializable
  public static class Lobby {

    @Comment(
        """

                Teleport behavior for newly registered or first-time joining players.
                • Often used to force a registration/limbo World.""")
    public TeleportConfig limboConfig = new TeleportConfig();

    @Comment(
        """

                Teleport behavior after successful authentication.
                • Can force players back to a spawn/hub until fully logged in.""")
    public TeleportConfig hubConfig =
        new TeleportConfig() {
          {
            enabled = false;
          }
        };

    @Comment(
        """

                Dynamic login timeout adjustments based on player ping/latency.
                • Provides fairness for high-latency connections.""")
    public Timeout timeout = new Timeout();

    @Comment(
        """

                Maximum number of players that can be jailed simultaneously.
                • Default: 30 (adjust based on server capacity)""")
    public int maxJailedUsers = 50;

    @Comment(
        """

                Permit jailed players to use global chat.
                • Default: false (prevents spam/abuse)""")
    public boolean allowChat = false;

    @Comment(
        """

                Permit jailed players to execute commands (except AuthCore auth commands).
                • Default: false""")
    public boolean allowCommands = false;

    @Comment(
        """

                List of commands affected by whitelist/blacklist logic.
                • Controlled by useWhitelistAsBlacklist below.""")
    public Set<String> whitelistedCommands = Set.of("login", "register", "logout");

    @Comment(
        """

                true  → listed commands are blocked (blacklist mode)
                false → only listed commands are allowed (whitelist mode)
                • Default: false""")
    public boolean useWhitelistAsBlacklist = false;

    @Comment(
        """

                Allow basic movement (walking, jumping, sprinting) while jailed.
                • Default: false""")
    public boolean allowMovement = false;

    @Comment(
        """

                Allow right-click interaction with blocks (doors, chests, etc.).
                • Default: false""")
    public boolean allowBlockInteraction = false;

    @Comment(
        """

                Allow breaking/placing blocks.
                • Default: false""")
    public boolean allowBlockBreaking = false;

    @Comment(
        """

                Allow attacking other players.
                • Default: false""")
    public boolean allowAttackingPlayer = false;

    @Comment(
        """

                Allow attacking hostile mobs (zombies, skeletons, etc.).
                • Default: false""")
    public boolean allowAttackingHostileMobs = false;

    @Comment(
        """

                Allow attacking passive animals (cows, pigs, etc.).
                • Default: false""")
    public boolean allowAttackingAnimals = false;

    @Comment(
        """

                Allow attacking friendly/villager-type mobs.
                • Default: false""")
    public boolean allowAttackingFriendlyMobs = false;

    @Comment(
        """

                Allow attacking neutral mobs (endermen, piglins, etc.).
                • Default: false""")
    public boolean allowAttackNeutralMobs = false;

    @Comment(
        """

                Allow attacking mountable entities (boat, camel, etc.).
                • Default: false""")
    public boolean allowAttackMountableEntity = false;

    @Comment(
        """

                Allow attacking other entities (item-frame, etc.).
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

                Allow rearranging/moving items within the inventory.
                • Default: false""")
    public boolean allowItemMoving = false;

    @Comment(
        """

                Allow using items (eating food, shooting bows, etc.).
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

                Allow right-click interaction with other entities.
                • Default: false""")
    public boolean allowEntityInteractWith = false;

    @Comment(
        """

                Allow right-click interaction with hostile mobs.
                • Default: false""")
    public boolean allowHostileMobsInteractWith = false;

    @Comment(
        """

                Allow right-click interaction with friendly mobs.
                • Default: false""")
    public boolean allowFriendlyMobsInteractWith = false;

    @Comment(
        """

                Allow right-click interaction with neutral mobs.
                • Default: false""")
    public boolean allowNeutralMobsInteractWith = false;

    @Comment(
        """

                Render unauthenticated players invisible to others.
                • Default: true (reduces visual clutter)""")
    public boolean invisibleUnauthorized = true;

    @Comment(
        """

                Apply permanent blindness effect to jailed players.
                • Default: true (forces focus on login)""")
    public boolean applyBlindnessEffect = true;

    @Comment(
        """

                Completely hide the inventory UI for jailed players.
                • Default: false""")
    public boolean hideInventory = false;

    @Comment(
        """

                Allow mobs to damage jailed players.
                • Default: false (prevents cheap deaths)""")
    public boolean allowMobDamage = false;

    @Comment(
        """

                Force jailed players into Adventure gamemode.
                • Default: true (prevents unintended block changes)""")
    public boolean forceAdventureMode = true;

    @Comment(
        """

                Prevent damage from items/projectiles to jailed players.
                • Default: false""")
    public boolean preventDamage = true;

    @Comment(
        """

                Block application of status effects on jailed players.
                • Default: true""")
    public boolean preventStatusEffect = true;

    @Comment(
        """

                Protect jailed players from damage inflicted by authenticated players.
                • Default: true""")
    public boolean preventPlayerDamage = true;

    @ConfigSerializable
    public static class TeleportConfig {

      @Comment(
          """

                    Teleport players to the lobby location on first join/registration.
                    • Default: true""")
      public boolean enabled = true;

      @Comment(
          """

                    Designated spawn point for new registrations.
                    • Default: overworld 0,64,0""")
      public Location location = new Location();

      @ConfigSerializable
      public static class Location {

        @Comment(
            """

                        Target dimension/resource location.
                        • Example: "minecraft, overworld"
                        • Default: minecraft, overworld""")
        public String dimension = "minecraft:overworld";

        @Comment(
            """

                        X coordinate • Default: 0.0""")
        public double x = 0;

        @Comment(
            """

                        Y coordinate • Default: 64.0""")
        public double y = 64;

        @Comment(
            """

                        Z coordinate • Default: 0.0""")
        public double z = 0;
      }
    }

    @ConfigSerializable
    public static class Timeout {

      @Comment(
          """

                    Adjust login timeout dynamically based on player ping.
                    • Default: true (fairer for distant players)""")
      public boolean enabled = true;

      @Comment(
          """

                    Base login timeout for players with ≤200ms ping.
                    • Default: 60000 ms (1 minute)""")
      public int loginTimeoutMs = 60_000;

      @Comment(
          """

                    Login timeout for players with ping >200ms.
                    • Default: 120000 ms (2 minutes)""")
      public int loginTimeoutAbove200LatencyMs = 120_000;

      @Comment(
          """

                    Login timeout for players with ping >400ms.
                    • Default: 240000 ms (4 minutes)""")
      public int loginTimeoutAbove400LatencyMs = 240_000;

      @Comment(
          """

                    Login timeout for players with ping >600ms.
                    • Default: 480000 ms (8 minutes)""")
      public int loginTimeoutAbove600LatencyMs = 480_000;
    }
  }
}
