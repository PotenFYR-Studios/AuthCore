/*? if neoforge {*/
package in.potenfyr.authcore.entrypoint;

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
    in.potenfyr.authcore.AuthCoreServer.start();

    // Native event-bus hooks are active: the fallback mixins must not fire in parallel
    // (otherwise join/leave/tick would be processed twice on every Forge-like server).
    in.potenfyr.authcore.events.ServerEvents.nativeJoinActive = true;
    in.potenfyr.authcore.events.ServerEvents.nativeLeaveActive = true;
    in.potenfyr.authcore.events.ServerEvents.nativeTickActive = true;

    NeoForge.EVENT_BUS.addListener(
        RegisterCommandsEvent.class,
        (RegisterCommandsEvent event) ->
            in.potenfyr.authcore.util.FabricHooks.registerCommands(event.getDispatcher()));

    // The fabric-api hooks (registered reflectively when fabric-api is present, e.g. via
    // Sinytra Connector) fire for the same join/leave/tick - skip when they are active.
    NeoForge.EVENT_BUS.addListener(
        PlayerEvent.PlayerLoggedInEvent.class,
        (PlayerEvent.PlayerLoggedInEvent event) -> {
          if (event.getEntity() instanceof ServerPlayer player
              && !in.potenfyr.authcore.events.ServerEvents.fabricJoinActive)
            in.potenfyr.authcore.events.ServerEvents.onPlayerJoin(player.connection);
        });

    NeoForge.EVENT_BUS.addListener(
        PlayerEvent.PlayerLoggedOutEvent.class,
        (PlayerEvent.PlayerLoggedOutEvent event) -> {
          if (event.getEntity() instanceof ServerPlayer player
              && !in.potenfyr.authcore.events.ServerEvents.fabricLeaveActive)
            in.potenfyr.authcore.events.ServerEvents.onPlayerLeave(player.connection);
        });

    NeoForge.EVENT_BUS.addListener(
        ServerTickEvent.Post.class,
        (ServerTickEvent.Post event) -> {
          if (!in.potenfyr.authcore.events.ServerEvents.fabricTickActive)
            in.potenfyr.authcore.events.ServerEvents.onEndServerTick(ServerLifecycleHooks.getCurrentServer());
        });
  }
}
/*?}*/
