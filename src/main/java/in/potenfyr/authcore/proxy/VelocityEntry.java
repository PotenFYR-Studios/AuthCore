package in.potenfyr.authcore.proxy;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import java.nio.file.Path;
// com.google.inject.Inject is the annotation Velocity expects and is present in both
// Guice 5 (velocity-api 3.1.x) and Guice 7 (3.5.x); javax.inject was dropped by Guice 7.
import com.google.inject.Inject;
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
    version = "1.0.3",
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
      server.getEventManager().register(this, new ServerGateListener());
      if (config.logEvents)
        logger.info(
            "AuthCore proxy mode enabled (Velocity detected) - config: config/authcore-proxy.properties");
      if (config.blockUnauthenticated)
        logger.info(
            "AuthCore proxy-side auth ACTIVE - unauthenticated players may only reach auth-server '{}' (checked on backend connect).",
            config.authServer.isBlank() ? "(first landing server)" : config.authServer);
    } catch (Throwable err) {
      logger.warn("AuthCore proxy mode failed to start: {}", err.toString());
    }
  }

  /**
   * Blocks unauthenticated players from GAME servers.
   *
   * <p>The gate runs on {@code ServerPreConnectEvent} - i.e. when the player is about to be
   * connected to a backend - NOT at proxy login. A login-time gate can never be satisfied: the
   * player must first reach a backend to run /login or /register, and only then do the backends
   * broadcast the AUTH_CHANGED message that marks the session. Unauthenticated players are
   * redirected to the configured auth server (or kept on the first server they land on), and
   * blocked from transferring to any other server until they authenticate.
   *
   * <p>Event access is reflective so the same plugin works across Velocity 3.x/4.x event shapes.
   */
  private final class ServerGateListener {

    @Subscribe
    public void onServerPreConnect(Object event) {
      if (config == null || !config.enabled || !config.blockUnauthenticated) return;
      try {
        Object player = invoke(event, "getPlayer");
        if (player == null) return;

        // The server the player is connecting to (null when it cannot be determined).
        Object target = invoke(event, "getOriginalServer");
        if (target == null) return;
        String targetName = serverName(target);

        // The auth/hub server is always reachable - that is where /login and /register live.
        if (ProxyAuthGate.isAuthServer(targetName, config)) return;

        Object uuidObj = invoke(player, "getUniqueId");
        if (uuidObj instanceof java.util.UUID uuid
            && ProxyAuthGate.hasValidSession(uuid, config, msg -> logger.warn(msg))) {
          return; // authenticated - free to move
        }

        Object previous = invoke(event, "getPreviousServer");
        if (previous == null) {
          // Initial connect: never disconnect the player here - send them to the auth server when
          // one is configured, otherwise let them stay on the first server they landed on.
          if (!config.authServer.isBlank()) {
            Object auth = findServer(config.authServer);
            if (auth != null) {
              allowServer(event, auth);
              sendMessage(player, config.kickMessage);
              return;
            }
            logger.error(
                "AuthCore proxy auth: auth-server '{}' is not a registered Velocity server - allowing the initial connect; fix auth-server in config/authcore-proxy.properties.",
                config.authServer);
          }
          return;
        }

        // A transfer to a game server before authenticating -> stay on the current server.
        sendMessage(player, config.kickMessage);
        deny(event);
      } catch (Throwable err) {
        // Never let a gate bug crash the proxy - but an unidentifiable failure must not open the
        // gate: deny unless the operator explicitly chose fail-open behavior.
        logger.warn("AuthCore proxy auth: server gate error - {}", err.toString());
        if (config.failClosed) deny(event);
      }
    }

    /** Denies the pre-connect (the player stays on the current server). */
    private void deny(Object event) {
      try {
        Class<?> resultClass =
            Class.forName("com.velocitypowered.api.event.player.ServerPreConnectEvent$ServerResult");
        Object denied = resultClass.getMethod("denied").invoke(null);
        event.getClass().getMethod("setResult", resultClass).invoke(event, denied);
      } catch (Throwable err) {
        logger.warn("AuthCore proxy auth: could not deny server connect - {}", err.toString());
      }
    }

    /** Redirects the pre-connect to another (auth) server. */
    private void allowServer(Object event, Object registeredServer) {
      try {
        Class<?> resultClass =
            Class.forName("com.velocitypowered.api.event.player.ServerPreConnectEvent$ServerResult");
        Class<?> serverClass = Class.forName("com.velocitypowered.api.proxy.server.RegisteredServer");
        Object allowed = resultClass.getMethod("allowed", serverClass).invoke(null, registeredServer);
        event.getClass().getMethod("setResult", resultClass).invoke(event, allowed);
      } catch (Throwable err) {
        logger.warn("AuthCore proxy auth: could not redirect to the auth server - {}", err.toString());
      }
    }

    /** Resolves a registered backend server by name (empty Optional when missing). */
    private Object findServer(String name) {
      if (name == null || name.isBlank()) return null;
      try {
        Object optional = server.getClass().getMethod("getServer", String.class).invoke(server, name);
        if (optional == null) return null;
        Object present = optional.getClass().getMethod("isPresent").invoke(optional);
        if (!(present instanceof Boolean b) || !b) return null;
        return optional.getClass().getMethod("get").invoke(optional);
      } catch (Throwable err) {
        return null;
      }
    }

    /** Reads the server-info name from a Velocity RegisteredServer. */
    private String serverName(Object registeredServer) {
      try {
        Object info = registeredServer.getClass().getMethod("getServerInfo").invoke(registeredServer);
        Object name = info.getClass().getMethod("getName").invoke(info);
        return name instanceof String s ? s : null;
      } catch (Throwable err) {
        return null;
      }
    }

    /** Sends the configured explanation to the player (best-effort). */
    private void sendMessage(Object player, String message) {
      try {
        player
            .getClass()
            .getMethod("sendMessage", net.kyori.adventure.text.Component.class)
            .invoke(player, net.kyori.adventure.text.Component.text(message));
      } catch (Throwable ignored) {
        // message delivery is best-effort
      }
    }
  }

  /** Reflective no-arg accessor (Velocity 3/4 event shapes differ). */
  private static Object invoke(Object target, String method) {
    try {
      return target.getClass().getMethod(method).invoke(target);
    } catch (Throwable err) {
      return null;
    }
  }

  /** Listens for AuthCore interop messages (reflectively, Velocity 3/4 compatible). */
  private final class PluginMessageListener {

    @Subscribe
    public void onPluginMessage(Object event) {
      try {
        if (config == null || !config.enabled) return;

        // SECURITY: AUTH_CHANGED payloads are client-spoofable if accepted from players.
        // Only backend-server connections may speak for AuthCore.
        Object source = event.getClass().getMethod("getSource").invoke(event);
        if (!(source instanceof com.velocitypowered.api.proxy.ServerConnection)) return;

        // Velocity 3: getIdentifier() -> Identifier; Velocity 4: getIdentifier() -> String
        String channel = null;
        Object id = event.getClass().getMethod("getIdentifier").invoke(event);
        if (id != null) channel = id.toString();
        if (!InteropMessages.CHANNEL.equals(channel)) return;

        // Consume the message so it is never forwarded to other clients/backends.
        Class<?> forwardResult =
            Class.forName("com.velocitypowered.api.proxy.messages.PluginMessageEvent$ForwardResult");
        event.getClass()
            .getMethod("setResult", forwardResult)
            .invoke(event, forwardResult.getMethod("handled").invoke(null));

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
