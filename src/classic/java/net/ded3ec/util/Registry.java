package net.ded3ec.util;

import net.ded3ec.events.BlockEvents;
import net.ded3ec.events.EntityEvents;
import net.ded3ec.events.ServerEvents;
import net.ded3ec.models.User;
import net.ded3ec.security.IpRules;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/**
 * Utility class for registering hooks, commands, and events in AuthCoreServer. All
 * version-sensitive fabric-api hooks are registered through {@link FabricHooks} reflectively so
 * the same jar runs on Minecraft 1.16 - 26.x.
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

  /** Registers commands for the mod (version-agnostic fabric command API). */
  private static void registerCommands() {
    FabricHooks.registerCommands();
  }

  /** Registers event listeners for server and player events. */
  private static void registerEvents() {

    ServerPlayConnectionEvents.JOIN.register(ServerEvents::onPlayerJoin);
    ServerPlayConnectionEvents.DISCONNECT.register(ServerEvents::onPlayerLeave);
    ServerTickEvents.END_SERVER_TICK.register(ServerEvents::onEndServerTick);

    // Version-sensitive hooks (item use, damage/death) are registered reflectively
    FabricHooks.registerInteractionEvents();

    UseBlockCallback.EVENT.register(BlockEvents::onBlockUsage);

    UseEntityCallback.EVENT.register(EntityEvents::onEntityUse);
    AttackEntityCallback.EVENT.register(EntityEvents::onEntityAttack);
  }
}
