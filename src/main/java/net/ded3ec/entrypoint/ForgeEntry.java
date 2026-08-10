package net.ded3ec.entrypoint;

/*? if forge && < 1.21 {*/
/*import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

// Forge (legacy, 1.16 - 1.20) entrypoint - listed in META-INF/mods.toml.
// Uses the classic @SubscribeEvent + EVENT_BUS.register(...) pattern (forge 1.21+ moved
// to the record-based eventbus, see ForgeEntryModern).
@Mod("authcore")
public class ForgeEntry {

  public ForgeEntry() {
    net.ded3ec.AuthCoreServer.start();
    MinecraftForge.EVENT_BUS.register(ForgeEvents.class);
  }

  // Forge event handlers (scanned via EVENT_BUS.register - no per-version addListener APIs).
  public static class ForgeEvents {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
      net.ded3ec.util.FabricHooks.registerCommands(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
      if (event.getEntity() instanceof ServerPlayer player)
        net.ded3ec.events.ServerEvents.onPlayerJoin(player.connection);
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
      if (event.getEntity() instanceof ServerPlayer player)
        net.ded3ec.events.ServerEvents.onPlayerLeave(player.connection);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
      if (isEndTick(event))
        net.ded3ec.events.ServerEvents.onEndServerTick(ServerLifecycleHooks.getCurrentServer());
    }

    // End-of-tick detection: TickEvent.Phase.END field on 1.16 - 1.20.
    private static boolean isEndTick(Object event) {
      try {
        java.lang.reflect.Field phase = event.getClass().getField("phase");
        Object value = phase.get(event);
        return value != null && "END".equals(value.toString());
      } catch (ReflectiveOperationException e) {
        return false;
      }
    }
  }
}
*//*?}*/
