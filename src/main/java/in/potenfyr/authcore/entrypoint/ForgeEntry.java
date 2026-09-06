package in.potenfyr.authcore.entrypoint;

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
    in.potenfyr.authcore.AuthCoreServer.start();

    // Native event-bus hooks are active: the fallback mixins must not fire in parallel.
    in.potenfyr.authcore.events.ServerEvents.nativeJoinActive = true;
    in.potenfyr.authcore.events.ServerEvents.nativeLeaveActive = true;
    in.potenfyr.authcore.events.ServerEvents.nativeTickActive = true;

    MinecraftForge.EVENT_BUS.register(ForgeEvents.class);
  }

  // Forge event handlers (scanned via EVENT_BUS.register - no per-version addListener APIs).
  // The fabric-api hooks (present e.g. via Sinytra Connector) fire for the same events -
  // skip when they are active.
  public static class ForgeEvents {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
      in.potenfyr.authcore.util.FabricHooks.registerCommands(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
      if (event.getEntity() instanceof ServerPlayer player
          && !in.potenfyr.authcore.events.ServerEvents.fabricJoinActive)
        in.potenfyr.authcore.events.ServerEvents.onPlayerJoin(player.connection);
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
      if (event.getEntity() instanceof ServerPlayer player
          && !in.potenfyr.authcore.events.ServerEvents.fabricLeaveActive)
        in.potenfyr.authcore.events.ServerEvents.onPlayerLeave(player.connection);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
      if (isEndTick(event) && !in.potenfyr.authcore.events.ServerEvents.fabricTickActive)
        in.potenfyr.authcore.events.ServerEvents.onEndServerTick(ServerLifecycleHooks.getCurrentServer());
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
