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
                  loadCommands(args[0]);
                }
                return null;
              });

      event.getClass().getMethod("register", callbackClass).invoke(event, listener);
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
                  loadCommands(args[0]);
                }
                return null;
              });

      event.getClass().getMethod("register", callbackClass).invoke(event, listener);
    } catch (ReflectiveOperationException ignored) {
      // no command API available on this version
    }
  }

  /** Loads the four AuthCore command trees into the dispatcher. */
  private static void loadCommands(Object dispatcher) {
    @SuppressWarnings("unchecked")
    com.mojang.brigadier.CommandDispatcher<net.minecraft.server.command.ServerCommandSource> d =
        (com.mojang.brigadier.CommandDispatcher<net.minecraft.server.command.ServerCommandSource>) dispatcher;
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
                  net.minecraft.util.ActionResult result =
                      BlockEvents.onItemUsage(
                          (net.minecraft.entity.player.PlayerEntity) args[0],
                          (net.minecraft.world.World) args[1],
                          (net.minecraft.util.Hand) args[2]);

                  // Adapt the return type to the version-specific callback signature
                  for (Method m : method.getReturnType().getMethods()) {
                    if (m.getName().equals("pass") || m.getName().equals("fail")) {
                      Object empty = net.minecraft.item.ItemStack.EMPTY;
                      return m.invoke(null, empty);
                    }
                  }
                  return result;
                }
                return null;
              });

      event.getClass().getMethod("register", callbackClass).invoke(event, listener);
    } catch (ReflectiveOperationException ignored) {
      // UseItemCallback not available
    }

    // ServerLivingEntityEvents (ALLOW_DAMAGE / ALLOW_DEATH) - only on newer fabric-api versions
    try {
      Class<?> eventsClass =
          Class.forName("net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents");
      for (String field : new String[] {"ALLOW_DAMAGE", "ALLOW_DEATH"}) {
        Object event = eventsClass.getField(field).get(null);
        Object listener =
            java.lang.reflect.Proxy.newProxyInstance(
                eventsClass.getClassLoader(),
                new Class<?>[] {event.getClass().getInterfaces()[0]},
                (proxy, method, args) -> {
                  if (method.getName().equals("allowDamage") || method.getName().equals("allowDeath")) {
                    if (args != null && args.length == 3) {
                      return EntityEvents.onEntityDamage(
                          (net.minecraft.entity.LivingEntity) args[0],
                          (net.minecraft.entity.damage.DamageSource) args[1],
                          ((Number) args[2]).floatValue());
                    }
                  }
                  return true;
                });
        try {
          event.getClass().getMethod("register", listener.getClass().getInterfaces()[0])
              .invoke(event, listener);
        } catch (ReflectiveOperationException e) {
          // fall back to the generic Event.register(consumer) shape
          event.getClass().getMethod("register", Object.class).invoke(event, listener);
        }
      }
    } catch (ReflectiveOperationException ignored) {
      // entity damage events not available on this fabric-api version
    }
  }
}
