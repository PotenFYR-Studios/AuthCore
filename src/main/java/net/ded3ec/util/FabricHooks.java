package net.ded3ec.util;

import java.lang.reflect.Method;
import net.ded3ec.command.Account;
import net.ded3ec.command.Admin;
import net.ded3ec.command.Login;
import net.ded3ec.command.Register;
import net.ded3ec.events.BlockEvents;
import net.ded3ec.events.EntityEvents;

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

  /** Loads the four AuthCore command trees into a brigadier dispatcher (loader-neutral). */
  public static void registerCommands(Object dispatcher) {
    @SuppressWarnings("unchecked")
    com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> d =
        (com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>) dispatcher;
    Register.load(d);
    Login.load(d);
    Account.load(d);
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
        "attack",
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
   * Registers the Velocity MODERN forwarding receiver (fabric-api ServerLoginNetworking).
   * Same logic as the classic build; Mojang class names (ResourceLocation). Registered
   * reflectively - if the 26.x fabric-api does not expose ServerLoginNetworking yet, the
   * receiver is skipped gracefully.
   */
  public static void registerVelocityForwarding() {
    try {
      var cfg = net.ded3ec.AuthCoreServer.config;
      if (cfg == null || !cfg.session.proxySupport.enabled) return;
      String secret = cfg.session.proxySupport.velocitySecret;
      if (secret == null || secret.isBlank()) return;

      Class<?> networking = Class.forName("net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking");
      Class<?> handler =
          Class.forName("net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking$LoginQueryRequestHandler");

      Object identifier = null;
      try {
        Class<?> idClass = Class.forName("net.minecraft.resources.ResourceLocation");
        identifier =
            idClass.getMethod("tryParse", String.class)
                .invoke(null, net.ded3ec.network.VelocitySupport.PLAYER_INFO_CHANNEL);
      } catch (ReflectiveOperationException ignored) {
        // tryParse not present on this version
      }
      if (identifier == null) {
        identifier =
            Class.forName("net.minecraft.resources.ResourceLocation")
                .getConstructor(String.class)
                .newInstance(net.ded3ec.network.VelocitySupport.PLAYER_INFO_CHANNEL);
      }

      Object receiver =
          java.lang.reflect.Proxy.newProxyInstance(
              handler.getClassLoader(),
              new Class<?>[] {handler},
              (proxy, method, args) -> {
                if (method.getName().equals("receive") && args != null && args.length >= 4) {
                  if (!Boolean.TRUE.equals(args[2]) || args[3] == null) return null;
                  byte[] data = readRemainingBytes(args[3]);
                  var info =
                      net.ded3ec.network.VelocitySupport.parsePlayerInfo(
                          data, net.ded3ec.AuthCoreServer.config.session.proxySupport.velocitySecret);
                  if (info != null) applyForwardedProfile(args[1], info);
                }
                return null;
              });

      networking
          .getMethod("registerGlobalReceiver", identifier.getClass(), handler)
          .invoke(null, identifier, receiver);
      net.ded3ec.AuthCoreServer.LOGGER.info(
          true, "Velocity modern forwarding receiver registered (velocity:player_info).");
    } catch (ReflectiveOperationException | RuntimeException ignored) {
      // ServerLoginNetworking not available on this version - skipped gracefully
    }
  }

  /** Copies the remaining bytes of the packet buffer (FriendlyByteBuf / ByteBuf). */
  private static byte[] readRemainingBytes(Object buf) {
    try {
      if (buf instanceof io.netty.buffer.ByteBuf b) {
        byte[] data = new byte[b.readableBytes()];
        b.getBytes(b.readerIndex(), data);
        return data;
      }
    } catch (Exception ignored) {
      // fall through
    }
    return null;
  }

  /** Sets the login profile to the forwarded identity (reflective - profile setter varies). */
  private static void applyForwardedProfile(Object handler, net.ded3ec.network.VelocitySupport.PlayerInfo info) {
    try {
      com.mojang.authlib.GameProfile profile =
          new com.mojang.authlib.GameProfile(info.uuid, info.username);
      try {
        handler.getClass().getMethod("setProfile", com.mojang.authlib.GameProfile.class)
            .invoke(handler, profile);
      } catch (ReflectiveOperationException e) {
        java.lang.reflect.Field f = handler.getClass().getField("profile");
        f.set(handler, profile);
      }
      net.ded3ec.AuthCoreServer.LOGGER.info(
          true,
          "Velocity forwarding: applied identity {} ({}) for login.",
          info.username,
          info.uuid);
    } catch (ReflectiveOperationException ignored) {
      // profile could not be applied - vanilla flow continues
    }
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