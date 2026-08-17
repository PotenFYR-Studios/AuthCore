package net.ded3ec.events;

import net.ded3ec.models.Config;
import net.ded3ec.models.Messages;
import net.ded3ec.network.EmailSender;
import net.ded3ec.network.McApiManager;
import net.ded3ec.network.RedisManager;
import net.ded3ec.network.Webhook;
import net.ded3ec.security.RateLimiter;
import net.ded3ec.security.Security;
import net.ded3ec.security.SecurityLog;
import net.ded3ec.util.Logger;
import net.ded3ec.util.TaskScheduler;
import net.ded3ec.util.TpsManager;

import java.util.UUID;

import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.Lobby;
import net.ded3ec.models.User;
import net.ded3ec.util.TimeManager;
import net.ded3ec.util.*;
import net.ded3ec.security.*;
import net.ded3ec.network.*;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * The main game hooks: player join, player leave and the per-tick guard.
 *
 * <p>onPlayerJoin is the gate every connection passes through. It classifies the player
 * (premium or standard), applies every security check in order (duplicates, rate limits, IP
 * rules, locks, proxy detection) and ends with one of three outcomes: session resumed,
 * premium auto-login, or locked into the lobby. onEndServerTick re-asserts the limbo
 * (blindness, position anchor) as a fallback for anything the packet-level guards miss.
 */
public class ServerEvents {

  /** True when the fabric-api join/leave/tick hooks are registered (set by FabricHooks).
   *  The mixin fallbacks in ServerEventsFallbackMixin skip when the fabric hook is active,
   *  so AuthCore keeps working even on servers WITHOUT fabric-api. */
  public static volatile boolean fabricJoinActive = false;
  public static volatile boolean fabricLeaveActive = false;
  public static volatile boolean fabricTickActive = false;

  /** True when a Forge/NeoForge native event-bus hook is registered (set by the loader
   *  entrypoints). The mixin fallbacks skip when EITHER the fabric hook or the native hook
   *  is active - otherwise a Forge-like server would fire the same join/leave/tick twice
   *  (native event + fallback mixin) and the duplicate-register check would kick players. */
  public static volatile boolean nativeJoinActive = false;
  public static volatile boolean nativeLeaveActive = false;
  public static volatile boolean nativeTickActive = false;

  /**
   * Connections already processed by the join handler. Dedupes loaders where multiple hooks
   * fire for the SAME join (e.g. NeoForge's PlayerLoggedInEvent + fabric-api's JOIN event via
   * Sinytra Connector, or a native event + the fallback mixin): without this the second call
   * sees the user already locked in the lobby and kicks them with "already registering".
   */
  private static final java.util.Set<ServerGamePacketListenerImpl> JOINED_CONNECTIONS =
      java.util.concurrent.ConcurrentHashMap.newKeySet();

  /** Connections already processed by the leave handler (same double-fire protection). */
  private static final java.util.Set<ServerGamePacketListenerImpl> LEFT_CONNECTIONS =
      java.util.concurrent.ConcurrentHashMap.newKeySet();

  /** Player join handler (loader-neutral - the fabric PacketSender arg was unused). */
  public static void onPlayerJoin(ServerGamePacketListenerImpl connection) {
    if (connection == null || !JOINED_CONNECTIONS.add(connection)) return;

    ServerPlayer player = connection.player;

    AuthCoreServer.LOGGER.debug(
        false,
        "{} | join started (online-mode server: {})",
        player.getName().getString(),
        AuthCoreServer.isServerOnline());

    // Detect the real server.properties online-mode once (it is ALWAYS taken from the
    // Minecraft server - there is no config override, so offline servers are handled
    // correctly without any setup).
    net.minecraft.server.MinecraftServer joinServer = net.ded3ec.compat.Compat.getServer(player);
    if (joinServer != null)
      AuthCoreServer.detectServerOnlineMode(
          net.ded3ec.compat.Compat.serverUsesAuthentication(joinServer));
    boolean onlineServer = AuthCoreServer.isServerOnline();

    // ClientGuard: per-player profile + confusable-name impersonation scan.
    net.ded3ec.security.ClientGuard.onJoin(player);
    net.ded3ec.security.ClientGuard.checkConfusableName(player.getName().getString());

    // Register interop channels (other mods / proxy plugin messaging)
    net.ded3ec.network.AuthInterop.register(player);

    // Maintenance mode: block all joins
    if (AuthCoreServer.config.session.maintenance.enabled) {
      connection.disconnect(
          net.ded3ec.compat.Compat.text(AuthCoreServer.config.session.maintenance.kickMessage));
      return;
    }

    UUID uuid = player.getUUID();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);
    boolean brandNew = false;

