package net.ded3ec.compat;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
/**
 * Version-bridging layer so the same source compiles and runs on Minecraft 1.16.0 through 1.21.x
 * (and beyond). Every method here uses reflection where the underlying API changed between
 * versions, keeping the rest of the codebase version-agnostic.
 */
public final class Compat {

  private Compat() {}

  /**
   * Resolves the executing player from a command source (or brigadier context).
   *
   * <p>Multi-candidate reflection: the method name differs per mapping era
   * ({@code getPlayer} / {@code getPlayerOrException} on unobfuscated versions, the
   * intermediary ids on 1.16-1.21). A direct call compiled for one era cannot compile for
   * the others, so every candidate is tried.
   */
  public static ServerPlayer sourcePlayer(Object sourceOrContext) {
    Object source = sourceOrContext;
    if (sourceOrContext instanceof com.mojang.brigadier.context.CommandContext<?> context) {
      source = context.getSource();
    }
    if (source == null) return null;
    for (String name : new String[] {"getPlayer", "getPlayerOrException", "method_44023", "method_9207"}) {
      try {
        Object player = source.getClass().getMethod(name).invoke(source);
        if (player instanceof ServerPlayer serverPlayer) return serverPlayer;
      } catch (ReflectiveOperationException | RuntimeException ignored) {
        // try the next name
      }
    }
    return null;
  }

  /** Sends a chat message, with or without the action-bar overlay parameter. */
  public static void sendMessage(ServerPlayer player, Component text, boolean overlay) {
    sendSystemMessage(player, text, overlay);
  }

  /** Adds a status effect (direct call - {@code LivingEntity.addEffect} in every era). */
  public static void addStatusEffect(LivingEntity entity, MobEffectInstance effect) {
    try {
      entity.addEffect(effect);
    } catch (RuntimeException ignored) {
      // could not apply effect
    }
  }

  /** Removes a status effect (RegistryEntry-based on 1.19.4+, StatusEffect on older versions). */
  public static void removeStatusEffect(LivingEntity entity, MobEffect effect) {
    try {
      Class<?> statusEffect = Class.forName("net.minecraft.entity.effect.StatusEffect");
      Method m = LivingEntity.class.getMethod("removeStatusEffect", statusEffect);
      m.invoke(entity, effect);
    } catch (ReflectiveOperationException e) {
      try {
        Class<?> registryEntry = Class.forName("net.minecraft.registry.entry.RegistryEntry");
        Method m = LivingEntity.class.getMethod("removeStatusEffect", registryEntry);
        Object entry = effect.getClass().getMethod("getRegistryEntry").invoke(effect);
        m.invoke(entity, entry);
      } catch (ReflectiveOperationException ignored) {
        // could not remove effect
      }
    }
  }

  /** Kills an entity (kill() on 1.16-1.18, kill(ServerLevel) on 1.19+ - candidate reflection). */
  public static void kill(LivingEntity entity) {
    try {
      Method m = LivingEntity.class.getMethod("kill", ServerLevel.class);
      /*? if < 1.20.2 {*/
      /*m.invoke(entity, entity.getLevel());
      *//*?} else {*/
      m.invoke(entity, entity.level());
      /*?}*/
    } catch (Throwable e) {
      try {
        Method m = LivingEntity.class.getMethod("kill");
        m.invoke(entity);
      } catch (Throwable ignored) {
        // could not kill
      }
    }
  }

  /**
   * Sends a client position-correction packet directly to the player - the guaranteed layer
   * for limbo anchoring. Even if a version's teleport API fails or skips the client
   * notification, this packet snaps the client back to the authoritative position.
   */
  public static void correctClientPosition(
      ServerPlayer player,
      double x,
      double y,
      double z,
      float yaw,
      float pitch) {
    /*? if < 1.21.11 {*/
    /*// Classic shape (1.16-1.21.1): (double, double, double, float, float, Set<Relative>, int, boolean)
    try {
      player.connection.send(
          new net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket(
              x, y, z, yaw, pitch, java.util.Set.of(), teleportId(), false));
    } catch (RuntimeException ignored) {
      // client correction is best-effort
    }
    *//*?} else {*/
    try {
      // 1.21.1 mid-range: the legacy 8-arg constructor still exists there but does NOT exist
      // on 1.21.2+ - resolved reflectively so the G2 jar runs on both.
      java.lang.reflect.Constructor<?> legacy =
          net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket.class
              .getConstructor(
                  double.class,
                  double.class,
                  double.class,
                  float.class,
                  float.class,
                  java.util.Set.class,
                  int.class,
                  boolean.class);
      player.connection.send(
          (net.minecraft.network.protocol.Packet<?>)
              legacy.newInstance(x, y, z, yaw, pitch, java.util.Set.of(), teleportId(), false));
      return;
    } catch (ReflectiveOperationException ignored) {
      // 1.21.2+ / 26.x: record shape (int, PositionMoveRotation, Set<Relative>)
    }
    try {
      // Fully reflective: PositionMoveRotation does not exist before 1.21.2, so a direct
      // reference would crash 1.21.1 servers at class load (the G2 jar must run on both).
      Class<?> pktClass =
          Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket");
      Class<?> pmrClass = Class.forName("net.minecraft.world.entity.PositionMoveRotation");
      Object rotation =
          pmrClass
              .getConstructor(
                  net.minecraft.world.phys.Vec3.class,
                  net.minecraft.world.phys.Vec3.class,
                  float.class,
                  float.class)
              .newInstance(
                  new net.minecraft.world.phys.Vec3(x, y, z),
                  net.minecraft.world.phys.Vec3.ZERO,
                  yaw,
                  pitch);
      Object packet =
          pktClass
              .getConstructor(int.class, pmrClass, java.util.Set.class)
              .newInstance(teleportId(), rotation, java.util.Set.of());
      player.connection.send((net.minecraft.network.protocol.Packet<?>) packet);
    } catch (Throwable ignored) {
      // client correction is best-effort - the server-side position is still authoritative
    }
    /*?}*/
  }

  /** A unique teleport id for client position-correction packets. */
  private static final java.util.concurrent.atomic.AtomicInteger TELEPORT_IDS =
      new java.util.concurrent.atomic.AtomicInteger(1);

  private static int teleportId() {
    return TELEPORT_IDS.incrementAndGet();
  }

  /** Teleports a player (new 1.20.5+ signature or the classic 6-arg one). */
  public static boolean teleport(
      ServerPlayer player,
      ServerLevel world,
      double x,
      double y,
      double z,
      float yaw,
      float pitch) {
    // 26.x: teleportTo(ServerLevel, double, double, double, Set<Relative>, float, float, boolean)
    try {
      Method m =
          ServerPlayer.class.getMethod(
              "teleportTo",
              ServerLevel.class,
              double.class,
              double.class,
              double.class,
              java.util.Set.class,
              float.class,
              float.class,
              boolean.class);
      return (Boolean) m.invoke(player, world, x, y, z, java.util.Set.of(), yaw, pitch, false);
    } catch (ReflectiveOperationException e) {
      // fall through to the older shapes
    }
    try {
      Method m =
          ServerPlayer.class.getMethod(
              "teleport",
              ServerLevel.class,
              double.class,
              double.class,
              double.class,
              java.util.Set.class,
              float.class,
              float.class,
              boolean.class);
      return (Boolean) m.invoke(player, world, x, y, z, java.util.Set.of(), yaw, pitch, false);
    } catch (ReflectiveOperationException e) {
      try {
        Method m =
            ServerPlayer.class.getMethod(
                "teleport",
                ServerLevel.class,
                double.class,
                double.class,
                double.class,
                float.class,
                float.class);
        m.invoke(player, world, x, y, z, yaw, pitch);
        return true;
      } catch (ReflectiveOperationException ignored) {
        return false;
      }
    }
  }

  /** Reads the frozen ticks (1.17+; 0 on older versions). */
  public static int getFrozenTicks(ServerPlayer player) {
    try {
      Method m = LivingEntity.class.getMethod("getFrozenTicks");
      return (Integer) m.invoke(player);
    } catch (ReflectiveOperationException e) {
      return 0;
    }
  }

  /** Sets the frozen ticks (no-op on versions without the API). */
  public static void setFrozenTicks(ServerPlayer player, int ticks) {
    try {
      Method m = LivingEntity.class.getMethod("setFrozenTicks", int.class);
      m.invoke(player, ticks);
    } catch (ReflectiveOperationException ignored) {
      // not available on this version
    }
  }

  /**
   * Sets the entity fall distance, tolerating the float → double field-type drift across the
   * supported range (float on 1.16-1.21.x, double on 1.21.11+ / 26.x). A direct assignment
   * compiles against the build target's descriptor and throws {@link NoSuchFieldError} on older
   * endpoints where the field has a different primitive type, so the field is resolved
   * reflectively (mojmap / SRG / intermediary names) and written with the runtime's own type.
   */
  public static void setFallDistance(net.minecraft.server.level.ServerPlayer player, double value) {
    java.lang.reflect.Field field = entityField(player.getClass(), "fallDistance", "f_19789_", "field_23362");
    if (field == null) return;
    try {
      Class<?> type = field.getType();
      if (type == double.class) field.setDouble(player, value);
      else if (type == float.class) field.setFloat(player, (float) value);
      else if (type == int.class) field.setInt(player, (int) value);
      else field.set(player, value);
    } catch (ReflectiveOperationException | RuntimeException ignored) {
      // best-effort - never break the unlock over a fall-distance reset
    }
  }

