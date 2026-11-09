package in.potenfyr.authcore.util;

import java.lang.reflect.Method;
import in.potenfyr.authcore.command.Account;
import in.potenfyr.authcore.command.Admin;
import in.potenfyr.authcore.command.Discord;
import in.potenfyr.authcore.command.Login;
import in.potenfyr.authcore.command.Register;
import in.potenfyr.authcore.events.BlockEvents;
import in.potenfyr.authcore.events.EntityEvents;

/**
 * Universal Fabric API hook registration (1.16 - 26.x).
 *
 * <p>Fabric APIs differ between versions (command API v1 vs v2, item-use callback return types,
 * presence of the entity damage events), so every registration happens reflectively against the
 * fabric-api classes available on the running version. Missing APIs are skipped gracefully.
 */
public final class FabricHooks {

  private FabricHooks() {}

  /** Registers all AuthCore commands (command API v2 on 1.19.4+, v1 before that). */
  public static void registerCommands() {
    // v2 callback: (CommandDispatcher, CommandRegistryAccess, Environment)
    try {
      Class<?> callbackClass =
          Class.forName("net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback");
      Object event = callbackClass.getField("EVENT").get(null);

      Object listener =
          java.lang.reflect.Proxy.newProxyInstance(
              callbackClass.getClassLoader(),
              new Class<?>[] {callbackClass},
              (proxy, method, args) -> {
                if (method.getName().equals("register") && args != null && args.length == 3) {
                  registerCommands(args[0]);
                }
                return null;
              });

      registerEvent(event, callbackClass, listener);
      return;
    } catch (ReflectiveOperationException ignored) {
      // v2 not available - try v1
    }

    // v1 callback: (CommandDispatcher, boolean)
    try {
      Class<?> callbackClass =
          Class.forName("net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback");
      Object event = callbackClass.getField("EVENT").get(null);

      Object listener =
          java.lang.reflect.Proxy.newProxyInstance(
              callbackClass.getClassLoader(),
              new Class<?>[] {callbackClass},
              (proxy, method, args) -> {
                if (method.getName().equals("register") && args != null && args.length == 2) {
                  registerCommands(args[0]);
                }
                return null;
              });

      registerEvent(event, callbackClass, listener);
    } catch (ReflectiveOperationException ignored) {
      // no command API available on this version
    }
  }

  /** Loads the AuthCore command trees into a brigadier dispatcher (loader-neutral). */
  public static void registerCommands(Object dispatcher) {
    @SuppressWarnings("unchecked")
    com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> d =
        (com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>) dispatcher;
    Register.load(d);
    Login.load(d);
    Account.load(d);
    Discord.load(d);
    Admin.load(d);
  }

