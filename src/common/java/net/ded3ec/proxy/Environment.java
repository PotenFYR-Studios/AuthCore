package net.ded3ec.proxy;

/**
 * Runtime environment detection.
 *
 * <p>The SAME jar is a Fabric server mod, a Fabric client mod, a BungeeCord plugin and a
 * Velocity plugin - the loader that picks it up is detected at runtime and the matching
 * configuration file is created automatically:
 *
 * <ul>
 *   <li>Fabric server -> {@code config/authcore/settings.conf}</li>
 *   <li>Fabric client -> {@code config/authcore-client.json}</li>
 *   <li>BungeeCord / Velocity -> {@code config/authcore-proxy.properties}</li>
 * </ul>
 */
public final class Environment {

  private Environment() {}

  /** Detected runtime kind. */
  public static String detect() {
    if (classPresent("net.fabricmc.loader.api.FabricLoader")) return "fabric";
    if (classPresent("com.velocitypowered.api.plugin.PluginManager")) return "velocity";
    if (classPresent("net.md_5.bungee.api.ProxyServer")) return "bungeecord";
    return "unknown";
  }

  /** Whether this jar is running as a proxy plugin (BungeeCord or Velocity). */
  public static boolean isProxy() {
    String env = detect();
    return "velocity".equals(env) || "bungeecord".equals(env);
  }

  private static boolean classPresent(String name) {
    try {
      Class.forName(name);
      return true;
    } catch (Throwable notPresent) {
      return false;
    }
  }
}