    // Duplicate login handling
    if (user != null && user.isActive) {

      if (user.isInLobby.get()
          && AuthCoreServer.config.session.authentication.blockDuplicateRegister) {
        AuthCoreServer.LOGGER.toKick(
            false, connection, AuthCoreServer.messages.promptUserAnotherAccountIsRegistering);
        return;
      }

      if (user.isAuthenticated.get()
          && AuthCoreServer.config.session.authentication.blockDuplicateSession) {
        AuthCoreServer.LOGGER.toKick(
            false, connection, AuthCoreServer.messages.promptUserAnotherAccountSession);
        return;
      }

      // Concurrent-login policy: "kick-old" disconnects the previous connection instead.
      if ("kick-old".equals(AuthCoreServer.config.session.clientGuard.concurrentLoginPolicy)
          && user.player.get() != null
          && user.player.get() != player) {
        net.ded3ec.security.ClientGuard.profile(player).addSignal(
            net.ded3ec.security.ClientGuard.Signal.CONCURRENT_LOGIN);
        SecurityLog.log(
            "CONCURRENT_LOGIN",
            username + " logged in from a second connection - kicking the old one");
        net.ded3ec.network.Webhook.sendEmbed(
            "AuthCore - Concurrent Login",
            "**" + username + "** logged in from a second connection - old connection kicked.",
            0xF1C40F);
        user.kick(AuthCoreServer.messages.promptUserAnotherAccountSession);
        user.isActive = false;
      }

} else if (user == null) {
      // A brand-new account. On an online-mode server every accepted player with a REAL
      // (non-offline) UUID is premium (the server itself authenticated them) - offline-UUID
      // players are accepted by the hybrid login flow and are OFFLINE. On an offline-mode
      // server the online-mode status is verified ASYNCHRONOUSLY (below) so the join path
      // never blocks on a Mojang API call - verified players are auto-logged-in as soon as
      // the lookup returns.
      boolean premiumNew =
          onlineServer && !net.ded3ec.compat.Compat.isOfflineUuid(username, uuid);
      user = new User(uuid, username, System.currentTimeMillis(), premiumNew);
      brandNew = true;
    }

    // Final snapshot for lambdas (user may be reassigned above).
    final User joinUser = user;

    // Companion session-claim verification (delayed a few seconds so the client's
    // HELLO / SESSION_TOKEN_ECHO payloads have time to arrive).
    net.ded3ec.util.TaskScheduler.getInstance()
        .setTimeout(() -> net.ded3ec.security.ClientGuard.verifySessionClaim(player), 5000L);

    // Per-IP join rate limit (bot flood protection)
    if (AuthCoreServer.config.session.rateLimit.enabled
        && !McApiManager.isPrivateAddress(player.getIpAddress())
        && !RateLimiter.tryAcquire(
            player.getIpAddress(),
            AuthCoreServer.config.session.rateLimit.maxJoinsPerWindow,
            AuthCoreServer.config.session.rateLimit.windowMs)) {

      String reason = AuthCoreServer.config.session.rateLimit.overLimitMessage;
      net.ded3ec.security.SecurityLog.log("JOIN_RATE_LIMITED", player.getIpAddress() + " | " + username);
      AuthCoreServer.LOGGER.toKick(
          false,
          connection,
          new net.ded3ec.models.Messages.KickTemplate() {
            {
              logout.text = reason;
            }
          });
      return;
    }

