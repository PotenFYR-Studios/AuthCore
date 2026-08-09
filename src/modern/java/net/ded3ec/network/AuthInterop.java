package net.ded3ec.network;

import net.ded3ec.compat.Compat;
import net.ded3ec.models.Config;
import net.minecraft.server.level.ServerPlayer;

/**
 * Interop bridge between AuthCore and OTHER mods / the proxy (Velocity / BungeeCord) - 26.1+
 * (Mojang names). Broadcasts auth-state changes as lightweight plugin messages so a network can
 * coexist with a DIFFERENT authentication mod on the backend, or a proxy plugin can react to
 * logins. Channels: {@code authcore:auth} (custom Fabric) + {@code bungeecord:main} subchannel
 * {@code AuthCore}. Payload: {@code AUTH_CHANGED|<uuid>|<username>|<1|0>} (ASCII).
 * Best-effort: failures are silently skipped - gameplay is never affected.
 */
public final class AuthInterop {

  private AuthInterop() {}

  /** Registers the interop channels with the player's connection (sent once per join). */
  public static void register(ServerPlayer player) {
    Config.Session.InteropConfig cfg = interopConfig();
    if (cfg == null || !cfg.enabled || player == null) return;
    Compat.sendCustomPayload(player, "minecraft:register", registerPayload(cfg));
  }

  /** Broadcasts the current auth state of a player to other mods / the proxy. */
  public static void broadcast(ServerPlayer player, boolean authenticated) {
    Config.Session.InteropConfig cfg = interopConfig();
    if (cfg == null || !cfg.enabled || player == null) return;

    String line =
        "AUTH_CHANGED|"
            + player.getUUID()
            + "|"
            + player.getName().getString()
            + "|"
            + (authenticated ? "1" : "0");

    // Custom Fabric channel (other mods)
    Compat.sendCustomPayload(player, cfg.channel, line.getBytes(java.nio.charset.StandardCharsets.US_ASCII));

    // BungeeCord plugin-messaging channel (proxy plugins), subchannel "AuthCore"
    if (cfg.bungeeChannel) {
      byte[] sub = "AuthCore".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
      byte[] data = new byte[sub.length + 1 + line.length()];
      System.arraycopy(sub, 0, data, 0, sub.length);
      data[sub.length] = 0;
      System.arraycopy(
          line.getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, data, sub.length + 1, line.length());
      Compat.sendCustomPayload(player, "bungeecord:main", data);
    }
  }

  /** minecraft:register payload listing the channels this mod wants to receive. */
  private static byte[] registerPayload(Config.Session.InteropConfig cfg) {
    StringBuilder sb = new StringBuilder(cfg.channel);
    if (cfg.bungeeChannel) sb.append('\u0000').append("bungeecord:main");
    return sb.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
  }

  private static Config.Session.InteropConfig interopConfig() {
    if (net.ded3ec.AuthCoreServer.config == null) return null;
    return net.ded3ec.AuthCoreServer.config.session.interop;
  }
}