  /** Resolves a public (or hierarchy-private) float/double field by any of the given names. */
  private static java.lang.reflect.Field entityField(Class<?> owner, String... names) {
    for (String name : names) {
      try {
        java.lang.reflect.Field field = owner.getField(name);
        if (field.getType() == float.class || field.getType() == double.class) return field;
      } catch (ReflectiveOperationException ignored) {
        // try the next name
      }
    }
    for (Class<?> type = owner; type != null; type = type.getSuperclass())
      for (String name : names) {
        try {
          java.lang.reflect.Field field = type.getDeclaredField(name);
          if (field.getType() == float.class || field.getType() == double.class) {
            try {
              field.setAccessible(true);
            } catch (RuntimeException ignored) {
              // access flag could not be raised - the write below may still fail
            }
            return field;
          }
        } catch (ReflectiveOperationException ignored) {
          // try the next class/name
        }
      }
    return null;
  }

  /**
   * Builds a world registry key for the given dimension identifier (registries moved packages in
   * 1.19.3+ and ResourceLocation became Identifier at 1.21.11 - fully reflective so no shape is
   * referenced at compile time). The returned value is an opaque {@link Object} - use
   * {@link #getWorld(MinecraftServer, Object)} to resolve it.
   */
  public static Object worldKey(Object id) {
    if (id == null) return null;
    try {
      Class<?> registryKey = resolveRegistryKeyClass();
      Object world = dimensionRegistry();
      if (world == null) return null;
      return registryKey.getMethod("of", Class.class, Object.class).invoke(null, Level.class, world);
    } catch (ReflectiveOperationException ignored) {
      return null;
    }
  }

  /** Resolves the version-specific registry key class. */
  private static Class<?> resolveRegistryKeyClass() {
    try {
      return Class.forName("net.minecraft.registry.RegistryKey");
    } catch (ClassNotFoundException e) {
      try {
        return Class.forName("net.minecraft.util.registry.RegistryKey");
      } catch (ClassNotFoundException ignored) {
        return null;
      }
    }
  }

  /** The registry key of the world the player is in (direct call - stable in every era). */
  public static Object worldRegistryKey(Level world) {
    try {
      return world.dimension();
    } catch (RuntimeException err) {
      return null;
    }
  }

  /** Resolves a server world from a registry key (direct call - {@code getLevel} in every era). */
  public static ServerLevel getWorld(MinecraftServer server, Object key) {
    if (key == null) return null;
    try {
      return server.getLevel((net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>) key);
    } catch (RuntimeException err) {
      return null;
    }
  }

  /** Whether the given entity is considered "mountable" (horses, boats, camels, striders...).
   *  Name-based (the package layout moved at 1.21.11 - no compile-time class reference). */
  public static boolean isMountable(Entity entity) {
    String name = entity.getClass().getName();
    return name.endsWith(".Boat")
        || name.contains("Boat")
        || name.contains("Minecart")
        || name.endsWith(".Pig")
        || name.contains("HorseBaseEntity")
        || name.contains("AbstractHorseEntity")
        || name.endsWith(".CamelEntity")
        || name.endsWith(".StriderEntity")
        || name.endsWith(".DonkeyEntity")
        || name.endsWith(".MuleEntity");
  }

  /** Whether the given block is a dripleaf (only exists on 1.17+). */
  public static boolean isDripleaf(net.minecraft.world.level.block.state.BlockState state) {
    String name = state.getBlock().getClass().getName();
    return name.endsWith(".BigDripleafBlock") || name.endsWith(".SmallDripleafBlock");
  }

  /** Removes the player from the operator list (GameProfile-based on old versions). */
  public static void removeFromOperators(MinecraftServer server, ServerPlayer player) {
    if (server == null || player == null) return;
    try {
      Class<?> configEntry = Class.forName("net.minecraft.server.PlayerManager$PlayerConfigEntry");
      Method m = server.getPlayerList().getClass().getMethod("removeFromOperators", configEntry);
      Object entry = player.getClass().getMethod("getPlayerConfigEntry").invoke(player);
      m.invoke(server.getPlayerList(), entry);
    } catch (ReflectiveOperationException e) {
      try {
        Method m =
            server.getPlayerList().getClass().getMethod(
                "removeFromOperators", com.mojang.authlib.GameProfile.class);
        m.invoke(server.getPlayerList(), player.getGameProfile());
      } catch (ReflectiveOperationException ignored) {
        // could not remove operator status
      }
    }
  }

  /** Adds the player back to the operator list with the stored level. */
  public static void addToOperators(MinecraftServer server, ServerPlayer player, int level) {
    if (server == null || player == null) return;
    try {
      Class<?> configEntry = Class.forName("net.minecraft.server.PlayerManager$PlayerConfigEntry");
      Method m =
          server.getPlayerList().getClass().getMethod(
              "addToOperators", configEntry, Optional.class, Optional.class);
      Object entry = player.getClass().getMethod("getPlayerConfigEntry").invoke(player);
      Class<?> leveledPredicate =
          Class.forName("net.minecraft.command.permission.LeveledPermissionPredicate");
      Object predicate = leveledPredicate.getConstructor(int.class).newInstance(level);
      m.invoke(server.getPlayerList(), entry, Optional.of(predicate), Optional.of(true));
    } catch (ReflectiveOperationException e) {
      try {
        Method m =
            server.getPlayerList().getClass().getMethod(
                "addToOperators", com.mojang.authlib.GameProfile.class, int.class);
        m.invoke(server.getPlayerList(), player.getGameProfile(), level);
      } catch (ReflectiveOperationException e2) {
        try {
          Method m =
              server.getPlayerList().getClass().getMethod(
                  "addToOperators", com.mojang.authlib.GameProfile.class);
          m.invoke(server.getPlayerList(), player.getGameProfile());
        } catch (ReflectiveOperationException ignored) {
          // could not restore operator status
        }
      }
    }
  }

  /** Whether the player is currently gliding with an elytra (name differs by version). */
  public static boolean isGliding(ServerPlayer player) {
    try {
      Method m = Entity.class.getMethod("isGliding");
      return (Boolean) m.invoke(player);
    } catch (ReflectiveOperationException e) {
      try {
        Method m = Entity.class.getMethod("isFallFlying");
        return (Boolean) m.invoke(player);
      } catch (ReflectiveOperationException ignored) {
        return false;
      }
    }
  }

  /** Reads the player's connection latency in milliseconds. */
  public static int getLatency(ServerPlayer player) {    try {
      Method m =
          net.minecraft.server.network.ServerGamePacketListenerImpl.class.getMethod("getLatency");
      Object handler = player.getClass().getMethod("networkHandler").invoke(player);
      if (handler != null) return (Integer) m.invoke(handler);
    } catch (ReflectiveOperationException e) {
      try {
        return player.getClass().getField("pingMilliseconds").getInt(player);
      } catch (ReflectiveOperationException ignored) {
        // latency unavailable
      }
    }
    return 0;
  }

  /**
   * Runs a command for the player through the server dispatcher on every era:
   * {@code performPrefixedCommand(CommandSourceStack, String)} (1.19.3+) with a reflective
   * fallback to {@code performCommand(CommandSourceStack, String)} (1.16-1.19.2) - the G2 jar
   * is compiled against 1.21.11 and must run on 1.19.0-1.19.2 too. Never throws.
   */
  public static void runCommand(ServerPlayer player, String command) {
    if (player == null || command == null || command.isBlank()) return;
    try {
      MinecraftServer server = getServer(player);
      if (server == null) return;
      Object dispatcher = server.getCommands();
      Object source = player.createCommandSourceStack();
      try {
        dispatcher.getClass()
            .getMethod("performPrefixedCommand", net.minecraft.commands.CommandSourceStack.class, String.class)
            .invoke(dispatcher, source, command);
        return;
      } catch (ReflectiveOperationException ignored) {
        // 1.16-1.19.2: performCommand(CommandSourceStack, String)
      }
      dispatcher.getClass()
          .getMethod("performCommand", net.minecraft.commands.CommandSourceStack.class, String.class)
          .invoke(dispatcher, source, command);
    } catch (Throwable ignored) {
      // command execution is best-effort
    }
  }

  /**
   * Sends a system chat message across every era: {@code sendSystemMessage(Component, boolean)}
   * (1.19.4+) with direct {@code sendMessage(Component, UUID)} on (1.16-1.18.2). Never throws.
   */
  public static void sendSystemMessage(
      ServerPlayer player, net.minecraft.network.chat.Component component, boolean actionBar) {
    if (player == null || component == null) return;
    /*? if < 1.19.4 {*/
    /*try {
      if (actionBar) {
        player.sendMessage(component, net.minecraft.network.chat.ChatType.GAME_INFO, net.minecraft.Util.NIL_UUID);
      } else {
        player.sendMessage(component, net.minecraft.Util.NIL_UUID);
      }
      return;
    } catch (Throwable ignored) {
      // try fallback
    }
    *//*?} else {*/
    try {
      player.sendSystemMessage(component, actionBar);
      return;
    } catch (Throwable ignored) {
      // 1.19.0-1.19.3 mid-range: displayClientMessage
    }
    try {
      player.getClass()
          .getMethod(
              "displayClientMessage", net.minecraft.network.chat.Component.class, boolean.class)
          .invoke(player, component, actionBar);
      return;
    } catch (Throwable ignored) {
      // try reflection fallback
    }
    /*?}*/
    // Universal runtime reflection fallback for 1.16-1.18.2 Fabric/Forge
    try {
      for (Method m : player.getClass().getMethods()) {
        if (m.getName().equals("sendMessage")) {
          if (m.getParameterCount() == 2
              && m.getParameterTypes()[0] == net.minecraft.network.chat.Component.class
              && m.getParameterTypes()[1] == UUID.class) {
            m.invoke(player, component, new UUID(0L, 0L));
            return;
          } else if (m.getParameterCount() == 3
              && m.getParameterTypes()[0] == net.minecraft.network.chat.Component.class
              && m.getParameterTypes()[2] == UUID.class) {
            Object[] types = m.getParameterTypes()[1].getEnumConstants();
            Object type =
                actionBar && types != null && types.length > 2
                    ? types[2]
                    : (types != null && types.length > 1
                        ? types[1]
                        : (types != null && types.length > 0 ? types[0] : null));
            m.invoke(player, component, type, new UUID(0L, 0L));
            return;
          }
        }
      }
    } catch (Throwable ignored) {
      // message send is best-effort
    }
  }

