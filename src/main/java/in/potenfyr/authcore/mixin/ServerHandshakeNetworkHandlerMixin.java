package in.potenfyr.authcore.mixin;

import in.potenfyr.authcore.AuthCoreServer;
import in.potenfyr.authcore.network.ProxySupport;
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

    // Velocity MODERN forwarding puts a random token in the handshake address (the real IP and
    // UUID arrive in a signed login payload handled by FabricProxy-Lite / Proxy-Compatible-Forge),
    // so there is nothing to parse and no spoof to report here.
    if (ProxySupport.isModernForwardingConfigured()) return;

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
      in.potenfyr.authcore.security.SecurityLog.log(
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
      in.potenfyr.authcore.security.SecurityLog.log("PROXY_FORWARD", "IP " + realIp + " accepted from proxy");
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

  private static volatile java.lang.reflect.Field hostNameField;

  /**
   * Reads the handshake host string - where BungeeCord / Velocity legacy forwarding put the real
   * client IP.
   *
   * <p>The field is located by SHAPE: {@code ClientIntentionPacket} declares exactly one instance
   * {@code String} field (Mojang {@code hostName}) in every era - a plain class before 1.20.5, a
   * record after. The previous lookup asked for the YARN name {@code address}, which exists on no
   * runtime (intermediary/SRG ids on Fabric/Forge, {@code hostName} on Mojang-named ones), so
   * proxied players were never given their real IP on any loader or version.
   */
  private static String readAddress(ClientIntentionPacket packet) {
    try {
      java.lang.reflect.Field field = hostNameField;
      if (field == null) {
        for (java.lang.reflect.Field f : ClientIntentionPacket.class.getDeclaredFields()) {
          if (f.getType() != String.class
              || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
          if (field != null) return null; // ambiguous shape - never guess
          field = f;
        }
        if (field == null) return null;
        field.setAccessible(true);
        hostNameField = field;
      }
      Object address = field.get(packet);
      return address instanceof String s ? s : null;
    } catch (ReflectiveOperationException | RuntimeException e) {
      return null;
    }
  }
}
