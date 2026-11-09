package in.potenfyr.authcore.util;

import in.potenfyr.authcore.models.User;
import in.potenfyr.authcore.security.IpRules;

/**
 * Boots every AuthCore subsystem: config, database, commands and events.
 *
 * <p>Loader strategy: fabric-api event registration is gated to the fabric variants
 * (Forge/NeoForge register their equivalents in the loader entrypoints). Version-sensitive
 * hooks (item use, damage/death, velocity forwarding) are registered reflectively by
 * {@link FabricHooks}, so the same code runs on Minecraft 1.16 - 26.x.
 */
public class Registry {
  /** Registers all hooks, commands, and events. */
  public static void register() {
    registerUtils();
    registerHelpers();
    registerCommands();
    registerEvents();
  }

  /** Registers helper components, such as loading user data. */
  private static void registerHelpers() {
    User.load();
  }

  /** Registers utility components, such as configuration initialization. */
  private static void registerUtils() {
    HoconConf.initialize();
    IpRules.load();
  }

  /** Registers commands for the mod (version-agnostic command API). */
  private static void registerCommands() {
    FabricHooks.registerCommands();
  }

  /** Registers event listeners for server and player events. */
  private static void registerEvents() {

    // Player join/leave + server tick (fabric-api events, registered reflectively -
    // the fabric-api jar spans the id rename "fabric" -> "fabric-api" across 1.16-26.x
    // and may be absent entirely; a direct import would NoClassDefFoundError the boot.
    // Forge/NeoForge register their equivalents in the loader entrypoints).
    FabricHooks.registerServerEvents();

    // Version-sensitive hooks (item use, damage/death) are registered reflectively
    FabricHooks.registerInteractionEvents();

    // Proxy identity: legacy (BungeeCord / Velocity-"bungeecord") forwarding is read natively
    // by the handshake mixin. Velocity MODERN forwarding is performed by the proxy-forwarding
    // mod (FabricProxy-Lite / Proxy-Compatible-Forge), so an operator who set
    // proxy-support.protocol = "velocity" gets one clear notice instead of silent no-op IP
    // forwarding.
    in.potenfyr.authcore.network.ProxySupport.warnUnsupportedModernForwarding();

    // Item use / block use / entity use / attack callbacks are registered
    // reflectively in FabricHooks - their interfaces are compiled per-version and
    // must not be linked directly (NoClassDefFoundError on older servers).
  }
}