  /** Resolves a block position from floored doubles (API changed across versions). */
  public static BlockPos blockPosFloored(double x, double y, double z) {
    try {
      Method m = BlockPos.class.getMethod("ofFloored", double.class, double.class, double.class);
      return (BlockPos) m.invoke(null, x, y, z);
    } catch (ReflectiveOperationException e) {
      return new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }
  }

  /** The lowest Y coordinate of the world (1.17+ API; 0 on older versions). */
  public static int getBottomY(Level world) {
    try {
      Method m = Level.class.getMethod("getBottomY");
      return (Integer) m.invoke(world);
    } catch (ReflectiveOperationException e) {
      return 0;
    }
  }

  /** The highest buildable Y coordinate of the world (1.17+ API; 256 on older versions). */
  public static int getMaxBuildHeight(Level world) {
    try {
      Method m = Level.class.getMethod("getMaxBuildHeight");
      return (Integer) m.invoke(world);
    } catch (ReflectiveOperationException e) {
      return 256;
    }
  }

  /**
   * Creates a text component (direct call - {@code Component.nullToEmpty} exists in every
   * mapping era and returns a MutableComponent for non-null input; reflection with
   * hard-coded names only ever matched unobfuscated 26.x runtimes).
   */
  public static net.minecraft.network.chat.MutableComponent text(String value) {
    if (value == null) value = "";
    return (net.minecraft.network.chat.MutableComponent)
        net.minecraft.network.chat.Component.nullToEmpty(value);
  }

  /**
   * Sends a packet through a network handler (signature differs by version).
   *
   * <p>The handler declares {@code send(Packet, PacketSendListener)} / {@code send(Packet)}
   * where the first parameter is the ABSTRACT {@code Packet} base type - whose class name
   * differs per mapping era ({@code net.minecraft.network.protocol.Packet} on Mojang names,
   * {@code net.minecraft.network.Packet} on Yarn). {@code getMethod} requires an EXACT
   * parameter-type match, so looking it up with the concrete packet class always fails -
   * the method is matched by name + parameter count + assignability instead.
   */
  public static boolean sendPacket(
      net.minecraft.server.network.ServerGamePacketListenerImpl handler, Object packet) {
    Method sendMethod = null;
    for (Method m : handler.getClass().getMethods()) {
      if (!"send".equals(m.getName()) || m.getParameterCount() < 1) continue;
      if (!m.getParameterTypes()[0].isAssignableFrom(packet.getClass())) continue;
      // Prefer the 2-arg PacketSendListener overload when present
      if (m.getParameterCount() == 2) {
        sendMethod = m;
        break;
      }
      if (sendMethod == null) sendMethod = m;
    }
    if (sendMethod == null) return false;

    try {
      if (sendMethod.getParameterCount() == 2)
        sendMethod.invoke(handler, packet, (Object) null);
      else sendMethod.invoke(handler, packet);
      return true;
    } catch (ReflectiveOperationException | RuntimeException ignored) {
      // could not send packet
      return false;
    }
  }

  /**
   * Sends a custom payload (plugin message) to the player's client (Mojang names, 26.1+).
   * Uses the {@code ClientboundCustomPayloadPacket(ResourceLocation, FriendlyByteBuf)}
   * constructor and never throws on failure - interop is best-effort.
   */
  public static void sendCustomPayload(
      net.minecraft.server.level.ServerPlayer player, String channel, byte[] data) {
    try {
      if (player == null || channel == null || data == null || player.connection == null) return;

      Object location = null;
      try {
        Class<?> rl = Class.forName("net.minecraft.resources.ResourceLocation");
        location = rl.getMethod("tryParse", String.class).invoke(null, channel);
      } catch (ReflectiveOperationException ignored) {
        // tryParse not present on this version
      }
      if (location == null) {
        Class<?> rl = Class.forName("net.minecraft.resources.ResourceLocation");
        location = rl.getConstructor(String.class).newInstance(channel);
      }

      Object buf =
          Class.forName("net.minecraft.network.FriendlyByteBuf")
              .getConstructor(io.netty.buffer.ByteBuf.class)
              .newInstance(io.netty.buffer.Unpooled.buffer(data.length));
      buf.getClass().getMethod("writeBytes", byte[].class).invoke(buf, (Object) data);

      Object packet =
          Class.forName("net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket")
              .getConstructor(
                  Class.forName("net.minecraft.resources.ResourceLocation"),
                  Class.forName("net.minecraft.network.FriendlyByteBuf"))
              .newInstance(location, buf);

      sendPacket(player.connection, packet);
    } catch (ReflectiveOperationException | RuntimeException ignored) {
      // interop is best-effort - never break gameplay
    }
  }

  /**
   * Sends the title, subtitle and fade packets.
   *
   * <p>The split title packets and the {@code send(Packet)} handler carry the same Mojang
   * names in every mapping era (verified on 1.18.2, 1.21.11 and 26.2), so direct
   * construction + direct send remaps correctly on every loader/version. The old
   * {@code Class.forName} approach only matched unobfuscated 26.x runtimes and silently
   * dropped every title on intermediary/SRG servers.
   *
   * @return {@code true} when the packets were sent, {@code false} otherwise (callers
   *     should fall back to chat so the message is never lost)
   */
  public static boolean sendTitle(
      net.minecraft.server.network.ServerGamePacketListenerImpl connection,
      Component title,
      Component subtitle,
      int fadeInTicks,
      int stayTicks,
      int fadeOutTicks) {
    try {
      connection.send(
          new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(
              fadeInTicks, stayTicks, fadeOutTicks));
      if (subtitle != null)
        connection.send(
            new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(subtitle));
      if (title != null)
        connection.send(
            new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(title));
      return true;
    } catch (RuntimeException err) {
      return false;
    }
  }

  /** Applies bold/italic/underline/strikethrough/obfuscate to a style (direct calls -
   * {@code withUnderlined} is the name in every era; the previous reflective lookups threw
   * NoSuchMethodException on every call and the JVM got stuck filling the exception stack
   * traces on the server thread - the watchdog killed the server. Direct calls never
   * throw here. */
  public static net.minecraft.network.chat.Style applyStyleFlags(
      net.minecraft.network.chat.Style style,
      boolean bold,
      boolean italic,
      boolean underline,
      boolean strikethrough,
      boolean obfuscate) {
    try {
      if (bold) style = style.withBold(true);
      if (italic) style = style.withItalic(true);
      if (underline) style = style.withUnderlined(true);
      if (strikethrough) style = style.withStrikethrough(true);
      if (obfuscate) style = style.withObfuscated(true);
    } catch (RuntimeException ignored) {
      // could not apply style flags
    }
    return style;
  }

  /** Applies the shadow settings (1.19.3+ API; no-op on older versions - direct calls). */
  public static net.minecraft.network.chat.Style applyShadow(
      net.minecraft.network.chat.Style style, boolean shadow, int strength) {
    try {
      /*? if < 1.19.3 {*/
      /*return style;
      *//*?} else {*/
      return shadow ? style.withShadowColor(strength) : style.withoutShadow();
      /*?}*/
    } catch (Throwable e) {
      return style;
    }
  }

  /**
   * Reads the real server.properties online-mode from the running server
   * ({@code MinecraftServer#usesAuthentication()} / {@code isOnlineMode()} reflectively,
   * since the method name differs between Yarn and Mojang mappings).
   *
   * <p>This is the authoritative source for premium decisions - the mod ALWAYS follows the
   * real server.properties mode (there is no config override). When the mode cannot be
   * detected it defaults to {@code true} (online) so a detection failure never weakens an
   * online-mode server.
   *
   * @param server the running server instance
   * @return {@code true} when the server authenticates clients (online-mode)
   */
  public static boolean serverUsesAuthentication(MinecraftServer server) {
    if (server == null) return true;
    try {
      return server.usesAuthentication();
    } catch (Throwable ignored) {
      // Fall through to reflection for custom/derivative server runtimes
    }
    // Mojang names: usesAuthentication / isOnlineMode (1.16-1.21.11), getOnlineMode (26.x); Yarn: usesAuthentication.
    // Field fallback: onlineMode (private field, mojmap - setAccessible on a JVM where
    // field access is permitted for same-module classes).
    for (String name : new String[] {"usesAuthentication", "isOnlineMode", "getOnlineMode"}) {
      try {
        return (Boolean) MinecraftServer.class.getMethod(name).invoke(server);
      } catch (ReflectiveOperationException | RuntimeException ignored) {
        // try the next name
      }
    }
    try {
      java.lang.reflect.Field f = MinecraftServer.class.getDeclaredField("onlineMode");
      f.setAccessible(true);
      Object value = f.get(server);
      if (value instanceof Boolean b) return b;
    } catch (ReflectiveOperationException | RuntimeException ignored) {
      // fall through to the safe default
    }
    return true;
  }

  /**
   * Resolves the player's level across every era: {@code level()} (1.20.2+) with a reflective
   * fallback to {@code getLevel()} (1.16-1.20.1) - the G2 jar is compiled against 1.21.11 and
   * must also run on 1.19-1.20.1 where only the old name exists. Never throws.
   */
  public static ServerLevel playerLevel(ServerPlayer player) {
    if (player == null) return null;
    /*? if < 1.20.2 {*/
    /*try {
      return (ServerLevel) player.getLevel();
    } catch (Throwable ignored) {
      return null;
    }
    *//*?} else {*/
    try {
      return (ServerLevel) player.level();
    } catch (Throwable ignored) {
      // 1.16-1.20.1: getLevel()
    }
    try {
      return (ServerLevel) player.getClass().getMethod("getLevel").invoke(player);
    } catch (Throwable ignored) {
      return null;
    }
    /*?}*/
  }

