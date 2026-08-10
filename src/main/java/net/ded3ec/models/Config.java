package net.ded3ec.models;

import net.ded3ec.network.ProxySupport;
import net.ded3ec.network.Webhook;
import net.ded3ec.network.WebPanel;
import net.ded3ec.security.Security;
import net.ded3ec.util.Database;

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
            Language/locale used for player-facing messages.
            • "en" → messages.conf
            • "zh" → messages-zh.conf (简体中文)
            • "es" → messages-es.conf (Español)
            • "de" → messages-de.conf (Deutsch)
            • "fr" → messages-fr.conf (Français)
            • "pt" → messages-pt.conf (Português)
            • "ru" → messages-ru.conf (Русский)
            • A bundled locale file is extracted automatically on first start.
            • Default: "en\"""")
  public String language = "en";

  @Comment(
      """
            Enables detailed debug logging to the console.
            • Useful for troubleshooting issues.
            • Recommended: false in production to reduce log spam.
            • Default: false""")
  public boolean debugMode = false;

  @Comment(
      """
            Logging customization.""")
  public LoggingConfig logging = new LoggingConfig();

  @Comment(
      """
            In-memory user cache size (lazy-loaded from the DB).
            • Larger = fewer DB queries, more memory. Online users are never evicted.
            • For 500k+ registered accounts keep this modest (e.g. 10000).
            • Default: 20000""")
  public int cacheMaxUsers = 20000;

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
  public net.ded3ec.models.Config.Database database = new net.ded3ec.models.Config.Database();

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
                • Required for new players to create an account.""")
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
    public CommandPermissions changePassword =
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
                • Lists currently connected offline-mode players.""")
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
                • Forces a player into offline authentication mode.""")
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
  public static class LoggingConfig {

    @Comment("Show the ASCII startup banner. Default: true")
    public boolean showBanner = true;

    @Comment("Show the version/security summary at startup. Default: true")
    public boolean showSummary = true;

    @Comment("Warn when running on an untested Minecraft version. Default: true")
    public boolean showUntestedVersionWarning = true;
  }

  @ConfigSerializable
  public static class Session {

    @Comment(
        """
        Server operation mode.
        • Accepted values: "online", "offline"
        • Default: online""")
    public String serverMode = "online";

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
                   Enable support for two‑factor authentication (TOTP).
                   • Default: false (recommended for enhanced account security)
                """)
      public boolean allowTOTPSupport = false;

      @Comment(
          """
                Enable email one-time-password as a second factor (sent via SMTP).
                Requires the email/SMTP config to be enabled. Default: false""")
      public boolean emailOtpSupport = false;

      @Comment(
          """
                Require a verified second factor for sensitive actions (password change, email
                change, session transfer). Only applies to players who have 2FA enabled.
                Default: true""")
      public boolean requireMfaForSensitive = true;

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
                    Allow offline players to load using a premium username.
                    • Disabling prevents username squatting.
                    • Default: false""")
      public boolean allowOnlineNameByOffline = false;

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

    @Comment(
        """
                    Automatically assign a LuckPerms group to newly registered accounts.
                    • Empty = disabled.
                    • Example: "member""")
    public String autoLuckPermsGroup = "";

    @Comment(
        """
                    Bind Bedrock (Geyser/Floodgate) accounts to their XUID.
                    • On join, the player's XUID must match the stored one or they are kicked.
                    • Default: false""")
    public boolean bindBedrockXuid = false;

    @Comment(
        """
                    Strict premium (online-mode) API handling.
                    • false = when the Mojang API is unreachable, premium checks degrade gracefully
                      and NO player is blocked (recommended - avoids locking out legit premium users
                      during Mojang outages).
                    • true = legacy behavior; the premium-name restriction is enforced even when the
                      API cannot be reached.
                    • Default: false""")
    public boolean premiumApiStrict = false;
  }

  @Comment(
      """
                Combat-log punishment: what happens when a player disconnects shortly after
                being in combat (e.g. to avoid dying).
                • killOnDisconnect kills the player entity on logout while still in the combat
                  window. Disabled by default for fairness.""")
  public CombatLogConfig combatLog = new CombatLogConfig();

  @Comment(
      """
                Progressive punishment: exponentially increasing kick cooldowns for repeated
                failed authentication (e.g. 5s -> 30s -> 5m).""")
  public ProgressivePunishmentConfig progressivePunishment = new ProgressivePunishmentConfig();

  @Comment(
      """
                Automatic account locking after repeated failed logins (persisted to the database).""")
  public AccountLockConfig accountLock = new AccountLockConfig();

  @Comment(
      """
                Login intelligence: detects new IPs / new countries for an account and can alert
                admins (webhook + security log) or block.""")
  public IntelligenceConfig intelligence = new IntelligenceConfig();

  @Comment(
      """
                Trusted players: accounts that authenticated successfully are trusted for a while
                and can skip the captcha challenge on rejoins.""")
  public TrustedConfig trusted = new TrustedConfig();

  @Comment(
      """
                Auto-whitelist: successfully registered accounts are added to the vanilla
                whitelist automatically.""")
  public AutoWhitelistConfig autoWhitelist = new AutoWhitelistConfig();

  @Comment(
      """
                Shadow-ban mode: security blocks (e.g. new-country blocking) use a generic
                "connection lost" disconnect instead of revealing the security reason.""")
  public ShadowBanConfig shadowBan = new ShadowBanConfig();

  @ConfigSerializable
  public static class TrustedConfig {

    @Comment("Enable the trusted-player system. Default: true")
    public boolean enabled = true;

    @Comment(
        """
                How long a successful login marks the account as trusted (hours).
                • Trusted players skip the captcha on rejoins.
                • Default: 24""")
    public int bypassCaptchaHours = 24;
  }

  @ConfigSerializable
  public static class AutoWhitelistConfig {

    @Comment(
        """
                Add registered accounts to the vanilla whitelist automatically.
                • Only applies while the server whitelist is enabled.
                • Default: false""")
    public boolean enabled = false;
  }

  @ConfigSerializable
  public static class ShadowBanConfig {

    @Comment("Enable shadow-ban behavior. Default: false")
    public boolean enabled = false;

    @Comment("Disconnect reason shown to shadow-banned players. Default: \"Connection lost\"")
    public String disconnectReason = "Connection lost";
  }

  @Comment(
      """
                Security events: optional Discord webhook and on-disk security log file.""")
  public SecurityConfig security = new SecurityConfig();

  @Comment(
      """
                Per-IP rate limiting to mitigate bot login floods.""")
  public RateLimitConfig rateLimit = new RateLimitConfig();

  @Comment(
      """
                Maintenance mode: temporarily block all joins with a custom message.
                • Useful for updates, backups or security incidents.
                • Toggle at runtime with /authcore maintenance on|off.""")
  public MaintenanceConfig maintenance = new MaintenanceConfig();

  @Comment(
      """
                Fake-server honeypot: an additional listening port that logs and auto-bans every
                IP that connects to it (attackers scanning for open ports).""")
  public HoneypotConfig honeypot = new HoneypotConfig();

  @Comment(
      """
                Automated database backups (independent from the /authcore backup command).""")
  public BackupConfig backup = new BackupConfig();

  @ConfigSerializable
  public static class MaintenanceConfig {

    @Comment("Enable maintenance mode. Default: false")
    public boolean enabled = false;

    @Comment(
        """
                Kick reason shown to players while maintenance mode is active.
                • Default: Server is under maintenance! Please check back later.""")
    public String kickMessage = "Server is under maintenance! Please check back later.";
  }

  @ConfigSerializable
  public static class HoneypotConfig {

    @Comment("Enable the honeypot listener. Default: false")
    public boolean enabled = false;

    @Comment(
        """
                Port the honeypot listens on (any connection = attacker).
                • Default: 25599""")
    public int port = 25599;
  }

  @ConfigSerializable
  public static class BackupConfig {

    @Comment(
        """
                Automatic backup interval in hours (0 = disabled).
                • SQLite: full file copy to config/authcore/backups/.
                • MySQL/PostgreSQL: the export command is recommended instead.
                • Default: 0 (disabled)""")
    public int intervalHours = 0;

    @Comment("Maximum number of automatic backups kept (oldest are deleted). Default: 10")
    public int keep = 10;
  }

  @Comment(
      """
                Proxy support (Velocity / BungeeCord networks).
                • bungeecord-style forwarding is parsed from the handshake so the real client
                  IP is used for GeoIP, sessions, rate limits and intelligence.
                • Enable only when your network runs a proxy in IP-forwarding mode.""")
  public ProxySupportConfig proxySupport = new ProxySupportConfig();

  @Comment(
      """
                Built-in web admin panel (lightweight HTML interface).
                • Serves read-only stats + player lists + admin actions over HTTP.
                • Keep the host at 127.0.0.1 and use an SSH tunnel or reverse proxy!
                • A token is mandatory when enabled.""")
  public WebPanelConfig webPanel = new WebPanelConfig();

  @Comment(
      """
                Email notifications & account recovery (SMTP).
                • Sends login alerts for new IPs/countries and recovery codes for
                  forgotten passwords via /account recover.""")
  public EmailConfig email = new EmailConfig();

  @Comment(
      """
                Interop with OTHER mods and the proxy (Velocity / BungeeCord).
                • AuthCore broadcasts auth-state changes as lightweight plugin messages
                  (channel "authcore:auth" + BungeeCord subchannel "AuthCore") so a network can
                  coexist with a DIFFERENT auth mod on the backend, or a proxy plugin can react.
                • Payload: AUTH_CHANGED|<uuid>|<username>|<1|0> (ASCII)""")
  public InteropConfig interop = new InteropConfig();

  /** Client detection & anti-bypass guard (ClientGuard engine). */
  @Comment(
      """
      Client detection and anti-bypass guard.
      • Detects ghost clients, macro abuse, session theft and fake companion clients.
      • Every signal raises the player's risk score; the decision matrix below decides the action.""")
  public ClientGuardConfig clientGuard = new ClientGuardConfig();

  /** Network-wide single sign-on (Redis). */
  @Comment(
      """
      Single sign-on across a server network. When enabled, a player who logs in on one
      server of the network (Redis-backed) is trusted on the others - no password needed
      on the second join. Requires the Redis config to be enabled. Default: false""")
  public SsoConfig sso = new SsoConfig();

  @ConfigSerializable
  public static class SsoConfig {

    @Comment("Master switch for network-wide single sign-on. Default: false")
    public boolean enabled = false;

    @Comment(
        """
                SSO trust window in minutes (how long a login on one server is honoured on
                the others). Default: 30""")
    public int sessionTtlMin = 30;

    @Comment(
        """
                Also trust vanilla clients (no companion session token) when a remote session
                exists. Less secure - an attacker on the same IP could impersonate. Default: false""")
    public boolean trustVanilla = false;
  }


  @ConfigSerializable
  public static class ClientGuardConfig {

    @Comment("Master switch for the client guard. Default: true")
    public boolean enabled = true;

    @Comment(
        """
                Kick players who authenticate/chat/interact not at all for this many seconds
                after joining (ghost clients). 0 = disabled. Default: 45""")
    public int ghostKickAfterSec = 45;

    @Comment(
        """
                Seconds a player may stay connected without sending the client settings packet
                (real clients always send it right after joining). 0 = disabled. Default: 20""")
    public int settingsTimeoutSec = 20;

    @Comment(
        """
                Maximum movement packets per second before a flood signal is raised.
                0 = disabled. Default: 120""")
    public int movePacketRatePerSec = 120;

    @Comment(
        """
                Maximum inventory click / item-use actions per second in the lobby.
                0 = disabled. Default: 12""")
    public int lobbyClickRatePerSec = 12;

    @Comment(
        """
                Maximum custom-payload packets per second (unknown channels included).
                0 = disabled. Default: 15""")
    public int payloadRatePerSec = 15;

    @Comment(
        """
                Maximum chat messages per second in the lobby. 0 = disabled. Default: 3""")
    public int lobbyChatRatePerSec = 3;

    @Comment(
        """
                Risk score at which a webhook alert + security log entry is emitted.
                Default: 40""")
    public int riskAlertThreshold = 40;

    @Comment(
        """
                Risk score at which the player is kicked from the lobby.
                Default: 70""")
    public int riskKickThreshold = 70;

    @Comment(
        """
                Risk score at which a player with 2FA enabled must complete TOTP before
                leaving the lobby. Default: 50""")
    public int risk2FAThreshold = 50;

    @Comment(
        """
                Companion attestation: re-issue a random challenge every N seconds while the
                player is in the lobby. 0 = challenge only once at join. Default: 30""")
    public int reChallengeIntervalSec = 30;

    @Comment(
        """
                Challenge response timeout in seconds. Default: 10""")
    public int challengeTimeoutSec = 10;

    @Comment(
        """
                Raise the risk score when a client claims the AuthCore companion but fails
                the attestation challenge. Default: 40""")
    public int companionSpoofRisk = 40;

    @Comment(
        """
                Concurrent login policy. "allow" = both stay, "kick-new" = the new connection
                is kicked, "kick-old" = the previous connection is kicked. Default: kick-new""")
    public String concurrentLoginPolicy = "kick-new";

    @Comment(
        """
                Username validation regex (applied to offline-mode names at join).
                Empty = vanilla rules. Default: ASCII letters/digits/underscore, 3-16 chars""")
    public String allowedNameRegex = "^[A-Za-z0-9_]{3,16}$";

    @Comment(
        """
                Flag names that differ from a registered account only by confusable/unicode
                characters (impersonation attempts). Default: true""")
    public boolean detectConfusableNames = true;

    @Comment(
        """
                Require the companion attestation + session token for session resume.
                Vanilla clients (no companion) fall back to IP-only resume with a risk penalty.
                Default: true""")
    public boolean requireTokenForResume = true;

    @Comment("Risk penalty applied when a vanilla client resumes a session by IP only. Default: 10")
    public int vanillaResumeRisk = 10;

    @Comment(
        """
                Hard limit for a single custom payload in the lobby (bytes).
                0 = disabled. Default: 8192""")
    public int maxPayloadBytes = 8192;
  }

  @ConfigSerializable
  public static class ProxySupportConfig {

    @Comment(
        """
                Enable proxy IP-forwarding support.
                • Default: false""")
    public boolean enabled = false;

    @Comment(
        """
                Forwarding protocol:
                • "auto"       - detect (BungeeCord legacy format ip\\0uuid\\0properties)
                • "bungeecord" - BungeeCord (or Velocity legacy) IP forwarding
                • "velocity"   - Velocity modern forwarding (real IP is delivered natively on
                  recent protocol versions; this setting enables the legacy parse as fallback)
                • Default: "auto\"""")
    public String protocol = "auto";

    @Comment(
        """
                Velocity modern forwarding shared secret (optional).
                • Used to verify forwarded payloads when the protocol carries them.
                • Default: "" (disabled)""")
    public String velocitySecret = "";
  }

  @ConfigSerializable
  public static class InteropConfig {

    @Comment("Enable interop auth-state broadcasts. Default: true")
    public boolean enabled = true;

    @Comment(
        """
                Custom Fabric channel used for auth-state messages.
                • Other Fabric mods listen on this channel for "AUTH_CHANGED|..." payloads.
                • Default: "authcore:auth\"""")
    public String channel = "authcore:auth";

    @Comment(
        """
                Also broadcast on the BungeeCord plugin-messaging channel
                ("bungeecord:main", subchannel "AuthCore") so proxy plugins
                (Velocity / BungeeCord) can react to logins.
                • Default: true""")
    public boolean bungeeChannel = true;
  }

  @ConfigSerializable
  public static class WebPanelConfig {

    @Comment("Enable the web admin panel. Default: false")
    public boolean enabled = false;

    @Comment(
        """
                Interface to bind to.
                • "127.0.0.1" = local only (recommended - tunnel to it)
                • "0.0.0.0"   = exposed on all interfaces (NOT recommended without a token)
                • Default: "127.0.0.1\"""")
    public String host = "127.0.0.1";

    @Comment("Panel port (HTTP). Default: 25570")
    public int port = 25570;

    @Comment(
        """
                Serve the panel over HTTPS.
                • When no keystore is configured, a self-signed certificate is generated
                  automatically on first start (config/authcore/panel-keystore.p12) and its
                  fingerprint is printed in the console.
                • Default: false""")
    public boolean httpsEnabled = false;

    @Comment("HTTPS port. Default: 25571")
    public int httpsPort = 25571;

    @Comment(
        """
                Path to a custom PKCS12/JKS keystore (optional).
                • When empty, the auto-generated self-signed keystore is used.
                • Example: "/etc/authcore/panel.p12\"""")
    public String httpsKeystore = "";

    @Comment("Password of the custom keystore (ignored for the auto-generated one).")
    public String httpsKeystorePassword = "";

    @Comment(
        """
                Access token (required). Send it as "Authorization: Bearer <token>".
                • Generate one, e.g.: openssl rand -hex 16
                • Default: "" (panel will not start)""")
    public String token = "";

    @Comment(
        """
                Path to a file containing the access token (optional).
                • Useful for provisioning; the token file wins over the inline token.
                • Default: "" (disabled)""")
    public String tokenFile = "";

    @Comment(
        """
                Optional read-only token: requests authenticated with this token can view data
                but cannot run actions (kick, delete, set-password, link...).
                • Default: "" (disabled)""")
    public String readonlyToken = "";
  }

  @ConfigSerializable
  public static class EmailConfig {

    @Comment("Enable SMTP email features. Default: false")
    public boolean enabled = false;

    @Comment(
        """
                SMTP server host.
                • Example: "smtp.gmail.com\"""")
    public String host = "";

    @Comment(
        """
                SMTP server port.
                • 587 (STARTTLS) or 465 (implicit SSL).
                • Default: 587""")
    public int port = 587;

    @Comment("SMTP username (usually the full email address).")
    public String username = "";

    @Comment("SMTP password / app password.")
    public String password = "";

    @Comment("From address shown in sent emails.")
    public String from = "";

    @Comment(
        """
                Use implicit SSL (port 465). When false, STARTTLS is used (port 587).
                • Default: false""")
    public boolean useSsl = false;

    @Comment(
        """
                Send a login alert email when an account logs in from a new IP or country.
                • Default: true""")
    public boolean alertOnNewLogin = true;
  }

  @ConfigSerializable
  public static class CombatLogConfig {

    @Comment("Enable combat-log punishment. Default: false")
    public boolean enabled = false;

    @Comment(
        """
                Kill the player entity when they disconnect while still inside the combat window.
                • Only the entity is removed; inventory/XP still drop on the ground.
                • Default: false""")
    public boolean killOnDisconnect = false;
  }

  @ConfigSerializable
  public static class ProgressivePunishmentConfig {

    @Comment("Enable exponential kick cooldowns. Default: false")
    public boolean enabled = false;

    @Comment("Base cooldown in milliseconds for the first offense. Default: 5000 (5 seconds)")
    public long baseCooldownMs = 5_000;

    @Comment(
        """
                Cooldown growth factor per offense. A value of 6 roughly yields 5s -> 30s -> 3m.
                • Default: 6""")
    public double multiplier = 6.0;

    @Comment("Maximum cooldown cap in milliseconds. Default: 300000 (5 minutes)")
    public long maxCooldownMs = 5 * 60 * 1000;
  }

  @ConfigSerializable
  public static class AccountLockConfig {

    @Comment("Enable automatic account locking. Default: true")
    public boolean enabled = true;

    @Comment("Failed logins before the account is locked. Default: 8")
    public int maxFailedLogins = 8;

    @Comment("How long the account stays locked in milliseconds. Default: 600000 (10 minutes)")
    public long lockDurationMs = 10 * 60 * 1000;
  }

  @ConfigSerializable
  public static class IntelligenceConfig {

    @Comment("Enable login intelligence (new IP / country detection). Default: true")
    public boolean enabled = true;

    @Comment("Alert admins (webhook + security log) on login from a new IP. Default: true")
    public boolean alertOnNewIp = true;

    @Comment("Alert admins on login from a new country. Default: true")
    public boolean alertOnNewCountry = true;

    @Comment(
        """
                Block logins from a new country (anti account-sharing; use with caution).
                • Default: false""")
    public boolean blockOnNewCountry = false;

    @Comment(
        """
                Risk score threshold (0-100) above which admins are alerted on login.
                • Default: 60""")
    public int alertRiskThreshold = 60;
  }

  @ConfigSerializable
  public static class SecurityConfig {

    @Comment(
        """
                Discord webhook URL for login/security events (join, login, failed login, lockouts,
                suspicious logins). Leave empty to disable.
                • Example: "https://discord.com/api/webhooks/..." """)
    public String webhookUrl = "";

    @Comment(
        """
                Optional separate webhook for registration announcements (used by Discord bots
                for role sync). Falls back to webhook-url when empty.""")
    public String roleSyncWebhookUrl = "";

    @Comment(
        """
                Additional generic webhook URLs (Slack, Telegram, custom) that receive the same
                events as the Discord webhook.
                • Example: ["https://hooks.slack.com/services/..."]
                • Default: [] (disabled)""")
    public java.util.Set<String> extraWebhookUrls = java.util.Set.of();

    @Comment(
        """
                File name (in the config directory) for the security event log.
                • Set to "" to disable.
                • Default: "security.log\"""")
    public String logFile = "security.log";

    @Comment(
        """
                Maximum size of the security log before it is rotated (bytes).
                • Rotated files are kept as security.log.1, .2, .3.
                • Default: 5242880 (5 MB)""")
    public long logMaxBytes = 5 * 1024 * 1024;

    @Comment(
        """
                File (in the config directory) with per-IP allow/deny rules.
                • Format: one rule per line: "allow 1.2.3.4" or "deny 5.6.7.8"
                • Lines starting with # are comments.
                • Default: "ip-rules.conf\"""")
    public String ipRulesFile = "ip-rules.conf";
  }

  @ConfigSerializable
  public static class RateLimitConfig {

    @Comment("Enable per-IP join/login rate limiting. Default: true")
    public boolean enabled = true;

    @Comment("Maximum joins per IP within the window. Default: 10")
    public int maxJoinsPerWindow = 10;

    @Comment("Rate-limit window in milliseconds. Default: 60000 (1 minute)")
    public long windowMs = 60 * 1000;

    @Comment("Kick message shown when the join rate limit is exceeded. Default: empty = generic")
    public String overLimitMessage = "Too many connections from your address. Please wait a moment!";

    @Comment(
        """
                Alert when a player rejoins extremely fast after disconnecting (bot pattern).
                • Alert-only (no kick) - webhook + security log.
                • Default: false""")
    public boolean alertOnFastRejoin = false;

    @Comment(
        """
                Minimum disconnect-to-rejoin window for the fast-rejoin alert (ms).
                • Default: 500""")
    public long fastRejoinWindowMs = 500;

    @Comment(
        """
                Minimum delay between two /login or /register executions per player (ms).
                • Prevents command spam.
                • Default: 1000""")
    public long commandCooldownMs = 1000;
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

    @Comment(
        """
                Password history: how many previous passwords are remembered and blocked
                from reuse (0 = disabled).
                • Default: 5""")
    public int historySize = 5;

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

  @Comment(
      """
            PostgreSQL remote database configuration.
            • Alternative to MySQL for larger setups. Requires the PostgreSQL server to exist.""")
  public postgresDatabase postgres = new postgresDatabase();

  @Comment(
      """
            Redis configuration for network-wide session & ban synchronization.
            • Optional: enables cross-server duplicate login detection and shared ban lists.
            • Requires a reachable Redis server.""")
  public redisDatabase redis = new redisDatabase();
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
                • Default here: 3306""")
    public int port = 3306;

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
  public static class postgresDatabase {

    @Comment(
        """
                Enable PostgreSQL instead of SQLite/MySQL.
                • Requires correct credentials and a reachable server.
                • Default: false""")
    public boolean enabled = false;

    @Comment(
        """
                PostgreSQL server host address.
                • Example: "localhost" or "db.example.com\"""")
    public String host = "";

    @Comment("PostgreSQL server port. Standard port is 5432. Default: 5432")
    public int port = 5432;

    @Comment("Database/schema name.")
    public String database = "";

    @Comment("Database username.")
    public String username = "";

    @Comment("Database password (stored in plain text - protect the config file).")
    public String password = "";

    @Comment(
        """
                Use SSL/TLS encryption for the database connection.
                • Default: false""")
    public boolean ssl = false;
  }

  @ConfigSerializable
  public static class redisDatabase {

    @Comment(
        """
                Enable Redis session & ban synchronization.
                • Default: false""")
    public boolean enabled = false;

    @Comment("Redis server host address. Example: \"localhost\"")
    public String host = "localhost";

    @Comment("Redis server port. Default: 6379")
    public int port = 6379;

    @Comment(
        """
                Redis password (empty if authentication is disabled).""")
    public String password = "";

    @Comment("Redis logical database index. Default: 0")
    public int database = 0;
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
                Captcha protection for bots attempting to register/login.
                • When enabled, the lobby shows a captcha code and /login & /register require it
                  as an extra argument.
                • Default: false""")
    public CaptchaConfig captcha = new CaptchaConfig();

    @Comment(
        """
                Rotating server announcements shown to authenticated players.
                • Each entry is shown for announcement-interval-sec, then the next one.
                • Supports %player% placeholder.
                • Default: [] (disabled)""")
    public java.util.List<String> announcements = new java.util.ArrayList<>();

    @Comment(
        """
                Seconds between rotating announcements.
                • Default: 600 (10 minutes)""")
    public int announcementIntervalSec = 600;

    @Comment(
        """
                Server announcement shown to authenticated players on join.""")
    public AnnouncementConfig announcement = new AnnouncementConfig();

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
    public static class CaptchaConfig {

      @Comment("Enable captcha for registration/login. Default: false")
      public boolean enabled = false;

      @Comment("Captcha code length. Default: 5")
      public int length = 5;

      @Comment("Captcha code validity in milliseconds. Default: 60000 (1 minute)")
      public long ttlMs = 60 * 1000;

      @Comment(
          """
                    Disable the captcha automatically while the server TPS is below this value
                    (0 = never disable). Prevents captcha annoyance during lag spikes.
                    • Default: 0""")
      public double disableWhenTpsBelow = 0;
  }

  @ConfigSerializable
  public static class AnnouncementConfig {

    @Comment("Show a server announcement to authenticated players on join. Default: false")
    public boolean enabled = false;

    @Comment(
        """
                Announcement text. Supports %player% placeholder.
                • Default: "" (empty)""")
    public String text = "";
  }

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
