package net.ded3ec.authcore.models;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import net.ded3ec.authcore.AuthCore;
import net.ded3ec.authcore.utils.Logger;
import net.ded3ec.authcore.utils.Misc;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import org.jspecify.annotations.Nullable;

/**
 * Represents a lobby system for managing player authentication and queuing in the AuthCore mod.
 * This class handles locking players into a restricted lobby mode, teleporting them to designated
 * locations, managing timeouts, and restoring player states upon unlocking. It supports different
 * configurations for registered and unregistered users, including limbo and hub locations.
 */
public class Lobby {

  /** Collection of jailed users mapped by their usernames. */
  public static Map<String, Lobby> users = new HashMap<>();

  /** Snapshot of the player's state before entering the lobby. */
  public Snapshot snapshot;

  /** User model associated with this lobby instance. */
  public User user;

  /** Scheduled task for handling lobby session timeout. */
  private ScheduledFuture<?> lobbyTimeoutTask;

  /** Scheduled task for sending reminders in restricted mode. */
  private ScheduledFuture<?> lobbyIntervalTask;

  /** Block position for unregistered users in the lobby. */
  private BlockPos lobbyRegisterPosition;

  /** Block position for registered users in the lobby. */
  private BlockPos lobbyLoginPosition;

  /** General lobby position for the player. */
  private BlockPos lobbyPosition;

  /**
   * Constructs a new Lobby instance for the given user.
   *
   * @param user the user associated with this lobby
   */
  public Lobby(User user) {
    this.user = user;
  }

  /**
   * Locks the player into the lobby/queue/restricted mode. This method creates a snapshot of the
   * player's current state, teleports them to the appropriate lobby location, sends a welcome
   * message, and initiates timeout handling if configured.
   */
  public void lock() {

    ServerPlayerEntity player = user.player.get();

    this.snapshot = new Snapshot(player);
    this.teleportToLobby();
    Logger.toUser(true, user.handler, AuthCore.messages.welcomeJailUser);

    if (AuthCore.config.lobby.timeout.enabled) this.handleTimeout();

    Lobby.users.put(this.user.username, this);
  }

  /**
   * Teleports the player to the appropriate lobby location based on their registration status and
   * configuration. For unregistered users, teleports to the limbo location if enabled; for
   * registered users, to the hub location. Ensures safe teleportation by adjusting positions to
   * avoid suffocation or unsafe landing.
   */
  public void teleportToLobby() {

    MinecraftServer server = this.user.server.get();
    ServerPlayerEntity player = this.user.player.get();
    ServerWorld world = player.getEntityWorld();
    BlockPos blockPos = player.getBlockPos().toImmutable();

    if (AuthCore.config.lobby.limboConfig.enabled && !this.user.isRegistered.get()) {
      String raw = AuthCore.config.lobby.limboConfig.location.dimension.trim().toLowerCase();
      Identifier id = Identifier.of(raw);
      RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, id);

      world = server.getWorld(key);
      if (world == null) return;

      if (this.lobbyRegisterPosition == null) {
        blockPos =
            BlockPos.ofFloored(
                AuthCore.config.lobby.limboConfig.location.x,
                AuthCore.config.lobby.limboConfig.location.y,
                AuthCore.config.lobby.limboConfig.location.z);
        blockPos = this.snapshot.getTeleportPos(player, blockPos.toImmutable(), world);
        this.lobbyRegisterPosition = blockPos.toImmutable();
      } else blockPos = this.lobbyRegisterPosition.toImmutable();

    } else if (AuthCore.config.lobby.hubConfig.enabled && this.user.isRegistered.get()) {
      String raw = AuthCore.config.lobby.hubConfig.location.dimension.trim().toLowerCase();
      Identifier id = Identifier.of(raw);
      RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, id);

      world = server.getWorld(key);
      if (world == null) return;

