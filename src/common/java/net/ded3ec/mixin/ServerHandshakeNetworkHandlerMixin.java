package net.ded3ec.mixin;

import net.ded3ec.AuthCoreServer;
import net.ded3ec.network.ProxySupport;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.c2s.handshake.HandshakeC2SPacket;
import net.minecraft.server.network.ServerHandshakeNetworkHandler;
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
@Environment(EnvType.SERVER)
@Pseudo
@Mixin(ServerHandshakeNetworkHandler.class)
abstract class ServerHandshakeNetworkHandlerMixin {

  @Shadow @Final private ClientConnection connection;

  @Inject(method = "onHandshake", at = @At("HEAD"), require = 0)
  private void authCore$forwardProxyIp(HandshakeC2SPacket packet, CallbackInfo ci) {
    if (AuthCoreServer.config == null || !AuthCoreServer.config.session.proxySupport.enabled) return;

    String rawAddress = readAddress(packet);
    String realIp = ProxySupport.parseForwardedIp(rawAddress);
    if (realIp == null) return;

    try {
      java.net.InetSocketAddress oldAddress = (java.net.InetSocketAddress) connection.getAddress();
      java.net.InetSocketAddress forwarded =
          new java.net.InetSocketAddress(realIp, oldAddress == null ? 0 : oldAddress.getPort());

      ((ClientConnectionAccessor) connection).authCore$setAddress(forwarded);

      AuthCoreServer.LOGGER.debug(true, "Proxy forwarding: rewritten connection address {} -> {}", oldAddress, forwarded);
      net.ded3ec.security.SecurityLog.log("PROXY_FORWARD", "IP " + realIp + " accepted from proxy");
    } catch (Exception err) {
      AuthCoreServer.LOGGER.debug(false, "Failed to rewrite proxied address:", err);
    }
  }

  /** Reads the handshake address string (record accessor or private field, by version). */
  private static String readAddress(HandshakeC2SPacket packet) {
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