    // Remote (Redis) duplicate session check for server networks.
    // With SSO enabled the remote session is TRUSTED instead of blocked - the player
    // already authenticated on another server of the network.
    if (user.isRegistered.get()
        && !AuthCoreServer.config.session.sso.enabled
        && net.ded3ec.network.RedisManager.hasRemoteSession(uuid)) {
      AuthCoreServer.LOGGER.toKick(
          false, connection, AuthCoreServer.messages.promptUserAnotherAccountSession);
      return;
    }

    // Persistent account lock check
    if (AuthCoreServer.config.session.accountLock.enabled && user.isLocked()) {
      long remainingMs = user.lockUntilMs - System.currentTimeMillis();
      AuthCoreServer.LOGGER.toUser(
          false,
          connection,
          AuthCoreServer.messages.promptUserAccountLocked,
          net.ded3ec.util.TimeManager.toDuration(Math.max(remainingMs, 1000)));
      AuthCoreServer.LOGGER.toKick(
          false, connection, AuthCoreServer.messages.promptUserAuthenticationExpiredTimeout,
          net.ded3ec.util.TimeManager.toDuration(Math.max(remainingMs, 1000)));
      return;
    }

    // Establish connection
    user.connect(connection);

    // Per-IP allow/deny rules (ip-rules.conf)
    if (net.ded3ec.security.IpRules.isDenied(player.getIpAddress())) {
      SecurityLog.log("IP_DENIED", player.getIpAddress() + " | " + username + " blocked by ip-rules.conf");
      AuthCoreServer.LOGGER.toKick(
          false,
          connection,
          AuthCoreServer.messages.promptUserProxyNotAllowed);
      return;
    }

    // Fast-rejoin alert (bot pattern: disconnect + reconnect within milliseconds)
    if (AuthCoreServer.config.session.rateLimit.alertOnFastRejoin
        && !net.ded3ec.security.RateLimiter.tryAcquire(
            "rejoin:" + player.getIpAddress(),
            1,
            AuthCoreServer.config.session.rateLimit.fastRejoinWindowMs)) {
      SecurityLog.log("FAST_REJOIN", player.getIpAddress() + " | " + username + " rejoined too fast");
      Webhook.sendEmbed(
          "Fast Rejoin Detected",
          "**" + username + "** reconnected from `" + player.getIpAddress() + "` within "
              + AuthCoreServer.config.session.rateLimit.fastRejoinWindowMs + " ms (bot pattern).",
          0xF1C40F);
    }

    // Proxy restriction. The GeoIP lookup itself runs ASYNCHRONOUSLY in User.connect (off
    // the server thread - a synchronous HTTP call here stalls the whole server for up to
    // the HTTP timeout, the "Can't keep up" killer). This path only consumes the CACHE:
    // if the async lookup has not completed yet, the proxy decision is deferred a few
    // seconds instead of blocking the server thread.
    if (!AuthCoreServer.config.session.authentication.allowProxyUsers) {
      com.google.gson.JsonObject geo = McApiManager.geoIpCached(player.getIpAddress());
      if (geo != null
          && geo.has("status")
          && "success".equalsIgnoreCase(geo.get("status").getAsString()))
        user.geoIpData = geo;

      if (user.isProxy.get()) {
        AuthCoreServer.LOGGER.toKick(
            false, connection, AuthCoreServer.messages.promptUserProxyNotAllowed);
        return;
      }

      // Data not cached yet (async lookup still running): re-check once after it had time
      // to complete instead of blocking the join on an HTTP call.
      if (McApiManager.geoIpCached(player.getIpAddress()) == null) {
        net.ded3ec.util.TaskScheduler.getInstance()
            .setTimeout(
                () -> {
                  if (joinUser == null || !joinUser.isActive || joinUser.connection == null)
                    return;
                  com.google.gson.JsonObject geo2 = McApiManager.geoIpCached(player.getIpAddress());
                  if (geo2 != null
                      && geo2.has("status")
                      && "success".equalsIgnoreCase(geo2.get("status").getAsString()))
                    joinUser.geoIpData = geo2;
                  if (joinUser.isProxy.get())
                    AuthCoreServer.LOGGER.toKick(
                        false, joinUser.connection, AuthCoreServer.messages.promptUserProxyNotAllowed);
                },
                7000L);
      }
    }

