package in.potenfyr.authcore.events;

import in.potenfyr.authcore.models.Messages;
import in.potenfyr.authcore.network.EmailSender;
import in.potenfyr.authcore.network.McApiManager;
import in.potenfyr.authcore.network.RedisManager;
import in.potenfyr.authcore.network.Webhook;
import in.potenfyr.authcore.security.RateLimiter;
import in.potenfyr.authcore.security.Security;
import in.potenfyr.authcore.security.SecurityLog;
import in.potenfyr.authcore.util.TaskScheduler;
import in.potenfyr.authcore.util.TpsManager;

import java.util.UUID;

import in.potenfyr.authcore.AuthCoreServer;
import in.potenfyr.authcore.models.Lobby;
import in.potenfyr.authcore.models.User;
import in.potenfyr.authcore.util.TimeManager;
import in.potenfyr.authcore.util.*;
import in.potenfyr.authcore.security.*;
import in.potenfyr.authcore.compat.Compat;
import in.potenfyr.authcore.network.*;

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

  /** Login packet sequence tracker: connection → sequence state. */
  private static final java.util.Map<Object, PacketSequence> PACKET_SEQUENCES =
      new java.util.concurrent.ConcurrentHashMap<>();

  /** Maximum time (ms) the client has to complete the expected packet sequence. */
  private static final long PACKET_SEQUENCE_TIMEOUT_MS = 15_000L; // 15s

  /** Expected login packet sequence (HELLO → SETTINGS → READY). */
  private enum SequenceState { EXPECTING_HELLO, EXPECTING_SETTINGS, EXPECTING_READY, COMPLETE }

  /** Per-connection packet sequence state. */
  private static final class PacketSequence {
    final Object connection;
    final long createdAtMs;
    volatile SequenceState state = SequenceState.EXPECTING_HELLO;
    volatile long lastPacketMs;
    volatile boolean anomalyLogged = false;

    PacketSequence(Object connection) {
      this.connection = connection;
      this.createdAtMs = System.currentTimeMillis();
      this.lastPacketMs = this.createdAtMs;
    }

    /** Records a packet and advances the state machine. Returns true when anomalous. */
    boolean record(String packetName) {
      lastPacketMs = System.currentTimeMillis();
      return switch (state) {
        case EXPECTING_HELLO -> {
          if ("HELLO".equalsIgnoreCase(packetName) || "LOGIN".equalsIgnoreCase(packetName)) {
            state = SequenceState.EXPECTING_SETTINGS;
            yield false;
          }
          yield !anomalyLogged; // unexpected first packet
        }
        case EXPECTING_SETTINGS -> {
          if ("SETTINGS".equalsIgnoreCase(packetName)
              || "CONFIGURATION".equalsIgnoreCase(packetName)) {
            state = SequenceState.EXPECTING_READY;
            yield false;
          }
          yield !anomalyLogged; // missing SETTINGS after HELLO
        }
        case EXPECTING_READY -> {
          if ("READY".equalsIgnoreCase(packetName) || "FINISH".equalsIgnoreCase(packetName)) {
            state = SequenceState.COMPLETE;
            yield false;
          }
          yield !anomalyLogged; // missing READY
        }
        case COMPLETE -> false; // post-login packets are fine
      };
    }

    boolean isExpired() {
      return System.currentTimeMillis() - createdAtMs > PACKET_SEQUENCE_TIMEOUT_MS;
    }
  }

  /** Tracks a login packet for sequence validation. Safe to call from mixins (null-guarded). */
  public static void trackPacket(Object connection, String packetName) {
    if (connection == null || packetName == null || packetName.isBlank()) return;
    Object key = Compat.getConnection(connection);
    if (key == null) key = connection;
    PacketSequence seq = PACKET_SEQUENCES.get(key);
    if (seq == null && key != connection) {
      seq = PACKET_SEQUENCES.get(connection);
    }
    if (seq == null) return;
    boolean anomalous = seq.record(packetName);
    if (anomalous) {
      String username = Compat.getConnectionUsername(connection);
      SecurityLog.log("PACKET_SEQUENCE_ANOMALY",
          username + " sent unexpected packet '" + packetName
              + "' during login (state=" + seq.state + ")");
    }
  }

  /** Creates a packet sequence tracker for a new connection. */
  public static void startPacketTracking(Object connection) {
    if (connection == null) return;
    Object key = Compat.getConnection(connection);
    if (key == null) key = connection;
    PacketSequence seq = new PacketSequence(key);
    PACKET_SEQUENCES.put(key, seq);
    if (key != connection) {
      PACKET_SEQUENCES.put(connection, seq);
    }
  }

  /** Marks the login sequence as complete (called when login finishes). */
  public static void completePacketTracking(Object connection) {
    if (connection == null) return;
    Object key = Compat.getConnection(connection);
    if (key == null) key = connection;
    PacketSequence seq = PACKET_SEQUENCES.get(key);
    if (seq == null && key != connection) {
      seq = PACKET_SEQUENCES.get(connection);
    }
    if (seq == null) return;
    seq.record("READY");
  }

  /** Cleans up packet sequence tracking for a connection. */
  public static void cleanupPacketTracking(Object connection) {
    if (connection == null) return;
    Object key = Compat.getConnection(connection);
    if (key != null) PACKET_SEQUENCES.remove(key);
    PACKET_SEQUENCES.remove(connection);
  }

  /** Player join handler (loader-neutral - the fabric PacketSender arg was unused). */
  public static void onPlayerJoin(ServerGamePacketListenerImpl connection) {
    if (connection == null || !JOINED_CONNECTIONS.add(connection)) return;

    ServerPlayer player = connection.player;

    // Protocol-level username validation. A vanilla client can only produce [A-Za-z0-9_.-]{1,16},
    // but a hacked client controls the hello name: a name with control characters, a newline or
    // lookalike Unicode is a log-injection / staff-impersonation vector once it reaches the
    // database, chat and staff tooling. Reject it before any state is created for the session.
    String joinName = player == null ? null : player.getName().getString();
    if (joinName != null && !in.potenfyr.authcore.util.Sanitizer.isSafeUsername(joinName)) {
      AuthCoreServer.LOGGER.warn(
          false,
          "Rejected a join with an invalid username: {}",
          in.potenfyr.authcore.util.Sanitizer.logDetail(joinName));
      try {
        connection.disconnect(in.potenfyr.authcore.compat.Compat.text("Invalid username"));
      } catch (RuntimeException ignored) {
        // the connection is going away anyway
      }
      return;
    }

    // Map-image crash recovery: remove temporary maps left behind by a crash or a mid-display
    // disconnect (their ids are persisted to config/authcore/map-images.txt).
    in.potenfyr.authcore.util.MapImageService.onPlayerJoin(player);

    AuthCoreServer.LOGGER.debug(
        false,
        "{} | join started (online-mode server: {})",
        player.getName().getString(),
        AuthCoreServer.isServerOnline());

    // Detect the real server.properties online-mode once (it is ALWAYS taken from the
    // Minecraft server - there is no config override, so offline servers are handled
    // correctly without any setup).
    net.minecraft.server.MinecraftServer joinServer = in.potenfyr.authcore.compat.Compat.getServer(player);
    if (joinServer != null)
      AuthCoreServer.detectServerOnlineMode(
          in.potenfyr.authcore.compat.Compat.serverUsesAuthentication(joinServer));
    boolean onlineServer = AuthCoreServer.isServerOnline();

    // ClientGuard: per-player profile + confusable-name impersonation scan.
    in.potenfyr.authcore.security.ClientGuard.onJoin(player);
    in.potenfyr.authcore.security.ClientGuard.checkConfusableName(player.getName().getString());

    // Concurrent-farm detection: flag IPs that bring many distinct usernames in a short window.
    in.potenfyr.authcore.security.ClientGuard.checkConcurrentFarm(
        in.potenfyr.authcore.security.ClientGuard.resolveRealIp(player),
        player.getName().getString(),
        player.getUUID());

    // Register interop channels (other mods / proxy plugin messaging)
    in.potenfyr.authcore.network.AuthInterop.register(player);

    // Maintenance mode: block all joins
    if (AuthCoreServer.config.session.maintenance.enabled) {
      connection.disconnect(
          in.potenfyr.authcore.compat.Compat.text(AuthCoreServer.config.session.maintenance.kickMessage));
      return;
    }

    UUID uuid = player.getUUID();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);
    boolean brandNew = false;

    // Consume THIS connection's server-side verification marker exactly once (see
    // AuthCoreServer.consumePremiumVerified): the marker is set by the login handshake
    // mixin for this join alone and must never leak to a second connection with the
    // same name/uuid.
    final boolean premiumVerifiedThisJoin =
        AuthCoreServer.consumePremiumVerified(
            uuid, in.potenfyr.authcore.compat.Compat.networkConnection(connection));

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
        in.potenfyr.authcore.security.ClientGuard.profile(player).addSignal(
            in.potenfyr.authcore.security.ClientGuard.Signal.CONCURRENT_LOGIN);
        SecurityLog.log(
            "CONCURRENT_LOGIN",
            username + " logged in from a second connection - kicking the old one");
        in.potenfyr.authcore.network.Webhook.sendEmbed(
            "AuthCore - Concurrent Login",
            "**" + username + "** logged in from a second connection - old connection kicked.",
            0xF1C40F);

        // Release the OLD session's limbo state FIRST: the per-user Lobby instance is reused, so
        // a stale lobby entry would make the NEW session's lock() a no-op and the old snapshot
        // (position/inventory/game mode) would be restored onto the new session at login.
        if (user.isInLobby.get()) user.lobby.unlock();

        // Disconnect the old connection DIRECTLY instead of user.kick(): kick() arms the account
        // kick cooldown (cooldownAfterKickMs, 2 min by default) and the progressive-punishment
        // counter - the NEW join below would then be kicked by the cooldown check, so "kick-old"
        // would kick BOTH connections.
        AuthCoreServer.LOGGER.toKick(
            false, user.connection, AuthCoreServer.messages.promptUserAnotherAccountSession);
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
          onlineServer && !in.potenfyr.authcore.compat.Compat.isOfflineUuid(username, uuid);
      user = new User(uuid, username, System.currentTimeMillis(), premiumNew);
      brandNew = true;
    }

    // Final snapshot for lambdas (user may be reassigned above).
    final User joinUser = user;

    // Companion session-claim verification (delayed a few seconds so the client's
    // HELLO / SESSION_TOKEN_ECHO payloads have time to arrive).
    in.potenfyr.authcore.util.TaskScheduler.getInstance()
        .setTimeout(() -> in.potenfyr.authcore.security.ClientGuard.verifySessionClaim(player), 5000L);

    // Per-IP join rate limit (bot flood protection)
    if (AuthCoreServer.config.session.rateLimit.enabled
        && !McApiManager.isPrivateAddress(player.getIpAddress())
        && !RateLimiter.tryAcquire(
            player.getIpAddress(),
            AuthCoreServer.config.session.rateLimit.maxJoinsPerWindow,
            AuthCoreServer.config.session.rateLimit.windowMs)) {

      String reason = AuthCoreServer.config.session.rateLimit.overLimitMessage;
      in.potenfyr.authcore.security.SecurityLog.log("JOIN_RATE_LIMITED", player.getIpAddress() + " | " + username);
      AuthCoreServer.LOGGER.toKick(
          false,
          connection,
          new in.potenfyr.authcore.models.Messages.KickTemplate() {
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
        && in.potenfyr.authcore.network.RedisManager.hasRemoteSession(uuid)) {
      AuthCoreServer.LOGGER.toKick(
          false, connection, AuthCoreServer.messages.promptUserAnotherAccountSession);
      return;
    }

    // Network-wide ban (/authcore network-ban, shared through Redis).
    if (AuthCoreServer.config.database.redis.enabled
        && in.potenfyr.authcore.network.RedisManager.isBannedRemotely(username)) {
      SecurityLog.log("NETWORK_BAN_BLOCKED", username + " | " + player.getIpAddress());
      AuthCoreServer.LOGGER.toKick(
          false, connection, AuthCoreServer.messages.promptUserNetworkBanned);
      return;
    }

    // Persistent account lock check
    if (AuthCoreServer.config.session.accountLock.enabled && user.isLocked()) {
      long remainingMs = user.lockUntilMs - System.currentTimeMillis();
      AuthCoreServer.LOGGER.toUser(
          false,
          connection,
          AuthCoreServer.messages.promptUserAccountLocked,
          in.potenfyr.authcore.util.TimeManager.toDuration(Math.max(remainingMs, 1000)));
      AuthCoreServer.LOGGER.toKick(
          false, connection, AuthCoreServer.messages.promptUserAuthenticationExpiredTimeout,
          in.potenfyr.authcore.util.TimeManager.toDuration(Math.max(remainingMs, 1000)));
      return;
    }

    // Establish connection
    user.connect(connection);

    // Limbo crash recovery FIRST: a leftover snapshot means the previous limbo session ended
    // without a clean unlock (server crash / kill). It must be restored and deleted before ANY
    // authentication branch can return early (session resume, premium auto-login, kicks) -
    // otherwise the crash-persisted limbo state (forced Adventure, emptied inventory, invulnerable)
    // sticks in playerdata, and a later join that DOES reach the recovery reloads stale items over
    // the live inventory (1.0.3 audit: soft-lock + item duplication).
    recoverLimboSnapshot(player, username, uuid);

    // Killed by the combat-log punishment on the last logout: say so (otherwise the player only
    // sees a cause-less death screen and blames the login - issue #11).
    notifyCombatLogDeath(user);

    // Per-IP allow/deny rules (ip-rules.conf)
    if (in.potenfyr.authcore.security.IpRules.isDenied(player.getIpAddress())) {
      SecurityLog.log("IP_DENIED", player.getIpAddress() + " | " + username + " blocked by ip-rules.conf");
      AuthCoreServer.LOGGER.toKick(
          false,
          connection,
          AuthCoreServer.messages.promptUserProxyNotAllowed);
      return;
    }

    // Fast-rejoin alert (bot pattern: disconnect + reconnect within milliseconds)
    if (AuthCoreServer.config.session.rateLimit.alertOnFastRejoin
        && !in.potenfyr.authcore.security.RateLimiter.tryAcquire(
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
        in.potenfyr.authcore.util.TaskScheduler.getInstance()
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

    // NOTE: the same-IP restriction is enforced ONLY on the session-resume fast-path below
    // (sameIpOk). It must NOT abort the join here: the stored IP is refreshed by
    // User.login() after a SUCCESSFUL authentication, so kicking a different-IP join before
    // /login can run permanently locked out every player whose ISP rotated their IP - the
    // player never reached login() and therefore could never update the address. A mismatch
    // simply suppresses the silent session resume and forces a normal /login, which is
    // equally safe against session hijacking (the companion token alone cannot resume a
    // session from another IP).

    // Online-mode-only servers: when offline (cracked) players are disallowed, reject any
    // join the server did NOT verify as a genuine online-mode session. On an online-mode
    // server only real-UUID clients were verified by vanilla (offline-UUID clients were
    // accepted by the hybrid login flow and are offline); on an offline-mode server a real
    // online-mode player was verified by the handshake mixin, everyone else is offline and
    // is kicked - before session resume, register or login can happen.
    if (!AuthCoreServer.config.session.authentication.allowOfflinePlayers
        && !((onlineServer && !in.potenfyr.authcore.compat.Compat.isOfflineUuid(username, uuid))
            || premiumVerifiedThisJoin)) {
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

    // A mismatching IP no longer kicks; it only denies the silent resume. Log it so admins
    // can tell why a returning player was asked to authenticate again.
    if (!sameIpOk && user.uuid.equals(uuid) && user.isActiveSession.get()) {
      AuthCoreServer.LOGGER.debug(
          false,
          "{} | join - active session not resumed: join IP {} differs from session IP {} "
              + "(re-authentication required)",
          username,
          player.getIpAddress(),
          user.ipAddress);
    }

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
        (onlineServer && !in.potenfyr.authcore.compat.Compat.isOfflineUuid(username, uuid))
            || premiumVerifiedThisJoin;
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

    // Premium name ownership on an ONLINE server: the server verifies every genuine premium
    // profile, so a session that was NOT verified as premium can never use a stored premium
    // account's name. Without this check an offline client (hybrid mode) claiming a premium
    // player's name reached the limbo and could /register a password onto that account.
    if (onlineServer && user.isPremium && !verifiedPremium && !user.uuid.equals(uuid)) {
      SecurityLog.log(
          "PREMIUM_NAME_COLLISION",
          username + " joined with a different uuid while the account is premium - rejected");
      user.kick(AuthCoreServer.messages.promptUserPremiumDifferentUUID);
      return;
    }

    // Stale premium flag: the account claims online-mode but THIS session was not verified as
    // premium. Only the IN-MEMORY flag is downgraded here so the player is asked to
    // register/login; the DB row is NOT written before authentication - otherwise any client
    // claiming a premium player's name (hybrid mode, name collision) could permanently clear
    // that account's online-mode flag. The downgrade is persisted after a successful password
    // login (Login#finishLogin), where ownership is proven.
    if (user.isPremium && !verifiedPremium) {
      user.isPremium = false;
      AuthCoreServer.LOGGER.info(
          true,
          "{} was registered as an online-mode account but this session was not verified as "
              + "online-mode - using offline-mode authentication for this session "
              + "(register/login required).",
          user.username);
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
              in.potenfyr.authcore.compat.Compat.text(AuthCoreServer.config.session.shadowBan.disconnectReason));
          Webhook.sendEmbed(
              "Shadow-Banned Login",
              "**" + username + "** was silently blocked from **" + country + "**.",
              0xE74C3C);
          return;
        }
        user.kick(AuthCoreServer.messages.promptUserNewCountryLoginNotAllowed);
        return;
      }

      // Network-based device fingerprint (anti account-sharing / anti account-trading)
      String fingerprint = in.potenfyr.authcore.security.Security.DeviceFingerprint.compute(player.getIpAddress(), country);
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
      if ((newIp || newCountry)
          && EmailSender.isEnabled()
          && AuthCoreServer.config.session.email.alertOnNewLogin
          && user.email != null) {
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

    // Default: lock user in lobby (the crash recovery already ran right after User.connect)
    user.lobby.lock();
  }

  /**
   * Restores (and clears) a leftover limbo snapshot from an unclean shutdown.
   *
   * <p>Runs as the FIRST join step so no authentication shortcut can skip it.
   */
  private static void recoverLimboSnapshot(
      ServerPlayer player, String username, java.util.UUID uuid) {
    try {
      java.nio.file.Path snapshot = in.potenfyr.authcore.models.Lobby.snapshotFile(uuid);
      if (!java.nio.file.Files.exists(snapshot)) return;
      boolean restored = in.potenfyr.authcore.models.Lobby.restoreCrashSnapshot(player, snapshot);
      AuthCoreServer.LOGGER.warn(
          restored,
          "Crash recovery: restored pre-limbo state for {} from a leftover limbo snapshot.",
          username);
      if (restored)
        in.potenfyr.authcore.security.SecurityLog.log(
            "LIMBO_CRASH_RECOVERY",
            username + " pre-limbo state restored after an unclean shutdown");
      try {
        java.nio.file.Files.deleteIfExists(snapshot);
      } catch (java.io.IOException ignored) {
        // cleanup is best-effort
      }
    } catch (RuntimeException | LinkageError ignored) {
      // recovery is best-effort - a corrupt snapshot file must never block the join
    }
  }

  /**
   * Connections the SERVER disconnected (AuthCore kicks, kick-old duplicates, timeouts). Weak keys:
   * an entry can never outlive its connection even if the leave event never fires.
   */
  private static final java.util.Set<Object> SERVER_INITIATED_DISCONNECTS =
      java.util.Collections.synchronizedSet(
          java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));

  /** Players killed by the combat-log punishment, told why on their next join (bounded). */
  private static final java.util.Set<UUID> COMBAT_LOG_KILLED =
      java.util.concurrent.ConcurrentHashMap.newKeySet();

  /** Marks a connection as disconnected by the server (never a combat-log escape). */
  public static void markServerInitiatedDisconnect(Object connection) {
    if (connection != null) SERVER_INITIATED_DISCONNECTS.add(connection);
  }

  /**
   * Why a combat-tagged disconnect must NOT be punished, or null when it is a real combat log.
   *
   * <p>Killing a player is irreversible and costs them every item, so only a disconnect the player
   * chose is punished: never one the server caused (AuthCore kick, duplicate-login kick, shutdown /
   * restart), never a player who was still unauthenticated in the limbo, and never one already
   * dead (a second death on the death screen).
   */
  private static String combatLogExemption(
      ServerGamePacketListenerImpl connection, ServerPlayer player, boolean leftFromLimbo) {
    if (leftFromLimbo) return "still unauthenticated in the limbo";
    if (SERVER_INITIATED_DISCONNECTS.contains(connection)) return "disconnected by the server";
    try {
      MinecraftServer server = in.potenfyr.authcore.compat.Compat.getServer(player);
      if (server != null && !server.isRunning()) return "server shutting down";
    } catch (RuntimeException | LinkageError ignored) {
      // unknown server state - fall through
    }
    if (player == null || !(player.getHealth() > 0.0F)) return "already dead";
    return null;
  }

  /** Tells a returning player that the combat-log punishment killed them (once). */
  public static void notifyCombatLogDeath(User user) {
    if (user == null || user.uuid == null || !COMBAT_LOG_KILLED.remove(user.uuid)) return;
    try {
      AuthCoreServer.LOGGER.toUser(
          false, user.connection, AuthCoreServer.messages.promptUserKilledForCombatLog);
    } catch (RuntimeException ignored) {
      // the notice is best-effort
    }
  }

  /** Player leave handler */
  public static void onPlayerLeave(ServerGamePacketListenerImpl connection) {
    if (connection == null || !LEFT_CONNECTIONS.add(connection)) return;
    // The dedupe marker and any packet-sequence state must never outlive the connection:
    // these sets are keyed by the connection object (a strong reference to the player/level),
    // so a missed removal leaks one entry per join for the whole server uptime.
    try {

    JOINED_CONNECTIONS.remove(connection);
    ServerPlayer player = connection.player;

    UUID uuid = player.getUUID();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    // Connection-identity guard FIRST. Under "kick-old" duplicate logins (and any window where two
    // connections briefly share one User), the kicked OLD connection's disconnect event can arrive
    // AFTER the new join already registered its fresh limbo: a stale teardown must not touch the
    // NEW session's state - including the per-player caches below, which are keyed by UUID and
    // would otherwise delete the new session's ClientGuard profile, map image, captcha state and
    // premium marker.
    boolean staleTeardown = user != null && user.connection != null && user.connection != connection;

    if (!staleTeardown) {
      // Give back / remove any map image the player still holds BEFORE the player data is saved,
      // so a normal disconnect can never persist the temporary map.
      in.potenfyr.authcore.util.MapImageService.onPlayerLeave(player);

      // Drop any pending action captcha (the single human verification).
      in.potenfyr.authcore.security.ActionCaptcha.onLeave(player);

      // ClientGuard: drop the per-player profile.
      in.potenfyr.authcore.security.ClientGuard.onLeave(player);

      // Optional integrations: drop per-player caches.
      in.potenfyr.authcore.integration.ModIntegrations.onLeave(player.getUUID());

      // Drop the per-join premium-verification marker (vanilla Mojang session authentication).
      in.potenfyr.authcore.AuthCoreServer.clearPremiumVerified(player.getUUID());
    }

    // Packet sequence tracking cleanup - keyed by CONNECTION, always safe to run.
    cleanupPacketTracking(connection);

    if (user != null) {

      // Drop the join-time uuid alias (see User.connect): the account must remain reachable only
      // through its canonical stored uuid once this session is gone. Skipped for a stale leave -
      // a stale connection sharing the drifted uuid would drop the NEW session's alias.
      if (!staleTeardown) user.releaseJoinUuid(uuid);

      // Captured BEFORE the unlock below: a player who was still unauthenticated never took part in
      // this session's gameplay and can never be a combat logger.
      final boolean leftFromLimbo = user.isInLobby.get();

      if (user.isInLobby.get()) {
        if (staleTeardown) {
          AuthCoreServer.LOGGER.debug(
              false,
              "{} | stale leave of an old connection ignored for limbo teardown "
                  + "(new session already owns the user)",
              username);
        } else {
          user.lobby.unlock();
        }
      }
      if (staleTeardown) return;

      user.isActive = false;
      // The password proof belongs to this connection only (and must not pin it in memory).
      user.passwordProvenOn = null;

      // Tell other mods / the proxy that this player is no longer authenticated
      in.potenfyr.authcore.network.AuthInterop.broadcast(player, false);

      final long lastCombat = user.lastCombatDetectMs;
      user.isInCombatPenalty =
          user.lastCombatDetectMs > 0
              && AuthCoreServer.config.session.combatTimeout > 0
              && ((System.currentTimeMillis() - user.lastCombatDetectMs)
                  < AuthCoreServer.config.session.combatTimeout);
      // The combat window belongs to THIS session only - it must never carry over into the next one
      // (a quick rejoin + leave used to punish the player a second time for the same fight).
      user.lastCombatDetectMs = 0;

      // Combat-log punishment: kill players who CHOSE to disconnect while still in combat.
      if (user.isInCombatPenalty && AuthCoreServer.config.session.combatLog.enabled) {
        String exemption = combatLogExemption(connection, player, leftFromLimbo);
        if (exemption != null) {
          AuthCoreServer.LOGGER.info(
              true,
              "{} left during combat but was not punished ({}).",
              user.username,
              exemption);
        } else {
          SecurityLog.log("COMBAT_LOGOUT", user.username + " disconnected during combat");
          if (AuthCoreServer.config.session.combatLog.killOnDisconnect) {
            boolean killed = false;
            try {
              killed = in.potenfyr.authcore.compat.Compat.kill(player);
            } catch (RuntimeException | LinkageError err) {
              AuthCoreServer.LOGGER.debug(false, "Failed to kill combat-logger:", err);
            }
            if (killed) {
              // INFO on purpose (issue #11): the death happens while the player is disconnecting,
              // so on the next join they only see a death screen without a cause. This line and
              // the join notice are the only trace of WHY.
              AuthCoreServer.LOGGER.info(
                  true,
                  "{} was killed for combat-logging (disconnected {} ms after combat).",
                  user.username,
                  String.valueOf(System.currentTimeMillis() - lastCombat));
              COMBAT_LOG_KILLED.add(uuid);
              if (COMBAT_LOG_KILLED.size() > 10_000) COMBAT_LOG_KILLED.clear();
              Webhook.send(":skull: **" + user.username + "** was killed for combat-logging.");
            } else {
              AuthCoreServer.LOGGER.warn(
                  false,
                  "{} combat-logged, but this runtime offers no safe kill path - not punished.",
                  user.username);
            }
          }
        }
      }

      user.update("Player Leave Cache");
    }
    } finally {
      LEFT_CONNECTIONS.remove(connection);
      PACKET_SEQUENCES.remove(connection);
      SERVER_INITIATED_DISCONNECTS.remove(connection);
    }
  }

  /** Server tick handler */
  public static void onEndServerTick(MinecraftServer server) {
    if (server != null) {
      AuthCoreServer.server = server;
      if (!AuthCoreServer.isServerDetected()) {
        AuthCoreServer.detectServerOnlineMode(in.potenfyr.authcore.compat.Compat.serverUsesAuthentication(server));
      }
    }

    TpsManager.onTick();
    TaskScheduler.getInstance().onTick();

    // Post-restore fall-safety windows (issue #11): pin the fall distance and re-land a player the
    // limbo restore could not place on safe ground.
    Lobby.tickRestoreGuards(server);

    // Action captcha progress measurement (per-tick physical task verification).
    in.potenfyr.authcore.security.ActionCaptcha.tickAll();

    // ClientGuard: ghost/settings watchdogs, re-challenges and the risk decision matrix.
    in.potenfyr.authcore.security.ClientGuard.tick(server);

    // Packet sequence validation: prune stale entries (connections that disconnected without
    // triggering the leave handler, e.g. network timeout during login).
    if (!PACKET_SEQUENCES.isEmpty()) {
      PACKET_SEQUENCES.entrySet().removeIf(e -> {
        PacketSequence seq = e.getValue();
        return seq == null || seq.isExpired();
      });
    }

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

        // Dead-on-join players who respawned inside the limbo (fallback for the packet hook):
        // record the respawn point before the anchor guard below moves the new entity.
        lobby.onRespawnInLimbo(p);

        // Fly/elytra bypass guards - apply to EVERY lobby player, even when basic movement
        // is allowed: flight is never part of the lobby's allowed movement. Hack clients
        // that set the flying ability or started a glide are snapped back next tick (the
        // move-cancel mixin keeps the server entity anchored in the meantime).
        net.minecraft.world.entity.player.Abilities abilities =
            in.potenfyr.authcore.compat.Compat.getAbilities(p);
        if (abilities != null && abilities.flying) abilities.flying = false;
        if (p.isFallFlying()) in.potenfyr.authcore.compat.Compat.stopFallFlying(p);

        // Vertical fly watchdog: with movement fully disabled the server entity must never
        // climb. Zero any upward velocity and re-anchor the player the moment the server
        // position drifts off the lobby anchor on ANY axis (packet-level hacks that slip
        // past the move-cancel are corrected here, and the client gets snapped back).
        if (noMove) {
          if (p.getDeltaMovement().y > 0.2) {
            p.setDeltaMovement(p.getDeltaMovement().multiply(1.0, 0.0, 1.0));
            in.potenfyr.authcore.security.SecurityLog.log(
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
          Lobby.applyLimboEffect(
              p,
              new net.minecraft.world.effect.MobEffectInstance(
                  net.minecraft.world.effect.MobEffects.INVISIBILITY,
                  Integer.MAX_VALUE,
                  1,
                  false,
                  false));
        if (blind && p.getActiveEffects().stream().noneMatch(
            e -> e.getEffect() == net.minecraft.world.effect.MobEffects.BLINDNESS))
          Lobby.applyLimboEffect(
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
