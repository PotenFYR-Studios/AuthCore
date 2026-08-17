package net.ded3ec.entrypoint;

/*? if forge && >= 1.21 {*/
/*import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

// Forge 1.21+ entrypoint - listed in META-INF/mods.toml.
// Forge 61 moved to the record-based eventbus: every event carries its own static
// EventBus<T> (e.g. RegisterCommandsEvent.BUS), and @SubscribeEvent + EVENT_BUS.register
// only supports the pre-1.21 event classes. Legacy (1.16 - 1.20) uses ForgeEntry.
@Mod("authcore")
public class ForgeEntryModern {

  public ForgeEntryModern() {
    net.ded3ec.AuthCoreServer.start();

    // Native event-bus hooks are active: the fallback mixins must not fire in parallel.
    net.ded3ec.events.ServerEvents.nativeJoinActive = true;
    net.ded3ec.events.ServerEvents.nativeLeaveActive = true;
    net.ded3ec.events.ServerEvents.nativeTickActive = true;

    RegisterCommandsEvent.BUS.addListener(
        (RegisterCommandsEvent event) ->
            net.ded3ec.util.FabricHooks.registerCommands(event.getDispatcher()));

    // The fabric-api hooks (present e.g. via Sinytra Connector) fire for the same events -
    // skip when they are active.
    PlayerEvent.PlayerLoggedInEvent.BUS.addListener(
        (PlayerEvent.PlayerLoggedInEvent event) -> {
          if (event.getEntity() instanceof ServerPlayer player
              && !net.ded3ec.events.ServerEvents.fabricJoinActive)
            net.ded3ec.events.ServerEvents.onPlayerJoin(player.connection);
        });

    PlayerEvent.PlayerLoggedOutEvent.BUS.addListener(
        (PlayerEvent.PlayerLoggedOutEvent event) -> {
          if (event.getEntity() instanceof ServerPlayer player
              && !net.ded3ec.events.ServerEvents.fabricLeaveActive)
            net.ded3ec.events.ServerEvents.onPlayerLeave(player.connection);
        });

    TickEvent.ServerTickEvent.Post.BUS.addListener(
        (TickEvent.ServerTickEvent.Post event) -> {
          if (!net.ded3ec.events.ServerEvents.fabricTickActive)
            net.ded3ec.events.ServerEvents.onEndServerTick(ServerLifecycleHooks.getCurrentServer());
        });
  }
}
*//*?}*/