    // Same-IP restriction (empty/legacy IPs are never considered a mismatch)
    if (AuthCoreServer.config.session.sessionFromSameIPOnly
        && user.isRegistered.get()
        && org.apache.commons.lang3.StringUtils.isNotBlank(user.ipAddress)
        && !user.ipAddress.equals(player.getIpAddress())) {

      AuthCoreServer.LOGGER.toKick(
          false, connection, AuthCoreServer.messages.promptUserDifferentIpLoginNotAllowed);
      return;
    }

    // Online-mode-only servers: when offline (cracked) players are disallowed, reject any
    // join the server did NOT verify as a genuine online-mode session. On an online-mode
    // server only real-UUID clients were verified by vanilla (offline-UUID clients were
    // accepted by the hybrid login flow and are offline); on an offline-mode server a real
    // online-mode player was verified by the handshake mixin, everyone else is offline and
    // is kicked - before session resume, register or login can happen.
    if (!AuthCoreServer.config.session.authentication.allowOfflinePlayers
        && !((onlineServer && !net.ded3ec.compat.Compat.isOfflineUuid(username, uuid))
            || AuthCoreServer.isPremiumVerified(uuid))) {
      AuthCoreServer.LOGGER.debug(
          false,
          "{} | join - offline-mode players are not allowed on this server, kicking",
          username);
      AuthCoreServer.LOGGER.toKick(
          false, connection, AuthCoreServer.messages.promptUserOfflinePlayersNotAllowed);
      return;
    }

    // Resume active session. The same-IP requirement is enforced ONLY when
    // sessionFromSameIPOnly is enabled: on hybrid/proxy networks (hub -> game switches)
    // the forwarded IP can legitimately differ between servers, so an unconditional IP
    // check would silently drop the session on every hub transfer.
    boolean sameIpOk =
        !AuthCoreServer.config.session.sessionFromSameIPOnly
            || (org.apache.commons.lang3.StringUtils.isNotBlank(user.ipAddress)
                && user.ipAddress.equals(player.getIpAddress()));
    if (user.uuid.equals(uuid) && sameIpOk && user.isActiveSession.get()) {

      AuthCoreServer.LOGGER.debug(
          false,
          "{} | join - active session resumed (same-IP {}), skipping auth",
          username,
          sameIpOk);

      AuthCoreServer.LOGGER.debug(
          true, "{} skipped authentication and resumed session!", user.username);

      AuthCoreServer.LOGGER.toUser(
          true, connection, AuthCoreServer.messages.promptUserSessionResumed);

      user.login(player);
      return;
    }

    // Premium auto-login.
    //
    // Online-mode status comes ONLY from the server's own Mojang session authentication
    // (online-mode servers verify every real-UUID profile via the session service - captured
    // by the ServerLoginNetworkHandlerMixin premium hook; offline-UUID clients there were
    // accepted by the hybrid login flow and are OFFLINE) - AuthCore makes NO Mojang API
    // calls. On offline-mode servers the server never authenticates anyone, so no player can
    // be online-mode unless the handshake mixin verified them: DB records flagged
    // "online-mode" by earlier builds are stale and are downgraded instead of trusted
    // (previously they were auto-registered / auto-logged-in, which silently bypassed
    // registration for offline players).
    //
    // The premium-auto-login CONFIG is always respected, regardless of the server's mode
    // (it defaults to enabled). With it OFF, verified online-mode players are treated like any
    // standard account: since auto-login accounts keep a NULL password, they are asked to
    // /register on their next re-auth (their premium status itself is NOT destroyed, so the
    // auto-login simply resumes when the admin turns the config back on).
    //
    // A player's OWN mode choice is honored: brand-new accounts and accounts whose stored
    // mode is online auto-login when the session is verified; an account the player (or an
    // admin) explicitly switched to offline-mode is NEVER auto-logged-in - it is asked to
    // register/login like any offline account, even on an online-mode server.
    boolean verifiedPremium =
        (onlineServer && !net.ded3ec.compat.Compat.isOfflineUuid(username, uuid))
            || AuthCoreServer.isPremiumVerified(uuid);
    boolean autoLogin =
        AuthCoreServer.config.session.authentication.premiumAutoLogin
            && verifiedPremium
            && (brandNew || user.isPremium);

