package net.ded3ec.proxy;

import java.nio.file.Path;
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
        getLogger().info("AuthCore proxy-side auth ACTIVE - unauthenticated players are blocked before backend connect.");

      getProxy().getPluginManager().registerCommand(this, new AuthCoreStatusCommand());
    } catch (Throwable err) {
      getLogger().warning("AuthCore proxy mode failed to start: " + err);
    }
  }

  @EventHandler
  public void onLogin(net.md_5.bungee.api.event.LoginEvent event) {
    try {
      if (config == null || !config.enabled || !config.blockUnauthenticated) return;
      var connection = event.getConnection();
      if (connection == null || connection.getUniqueId() == null) return;

      if (!ProxyAuthGate.hasValidSession(connection.getUniqueId(), config, msg -> getLogger().warning(msg))) {
        event.setCancelReason(new net.md_5.bungee.api.chat.TextComponent(config.kickMessage));
        event.setCancelled(true);
      }
    } catch (Throwable ignored) {
      // fail-open - never break the proxy
    }
  }

  @EventHandler
  public void onPluginMessage(PluginMessageEvent event) {
    try {
      if (config == null || !config.enabled) return;
      String tag = event.getTag();
      if (tag == null) return;
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