  /** Resolves any entity's level across every era (level() / getLevel() - never throws). */
  public static net.minecraft.world.level.Level entityLevel(net.minecraft.world.entity.Entity entity) {
    if (entity == null) return null;
    /*? if < 1.20.2 {*/
    /*try {
      return entity.getLevel();
    } catch (Throwable ignored) {
      return null;
    }
    *//*?} else {*/
    try {
      return entity.level();
    } catch (Throwable ignored) {
      // 1.16-1.20.1: getLevel()
    }
    try {
      return (net.minecraft.world.level.Level) entity.getClass().getMethod("getLevel").invoke(entity);
    } catch (Throwable ignored) {
      return null;
    }
    /*?}*/
  }

  /** Resolves the server instance of a player (getServer() / the public {@code server} field). */
  public static MinecraftServer getServer(ServerPlayer player) {
    if (player == null) return null;
    try {
      Object server = player.getClass().getMethod("getServer").invoke(player);
      if (server instanceof MinecraftServer ms) return ms;
    } catch (Throwable ignored) {
      // fall through to the next strategy below
    }
    // Direct level lookup (level().getServer()) - works even when getServer() is absent.
    ServerLevel level = playerLevel(player);
    if (level != null) {
      try {
        MinecraftServer ms = level.getServer();
        if (ms != null) return ms;
      } catch (Throwable ignored) {
        // level not fully attached yet
      }
    }
    // Public field (older era: ServerPlayer.server).
    try {
      Object server = ServerPlayer.class.getField("server").get(player);
      if (server instanceof MinecraftServer ms) return ms;
    } catch (Throwable ignored) {
      // fall through to the connection lookup below
    }
    // Connection-based lookup. This is the ONLY strategy that works mid-join, before the
    // player's level is attached (the join event fires from inside placeNewPlayer and
    // player.level() is null there on several loaders) - the connection's server field is
    // set as soon as the listener is created.
    try {
      Object connection = null;
      try {
        connection = player.getClass().getField("connection").get(player);
      } catch (Throwable ignored) {
        connection = player.getClass().getField("networkHandler").get(player);
      }
      if (connection != null) {
        try {
          Object server = connection.getClass().getField("server").get(connection);
          if (server instanceof MinecraftServer ms) return ms;
        } catch (Throwable ignored) {
          // private field - try a declared-field access below
        }
        try {
          java.lang.reflect.Field f = connection.getClass().getDeclaredField("server");
          f.setAccessible(true);
          Object server = f.get(connection);
          if (server instanceof MinecraftServer ms) return ms;
        } catch (Throwable ignored) {
          // server not resolvable through the connection
        }
      }
    } catch (Throwable ignored) {
      // no connection field on this mapping
    }
    return null;
  }

  /**
   * Reads the private {@code server} field of a login handler (version-agnostic - the field
   * name is stable across Yarn and Mojang mappings). Used to detect the real server
   * online-mode before the first player has fully joined.
   *
   * @param handler a {@code ServerLoginPacketListenerImpl} instance
   * @return the server, or {@code null} when the field cannot be read
   */
  public static MinecraftServer loginServer(Object handler) {
    if (handler == null) return null;
    try {
      java.lang.reflect.Field field = handler.getClass().getDeclaredField("server");
      field.setAccessible(true);
      Object server = field.get(handler);
      if (server instanceof MinecraftServer ms) return ms;
    } catch (ReflectiveOperationException | RuntimeException ignored) {
      // no server field on this version
    }
    return null;
  }

  /** Resolves the MinecraftServer from a command source stack or execution context. */
  public static MinecraftServer getSourceServer(Object source) {
    if (source == null) return null;
    if (source instanceof MinecraftServer ms) return ms;
    try {
      Object srv = source.getClass().getMethod("getServer").invoke(source);
      if (srv instanceof MinecraftServer ms) return ms;
    } catch (Throwable ignored) {}
    try {
      Object srv = source.getClass().getField("server").get(source);
      if (srv instanceof MinecraftServer ms) return ms;
    } catch (Throwable ignored) {}
    return null;
  }

  /**
   * Whether a UUID is the offline-mode UUID convention for the given username
   * ({@code UUID.nameUUIDFromBytes("OfflinePlayer:" + name)}). Offline-mode servers (and
   * premium clients joining them) present these UUIDs; they must never be mistaken for a
   * real (premium) profile UUID mismatch.
   */
  public static boolean isOfflineUuid(String username, java.util.UUID uuid) {
    if (username == null || uuid == null) return false;
    return java.util.UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .equals(uuid);
  }

  // ------------------------------------------------------------------
  // Server-side premium verification (login encryption handshake).
  //
  // On offline-mode servers the login mixin forces the vanilla encryption handshake so the
  // client sends its session token; the server then verifies it with its OWN
  // MinecraftSessionService.hasJoinedServer(...) - no external API calls from AuthCore.
  // All helpers are reflection-based because the shapes differ across 1.16 - 26.x
  // (field names nonce/challenge, sessionService() vs getSessionService(), ProfileResult
  // vs GameProfile, 3-arg vs 4-arg hello packet constructor).
  // ------------------------------------------------------------------

  /** Reads a private field by any of the given candidate names. */
  private static Object getLoginField(Object target, String... names) {
    if (target == null) return null;
    for (String name : names) {
      try {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        Object value = f.get(target);
        if (value != null) return value;
      } catch (ReflectiveOperationException | RuntimeException ignored) {
        // try the next name
      }
    }
    return null;
  }

  /**
   * Starts the vanilla encryption handshake on a login handler: state → KEY and a
   * {@code ClientboundHelloPacket(serverId="", publicKey, challenge)} is sent. The client
   * then answers with the key packet, which {@link #loginFinishHandshake} decrypts.
   *
   * @param handler a {@code ServerLoginPacketListenerImpl}
   * @return {@code true} when the handshake was started, {@code false} on failure (caller
   *     must let the vanilla offline flow continue)
   */
  public static boolean loginStartHandshake(Object handler) {
    try {
      Object server = getLoginField(handler, "server");
      Object connection = getLoginField(handler, "connection");
      if (server == null || connection == null) return false;

      Object keyPair = server.getClass().getMethod("getKeyPair").invoke(server);
      if (keyPair == null) return false;
      Object publicKey = keyPair.getClass().getMethod("getPublic").invoke(keyPair);
      byte[] encoded = (byte[]) publicKey.getClass().getMethod("getEncoded").invoke(publicKey);

      byte[] challenge = (byte[]) getLoginField(handler, "challenge", "nonce");
      if (challenge == null) return false;

      // state = KEY (enum constant)
      java.lang.reflect.Field stateField = handler.getClass().getDeclaredField("state");
      stateField.setAccessible(true);
      Object keyState = Enum.valueOf((Class<? extends Enum>) stateField.getType(), "KEY");
      stateField.set(handler, keyState);

      // ClientboundHelloPacket("", publicKeyEncoded, challenge [, true on 26.x])
      Class<?> helloClass =
          Class.forName("net.minecraft.network.protocol.login.ClientboundHelloPacket");
      Object packet;
      try {
        packet =
            helloClass
                .getConstructor(String.class, byte[].class, byte[].class, boolean.class)
                .newInstance("", encoded, challenge, true);
      } catch (NoSuchMethodException err) {
        packet =
            helloClass
                .getConstructor(String.class, byte[].class, byte[].class)
                .newInstance("", encoded, challenge);
      }

      connection.getClass().getMethod("send", packet.getClass()).invoke(connection, packet);
      return true;
    } catch (ReflectiveOperationException | RuntimeException err) {
      return false;
    }
  }

  /**
   * Completes the vanilla encryption handshake for a received key packet: verifies the
   * challenge, decrypts the shared secret, activates connection encryption and computes the
   * session digest used for {@code hasJoinedServer}.
   *
   * @param handler a {@code ServerLoginPacketListenerImpl}
   * @param keyPacket a {@code ServerboundKeyPacket}
   * @return the session digest (hex), or {@code null} on failure
   */
  public static String loginFinishHandshake(Object handler, Object keyPacket) {
    try {
      Object server = getLoginField(handler, "server");
      Object connection = getLoginField(handler, "connection");
      Object keyPair = server.getClass().getMethod("getKeyPair").invoke(server);
      Object privateKey = keyPair.getClass().getMethod("getPrivate").invoke(keyPair);
      Object publicKey = keyPair.getClass().getMethod("getPublic").invoke(keyPair);

      byte[] challenge = (byte[]) getLoginField(handler, "challenge", "nonce");
      if (challenge != null) {
        try {
          Object valid =
              keyPacket
                  .getClass()
                  .getMethod("isChallengeValid", byte[].class, java.security.PrivateKey.class)
                  .invoke(keyPacket, challenge, privateKey);
          if (!Boolean.TRUE.equals(valid)) return null;
        } catch (NoSuchMethodException ignored) {
          // 1.16-1.19.2: getNonce(PrivateKey) comparison
          byte[] sent =
              (byte[])
                  keyPacket
                      .getClass()
                      .getMethod("getNonce", java.security.PrivateKey.class)
                      .invoke(keyPacket, privateKey);
          if (sent == null || !java.util.Arrays.equals(sent, challenge)) return null;
        }
      }

      javax.crypto.SecretKey secret =
          (javax.crypto.SecretKey)
              keyPacket
                  .getClass()
                  .getMethod("getSecretKey", java.security.PrivateKey.class)
                  .invoke(keyPacket, privateKey);

      Class<?> crypt = Class.forName("net.minecraft.util.Crypt");
      javax.crypto.Cipher decrypt =
          (javax.crypto.Cipher) crypt.getMethod("getCipher", int.class, java.security.Key.class).invoke(null, 2, secret);
      javax.crypto.Cipher encrypt =
          (javax.crypto.Cipher) crypt.getMethod("getCipher", int.class, java.security.Key.class).invoke(null, 1, secret);
      connection
          .getClass()
          .getMethod("setEncryptionKey", javax.crypto.Cipher.class, javax.crypto.Cipher.class)
          .invoke(connection, decrypt, encrypt);

      byte[] digestBytes =
          (byte[])
              crypt.getMethod(
                      "digestData",
                      String.class,
                      java.security.PublicKey.class,
                      javax.crypto.SecretKey.class)
                  .invoke(null, "", publicKey, secret);
      return new java.math.BigInteger(digestBytes).toString(16);
    } catch (ReflectiveOperationException | RuntimeException err) {
      return null;
    }
  }