    if (autoLogin) {
      // A brand-new account verified as premium on this join - persist the flag so future
      // joins keep auto-logging-in. An account that explicitly switched to offline-mode is
      // never upgraded here (isPremium=false and it is not brand-new).
      if (!user.isPremium) {
        user.isPremium = true;
        user.update("Online-mode verified via server authentication");
      }

      AuthCoreServer.LOGGER.debug(
          false, "{} | join - online-mode verified, auto-login path", username);
      AuthCoreServer.LOGGER.debug(
          true, "{} is an online-mode player and skipped authentication!", user.username);

      // Premium auto-login players are NEVER given an auto-generated password - their stored
      // password stays null (they are not password-"registered"). They are logged in directly;
      // if they later switch to offline mode they are asked to register a password.
      AuthCoreServer.LOGGER.toUser(
          true, connection, AuthCoreServer.messages.promptUserPremiumAutoLogin);

      user.login(player);
      return;
    }

    // Stale premium flag: the account claims premium but THIS session was not verified as
    // premium (offline server without session verification, or premium auto-login disabled) -
    // downgrade to standard so it cannot bypass register/login. When only the auto-login
    // config is OFF the account stays premium (the flag is preserved) - the player is just
    // asked to register/login because their stored password is null.
    if (user.isPremium && !verifiedPremium) {
      AuthCoreServer.LOGGER.info(
          true,
          "{} was registered as an online-mode account but this session was not verified as "
              + "online-mode - moved to offline-mode authentication (register/login required).",
          user.username);
      user.isPremium = false;
      user.update("Online-mode flag cleared (offline-mode auth server)");
    }

    // Premium UUID mismatch. Only applies on online-mode servers (where the server itself
    // verified the connecting profile): a REAL premium UUID that does not match the stored
    // one means an impostor. Offline-mode servers derive offline UUIDs for everyone, so the
    // check never applies there.
    if (!user.uuid.equals(uuid)
        && user.isPremium
        && AuthCoreServer.config.session.authentication.premiumAutoLogin
        && onlineServer
        && !net.ded3ec.compat.Compat.isOfflineUuid(username, uuid)) {

      user.kick(AuthCoreServer.messages.promptUserPremiumDifferentUUID);
      return;
    }

