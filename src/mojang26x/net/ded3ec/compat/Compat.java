package net.ded3ec.compat;

import java.lang.reflect.Method;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
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

  /** Sends a chat message, with or without the action-bar overlay parameter. */
  public static void sendMessage(ServerPlayer player, Component text, boolean overlay) {
    try {
      Method m = ServerPlayer.class.getMethod("sendMessage", Component.class, boolean.class);
      m.invoke(player, text, overlay);
    } catch (ReflectiveOperationException e) {
      try {
        Method m = ServerPlayer.class.getMethod("sendMessage", Component.class);
        m.invoke(player, text);
      } catch (ReflectiveOperationException ignored) {
        // no sendMessage available
      }
    }
  }

  /** Adds a status effect (with or without the source entity parameter). */
  public static void addStatusEffect(LivingEntity entity, MobEffectInstance effect) {
    try {
      Method m =
          LivingEntity.class.getMethod(
              "addStatusEffect", MobEffectInstance.class, Entity.class);
      m.invoke(entity, effect, (Object) null);
    } catch (ReflectiveOperationException e) {
      try {
        Method m = LivingEntity.class.getMethod("addStatusEffect", MobEffectInstance.class);
        m.invoke(entity, effect);
      } catch (ReflectiveOperationException ignored) {
        // could not apply effect
      }
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

  /** Kills an entity (kill() on 1.16, kill(ServerWorld) on 1.20.5+). */
  public static void kill(LivingEntity entity) {
    try {
      Method m = LivingEntity.class.getMethod("kill", ServerLevel.class);
      m.invoke(entity, entity.level());
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
      ServerPlayer player,
      ServerLevel world,
      double x,
      double y,
      double z,
      float yaw,
      float pitch) {
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
   * Builds a world registry key for the given dimension identifier (registries moved packages in
   * 1.19.3+). The returned value is an opaque {@link Object} - use
   * {@link #getWorld(MinecraftServer, Object)} to resolve it.
   */
  public static Object worldKey(Identifier id) {
    try {
      Class<?> registryKey = resolveRegistryKeyClass();
      Class<?> registryKeys = Class.forName("net.minecraft.registry.RegistryKeys");
      Object world = registryKeys.getField("WORLD").get(null);
      return registryKey.getMethod("of", Class.class, Object.class).invoke(null, Level.class, world);
    } catch (ReflectiveOperationException e) {
      try {
        Class<?> registryKey = resolveRegistryKeyClass();
        Class<?> registry = Class.forName("net.minecraft.util.registry.Registry");
        Object worldKey = registry.getField("WORLD_KEY").get(null);
        return registryKey.getMethod("of", Class.class, Object.class).invoke(null, Level.class, worldKey);
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
  public static Object worldRegistryKey(Level world) {
    try {
      return world.getClass().getMethod("getRegistryKey").invoke(world);
    } catch (ReflectiveOperationException e) {
      return null;
    }
  }

  /** Resolves a server world from an opaque registry key. */
  public static ServerLevel getWorld(MinecraftServer server, Object key) {
    if (key == null) return null;
    try {
      for (Method m : MinecraftServer.class.getMethods()) {
        if (m.getName().equals("getWorld") && m.getParameterCount() == 1) {
          Object world = m.invoke(server, key);
          if (world instanceof ServerLevel serverWorld) return serverWorld;
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
    return entity instanceof net.minecraft.world.entity.vehicle.boat.Boat
        || entity instanceof net.minecraft.world.entity.vehicle.minecart.Minecart
        || entity instanceof net.minecraft.world.entity.animal.pig.Pig
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
  public static int getLatency(ServerPlayer player) {
    try {
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

  /** Creates a text component (Text.literal on 1.19+, LiteralText on older versions). */
  public static net.minecraft.network.chat.MutableComponent text(String value) {
    try {
      Method m = Component.class.getMethod("literal", String.class);
      return (net.minecraft.network.chat.MutableComponent) m.invoke(null, value);
    } catch (ReflectiveOperationException e) {
      try {
        Class<?> literalText = Class.forName("net.minecraft.text.LiteralText");
        return (net.minecraft.network.chat.MutableComponent) literalText.getConstructor(String.class).newInstance(value);
      } catch (ReflectiveOperationException ignored) {
        return null;
      }
    }
  }

  /** Sends a packet through a network handler (signature differs by version). */
  public static void sendPacket(
      net.minecraft.server.network.ServerGamePacketListenerImpl handler, Object packet) {
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
   * Sends the title/subtitle/fade packets. Uses the split packet API on 1.17+ and the combined
   * {@code TitleS2CPacket(Action, Text)} API on 1.16.
   */
  public static void sendTitle(
      net.minecraft.server.network.ServerGamePacketListenerImpl connection,
      Component title,
      Component subtitle,
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
        Object sub = subtitlePacket.getConstructor(Component.class).newInstance(subtitle);
        sendPacket(connection, sub);
      }
      if (title != null) {
        Object t = titlePacket.getConstructor(Component.class).newInstance(title);
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
          Object sub = titlePacket.getConstructor(actionClass, Component.class).newInstance(actionSub, subtitle);
          sendPacket(connection, sub);
        }
        if (title != null) {
          Object actionTitle = Enum.valueOf((Class<? extends Enum>) actionClass, "TITLE");
          Object t = titlePacket.getConstructor(actionClass, Component.class).newInstance(actionTitle, title);
          sendPacket(connection, t);
        }
      } catch (ReflectiveOperationException ignored) {
        // title API not available
      }
    }
  }

  /** Applies bold/italic/underline/strikethrough/obfuscate to a style (API differs by version). */
  public static net.minecraft.network.chat.Style applyStyleFlags(
      net.minecraft.network.chat.Style style,
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
  public static net.minecraft.network.chat.Style applyShadow(
      net.minecraft.network.chat.Style style, boolean shadow, int strength) {
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

  /** The player inventory (getter on 1.17+, public field on 1.16). */
  public static net.minecraft.world.entity.player.Inventory getInventory(
      net.minecraft.world.entity.player.Player player) {
    try {
      Method m = net.minecraft.world.entity.player.Player.class.getMethod("getInventory");
      return (net.minecraft.world.entity.player.Inventory) m.invoke(player);
    } catch (ReflectiveOperationException e) {
      try {
        return (net.minecraft.world.entity.player.Inventory)
            net.minecraft.world.entity.player.Player.class.getField("inventory").get(player);
      } catch (ReflectiveOperationException ignored) {
        return null;
      }
    }
  }

  /** The player abilities (getter on newer versions, field on older versions). */
  public static net.minecraft.world.entity.player.Abilities getAbilities(ServerPlayer player) {
    try {
      Method m = net.minecraft.world.entity.player.Player.class.getMethod("getAbilities");
      return (net.minecraft.world.entity.player.Abilities) m.invoke(player);
    } catch (ReflectiveOperationException e) {
      try {
        return (net.minecraft.world.entity.player.Abilities)
            net.minecraft.world.entity.player.Player.class.getField("abilities").get(player);
      } catch (ReflectiveOperationException ignored) {
        return null;
      }
    }
  }

  /** Changes the player game mode (method name stable across versions). */
  public static void changeGameMode(ServerPlayer player, net.minecraft.world.level.GameType mode) {
    try {
      Method m = ServerPlayer.class.getMethod("changeGameMode", net.minecraft.world.level.GameType.class);
      m.invoke(player, mode);
    } catch (ReflectiveOperationException ignored) {
      // could not change game mode
    }
  }

  /** Whether the player is currently an operator. */
  public static boolean isOperator(MinecraftServer server, ServerPlayer player) {
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

  /** Reads the configured operator permission level for the player. */
  public static int getOperatorLevel(MinecraftServer server, ServerPlayer player) {
    try {
      Class<?> configEntry = Class.forName("net.minecraft.server.PlayerManager$PlayerConfigEntry");
      Method m = server.getPlayerList().getClass().getMethod("getPermissionLevel", configEntry);
      Object entry = player.getClass().getMethod("getPlayerConfigEntry").invoke(player);
      return (Integer) m.invoke(server.getPlayerList(), entry);
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
}