  /**
   * Verifies a session with the server's OWN Minecraft session service
   * ({@code hasJoinedServer}) - the same call vanilla makes on online-mode servers.
   *
   * @param server a {@code MinecraftServer}
   * @param username the requested username
   * @param digest the session digest from the handshake
   * @param address the client IP
   * @return the verified profile, or {@code null} when Mojang does not confirm the session
   */
  public static com.mojang.authlib.GameProfile loginVerifyProfile(
      Object server, String username, String digest, java.net.InetAddress address) {
    try {
      Object sessionService = null;
      try {
        sessionService = server.getClass().getMethod("getSessionService").invoke(server);
      } catch (NoSuchMethodException ignored) {
        Object services = server.getClass().getMethod("services").invoke(server);
        if (services != null)
          sessionService = services.getClass().getMethod("sessionService").invoke(services);
      }
      if (sessionService == null) return null;

      Object result =
          sessionService
              .getClass()
              .getMethod("hasJoinedServer", String.class, String.class, java.net.InetAddress.class)
              .invoke(sessionService, username, digest, address);
      if (result instanceof com.mojang.authlib.GameProfile profile) return profile;
      if (result != null) {
        // 26.x: ProfileResult wrapper
        Object profile = result.getClass().getMethod("profile").invoke(result);
        if (profile instanceof com.mojang.authlib.GameProfile gp) return gp;
      }
      return null;
    } catch (ReflectiveOperationException | RuntimeException err) {
      return null;
    }
  }

  /**
   * Accepts a client as an OFFLINE-mode player on an ONLINE-mode server (hybrid mode):
   * mimics the vanilla offline hello branch so the normal vanilla accept flow
   * ({@code tick} → {@code verifyLoginAndFinishConnectionSetup}) places the player with
   * their offline UUID - no Mojang session check, no encryption handshake.
   *
   * <p>Strategy chain (fail-safe - {@code false} when no strategy matched, so the caller
   * simply lets vanilla handle the connection):
   * <ol>
   *   <li>1.19+ / 26.x: invoke {@code startClientVerification(GameProfile)} with the
   *       offline profile (sets the handler state to VERIFYING; vanilla tick() completes
   *       the login).</li>
   *   <li>1.16-1.18.2: set {@code state = READY_TO_ACCEPT} and the profile field (the
   *       vanilla tick() then accepts the player).</li>
   * </ol>
   *
   * @param handler a {@code ServerLoginPacketListenerImpl}
   * @param username the requested username
   * @return {@code true} when the offline accept flow was started
   */
  public static boolean loginAcceptOffline(Object handler, String username) {
    if (handler == null || username == null || username.isBlank()) return false;
    try {
      java.util.UUID offlineId =
          java.util.UUID.nameUUIDFromBytes(
              ("OfflinePlayer:" + username).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      com.mojang.authlib.GameProfile offlineProfile = new com.mojang.authlib.GameProfile(offlineId, username);

      // Strategy 1: startClientVerification(GameProfile) - 1.19+ / 26.x (mojmap,
      // yarn and common intermediary names).
      java.lang.reflect.Method startVerification = null;
      for (String name :
          new String[] {"startClientVerification", "method_52417", "method_29409"}) {
        try {
          startVerification =
              handler.getClass().getDeclaredMethod(name, com.mojang.authlib.GameProfile.class);
          break;
        } catch (NoSuchMethodException ignored) {
          // try the next name
        }
      }
      if (startVerification != null) {
        startVerification.setAccessible(true);
        startVerification.invoke(handler, offlineProfile);
        return true;
      }

      // Strategy 2: 1.16-1.18.2 - state = READY_TO_ACCEPT + profile field.
      java.lang.reflect.Field stateField = null;
      for (String name : new String[] {"state", "field_14163"}) {
        try {
          stateField = handler.getClass().getDeclaredField(name);
          break;
        } catch (NoSuchFieldException ignored) {
          // try the next name
        }
      }
      java.lang.reflect.Field profileField = null;
      for (String name : new String[] {"profile", "gameProfile", "authenticatedProfile", "field_14160"}) {
        try {
          profileField = handler.getClass().getDeclaredField(name);
          break;
        } catch (NoSuchFieldException ignored) {
          // try the next name
        }
      }
      if (stateField != null && profileField != null) {
        stateField.setAccessible(true);
        profileField.setAccessible(true);
        Object readyState =
            Enum.valueOf((Class<? extends Enum>) stateField.getType(), "READY_TO_ACCEPT");
        stateField.set(handler, readyState);
        profileField.set(handler, offlineProfile);
        return true;
      }
    } catch (ReflectiveOperationException | RuntimeException err) {
      // fail-safe: caller lets vanilla handle the connection
    }
    return false;
  }

  /**
   * Re-runs the original hello packet through the login handler on the connection's event
   * loop (state is reset to HELLO first) - used to continue the join as a normal offline
   * player after premium verification succeeded or failed.
   *
   * @param handler a {@code ServerLoginPacketListenerImpl}
   * @param helloPacket the original {@code ServerboundHelloPacket}
   */
  public static void loginReplayHello(Object handler, Object helloPacket) {
    if (handler == null || helloPacket == null) return;
    try {
      Object connection = getLoginField(handler, "connection");
      java.lang.reflect.Field stateField = handler.getClass().getDeclaredField("state");
      stateField.setAccessible(true);
      java.lang.reflect.Method handleHello =
          handler.getClass().getMethod("handleHello", helloPacket.getClass());

      Runnable replay =
          () -> {
            try {
              Object helloState =
                  Enum.valueOf((Class<? extends Enum>) stateField.getType(), "HELLO");
              stateField.set(handler, helloState);
              handleHello.invoke(handler, helloPacket);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
              // join flow could not be resumed - vanilla disconnect handling takes over
            }
          };

      // Dispatch on the connection's event loop when possible (thread-safe join resume)
      if (connection != null) {
        java.lang.reflect.Field channelField =
            connection.getClass().getDeclaredField("channel");
        channelField.setAccessible(true);
        Object channel = channelField.get(connection);
        Object eventLoop = channel.getClass().getMethod("eventLoop").invoke(channel);
        eventLoop.getClass().getMethod("execute", Runnable.class).invoke(eventLoop, replay);
        return;
      }
      replay.run();
    } catch (ReflectiveOperationException | RuntimeException ignored) {
      // fallback: nothing - the vanilla login timeout will disconnect the client safely
    }
  }

  /** Reads the player yaw (tickDelta parameter on older versions). */
  public static float getYaw(ServerPlayer player) {
    try {
      Method m = Entity.class.getMethod("getYaw");
      return (Float) m.invoke(player);
    } catch (ReflectiveOperationException e) {
      try {
        Method m = Entity.class.getMethod("getYaw", float.class);
        return (Float) m.invoke(player, 1.0f);
      } catch (ReflectiveOperationException ignored) {
        return 0f;
      }
    }
  }

  /** Reads the player pitch (tickDelta parameter on older versions). */
  public static float getPitch(ServerPlayer player) {
    try {
      Method m = Entity.class.getMethod("getPitch");
      return (Float) m.invoke(player);
    } catch (ReflectiveOperationException e) {
      try {
        Method m = Entity.class.getMethod("getPitch", float.class);
        return (Float) m.invoke(player, 1.0f);
      } catch (ReflectiveOperationException ignored) {
        return 0f;
      }
    }
  }

  /**
   * Makes a chat component clickable - a "button" that runs (or suggests) a command when
   * clicked. Commands WITH arguments use SUGGEST_COMMAND (fills the chat input so the
   * player just completes it), plain commands use RUN_COMMAND. Fallback-safe: if the
   * click API is unavailable on this version/loader, the original component is returned
   * and the message still renders as plain text.
   */
  public static net.minecraft.network.chat.Component withClickCommand(
      net.minecraft.network.chat.Component component, String command) {
    if (command == null || command.isBlank()) return component;
    try {
      boolean suggests = command.contains(" ");
      net.minecraft.network.chat.ClickEvent event = buildClickEvent(suggests, command);
      if (event == null) return component;
      return component.copy().withStyle(style -> style.withClickEvent(event));
    } catch (RuntimeException err) {
      return component;
    }
  }

  /**
   * Builds a {@code ClickEvent} without a compile-time reference to either shape: the legacy
   * {@code ClickEvent(Action, String)} (1.16-1.21.1) or the nested-record shape
   * ({@code ClickEvent.SuggestCommand/RunCommand}, 1.21.2+). The G2 jar is compiled against
   * 1.21.11 and must run on every version of its range - a direct reference to the nested
   * class crashes 1.21.1 servers at class load (NoClassDefFoundError).
   */
  private static net.minecraft.network.chat.ClickEvent buildClickEvent(
      boolean suggests, String command) {
    // Modern nested-record shape (1.21.2+).
    try {
      Class<?> nested =
          Class.forName(
              suggests
                  ? "net.minecraft.network.chat.ClickEvent$SuggestCommand"
                  : "net.minecraft.network.chat.ClickEvent$RunCommand");
      Object event = nested.getConstructor(String.class).newInstance(command);
      if (event instanceof net.minecraft.network.chat.ClickEvent ce) return ce;
    } catch (ReflectiveOperationException | RuntimeException ignored) {
      // fall through to the legacy shape
    }
    // Legacy shape (1.16-1.21.1): new ClickEvent(Action.SUGGEST_COMMAND/RUN_COMMAND, command).
    try {
      Class<?> eventClass = Class.forName("net.minecraft.network.chat.ClickEvent");
      Class<?> actionClass = Class.forName("net.minecraft.network.chat.ClickEvent$Action");
      @SuppressWarnings({"unchecked", "rawtypes"})
      Object action =
          Enum.valueOf(
              (Class) actionClass, suggests ? "SUGGEST_COMMAND" : "RUN_COMMAND");
      Object event = eventClass.getConstructor(actionClass, String.class).newInstance(action, command);
      if (event instanceof net.minecraft.network.chat.ClickEvent ce) return ce;
    } catch (ReflectiveOperationException | RuntimeException ignored) {
      // click events unavailable
    }
    return null;
  }

/**
   * Serializes a CompoundTag to a base64 string (stream-based NbtIo - stable on every
   * era and loader; the entity/Path overloads differ per loader on modern versions).
   */
  public static String compoundTagToBase64(net.minecraft.nbt.CompoundTag tag) {
    try {
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      net.minecraft.nbt.NbtIo.writeCompressed(tag, out);
      return java.util.Base64.getEncoder().encodeToString(out.toByteArray());
    } catch (Exception err) {
      return null;
    }
  }

  /** Deserializes a base64 string back into a CompoundTag (or null on failure). */
  public static net.minecraft.nbt.CompoundTag compoundTagFromBase64(String data) {
    try {
      java.io.ByteArrayInputStream in =
          new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(data));
      /*? if < 1.20.2 {*/
      /*return net.minecraft.nbt.NbtIo.readCompressed(in);
      *//*?} else {*/
      // NbtAccounter does not exist before 1.20.2 and the single-arg readCompressed was
      // removed on 1.21.2+ - both shapes are resolved reflectively so the G2 jar runs on
      // every version of its range without loading either missing class.
      try {
        Class<?> accounterClass = Class.forName("net.minecraft.nbt.NbtAccounter");
        Object accounter = accounterClass.getMethod("unlimitedHeap").invoke(null);
        Object tag =
            net.minecraft.nbt.NbtIo.class
                .getMethod("readCompressed", java.io.InputStream.class, accounterClass)
                .invoke(null, in, accounter);
        if (tag instanceof net.minecraft.nbt.CompoundTag compound) return compound;
      } catch (Throwable ignored) {
        // 1.19.0-1.20.1: no accounter - single-arg readCompressed(InputStream)
        in.reset();
      }
      try {
        Object tag =
            net.minecraft.nbt.NbtIo.class
                .getMethod("readCompressed", java.io.InputStream.class)
                .invoke(null, in);
        if (tag instanceof net.minecraft.nbt.CompoundTag compound) return compound;
      } catch (Throwable ignored) {
        return null;
      }
      return null;
      /*?}*/
    } catch (Throwable err) {
      return null;
    }
  }

