package net.ded3ec.proxy;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import java.nio.file.Path;
import javax.inject.Inject;
import org.slf4j.Logger;

/**
 * Velocity plugin entrypoint - activated automatically when the AuthCore jar is dropped into a
 * Velocity proxy's {@code plugins/} folder (see {@code velocity-plugin.json}).
 *
 * <p>Auto-detects the environment, creates {@code config/authcore-proxy.properties}, listens
 * for AuthCore interop messages from backends and keeps the network session cache in sync.
 * The plugin message body is read reflectively so both Velocity 3.x and 4.x event shapes work.
 */
@Plugin(
    id = "authcore",
    name = "AuthCore",
    version = "1.0.0",
    description = "Network auth-state bridge: tracks which players authenticated on AuthCore backends.",
    authors = {"Ded3ec"})
public final class VelocityEntry {

  private final ProxyServer server;
  private final Logger logger;
  private ProxyConfig config;

  @Inject
  public VelocityEntry(ProxyServer server, Logger logger) {
    this.server = server;
    this.logger = logger;
  }

  @Subscribe
  public void onProxyInitialize(ProxyInitializeEvent event) {
    try {
      config = ProxyConfig.load(Path.of("config"));
      server.getEventManager().register(this, new PluginMessageListener());
      server.getEventManager().register(this, new LoginGateListener());
      if (config.logEvents)
        logger.info(
            "AuthCore proxy mode enabled (Velocity detected) - config: config/authcore-proxy.properties");
      if (config.blockUnauthenticated)
        logger.info("AuthCore proxy-side auth ACTIVE - unauthenticated players are blocked before backend connect.");
    } catch (Throwable err) {
      logger.warn("AuthCore proxy mode failed to start: {}", err.toString());
    }
  }

  /** Blocks players without a valid Redis session (fail-open when Redis is down). */
  private final class LoginGateListener {

    @Subscribe
    public void onLogin(Object event) {
      try {
        if (config == null || !config.enabled || !config.blockUnauthenticated) return;

        // Velocity 3/4: event.getPlayer() -> Player with getUniqueId()
        Object player = event.getClass().getMethod("getPlayer").invoke(event);
        if (player == null) return;
        Object uuidObj = player.getClass().getMethod("getUniqueId").invoke(player);
        if (!(uuidObj instanceof java.util.UUID uuid)) return;

        if (!ProxyAuthGate.hasValidSession(uuid, config, msg -> logger.warn(msg))) {
          // event.setResult(Result.denied(Component)) - reflectively, velocity 3.x and 4.x
          Class<?> resultClass = Class.forName("com.velocitypowered.api.event.Result");
          Object denied =
              resultClass
                  .getMethod("denied", net.kyori.adventure.text.Component.class)
                  .invoke(null, net.kyori.adventure.text.Component.text(config.kickMessage));
          event.getClass().getMethod("setResult", resultClass).invoke(event, denied);
        }
      } catch (Throwable ignored) {
        // fail-open - never break the proxy
      }
    }
  }

  /** Listens for AuthCore interop messages (reflectively, Velocity 3/4 compatible). */
  private final class PluginMessageListener {

    @Subscribe
    public void onPluginMessage(Object event) {
      try {
        if (config == null || !config.enabled) return;

        // Velocity 3: getIdentifier() -> Identifier; Velocity 4: getIdentifier() -> String
        String channel = null;
        Object id = event.getClass().getMethod("getIdentifier").invoke(event);
        if (id != null) channel = id.toString();

        // Velocity 3: getData() -> ByteBuf; Velocity 4: getData() -> byte[]
        byte[] data = null;
        Object raw = event.getClass().getMethod("getData").invoke(event);
        if (raw instanceof byte[]) {
          data = (byte[]) raw;
        } else if (raw instanceof io.netty.buffer.ByteBuf) {
          io.netty.buffer.ByteBuf buf = (io.netty.buffer.ByteBuf) raw;
          data = new byte[buf.readableBytes()];
          buf.getBytes(buf.readerIndex(), data);
        }

        if (channel != null && InteropMessages.handle(channel, data)) {
          SessionCache.prune(config.sessionTimeoutMs);
        }
      } catch (Throwable ignored) {
        // interop is best-effort
      }
    }
  }
}