    // Login intelligence: risk score + new IP/country detection (only for returning users)
    if (user.isRegistered.get()
        && AuthCoreServer.config.session.intelligence.enabled
        && !McApiManager.isPrivateAddress(player.getIpAddress())) {

      // Cache-only GeoIP read: the async lookup in User.connect populates this shortly
      // after join. A synchronous HTTP call here would stall the server thread (the
      // "Can't keep up" killer), so the country may be momentarily unavailable on the
      // very first join - the risk score simply falls back to null country.
      com.google.gson.JsonObject geo = user.geoIpData;
      String country = user.country.get();
      if (geo == null && country == null) {
        geo = McApiManager.geoIpCached(player.getIpAddress());
        if (geo != null
            && geo.has("status")
            && "success".equalsIgnoreCase(geo.get("status").getAsString()))
          user.geoIpData = geo;
        country = user.country.get();
      }

      user.riskScore = Security.RiskScore.compute(user, player.getIpAddress(), country);

      boolean newIp =
          user.lastLoginIp != null && !user.lastLoginIp.isEmpty() && !user.lastLoginIp.equals(player.getIpAddress());
      boolean newCountry =
          country != null
              && user.lastLoginCountry != null
              && !user.lastLoginCountry.isEmpty()
              && !user.lastLoginCountry.equalsIgnoreCase(country);

      if (newIp && AuthCoreServer.config.session.intelligence.alertOnNewIp) {
        SecurityLog.log(
            "NEW_IP",
            username + " logged in from a new IP: " + player.getIpAddress() + " (was " + user.lastLoginIp + ")");
        Webhook.sendEmbed(
            "New IP Login",
            "**" + username + "** logged in from a new IP `" + player.getIpAddress() + "` (was `" + user.lastLoginIp + "`)",
            0xF1C40F);
      }

      if (newCountry && AuthCoreServer.config.session.intelligence.alertOnNewCountry) {
        SecurityLog.log(
            "NEW_COUNTRY",
            username + " logged in from a new country: " + country + " (was " + user.lastLoginCountry + ")");
        Webhook.sendEmbed(
            "New Country Login (possible account sharing)",
            "**" + username + "** logged in from **" + country + "** (was " + user.lastLoginCountry + ")",
            0xE67E22);
      }

      if (newCountry && AuthCoreServer.config.session.intelligence.blockOnNewCountry) {
        SecurityLog.log("BLOCKED_NEW_COUNTRY", username + " blocked from " + country);
        if (AuthCoreServer.config.session.shadowBan.enabled) {
          // Shadow-ban: generic disconnect instead of a security message
          connection.disconnect(
              net.ded3ec.compat.Compat.text(AuthCoreServer.config.session.shadowBan.disconnectReason));
          Webhook.sendEmbed(
              "Shadow-Banned Login",
              "**" + username + "** was silently blocked from **" + country + "**.",
              0xE74C3C);
          return;
        }
        user.kick(AuthCoreServer.messages.promptUserDifferentIpLoginNotAllowed);
        return;
      }

      // Network-based device fingerprint (anti account-sharing / anti account-trading)
      String fingerprint = net.ded3ec.security.Security.DeviceFingerprint.compute(player.getIpAddress(), country);
      if (user.deviceFingerprint != null
          && !user.deviceFingerprint.isEmpty()
          && !user.deviceFingerprint.equals(fingerprint)) {
        user.riskScore = Math.min(100, user.riskScore + 15);
        SecurityLog.log(
            "DEVICE_CHANGED",
            username + " logged in from a different network fingerprint (was "
                + user.deviceFingerprint + ", now " + fingerprint + ")");
        Webhook.sendEmbed(
            "Possible Account Sharing",
            "**" + username + "** logged in from a different network/device fingerprint.",
            0xE74C3C);
      }
      user.deviceFingerprint = fingerprint;

      // Email login alert (SMTP) for new IP / new country logins
      if ((newIp || newCountry) && EmailSender.isEnabled() && user.email != null) {
        EmailSender.sendAsync(
            user.email,
            "AuthCore - New login detected",
            "Hello " + username + ",\n\n"
                + "Your account was just used from:\n"
                + "  IP: " + player.getIpAddress() + "\n"
                + "  Country: " + (country != null ? country : "unknown") + "\n\n"
                + "If this was you, no action is needed. If not, contact the server staff "
                + "immediately - your account may have been compromised.");
        SecurityLog.log("EMAIL_ALERT", "login alert sent to " + user.email + " for " + username);
      }

      // Remember the login origin for the next intelligence pass
      user.lastLoginIp = player.getIpAddress();
      if (country != null) user.lastLoginCountry = country;
      user.update("Login intelligence");
    }

    // Kick cooldown
    if (user.lastKickedMs > 0
        && AuthCoreServer.config.session.cooldownAfterKickMs
            > (System.currentTimeMillis() - user.lastKickedMs)) {

      user.kick(
          AuthCoreServer.messages.promptUserCooldownAfterKickNotExpired,
          TimeManager.toDuration(
              (System.currentTimeMillis() - user.lastKickedMs)
                  - AuthCoreServer.config.session.cooldownAfterKickMs));
      return;
    }

