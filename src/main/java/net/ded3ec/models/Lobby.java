package net.ded3ec.models;

import java.net.URI;
import java.util.*;
import net.ded3ec.AuthCoreServer;
import net.ded3ec.utils.TimeManager;
import net.ded3ec.utils.Security;
import net.ded3ec.utils.TaskScheduler;
import net.minecraft.block.*;
import net.minecraft.command.permission.LeveledPermissionPredicate;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

/**
 * Manages the lobby system for player authentication and queuing within the AuthCoreServer mod. *
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

    AuthCoreServer.LOGGER.toUser(
        true, user.connection, AuthCoreServer.messages.promptUserWelcomeLobbyUser);

    if (AuthCoreServer.config.lobby.timeout.enabled) this.handleTimeout();
    Lobby.users.put(this.user.username, this);

    // Handle 2FA
    if (!user.isRegistered.get() && AuthCoreServer.config.session.authentication.allowTOTPSupport && user.authSecret == null) {
      user.authSecret = Security.TOTPManager.getSecret();
      String qrURL = Security.TOTPManager.getQrUrl(user.username, user.authSecret);

      user.player
          .get()
          .sendMessage(
              Text.literal(
                      "You need to Register 2FA QR with your Mobile Authenticator App like (Microsoft Authenticator/Authy/Google Authenticator): ")
                  .styled(
                      style ->
                          style
                              .withColor(Formatting.BLUE) // aqua
                              .withBold(true))
                  .append(
                      Text.literal("[QR Image]")
                          .styled(
                              style ->
                                  style
                                      .withColor(0x55FFFF) // aqua
                                      .withUnderline(true)
                                      .withBold(true)
                                      .withClickEvent(new ClickEvent.OpenUrl(URI.create(qrURL))))));
    }
  }

  /**
   * Determines the target destination and teleports the player based on configuration.
   *
   * <p>If Limbo is enabled and applicable (e.g., for unregistered users), it calculates a safe
   * position in the designated dimension. It uses the snapshot logic to prevent players from
   * spawning in walls or unsafe locations.
   */
  public void handleTeleport() {

    MinecraftServer server = this.user.server.get();
    ServerPlayerEntity player = this.user.player.get();
    ServerWorld world = player.getEntityWorld();
    BlockPos blockPos = player.getBlockPos();

    // Limbo logic
    if (AuthCoreServer.config.lobby.limboConfig.enabled
        && (!this.user.isRegistered.get()
            || !AuthCoreServer.config.lobby.limboConfig.onlyOnFirstTime)) {

      String raw = AuthCoreServer.config.lobby.limboConfig.location.dimension.trim().toLowerCase();

      // Fabric uses Identifier + RegistryKey<World>
      Identifier id = Identifier.tryParse(raw);
      if (id == null) return;

      RegistryKey<World> worldKey = RegistryKey.of(RegistryKeys.WORLD, id);

      world = server.getWorld(worldKey);
      if (world == null) return;

      if (this.position == null) {

        blockPos =
            BlockPos.ofFloored(
                AuthCoreServer.config.lobby.limboConfig.location.x,
                AuthCoreServer.config.lobby.limboConfig.location.y,
                AuthCoreServer.config.lobby.limboConfig.location.z);

        blockPos = this.snapshot.getTeleportPos(player, blockPos, world);
        this.position = blockPos;

      } else {
        blockPos = this.position;
      }
    }

    this.snapshot.teleport(player, blockPos, world);
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

    AuthCoreServer.LOGGER.debug(
        false, "{} has been taken out from the lobby/restricted mode!", this.user.username);

    this.cancel();
  }

  /** Cancels any active scheduled tasks (timeout or reminders) for this lobby session. */
  public void cancel() {

    TaskScheduler.getInstance().stopTask(this.lobbyIntervalTask);
    TaskScheduler.getInstance().stopTask(this.lobbyTimeoutTask);

    AuthCoreServer.LOGGER.debug(
        false, "{}'s Lobby interval and timeout has been cancelled!", this.user.username);
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

    int loginTimeoutMs = AuthCoreServer.config.lobby.timeout.timeInMs;

    if (user.connection.getLatency() >= 600)
      loginTimeoutMs = AuthCoreServer.config.lobby.timeout.timeoutAbove600LatencyMs;
    else if (user.connection.getLatency() >= 400)
      loginTimeoutMs = AuthCoreServer.config.lobby.timeout.timeoutAbove400LatencyMs;
    else if (user.connection.getLatency() >= 200)
      loginTimeoutMs = AuthCoreServer.config.lobby.timeout.timeoutAbove200LatencyMs;

    int _loginTimeoutMs = loginTimeoutMs;

    if (loginTimeoutMs > 0)
      this.lobbyTimeoutTask =
          TaskScheduler.getInstance()
              .setTimeout(
                  () -> {
                    if (user.isActive && this.user.isInLobby.get())
                      AuthCoreServer.LOGGER.toKick(
                          false,
                          this.user.connection,
                          AuthCoreServer.messages.promptUserAuthenticationExpiredTimeout,
                          TimeManager.toDuration(_loginTimeoutMs));
                  },
                  loginTimeoutMs);

    long endIntervalMs = System.currentTimeMillis() + loginTimeoutMs;

    if (AuthCoreServer.config.session.messageReminderIntervalMs > 0)
      if (!user.isRegistered.get())
        this.lobbyIntervalTask =
            TaskScheduler.getInstance()
                .setInterval(
                    () -> {
                      if (this.user.isActive && this.user.isInLobby.get())
                        AuthCoreServer.LOGGER.toUser(
                            true,
                            this.user.connection,
                            AuthCoreServer.messages.promptUserRegisterCommandReminderInterval,
                            _loginTimeoutMs > 0 && (endIntervalMs - System.currentTimeMillis() > 1)
                                ? TimeManager.toDuration(endIntervalMs - System.currentTimeMillis())
                                : "Infinite");
                    },
                    AuthCoreServer.config.session.messageReminderIntervalMs);
      else
        this.lobbyIntervalTask =
            TaskScheduler.getInstance()
                .setInterval(
                    () -> {
                      if (this.user.isActive && this.user.isInLobby.get())
                        AuthCoreServer.LOGGER.toUser(
                            true,
                            this.user.connection,
                            AuthCoreServer.messages.promptUserLoginCommandReminderInterval,
                            _loginTimeoutMs > 0 && (endIntervalMs - System.currentTimeMillis() > 1)
                                ? TimeManager.toDuration(endIntervalMs - System.currentTimeMillis())
                                : "Infinite");
                    },
                    AuthCoreServer.config.session.messageReminderIntervalMs);
  }

  /**
   * Captures and restores the state of a {@link ServerPlayerEntity}.
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
    private final float fallDistance;

    private final GameMode gameMode;

    private final boolean operator;
    private LeveledPermissionPredicate opPermissionLevel;

    /** Captures the current state of the player and applies lobby-specific status effects. */
    public Snapshot(ServerPlayerEntity player) {

      MinecraftServer server = player.getEntityWorld().getServer();

      // --- POSITION & DIMENSION ---
      this.blockPos = player.getBlockPos();
      this.dimensionKey = player.getEntityWorld().getRegistryKey();

      // --- EFFECTS ---
      this.effects = new ArrayList<>(player.getStatusEffects());

      // --- INVENTORY ---
      this.inventory = new ArrayList<>();
      if (AuthCoreServer.config.lobby.hideInventory) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
          ItemStack stack = inv.getStack(i);
          this.inventory.add(stack.copy());
        }
      }

      // --- HEALTH & FOOD ---
      this.health = player.getHealth();
      this.foodLevel = player.getHungerManager().getFoodLevel();
      this.saturation = player.getHungerManager().getSaturationLevel();

      // --- EXPERIENCE ---
      this.xpLevel = player.experienceLevel;
      this.xpProgress = player.experienceProgress;
      this.totalXp = player.totalExperience;

      // --- FIRE / FREEZE / FALL ---
      this.fireTicks = player.getFireTicks();
      this.frozenTicks = player.getFrozenTicks();
      this.fallDistance = (float) player.fallDistance;

      // --- GAMEMODE ---
      this.gameMode = player.interactionManager.getGameMode();

      // --- OPERATOR STATUS ---
      this.operator = server.getPlayerManager().isOperator(player.getPlayerConfigEntry());

      if (this.operator)
        this.opPermissionLevel = server.getPermissionLevel(player.getPlayerConfigEntry());

      // --- CLEAR EFFECTS ---
      player.clearStatusEffects();

      // --- APPLY LOBBY EFFECTS ---
      if (AuthCoreServer.config.lobby.invisibleUnauthorized)
        player.addStatusEffect(
            new StatusEffectInstance(
                StatusEffects.INVISIBILITY, Integer.MAX_VALUE, 1, false, false));

      if (AuthCoreServer.config.lobby.applyBlindnessEffect)
        player.addStatusEffect(
            new StatusEffectInstance(StatusEffects.BLINDNESS, Integer.MAX_VALUE, 1, false, false));

      // --- DAMAGE PROTECTION ---
      if (AuthCoreServer.config.lobby.preventDamage) {
        player.setInvulnerable(true);
        player.setHealth(player.getMaxHealth());
      }

      // --- SAFE OPERATORS ---
      if (this.operator && AuthCoreServer.config.lobby.safeOperators)
        server.getPlayerManager().removeFromOperators(player.getPlayerConfigEntry());
    }

    /**
     * Reverts the player to the state stored in this snapshot.
     *
     * <p>Clears lobby status effects, restores the inventory, experience, health, and teleports the
     * player back to their original position and dimension.
     */
    public void reset(ServerPlayerEntity player) {

      MinecraftServer server = player.getEntityWorld().getServer();

      // --- CLEAR LOBBY EFFECTS ---
      player.clearStatusEffects();
      player.setInvulnerable(false);

      // --- RESTORE INVENTORY ---
      if (AuthCoreServer.config.lobby.hideInventory && inventory != null && !inventory.isEmpty()) {
        PlayerInventory inv = player.getInventory();
        inv.clear();

        for (int i = 0; i < inventory.size(); i++) inv.setStack(i, inventory.get(i).copy());
        player.currentScreenHandler.sendContentUpdates();
      }

      // --- RESTORE EFFECTS ---
      for (StatusEffectInstance effect : effects)
        player.addStatusEffect(new StatusEffectInstance(effect));

      // --- RESTORE DIMENSION ---
      ServerWorld world = server.getWorld(this.dimensionKey);
      if (world == null) return;

      this.teleport(player, this.blockPos, world);

      // --- RESTORE HEALTH / FOOD ---
      player.setHealth(health);
      player.getHungerManager().setFoodLevel(foodLevel);
      player.getHungerManager().setSaturationLevel(saturation);

      // --- RESTORE EXPERIENCE ---
      player.experienceLevel = xpLevel;
      player.experienceProgress = xpProgress;
      player.totalExperience = totalXp;

      // --- RESTORE FIRE / FREEZE / FALL ---
      player.setFireTicks(fireTicks);
      player.setFrozenTicks(frozenTicks);
      player.fallDistance = fallDistance;

      // --- RESTORE GAMEMODE ---
      player.changeGameMode(gameMode);

      // --- RESTORE OPERATOR STATUS ---
      if (this.operator)
        server
            .getPlayerManager()
            .addToOperators(
                player.getPlayerConfigEntry(),
                Optional.of(this.opPermissionLevel),
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
      player.teleport(
          world,
          pos.getX() + 0.5,
          pos.getY() + 0.5,
          pos.getZ() + 0.5,
          Set.of(),
          player.getYaw(),
          player.getPitch(),
          false);
    }

    /**
     * Calculates a safe landing position to prevent the player from suffocating or falling into the
     * void.
     *
     * <p>Accounts for player state (crouching, swimming, flying) and finds appropriate ground or
     * surface levels.
     */
    private BlockPos getTeleportPos(ServerPlayerEntity player, BlockPos pos, ServerWorld world) {

      // Spectators can always teleport anywhere
      if (player.isSpectator()) return pos;

      BlockPos candidate = pos;

      boolean airborne =
          player.getAbilities().flying
              || player.isGliding()
              || player.hasVehicle()
              || !player.isOnGround();

      boolean inGap =
          (player.isGliding() || !player.isOnGround())
              && !world.getBlockState(pos).isAir()
              && world.getBlockState(pos.down()).isOpaque()
              && world.getBlockState(pos.up()).isOpaque();

      boolean inWater =
          player.isSwimming()
              || player.isTouchingWater()
              || player.getPose() == EntityPose.SWIMMING;

      // 1. If unsafe or inside wall → find ground above
      if (inGap || player.isInsideWall() || !isBlockSafe(world, candidate)) {
        BlockPos safe = getGroundAbove(world, candidate);
        if (safe != null) candidate = safe.up();

      }

      // 2. Crouching → ensure headroom
      else if (player.getPose() == EntityPose.CROUCHING) {
        BlockPos safe = getGroundAbove(world, candidate);
        if (safe != null) candidate = safe.up();

      }

      // 3. Water → move to surface
      else if (inWater) {
        BlockPos surface = findWaterSurface(world, candidate);
        if (surface != null) candidate = surface.up();

      }

      // 4. Airborne → find ground below
      else if (airborne) {
        BlockPos ground = getGroundBelow(world, candidate);
        if (ground != null) candidate = ground.up();
      }

      // 5. Final safety check
      if (player.isInsideWall() || !isBlockSafe(world, candidate)) {
        BlockPos safe = getGroundAbove(world, candidate);
        if (safe != null) candidate = safe.up();
      }

      return candidate;
    }

    /**
     * Searches upwards for the surface of a body of water.
     *
     * @return The position of the water surface, or {@code null} if not found.
     */
    private BlockPos findWaterSurface(ServerWorld world, BlockPos origin) {

      BlockPos.Mutable check = origin.mutableCopy();

      int topY =
          world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, origin.getX(), origin.getZ());

      // Move upward while still inside water
      while (check.getY() <= topY && world.getBlockState(check).isOf(Blocks.WATER))
        check.move(0, 1, 0);

      // If we exceeded world height, no surface found
      if (check.getY() > topY) return null;

      // The block below is the last water block
      return check.down();
    }

    /**
     * Searches downwards for the nearest solid ground.
     *
     * @return The ground position, or {@code null} if the void is reached.
     */
    private BlockPos getGroundBelow(ServerWorld world, BlockPos origin) {
      BlockPos.Mutable check = origin.mutableCopy();

      while (check.getY() >= world.getBottomY() && world.getBlockState(check.down()).isAir()) {
        check.move(0, -1, 0);

        if (isBlockSafe(world, check)) return check.toImmutable();
      }

      return null;
    }

    /**
     * Searches upwards for the nearest solid ground.
     *
     * @return The ground position, or {@code null} if the build limit is reached.
     */
    private BlockPos getGroundAbove(ServerWorld world, BlockPos origin) {
      BlockPos.Mutable check = origin.mutableCopy();

      int topY =
          world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, origin.getX(), origin.getZ());

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
     * @return {@code true} if the block and the two blocks above it allow for safe standing.
     */
    private boolean isBlockSafe(ServerWorld world, BlockPos pos) {

      BlockState state = world.getBlockState(pos);

      // A block is "standable" if it has a solid collision shape or is a known safe block
      boolean standable =
          state.isOpaque()
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
              || state.getBlock() instanceof SmallDripleafBlock
              || state.isOf(Blocks.SNOW)
              || state.isOf(Blocks.SNOW_BLOCK)
              || !state.getCollisionShape(world, pos).isEmpty();

      // Must have air above for head + body
      boolean headroom =
          world.getBlockState(pos.up()).isAir() && world.getBlockState(pos.up(2)).isAir();

      return standable && headroom;
    }
  }
}
