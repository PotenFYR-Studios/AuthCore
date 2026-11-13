package in.potenfyr.authcore.compat;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import in.potenfyr.authcore.AuthCoreServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
  public static ServerPlayer getPlayerByUuid(UUID uuid) {
    if (uuid == null) return null;
    MinecraftServer server = AuthCoreServer.server;
    if (server == null) return null;
    return server.getPlayerList().getPlayer(uuid);
  }

  /** Per-listener-class Connection field, found by TYPE (field names differ in every mapping era). */
  private static final java.util.Map<Class<?>, java.util.Optional<java.lang.reflect.Field>>
      CONNECTION_FIELDS = new java.util.concurrent.ConcurrentHashMap<>();

  /**
   * Resolves the underlying network {@link net.minecraft.network.Connection} of a packet listener
   * (login, configuration or play), or returns the argument itself when none is found.
   *
   * <p>Type-based and hierarchy-aware: the field is called {@code connection} only in Mojang names
   * (intermediary/SRG rename it), and since 1.20.2 it lives in the {@code
   * ServerCommonPacketListenerImpl} SUPERCLASS - the old name-then-declared-fields lookup missed it
   * on every play listener and silently returned the listener instead. The Connection object is the
   * one identity that survives the whole login -> configuration -> play sequence, which is what the
   * premium-verification binding relies on.
   */
  public static Object getConnection(Object handler) {
    if (handler == null) return null;
    if (handler instanceof net.minecraft.network.Connection) return handler;
    java.util.Optional<java.lang.reflect.Field> field =
        CONNECTION_FIELDS.computeIfAbsent(handler.getClass(), Compat::findConnectionField);
    if (field.isPresent()) {
      try {
        Object conn = field.get().get(handler);
        if (conn != null) return conn;
      } catch (ReflectiveOperationException | RuntimeException ignored) {
        // fall through
      }
    }
    return handler;
  }

  /** The network Connection of a listener, or null when it cannot be resolved. */
  public static net.minecraft.network.Connection networkConnection(Object handler) {
    Object conn = getConnection(handler);
    return conn instanceof net.minecraft.network.Connection c ? c : null;
  }

  private static java.util.Optional<java.lang.reflect.Field> findConnectionField(Class<?> type) {
    for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass())
      for (java.lang.reflect.Field f : c.getDeclaredFields()) {
        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
        if (!net.minecraft.network.Connection.class.isAssignableFrom(f.getType())) continue;
        try {
          f.setAccessible(true);
          return java.util.Optional.of(f);
        } catch (RuntimeException inaccessible) {
          // keep looking
        }
      }
    return java.util.Optional.empty();
  }

  /** Resolves the username associated with a packet listener or connection (best-effort). */
  public static String getConnectionUsername(Object handler) {
    if (handler == null) return "unknown";
    try {
      if (handler instanceof ServerPlayer sp) {
        return sp.getName().getString();
      }
      if (handler instanceof net.minecraft.server.network.ServerGamePacketListenerImpl sgpli
          && sgpli.player != null) {
        return sgpli.player.getName().getString();
      }
      java.lang.reflect.Method m = handler.getClass().getMethod("getUserName");
      Object res = m.invoke(handler);
      if (res != null) return res.toString();
    } catch (Throwable ignored) {}
    try {
      java.lang.reflect.Method m = handler.getClass().getMethod("getOwner");
      Object res = m.invoke(handler);
      if (res != null) {
        try {
          return (String) res.getClass().getMethod("getName").invoke(res);
        } catch (Throwable t) {
          return (String) res.getClass().getMethod("name").invoke(res);
        }
      }
    } catch (Throwable ignored) {}
    return "unknown";
  }

  public static ServerPlayer sourcePlayer(Object sourceOrContext) {
    Object source = sourceOrContext;
    if (sourceOrContext instanceof com.mojang.brigadier.context.CommandContext<?> context) {
      source = context.getSource();
    }
    if (source == null) return null;
    // Direct, build-remapped call: getEntity() is the same method in every era and resolves on
    // every runtime (Mojang names, intermediary AND SRG - the name list below has no SRG ids, so
    // on Forge it never found the player and every player command read as "console").
    if (source instanceof net.minecraft.commands.CommandSourceStack stack) {
      try {
        if (stack.getEntity() instanceof ServerPlayer serverPlayer) return serverPlayer;
        return null;
      } catch (RuntimeException | LinkageError ignored) {
        // fall back to the name candidates
      }
    }
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

  /**
   * Kills an entity through the vanilla death path (loot drops, death message, statistics).
   *
   * <p>Build-remapped direct calls: {@code kill()} on 1.16-1.18, {@code kill(ServerLevel)} on
   * 1.21.2+/26.x. The previous Mojang-name reflection only resolved on Mojang-named runtimes
   * (NeoForge, 26.x) and silently did nothing on Fabric (intermediary) and Forge (SRG) - the same
   * config killed players on one loader and not on another. The 1.19-1.21.1 runtimes inside the
   * 1.21.11 range jar use {@code setHealth(0) + die(genericKill)}, whose descriptors are stable.
   *
   * @return true when the entity was killed through a real death path; false when this runtime
   *     offers none (the caller then leaves the entity alone - a half-dead player without loot drops
   *     would lose the items entirely on respawn)
   */
  public static boolean kill(LivingEntity entity) {
    if (entity == null) return false;
    /*? if < 1.21.2 {*/
    /*try {
      entity.kill();
      return true;
    } catch (RuntimeException | LinkageError err) {
      return false;
    }
    *//*?} else {*/
    try {
      if (entityLevel(entity) instanceof ServerLevel level) {
        entity.kill(level);
        return true;
      }
    } catch (RuntimeException | LinkageError olderRuntime) {
      // 1.19-1.21.1 inside the range jar: no kill(ServerLevel) - fall through
    }
    try {
      net.minecraft.world.damagesource.DamageSource source = entity.damageSources().genericKill();
      entity.setHealth(0.0F);
      entity.die(source);
      return true;
    } catch (RuntimeException | LinkageError unsupported) {
      return false;
    }
    /*?}*/
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
    /*? if < 1.19.4 {*/
    /*// Classic shape (1.16-1.19.3): (double, double, double, float, float, Set<Relative>, int, boolean)
    try {
      player.connection.send(
          new net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket(
              x, y, z, yaw, pitch, java.util.Set.of(), teleportId(), false));
    } catch (RuntimeException ignored) {
      // client correction is best-effort
    }
    *//*?} else {*/
    // Direct, build-remapped record shape (1.21.2+). The previous Class.forName lookup of
    // "net.minecraft...ClientboundPlayerPositionPacket" only matched Mojang-named runtimes, so on
    // every Fabric 1.21.2-1.21.11 server (intermediary) the limbo never sent its client correction.
    /*? if >= 1.21.2 {*/
    try {
      ModernPosition.send(player, x, y, z, yaw, pitch, teleportId());
      return;
    } catch (RuntimeException err) {
      return; // best-effort - the server-side position stays authoritative
    } catch (LinkageError olderRuntime) {
      // 1.19-1.21.1 inside the 1.21.11 build: classic constructor, located by shape below
    }
    /*?}*/
    // Classic shape (double x, y, z, float yaw, pitch, Set relatives, int id [, boolean dismount]):
    // the trailing boolean was dropped at 1.19.4. Matched by parameter types, never by name.
    try {
      for (java.lang.reflect.Constructor<?> ctor :
          net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket.class.getConstructors()) {
        Class<?>[] p = ctor.getParameterTypes();
        if ((p.length != 7 && p.length != 8)
            || p[0] != double.class || p[1] != double.class || p[2] != double.class
            || p[3] != float.class || p[4] != float.class
            || p[5] != java.util.Set.class || p[6] != int.class
            || (p.length == 8 && p[7] != boolean.class)) continue;
        Object packet =
            p.length == 7
                ? ctor.newInstance(x, y, z, yaw, pitch, java.util.Set.of(), teleportId())
                : ctor.newInstance(x, y, z, yaw, pitch, java.util.Set.of(), teleportId(), false);
        player.connection.send((net.minecraft.network.protocol.Packet<?>) packet);
        return;
      }
    } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
      // client correction is best-effort - the server-side position is still authoritative
    }
    /*?}*/
  }

  /*? if >= 1.21.2 {*/
  /** Isolated so an older runtime fails with a catchable LinkageError at the call, not at load. */
  private static final class ModernPosition {
    private ModernPosition() {}

    static void send(
        ServerPlayer player, double x, double y, double z, float yaw, float pitch, int id) {
      player.connection.send(
          new net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket(
              id,
              new net.minecraft.world.entity.PositionMoveRotation(
                  new net.minecraft.world.phys.Vec3(x, y, z),
                  net.minecraft.world.phys.Vec3.ZERO,
                  yaw,
                  pitch),
              java.util.Set.of()));
    }
  }
  /*?}*/

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
    if (player == null) return 0;
    try {
      return player.getTicksFrozen(); // 1.17+ (powder snow); remapped per runtime
    } catch (RuntimeException | LinkageError legacy) {
      return 0;
    }
  }

  /** Sets the frozen ticks (no-op on versions without the API). */
  public static void setFrozenTicks(ServerPlayer player, int ticks) {
    if (player == null) return;
    try {
      player.setTicksFrozen(ticks);
    } catch (RuntimeException | LinkageError legacy) {
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
    if (player == null) return;
    // The common case (reset to 0) is a direct, remapped call in every era from 1.17 on.
    if (value == 0.0) {
      try {
        player.resetFallDistance();
        return;
      } catch (RuntimeException | LinkageError legacy) {
        // 1.16 - field write below
      }
    }
    // Field names: Mojang / SRG / intermediary. The intermediary id used to be wrong
    // (field_23362 instead of field_6017), so on every Fabric 1.16-1.21 runtime the restore never
    // cleared the fall distance at all (issue #11).
    java.lang.reflect.Field field = entityField(player.getClass(), "fallDistance", "f_19789_", "field_6017");
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

  /**
   * Reads the entity fall distance on every supported version (0 when the field is unreadable).
   *
   * <p>Used by the post-restore fall guard and by the death diagnostic: the fall distance at the
   * moment of a death is the fastest way to tell a restore-caused fall from any other damage.
   */
  public static double getFallDistance(net.minecraft.server.level.ServerPlayer player) {
    if (player == null) return 0.0;
    java.lang.reflect.Field field =
        entityField(player.getClass(), "fallDistance", "f_19789_", "field_6017");
    if (field == null) return 0.0;
    try {
      Class<?> type = field.getType();
      if (type == double.class) return field.getDouble(player);
      if (type == float.class) return field.getFloat(player);
      if (type == int.class) return field.getInt(player);
      Object value = field.get(player);
      return value instanceof Number number ? number.doubleValue() : 0.0;
    } catch (ReflectiveOperationException | RuntimeException ignored) {
      return 0.0;
    }
  }

  // ------------------------------------------------------------------
  // Entity invulnerability flag (the limbo damage shield).
  //
  // Minecraft 26.3 REMOVED Entity#setInvulnerable(boolean) in favour of
  // Entity#setPermanentlyInvulnerable(boolean) (verified on the 26.3 official-mappings
  // jar; the isInvulnerable() GETTER was kept as the combined read). The G3 jar is ONE
  // artifact compiled against 26.3 but released for the whole OPEN-ENDED >=26.1 range,
  // so a compile-time Stonecutter switch always bakes the 26.3 name into the bytecode -
  // 26.1/26.2 servers then crashed with NoSuchMethodError inside the join-time limbo
  // lock (issue #10). Within the 26.x era (unobfuscated, runtime names = Mojang names)
  // the rename must therefore be dispatched at RUNTIME. The same single-jar-per-range
  // pattern already used by setFallDistance / runCommand / playerLevel.
  // ------------------------------------------------------------------

  /**
   * Resolves the invulnerability setter available on the given entity class
   * ({@code setPermanentlyInvulnerable(boolean)} on 26.3+,
   * {@code setInvulnerable(boolean)} on 26.2 and older), or {@code null} when neither
   * exists. Public so tests can exercise the era dispatch against synthetic shapes.
   */
  public static Method invulnerableSetter(Class<?> entityType) {
    if (entityType == null) return null;
    for (String name : new String[] {"setPermanentlyInvulnerable", "setInvulnerable"}) {
      try {
        return entityType.getMethod(name, boolean.class);
      } catch (ReflectiveOperationException ignored) {
        // try the next era's name
      }
    }
    return null;
  }

  /**
   * Resolves the invulnerability getter available on the given entity class, or
   * {@code null} when neither exists. 26.3 kept {@code isInvulnerable()} (the combined
   * temporary-or-permanent read) alongside the new {@code isPermanentlyInvulnerable()};
   * the permanent flag is what the limbo shield toggles, so it is matched FIRST - on
   * 26.2 and older only {@code isInvulnerable()} exists and that single flag is both
   * the shield and the read.
   */
  public static Method invulnerableGetter(Class<?> entityType) {
    if (entityType == null) return null;
    for (String name : new String[] {"isPermanentlyInvulnerable", "isInvulnerable"}) {
      try {
        return entityType.getMethod(name);
      } catch (ReflectiveOperationException ignored) {
        // try the next era's name
      }
    }
    return null;
  }

  /**
   * Toggles the player's invulnerability flag on every supported version - the limbo
   * damage shield. Never throws: on an unknown future shape the flag is left untouched
   * and the remaining limbo protections (adventure mode, attack/damage guards, health
   * restore) stay in effect.
   */
  public static void setInvulnerable(ServerPlayer player, boolean invulnerable) {
    if (player == null) return;
    Method setter = invulnerableSetter(Entity.class);
    if (setter == null) {
      AuthCoreServer.LOGGER.debug(
          false,
          "No invulnerability setter on this Minecraft version - limbo damage shield "
              + "not toggled (adventure-mode and attack guards remain active).");
      return;
    }
    try {
      setter.invoke(player, invulnerable);
    } catch (ReflectiveOperationException | RuntimeException err) {
      AuthCoreServer.LOGGER.debug(
          false, "Failed to toggle the invulnerability flag: {}", String.valueOf(err));
    }
  }

  /** Reads the player's invulnerability flag on every supported version (false when unreadable). */
  public static boolean isInvulnerable(ServerPlayer player) {
    if (player == null) return false;
    try {
      Method getter = invulnerableGetter(Entity.class);
      if (getter == null) return false;
      return getter.invoke(player) instanceof Boolean flag && flag;
    } catch (ReflectiveOperationException | RuntimeException err) {
      return false;
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

  /**
   * Whether the given entity is "mountable" (horses, camels, pigs, striders, boats, minecarts...).
   *
   * <p>The previous check matched YARN class names ({@code AbstractHorseEntity}, {@code
   * CamelEntity}...) - those never exist at runtime, and on intermediary even "Boat" is {@code
   * class_1690}, so the allow-mountable settings never applied. {@code PlayerRideable} sits in the
   * same package in every era; vehicles are matched by their (remapped) base class, with a
   * simple-name fallback for Mojang-named runtimes whose vehicle packages moved (1.21.11).
   */
  public static boolean isMountable(Entity entity) {
    if (entity == null) return false;
    try {
      if (entity instanceof net.minecraft.world.entity.PlayerRideable) return true;
    } catch (LinkageError ignored) {
      // fall through
    }
    try {
      /*? if < 1.20.3 {*/
      /*if (entity instanceof net.minecraft.world.entity.vehicle.Boat
          || entity instanceof net.minecraft.world.entity.vehicle.AbstractMinecart) return true;
      *//*?} else {*/
      if (entity instanceof net.minecraft.world.entity.vehicle.VehicleEntity) return true;
      /*?}*/
    } catch (LinkageError olderRuntime) {
      // vehicle base class absent on this runtime - name fallback below
    }
    for (Class<?> c = entity.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
      String name = c.getSimpleName();
      if (name.equals("Boat")
          || name.equals("AbstractBoat")
          || name.equals("AbstractMinecart")
          || name.equals("VehicleEntity")) return true;
    }
    return false;
  }

  /** Whether the given block is a dripleaf (only exists on 1.17+). */
  public static boolean isDripleaf(net.minecraft.world.level.block.state.BlockState state) {
    String name = state.getBlock().getClass().getName();
    return name.endsWith(".BigDripleafBlock") || name.endsWith(".SmallDripleafBlock");
  }

  /**
   * Operator-list access for the limbo's {@code safe-operators} feature.
   *
   * <p>The previous implementation looked the API up by YARN names ({@code
   * PlayerManager$PlayerConfigEntry}, {@code isOperator}, {@code addToOperators}...). Those names
   * exist only in a Yarn development environment - never at runtime (intermediary on Fabric, SRG on
   * Forge, Mojang names on NeoForge/26.x) - so every call silently failed: operators were NEVER
   * deopped while unauthenticated, on any loader or version. These are now direct, build-remapped
   * calls for the build target, with a signature-shape fallback (authlib {@code GameProfile} is
   * never remapped) for the older runtimes a range jar also covers (1.19-1.21.8 inside the 1.21.11
   * build, where the PlayerList API still takes a GameProfile instead of NameAndId).
   *
   * <p>Removal and restore edit the ops list directly and re-send the permission level, exactly as
   * vanilla's op/deop do, so AuthCore's own temporary strip is never mistaken for an admin deop by
   * {@code PlayerListOpMixin}.
   */
  public static boolean isOperator(MinecraftServer server, ServerPlayer player) {
    if (server == null || player == null) return false;
    try {
      /*? if >= 1.21.9 {*/
      return server.getPlayerList().isOp(
          new net.minecraft.server.players.NameAndId(player.getGameProfile()));
      /*?} else {*/
      /*return server.getPlayerList().isOp(player.getGameProfile());
      *//*?}*/
    } catch (RuntimeException | LinkageError otherEra) {
      return LegacyOps.entry(server, player) != null;
    }
  }

  /** The operator permission level (0 when not an operator or unreadable). */
  public static int getOperatorLevel(MinecraftServer server, ServerPlayer player) {
    if (server == null || player == null) return 0;
    try {
      /*? if >= 1.21.9 {*/
      net.minecraft.server.players.ServerOpListEntry entry =
          server.getPlayerList().getOps().get(
              new net.minecraft.server.players.NameAndId(player.getGameProfile()));
      return entry == null ? 0 : entry.permissions().level().id();
      /*?} else {*/
      /*net.minecraft.server.players.ServerOpListEntry entry =
          server.getPlayerList().getOps().get(player.getGameProfile());
      return entry == null ? 0 : entry.getLevel();
      *//*?}*/
    } catch (RuntimeException | LinkageError otherEra) {
      return LegacyOps.level(LegacyOps.entry(server, player));
    }
  }

  /** Whether the operator entry bypasses the player limit (false when unreadable). */
  public static boolean getOperatorBypass(MinecraftServer server, ServerPlayer player) {
    if (server == null || player == null) return false;
    try {
      /*? if >= 1.21.9 {*/
      net.minecraft.server.players.ServerOpListEntry entry =
          server.getPlayerList().getOps().get(
              new net.minecraft.server.players.NameAndId(player.getGameProfile()));
      /*?} else {*/
      /*net.minecraft.server.players.ServerOpListEntry entry =
          server.getPlayerList().getOps().get(player.getGameProfile());
      *//*?}*/
      return entry != null && entry.getBypassesPlayerLimit();
    } catch (RuntimeException | LinkageError otherEra) {
      Object entry = LegacyOps.entry(server, player);
      try {
        return entry instanceof net.minecraft.server.players.ServerOpListEntry e
            && e.getBypassesPlayerLimit();
      } catch (RuntimeException | LinkageError ignored) {
        return false;
      }
    }
  }

  /** Temporarily removes the player from the operator list (limbo safe-operators). */
  public static void removeFromOperators(MinecraftServer server, ServerPlayer player) {
    if (server == null || player == null) return;
    try {
      /*? if >= 1.21.9 {*/
      server.getPlayerList().getOps().remove(
          new net.minecraft.server.players.NameAndId(player.getGameProfile()));
      /*?} else {*/
      /*server.getPlayerList().getOps().remove(player.getGameProfile());
      *//*?}*/
    } catch (RuntimeException | LinkageError otherEra) {
      LegacyOps.remove(server, player);
    }
    resendPermissionLevel(server, player);
  }

  /** Restores the operator entry with the default bypass flag. */
  public static void addToOperators(MinecraftServer server, ServerPlayer player, int level) {
    addToOperators(server, player, level, false);
  }

  /** Restores the operator entry with the EXACT captured level and bypass flag. */
  public static void addToOperators(
      MinecraftServer server, ServerPlayer player, int level, boolean bypass) {
    if (server == null || player == null) return;
    int lvl = Math.max(1, Math.min(4, level <= 0 ? 4 : level));
    try {
      /*? if >= 1.21.9 {*/
      server.getPlayerList().getOps().add(
          new net.minecraft.server.players.ServerOpListEntry(
              new net.minecraft.server.players.NameAndId(player.getGameProfile()),
              net.minecraft.server.permissions.LevelBasedPermissionSet.forLevel(
                  net.minecraft.server.permissions.PermissionLevel.byId(lvl)),
              bypass));
      /*?} else {*/
      /*server.getPlayerList().getOps().add(
          new net.minecraft.server.players.ServerOpListEntry(player.getGameProfile(), lvl, bypass));
      *//*?}*/
    } catch (RuntimeException | LinkageError otherEra) {
      LegacyOps.add(server, player, lvl, bypass);
    }
    resendPermissionLevel(server, player);
  }

  /**
   * Re-sends the player's command tree after an authentication-state change.
   *
   * <p>Vanilla sends the tree ONCE, while the player is being placed in the world - before
   * AuthCore's join handler puts them into the limbo. Every AuthCore {@code requires()} predicate
   * (/register, /login, /account ...) depends on the limbo/auth state, so the client kept the
   * pre-limbo tree: /register and /login were missing (typed in red, no suggestions) for every
   * non-op player, and after login the client still showed /login but none of the /account
   * subcommands. Ops only escaped this because the safe-operators deop re-sends the tree.
   */
  public static void refreshCommandTree(ServerPlayer player) {
    if (player == null) return;
    try {
      MinecraftServer server = getServer(player);
      if (server == null) return;
      // Packets and the dispatcher belong to the server thread (callers include the web panel
      // and async integrations). On the server thread execute() runs the task immediately.
      server.execute(
          () -> {
            try {
              in.potenfyr.authcore.command.CommandTree.applyLimboFilter(
                  server.getCommands().getDispatcher());
              resendPermissionLevel(server, player);
            } catch (RuntimeException | LinkageError ignored) {
              // best-effort: typed commands still execute, only client-side hints are stale
            }
          });
    } catch (RuntimeException | LinkageError ignored) {
      // best-effort
    }
  }

  /** Re-sends the command tree of every online AuthCore user (config reload). */
  public static void refreshAllCommandTrees() {
    for (in.potenfyr.authcore.models.User user : in.potenfyr.authcore.models.User.users.values()) {
      if (user == null || !user.isActive) continue;
      try {
        refreshCommandTree(user.player.get());
      } catch (RuntimeException | LinkageError ignored) {
        // next user
      }
    }
  }

  /** Re-sends the permission level + command tree (what vanilla op/deop do after editing the list). */
  private static void resendPermissionLevel(MinecraftServer server, ServerPlayer player) {
    try {
      server.getPlayerList().sendPlayerPermissionLevel(player);
    } catch (RuntimeException | LinkageError ignored) {
      // the list edit is what matters; the client refreshes on the next permission change
    }
  }

  /**
   * Signature-shape operator access for the runtimes a range jar covers but was not compiled
   * against (GameProfile-keyed PlayerList API). Only authlib/JDK types are named - every Minecraft
   * member is located by its parameter and return types, so no mapping name is ever needed.
   */
  private static final class LegacyOps {
    private LegacyOps() {}

    private static Object ops(MinecraftServer server) {
      try {
        return server.getPlayerList().getOps();
      } catch (RuntimeException | LinkageError err) {
        return null;
      }
    }

    private static java.lang.reflect.Method find(
        Class<?> owner, Class<?> param, java.util.function.Predicate<java.lang.reflect.Method> extra) {
      for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass())
        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
          if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())
              || java.lang.reflect.Modifier.isStatic(m.getModifiers())
              || m.getParameterCount() != 1
              || m.getParameterTypes()[0] != param) continue;
          if (extra.test(m)) return m;
        }
      return null;
    }

    /** The player's ops-list entry (null when not an operator or unresolvable). */
    static Object entry(MinecraftServer server, ServerPlayer player) {
      return entry(server, player.getGameProfile());
    }

    static Object entry(MinecraftServer server, com.mojang.authlib.GameProfile profile) {
      Object ops = ops(server);
      if (ops == null) return null;
      // StoredUserList#get(K) - erasure (Object) -> StoredUserEntry
      java.lang.reflect.Method get =
          find(ops.getClass(), Object.class,
              m -> net.minecraft.server.players.StoredUserEntry.class.isAssignableFrom(m.getReturnType()));
      if (get == null) return null;
      try {
        get.setAccessible(true);
        return get.invoke(ops, profile);
      } catch (ReflectiveOperationException | RuntimeException | LinkageError err) {
        return null;
      }
    }

    /** ServerOpListEntry#getLevel() located by shape (the only public no-arg int getter). */
    static int level(Object entry) {
      if (entry == null) return 0;
      for (Class<?> c = entry.getClass(); c != null && c != Object.class; c = c.getSuperclass())
        for (java.lang.reflect.Method m : c.getDeclaredMethods())
          if (m.getParameterCount() == 0
              && m.getReturnType() == int.class
              && java.lang.reflect.Modifier.isPublic(m.getModifiers())
              && !java.lang.reflect.Modifier.isStatic(m.getModifiers())
              && !"hashCode".equals(m.getName())) {
            try {
              return (Integer) m.invoke(entry);
            } catch (ReflectiveOperationException | RuntimeException err) {
              return 0;
            }
          }
      return 0;
    }

    static void remove(MinecraftServer server, ServerPlayer player) {
      Object ops = ops(server);
      if (ops == null) return;
      // StoredUserList#remove(K) - erasure (Object), returns void (or boolean on newer eras)
      java.lang.reflect.Method remove =
          find(ops.getClass(), Object.class,
              m -> m.getReturnType() == void.class || m.getReturnType() == boolean.class);
      if (remove == null) return;
      try {
        remove.setAccessible(true);
        remove.invoke(ops, player.getGameProfile());
      } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
        // operator status could not be removed - the command guards remain in place
      }
    }

    static void add(MinecraftServer server, ServerPlayer player, int level, boolean bypass) {
      add(server, player.getGameProfile(), level, bypass);
    }

    static void add(
        MinecraftServer server, com.mojang.authlib.GameProfile profile, int level, boolean bypass) {
      Object ops = ops(server);
      if (ops == null) return;
      Object newEntry;
      try {
        newEntry =
            net.minecraft.server.players.ServerOpListEntry.class
                .getConstructor(com.mojang.authlib.GameProfile.class, int.class, boolean.class)
                .newInstance(profile, level, bypass);
      } catch (ReflectiveOperationException | RuntimeException | LinkageError err) {
        return;
      }
      // StoredUserList#add(V) and #remove(StoredUserEntry) share the erased shape: add's parameter
      // is the type VARIABLE V, remove's is the parameterized StoredUserEntry<K>. Verified after the
      // call either way, so a wrong pick can never leave the account without its entry silently.
      for (Class<?> c = ops.getClass(); c != null && c != Object.class; c = c.getSuperclass())
        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
          if (m.getParameterCount() != 1
              || m.getParameterTypes()[0] != net.minecraft.server.players.StoredUserEntry.class
              || !(m.getGenericParameterTypes()[0] instanceof java.lang.reflect.TypeVariable))
            continue;
          try {
            m.setAccessible(true);
            m.invoke(ops, newEntry);
            if (entry(server, profile) != null) return;
          } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // try the next candidate
          }
        }
    }
  }


  

  /** Whether the player is currently gliding with an elytra (name differs by version). */
  public static boolean isGliding(ServerPlayer player) {
    if (player == null) return false;
    // isFallFlying() is the Mojang name in every era (Yarn's "isGliding" never exists at runtime);
    // the direct call is remapped for intermediary/SRG.
    try {
      return player.isFallFlying();
    } catch (RuntimeException | LinkageError ignored) {
      return false;
    }
  }

  /** Reads the player's connection latency in milliseconds. */
  public static int getLatency(ServerPlayer player) {
    if (player == null) return 0;
    // The previous lookup used Yarn names ("networkHandler", "pingMilliseconds") that exist on no
    // runtime, so the latency-based login timeouts never applied anywhere.
    try {
      /*? if >= 1.20.2 {*/
      return player.connection.latency();
      /*?} else {*/
      /*return player.latency;
      *//*?}*/
    } catch (RuntimeException | LinkageError otherEra) {
      return 0;
    }
  }

  /**
   * Runs a command for the player through the server dispatcher on every era.
   *
   * <p>Stonecutter-gated DIRECT calls ({@code performCommand} on 1.16-1.18, {@code
   * performPrefixedCommand} on 1.19.3+) - Loom remaps them for the runtime, which is what makes
   * this work on Fabric/intermediary and Forge/SRG. Reflecting on the Mojang method names (the
   * previous implementation) silently did nothing on those runtimes. Never throws.
   */
  public static void runCommand(ServerPlayer player, String command) {
    if (player == null || command == null || command.isBlank()) return;
    try {
      MinecraftServer server = getServer(player);
      if (server == null) return;
      net.minecraft.commands.CommandSourceStack source = player.createCommandSourceStack();
      /*? if < 1.19 {*/
      /*server.getCommands().performCommand(source, command);
      *//*?} else {*/
      server.getCommands().performPrefixedCommand(source, command);
      /*?}*/
    } catch (Throwable ignored) {
      // command execution is best-effort
    }
  }

  /**
   * Runs a command as the server console through the dispatcher on every era (same Stonecutter-
   * gated direct dispatch as the player overload). Never throws.
   */
  public static void runConsoleCommand(MinecraftServer server, String command) {
    if (server == null || command == null || command.isBlank()) return;
    try {
      net.minecraft.commands.CommandSourceStack source = server.createCommandSourceStack();
      /*? if < 1.19 {*/
      /*server.getCommands().performCommand(source, command);
      *//*?} else {*/
      server.getCommands().performPrefixedCommand(source, command);
      /*?}*/
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
    // The (int, int, int) constructor exists unchanged in every supported era, so no reflection
    // is needed. The old reflective "ofFloored" lookup is a Yarn name that never resolves on the
    // Mojang-named / intermediary / SRG runtimes: it threw (and allocated) a
    // NoSuchMethodException on EVERY call, including per-tick limbo paths.
    return new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
  }

  /**
   * The lowest Y coordinate of the world.
   *
   * <p>The accessor moved AND was renamed across the supported range: 1.17+ expose {@code
   * LevelHeightAccessor#getMinY()}, the unobfuscated 26.x era only has {@code getMinY()} (there is
   * no {@code getBottomY} method anywhere in the 26.x {@code Level} hierarchy - verified on the
   * 26.3 jar), and some older build targets still name it {@code getMinBuildHeight()}. Resolving
   * only the oldest name silently returned {@code 0} on 26.x, which made every landing search stop
   * at Y=0 and find NOTHING below it - a mid-air restore anywhere in the modern deep world
   * (mining/caving below Y=0) therefore had no landing at all (issue #11 follow-up).
   */
  public static int getBottomY(Level world) {
    if (world == null) return 0;
    // Direct, build-remapped call first: intermediary names survive Mojang's renames
    // (getMinBuildHeight -> getMinY), so this resolves on every Fabric runtime of the range jar.
    try {
      /*? if >= 1.21.2 {*/
      return world.getMinY();
      /*?} else {*/
      /*return world.getMinBuildHeight();
      *//*?}*/
    } catch (RuntimeException | LinkageError otherName) {
      // a Mojang-named runtime (NeoForge) on the other side of the rename - by name below
    }
    for (String name : new String[] {"getMinY", "getMinBuildHeight", "getBottomY"}) {
      try {
        Object value = in.potenfyr.authcore.util.ReflectCache.method(Level.class, name).invoke(world);
        if (value instanceof Integer y) return y;
      } catch (ReflectiveOperationException | RuntimeException ignored) {
        // try the next name
      }
    }
    return 0;
  }

  /**
   * The world's shared spawn position across eras.
   *
   * <p>26.x moved it out of {@code Level#getSharedSpawnPos()} into
   * {@code LevelData.RespawnData#pos()}; both are resolved reflectively so one jar works on every
   * supported version. Returns {@code null} when neither shape is readable.
   */
  public static BlockPos sharedSpawnPos(Level world) {
    if (world == null) return null;
    try {
      /*? if >= 1.21.9 {*/
      return world.getRespawnData().pos();
      /*?} else {*/
      /*if (world instanceof ServerLevel serverLevel) return serverLevel.getSharedSpawnPos();
      *//*?}*/
    } catch (RuntimeException | LinkageError otherEra) {
      // the other side of the 1.21.9 spawn-data change - by name below
    }
    for (Class<?> owner : new Class<?>[] {world.getClass(), Level.class}) {
      try {
        Object value = owner.getMethod("getSharedSpawnPos").invoke(world);
        if (value instanceof BlockPos pos) return pos;
      } catch (ReflectiveOperationException | RuntimeException ignored) {
        // next shape
      }
    }
    try {
      Object data = Level.class.getMethod("getRespawnData").invoke(world);
      if (data != null) {
        Object pos = data.getClass().getMethod("pos").invoke(data);
        if (pos instanceof BlockPos p) return p;
      }
    } catch (ReflectiveOperationException | RuntimeException ignored) {
      // not available on this era
    }
    return null;
  }

  /**
   * The dimension id of a level as a plain string, across every supported era.
   *
   * <p>{@code ResourceKey#location()} was renamed to {@code identifier()} in the 26.x era, so this
   * is resolved reflectively (used for diagnostics only - the exact textual form is irrelevant).
   */
  public static String dimensionName(Level world) {
    if (world == null) return "unknown";
    try {
      Object key = world.dimension();
      if (key == null) return "unknown";
      for (String name : new String[] {"location", "identifier"}) {
        try {
          Object id = key.getClass().getMethod(name).invoke(key);
          if (id != null) return id.toString();
        } catch (ReflectiveOperationException | RuntimeException ignored) {
          // try the other name
        }
      }
      return String.valueOf(key);
    } catch (RuntimeException ignored) {
      return "unknown";
    }
  }

  /**
   * The highest buildable Y coordinate of the world (exclusive end of the build range).
   *
   * <p>Same rename story as {@link #getBottomY}: 26.x exposes {@code getMaxY()} /
   * {@code getHeight()}, older eras {@code getMaxBuildHeight()}. The old fallback of 256 was
   * harmless in the overworld but wrong for tall/custom dimensions.
   */
  public static int getMaxBuildHeight(Level world) {
    if (world == null) return 256;
    // Exclusive top = bottom + height; getHeight() is the same method in every era.
    try {
      return getBottomY(world) + world.getHeight();
    } catch (RuntimeException | LinkageError unavailable) {
      // 1.16 (no LevelHeightAccessor) - by name below
    }
    for (String name : new String[] {"getMaxBuildHeight", "getHeight"}) {
      try {
        Object value = in.potenfyr.authcore.util.ReflectCache.method(Level.class, name).invoke(world);
        if (value instanceof Integer y) return name.equals("getHeight") ? getBottomY(world) + y : y;
      } catch (ReflectiveOperationException | RuntimeException ignored) {
        // try the next name
      }
    }
    return 256;
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
   * Sends a custom payload (plugin message) to the player's client - the AuthInterop channel
   * ({@code AUTH_CHANGED} broadcasts for proxy plugins, companion messages). Never throws.
   *
   * <p>1.16-1.18 builds: direct, build-remapped {@code (ResourceLocation, FriendlyByteBuf)} packet.
   * The previous Class.forName lookups only matched a Mojang-named runtime that ALSO still had
   * that pre-1.20.2 constructor - a combination no supported server has - so nothing was ever sent.
   *
   * <p>1.20.2+ builds: see {@link RawPayload} - vanilla, Fabric and NeoForge only encode payload
   * types registered with them, so the bytes are appended on the netty pipeline instead.
   */
  public static void sendCustomPayload(
      net.minecraft.server.level.ServerPlayer player, String channel, byte[] data) {
    if (player == null || channel == null || data == null || player.connection == null) return;
    /*? if < 1.20.2 {*/
    /*try {
      player.connection.send(
          new net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket(
              new net.minecraft.resources.ResourceLocation(channel),
              new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(data))));
    } catch (RuntimeException | LinkageError ignored) {
      // interop is best-effort - never break gameplay
    }
    *//*?} else {*/
    try {
      if (!RawPayload.send(player, channel, data)) reportPayloadUnavailable(channel, null);
    } catch (RuntimeException | LinkageError err) {
      reportPayloadUnavailable(channel, err);
    }
    /*?}*/
  }

  private static final java.util.concurrent.atomic.AtomicBoolean CUSTOM_PAYLOAD_UNSUPPORTED_LOGGED =
      new java.util.concurrent.atomic.AtomicBoolean();

  /** Reports once that plugin messages cannot be sent here (warn on proxy networks, else debug). */
  private static void reportPayloadUnavailable(String channel, Throwable cause) {
    if (!CUSTOM_PAYLOAD_UNSUPPORTED_LOGGED.compareAndSet(false, true)) return;
    String msg =
        "Interop plugin messages (channel '{}', proxy AUTH_CHANGED broadcasts) could not be sent"
            + " on this server ({}) - proxy auth gates need Redis (database.redis) here.";
    String why = cause == null ? "no netty channel / packet encoder" : cause.toString();
    var cfg = in.potenfyr.authcore.AuthCoreServer.config;
    if (cfg != null && cfg.session.proxySupport.enabled)
      in.potenfyr.authcore.AuthCoreServer.LOGGER.warn(false, msg, channel, why);
    else in.potenfyr.authcore.AuthCoreServer.LOGGER.debug(false, msg, channel, why);
  }

  /*? if >= 1.20.2 {*/
  /**
   * Raw plugin messages for 1.20.2+.
   *
   * <p>Since 1.20.2 a custom payload is a typed object, and the packet codec only writes the body
   * of types registered with vanilla / Fabric API / NeoForge; every other id falls back to
   * {@code DiscardedPayload}, whose encoder writes NOTHING after the channel id. AuthCore's
   * channels carry arbitrary bytes to the proxy, so instead of registering a codec per loader (and
   * NeoForge's channel negotiation, which refuses payloads a vanilla client never announced) the
   * packet is encoded as an empty {@code DiscardedPayload} of the channel and the bytes are
   * appended by a tiny outbound handler sitting right after the packet encoder - before
   * compression, length prefix and encryption. The result is exactly the vanilla wire format
   * {@code [packet id][channel][bytes]}.
   *
   * <p>Everything runs on the channel's event loop, so the encoder output seen by the handler is
   * the packet written in the same task. The 1.19-1.20.1 runtimes of the 1.21.11 range jar
   * (pre-record packet) use the classic {@code (ResourceLocation, FriendlyByteBuf)} constructor,
   * located by shape.
   */
  private static final class RawPayload {
    private RawPayload() {}

    static final String HANDLER = "authcore_payload";

    /** Appends the pending bytes to the next encoded packet buffer. */
    static final class Appender extends io.netty.channel.ChannelOutboundHandlerAdapter {
      byte[] pending; // confined to the channel's event loop

      @Override
      public void write(
          io.netty.channel.ChannelHandlerContext ctx,
          Object msg,
          io.netty.channel.ChannelPromise promise)
          throws Exception {
        byte[] data = pending;
        if (data != null && msg instanceof io.netty.buffer.ByteBuf buf) {
          pending = null;
          buf.writeBytes(data);
        }
        super.write(ctx, msg, promise);
      }
    }

    /** @return false when this runtime offers no way to send it (reported by the caller) */
    static boolean send(ServerPlayer player, String channel, byte[] data) {
      net.minecraft.network.Connection connection = networkConnection(player.connection);
      if (connection == null) return false;
      io.netty.channel.Channel ch =
          ((in.potenfyr.authcore.mixin.ClientConnectionAccessor) connection).authCore$getChannel();
      if (ch == null) return false;
      if (!ch.isOpen()) return true; // the player is leaving - nothing to report
      net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.tryParse(channel);
      if (id == null) return true; // invalid channel name - nothing a retry could fix

      Object packet;
      boolean append;
      try {
        // Fabric API / NeoForge own the channel-registration channel and announce channels
        // themselves (their codec for it would reject a DiscardedPayload).
        if ("minecraft:register".equals(channel)) return true;
        packet =
            new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                new net.minecraft.network.protocol.common.custom.DiscardedPayload(id));
        append = true;
      } catch (LinkageError preRecordRuntime) {
        packet = legacyPacket(id, data);
        append = false;
        if (packet == null) return false;
      }

      final Object out = packet;
      final boolean withBytes = append;
      ch.eventLoop()
          .execute(
              () -> {
                try {
                  io.netty.channel.ChannelPipeline pipeline = ch.pipeline();
                  Appender appender = null;
                  if (withBytes) {
                    appender = (Appender) pipeline.get(HANDLER);
                    if (appender == null) {
                      if (pipeline.get("encoder") == null) return;
                      appender = new Appender();
                      pipeline.addBefore("encoder", HANDLER, appender);
                    }
                    appender.pending = data;
                  }
                  try {
                    ch.writeAndFlush(out);
                  } finally {
                    if (appender != null) appender.pending = null;
                  }
                } catch (RuntimeException | LinkageError ignored) {
                  // interop is best-effort - never break the connection
                }
              });
      return true;
    }

    /** 1.19-1.20.1: {@code new ClientboundCustomPayloadPacket(ResourceLocation, FriendlyByteBuf)}. */
    private static Object legacyPacket(net.minecraft.resources.Identifier id, byte[] data) {
      try {
        for (java.lang.reflect.Constructor<?> ctor :
            net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket.class
                .getConstructors()) {
          Class<?>[] p = ctor.getParameterTypes();
          if (p.length == 2
              && p[0] == net.minecraft.resources.Identifier.class
              && p[1] == net.minecraft.network.FriendlyByteBuf.class)
            return ctor.newInstance(
                id,
                new net.minecraft.network.FriendlyByteBuf(
                    io.netty.buffer.Unpooled.wrappedBuffer(data)));
        }
      } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
        // no classic shape on this runtime
      }
      return null;
    }
  }
  /*?}*/

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
      /*? if < 1.21.4 {*/
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
    /*? if < 1.20 {*/
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
    /*? if < 1.20 {*/
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
    // By TYPE: the field is only called "server" in Mojang names.
    Object server = loginValue(handler, MinecraftServer.class);
    return server instanceof MinecraftServer ms ? ms : null;
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
  /** The offline-mode UUID vanilla assigns to a name ("OfflinePlayer:" + name, v3). */
  public static java.util.UUID offlineUuid(String username) {
    if (username == null) return null;
    return java.util.UUID.nameUUIDFromBytes(
        ("OfflinePlayer:" + username).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  /** The requested name of a login hello packet (direct, remapped accessors per era). */
  public static String helloName(Object packet) {
    if (!(packet instanceof net.minecraft.network.protocol.login.ServerboundHelloPacket hello))
      return null;
    try {
      /*? if >= 1.19 {*/
      return hello.name();
      /*?} else {*/
      /*com.mojang.authlib.GameProfile profile = hello.getGameProfile();
      return profile == null ? null : profile.getName();
      *//*?}*/
    } catch (RuntimeException | LinkageError otherEra) {
      return null;
    }
  }

  /**
   * The account UUID the client CLAIMS in its hello (1.20.2+: always present; 1.19-1.20.1: an
   * Optional; 1.16-1.18: absent). Null when the runtime's hello carries none.
   */
  public static java.util.UUID helloId(Object packet) {
    if (!(packet instanceof net.minecraft.network.protocol.login.ServerboundHelloPacket hello))
      return null;
    /*? if >= 1.20.2 {*/
    try {
      return hello.profileId();
    } catch (RuntimeException | LinkageError otherEra) {
      // 1.19-1.20.1 inside the range jar: profileId() returns Optional<UUID> - by shape below
    }
    /*?}*/
    // By shape (a build target before 1.20.2, or an older runtime of a range jar): the uuid is a
    // UUID or an Optional<UUID> accessor; 1.16-1.19.0 have neither.
    try {
      for (java.lang.reflect.Method m : hello.getClass().getMethods()) {
        if (m.getParameterCount() != 0) continue;
        if (m.getReturnType() == java.util.UUID.class) return (java.util.UUID) m.invoke(hello);
        if (m.getReturnType() == java.util.Optional.class) {
          Object value = ((java.util.Optional<?>) m.invoke(hello)).orElse(null);
          if (value instanceof java.util.UUID u) return u;
        }
      }
    } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
      // unreadable
    }
    return null;
  }

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

  /**
   * Login-listener field by TYPE (first non-static field assignable to {@code type}, walking the
   * hierarchy). The names differ in every era ({@code challenge} vs {@code nonce}) and in every
   * mapping (intermediary/SRG), so the old name lookups never resolved on Fabric or Forge and the
   * offline-server premium verification silently never ran there.
   */
  private static java.lang.reflect.Field loginField(Object handler, Class<?> type) {
    for (Class<?> c = handler.getClass(); c != null && c != Object.class; c = c.getSuperclass())
      for (java.lang.reflect.Field f : c.getDeclaredFields()) {
        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
        if (!type.isAssignableFrom(f.getType())) continue;
        try {
          f.setAccessible(true);
          return f;
        } catch (RuntimeException inaccessible) {
          // keep looking
        }
      }
    return null;
  }

  private static Object loginValue(Object handler, Class<?> type) {
    java.lang.reflect.Field f = loginField(handler, type);
    if (f == null) return null;
    try {
      return f.get(handler);
    } catch (ReflectiveOperationException | RuntimeException err) {
      return null;
    }
  }

  /** The login listener's nested State enum field (located by shape: an enum nested in it). */
  private static java.lang.reflect.Field loginStateField(Object handler) {
    for (Class<?> c = handler.getClass(); c != null && c != Object.class; c = c.getSuperclass())
      for (java.lang.reflect.Field f : c.getDeclaredFields()) {
        if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || !f.getType().isEnum()) continue;
        if (f.getType().getEnclosingClass() != c) continue;
        try {
          f.setAccessible(true);
          return f;
        } catch (RuntimeException inaccessible) {
          // keep looking
        }
      }
    return null;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static boolean setLoginState(Object handler, String constant) {
    java.lang.reflect.Field stateField = loginStateField(handler);
    if (stateField == null) return false;
    try {
      // Enum constant NAMES survive obfuscation (the name string is kept), so valueOf is era-safe.
      stateField.set(handler, Enum.valueOf((Class<? extends Enum>) stateField.getType(), constant));
      return true;
    } catch (ReflectiveOperationException | RuntimeException err) {
      return false;
    }
  }

  /**
   * Starts the vanilla encryption handshake on a login handler: state → KEY and a
   * {@code ClientboundHelloPacket(serverId="", publicKey, challenge)} is sent. The client
   * then answers with the key packet, which {@link #loginFinishHandshake} decrypts.
   *
   * @return {@code true} when the handshake was started, {@code false} on failure (caller must
   *     let the vanilla offline flow continue)
   */
  public static boolean loginStartHandshake(Object handler) {
    if (handler == null) return false;
    try {
      MinecraftServer server = (MinecraftServer) loginValue(handler, MinecraftServer.class);
      net.minecraft.network.Connection connection =
          (net.minecraft.network.Connection) loginValue(handler, net.minecraft.network.Connection.class);
      byte[] challenge = (byte[]) loginValue(handler, byte[].class);
      if (server == null || connection == null || challenge == null) return false;
      java.security.KeyPair keyPair = server.getKeyPair();
      if (keyPair == null) return false;
      byte[] encoded = keyPair.getPublic().getEncoded();

      net.minecraft.network.protocol.login.ClientboundHelloPacket packet = null;
      try {
        /*? if >= 1.20.5 {*/
        packet =
            new net.minecraft.network.protocol.login.ClientboundHelloPacket(
                "", encoded, challenge, true);
        /*?} else {*/
        /*packet = new net.minecraft.network.protocol.login.ClientboundHelloPacket("", encoded, challenge);
        *//*?}*/
      } catch (LinkageError otherEra) {
        // the other side of the 1.20.5 constructor change inside a range jar - by shape
        for (java.lang.reflect.Constructor<?> ctor :
            net.minecraft.network.protocol.login.ClientboundHelloPacket.class.getConstructors()) {
          Class<?>[] p = ctor.getParameterTypes();
          try {
            if (p.length == 3 && p[0] == String.class && p[1] == byte[].class && p[2] == byte[].class)
              packet =
                  (net.minecraft.network.protocol.login.ClientboundHelloPacket)
                      ctor.newInstance("", encoded, challenge);
            else if (p.length == 4 && p[0] == String.class && p[1] == byte[].class
                && p[2] == byte[].class && p[3] == boolean.class)
              packet =
                  (net.minecraft.network.protocol.login.ClientboundHelloPacket)
                      ctor.newInstance("", encoded, challenge, true);
          } catch (ReflectiveOperationException ignored) {
            // next constructor
          }
          if (packet != null) break;
        }
      }
      if (packet == null) return false;

      // Only switch the state once the packet exists - a failure above leaves vanilla untouched.
      if (!setLoginState(handler, "KEY")) return false;
      connection.send(packet);
      return true;
    } catch (RuntimeException | LinkageError err) {
      return false;
    }
  }

  /**
   * Completes the vanilla encryption handshake for a received key packet: verifies the
   * challenge, decrypts the shared secret, activates connection encryption and computes the
   * session digest used for {@code hasJoinedServer}.
   *
   * @return the session digest (hex), or {@code null} on failure
   */
  public static String loginFinishHandshake(Object handler, Object keyPacket) {
    if (handler == null
        || !(keyPacket instanceof net.minecraft.network.protocol.login.ServerboundKeyPacket key))
      return null;
    try {
      MinecraftServer server = (MinecraftServer) loginValue(handler, MinecraftServer.class);
      net.minecraft.network.Connection connection =
          (net.minecraft.network.Connection) loginValue(handler, net.minecraft.network.Connection.class);
      byte[] challenge = (byte[]) loginValue(handler, byte[].class);
      if (server == null || connection == null || challenge == null) return null;
      java.security.KeyPair keyPair = server.getKeyPair();
      java.security.PrivateKey privateKey = keyPair.getPrivate();

      // The challenge MUST verify - a key packet that does not echo it is rejected (never skipped).
      boolean challengeOk;
      try {
        /*? if >= 1.19 {*/
        challengeOk = key.isChallengeValid(challenge, privateKey);
        /*?} else {*/
        /*challengeOk = java.util.Arrays.equals(key.getNonce(privateKey), challenge);
        *//*?}*/
      } catch (LinkageError otherEra) {
        challengeOk = false;
      }
      if (!challengeOk) return null;

      javax.crypto.SecretKey secret = key.getSecretKey(privateKey);
      javax.crypto.Cipher decrypt = net.minecraft.util.Crypt.getCipher(2, secret);
      javax.crypto.Cipher encrypt = net.minecraft.util.Crypt.getCipher(1, secret);
      connection.setEncryptionKey(decrypt, encrypt);
      byte[] digest = net.minecraft.util.Crypt.digestData("", keyPair.getPublic(), secret);
      return new java.math.BigInteger(digest).toString(16);
    } catch (Exception | LinkageError err) {
      return null;
    }
  }

  /**
   * Verifies a session with the server's OWN Minecraft session service
   * ({@code hasJoinedServer}) - the same call vanilla makes on online-mode servers.
   *
   * @return the verified profile, or {@code null} when Mojang does not confirm the session
   */
  public static com.mojang.authlib.GameProfile loginVerifyProfile(
      Object server, String username, String digest, java.net.InetAddress address) {
    if (!(server instanceof MinecraftServer mc)) return null;
    try {
      Object sessionService = null;
      try {
        /*? if >= 1.20.5 {*/
        sessionService = mc.services().sessionService();
        /*?} else {*/
        /*sessionService = mc.getSessionService();
        *//*?}*/
      } catch (LinkageError otherEra) {
        // older runtime inside the range jar: the session service is the only field/getter of
        // that authlib type - authlib is never remapped, so locate it by type
        for (java.lang.reflect.Method m : MinecraftServer.class.getMethods())
          if (m.getParameterCount() == 0
              && m.getReturnType().getName().startsWith("com.mojang.authlib.minecraft.")
              && m.getReturnType().getSimpleName().endsWith("SessionService")) {
            sessionService = m.invoke(mc);
            break;
          }
      }
      if (sessionService == null) return null;

      // authlib API (real, never-remapped names): hasJoinedServer(GameProfile|String, String, InetAddress)
      Object result = null;
      for (java.lang.reflect.Method m : sessionService.getClass().getMethods()) {
        if (!m.getName().equals("hasJoinedServer") || m.getParameterCount() != 3) continue;
        Class<?> first = m.getParameterTypes()[0];
        if (first == String.class) result = m.invoke(sessionService, username, digest, address);
        else if (first == com.mojang.authlib.GameProfile.class)
          result =
              m.invoke(sessionService, new com.mojang.authlib.GameProfile(null, username), digest, address);
        else continue;
        break;
      }
      if (result instanceof com.mojang.authlib.GameProfile profile) return profile;
      if (result != null) {
        // ProfileResult wrapper (authlib 5+)
        Object profile = result.getClass().getMethod("profile").invoke(result);
        if (profile instanceof com.mojang.authlib.GameProfile gp) return gp;
      }
      return null;
    } catch (ReflectiveOperationException | RuntimeException | LinkageError err) {
      return null;
    }
  }

  /**
   * Resumes the vanilla join after a verification: state → HELLO and the original hello packet is
   * handled again, on the connection's event loop. Direct, remapped calls - the old name lookups
   * ("state", "handleHello", "channel") only resolved on Mojang-named runtimes.
   */
  public static void loginReplayHello(Object handler, Object helloPacket) {
    if (!(handler instanceof net.minecraft.server.network.ServerLoginPacketListenerImpl login)
        || !(helloPacket instanceof net.minecraft.network.protocol.login.ServerboundHelloPacket hello))
      return;
    Runnable replay =
        () -> {
          try {
            if (setLoginState(login, "HELLO")) login.handleHello(hello);
          } catch (RuntimeException | LinkageError ignored) {
            // join flow could not be resumed - the vanilla login timeout disconnects safely
          }
        };
    try {
      net.minecraft.network.Connection connection =
          (net.minecraft.network.Connection) loginValue(login, net.minecraft.network.Connection.class);
      Object channel =
          connection == null ? null : loginValue(connection, io.netty.channel.Channel.class);
      if (channel instanceof io.netty.channel.Channel ch) {
        ch.eventLoop().execute(replay);
        return;
      }
    } catch (RuntimeException | LinkageError ignored) {
      // fall through - replay inline
    }
    replay.run();
  }

  // ------------------------------------------------------------------
  // Premium identity on offline-mode servers (premium-skins / premium-real-uuid).
  //
  // After a successful server-side verification the login is resumed ON THE SERVER THREAD (login
  // tick) and the profile vanilla just created for the offline flow is replaced before vanilla
  // reads it in the same tick - either the offline profile carrying the Mojang skin, or the
  // verified Mojang profile itself (real UUID). authlib types are never remapped, so its members
  // are reached by their real names; every Minecraft member is a direct (build-remapped) call or
  // is located by shape.
  // ------------------------------------------------------------------

  /**
   * Replays the hello on the CALLING thread (the server thread, from the login tick): state ->
   * HELLO and vanilla handles the packet again, creating its offline profile.
   *
   * @return {@code true} when vanilla handled the hello
   */
  public static boolean loginReplayHelloNow(Object handler, Object helloPacket) {
    if (!(handler instanceof net.minecraft.server.network.ServerLoginPacketListenerImpl login)
        || !(helloPacket instanceof net.minecraft.network.protocol.login.ServerboundHelloPacket hello))
      return false;
    try {
      if (!setLoginState(login, "HELLO")) return false;
      login.handleHello(hello);
      return true;
    } catch (RuntimeException | LinkageError err) {
      return false;
    }
  }

  /** Runs a task on the login connection's event loop (inline when it cannot be resolved). */
  public static void loginExecute(Object handler, Runnable task) {
    if (task == null) return;
    try {
      Object connection = loginValue(handler, net.minecraft.network.Connection.class);
      Object channel =
          connection == null ? null : loginValue(connection, io.netty.channel.Channel.class);
      if (channel instanceof io.netty.channel.Channel ch) {
        ch.eventLoop().execute(task);
        return;
      }
    } catch (RuntimeException | LinkageError ignored) {
      // fall through - run inline
    }
    task.run();
  }

  /** Replaces the login listener's (single) GameProfile field - the profile vanilla joins with. */
  public static boolean loginSetProfile(Object handler, com.mojang.authlib.GameProfile profile) {
    if (handler == null || profile == null) return false;
    java.lang.reflect.Field field = loginField(handler, com.mojang.authlib.GameProfile.class);
    if (field == null) return false;
    try {
      // Only replace a profile vanilla already set: a null field means the hello was not accepted.
      if (field.get(handler) == null) return false;
      field.set(handler, profile);
      return true;
    } catch (ReflectiveOperationException | RuntimeException err) {
      return false;
    }
  }

  private static Object authlibCall(Object target, String... names) {
    if (target == null) return null;
    for (String name : names) {
      try {
        Object value = target.getClass().getMethod(name).invoke(target);
        if (value != null) return value;
      } catch (ReflectiveOperationException | RuntimeException ignored) {
        // next accessor
      }
    }
    return null;
  }

  /** authlib GameProfile id across authlib eras ({@code getId()} / record {@code id()}). */
  public static java.util.UUID profileId(com.mojang.authlib.GameProfile profile) {
    return authlibCall(profile, "getId", "id") instanceof java.util.UUID id ? id : null;
  }

  /** authlib GameProfile name across authlib eras ({@code getName()} / record {@code name()}). */
  public static String profileName(com.mojang.authlib.GameProfile profile) {
    return authlibCall(profile, "getName", "name") instanceof String name ? name : null;
  }

  /** Whether the profile carries a Mojang {@code textures} (skin) property. */
  public static boolean profileHasTextures(com.mojang.authlib.GameProfile profile) {
    Object props = authlibCall(profile, "getProperties", "properties");
    if (!(props instanceof com.google.common.collect.Multimap<?, ?> map)) return false;
    try {
      return map.containsKey("textures");
    } catch (RuntimeException err) {
      return false;
    }
  }

  /**
   * A profile with the given id and name that carries the properties (the signed Mojang skin) of
   * {@code source}. Record authlib (1.21.9+): the immutable property map is shared through the
   * 3-arg constructor; classic authlib: the properties are copied into the new profile's map.
   *
   * @return the new profile, or {@code null} when it cannot be built on this runtime
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static com.mojang.authlib.GameProfile profileWithProperties(
      java.util.UUID id, String name, com.mojang.authlib.GameProfile source) {
    if (id == null || name == null || source == null) return null;
    try {
      Object sourceProps = authlibCall(source, "getProperties", "properties");
      if (sourceProps == null) return null;
      for (java.lang.reflect.Constructor<?> ctor :
          com.mojang.authlib.GameProfile.class.getConstructors()) {
        Class<?>[] p = ctor.getParameterTypes();
        if (p.length == 3
            && p[0] == java.util.UUID.class
            && p[1] == String.class
            && p[2].isInstance(sourceProps))
          return (com.mojang.authlib.GameProfile) ctor.newInstance(id, name, sourceProps);
      }
      com.mojang.authlib.GameProfile out = new com.mojang.authlib.GameProfile(id, name);
      Object outProps = authlibCall(out, "getProperties", "properties");
      if (!(outProps instanceof com.google.common.collect.Multimap target)
          || !(sourceProps instanceof com.google.common.collect.Multimap from)) return null;
      target.putAll(from);
      return out;
    } catch (ReflectiveOperationException | RuntimeException | LinkageError err) {
      return null;
    }
  }

  /**
   * Whether this runtime validates the client's chat-signing key against the JOIN profile's uuid
   * during the login (1.19 - 1.19.2). A complete offline-uuid profile fails that check with a
   * genuine client (its key is signed for the real uuid), so the skin-only profile is not used
   * there. The real-uuid profile passes it, exactly as on an online-mode server.
   */
  public static boolean loginValidatesProfileKey() {
    String version = getGameVersion();
    return "1.19".equals(version) || "1.19.1".equals(version) || "1.19.2".equals(version);
  }

  /** Whether a player with this uuid is currently online. */
  public static boolean isPlayerOnline(MinecraftServer server, java.util.UUID uuid) {
    if (server == null || uuid == null) return false;
    try {
      return server.getPlayerList().getPlayer(uuid) != null;
    } catch (RuntimeException | LinkageError err) {
      return false;
    }
  }

  /** The entry of a stored user list (bans / whitelist / ops) for a profile, or null. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Object storedEntry(Object list, com.mojang.authlib.GameProfile profile) {
    if (!(list instanceof net.minecraft.server.players.StoredUserList stored) || profile == null)
      return null;
    try {
      /*? if >= 1.21.9 {*/
      return stored.get(new net.minecraft.server.players.NameAndId(profile));
      /*?} else {*/
      /*return stored.get(profile);
      *//*?}*/
    } catch (RuntimeException | LinkageError otherEra) {
      // GameProfile-keyed runtime inside a NameAndId build: StoredUserList#get(K) by shape
      for (Class<?> c = list.getClass(); c != null && c != Object.class; c = c.getSuperclass())
        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
          if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())
              || java.lang.reflect.Modifier.isStatic(m.getModifiers())
              || m.getParameterCount() != 1
              || m.getParameterTypes()[0] != Object.class
              || !net.minecraft.server.players.StoredUserEntry.class.isAssignableFrom(
                  m.getReturnType())) continue;
          try {
            m.setAccessible(true);
            return m.invoke(list, profile);
          } catch (ReflectiveOperationException | RuntimeException | LinkageError err) {
            return null;
          }
        }
      return null;
    }
  }

  /** Whether the profile is on the server's player ban list. */
  public static boolean isProfileBanned(
      MinecraftServer server, com.mojang.authlib.GameProfile profile) {
    if (server == null || profile == null) return false;
    try {
      return storedEntry(server.getPlayerList().getBans(), profile) != null;
    } catch (RuntimeException | LinkageError err) {
      return false;
    }
  }

  /**
   * Carries the whitelist and operator entries of {@code from} over to {@code to} (premium-real-uuid:
   * the offline profile's access follows the player to the real uuid). Existing entries of
   * {@code to} are never changed and the entries of {@code from} are kept.
   */
  public static void carryProfileAccess(
      MinecraftServer server,
      com.mojang.authlib.GameProfile from,
      com.mojang.authlib.GameProfile to) {
    if (server == null || from == null || to == null) return;
    try {
      net.minecraft.server.players.PlayerList players = server.getPlayerList();
      Object whitelist = players.getWhiteList();
      if (storedEntry(whitelist, from) != null && storedEntry(whitelist, to) == null)
        addToWhitelist(server, to);

      Object ops = players.getOps();
      Object opEntry = storedEntry(ops, from);
      if (opEntry != null && storedEntry(ops, to) == null) {
        int level;
        boolean bypass = false;
        try {
          /*? if >= 1.21.9 {*/
          level =
              ((net.minecraft.server.players.ServerOpListEntry) opEntry).permissions().level().id();
          /*?} else {*/
          /*level = ((net.minecraft.server.players.ServerOpListEntry) opEntry).getLevel();
          *//*?}*/
        } catch (RuntimeException | LinkageError otherEra) {
          level = LegacyOps.level(opEntry);
        }
        try {
          bypass =
              ((net.minecraft.server.players.ServerOpListEntry) opEntry).getBypassesPlayerLimit();
        } catch (RuntimeException | LinkageError ignored) {
          // default bypass flag
        }
        int lvl = Math.max(1, Math.min(4, level <= 0 ? 4 : level));
        try {
          /*? if >= 1.21.9 {*/
          players.getOps().add(
              new net.minecraft.server.players.ServerOpListEntry(
                  new net.minecraft.server.players.NameAndId(to),
                  net.minecraft.server.permissions.LevelBasedPermissionSet.forLevel(
                      net.minecraft.server.permissions.PermissionLevel.byId(lvl)),
                  bypass));
          /*?} else {*/
          /*players.getOps().add(
              new net.minecraft.server.players.ServerOpListEntry(to, lvl, bypass));
          *//*?}*/
        } catch (RuntimeException | LinkageError otherEra) {
          LegacyOps.add(server, to, lvl, bypass);
        }
      }
    } catch (RuntimeException | LinkageError err) {
      AuthCoreServer.LOGGER.debug(
          false, "Whitelist/operator carry-over to the real uuid failed (best-effort):", err);
    }
  }

  /**
   * The world's per-player storage directories: {@code [playerdata, advancements, stats]}, or
   * {@code null} when they cannot be resolved on this runtime.
   */
  public static java.nio.file.Path[] playerStorageDirs(MinecraftServer server) {
    if (server == null) return null;
    try {
      return new java.nio.file.Path[] {
        server.getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR),
        server.getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_ADVANCEMENTS_DIR),
        server.getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_STATS_DIR)
      };
    } catch (RuntimeException | LinkageError err) {
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

  

  /** Reads the player yaw (tickDelta parameter on older versions). */
  public static float getYaw(ServerPlayer player) {
    if (player == null) return 0f;
    // Direct getYRot() (1.17+, remapped per runtime). The previous Yarn-name reflection never resolved,
    // so every limbo/restore teleport snapped the player's view to 0/0.
    try {
      return player.getYRot();
    } catch (RuntimeException | LinkageError legacy) {
      return 0f;
    }
  }

  /** Reads the player pitch (tickDelta parameter on older versions). */
  public static float getPitch(ServerPlayer player) {
    if (player == null) return 0f;
    // Direct getXRot() (1.17+, remapped per runtime). The previous Yarn-name reflection never resolved,
    // so every limbo/restore teleport snapped the player's view to 0/0.
    try {
      return player.getXRot();
    } catch (RuntimeException | LinkageError legacy) {
      return 0f;
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
    // The old Class.forName("net.minecraft.network.chat.ClickEvent...") lookups only matched
    // Mojang-named runtimes: on intermediary (every Fabric 1.16-1.21 server) and SRG (Forge) the
    // limbo's clickable /login and /register buttons silently rendered as plain text.
    /*? if >= 1.21.5 {*/
    try {
      return ModernClick.create(suggests, command); // direct, build-remapped
    } catch (LinkageError olderRuntime) {
      // 1.19-1.21.4 inside the 1.21.11 build: no nested records - legacy shape below
    }
    /*?}*/
    // Legacy shape: new ClickEvent(Action, String), located by signature. The enum constant
    // NAMES survive obfuscation (Enum.name() is the literal javac compiled in), so this works on
    // intermediary, SRG and Mojang runtimes alike.
    try {
      String wanted = suggests ? "SUGGEST_COMMAND" : "RUN_COMMAND";
      for (java.lang.reflect.Constructor<?> ctor :
          net.minecraft.network.chat.ClickEvent.class.getConstructors()) {
        Class<?>[] params = ctor.getParameterTypes();
        if (params.length != 2 || !params[0].isEnum() || params[1] != String.class) continue;
        for (Object constant : params[0].getEnumConstants())
          if (((Enum<?>) constant).name().equals(wanted))
            return (net.minecraft.network.chat.ClickEvent) ctor.newInstance(constant, command);
      }
    } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
      // click events unavailable
    }
    return null;
  }

  /*? if >= 1.21.5 {*/
  /** Isolated so an older runtime fails with a catchable LinkageError at the call, not at load. */
  private static final class ModernClick {
    private ModernClick() {}

    static net.minecraft.network.chat.ClickEvent create(boolean suggests, String command) {
      return suggests
          ? new net.minecraft.network.chat.ClickEvent.SuggestCommand(command)
          : new net.minecraft.network.chat.ClickEvent.RunCommand(command);
    }
  }
  /*?}*/

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

  /**
   * Deserializes a base64 string back into a CompoundTag (or null on failure).
   *
   * <p>Direct, build-remapped call for the build target. The 1.21.11 build also covers 1.19-1.20.1
   * (no {@code NbtAccounter} argument yet): there the method is found by SHAPE - the old lookup
   * by Mojang name ({@code "readCompressed"}) never resolved on intermediary/SRG runtimes, so every
   * crash-snapshot read failed outside NeoForge/26.x.
   */
  public static net.minecraft.nbt.CompoundTag compoundTagFromBase64(String data) {
    final byte[] raw;
    try {
      raw = java.util.Base64.getDecoder().decode(data);
    } catch (RuntimeException err) {
      return null;
    }
    try {
      /*? if < 1.20.2 {*/
      /*return net.minecraft.nbt.NbtIo.readCompressed(new java.io.ByteArrayInputStream(raw));
      *//*?} else {*/
      return net.minecraft.nbt.NbtIo.readCompressed(
          new java.io.ByteArrayInputStream(raw), net.minecraft.nbt.NbtAccounter.unlimitedHeap());
      /*?}*/
    } catch (java.io.IOException | RuntimeException err) {
      return null;
    } catch (LinkageError otherEra) {
      return legacyReadCompressed(raw);
    }
  }

  /** NbtIo's static (InputStream) -> CompoundTag reader, located by signature. */
  private static net.minecraft.nbt.CompoundTag legacyReadCompressed(byte[] raw) {
    for (java.lang.reflect.Method m : net.minecraft.nbt.NbtIo.class.getMethods()) {
      if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())
          || m.getParameterCount() != 1
          || m.getParameterTypes()[0] != java.io.InputStream.class
          || m.getReturnType() != net.minecraft.nbt.CompoundTag.class) continue;
      try {
        return (net.minecraft.nbt.CompoundTag) m.invoke(null, new java.io.ByteArrayInputStream(raw));
      } catch (ReflectiveOperationException | RuntimeException err) {
        // try the next candidate (readCompressed vs. an uncompressed reader)
      }
    }
    return null;
  }

  // ------------------------------------------------------------------ snapshot serializers
  //
  // The crash-safe limbo snapshot stores the hidden inventory and the player's effects. Earlier
  // builds looked the (de)serializers up by MOJANG name ("save", "of", "load", "CODEC") - string
  // literals are never remapped, so on intermediary (Fabric 1.16-1.21) and SRG (Forge) runtimes
  // every stack/effect was written as "" and a crash during the limbo lost the hidden inventory.
  //
  //   1.16-1.18 build     direct legacy calls (save/of/load), remapped by the toolchain
  //   1.21.11 / 26.x      direct CODEC calls through RegistryOps (components and enchantments are
  //                       registry-bound since 1.20.5 - plain NbtOps cannot encode them); the
  //                       1.19-1.20.4 runtimes of that range jar fall back to the legacy shapes,
  //                       located by signature rather than by name.

  /*? if >= 1.20.5 {*/
  private static net.minecraft.core.HolderLookup.Provider registries(ServerPlayer player) {
    MinecraftServer server = getServer(player);
    return server == null ? null : server.registryAccess();
  }
  /*?}*/

  /** Serializes an ItemStack to a base64 string (for the crash-safe limbo snapshot). */
  public static String itemStackToBase64(ServerPlayer player, net.minecraft.world.item.ItemStack stack) {
    if (stack == null || stack.isEmpty()) return "";
    net.minecraft.nbt.CompoundTag tag = null;
    /*? if < 1.20.5 {*/
    /*try {
      tag = stack.save(new net.minecraft.nbt.CompoundTag());
    } catch (RuntimeException | LinkageError err) {
      tag = null;
    }
    *//*?} else {*/
    try {
      tag = ModernNbt.encodeStack(registries(player), stack);
    } catch (RuntimeException err) {
      tag = null;
    } catch (LinkageError olderRuntime) {
      tag = legacySave(net.minecraft.world.item.ItemStack.class, stack);
    }
    /*?}*/
    return tag == null ? "" : compoundTagToBase64(tag);
  }

  /** Deserializes an ItemStack from a base64 string (empty stack on failure). */
  public static net.minecraft.world.item.ItemStack itemStackFromBase64(ServerPlayer player, String data) {
    if (data == null || data.isBlank()) return net.minecraft.world.item.ItemStack.EMPTY;
    net.minecraft.nbt.CompoundTag tag = compoundTagFromBase64(data);
    if (tag == null) return net.minecraft.world.item.ItemStack.EMPTY;
    net.minecraft.world.item.ItemStack stack;
    /*? if < 1.20.5 {*/
    /*try {
      stack = net.minecraft.world.item.ItemStack.of(tag);
    } catch (RuntimeException | LinkageError err) {
      stack = null;
    }
    *//*?} else {*/
    try {
      stack = ModernNbt.decodeStack(registries(player), tag);
    } catch (RuntimeException err) {
      stack = null;
    } catch (LinkageError olderRuntime) {
      stack = legacyLoad(net.minecraft.world.item.ItemStack.class, tag);
    }
    /*?}*/
    return stack == null ? net.minecraft.world.item.ItemStack.EMPTY : stack;
  }

  /** Serializes a status effect to a base64 string. */
  public static String effectToBase64(
      ServerPlayer player, net.minecraft.world.effect.MobEffectInstance effect) {
    if (effect == null) return "";
    net.minecraft.nbt.CompoundTag tag;
    /*? if < 1.20.5 {*/
    /*try {
      tag = effect.save(new net.minecraft.nbt.CompoundTag());
    } catch (RuntimeException | LinkageError err) {
      tag = null;
    }
    *//*?} else {*/
    try {
      tag = ModernNbt.encodeEffect(registries(player), effect);
    } catch (RuntimeException err) {
      tag = null;
    } catch (LinkageError olderRuntime) {
      tag = legacySave(net.minecraft.world.effect.MobEffectInstance.class, effect);
    }
    /*?}*/
    return tag == null ? "" : compoundTagToBase64(tag);
  }

  /** Deserializes a status effect from a base64 string (null on failure). */
  public static net.minecraft.world.effect.MobEffectInstance effectFromBase64(
      ServerPlayer player, String data) {
    if (data == null || data.isBlank()) return null;
    net.minecraft.nbt.CompoundTag tag = compoundTagFromBase64(data);
    if (tag == null) return null;
    /*? if < 1.20.5 {*/
    /*try {
      return net.minecraft.world.effect.MobEffectInstance.load(tag);
    } catch (RuntimeException | LinkageError err) {
      return null;
    }
    *//*?} else {*/
    try {
      return ModernNbt.decodeEffect(registries(player), tag);
    } catch (RuntimeException err) {
      return null;
    } catch (LinkageError olderRuntime) {
      return legacyLoad(net.minecraft.world.effect.MobEffectInstance.class, tag);
    }
    /*?}*/
  }

  /**
   * Legacy {@code value.save(CompoundTag) -> CompoundTag}, located by signature: the only public
   * instance method of {@code type} taking and returning a CompoundTag. Ambiguity never guesses.
   */
  private static net.minecraft.nbt.CompoundTag legacySave(Class<?> type, Object value) {
    java.lang.reflect.Method found = null;
    for (java.lang.reflect.Method m : type.getMethods()) {
      if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
          || m.getParameterCount() != 1
          || m.getParameterTypes()[0] != net.minecraft.nbt.CompoundTag.class
          || m.getReturnType() != net.minecraft.nbt.CompoundTag.class) continue;
      if (found != null) return null;
      found = m;
    }
    if (found == null) return null;
    try {
      return (net.minecraft.nbt.CompoundTag) found.invoke(value, new net.minecraft.nbt.CompoundTag());
    } catch (ReflectiveOperationException | RuntimeException err) {
      return null;
    }
  }

  /**
   * Legacy static {@code Type.of/load(CompoundTag) -> Type}, located by signature (the only public
   * static method of {@code type} taking a CompoundTag and returning {@code type}).
   */
  private static <T> T legacyLoad(Class<T> type, net.minecraft.nbt.CompoundTag tag) {
    java.lang.reflect.Method found = null;
    for (java.lang.reflect.Method m : type.getMethods()) {
      if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())
          || m.getParameterCount() != 1
          || m.getParameterTypes()[0] != net.minecraft.nbt.CompoundTag.class
          || m.getReturnType() != type) continue;
      if (found != null) return null;
      found = m;
    }
    if (found == null) return null;
    try {
      return type.cast(found.invoke(null, tag));
    } catch (ReflectiveOperationException | RuntimeException err) {
      return null;
    }
  }

  /*? if >= 1.20.5 {*/
  /**
   * Codec-based (de)serialization, 1.20.5+. Isolated in its own class so the older runtimes of the
   * range jar fail with a catchable LinkageError at the call instead of at Compat's class load.
   */
  private static final class ModernNbt {
    private ModernNbt() {}

    private static com.mojang.serialization.DynamicOps<net.minecraft.nbt.Tag> ops(
        net.minecraft.core.HolderLookup.Provider registries) {
      return registries == null
          ? net.minecraft.nbt.NbtOps.INSTANCE
          : registries.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
    }

    private static net.minecraft.nbt.CompoundTag asCompound(
        java.util.Optional<net.minecraft.nbt.Tag> tag) {
      return tag.orElse(null) instanceof net.minecraft.nbt.CompoundTag compound ? compound : null;
    }

    static net.minecraft.nbt.CompoundTag encodeStack(
        net.minecraft.core.HolderLookup.Provider registries, net.minecraft.world.item.ItemStack stack) {
      return asCompound(
          net.minecraft.world.item.ItemStack.CODEC.encodeStart(ops(registries), stack).result());
    }

    static net.minecraft.world.item.ItemStack decodeStack(
        net.minecraft.core.HolderLookup.Provider registries, net.minecraft.nbt.CompoundTag tag) {
      return net.minecraft.world.item.ItemStack.CODEC.parse(ops(registries), tag).result().orElse(null);
    }

    static net.minecraft.nbt.CompoundTag encodeEffect(
        net.minecraft.core.HolderLookup.Provider registries,
        net.minecraft.world.effect.MobEffectInstance effect) {
      return asCompound(
          net.minecraft.world.effect.MobEffectInstance.CODEC.encodeStart(ops(registries), effect).result());
    }

    static net.minecraft.world.effect.MobEffectInstance decodeEffect(
        net.minecraft.core.HolderLookup.Provider registries, net.minecraft.nbt.CompoundTag tag) {
      return net.minecraft.world.effect.MobEffectInstance.CODEC.parse(ops(registries), tag).result().orElse(null);
    }
  }
  /*?}*/

  /**
   * The {@code namespace:path} id of a dimension key. {@code ResourceKey.toString()} is
   * {@code "ResourceKey[minecraft:dimension / minecraft:overworld]"} in every era, so this needs
   * neither ResourceLocation nor Identifier (renamed at 1.21.11) nor any mapping name.
   */
  public static String dimensionId(Object key) {
    if (key == null) return null;
    String s = String.valueOf(key);
    int sep = s.indexOf("/ ");
    int end = s.lastIndexOf(']');
    return sep >= 0 && end > sep ? s.substring(sep + 2, end).trim() : null;
  }

  /**
   * Finds a loaded level by its dimension id ({@code "minecraft:the_nether"}, or {@code "the_nether"}
   * for the minecraft namespace). Compares the levels the server actually has, so it works on every
   * mapping era and with datapack/mod dimensions. The previous lookup used YARN class names
   * ({@code net.minecraft.registry.RegistryKey/RegistryKeys}) that never exist at runtime - the
   * configured limbo dimension and the crash-recovery position were silently ignored everywhere.
   */
  public static ServerLevel findLevel(MinecraftServer server, String id) {
    if (server == null || id == null || id.isBlank()) return null;
    String wanted = id.trim().toLowerCase(java.util.Locale.ROOT);
    if (wanted.indexOf(':') < 0) wanted = "minecraft:" + wanted;
    try {
      for (ServerLevel level : server.getAllLevels())
        if (wanted.equals(dimensionId(level.dimension()))) return level;
    } catch (RuntimeException | LinkageError err) {
      return null;
    }
    return null;
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
    // Close the SERVER-SIDE menu first: sending only the close packet left the menu object alive
    // on the server, so another container (hopper/dispenser) could still feed it while the player
    // sat unauthenticated in the lobby.
    try {
      player.closeContainer();
    } catch (RuntimeException | LinkageError ignored) {
      // best-effort - the click guards remain the second layer
    }
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
    if (entity == null) return false;
    try {
      if (entity instanceof net.minecraft.world.entity.player.Player player) {
        player.stopFallFlying();
        return true;
      }
    } catch (RuntimeException | LinkageError ignored) {
      // fall back to the shared flag below
    }
    try {
      Class<?> entityClass = net.minecraft.world.entity.Entity.class;
      for (String name : new String[] {"setSharedFlag", "m_20115_", "method_5729"}) {
        try {
          java.lang.reflect.Method m = entityClass.getDeclaredMethod(name, int.class, boolean.class);
          m.setAccessible(true);
          m.invoke(entity, 7, false);
          return true;
        } catch (ReflectiveOperationException next) {
          // try the next name
        }
      }
    } catch (RuntimeException ignored) {
      // give up
    }
    return false;
  }

  /** Changes the player game mode (direct call - {@code ServerPlayer.setGameMode} in every era). */
  public static void changeGameMode(ServerPlayer player, net.minecraft.world.level.GameType mode) {
    try {
      player.setGameMode(mode);
    } catch (RuntimeException ignored) {
      // could not change game mode
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

  /**
   * Adds a player to the vanilla whitelist (authentication.auto-whitelist).
   *
   * <p>The previous lookup used YARN names ({@code net.minecraft.server.WhitelistEntry},
   * {@code getWhitelist}, {@code addToWhitelist}) that never exist at runtime, so auto-whitelist
   * silently did nothing on every loader and version. Direct, build-remapped call for the build
   * target; the GameProfile-keyed eras a range jar also covers (1.19-1.21.8 under the 1.21.11
   * build) are reached by signature shape - authlib's {@code GameProfile} is never remapped.
   */
  public static boolean addToWhitelist(MinecraftServer server, com.mojang.authlib.GameProfile profile) {
    if (server == null || profile == null) return false;
    try {
      /*? if >= 1.21.9 {*/
      server.getPlayerList().getWhiteList().add(
          new net.minecraft.server.players.UserWhiteListEntry(
              new net.minecraft.server.players.NameAndId(profile)));
      /*?} else {*/
      /*server.getPlayerList().getWhiteList().add(
          new net.minecraft.server.players.UserWhiteListEntry(profile));
      *//*?}*/
      return true;
    } catch (RuntimeException | LinkageError otherEra) {
      return legacyWhitelistAdd(server, profile);
    }
  }

  private static boolean legacyWhitelistAdd(
      MinecraftServer server, com.mojang.authlib.GameProfile profile) {
    try {
      Object list = server.getPlayerList().getWhiteList();
      Object entry =
          net.minecraft.server.players.UserWhiteListEntry.class
              .getConstructor(com.mojang.authlib.GameProfile.class)
              .newInstance(profile);
      // StoredUserList#add(V): the only single-arg method whose parameter is the type VARIABLE V
      // (erased to StoredUserEntry) - remove(StoredUserEntry<K>) is parameterized, not a variable.
      for (Class<?> c = list.getClass(); c != null && c != Object.class; c = c.getSuperclass())
        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
          if (m.getParameterCount() != 1
              || m.getParameterTypes()[0] != net.minecraft.server.players.StoredUserEntry.class
              || !(m.getGenericParameterTypes()[0] instanceof java.lang.reflect.TypeVariable))
            continue;
          m.setAccessible(true);
          m.invoke(list, entry);
          return true;
        }
      return false;
    } catch (ReflectiveOperationException | RuntimeException | LinkageError err) {
      return false;
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