    // Lobby full
    if (AuthCoreServer.config.lobby.maxLobbyUsers > 0
        && AuthCoreServer.config.lobby.maxLobbyUsers <= Lobby.users.size()) {

      user.kick(AuthCoreServer.messages.promptUserMaxLobbyUsersReached);
      return;
    }

    // Crash recovery: if a limbo snapshot file still exists for this player, the previous
    // limbo session ended without a clean unlock (server crash). Restore the saved
    // pre-limbo state BEFORE the fresh lobby lock captures it - otherwise the lock would
    // capture the crash-persisted Adventure mode + emptied inventory and the player would
    // stay stuck after logging in.
    java.nio.file.Path snapshot = net.ded3ec.models.Lobby.snapshotFile(uuid);
    if (java.nio.file.Files.exists(snapshot)) {
      boolean restored = net.ded3ec.models.Lobby.restoreCrashSnapshot(player, snapshot);
      AuthCoreServer.LOGGER.warn(
          restored,
          "Crash recovery: restored pre-limbo state for {} from a leftover limbo snapshot.",
          username);
      if (restored)
        net.ded3ec.security.SecurityLog.log(
            "LIMBO_CRASH_RECOVERY",
            username + " pre-limbo state restored after an unclean shutdown");
      try {
        java.nio.file.Files.deleteIfExists(snapshot);
      } catch (java.io.IOException ignored) {
        // cleanup is best-effort
      }
    }