  /**
   * Registers the item-use and entity damage/death hooks.
   *
   * <p>The item-use callback returns {@code ActionResult} on 1.19.4+ and
   * {@code TypedActionResult<ItemStack>} before that - the reflective listener adapts.
   */
  public static void registerInteractionEvents() {
    // UseItemCallback
    try {
      Class<?> callbackClass = Class.forName("net.fabricmc.fabric.api.event.player.UseItemCallback");
      Object event = callbackClass.getField("EVENT").get(null);

      Object listener =
          java.lang.reflect.Proxy.newProxyInstance(
              callbackClass.getClassLoader(),
              new Class<?>[] {callbackClass},
              (proxy, method, args) -> {
                if (method.getName().equals("interact") && args != null && args.length == 3) {
                  net.minecraft.world.InteractionResult result =
                      BlockEvents.onItemUsage(
                          (net.minecraft.world.entity.player.Player) args[0],
                          (net.minecraft.world.level.Level) args[1],
                          (net.minecraft.world.InteractionHand) args[2]);

                  // Adapt the return type to the version-specific callback signature
                  for (Method m : method.getReturnType().getMethods()) {
                    if (m.getName().equals("pass") || m.getName().equals("fail")) {
                      Object empty = net.minecraft.world.item.ItemStack.EMPTY;
                      return m.invoke(null, empty);
                    }
                  }
                  return result;
                }
                return null;
              });

      registerEvent(event, callbackClass, listener);
    } catch (ReflectiveOperationException ignored) {
      // UseItemCallback not available
    }

    // UseBlockCallback / UseEntityCallback / AttackEntityCallback.
    // Registered reflectively because their interfaces are compiled per-version and
    // reference version-specific types (e.g. ActionResult nested classes on 1.20.5+)
    // that do not exist on older servers - a direct reference breaks 1.19.4/1.20.6.
    registerUseCallback(
        "net.fabricmc.fabric.api.event.player.UseBlockCallback",
        "interact",
        args ->
            BlockEvents.onBlockUsage(
                (net.minecraft.world.entity.player.Player) args[0],
                (net.minecraft.world.level.Level) args[1],
                (net.minecraft.world.InteractionHand) args[2],
                (net.minecraft.world.phys.BlockHitResult) args[3]));
    registerUseCallback(
        "net.fabricmc.fabric.api.event.player.UseEntityCallback",
        "interact",
        args ->
            EntityEvents.onEntityUse(
                (net.minecraft.world.entity.player.Player) args[0],
                (net.minecraft.world.level.Level) args[1],
                (net.minecraft.world.InteractionHand) args[2],
                (net.minecraft.world.entity.Entity) args[3],
                (net.minecraft.world.phys.EntityHitResult) args[4]));
    registerUseCallback(
        "net.fabricmc.fabric.api.event.player.AttackEntityCallback",
        "interact",
        args ->
            EntityEvents.onEntityAttack(
                (net.minecraft.world.entity.player.Player) args[0],
                (net.minecraft.world.level.Level) args[1],
                (net.minecraft.world.InteractionHand) args[2],
                (net.minecraft.world.entity.Entity) args[3],
                (net.minecraft.world.phys.EntityHitResult) args[4]));

    // ServerLivingEntityEvents (ALLOW_DAMAGE / ALLOW_DEATH) - only on newer fabric-api versions
    try {
      Class<?> eventsClass =
          Class.forName("net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents");
      for (String field : new String[] {"ALLOW_DAMAGE", "ALLOW_DEATH"}) {
        java.lang.reflect.Field eventField = eventsClass.getField(field);
        Object event = eventField.get(null);
        Class<?> listenerType = resolveListenerType(eventField, event);
        if (listenerType == null) continue;
        Object listener =
            java.lang.reflect.Proxy.newProxyInstance(
                eventsClass.getClassLoader(),
                new Class<?>[] {listenerType},
                (proxy, method, args) -> {
                  if (method.getName().equals("allowDamage") || method.getName().equals("allowDeath")) {
                    if (args != null && args.length == 3) {
                      return EntityEvents.onEntityDamage(
                          (net.minecraft.world.entity.LivingEntity) args[0],
                          (net.minecraft.world.damagesource.DamageSource) args[1],
                          ((Number) args[2]).floatValue());
                    }
                  }
                  return true;
                });
        try {
          registerEvent(event, listenerType, listener);
        } catch (ReflectiveOperationException e) {
          // fall back to the generic Event.register(consumer) shape
          event.getClass().getMethod("register", Object.class).invoke(event, listener);
        }
      }
    } catch (ReflectiveOperationException ignored) {
      // entity damage events not available on this fabric-api version
    }
  }

  /**
   * Registers a fabric-api callback event reflectively through a proxy listener.
   *
   * <p>The proxy's invoke handler receives the version-specific argument list and forwards it
   * to the AuthCore handler; the returned {@code InteractionResult} is passed back through the
   * version-specific callback adapter, so no version-specific types are ever linked directly.
   */
  private static void registerUseCallback(
      String callbackClassName,
      String listenerMethod,
      java.util.function.Function<Object[], Object> handler) {
    try {
      Class<?> callbackClass = Class.forName(callbackClassName);
      Object event = callbackClass.getField("EVENT").get(null);
      Object listener =
          java.lang.reflect.Proxy.newProxyInstance(
              callbackClass.getClassLoader(),
              new Class<?>[] {callbackClass},
              (proxy, method, args) -> {
                if (method.getName().equals(listenerMethod) && args != null && args.length > 0) {
                  return handler.apply(args);
                }
                return null;
              });
      registerEvent(event, callbackClass, listener);
    } catch (ReflectiveOperationException ignored) {
      // callback not available on this fabric-api version
    }
  }

  /**
   * Resolves the listener interface of a fabric-api {@code Event<T>} field. Reads the generic
   * {@code T} argument (the interface is no longer discoverable via {@code getInterfaces()[0]}
   * on newer fabric-api versions where the event class shape changed).
   */
  private static Class<?> resolveListenerType(java.lang.reflect.Field field, Object event) {
    java.lang.reflect.Type generic = field.getGenericType();
    if (generic instanceof java.lang.reflect.ParameterizedType parameterized) {
      java.lang.reflect.Type argument = parameterized.getActualTypeArguments()[0];
      if (argument instanceof Class<?> type) return type;
    }
    Class<?>[] interfaces = event.getClass().getInterfaces();
    return interfaces.length > 0 ? interfaces[0] : null;
  }