  /** Serializes an ItemStack to a base64 string (for the crash-safe limbo snapshot). */
  public static String itemStackToBase64(net.minecraft.world.item.ItemStack stack) {
    if (stack == null || stack.isEmpty()) return "";
    try {
      // Legacy shape (1.16-1.21.1): stack.save(tag)
      net.minecraft.nbt.CompoundTag tag =
          (net.minecraft.nbt.CompoundTag)
              net.minecraft.world.item.ItemStack.class
                  .getMethod("save", net.minecraft.nbt.CompoundTag.class)
                  .invoke(stack, new net.minecraft.nbt.CompoundTag());
      return compoundTagToBase64(tag);
    } catch (Throwable ignored) {
      // 1.21.2+ / 26.x: ItemStack.CODEC via NbtOps - resolved reflectively (neither class
      // exists before 1.20.5, so the G2 jar cannot reference them directly).
    }
    try {
      net.minecraft.nbt.CompoundTag tag = codecTag(stack);
      if (tag != null) return compoundTagToBase64(tag);
      return "";
    } catch (Throwable err) {
      return "";
    }
  }

  /** Finds a method by name and parameter count (generic-erased Codec methods). */
  private static java.lang.reflect.Method findMethod(Class<?> type, String name, int arity) {
    for (java.lang.reflect.Method m : type.getMethods())
      if (m.getName().equals(name) && m.getParameterCount() == arity) return m;
    return null;
  }

  /** Reflective {@code X.CODEC.encodeStart(NbtOps.INSTANCE, value).result()} -> CompoundTag. */
  private static net.minecraft.nbt.CompoundTag codecTag(Object value) {
    try {
      Class<?> opsClass = Class.forName("net.minecraft.nbt.NbtOps");
      Object ops = opsClass.getField("INSTANCE").get(null);
      Object codec = value.getClass().getField("CODEC").get(null);
      java.lang.reflect.Method encode = findMethod(codec.getClass(), "encodeStart", 2);
      if (encode == null) return null;
      Object dataResult = encode.invoke(codec, ops, value);
      Object optional = dataResult.getClass().getMethod("result").invoke(dataResult);
      if (optional instanceof java.util.Optional<?> opt && opt.isPresent()) {
        Object tag = opt.get();
        if (tag instanceof net.minecraft.nbt.CompoundTag compound) return compound;
      }
      return null;
    } catch (Throwable ignored) {
      return null;
    }
  }

  /** Deserializes an ItemStack from a base64 string (empty stack on failure). */
  public static net.minecraft.world.item.ItemStack itemStackFromBase64(String data) {
    if (data == null || data.isBlank()) return net.minecraft.world.item.ItemStack.EMPTY;
    net.minecraft.nbt.CompoundTag tag = compoundTagFromBase64(data);
    if (tag == null) return net.minecraft.world.item.ItemStack.EMPTY;
    try {
      // Legacy shape (1.16-1.21.1): ItemStack.of(tag)
      return (net.minecraft.world.item.ItemStack)
          net.minecraft.world.item.ItemStack.class
              .getMethod("of", net.minecraft.nbt.CompoundTag.class)
              .invoke(null, tag);
    } catch (Throwable ignored) {
      // 1.21.2+ / 26.x: ItemStack.CODEC.parse(NbtOps.INSTANCE, tag).result()
    }
    try {
      Class<?> opsClass = Class.forName("net.minecraft.nbt.NbtOps");
      Object ops = opsClass.getField("INSTANCE").get(null);
      Object codec = net.minecraft.world.item.ItemStack.class.getField("CODEC").get(null);
      java.lang.reflect.Method parse = findMethod(codec.getClass(), "parse", 2);
      if (parse == null) return net.minecraft.world.item.ItemStack.EMPTY;
      Object dataResult = parse.invoke(codec, ops, tag);
      Object optional = dataResult.getClass().getMethod("result").invoke(dataResult);
      if (optional instanceof java.util.Optional<?> opt && opt.isPresent()) {
        Object stack = opt.get();
        if (stack instanceof net.minecraft.world.item.ItemStack itemStack) return itemStack;
      }
      return net.minecraft.world.item.ItemStack.EMPTY;
    } catch (Throwable err) {
      return net.minecraft.world.item.ItemStack.EMPTY;
    }
  }

  /** Serializes a status effect to a base64 string. */
  public static String effectToBase64(net.minecraft.world.effect.MobEffectInstance effect) {
    if (effect == null) return "";
    try {
      // Legacy shape (1.16-1.21.1): effect.save(new CompoundTag())
      net.minecraft.nbt.CompoundTag tag =
          (net.minecraft.nbt.CompoundTag)
              net.minecraft.world.effect.MobEffectInstance.class
                  .getMethod("save", net.minecraft.nbt.CompoundTag.class)
                  .invoke(effect, new net.minecraft.nbt.CompoundTag());
      return compoundTagToBase64(tag);
    } catch (Throwable ignored) {
      // 1.21.2+ / 26.x: MobEffectInstance.CODEC via NbtOps - reflective
    }
    try {
      net.minecraft.nbt.CompoundTag tag = codecTag(effect);
      if (tag != null) return compoundTagToBase64(tag);
      return "";
    } catch (Throwable err) {
      return "";
    }
  }

  /** Deserializes a status effect from a base64 string (null on failure). */
  public static net.minecraft.world.effect.MobEffectInstance effectFromBase64(String data) {
    if (data == null || data.isBlank()) return null;
    net.minecraft.nbt.CompoundTag tag = compoundTagFromBase64(data);
    if (tag == null) return null;
    try {
      // Legacy shape (1.16-1.21.1): MobEffectInstance.load(tag)
      return (net.minecraft.world.effect.MobEffectInstance)
          net.minecraft.world.effect.MobEffectInstance.class
              .getMethod("load", net.minecraft.nbt.CompoundTag.class)
              .invoke(null, tag);
    } catch (Throwable ignored) {
      // 1.21.2+ / 26.x: MobEffectInstance.CODEC.parse(NbtOps.INSTANCE, tag).result()
    }
    try {
      Class<?> opsClass = Class.forName("net.minecraft.nbt.NbtOps");
      Object ops = opsClass.getField("INSTANCE").get(null);
      Object codec = net.minecraft.world.effect.MobEffectInstance.class.getField("CODEC").get(null);
      java.lang.reflect.Method parse = findMethod(codec.getClass(), "parse", 2);
      if (parse == null) return null;
      Object dataResult = parse.invoke(codec, ops, tag);
      Object optional = dataResult.getClass().getMethod("result").invoke(dataResult);
      if (optional instanceof java.util.Optional<?> opt && opt.isPresent()) {
        Object fx = opt.get();
        if (fx instanceof net.minecraft.world.effect.MobEffectInstance instance) return instance;
      }
      return null;
    } catch (Throwable err) {
      return null;
    }
  }

