package net.ded3ec.authcore.utils;

import net.ded3ec.authcore.command.*;
import net.ded3ec.authcore.events.BlockEvents;
import net.ded3ec.authcore.events.EntityEvents;
import net.ded3ec.authcore.events.ServerEvents;
import net.ded3ec.authcore.models.User;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.*;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/** Utility class for registering hooks, commands, and events in AuthCore. */
public class Hooks {
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

    ConfigUtil.initialize();
  }

  /** Registers commands for the mod. */
  private static void registerCommands() {

    CommandRegistrationCallback.EVENT.register(
        (commandDispatcher, commandRegistryAccess, environment) -> {
          Register.register(commandDispatcher);
          Login.register(commandDispatcher);
          Logout.register(commandDispatcher);
          Reload.register(commandDispatcher);
          Account.register(commandDispatcher);
          Password.register(commandDispatcher);
        });
  }

  /** Registers event listeners for server and player events. */
  private static void registerEvents() {

    ServerPlayConnectionEvents.JOIN.register(ServerEvents::onPlayerJoin);
    ServerPlayConnectionEvents.DISCONNECT.register(ServerEvents::onPlayerLeave);
    ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(ServerEvents::onAllowChatMessage);
    ServerTickEvents.END_SERVER_TICK.register(ServerEvents::onEndServerTick);

    UseBlockCallback.EVENT.register(BlockEvents::onBlockUsage);
    UseItemCallback.EVENT.register(BlockEvents::onItemUsage);

    UseEntityCallback.EVENT.register(EntityEvents::onEntityUse);
    AttackEntityCallback.EVENT.register(EntityEvents::onEntityAttack);
    ServerLivingEntityEvents.ALLOW_DAMAGE.register(EntityEvents::onEntityDamage);
    ServerLivingEntityEvents.ALLOW_DEATH.register(EntityEvents::onEntityDamage);
  }
}
