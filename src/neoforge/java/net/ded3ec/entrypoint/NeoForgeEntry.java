/*? if neoforge {*/
package net.ded3ec.entrypoint;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * NeoForge (1.20.1+, incl. 26.x) entrypoint - listed in META-INF/neoforge.mods.toml.
 *
 * <p>Registers the neoforge-side equivalents of the fabric event hooks: command registration,
 * player join/leave and the server tick. Lobby interaction restrictions still work through
 * the loader-agnostic mixins; block/entity-use restrictions are fabric-only today.
 */
@Mod("authcore")
public class NeoForgeEntry {

  public NeoForgeEntry(IEventBus modEventBus, net.neoforged.fml.ModContainer container) {
    net.ded3ec.AuthCoreServer.start();

    NeoForge.EVENT_BUS.addListener(
        RegisterCommandsEvent.class,
        (RegisterCommandsEvent event) ->
            net.ded3ec.util.FabricHooks.registerCommands(event.getDispatcher()));

    NeoForge.EVENT_BUS.addListener(
        PlayerEvent.PlayerLoggedInEvent.class,
        (PlayerEvent.PlayerLoggedInEvent event) -> {
          if (event.getEntity() instanceof ServerPlayer player)
            net.ded3ec.events.ServerEvents.onPlayerJoin(player.connection);
        });

    NeoForge.EVENT_BUS.addListener(
        PlayerEvent.PlayerLoggedOutEvent.class,
        (PlayerEvent.PlayerLoggedOutEvent event) -> {
          if (event.getEntity() instanceof ServerPlayer player)
            net.ded3ec.events.ServerEvents.onPlayerLeave(player.connection);
        });

    NeoForge.EVENT_BUS.addListener(
        ServerTickEvent.Post.class,
        (ServerTickEvent.Post event) ->
            net.ded3ec.events.ServerEvents.onEndServerTick(ServerLifecycleHooks.getCurrentServer()));
  }
}
/*?}*/