  /** Parses a dimension key from its {@code namespace:path} string (fully reflective - the
   *  Identifier/ResourceLocation shape changed at 1.21.11, so the G2 jar cannot reference
   *  either shape directly). */
  @SuppressWarnings("unchecked")
  public static net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> parseDimensionKey(
      String location) {
    if (location == null || location.isBlank()) return null;
    try {
      Object id = tryParseDimension(location);
      if (id == null) return null;
      Object key = worldKey(id);
      if (key instanceof net.minecraft.resources.ResourceKey) {
        return (net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>) key;
      }
      return null;
    } catch (Throwable err) {
      return null;
    }
  }

  /** Resolves the world registry key object (net.minecraft.registry.RegistryKeys.WORLD or the
   *  1.19.2-and-older util.registry.Registry.WORLD_KEY). */
  private static Object dimensionRegistry() {
    try {
      Class<?> registryKeys = Class.forName("net.minecraft.registry.RegistryKeys");
      return registryKeys.getField("WORLD").get(null);
    } catch (ReflectiveOperationException ignored) {
      try {
        Class<?> registry = Class.forName("net.minecraft.util.registry.Registry");
        return registry.getField("WORLD_KEY").get(null);
      } catch (ReflectiveOperationException ignored2) {
        return null;
      }
    }
  }

