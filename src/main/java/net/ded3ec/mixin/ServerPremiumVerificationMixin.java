package net.ded3ec.mixin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.ded3ec.AuthCoreServer;
import net.ded3ec.network.McApiManager;
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
abstract class ServerPremiumVerificationMixin {

  /** Handlers currently waiting for the client's key packet: handler → original hello. */
  private static final Map<Object, Object> PENDING = new ConcurrentHashMap<>();

  /** How long to wait for the client's key packet before falling back to offline. */
  private static final long HANDSHAKE_TIMEOUT_MS = 15_000L;

  /**
   * When premium verification is wanted (offline server + premium auto-login) and the
   * client sent an offline-UUID hello: start the encryption handshake and hold the vanilla
   * offline flow until the verification completes (or times out).
   */
  @Inject(
      method = "handleHello(Lnet/minecraft/network/protocol/login/ServerboundHelloPacket;)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$verifyHello(ServerboundHelloPacket packet, CallbackInfo ci) {
    try {
      if (!AuthCoreServer.isPremiumVerificationEnabled()) return;

      String username = readHelloName(packet);
      UUID uuid = readHelloId(packet);
      if (username == null || uuid == null) return;
      if (!net.ded3ec.compat.Compat.isOfflineUuid(username, uuid)) return;

      // Start the handshake FIRST - only cancel vanilla's offline flow when it succeeded,
      // so a failure can never strand the player.
      if (!net.ded3ec.compat.Compat.loginStartHandshake(this)) return;

      PENDING.put(this, packet);
      ci.cancel();

      // Watchdog: if the client never answers the handshake, join as a normal offline player.
      net.ded3ec.util.TaskScheduler.getInstance()
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
      final String digest = net.ded3ec.compat.Compat.loginFinishHandshake(this, packet);
      final Object handler = this;
      final Object helloPacket = hello;
      final String username = readHelloName((ServerboundHelloPacket) hello);
      final UUID offlineUuid = readHelloId((ServerboundHelloPacket) hello);
      final java.net.InetAddress address = loginAddress();

      if (digest == null) {
        // Handshake failed (bad nonce) - continue as offline
        net.ded3ec.compat.Compat.loginReplayHello(handler, helloPacket);
        return;
      }

      AuthCoreServer.IO_EXECUTOR.execute(
          () -> {
            try {
              Object server = loginServer(handler);
              if (server == null) return;

              com.mojang.authlib.GameProfile verified =
                  net.ded3ec.compat.Compat.loginVerifyProfile(
                      server, username, digest, address);

              if (verified != null && offlineUuid != null) {
                AuthCoreServer.markPremiumVerified(offlineUuid);
                AuthCoreServer.LOGGER.info(
                    true,
                    "{} online-mode session verified via the server's Mojang session service.",
                    username);
                net.ded3ec.security.SecurityLog.log(
                    "PREMIUM_VERIFIED", username + " verified via server session authentication");
              } else {
                AuthCoreServer.LOGGER.debug(
                    true,
                    "{} did not pass Mojang session verification - joining as offline player.",
                    username);
              }
            } finally {
              // Resume the vanilla offline join (same hello packet, state reset to HELLO)
              net.ded3ec.compat.Compat.loginReplayHello(handler, helloPacket);
            }
          });
    } catch (RuntimeException err) {
      AuthCoreServer.LOGGER.debug(
          false, "Online-mode verification failed - continuing as offline:", err);
      net.ded3ec.compat.Compat.loginReplayHello(this, hello);
    }
  }

  /** Cleans up pending verification state when the connection drops. */
  @Inject(
      method = "onDisconnect(Lnet/minecraft/network/chat/Component;)V",
      at = @At("HEAD"),
      require = 0)
  private void authCore$verifyDisconnect(net.minecraft.network.chat.Component reason, CallbackInfo ci) {
    PENDING.remove(this);
  }

  /** Watchdog: resume the vanilla offline join when the client never answered the handshake. */
  private static void fallbackToOffline(Object handler, Object helloPacket) {
    if (PENDING.remove(handler) == null) return;
    AuthCoreServer.LOGGER.debug(
        true, "Client did not answer the encryption handshake - joining as offline player.");
    net.ded3ec.compat.Compat.loginReplayHello(handler, helloPacket);
  }

  // ------------------------------------------------------------------ helpers

  private Object loginServer() {
    try {
      java.lang.reflect.Field f = ServerLoginPacketListenerImpl.class.getDeclaredField("server");
      f.setAccessible(true);
      return f.get(this);
    } catch (ReflectiveOperationException | RuntimeException err) {
      return null;
    }
  }

  private static Object loginServer(Object handler) {
    try {
      java.lang.reflect.Field f = handler.getClass().getDeclaredField("server");
      f.setAccessible(true);
      return f.get(handler);
    } catch (ReflectiveOperationException | RuntimeException err) {
      return null;
    }
  }

  private java.net.InetAddress loginAddress() {
    try {
      Object connection = connection();
      if (connection == null) return null;
      Object address =
          connection.getClass().getMethod("getRemoteAddress").invoke(connection);
      return address instanceof java.net.InetSocketAddress isa
          ? isa.getAddress()
          : null;
    } catch (ReflectiveOperationException | RuntimeException err) {
      return null;
    }
  }

  private Object connection() {
    try {
      java.lang.reflect.Field f =
          ServerLoginPacketListenerImpl.class.getDeclaredField("connection");
      f.setAccessible(true);
      return f.get(this);
    } catch (ReflectiveOperationException | RuntimeException err) {
      return null;
    }
  }

  private static String readHelloName(ServerboundHelloPacket packet) {
    try {
      Object profile = packet.getClass().getMethod("getProfile").invoke(packet);
      if (profile != null) {
        for (String m : new String[] {"name", "getName"}) {
          try {
            Object v = profile.getClass().getMethod(m).invoke(profile);
            if (v instanceof String s) return s;
          } catch (ReflectiveOperationException ignored) {
            // next name
          }
        }
      }
      for (String m : new String[] {"name", "getName"}) {
        try {
          Object v = packet.getClass().getMethod(m).invoke(packet);
          if (v instanceof String s) return s;
        } catch (ReflectiveOperationException ignored) {
          // next name
        }
      }
      return null;
    } catch (ReflectiveOperationException | RuntimeException err) {
      return null;
    }
  }

  private static UUID readHelloId(ServerboundHelloPacket packet) {
    try {
      Object profile = packet.getClass().getMethod("getProfile").invoke(packet);
      if (profile != null) {
        for (String m : new String[] {"id", "getId"}) {
          try {
            Object v = profile.getClass().getMethod(m).invoke(profile);
            if (v instanceof UUID u) return u;
          } catch (ReflectiveOperationException ignored) {
            // next name
          }
        }
      }
      for (String m : new String[] {"profileId", "getProfileId"}) {
        try {
          Object v = packet.getClass().getMethod(m).invoke(packet);
          if (v instanceof UUID u) return u;
        } catch (ReflectiveOperationException ignored) {
          // next name
        }
      }
      return null;
    } catch (ReflectiveOperationException | RuntimeException err) {
      return null;
    }
  }
}
