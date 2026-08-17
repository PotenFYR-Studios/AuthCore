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

    // Native event-bus hooks are active: the fallback mixins must not fire in parallel
    // (otherwise join/leave/tick would be processed twice on every Forge-like server).
    net.ded3ec.events.ServerEvents.nativeJoinActive = true;
    net.ded3ec.events.ServerEvents.nativeLeaveActive = true;
    net.ded3ec.events.ServerEvents.nativeTickActive = true;

    NeoForge.EVENT_BUS.addListener(
        RegisterCommandsEvent.class,
        (RegisterCommandsEvent event) ->
            net.ded3ec.util.FabricHooks.registerCommands(event.getDispatcher()));

    // The fabric-api hooks (registered reflectively when fabric-api is present, e.g. via
    // Sinytra Connector) fire for the same join/leave/tick - skip when they are active.
    NeoForge.EVENT_BUS.addListener(
        PlayerEvent.PlayerLoggedInEvent.class,
        (PlayerEvent.PlayerLoggedInEvent event) -> {
          if (event.getEntity() instanceof ServerPlayer player
              && !net.ded3ec.events.ServerEvents.fabricJoinActive)
            net.ded3ec.events.ServerEvents.onPlayerJoin(player.connection);
        });

    NeoForge.EVENT_BUS.addListener(
        PlayerEvent.PlayerLoggedOutEvent.class,
        (PlayerEvent.PlayerLoggedOutEvent event) -> {
          if (event.getEntity() instanceof ServerPlayer player
              && !net.ded3ec.events.ServerEvents.fabricLeaveActive)
            net.ded3ec.events.ServerEvents.onPlayerLeave(player.connection);
        });

    NeoForge.EVENT_BUS.addListener(
        ServerTickEvent.Post.class,
        (ServerTickEvent.Post event) -> {
          if (!net.ded3ec.events.ServerEvents.fabricTickActive)
            net.ded3ec.events.ServerEvents.onEndServerTick(ServerLifecycleHooks.getCurrentServer());
        });
  }
}
/*?}*/