  /**
   * Parses a dimension location string without referencing either {@code ResourceLocation} or
   * {@code Identifier} directly (the rename happened at 1.21.11; the G2 jar must run on both).
   * Returns an opaque {@link Object} usable with {@link #worldKey(Object)}.
   */
  public static Object tryParseDimension(String raw) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return Class.forName("net.minecraft.resources.Identifier")
          .getMethod("tryParse", String.class)
          .invoke(null, raw);
    } catch (ReflectiveOperationException ignored) {
      // 1.16-1.21.1: ResourceLocation
    }
    try {
      return Class.forName("net.minecraft.resources.ResourceLocation")
          .getMethod("tryParse", String.class)
          .invoke(null, raw);
    } catch (ReflectiveOperationException ignored) {
      return null;
    }
  }

  /** The player inventory (direct call - {@code Player.getInventory} in every era). */
  public static net.minecraft.world.entity.player.Inventory getInventory(
      net.minecraft.world.entity.player.Player player) {
    try {
      return player.getInventory();
    } catch (RuntimeException err) {
      return null;
    }
  }

  /**
   * Force-closes the player's open container screen on the client (used to lock the
   * inventory in the lobby - the client has no "open" packet for its own inventory, so
   * the screen is closed by sending a container-close packet instead).
   */
  public static void forceCloseInventory(net.minecraft.server.level.ServerPlayer player) {
    try {
      player.connection.send(
          new net.minecraft.network.protocol.game.ClientboundContainerClosePacket(
              player.containerMenu.containerId));
    } catch (RuntimeException ignored) {
      // best-effort - the click guards already make the inventory unusable
    }
  }

  /** The player abilities (direct call - {@code Player.getAbilities} in every era). */
  public static net.minecraft.world.entity.player.Abilities getAbilities(
      net.minecraft.world.entity.player.Player player) {
    try {
      return player.getAbilities();
    } catch (RuntimeException err) {
      return null;
    }
  }

  /**
   * Force-stops an active elytra glide. The fall-flying shared flag index (7) is identical in
   * every mapping era; the setter name differs ({@code setSharedFlag} on 1.19.3+/Mojang names,
   * {@code setFlag} on older Yarn names) so both are tried.
   *
   * @param entity the living entity gliding (or about to glide)
   * @return {@code true} when the flag could be cleared
   */
  public static boolean stopFallFlying(net.minecraft.world.entity.LivingEntity entity) {
    try {
      Class<?> entityClass = net.minecraft.world.entity.Entity.class;
      try {
        entityClass.getMethod("setSharedFlag", int.class, boolean.class).invoke(entity, 7, false);
      } catch (ReflectiveOperationException e) {
        entityClass.getMethod("setFlag", int.class, boolean.class).invoke(entity, 7, false);
      }
      return true;
    } catch (ReflectiveOperationException | RuntimeException ignored) {
      return false;
    }
  }

  /** Changes the player game mode (direct call - {@code ServerPlayer.setGameMode} in every era). */
  public static void changeGameMode(ServerPlayer player, net.minecraft.world.level.GameType mode) {
    try {
      player.setGameMode(mode);
    } catch (RuntimeException ignored) {
      // could not change game mode
    }
  }

  /** Whether the player is currently an operator. Null-safe: a mid-join player has no
   *  resolvable server yet - treat them as non-operator instead of crashing the join. */
  public static boolean isOperator(MinecraftServer server, ServerPlayer player) {
    if (server == null || player == null) return false;
    try {
      Class<?> configEntry = Class.forName("net.minecraft.server.PlayerManager$PlayerConfigEntry");
      Method m = server.getPlayerList().getClass().getMethod("isOperator", configEntry);
      Object entry = player.getClass().getMethod("getPlayerConfigEntry").invoke(player);
      return (Boolean) m.invoke(server.getPlayerList(), entry);
    } catch (ReflectiveOperationException e) {
      try {
        Method m =
            server.getPlayerList().getClass().getMethod(
                "isOperator", com.mojang.authlib.GameProfile.class);
        return (Boolean) m.invoke(server.getPlayerList(), player.getGameProfile());
      } catch (ReflectiveOperationException ignored) {
        return false;
      }
    }
  }

  /** Reads the configured operator permission level for the player. Null-safe: returns 0
   *  when the server is not resolvable (mid-join). */
  public static int getOperatorLevel(MinecraftServer server, ServerPlayer player) {
    if (server == null || player == null) return 0;
    try {
      Class<?> configEntry = Class.forName("net.minecraft.server.PlayerManager$PlayerConfigEntry");
      Method m = server.getPlayerList().getClass().getMethod("getPermissionLevel", configEntry);
      Object entry = player.getClass().getMethod("getPlayerConfigEntry").invoke(player);
      return (Integer) m.invoke(server.getPlayerList(), entry);
    } catch (ReflectiveOperationException e) {
      return 0;
    }
  }

  /**
   * The loader platform + version ("Fabric Loader x.y.z" / "NeoForge x" / "Forge x").
   * Brand detection is robust (class presence); the version is best-effort (the versionInfo
   * API differs between NeoForge 20.x and 21.x) - never "unknown" when the loader class is
   * actually there.
   */
  public static String getLoaderVersion() {
    /*? if fabric {*/
    try {
      Class<?> loaderClass = Class.forName("net.fabricmc.loader.api.FabricLoader");
      Object loader = loaderClass.getMethod("getInstance").invoke(null);
      try {
        Object container =
            loaderClass.getMethod("getModContainer", String.class)
                .invoke(loader, "fabricloader");
        if (container instanceof Optional<?> opt && opt.isPresent()) {
          Object metadata = opt.get().getClass().getMethod("getMetadata").invoke(opt.get());
          Object version = metadata.getClass().getMethod("getVersion").invoke(metadata);
          String v = (String) version.getClass().getMethod("getFriendlyString").invoke(version);
          if (v != null && !v.isBlank()) return "Fabric Loader " + v;
        }
      } catch (Throwable ignored) {
        // container metadata unavailable - report the platform anyway
      }
      return "Fabric Loader";
    } catch (Throwable ignored) {
      // not Fabric - fall through to the FML checks below
    }
    /*?}*/
    // NeoForge.
    try {
      Class<?> fml = Class.forName("net.neoforged.fml.loading.FMLLoader");
      String version = loaderVersionVia(fml);
      return version != null ? "NeoForge " + version : "NeoForge";
    } catch (Throwable ignored) {
      // not NeoForge
    }
    // Forge.
    try {
      Class<?> fml = Class.forName("net.minecraftforge.fml.loading.FMLLoader");
      String version = loaderVersionVia(fml);
      return version != null ? "Forge " + version : "Forge";
    } catch (Throwable ignored) {
      return "unknown";
    }
  }

  /** Best-effort loader version from a FMLLoader class (versionInfo API differs per version). */
  private static String loaderVersionVia(Class<?> fmlClass) {
    try {
      Object versionInfo = null;
      for (String accessor : new String[] {"versionInfo", "getVersionInfo"}) {
        try {
          versionInfo = fmlClass.getMethod(accessor).invoke(null);
          if (versionInfo != null) break;
        } catch (ReflectiveOperationException ignored) {
          // try the next accessor name
        }
      }
      if (versionInfo != null) {
        for (String m : new String[] {"getForgeVersion", "getLoaderVersion", "getVersion",
                                      "neoForgeVersion", "forgeVersion"}) {
          try {
            Object v = versionInfo.getClass().getMethod(m).invoke(versionInfo);
            if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v);
          } catch (ReflectiveOperationException ignored) {
            // try the next name
          }
        }
      }
    } catch (Throwable ignored) {
      // version info unavailable
    }
    return null;
  }

  /**
   * The game's config directory (Fabric exposes it through the loader; Forge/NeoForge use
   * FMLPaths). Falls back to the plain "config" directory next to the server.
   */
  public static java.nio.file.Path getConfigDir() {
    /*? if fabric {*/
    try {
      var loader = net.fabricmc.loader.api.FabricLoader.getInstance();
      Object dir = loader.getClass().getMethod("getConfigDir").invoke(loader);
      if (dir instanceof java.nio.file.Path path) return path;
    } catch (ReflectiveOperationException | RuntimeException ignored) {
      // not Fabric
    }
    /*?}*/
    try {
      Class<?> paths = Class.forName("net.neoforged.fml.loading.FMLPaths");
      Object dir = paths.getField("CONFIGDIR").get(null);
      if (dir instanceof java.nio.file.Path path) return path;
    } catch (ReflectiveOperationException | RuntimeException ignored) {
      // not NeoForge
    }
    try {
      Class<?> paths = Class.forName("net.minecraftforge.fml.loading.FMLPaths");
      Object dir = paths.getField("CONFIGDIR").get(null);
      if (dir instanceof java.nio.file.Path path) return path;
    } catch (ReflectiveOperationException | RuntimeException ignored) {
      // not Forge
    }
    return java.nio.file.Paths.get("config");
  }

  /**
   * Best-effort raw payload read from a custom-payload packet across every version:
   * ByteBuf via getData() on 1.16 - 1.20.4, the record payload (payload() -> data()) on
   * 1.20.5+. Returns null when the payload cannot be read.
   */
  public static String readCustomPayloadData(Object packet) {
    try {
      Object data = packet.getClass().getMethod("getData").invoke(packet);
      if (data instanceof io.netty.buffer.ByteBuf buf) {
        String text = buf.toString(java.nio.charset.StandardCharsets.UTF_8);
        return text.replace("\u0000", "").trim();
      }
    } catch (ReflectiveOperationException ignored) {
      // 1.20.5+ shape below
    }
    try {
      Object payload = packet.getClass().getMethod("payload").invoke(packet);
      if (payload == null) return null;
      try {
        Object data = payload.getClass().getMethod("data").invoke(payload);
        if (data instanceof io.netty.buffer.ByteBuf buf) {
          String text = buf.toString(java.nio.charset.StandardCharsets.UTF_8);
          return text.replace("\u0000", "").trim();
        }
      } catch (ReflectiveOperationException ignored) {
        // fall through to toString
      }
      String text = payload.toString();
      int eq = text.indexOf('=');
      return eq >= 0 ? text.substring(eq + 1).replace("]", "").trim() : text.trim();
    } catch (ReflectiveOperationException e) {
      return null;
    }
  }

  /** Whether a mod with the given id is loaded (works on every loader). */
  public static boolean isModLoaded(String modId) {    /*? if fabric {*/
    try {
      var loader = net.fabricmc.loader.api.FabricLoader.getInstance();
      return (Boolean) loader.getClass().getMethod("isModLoaded", String.class).invoke(loader, modId);
    } catch (ReflectiveOperationException | RuntimeException ignored) {
      // not Fabric
    }
    /*?}*/
    for (String fmlClass : new String[] {"net.neoforged.fml.loader.FMLLoader", "net.minecraftforge.fml.loading.FMLLoader"}) {
      try {
        Class<?> fml = Class.forName(fmlClass);
        Object modList = fml.getMethod("getLoadingModList").invoke(null);
        Object modFile = modList.getClass().getMethod("getModFileById", String.class).invoke(modList, modId);
        return modFile != null;
      } catch (ReflectiveOperationException ignored) {
        // try the next loader
      }
    }
    return false;
  }

  /** The raw game version string (API renamed across loader versions). */
  public static String getGameVersion() {
    /*? if fabric {*/
    try {
      var loader = net.fabricmc.loader.api.FabricLoader.getInstance();
      try {
        return (String) loader.getClass().getMethod("getRawGameVersion").invoke(loader);
      } catch (ReflectiveOperationException e) {
        return (String) loader.getClass().getMethod("getGameVersion").invoke(loader);
      }
    } catch (Exception e) {
      // fall through to the FML / SharedConstants below
    }
    /*?}*/
    // Forge / NeoForge first: FML's VersionInfo carries the exact Minecraft
    // version and is NOT obfuscated - unlike SharedConstants, whose method names
    // are SRG-renamed on Forge runtimes, so the mojmap reflection below can
    // never find them there (banner showed "unknown").
    String viaFml = gameVersionViaFml("net.neoforged.fml.loading.FMLLoader");
    if (viaFml != null) return viaFml;
    viaFml = gameVersionViaFml("net.minecraftforge.fml.loading.FMLLoader");
    if (viaFml != null) return viaFml;
    try {
      Class<?> shared = Class.forName("net.minecraft.SharedConstants");
      Object version = shared.getMethod("getCurrentVersion").invoke(null);
      for (String m : new String[] {"getName", "getId", "getReleaseTarget"}) {
        try {
          Object v = version.getClass().getMethod(m).invoke(version);
          if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v);
        } catch (ReflectiveOperationException ignored) {
          // try the next name
        }
      }
      return "unknown";
    } catch (ReflectiveOperationException e) {
      return "unknown";
    }
  }

  /**
   * Best-effort Minecraft version from an FML VersionInfo (non-obfuscated classes).
   * Forge exposes a static {@code getVersionInfo()}; newer NeoForge exposes a static
   * FMLLoader singleton ({@code getCurrentOrNull()}) whose instance method carries it.
   */
  private static String gameVersionViaFml(String fmlClass) {
    try {
      Class<?> fmlType = Class.forName(fmlClass);
      Object info = staticGet(fmlType, new String[] {"versionInfo", "getVersionInfo"});
      if (info == null) {
        Object loader = staticGet(fmlType, new String[] {"getCurrent", "getCurrentOrNull", "getInstance", "instance"});
        if (loader != null) info = invokeAny(loader, new String[] {"getVersionInfo", "versionInfo"});
      }
      if (info != null) {
        for (String m : new String[] {"mcVersion", "getMcVersion", "getMcAndMinecraftVersion",
                                      "getMcAndForgeVersion", "minecraftVersion"}) {
          try {
            Object v = info.getClass().getMethod(m).invoke(info);
            if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v);
          } catch (ReflectiveOperationException ignored) {
            // try the next name
          }
        }
      }
    } catch (Throwable ignored) {
      // not this loader
    }
    return null;
  }

  /** Invokes the first existing no-arg STATIC method and returns its result (null if none). */
  private static Object staticGet(Class<?> type, String[] names) {
    for (String name : names) {
      try {
        var method = type.getMethod(name);
        if (java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
          Object v = method.invoke(null);
          if (v != null) return v;
        }
      } catch (Throwable ignored) {
        // try the next name
      }
    }
    return null;
  }

  /** Invokes the first existing no-arg INSTANCE method and returns its result (null if none). */
  private static Object invokeAny(Object target, String[] names) {
    for (String name : names) {
      try {
        Object v = target.getClass().getMethod(name).invoke(target);
        if (v != null) return v;
      } catch (Throwable ignored) {
        // try the next name
      }
    }
    return null;
  }

  /** Adds a player to the vanilla whitelist (API changed across versions). */
  public static boolean addToWhitelist(MinecraftServer server, com.mojang.authlib.GameProfile profile) {
    try {
      // 1.20.5+: Whitelist.add(WhitelistEntry)
      Object whitelist = server.getPlayerList().getClass().getMethod("getWhitelist").invoke(server.getPlayerList());
      Class<?> entryClass = Class.forName("net.minecraft.server.WhitelistEntry");
      Object entry = entryClass.getConstructor(com.mojang.authlib.GameProfile.class).newInstance(profile);
      whitelist.getClass().getMethod("add", entryClass).invoke(whitelist, entry);
      return true;
    } catch (ReflectiveOperationException e) {
      try {
        // 1.16-1.20.4: PlayerManager.addToWhitelist(GameProfile)
        Method m = server.getPlayerList().getClass().getMethod("addToWhitelist", com.mojang.authlib.GameProfile.class);
        m.invoke(server.getPlayerList(), profile);
        return true;
      } catch (ReflectiveOperationException ignored) {
        return false;
      }
    }
  }

  // ------------------------------------------------------------------
  // ActionResult constants (actionResultPass / actionResultFail below)
  //
  // On 1.20.4 and older ActionResult is an enum; from 1.20.5/1.21 it is a class with nested
  // subclasses. The 1.21.11 mappings even type the constants with their nested subclasses
  // (ActionResult$Pass...), so a direct field reference compiled for 1.21.11 forces the JVM to
  // load a class that does not exist on 1.20.6 and older - the methods below fall back to the
  // mojmap names (26.x, unobfuscated) or the stable intermediary field ids (1.16 - 1.21).
  // ------------------------------------------------------------------

  /**
   * Returns {@code InteractionResult.PASS}.
   *
   * <p>{@code InteractionResult} is an ENUM up to 1.21.9 and a class/interface with
   * nested-typed constants from 1.21.10+. The nested constant CLASSES (InteractionResult$Pass
   * etc.) do not exist on 1.21.1, so any direct field reference compiled against 1.21.11 would
   * crash older servers at class load - the constant is resolved REFLECTIVELY by name instead.
   * A null "pass" would cancel the attack in the fabric callback - it is only returned when
   * both resolutions fail.
   */
  public static net.minecraft.world.InteractionResult actionResultPass() {
    return actionResultConstant("PASS");
  }

  /** Returns {@code InteractionResult.FAIL} (reflective - see {@link #actionResultPass}). */
  public static net.minecraft.world.InteractionResult actionResultFail() {
    return actionResultConstant("FAIL");
  }

  /** Resolves an InteractionResult constant by name without referencing the nested class. */
  private static net.minecraft.world.InteractionResult actionResultConstant(String name) {
    // 1.21.10+ / 26.x: interface with nested-typed constants - iterate the declared fields.
    try {
      for (java.lang.reflect.Field f : net.minecraft.world.InteractionResult.class.getFields())
        if (f.getName().equals(name)) {
          Object value = f.get(null);
          if (value instanceof net.minecraft.world.InteractionResult result) return result;
        }
    } catch (Throwable ignored) {
      // not the interface shape - enum below
    }
    // 1.16 - 1.21.9: enum constant
    try {
      @SuppressWarnings({"unchecked", "rawtypes"})
      net.minecraft.world.InteractionResult result =
          (net.minecraft.world.InteractionResult)
              (Object) Enum.valueOf((Class) net.minecraft.world.InteractionResult.class, name);
      return result;
    } catch (Throwable ignored) {
      return null;
    }
  }
}

