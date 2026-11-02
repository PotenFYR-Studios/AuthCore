package in.potenfyr.authcore.proxy;

import java.nio.file.Path;
import java.util.UUID;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;

/**
 * BungeeCord plugin entrypoint - activated automatically when the AuthCore jar is dropped
 * into a BungeeCord proxy's {@code plugins/} folder (see {@code bungee.yml}).
 *
 * <p>Auto-detects the environment, creates {@code config/authcore-proxy.properties}, listens
 * for AuthCore interop messages from backends and keeps the network session cache in sync.
 */
public final class BungeeEntry extends Plugin implements Listener {

  private ProxyConfig config;

  @Override
  public void onEnable() {
    try {
      config = ProxyConfig.load(Path.of("config"));
      getProxy().getPluginManager().registerListener(this, this);
      getProxy().registerChannel(InteropMessages.CHANNEL);
      getProxy().registerChannel(InteropMessages.BUNGEE_CHANNEL);

      if (config.logEvents)
        getLogger().info("AuthCore proxy mode enabled (BungeeCord detected) - config: config/authcore-proxy.properties");

      if (config.blockUnauthenticated)
        getLogger().info(
            "AuthCore proxy-side auth ACTIVE - unauthenticated players may only reach auth-server '"
                + (config.authServer.isBlank() ? "(first landing server)" : config.authServer)
                + "' (checked on backend connect).");

      getProxy().getPluginManager().registerCommand(this, new AuthCoreStatusCommand());
    } catch (Throwable err) {
      getLogger().warning("AuthCore proxy mode failed to start: " + err);
    }
  }

  /**
   * Blocks unauthenticated players from GAME servers.
   *
   * <p>Runs on {@code ServerConnectEvent} (the player is about to be sent to a backend), NOT on
   * {@code LoginEvent}: a login-time gate can never be satisfied because the player must first
   * reach a backend to run /login or /register, and only then does the backend broadcast the
   * AUTH_CHANGED message that feeds the proxy session cache. Unauthenticated players are
   * redirected to the configured auth server on their initial join, and blocked from transferring
   * to any other server until they authenticate.
   */
  @EventHandler
  public void onServerConnect(net.md_5.bungee.api.event.ServerConnectEvent event) {
    if (config == null || !config.enabled || !config.blockUnauthenticated) return;
    try {
      var player = event.getPlayer();
      if (player == null) return;

      var target = event.getTarget();
      String targetName = target != null ? target.getName() : null;

      // The auth/hub server is always reachable - that is where /login and /register live.
      if (ProxyAuthGate.isAuthServer(targetName, config)) return;

      UUID uuid = player.getUniqueId();
      if (uuid != null
          && ProxyAuthGate.hasValidSession(uuid, config, msg -> getLogger().warning(msg))) {
        return; // authenticated - free to move
      }

      boolean initial =
          event.getReason() == net.md_5.bungee.api.event.ServerConnectEvent.Reason.JOIN_PROXY;
      if (initial) {
        // Never disconnect the player here - send them to the auth server when one is configured,
        // otherwise let them stay on the first server they landed on.
        if (!config.authServer.isBlank()) {
          var auth = getProxy().getServerInfo(config.authServer);
          if (auth != null) {
            event.setTarget(auth);
            player.sendMessage(new TextComponent(config.kickMessage));
            return;
          }
          getLogger()
              .severe(
                  "AuthCore proxy auth: auth-server '"
                      + config.authServer
                      + "' is not a registered BungeeCord server - allowing the initial connect;"
                      + " fix auth-server in config/authcore-proxy.properties.");
        }
        return;
      }

      // A transfer to a game server before authenticating -> stay on the current server.
      player.sendMessage(new TextComponent(config.kickMessage));
      event.setCancelled(true);
    } catch (Throwable err) {
      // Never let a gate bug crash the proxy - but an unidentifiable failure must not open the
      // gate: deny unless the operator explicitly chose fail-open behavior.
      getLogger().warning("AuthCore proxy auth: server gate error - " + err);
      if (config.failClosed) event.setCancelled(true);
    }
  }

  @EventHandler
  public void onPluginMessage(PluginMessageEvent event) {
    try {
      if (config == null || !config.enabled) return;
      String tag = event.getTag();
      if (tag == null) return;
      boolean interop =
          InteropMessages.CHANNEL.equals(tag) || InteropMessages.BUNGEE_CHANNEL.equals(tag);
      if (!interop) return;

      // SECURITY: AUTH_CHANGED payloads are client-spoofable if accepted from players.
      // Only backend-server connections may speak for AuthCore, and the message is always
      // consumed so it can never be forwarded to other clients.
      if (!(event.getSender() instanceof net.md_5.bungee.api.connection.Server)) {
        event.setCancelled(true);
        return;
      }
      event.setCancelled(true);

      if (InteropMessages.handle(tag, event.getData())) {
        SessionCache.prune(config.sessionTimeoutMs);
      }
    } catch (Throwable ignored) {
      // interop is best-effort
    }
  }

  /** {@code /authcore} proxy command - shows network auth state. */
  private final class AuthCoreStatusCommand extends Command {
    AuthCoreStatusCommand() {
      super("authcore", "authcore.status", "ac");
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
      SessionCache.prune(config == null ? ProxyConfig.DEFAULT_TIMEOUT : config.sessionTimeoutMs);
      sender.sendMessage(
          new TextComponent(
              "[AuthCore] Proxy mode active - tracked sessions: " + SessionCache.size()));
    }
  }
}
