package net.ded3ec.mixin;

import net.ded3ec.AuthCoreServer;
import net.ded3ec.network.ProxySupport;
/*? if fabric {*/
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
/*?}*/
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.server.network.ServerHandshakePacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Universal proxy IP forwarding (1.16 - 26.x).
 *
 * <p>Reads the handshake address via reflection ({@code address()} record accessor on newer
 * versions, a private field on 1.16-1.20.4) and rewrites the connection address to the real
 * client IP so GeoIP, sessions, rate limits and login intelligence work behind Velocity /
 * BungeeCord networks.
 */
/*? if fabric {*/
  @Environment(EnvType.SERVER)
  /*?}*/
@Pseudo
@Mixin(ServerHandshakePacketListenerImpl.class)
@SuppressWarnings({"mapping", "unresolvable-target"})
abstract class ServerHandshakeNetworkHandlerMixin {

  @Shadow @Final private Connection connection;

  @Inject(method = "handleIntention", at = @At("HEAD"), require = 0)
  private void authCore$forwardProxyIp(ClientIntentionPacket packet, CallbackInfo ci) {
    if (AuthCoreServer.config == null || !AuthCoreServer.config.session.proxySupport.enabled) return;

    String rawAddress = readAddress(packet);
    String realIp = ProxySupport.parseForwardedIp(rawAddress);
    if (realIp == null) return;

    // Source validation: forwarded data is client-controlled. When a trusted-proxies
    // list is configured, only connections from those addresses may claim a real IP -
    // everyone else keeps their genuine socket address (spoof attempts are logged).
    String socketIp = readSocketIp();
    if (!ProxySupport.isTrustedProxySource(socketIp)) {
      AuthCoreServer.LOGGER.debug(
          false,
          "Proxy forwarding REJECTED untrusted source {} claiming IP {}",
          socketIp,
          realIp);
      net.ded3ec.security.SecurityLog.log(
          "PROXY_SPOOF_ATTEMPT",
          "source " + socketIp + " claimed forwarded IP " + realIp + " - ignored");
      return;
    }

    try {
      java.net.InetSocketAddress oldAddress = (java.net.InetSocketAddress) connection.getRemoteAddress();
      java.net.InetSocketAddress forwarded =
          new java.net.InetSocketAddress(realIp, oldAddress == null ? 0 : oldAddress.getPort());

      ((ClientConnectionAccessor) connection).authCore$setAddress(forwarded);

      AuthCoreServer.LOGGER.debug(true, "Proxy forwarding: rewritten connection address {} -> {}", oldAddress, forwarded);
      net.ded3ec.security.SecurityLog.log("PROXY_FORWARD", "IP " + realIp + " accepted from proxy");
    } catch (Exception err) {
      AuthCoreServer.LOGGER.debug(false, "Failed to rewrite proxied address:", err);
    }
  }

  /** Extracts the plain socket IP of this connection (null when unavailable). */
  private String readSocketIp() {
    try {
      Object remote = connection.getRemoteAddress();
      if (remote instanceof java.net.InetSocketAddress address) {
        java.net.InetAddress addr = address.getAddress();
        return addr != null ? addr.getHostAddress() : null;
      }
    } catch (RuntimeException ignored) {
      // fall through
    }
    return null;
  }

  /** Reads the handshake address string (record accessor or private field, by version). */
  private static String readAddress(ClientIntentionPacket packet) {
    try {
      Object address = packet.getClass().getMethod("address").invoke(packet);
      if (address instanceof String s) return s;
    } catch (ReflectiveOperationException ignored) {
      // fall through to the field accessor
    }
    try {
      java.lang.reflect.Field field = packet.getClass().getDeclaredField("address");
      field.setAccessible(true);
      Object address = field.get(packet);
      return address instanceof String s ? s : null;
    } catch (ReflectiveOperationException e) {
      return null;
    }
  }
}