  /**
   * Registers the fabric-api player join/leave + server tick events reflectively.
   *
   * <p>Never linked directly: the fabric-api jar's mod id renamed from {@code fabric} to
   * {@code fabric-api} between 1.17 and 1.18, and servers may run without fabric-api at all -
   * a direct import would throw NoClassDefFoundError during Registry.register(). When
   * fabric-api is missing on a fabric server, a clear warning is logged once instead.
   */
  public static void registerServerEvents() {
    // Set each flag at REGISTRATION time (not inside the callback): the parallel loader-native
    // hooks (Forge/NeoForge event bus) must know BEFORE the first join that the fabric hook
    // will fire, or both would process the same join (duplicate-register kick).
    if (registerReflectEvent(
        "net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents",
        "JOIN",
        "onPlayReady",
        args -> {
          in.potenfyr.authcore.events.ServerEvents.onPlayerJoin(
              (net.minecraft.server.network.ServerGamePacketListenerImpl) args[0]);
          return null;
        }))
      in.potenfyr.authcore.events.ServerEvents.fabricJoinActive = true;
    if (registerReflectEvent(
        "net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents",
        "DISCONNECT",
        "onPlayDisconnect",
        args -> {
          in.potenfyr.authcore.events.ServerEvents.onPlayerLeave(
              (net.minecraft.server.network.ServerGamePacketListenerImpl) args[0]);
          return null;
        }))
      in.potenfyr.authcore.events.ServerEvents.fabricLeaveActive = true;
    if (registerReflectEvent(
        "net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents",
        "END_SERVER_TICK",
        "onEndTick",
        args -> {
          in.potenfyr.authcore.events.ServerEvents.onEndServerTick(
              (net.minecraft.server.MinecraftServer) args[0]);
          return null;
        }))
      in.potenfyr.authcore.events.ServerEvents.fabricTickActive = true;
    boolean anyRegistered =
        in.potenfyr.authcore.events.ServerEvents.fabricJoinActive
            || in.potenfyr.authcore.events.ServerEvents.fabricLeaveActive
            || in.potenfyr.authcore.events.ServerEvents.fabricTickActive;

    // Fabric server WITHOUT fabric-api: the mixin fallbacks (ServerEventsFallbackMixin)
    // keep the join/leave/tick flow working, so the auth limbo still applies.
    if (!anyRegistered
        && in.potenfyr.authcore.compat.Compat.getLoaderVersion() != null
        && !in.potenfyr.authcore.compat.Compat.getLoaderVersion().startsWith("FML")) {
      in.potenfyr.authcore.AuthCoreServer.LOGGER.warn(
          false,
          "Fabric API was not found - using mixin fallbacks for join/leave/tick. "
              + "The auth flow still works, but install Fabric API (Modrinth: fabric-api) "
              + "for the full feature set.");
    }
  }

  /**
   * Registers a fabric-api event field (e.g. {@code ServerPlayConnectionEvents.JOIN}) through
   * a reflective proxy listener. The listener interface is resolved from the field's generic
   * {@code Event<T>} type; invocations of {@code methodName} are forwarded to {@code handler}.
   *
   * @return true when the listener was registered
   */
  private static boolean registerReflectEvent(
      String holderClass,
      String fieldName,
      String methodName,
      java.util.function.Function<Object[], Object> handler) {
    try {
      Class<?> holder = Class.forName(holderClass);
      java.lang.reflect.Field eventField = holder.getField(fieldName);
      Object event = eventField.get(null);
      Class<?> listenerType = resolveListenerType(eventField, event);
      if (listenerType == null) return false;
      Object listener =
          java.lang.reflect.Proxy.newProxyInstance(
              holder.getClassLoader(),
              new Class<?>[] {listenerType},
              (proxy, method, args) ->
                  method.getName().equals(methodName) && args != null
                      ? handler.apply(args)
                      : null);
      try {
        registerEvent(event, listenerType, listener);
      } catch (ReflectiveOperationException e) {
        event.getClass().getMethod("register", Object.class).invoke(event, listener);
      }
      return true;
    } catch (ReflectiveOperationException ignored) {
      // event/field/interface not available on this fabric-api version
    }
    return false;
  }

  /**
   * Registers a listener on a fabric-api event. The concrete event class (ArrayBackedEvent) is
   * package-private and erases {@code register(T)} to {@code register(Object)}, so fall back to
   * the erased signature and open the method with setAccessible when needed.
   */
  private static void registerEvent(Object event, Class<?> listenerType, Object listener)
      throws ReflectiveOperationException {
    java.lang.reflect.Method m;
    try {
      m = event.getClass().getMethod("register", listenerType);
    } catch (NoSuchMethodException e) {
      m = event.getClass().getMethod("register", Object.class);
    }
    try {
      m.invoke(event, listener);
    } catch (IllegalAccessException e) {
      m.setAccessible(true);
      m.invoke(event, listener);
    }
  }
}