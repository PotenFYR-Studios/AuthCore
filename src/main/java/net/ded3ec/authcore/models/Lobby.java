package net.ded3ec.authcore.models;

import java.util.*;
import net.ded3ec.authcore.AuthCore;
import net.ded3ec.authcore.utils.Logger;
import net.ded3ec.authcore.utils.Misc;
import net.minecraft.block.*;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.command.permission.LeveledPermissionPredicate;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

/**
 * Manages the lobby system for player authentication and queuing within the AuthCore mod. *
 *
 * <p>This class handles "locking" players into a restricted state, managing teleports to limbo or
 * hub locations, handling session timeouts, and restoring the player's original state (Snapshot)
 * upon successful authentication.
 */
public class Lobby {

  /**
   * A global registry of users currently restricted within a lobby instance, mapped by username.
   */
  public static Map<String, Lobby> users = new HashMap<>();

  /** The saved state of the player before they were moved into the lobby. */
  public Snapshot snapshot;

  /** The user data model associated with this lobby session. */
  public User user;

  /** Task handle for the scheduled session expiration/kick. */
  private int lobbyTimeoutTask;

  /** Task handle for periodic login/registration reminders. */
  private int lobbyIntervalTask;

  /** The specific position within the lobby assigned to the player. */
  private BlockPos position;

  /**
   * Constructs a new Lobby instance for a specific user.
   *
   * @param user The user to be managed by this lobby instance.
   */
  public Lobby(User user) {
    this.user = user;
  }

  /**
   * Locks the player into the restricted lobby state. *
   *
   * <p>This method performs the following:
   *
   * <ul>
   *   <li>Captures a {@link Snapshot} of the player's current state.
   *   <li>Teleports the player to the configured lobby/limbo location.
   *   <li>Sends a welcome prompt and initiates the timeout/reminder tasks.
   *   <li>Registers the user in the global {@code users} map.
   * </ul>
   */
  public void lock() {

    ServerPlayerEntity player = user.player.get();

    this.snapshot = new Snapshot(player);
    this.handleTeleport();
    Logger.toUser(true, user.handler, AuthCore.messages.promptUserWelcomeLobbyUser);

    if (AuthCore.config.lobby.timeout.enabled) this.handleTimeout();

    Lobby.users.put(this.user.username, this);
  }

  /**
   * Determines the target destination and teleports the player based on configuration. *
   *
   * <p>If Limbo is enabled and applicable (e.g., for unregistered users), it calculates a safe
   * position in the designated dimension. It uses the snapshot logic to prevent players from
   * spawning in walls or unsafe locations.
   */
  public void handleTeleport() {

    MinecraftServer server = this.user.server.get();
    ServerPlayerEntity player = this.user.player.get();
    ServerWorld world = player.getEntityWorld();
    BlockPos blockPos = player.getBlockPos().toImmutable();

    if (AuthCore.config.lobby.limboConfig.enabled
        && (!this.user.isRegistered.get() || !AuthCore.config.lobby.limboConfig.onlyOnFirstTime)) {
      String raw = AuthCore.config.lobby.limboConfig.location.dimension.trim().toLowerCase();
      Identifier id = Identifier.of(raw);
      RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, id);

      world = server.getWorld(key);
      if (world == null) return;

      if (this.position == null) {
        blockPos =
            BlockPos.ofFloored(
                AuthCore.config.lobby.limboConfig.location.x,
                AuthCore.config.lobby.limboConfig.location.y,
                AuthCore.config.lobby.limboConfig.location.z);
        blockPos = this.snapshot.getTeleportPos(player, blockPos.toImmutable(), world);
        this.position = blockPos.toImmutable();
      } else blockPos = this.position.toImmutable();
    }

