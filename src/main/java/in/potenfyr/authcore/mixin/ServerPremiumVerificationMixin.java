package in.potenfyr.authcore.mixin;


import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import in.potenfyr.authcore.AuthCoreServer;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundKeyPacket;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Server-side premium verification for OFFLINE-mode servers.
 *
 * <p>On offline servers vanilla skips the Mojang session check, so AuthCore re-runs it
 * without any external API calls: the login mixin forces the vanilla encryption handshake
 * ({@code ClientboundHelloPacket}), the client answers with its session token
 * ({@code ServerboundKeyPacket}), and the server verifies the session with its own
 * {@code MinecraftSessionService.hasJoinedServer(...)}, exactly like an online-mode server.
 *
 * <p>Fail-safe by design:
 * <ul>
 *   <li>If the handshake cannot be started (API shape missing), the vanilla offline flow
 *       continues untouched (no premium, lobby + register).
 *   <li>If Mojang does not confirm the session (cracked client, fake session, API down),
 *       the join continues as a normal offline player. Never kicked, never blocked.
 *   <li>If the client never answers the handshake, a watchdog replays the original hello
 *       after 15 seconds so the player joins as offline instead of hanging.
 * </ul>
 *
 * <p>The verified player keeps their offline UUID (the offline-mode account machinery stays
 * intact) and is marked premium via {@link AuthCoreServer#markPremiumVerified(UUID)}.
 */
/*? if fabric {*/
  @net.fabricmc.api.Environment(net.fabricmc.api.EnvType.SERVER)
  /*?}*/
@Pseudo
@Mixin(ServerLoginPacketListenerImpl.class)
@SuppressWarnings({"mapping", "unresolvable-target"})
abstract class ServerPremiumVerificationMixin {

  /** Handlers currently waiting for the client's key packet: handler â†’ original hello. */
  private static final Map<Object, Object> PENDING = new ConcurrentHashMap<>();

  /** Handlers whose premium verification already completed (success, failure or fallback).
   *  The hello replay after a verification must NOT re-arm the encryption handshake -
   *  otherwise a failed verification loops forever (handshake â†’ key â†’ replay â†’ handshake)
   *  and the player can never finish logging in. */
  private static final java.util.Set<Object> HANDLED = java.util.concurrent.ConcurrentHashMap.newKeySet();

  /** How long to wait for the client's key packet before falling back to offline. */
  private static final long HANDSHAKE_TIMEOUT_MS = 15_000L;

  /** Safety cap: the disconnect hook removes both entries, but a forged/aborted login must never
   *  let the tracking maps grow without bound if that hook does not fire on some version. */
  private static final int MAX_TRACKED_HANDSHAKES = 4096;

  private static void boundTracking() {
    if (HANDLED.size() > MAX_TRACKED_HANDSHAKES) HANDLED.clear();
    if (PENDING.size() > MAX_TRACKED_HANDSHAKES) PENDING.clear();
  }

  /**
   * When premium verification is wanted (offline server + premium auto-login) and the
   * client claims a real (non-offline) account UUID: start the encryption handshake and hold the vanilla
   * offline flow until the verification completes (or times out). Only the FIRST hello of a
   * connection is intercepted - a replayed hello (after verification succeeded/failed) lets
   * the vanilla offline accept flow run untouched.
   */
  @Inject(
      method = "handleHello(Lnet/minecraft/network/protocol/login/ServerboundHelloPacket;)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$verifyHello(ServerboundHelloPacket packet, CallbackInfo ci) {
    try {
      if (!AuthCoreServer.isPremiumVerificationEnabled()) return;
      if (HANDLED.contains(this)) return; // already verified (or fell back) - never re-verify

      String username = readHelloName(packet);
      UUID claimed = readHelloId(packet);
      if (username == null || claimed == null) return;
      // Only a client claiming a REAL (non-offline) account UUID is challenged. The old check was
      // inverted: it challenged clients sending the OFFLINE uuid (cracked clients - the forced
      // handshake can only fail for them, and a vanilla client disconnects on a failed session
      // join) and skipped every genuine premium client, so premium auto-login on offline servers
      // never triggered for real accounts.
      if (in.potenfyr.authcore.compat.Compat.isOfflineUuid(username, claimed)) return;

      // Start the handshake FIRST - only cancel vanilla's offline flow when it succeeded,
      // so a failure can never strand the player.
      if (!in.potenfyr.authcore.compat.Compat.loginStartHandshake(this)) return;

      PENDING.put(this, packet);
      boundTracking();
      ci.cancel();

      // Watchdog: if the client never answers the handshake, join as a normal offline player.
      in.potenfyr.authcore.util.TaskScheduler.getInstance()
          .setTimeout(() -> fallbackToOffline(this, packet), HANDSHAKE_TIMEOUT_MS);
    } catch (RuntimeException err) {
      // Never break the login flow - vanilla offline handling continues.
      AuthCoreServer.LOGGER.debug(
          false, "Online-mode verification setup failed - continuing as offline:", err);
    }
  }

  /**
   * Completes the handshake for a pending verification and verifies the session with the
   * server's own session service (on the IO pool, never blocking the network thread).
   */
  @Inject(
      method = "handleKey(Lnet/minecraft/network/protocol/login/ServerboundKeyPacket;)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$verifyKey(ServerboundKeyPacket packet, CallbackInfo ci) {
    Object hello = PENDING.remove(this);
    if (hello == null) return; // not our handshake - vanilla flow (online server) proceeds

    ci.cancel();

    try {
      final String digest = in.potenfyr.authcore.compat.Compat.loginFinishHandshake(this, packet);
      final Object handler = this;
      final Object helloPacket = hello;
      final String username = readHelloName((ServerboundHelloPacket) hello);
      // The join uuid on an offline server is always the OFFLINE uuid vanilla assigns to the name;
      // the verified profile must match the account the client claimed.
      final UUID offlineUuid = in.potenfyr.authcore.compat.Compat.offlineUuid(username);
      final UUID claimedUuid = readHelloId((ServerboundHelloPacket) hello);
      final java.net.InetAddress address = loginAddress();

      if (digest == null) {
        // Handshake failed (bad nonce) - continue as offline
        HANDLED.add(handler);
        boundTracking();
        in.potenfyr.authcore.compat.Compat.loginReplayHello(handler, helloPacket);
        return;
      }

      AuthCoreServer.IO_EXECUTOR.execute(
          () -> {
            try {
              Object server = loginServer(handler);
              if (server == null) return;

              com.mojang.authlib.GameProfile verified =
                  in.potenfyr.authcore.compat.Compat.loginVerifyProfile(
                      server, username, digest, address);

              boolean matches =
                  verified != null
                      && offlineUuid != null
                      && username != null
                      && username.equalsIgnoreCase(profileName(verified))
                      && (claimedUuid == null || claimedUuid.equals(profileId(verified)));
              if (matches) {
                // Bound to THIS login's network connection: the offline uuid is shared by every
                // client claiming the name, so only this connection may consume the marker.
                AuthCoreServer.markPremiumVerified(
                    offlineUuid, in.potenfyr.authcore.compat.Compat.networkConnection(handler));
                AuthCoreServer.LOGGER.info(
                    true,
                    "{} online-mode session verified via the server's Mojang session service.",
                    username);
                in.potenfyr.authcore.security.SecurityLog.log(
                    "PREMIUM_VERIFIED", username + " verified via server session authentication");
              } else {
                AuthCoreServer.LOGGER.debug(
                    true,
                    "{} did not pass Mojang session verification - joining as offline player.",
                    username);
              }
            } finally {
              // Resume the vanilla offline join (same hello packet, state reset to HELLO).
              // Mark handled FIRST so the replayed hello never re-arms the handshake.
              HANDLED.add(handler);
              boundTracking();
              in.potenfyr.authcore.compat.Compat.loginReplayHello(handler, helloPacket);
            }
          });
    } catch (RuntimeException err) {
      AuthCoreServer.LOGGER.debug(
          false, "Online-mode verification failed - continuing as offline:", err);
      HANDLED.add(this);
      boundTracking();
      in.potenfyr.authcore.compat.Compat.loginReplayHello(this, hello);
    }
  }

  @Inject(
      method = "disconnect(Lnet/minecraft/network/chat/Component;)V",
      at = @At("HEAD"),
      require = 0)
  private void authCore$verifyDisconnect(net.minecraft.network.chat.Component reason, CallbackInfo ci) {
    PENDING.remove(this);
    HANDLED.remove(this);
  }

  /** Watchdog: resume the vanilla offline join when the client never answered the handshake. */
  private static void fallbackToOffline(Object handler, Object helloPacket) {
    if (PENDING.remove(handler) == null) return;
    AuthCoreServer.LOGGER.debug(
        true, "Client did not answer the encryption handshake - joining as offline player.");
    HANDLED.add(handler);
        boundTracking();
    in.potenfyr.authcore.compat.Compat.loginReplayHello(handler, helloPacket);
  }

  // ------------------------------------------------------------------ helpers

  /** The login listener's MinecraftServer, found by field TYPE (the name differs per era). */
  private static Object loginServer(Object handler) {
    for (Class<?> c = handler.getClass(); c != null && c != Object.class; c = c.getSuperclass())
      for (java.lang.reflect.Field f : c.getDeclaredFields()) {
        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
            || !net.minecraft.server.MinecraftServer.class.isAssignableFrom(f.getType())) continue;
        try {
          f.setAccessible(true);
          Object server = f.get(handler);
          if (server != null) return server;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
          // keep looking
        }
      }
    return null;
  }

  private java.net.InetAddress loginAddress() {
    try {
      net.minecraft.network.Connection connection =
          in.potenfyr.authcore.compat.Compat.networkConnection(this);
      if (connection == null) return null;
      java.net.SocketAddress address = connection.getRemoteAddress();
      return address instanceof java.net.InetSocketAddress isa ? isa.getAddress() : null;
    } catch (RuntimeException | LinkageError err) {
      return null;
    }
  }

  private static String readHelloName(ServerboundHelloPacket packet) {
    return in.potenfyr.authcore.compat.Compat.helloName(packet);
  }

  private static UUID readHelloId(ServerboundHelloPacket packet) {
    return in.potenfyr.authcore.compat.Compat.helloId(packet);
  }

  /** authlib GameProfile name/id across authlib eras (getName()/name(), getId()/id()). */
  private static String profileName(com.mojang.authlib.GameProfile profile) {
    for (String m : new String[] {"getName", "name"}) {
      try {
        Object v = profile.getClass().getMethod(m).invoke(profile);
        if (v instanceof String s) return s;
      } catch (ReflectiveOperationException | RuntimeException ignored) {
        // next accessor
      }
    }
    return null;
  }

  private static UUID profileId(com.mojang.authlib.GameProfile profile) {
    for (String m : new String[] {"getId", "id"}) {
      try {
        Object v = profile.getClass().getMethod(m).invoke(profile);
        if (v instanceof UUID u) return u;
      } catch (ReflectiveOperationException | RuntimeException ignored) {
        // next accessor
      }
    }
    return null;
  }
}