      if (this.lobbyLoginPosition == null) {
        blockPos =
            BlockPos.ofFloored(
                AuthCore.config.lobby.hubConfig.location.x,
                AuthCore.config.lobby.hubConfig.location.y,
                AuthCore.config.lobby.hubConfig.location.z);
        blockPos = this.snapshot.getTeleportPos(player, blockPos.toImmutable(), world);
        this.lobbyLoginPosition = blockPos.toImmutable();
      } else blockPos = this.lobbyLoginPosition.toImmutable();
    }

    if (this.lobbyPosition != null) blockPos = this.lobbyPosition.toImmutable();
    else this.lobbyPosition = blockPos.toImmutable();

    this.snapshot.teleport(player, blockPos.toImmutable(), world);
  }

  /**
   * Unlocks the player from the lobby/queue/restricted mode. Restores the player's previous state
   * using the snapshot, removes them from the jailed users collection, and cancels any ongoing
   * tasks. Only proceeds if the player is currently in lobby mode.
   */
  public void unlock() {
    if (!this.user.isInLobby.get()) return;

    ServerPlayerEntity player = this.user.player.get();

    Lobby.users.remove(this.user.username);

    this.snapshot.reset(player);

    Logger.debug(
        false, "{} has been taken out from the lobby/restricted mode!", this.user.username);

    this.cancel();
  }

  /**
   * Cancels the lobby timeout and interval tasks associated with this lobby instance. Logs the
   * cancellation for debugging purposes.
   */
  public void cancel() {

    if (this.lobbyIntervalTask != null) this.lobbyIntervalTask.cancel(false);
    if (this.lobbyTimeoutTask != null) this.lobbyTimeoutTask.cancel(false);

    Logger.debug(false, "{}'s Lobby interval and timeout has been cancelled!", this.user.username);
  }

  /**
   * Checks if the player has moved outside their lobby position based on new coordinates. Compares
   * the new X and Z coordinates with the player's current position to detect movement.
   *
   * @param newX the new X coordinate
   * @param newZ the new Z coordinate
   * @return true if the player has moved in X or Z direction, false otherwise
   */
  public boolean isOutsideOfLobbyPos(double newX, double newZ) {
    // Movement by jailed user event detection!
    double oldX = this.user.player.get().getX();
    double oldZ = this.user.player.get().getZ();

    // If player actually moved in X/Z
    return (Double.compare(newX, oldX) != 0) || (Double.compare(newZ, oldZ) != 0);
  }

  /**
   * Handles the timeout logic for the lobby session. Sets up a timeout task based on the player's
   * latency and configuration, and optionally an interval task for sending reminder messages.
   * Adjusts timeout duration according to latency thresholds.
   */
  private void handleTimeout() {

    int loginTimeoutMs = AuthCore.config.lobby.timeout.loginTimeoutMs;

    if (user.handler.getLatency() >= 600)
      loginTimeoutMs = AuthCore.config.lobby.timeout.loginTimeoutAbove600LatencyMs;
    else if (user.handler.getLatency() >= 400)
      loginTimeoutMs = AuthCore.config.lobby.timeout.loginTimeoutAbove400LatencyMs;
    else if (user.handler.getLatency() >= 200)
      loginTimeoutMs = AuthCore.config.lobby.timeout.loginTimeoutAbove200LatencyMs;

    int _loginTimeoutMs = loginTimeoutMs;

    if (loginTimeoutMs > 0)
      this.lobbyTimeoutTask =
          Misc.TimeManager.setTimeout(
              () -> {
                if (user.isOnline && this.user.isInLobby.get())
                  Logger.toKick(
                      false,
                      this.user.handler,
                      AuthCore.messages.authenticationTimeoutExpired,
                      Misc.TimeConverter.toDuration(_loginTimeoutMs));
              },
              loginTimeoutMs);

    if (AuthCore.config.session.loginReminderIntervalMs > 0)
      if (!user.isRegistered.get())
        this.lobbyIntervalTask =
            Misc.TimeManager.setInterval(
                () -> {
                  if (this.user.isOnline && this.user.isInLobby.get())
                    Logger.toUser(
                        true, this.user.handler, AuthCore.messages.registerCommandReminderInterval);
                },
                AuthCore.config.session.loginReminderIntervalMs);
      else
        this.lobbyIntervalTask =
            Misc.TimeManager.setInterval(
                () -> {
                  if (this.user.isOnline && this.user.isInLobby.get())
                    Logger.toUser(
                        true, this.user.handler, AuthCore.messages.loginCommandReminderInterval);
                },
                AuthCore.config.session.loginReminderIntervalMs);
  }

  /**
   * Represents a snapshot of a player's state before entering the lobby. This class captures
   * various player attributes such as inventory, effects, health, position, and game mode, allowing
   * for complete restoration upon exiting the lobby. It also applies lobby-specific effects like
   * invisibility, blindness, and invulnerability based on configuration.
   */
  public static class Snapshot {

    private final ArrayList<ItemStack> inventory;

    private final ArrayList<StatusEffectInstance> effects;

    private final int foodLevel;

    private final float saturation;

    private final int xpLevel;

    private final float xpProgress;

    private final int totalXp;

    private final BlockPos blockPos;

    private final float health;

    private final RegistryKey<World> dimensionKey;

    private final int fireTicks;

    private final int frozenTicks;

    private final double fallDistance;

    private final GameMode gameMode;

    private final @Nullable Team team;

    /**
     * Creates a snapshot of the player's current state. Captures inventory, effects, health,
     * experience, position, and other attributes. Applies lobby effects such as invisibility,
     * blindness, and invulnerability if configured.
     *
     * @param player the server player entity to snapshot
     */
    public Snapshot(ServerPlayerEntity player) {

      this.blockPos = player.getBlockPos();
      this.dimensionKey = player.getEntityWorld().getRegistryKey();

      this.effects = new ArrayList<>(player.getStatusEffects());

      this.inventory = new ArrayList<>();

      if (AuthCore.config.lobby.hideInventory) {
        for (int i = 0; i < player.getInventory().size(); i++) {
          ItemStack stack = player.getInventory().getStack(i);
          this.inventory.add(stack.copy());
        }
      }

      this.health = player.getHealth();
      this.foodLevel = player.getHungerManager().getFoodLevel();
      this.saturation = player.getHungerManager().getSaturationLevel();
      this.xpLevel = player.experienceLevel;
      this.xpProgress = player.experienceProgress;
      this.totalXp = player.totalExperience;

      this.fireTicks = player.getFireTicks();
      this.frozenTicks = player.getFrozenTicks();
      this.fallDistance = player.fallDistance;
      this.gameMode = player.interactionManager.getGameMode();
      this.team = player.getScoreboardTeam();

      player.clearStatusEffects();

      if (AuthCore.config.lobby.invisibleUnauthorized)
        player.addStatusEffect(
            new StatusEffectInstance(
                StatusEffects.INVISIBILITY, Integer.MAX_VALUE, 1, false, false),
            null);

      if (AuthCore.config.lobby.applyBlindnessEffect)
        player.addStatusEffect(
            new StatusEffectInstance(StatusEffects.BLINDNESS, Integer.MAX_VALUE, 1, false, false),
            null);

      if (AuthCore.config.lobby.preventDamage) {
        player.setInvulnerable(true);
        player.setHealth(player.getMaxHealth());
      }
    }

    /**
     * Resets the player to their original state captured in this snapshot. Restores inventory,
     * effects, health, experience, position, and other attributes. Teleports the player back to
     * their original location in the original dimension.
     *
     * @param player the server player entity to reset
     */
    public void reset(ServerPlayerEntity player) {
      MinecraftServer server = player.getEntityWorld().getServer();
      if (server == null) return;

      player.clearStatusEffects();
      player.setInvulnerable(false);

      if (AuthCore.config.lobby.hideInventory && inventory != null && !inventory.isEmpty()) {
        player.getInventory().clear();

        for (int i = 0; i < inventory.size(); i++)
          player.getInventory().setStack(i, inventory.get(i).copy());

        player.getInventory().markDirty();
        player.currentScreenHandler.sendContentUpdates();
        player.playerScreenHandler.updateToClient();
      }

      for (StatusEffectInstance effect : effects)
        player.addStatusEffect(new StatusEffectInstance(effect));

      ServerWorld world = server.getWorld(this.dimensionKey);
      if (world == null) return;

      BlockPos pos = this.getTeleportPos(player, this.blockPos.toImmutable(), world);
      this.teleport(player, pos.toImmutable(), world);

      player.setHealth(health);
      player.getHungerManager().setFoodLevel(foodLevel);
      player.getHungerManager().setSaturationLevel(saturation);
      player.experienceLevel = xpLevel;
      player.experienceProgress = xpProgress;
      player.totalExperience = totalXp;

      player.setFireTicks(fireTicks);
      player.setFrozenTicks(frozenTicks);
      player.fallDistance = fallDistance;
      player.changeGameMode(gameMode);
    }

    /**
     * Teleports the player to the specified position in the given world. Adjusts the position
     * slightly to center the player on the block.
     *
     * @param player the server player entity to teleport
     * @param pos the target block position
     * @param world the target server world
     */
    private void teleport(ServerPlayerEntity player, BlockPos pos, ServerWorld world) {
      MinecraftServer server = player.getEntityWorld().getServer();

      if (server != null && world != null)
        player.teleport(
            world,
            pos.getX() + 0.5,
            pos.getY() + 0.5,
            pos.getZ() + 0.5,
            EnumSet.noneOf(PositionFlag.class),
            player.getYaw(),
            player.getPitch(),
            true);
    }

    /**
     * Calculates a safe teleport position for the player based on their current state and the
     * target position. Adjusts for various player conditions like crouching, swimming, flying,
     * etc., to prevent suffocation or unsafe landing.
     *
     * @param player the server player entity
     * @param pos the initial target block position
     * @param world the server world
     * @return a safe block position for teleportation
     */
    private BlockPos getTeleportPos(ServerPlayerEntity player, BlockPos pos, ServerWorld world) {
      if (player.isSpectator()) return pos.toImmutable();

      BlockPos candidate = pos.toImmutable();

      if (player.getPose() == EntityPose.CROUCHING) {
        BlockPos safe = getGroundAbove(world, candidate);
        if (safe != null) candidate = safe.up();
      } else if (player.isSwimming()
          || player.isTouchingWater()
          || !(world.getBlockState(candidate).getCollisionShape(world, candidate)).isEmpty()
          || player.getPose() == EntityPose.SWIMMING) {
        BlockPos surface = findWaterSurface(world, candidate);
        if (surface != null) candidate = surface.up();
      } else if (player.getAbilities().flying
          || player.isGliding()
          || player.hasVehicle()
          || !player.isOnGround()
          || !world.getBlockState(candidate).isSolidBlock(world, candidate)
          || !(world.getBlockState(candidate).isAir()
              && world.getBlockState(candidate.up()).isAir())) {
        BlockPos ground = getGroundBelow(world, candidate);
        if (ground != null) candidate = ground.up();
      }

      if (player.isInsideWall() || isSuffocate(world, candidate)) {
        BlockPos safe = getGroundAbove(world, candidate);
        if (safe != null) candidate = safe.up();
      }

      return candidate.toImmutable();
    }

    /**
     * Find water surface above current position.
     *
     * @param world the server world
     * @param origin the origin block position
     * @return the water surface position or null
     */
    private BlockPos findWaterSurface(ServerWorld world, BlockPos origin) {
      BlockPos.Mutable check = origin.mutableCopy();

      int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, origin);

      while (check.getY() <= topY && world.getBlockState(check).isOf(Blocks.WATER))
        check.move(0, 1, 0);

      if (check.getY() > topY) return null;
      return check.down().toImmutable();
    }

    /**
     * Find nearest ground block below player.
     *
     * @param world the server world
     * @param origin the origin block position
     * @return the ground position or null
     */
    private BlockPos getGroundBelow(ServerWorld world, BlockPos origin) {
      BlockPos.Mutable check = origin.mutableCopy();
      while (check.getY() > world.getBottomY()) {
        check.move(0, -1, 0);
        BlockState state = world.getBlockState(check);
        if (state.isSolidBlock(world, check)
            && world.getBlockState(check.up()).isAir()
            && world.getBlockState(check.up(2)).isAir()) return check.toImmutable();
      }

      return null;
    }

    /**
     * Find nearest ground block above player.
     *
     * @param world the server world
     * @param origin the origin block position
     * @return the ground position or null
     */
    private BlockPos getGroundAbove(ServerWorld world, BlockPos origin) {
      BlockPos.Mutable check = origin.mutableCopy();
      int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, origin);

      while (check.getY() < topY) {
        check.move(0, 1, 0);
        BlockState state = world.getBlockState(check);
        if (state.isSolidBlock(world, check)
            && world.getBlockState(check.up()).isAir()
            && world.getBlockState(check.up(2)).isAir()) return check.toImmutable();
      }

      return null;
    }

    /**
     * Check if breaking/occupying this position would cause suffocation.
     *
     * @param world the server world
     * @param pos the block position
     * @return true if suffocation would occur
     */
    private boolean isSuffocate(ServerWorld world, BlockPos pos) {
      BlockState state = world.getBlockState(pos);
      boolean solidHere = state.isSolidBlock(world, pos);
      boolean clearance =
          world.getBlockState(pos.up()).isAir() && world.getBlockState(pos.up(2)).isAir();
      return solidHere && !clearance;
    }
  }
}
