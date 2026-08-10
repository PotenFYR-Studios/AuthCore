package net.ded3ec.compat;

import java.lang.reflect.Method;
import java.util.Optional;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
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
   * <p>The intermediary id of {@code ServerCommandSource#getPlayer()} changed between
   * generations: {@code method_9207} on 1.16.5 - 1.18.2, {@code method_44023} on 1.19.4+.
   * A direct call compiled for one generation crashes with {@code NoSuchMethodError} on the
   * others, so the lookup tries every candidate id (plus the yarn name used in dev runs).
   */
  public static ServerPlayerEntity sourcePlayer(Object sourceOrContext) {
    Object source = sourceOrContext;
    if (sourceOrContext instanceof com.mojang.brigadier.context.CommandContext<?> context) {
      source = context.getSource();
    }
    if (source == null) return null;
    for (String name : new String[] {"getPlayer", "method_44023", "method_9207"}) {
      try {
        Object player = source.getClass().getMethod(name).invoke(source);
        if (player instanceof ServerPlayerEntity serverPlayer) return serverPlayer;
      } catch (ReflectiveOperationException | RuntimeException ignored) {
        // try the next id
      }
    }
    return null;
  }

  /** Sends a chat message, with or without the action-bar overlay parameter. */
  public static void sendMessage(ServerPlayerEntity player, Text text, boolean overlay) {
    try {
      Method m = ServerPlayerEntity.class.getMethod("sendMessage", Text.class, boolean.class);
      m.invoke(player, text, overlay);
    } catch (ReflectiveOperationException e) {
      try {
        Method m = ServerPlayerEntity.class.getMethod("sendMessage", Text.class);
        m.invoke(player, text);
      } catch (ReflectiveOperationException ignored) {
        // no sendMessage available
      }
    }
  }

  /** Adds a status effect (with or without the source entity parameter). */
  public static void addStatusEffect(LivingEntity entity, StatusEffectInstance effect) {
    try {
      Method m =
          LivingEntity.class.getMethod(
              "addStatusEffect", StatusEffectInstance.class, Entity.class);
      m.invoke(entity, effect, (Object) null);
    } catch (ReflectiveOperationException e) {
      try {
        Method m = LivingEntity.class.getMethod("addStatusEffect", StatusEffectInstance.class);
        m.invoke(entity, effect);
      } catch (ReflectiveOperationException ignored) {
        // could not apply effect
      }
    }
  }

  /** Removes a status effect (RegistryEntry-based on 1.19.4+, StatusEffect on older versions). */
  public static void removeStatusEffect(LivingEntity entity, StatusEffect effect) {
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

  /** Kills an entity (kill() on 1.16, kill(ServerWorld) on 1.20.5+). */
  public static void kill(LivingEntity entity) {
    try {
      Method m = LivingEntity.class.getMethod("kill", ServerWorld.class);
      m.invoke(entity, entity.getEntityWorld());
    } catch (ReflectiveOperationException e) {
      try {
        Method m = LivingEntity.class.getMethod("kill");
        m.invoke(entity);
      } catch (ReflectiveOperationException ignored) {
        // could not kill
      }
    }
  }

  /** Teleports a player (new 1.20.5+ signature or the classic 6-arg one). */
  public static boolean teleport(
      ServerPlayerEntity player,
      ServerWorld world,
      double x,
      double y,
      double z,
      float yaw,
      float pitch) {
    try {
      Method m =
          ServerPlayerEntity.class.getMethod(
              "teleport",
              ServerWorld.class,
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
            ServerPlayerEntity.class.getMethod(
                "teleport",
                ServerWorld.class,
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
  public static int getFrozenTicks(ServerPlayerEntity player) {
    try {
      Method m = LivingEntity.class.getMethod("getFrozenTicks");
      return (Integer) m.invoke(player);
    } catch (ReflectiveOperationException e) {
      return 0;
    }
  }

  /** Sets the frozen ticks (no-op on versions without the API). */
  public static void setFrozenTicks(ServerPlayerEntity player, int ticks) {
    try {
      Method m = LivingEntity.class.getMethod("setFrozenTicks", int.class);
      m.invoke(player, ticks);
    } catch (ReflectiveOperationException ignored) {
      // not available on this version
    }
  }

  /**
   * Builds a world registry key for the given dimension identifier (registries moved packages in
   * 1.19.3+). The returned value is an opaque {@link Object} - use
   * {@link #getWorld(MinecraftServer, Object)} to resolve it.
   */
  public static Object worldKey(Identifier id) {
    try {
      Class<?> registryKey = resolveRegistryKeyClass();
      Class<?> registryKeys = Class.forName("net.minecraft.registry.RegistryKeys");
      Object world = registryKeys.getField("WORLD").get(null);
      return registryKey.getMethod("of", Class.class, Object.class).invoke(null, World.class, world);
    } catch (ReflectiveOperationException e) {
      try {
        Class<?> registryKey = resolveRegistryKeyClass();
        Class<?> registry = Class.forName("net.minecraft.util.registry.Registry");
        Object worldKey = registry.getField("WORLD_KEY").get(null);
        return registryKey.getMethod("of", Class.class, Object.class).invoke(null, World.class, worldKey);
      } catch (ReflectiveOperationException ignored) {
        return null;
      }
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

  /** The registry key of the world the player is in (opaque value). */
  public static Object worldRegistryKey(World world) {
    try {
      return world.getClass().getMethod("getRegistryKey").invoke(world);
    } catch (ReflectiveOperationException e) {
      return null;
    }
  }

  /** Resolves a server world from an opaque registry key. */
  public static ServerWorld getWorld(MinecraftServer server, Object key) {
    if (key == null) return null;
    try {
      for (Method m : MinecraftServer.class.getMethods()) {
        if (m.getName().equals("getWorld") && m.getParameterCount() == 1) {
          Object world = m.invoke(server, key);
          if (world instanceof ServerWorld serverWorld) return serverWorld;
        }
      }
    } catch (ReflectiveOperationException ignored) {
      // fall through
    }
    return null;
  }

  /** Whether the given entity is considered "mountable" (horses, boats, camels, striders...). */
  public static boolean isMountable(Entity entity) {
    String name = entity.getClass().getName();
    return entity instanceof net.minecraft.entity.vehicle.BoatEntity
        || entity instanceof net.minecraft.entity.vehicle.MinecartEntity
        || entity instanceof net.minecraft.entity.passive.PigEntity
        || name.contains("HorseBaseEntity")
        || name.contains("AbstractHorseEntity")
        || name.endsWith(".CamelEntity")
        || name.endsWith(".StriderEntity")
        || name.endsWith(".DonkeyEntity")
        || name.endsWith(".MuleEntity");
  }

  /** Whether the given block is a dripleaf (only exists on 1.17+). */
  public static boolean isDripleaf(net.minecraft.block.BlockState state) {
    String name = state.getBlock().getClass().getName();
    return name.endsWith(".BigDripleafBlock") || name.endsWith(".SmallDripleafBlock");
  }

  /** Removes the player from the operator list (GameProfile-based on old versions). */
  public static void removeFromOperators(MinecraftServer server, ServerPlayerEntity player) {
    try {
      Class<?> configEntry = Class.forName("net.minecraft.server.PlayerManager$PlayerConfigEntry");
      Method m = server.getPlayerManager().getClass().getMethod("removeFromOperators", configEntry);
      Object entry = player.getClass().getMethod("getPlayerConfigEntry").invoke(player);
      m.invoke(server.getPlayerManager(), entry);
    } catch (ReflectiveOperationException e) {
      try {
        Method m =
            server.getPlayerManager().getClass().getMethod(
                "removeFromOperators", com.mojang.authlib.GameProfile.class);
        m.invoke(server.getPlayerManager(), player.getGameProfile());
      } catch (ReflectiveOperationException ignored) {
        // could not remove operator status
      }
    }
  }

  /** Adds the player back to the operator list with the stored level. */
  public static void addToOperators(MinecraftServer server, ServerPlayerEntity player, int level) {
    try {
      Class<?> configEntry = Class.forName("net.minecraft.server.PlayerManager$PlayerConfigEntry");
      Method m =
          server.getPlayerManager().getClass().getMethod(
              "addToOperators", configEntry, Optional.class, Optional.class);
      Object entry = player.getClass().getMethod("getPlayerConfigEntry").invoke(player);
      Class<?> leveledPredicate =
          Class.forName("net.minecraft.command.permission.LeveledPermissionPredicate");
      Object predicate = leveledPredicate.getConstructor(int.class).newInstance(level);
      m.invoke(server.getPlayerManager(), entry, Optional.of(predicate), Optional.of(true));
    } catch (ReflectiveOperationException e) {
      try {
        Method m =
            server.getPlayerManager().getClass().getMethod(
                "addToOperators", com.mojang.authlib.GameProfile.class, int.class);
        m.invoke(server.getPlayerManager(), player.getGameProfile(), level);
      } catch (ReflectiveOperationException e2) {
        try {
          Method m =
              server.getPlayerManager().getClass().getMethod(
                  "addToOperators", com.mojang.authlib.GameProfile.class);
          m.invoke(server.getPlayerManager(), player.getGameProfile());
        } catch (ReflectiveOperationException ignored) {
          // could not restore operator status
        }
      }
    }
  }

  /** Whether the player is currently gliding with an elytra (name differs by version). */
  public static boolean isGliding(ServerPlayerEntity player) {
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
  public static int getLatency(ServerPlayerEntity player) {
    try {
      Method m =
          net.minecraft.server.network.ServerPlayNetworkHandler.class.getMethod("getLatency");
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
  public static int getBottomY(World world) {
    try {
      Method m = World.class.getMethod("getBottomY");
      return (Integer) m.invoke(world);
    } catch (ReflectiveOperationException e) {
      return 0;
    }
  }

  /** Creates a text component (Text.literal on 1.19+, LiteralText on older versions). */
  public static net.minecraft.text.MutableText text(String value) {
    try {
      Method m = Text.class.getMethod("literal", String.class);
      return (net.minecraft.text.MutableText) m.invoke(null, value);
    } catch (ReflectiveOperationException e) {
      try {
        Class<?> literalText = Class.forName("net.minecraft.text.LiteralText");
        return (net.minecraft.text.MutableText) literalText.getConstructor(String.class).newInstance(value);
      } catch (ReflectiveOperationException ignored) {
        return null;
      }
    }
  }

  /** Sends a packet through a network handler (signature differs by version). */
  public static void sendPacket(
      net.minecraft.server.network.ServerPlayNetworkHandler handler, Object packet) {
    try {
      Class<?> listener = Class.forName("net.minecraft.network.listener.PacketSendListener");
      Method m = handler.getClass().getMethod("send", packet.getClass(), listener);
      m.invoke(handler, packet, (Object) null);
    } catch (ReflectiveOperationException e) {
      try {
        Method m = handler.getClass().getMethod("send", packet.getClass());
        m.invoke(handler, packet);
      } catch (ReflectiveOperationException ignored) {
        // could not send packet
      }
    }
  }

  /**
   * Sends a custom payload (plugin message) to the player's client. Uses the
   * {@code CustomPayloadS2CPacket(Identifier, PacketByteBuf)} constructor which exists on every
   * supported version (1.16 - 26.x) and never throws on failure - interop is best-effort.
   *
   * @param player the player to send to
   * @param channel the channel name, e.g. {@code "authcore:auth"}
   * @param data the raw payload bytes
   */
  public static void sendCustomPayload(
      net.minecraft.server.network.ServerPlayerEntity player, String channel, byte[] data) {
    try {
      if (player == null || channel == null || data == null || player.networkHandler == null)
        return;

      // Identifier for the channel (Identifier.tryParse on modern, new Identifier(String) on 1.16)
      Object identifier = null;
      try {
        Class<?> idClass = Class.forName("net.minecraft.util.Identifier");
        Method tryParse = idClass.getMethod("tryParse", String.class);
        identifier = tryParse.invoke(null, channel);
      } catch (ReflectiveOperationException ignored) {
        // tryParse not present on this version
      }
      if (identifier == null) {
        Class<?> idClass = Class.forName("net.minecraft.util.Identifier");
        identifier = idClass.getConstructor(String.class).newInstance(channel);
      }

      // PacketByteBuf wrapping a raw ByteBuf
      Object buf =
          Class.forName("net.minecraft.network.PacketByteBuf")
              .getConstructor(io.netty.buffer.ByteBuf.class)
              .newInstance(io.netty.buffer.Unpooled.buffer(data.length));
      Method writeBytes =
          buf.getClass().getMethod("writeBytes", byte[].class);
      writeBytes.invoke(buf, (Object) data);

      // CustomPayloadS2CPacket(Identifier, PacketByteBuf) - kept as a compat constructor on
      // every version, so this single path covers 1.16 through 26.x.
      Object packet =
          Class.forName("net.minecraft.network.packet.s2c.play.CustomPayloadS2CPacket")
              .getConstructor(
                  Class.forName("net.minecraft.util.Identifier"),
                  Class.forName("net.minecraft.network.PacketByteBuf"))
              .newInstance(identifier, buf);

      sendPacket(player.networkHandler, packet);
    } catch (ReflectiveOperationException | RuntimeException ignored) {
      // interop is best-effort - never break gameplay
    }
  }

  /**
   * Sends the title/subtitle/fade packets. Uses the split packet API on 1.17+ and the combined
   * {@code TitleS2CPacket(Action, Text)} API on 1.16.
   */
  public static void sendTitle(
      net.minecraft.server.network.ServerPlayNetworkHandler connection,
      Text title,
      Text subtitle,
      int fadeInTicks,
      int stayTicks,
      int fadeOutTicks) {
    try {
      // 1.17+: TitleS2CPacket(Text), SubtitleS2CPacket(Text), TitleFadeS2CPacket(int,int,int)
      Class<?> titlePacket = Class.forName("net.minecraft.network.packet.s2c.play.TitleS2CPacket");
      Class<?> subtitlePacket = Class.forName("net.minecraft.network.packet.s2c.play.SubtitleS2CPacket");
      Class<?> fadePacket = Class.forName("net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket");
      Object fade = fadePacket.getConstructor(int.class, int.class, int.class)
          .newInstance(fadeInTicks, stayTicks, fadeOutTicks);
      sendPacket(connection, fade);
      if (subtitle != null) {
        Object sub = subtitlePacket.getConstructor(Text.class).newInstance(subtitle);
        sendPacket(connection, sub);
      }
      if (title != null) {
        Object t = titlePacket.getConstructor(Text.class).newInstance(title);
        sendPacket(connection, t);
      }
    } catch (ReflectiveOperationException e) {
      try {
        // 1.16: TitleS2CPacket(Action, Text) + TitleS2CPacket(int,int,int) for timings
        Class<?> titlePacket = Class.forName("net.minecraft.network.packet.s2c.play.TitleS2CPacket");
        Class<?> actionClass = Class.forName("net.minecraft.network.packet.s2c.play.TitleS2CPacket$Action");
        Object times = titlePacket.getConstructor(int.class, int.class, int.class)
            .newInstance(fadeInTicks, stayTicks, fadeOutTicks);
        sendPacket(connection, times);
        if (subtitle != null) {
          Object actionSub = Enum.valueOf((Class<? extends Enum>) actionClass, "SUBTITLE");
          Object sub = titlePacket.getConstructor(actionClass, Text.class).newInstance(actionSub, subtitle);
          sendPacket(connection, sub);
        }
        if (title != null) {
          Object actionTitle = Enum.valueOf((Class<? extends Enum>) actionClass, "TITLE");
          Object t = titlePacket.getConstructor(actionClass, Text.class).newInstance(actionTitle, title);
          sendPacket(connection, t);
        }
      } catch (ReflectiveOperationException ignored) {
        // title API not available
      }
    }
  }

  /** Applies bold/italic/underline/strikethrough/obfuscate to a style (API differs by version). */
  public static net.minecraft.text.Style applyStyleFlags(
      net.minecraft.text.Style style,
      boolean bold,
      boolean italic,
      boolean underline,
      boolean strikethrough,
      boolean obfuscate) {
    try {
      Method withBold = Style.class.getMethod("withBold", Boolean.class);
      Method withItalic = Style.class.getMethod("withItalic", Boolean.class);
      Method withUnderline = Style.class.getMethod("withUnderline", Boolean.class);
      style = (Style) withBold.invoke(style, bold);
      style = (Style) withItalic.invoke(style, italic);
      style = (Style) withUnderline.invoke(style, underline);
      try {
        Method withStrike = Style.class.getMethod("withStrikethrough", Boolean.class);
        style = (Style) withStrike.invoke(style, strikethrough);
      } catch (ReflectiveOperationException ignored) {
        // not available on this version
      }
      try {
        Method withObf = Style.class.getMethod("withObfuscated", Boolean.class);
        style = (Style) withObf.invoke(style, obfuscate);
      } catch (ReflectiveOperationException ignored) {
        // not available on this version
      }
    } catch (ReflectiveOperationException ignored) {
      // could not apply style flags
    }
    return style;
  }

  /** Applies the shadow settings (1.19.3+ API; no-op on older versions). */
  public static net.minecraft.text.Style applyShadow(
      net.minecraft.text.Style style, boolean shadow, int strength) {
    try {
      Method without = Style.class.getMethod("withoutShadow");
      Method withColor = Style.class.getMethod("withShadowColor", int.class);
      if (!shadow) return (Style) without.invoke(style);
      return (Style) withColor.invoke(style, strength);
    } catch (ReflectiveOperationException e) {
      return style;
    }
  }

  /** Reads the player yaw (tickDelta parameter on older versions). */
  public static float getYaw(ServerPlayerEntity player) {
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
  public static float getPitch(ServerPlayerEntity player) {
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

  /** The player inventory (getter on 1.17+, public field on 1.16). */
  public static net.minecraft.entity.player.PlayerInventory getInventory(
      net.minecraft.entity.player.PlayerEntity player) {
    try {
      Method m = net.minecraft.entity.player.PlayerEntity.class.getMethod("getInventory");
      return (net.minecraft.entity.player.PlayerInventory) m.invoke(player);
    } catch (ReflectiveOperationException e) {
      try {
        return (net.minecraft.entity.player.PlayerInventory)
            net.minecraft.entity.player.PlayerEntity.class.getField("inventory").get(player);
      } catch (ReflectiveOperationException ignored) {
        return null;
      }
    }
  }

  /** The player abilities (getter on newer versions, field on older versions). */
  public static net.minecraft.entity.player.PlayerAbilities getAbilities(ServerPlayerEntity player) {
    try {
      Method m = net.minecraft.entity.player.PlayerEntity.class.getMethod("getAbilities");
      return (net.minecraft.entity.player.PlayerAbilities) m.invoke(player);
    } catch (ReflectiveOperationException e) {
      try {
        return (net.minecraft.entity.player.PlayerAbilities)
            net.minecraft.entity.player.PlayerEntity.class.getField("abilities").get(player);
      } catch (ReflectiveOperationException ignored) {
        return null;
      }
    }
  }

  /** Changes the player game mode (method name stable across versions). */
  public static void changeGameMode(ServerPlayerEntity player, net.minecraft.world.GameMode mode) {
    try {
      Method m = ServerPlayerEntity.class.getMethod("changeGameMode", net.minecraft.world.GameMode.class);
      m.invoke(player, mode);
    } catch (ReflectiveOperationException ignored) {
      // could not change game mode
    }
  }

  /** Whether the player is currently an operator. */
  public static boolean isOperator(MinecraftServer server, ServerPlayerEntity player) {
    try {
      Class<?> configEntry = Class.forName("net.minecraft.server.PlayerManager$PlayerConfigEntry");
      Method m = server.getPlayerManager().getClass().getMethod("isOperator", configEntry);
      Object entry = player.getClass().getMethod("getPlayerConfigEntry").invoke(player);
      return (Boolean) m.invoke(server.getPlayerManager(), entry);
    } catch (ReflectiveOperationException e) {
      try {
        Method m =
            server.getPlayerManager().getClass().getMethod(
                "isOperator", com.mojang.authlib.GameProfile.class);
        return (Boolean) m.invoke(server.getPlayerManager(), player.getGameProfile());
      } catch (ReflectiveOperationException ignored) {
        return false;
      }
    }
  }

  /** Reads the configured operator permission level for the player. */
  public static int getOperatorLevel(MinecraftServer server, ServerPlayerEntity player) {
    try {
      Class<?> configEntry = Class.forName("net.minecraft.server.PlayerManager$PlayerConfigEntry");
      Method m = server.getPlayerManager().getClass().getMethod("getPermissionLevel", configEntry);
      Object entry = player.getClass().getMethod("getPlayerConfigEntry").invoke(player);
      return (Integer) m.invoke(server.getPlayerManager(), entry);
    } catch (ReflectiveOperationException e) {
      return 0;
    }
  }

  /** The Fabric loader version string (API renamed across loader versions). */
  public static String getLoaderVersion() {
    try {
      var loader = net.fabricmc.loader.api.FabricLoader.getInstance();
      Optional<?> container =
          (Optional<?>)
              loader.getClass().getMethod("getModContainer", String.class).invoke(loader, "fabricloader");
      if (container.isPresent()) {
        Object metadata = container.get().getClass().getMethod("getMetadata").invoke(container.get());
        Object version = metadata.getClass().getMethod("getVersion").invoke(metadata);
        return (String) version.getClass().getMethod("getFriendlyString").invoke(version);
      }
      return "unknown";
    } catch (ReflectiveOperationException e) {
      return "unknown";
    }
  }

  /** The raw game version string (API renamed across loader versions). */
  public static String getGameVersion() {
    try {
      var loader = net.fabricmc.loader.api.FabricLoader.getInstance();
      try {
        return (String) loader.getClass().getMethod("getRawGameVersion").invoke(loader);
      } catch (ReflectiveOperationException e) {
        return (String) loader.getClass().getMethod("getGameVersion").invoke(loader);
      }
    } catch (Exception e) {
      return "unknown";
    }
  }

  /** Adds a player to the vanilla whitelist (API changed across versions). */
  public static boolean addToWhitelist(MinecraftServer server, com.mojang.authlib.GameProfile profile) {
    try {
      // 1.20.5+: Whitelist.add(WhitelistEntry)
      Object whitelist = server.getPlayerManager().getClass().getMethod("getWhitelist").invoke(server.getPlayerManager());
      Class<?> entryClass = Class.forName("net.minecraft.server.WhitelistEntry");
      Object entry = entryClass.getConstructor(com.mojang.authlib.GameProfile.class).newInstance(profile);
      whitelist.getClass().getMethod("add", entryClass).invoke(whitelist, entry);
      return true;
    } catch (ReflectiveOperationException e) {
      try {
        // 1.16-1.20.4: PlayerManager.addToWhitelist(GameProfile)
        Method m = server.getPlayerManager().getClass().getMethod("addToWhitelist", com.mojang.authlib.GameProfile.class);
        m.invoke(server.getPlayerManager(), profile);
        return true;
      } catch (ReflectiveOperationException ignored) {
        return false;
      }
    }
  }

  // ------------------------------------------------------------------
  // ActionResult constants
  //
  // On 1.20.4 and older ActionResult is an enum; from 1.20.5/1.21 it is a class with nested
  // subclasses (ActionResult$Pass etc.). The enum constants became static fields of the nested
  // types, but the stable INTERMEDIARY field ids are unchanged (field_5811 = PASS,
  // field_5814 = FAIL), so reflection on the id works on every classic version.
  // ------------------------------------------------------------------

  private static java.lang.reflect.Field ACTION_RESULT_PASS;
  private static java.lang.reflect.Field ACTION_RESULT_FAIL;

  private static java.lang.reflect.Field resolveActionResultField(String runtimeFieldName) {
    try {
      return net.minecraft.util.ActionResult.class.getField(runtimeFieldName);
    } catch (NoSuchFieldException e) {
      return null;
    }
  }

  /** Returns the version-independent {@code ActionResult.PASS} instance. */
  public static net.minecraft.util.ActionResult actionResultPass() {
    if (ACTION_RESULT_PASS == null) ACTION_RESULT_PASS = resolveActionResultField("field_5811");
    try {
      return (net.minecraft.util.ActionResult) ACTION_RESULT_PASS.get(null);
    } catch (ReflectiveOperationException | NullPointerException e) {
      return null;
    }
  }

  /** Returns the version-independent {@code ActionResult.FAIL} instance. */
  public static net.minecraft.util.ActionResult actionResultFail() {
    if (ACTION_RESULT_FAIL == null) ACTION_RESULT_FAIL = resolveActionResultField("field_5814");
    try {
      return (net.minecraft.util.ActionResult) ACTION_RESULT_FAIL.get(null);
    } catch (ReflectiveOperationException | NullPointerException e) {
      return null;
    }
  }
}
