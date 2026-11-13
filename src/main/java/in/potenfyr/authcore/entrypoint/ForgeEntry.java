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

// Forge (legacy, 1.17 - 1.20.4, SRG names at runtime) entrypoint - listed in META-INF/mods.toml.
// Uses the classic @SubscribeEvent + EVENT_BUS.register(...) pattern; the builds for Forge
// 1.20.6+ use the reflective entrypoint at the bottom of this file.
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

/*? if forge && >= 1.21 {*/
/*import in.potenfyr.authcore.events.ServerEvents;
import java.lang.reflect.Method;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

// Forge (modern, 1.20.6 - 26.x, Mojang names at runtime) entrypoint - listed in META-INF/mods.toml.
//
// ONE jar spans two generations of the Forge event bus, so the listeners are registered
// reflectively instead of being compiled against either API:
//   - EventBus 7 (Forge 56+, Minecraft 1.21.6+): every event owns a static BUS -
//     RegisterCommandsEvent.BUS.addListener(consumer)
//   - EventBus 6 (Forge 50 - 55, Minecraft 1.20.6 - 1.21.5): one shared bus -
//     MinecraftForge.EVENT_BUS.addListener(priority, receiveCancelled, eventType, consumer)
// A listener that cannot be registered leaves its native*Active flag false, so the
// loader-neutral fallback mixins (ServerEventsFallback*Mixin) take over for that event.
@Mod("authcore")
public class ForgeEntry {

  private static final String EVENTS = "net.minecraftforge.event.";

  public ForgeEntry() {
    in.potenfyr.authcore.AuthCoreServer.start();

    if (!listen(
        EVENTS + "RegisterCommandsEvent",
        event ->
            in.potenfyr.authcore.util.FabricHooks.registerCommands(call(event, "getDispatcher"))))
      in.potenfyr.authcore.AuthCoreServer.LOGGER.error(
          false,
          "Forge RegisterCommandsEvent could not be hooked - AuthCore commands are unavailable");

    // The fabric-api hooks (present e.g. via Sinytra Connector) fire for the same events -
    // skip when they are active.
    ServerEvents.nativeJoinActive =
        listen(
            EVENTS + "entity.player.PlayerEvent$PlayerLoggedInEvent",
            event -> {
              if (call(event, "getEntity") instanceof ServerPlayer player
                  && !ServerEvents.fabricJoinActive) ServerEvents.onPlayerJoin(player.connection);
            });

    ServerEvents.nativeLeaveActive =
        listen(
            EVENTS + "entity.player.PlayerEvent$PlayerLoggedOutEvent",
            event -> {
              if (call(event, "getEntity") instanceof ServerPlayer player
                  && !ServerEvents.fabricLeaveActive) ServerEvents.onPlayerLeave(player.connection);
            });

    // End of tick: a dedicated Post event where it exists, the shared event's phase field before.
    Consumer<Object> tick =
        event -> {
          if (!ServerEvents.fabricTickActive)
            ServerEvents.onEndServerTick(ServerLifecycleHooks.getCurrentServer());
        };
    ServerEvents.nativeTickActive =
        listen(EVENTS + "TickEvent$ServerTickEvent$Post", tick)
            || listen(
                EVENTS + "TickEvent$ServerTickEvent",
                event -> {
                  if (isEndTick(event)) tick.accept(event);
                });
  }

  // Registers a listener for the named Forge event on whichever event bus this Forge has.
  private static boolean listen(String eventClassName, Consumer<Object> handler) {
    try {
      ClassLoader loader = ForgeEntry.class.getClassLoader();
      Class<?> eventClass = Class.forName(eventClassName, false, loader);
      try {
        java.lang.reflect.Field busField = eventClass.getField("BUS");
        Object bus = busField.get(null);
        busField.getType().getMethod("addListener", Consumer.class).invoke(bus, handler);
        return true;
      } catch (NoSuchFieldException eventBus6) {
        Object bus =
            Class.forName("net.minecraftforge.common.MinecraftForge", true, loader)
                .getField("EVENT_BUS")
                .get(null);
        Class<?> busType = Class.forName("net.minecraftforge.eventbus.api.IEventBus", false, loader);
        Class<?> priorityType =
            Class.forName("net.minecraftforge.eventbus.api.EventPriority", false, loader);
        Object normal = priorityType.getField("NORMAL").get(null);
        busType
            .getMethod("addListener", priorityType, boolean.class, Class.class, Consumer.class)
            .invoke(bus, normal, false, eventClass, handler);
        return true;
      }
    } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) {
      return false;
    }
  }

  // Invokes a public no-arg accessor of a Forge event (the most specific overload wins).
  private static Object call(Object event, String accessor) {
    try {
      Method best = null;
      for (Method m : event.getClass().getMethods())
        if (m.getName().equals(accessor)
            && m.getParameterCount() == 0
            && (best == null || best.getReturnType().isAssignableFrom(m.getReturnType()))) best = m;
      return best == null ? null : best.invoke(event);
    } catch (ReflectiveOperationException | RuntimeException e) {
      return null;
    }
  }

  private static boolean isEndTick(Object event) {
    try {
      Object value = event.getClass().getField("phase").get(event);
      return value != null && "END".equals(value.toString());
    } catch (ReflectiveOperationException e) {
      return false;
    }
  }
}
*//*?}*/