    // Default: lock user in lobby
    user.lobby.lock();
  }

  /** Player leave handler */
  public static void onPlayerLeave(ServerGamePacketListenerImpl connection) {
    if (connection == null || !LEFT_CONNECTIONS.add(connection)) return;

    JOINED_CONNECTIONS.remove(connection);
    ServerPlayer player = connection.player;

    // Drop any pending action captcha (the single human verification).
    net.ded3ec.security.ActionCaptcha.onLeave(player);

    // ClientGuard: drop the per-player profile.
    net.ded3ec.security.ClientGuard.onLeave(player.getUUID());

    // Optional integrations: drop per-player caches.
    net.ded3ec.integration.ModIntegrations.onLeave(player.getUUID());

    // Drop the per-join premium-verification marker (vanilla Mojang session authentication).
    net.ded3ec.AuthCoreServer.clearPremiumVerified(player.getUUID());

    UUID uuid = player.getUUID();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    if (user != null) {

      if (user.isInLobby.get()) user.lobby.unlock();

      user.isActive = false;

      // Tell other mods / the proxy that this player is no longer authenticated
      net.ded3ec.network.AuthInterop.broadcast(player, false);

      user.isInCombatPenalty =
          user.lastCombatDetectMs > 0
              && AuthCoreServer.config.session.combatTimeout > 0
              && ((System.currentTimeMillis() - user.lastCombatDetectMs)
                  < AuthCoreServer.config.session.combatTimeout);

      // Combat-log punishment: kill players who disconnect while still in combat
      if (user.isInCombatPenalty && AuthCoreServer.config.session.combatLog.enabled) {
        AuthCoreServer.LOGGER.debug(
            true, "{} attempted to skip death penalty!", user.username);
        SecurityLog.log("COMBAT_LOGOUT", user.username + " disconnected during combat");

        if (AuthCoreServer.config.session.combatLog.killOnDisconnect) {
          try {
            net.ded3ec.compat.Compat.kill(player);
            AuthCoreServer.LOGGER.info(
                true,
                "{} was killed for combat-logging (disconnect during combat).",
                user.username);
            Webhook.send(
                ":skull: **" + user.username + "** was killed for combat-logging.");
          } catch (Exception err) {
            AuthCoreServer.LOGGER.debug(false, "Failed to kill combat-logger:", err);
          }
        }
      }

      user.update("Player Leave Cache");
    }
  }

  /** Server tick handler */
  public static void onEndServerTick(MinecraftServer server) {
    TpsManager.onTick();
    TaskScheduler.getInstance().onTick();

    // Action captcha progress measurement (per-tick physical task verification).
    net.ded3ec.security.ActionCaptcha.tickAll();

    // ClientGuard: ghost/settings watchdogs, re-challenges and the risk decision matrix.
    net.ded3ec.security.ClientGuard.tick(server);

    // Limbo re-assert guards (fallbacks for environments where mixins cannot apply):
    // - re-apply the lobby blindness/invisibility effects if a lobby player lost them
    // - revoke the flying ability / elytra glide every tick (fly hacks)
    // - zero upward velocity + re-anchor vertically drifting players (vertical fly hacks)
    // - teleport lobby players back when movement is disabled and they drifted
    // NOTE: no periodic inventory force-close here - the client treats a container-close
    // packet as "close the current screen", which would snap the CHAT window shut and make
    // /register / /login unusable. The inventory is fully protected by the click guards
    // (every click blocked + close-on-first-click).
    if (AuthCoreServer.config != null && !Lobby.users.isEmpty()) {
      boolean invisible = AuthCoreServer.config.lobby.invisibleUnauthorized;
      boolean blind = AuthCoreServer.config.lobby.applyBlindnessEffect;
      boolean noMove = !AuthCoreServer.config.lobby.allowMovement;

      for (Lobby lobby : new java.util.ArrayList<>(Lobby.users.values())) {
        User u = lobby.user;
        if (u == null || !u.isActive || u.player.get() == null) continue;
        ServerPlayer p = u.player.get();

        // Fly/elytra bypass guards - apply to EVERY lobby player, even when basic movement
        // is allowed: flight is never part of the lobby's allowed movement. Hack clients
        // that set the flying ability or started a glide are snapped back next tick (the
        // move-cancel mixin keeps the server entity anchored in the meantime).
        net.minecraft.world.entity.player.Abilities abilities =
            net.ded3ec.compat.Compat.getAbilities(p);
        if (abilities != null && abilities.flying) abilities.flying = false;
        if (p.isFallFlying()) net.ded3ec.compat.Compat.stopFallFlying(p);

        // Vertical fly watchdog: with movement fully disabled the server entity must never
        // climb. Zero any upward velocity and re-anchor the player the moment the server
        // position drifts off the lobby anchor on ANY axis (packet-level hacks that slip
        // past the move-cancel are corrected here, and the client gets snapped back).
        if (noMove) {
          if (p.getDeltaMovement().y > 0.2) {
            p.setDeltaMovement(p.getDeltaMovement().multiply(1.0, 0.0, 1.0));
            net.ded3ec.security.SecurityLog.log(
                "LIMBO_VERTICAL_FLIGHT",
                u.username + " vertical velocity detected in limbo - velocity zeroed");
            lobby.teleportBack();
          }
          // Tight 0.25-block anchor: on runtimes without the movement-cancel mixin
          // (Fabric/Forge 1.16-1.21 without a mixin refmap) this per-tick re-assert is
          // the ONLY server-side lock - a loose radius there lets the player visibly
          // walk up to a full block before the snap-back.
          if (lobby.isFarFromLobbyPos(p.getX(), p.getY(), p.getZ(), 0.25))
            lobby.handleTeleport();
        }

        if (invisible && !p.isInvisible())
          net.ded3ec.compat.Compat.addStatusEffect(
              p,
              new net.minecraft.world.effect.MobEffectInstance(
                  net.minecraft.world.effect.MobEffects.INVISIBILITY,
                  Integer.MAX_VALUE,
                  1,
                  false,
                  false));
        if (blind && p.getActiveEffects().stream().noneMatch(
            e -> e.getEffect() == net.minecraft.world.effect.MobEffects.BLINDNESS))
          net.ded3ec.compat.Compat.addStatusEffect(
              p,
              new net.minecraft.world.effect.MobEffectInstance(
                  net.minecraft.world.effect.MobEffects.BLINDNESS,
                  Integer.MAX_VALUE,
                  1,
                  false,
                  false));
      }
    }
  }
}
