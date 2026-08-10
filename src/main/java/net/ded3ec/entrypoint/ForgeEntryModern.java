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

    RegisterCommandsEvent.BUS.addListener(
        (RegisterCommandsEvent event) ->
            net.ded3ec.util.FabricHooks.registerCommands(event.getDispatcher()));

    PlayerEvent.PlayerLoggedInEvent.BUS.addListener(
        (PlayerEvent.PlayerLoggedInEvent event) -> {
          if (event.getEntity() instanceof ServerPlayer player)
            net.ded3ec.events.ServerEvents.onPlayerJoin(player.connection);
        });

    PlayerEvent.PlayerLoggedOutEvent.BUS.addListener(
        (PlayerEvent.PlayerLoggedOutEvent event) -> {
          if (event.getEntity() instanceof ServerPlayer player)
            net.ded3ec.events.ServerEvents.onPlayerLeave(player.connection);
        });

    TickEvent.ServerTickEvent.Post.BUS.addListener(
        (TickEvent.ServerTickEvent.Post event) ->
            net.ded3ec.events.ServerEvents.onEndServerTick(ServerLifecycleHooks.getCurrentServer()));
  }
}
*//*?}*/