    this.snapshot.teleport(player, blockPos.toImmutable(), world);
  }

  /**
   * Unlocks the player and restores them to their pre-lobby state. *
   *
   * <p>This removes the user from the active lobby map, cancels all background tasks
   * (timeouts/reminders), and reverts the player's position, inventory, and status using the stored
   * {@link Snapshot}.
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

  /** Cancels any active scheduled tasks (timeout or reminders) for this lobby session. */
  public void cancel() {

    Misc.TaskScheduler.getInstance().stopTask(this.lobbyIntervalTask);
    Misc.TaskScheduler.getInstance().stopTask(this.lobbyTimeoutTask);

    Logger.debug(false, "{}'s Lobby interval and timeout has been cancelled!", this.user.username);
  }

  /**
   * Checks if the player has moved significantly from their restricted lobby position.
   *
   * @param newX The new X coordinate from the movement packet.
   * @param newZ The new Z coordinate from the movement packet.
   * @return {@code true} if the player has moved on the X or Z axis; {@code false} otherwise.
   */
  public boolean isOutsideOfLobbyPos(double newX, double newZ) {
    // Movement by jailed user event detection!
    double oldX = this.user.player.get().getX();
    double oldZ = this.user.player.get().getZ();

    // If player actually moved in X/Z
    return (Double.compare(newX, oldX) != 0) || (Double.compare(newZ, oldZ) != 0);
  }

  /**
   * Calculates and starts the timeout and reminder tasks. *
   *
   * <p>The timeout duration is dynamically adjusted based on the player's current latency (ping) to
   * prevent players with slow connections from being kicked prematurely. It also schedules periodic
   * messages reminding the player to /login or /register.
   */
  private void handleTimeout() {

    int loginTimeoutMs = AuthCore.config.lobby.timeout.timeInMs;

    if (user.handler.getLatency() >= 600)
      loginTimeoutMs = AuthCore.config.lobby.timeout.timeoutAbove600LatencyMs;
    else if (user.handler.getLatency() >= 400)
      loginTimeoutMs = AuthCore.config.lobby.timeout.timeoutAbove400LatencyMs;
    else if (user.handler.getLatency() >= 200)
      loginTimeoutMs = AuthCore.config.lobby.timeout.timeoutAbove200LatencyMs;

    int _loginTimeoutMs = loginTimeoutMs;

    if (loginTimeoutMs > 0)
      this.lobbyTimeoutTask =
          Misc.TaskScheduler.getInstance()
              .setTimeout(
                  () -> {
                    if (user.isActive && this.user.isInLobby.get())
                      Logger.toKick(
                          false,
                          this.user.handler,
                          AuthCore.messages.promptUserAuthenticationExpiredTimeout,
                          Misc.TimeConverter.toDuration(_loginTimeoutMs));
                  },
                  loginTimeoutMs);

    long endIntervalMs = System.currentTimeMillis() + loginTimeoutMs;

    if (AuthCore.config.session.messageReminderIntervalMs > 0)
      if (!user.isRegistered.get())
        this.lobbyIntervalTask =
            Misc.TaskScheduler.getInstance()
                .setInterval(
                    () -> {
                      if (this.user.isActive && this.user.isInLobby.get())
                        Logger.toUser(
                            true,
                            this.user.handler,
                            AuthCore.messages.promptUserRegisterCommandReminderInterval,
                            _loginTimeoutMs > 0
                                ? Misc.TimeConverter.toDuration(
                                    endIntervalMs - System.currentTimeMillis())
                                : "Infinite");
                    },
                    AuthCore.config.session.messageReminderIntervalMs);
      else
        this.lobbyIntervalTask =
            Misc.TaskScheduler.getInstance()
                .setInterval(
                    () -> {
                      if (this.user.isActive && this.user.isInLobby.get())
                        Logger.toUser(
                            true,
                            this.user.handler,
                            AuthCore.messages.promptUserLoginCommandReminderInterval,
                            _loginTimeoutMs > 0
                                ? Misc.TimeConverter.toDuration(
                                    endIntervalMs - System.currentTimeMillis())
                                : "Infinite");
                    },
                    AuthCore.config.session.messageReminderIntervalMs);
  }

  /**
   * Captures and restores the state of a {@link ServerPlayerEntity}. *
   *
   * <p>This static inner class stores inventory, health, experience, status effects, and spatial
   * data to ensure players can be returned exactly to where they were before the authentication
   * process started.
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

    private final boolean operator;

    private LeveledPermissionPredicate permissions;

    /**
     * Captures the current state of the player and applies lobby-specific status effects. *
     *
     * <p>Depending on configuration, this may hide the player's inventory and apply blindness or
     * invisibility to keep the authentication process secure and private.
     *
     * @param player The player whose state should be snapshot.
     */
    public Snapshot(ServerPlayerEntity player) {
      MinecraftServer server = player.getEntityWorld().getServer();

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

      if (server != null) {
        this.operator = server.getPlayerManager().isOperator(player.getPlayerConfigEntry());

        this.permissions = server.getPlayerManager().getServer().getOpPermissionLevel();
      } else this.operator = false;

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

      if (server != null && this.operator && AuthCore.config.lobby.safeOperators)
        server.getPlayerManager().removeFromOperators(player.getPlayerConfigEntry());
    }

    /**
     * Reverts the player to the state stored in this snapshot. *
     *
     * <p>Clears lobby status effects, restores the inventory, experience, health, and teleports the
     * player back to their original position and dimension.
     *
     * @param player The player entity to restore.
     */
    public void reset(ServerPlayerEntity player) {
      MinecraftServer server = player.getEntityWorld().getServer();
      UUID uuid = player.getUuid();
      String username = player.getName().getString();
      User user = User.getUser(username, uuid);

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

      BlockPos pos = this.blockPos;

      if (user != null && (AuthCore.config.session.skipCombatDetection || !user.isInCombactPenalty))
        pos = this.getTeleportPos(player, this.blockPos.toImmutable(), world);

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

      if (this.operator)
        server
            .getPlayerManager()
            .addToOperators(
                player.getPlayerConfigEntry(),
                Optional.ofNullable(this.permissions),
                Optional.of(true));
    }

    /**
     * Internal helper to execute the player teleportation with consistent offsets.
     *
     * @param player The player to teleport.
     * @param pos The target block position.
     * @param world The target world.
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
     * Calculates a safe landing position to prevent the player from suffocating or falling into the
     * void. *
     *
     * <p>Accounts for player state (crouching, swimming, flying) and finds appropriate ground or
     * surface levels.
     *
     * @param player The player entity.
     * @param pos The desired target position.
     * @param world The world context.
     * @return A {@link BlockPos} adjusted for safety.
     */
    private BlockPos getTeleportPos(ServerPlayerEntity player, BlockPos pos, ServerWorld world) {
      if (player.isSpectator()) return pos.toImmutable();

      BlockPos candidate = pos.toImmutable();

      boolean playerIsAirborne =
          player.getAbilities().flying
              || player.isGliding()
              || player.hasVehicle()
              || !player.isOnGround();

      boolean playerInGap =
          (player.isGliding() || !player.isOnGround())
              && !world.getBlockState(pos).isAir()
              && world.getBlockState(pos.down()).isSolidBlock(world, pos.down())
              && world.getBlockState(pos.up()).isSolidBlock(world, pos.up());

      boolean playerIsInWater =
          player.isSwimming()
              || player.isTouchingWater()
              || (player.getPose() == EntityPose.SWIMMING);

      if (playerInGap || player.isInsideWall() || !isBlockSafe(world, candidate)) {
        BlockPos safe = getGroundAbove(world, candidate);
        if (safe != null) candidate = safe.up();

      } else if (player.isInsideWall() || !isBlockSafe(world, candidate)) {
        BlockPos safe = getGroundAbove(world, candidate);
        if (safe != null) candidate = safe.up();

      } else if (player.getPose() == EntityPose.CROUCHING) {
        BlockPos safe = getGroundAbove(world, candidate);
        if (safe != null) candidate = safe.up();

      } else if (playerIsInWater) {
        BlockPos surface = findWaterSurface(world, candidate);
        if (surface != null) candidate = surface.up();

      } else if (playerIsAirborne) {
        BlockPos ground = getGroundBelow(world, candidate);
        if (ground != null) candidate = ground.up();
      }

      if (player.isInsideWall() || !isBlockSafe(world, candidate)) {
        BlockPos safe = getGroundAbove(world, candidate);
        if (safe != null) candidate = safe.up();
      }

      return candidate.toImmutable();
    }

    /**
     * Searches upwards for the surface of a body of water.
     *
     * @param world The server world.
     * @param origin The starting block position.
     * @return The position of the water surface, or {@code null} if not found.
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
     * Searches downwards for the nearest solid ground.
     *
     * @param world The server world.
     * @param origin The starting block position.
     * @return The ground position, or {@code null} if the void is reached.
     */
    private BlockPos getGroundBelow(ServerWorld world, BlockPos origin) {
      BlockPos.Mutable check = origin.mutableCopy();

      while (check.getY() >= world.getBottomY()) {
        check.move(0, -1, 0);
        if (isBlockSafe(world, check)) return check.toImmutable();
      }

      return null;
    }

    /**
     * Searches upwards for the nearest solid ground.
     *
     * @param world The server world.
     * @param origin The starting block position.
     * @return The ground position, or {@code null} if the build limit is reached.
     */
    private BlockPos getGroundAbove(ServerWorld world, BlockPos origin) {
      BlockPos.Mutable check = origin.mutableCopy();
      int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, origin);

      while (check.getY() <= topY) {
        check.move(0, 1, 0);
        if (isBlockSafe(world, check)) return check.toImmutable();
      }

      return null;
    }

    /**
     * Validates if a block position is safe for a player to stand on without suffocating or
     * falling.
     *
     * @param world The server world.
     * @param pos The block position to check.
     * @return {@code true} if the block and the two blocks above it allow for safe standing.
     */
    private boolean isBlockSafe(ServerWorld world, BlockPos pos) {
      BlockState state = world.getBlockState(pos);

      // Check if the block has a solid collision shape or is otherwise standable
      boolean stableBlockState =
          state.isSolidBlock(world, pos)
              || state.getBlock() instanceof LeavesBlock
              || state.getBlock() instanceof CarpetBlock
              || state.getBlock() instanceof ScaffoldingBlock
              || state.getBlock() instanceof SlimeBlock
              || state.getBlock() instanceof HoneyBlock
              || state.getBlock() instanceof FenceBlock
              || state.getBlock() instanceof WallBlock
              || state.getBlock() instanceof LadderBlock
              || state.getBlock() instanceof VineBlock
              || state.getBlock() instanceof BigDripleafBlock
              || state.getBlock() instanceof SmallDripleafBlock;

      return (state.isSolidBlock(world, pos)
              || stableBlockState
              || !state.getCollisionShape(world, pos).isEmpty()
              || world.getBlockState(pos).getBlock() == Blocks.SNOW
              || world.getBlockState(pos).getBlock() == Blocks.SNOW_BLOCK)
          && world.getBlockState(pos.up()).isAir()
          && world.getBlockState(pos.up(2)).isAir();
    }
  }
}
