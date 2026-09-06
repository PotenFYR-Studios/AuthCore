package in.potenfyr.authcore.security;

import in.potenfyr.authcore.AuthCoreServer;
import in.potenfyr.authcore.models.Config;

/** Configuration invariants shared by startup, reload and the standalone test harness. */
public final class SecurityConfiguration {
  private SecurityConfiguration() {}

  public static void enforce(Config config) {
    if (config == null || config.session == null || config.session.proxySupport == null) return;
    var proxy = config.session.proxySupport;
    if (proxy.enabled && (proxy.trustedProxies == null || proxy.trustedProxies.isEmpty())) {
      proxy.enabled = false;
      AuthCoreServer.LOGGER.error(false,
          "SECURITY: proxy support DISABLED because trusted-proxies is empty. "
              + "Configure session.proxy-support.trusted-proxies with your proxy addresses; "
              + "otherwise client-controlled forwarding could bypass IP-based controls.");
    }
  }
}
