package net.ded3ec.models;

import net.ded3ec.util.Logger;
import net.ded3ec.util.Registry;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.ded3ec.AuthCoreServer;
import net.ded3ec.util.TimeManager;
import net.ded3ec.security.Security;
import net.ded3ec.util.TaskScheduler;
import net.ded3ec.util.TpsManager;
import net.minecraft.world.level.block.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.HoneyBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.SlimeBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.ded3ec.compat.Compat;

/**
 * Manages the lobby system for player authentication and queuing within the AuthCoreServer mod. *
 *
 * <p>This class handles "locking" players into a restricted state, managing teleports to limbo or
 * hub locations, handling session timeouts, and restoring the player's original state (Snapshot)
 * upon successful authentication.
 */
public class Lobby {

  /**
   * A global registry of users currently restricted within a lobby instance, mapped by username
   * (thread-safe; accessed from netty and server threads).
   */
  public static Map<String, Lobby> users = new ConcurrentHashMap<>();

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
    ServerPlayer player = user.player.get();
    this.snapshot = new Snapshot(player);
    this.handleTeleport();

    AuthCoreServer.LOGGER.toUser(
        true, user.connection, AuthCoreServer.messages.promptUserWelcomeLobbyUser);

    if (AuthCoreServer.config.lobby.timeout.enabled) this.handleTimeout();
    Lobby.users.put(this.user.username, this);

    // Handle 2FA: generate a TOTP secret and send the setup link on first join
    if (!user.isRegistered.get()
        && AuthCoreServer.config.session.authentication.allowTOTPSupport
        && user.authSecret == null) {
      user.authSecret = Security.TOTPManager.getSecret();
      String qrURL = Security.TOTPManager.getQrUrl(user.username, user.authSecret);

      AuthCoreServer.LOGGER.toUser(
          true, user.connection, AuthCoreServer.messages.promptUser2faSetup, qrURL);
    }

    // Captcha challenge against bot registration/login attempts.
    // Skipped when: the account is trusted, or TPS is too low (config).
    boolean captchaEnabled = AuthCoreServer.config.lobby.captcha.enabled;
    boolean trustedBypass =
        AuthCoreServer.config.session.trusted.enabled
            && user.trustedUntilMs > System.currentTimeMillis();
    boolean tpsBypass =
        AuthCoreServer.config.lobby.captcha.disableWhenTpsBelow > 0
            && TpsManager.get() < AuthCoreServer.config.lobby.captcha.disableWhenTpsBelow;

    if (captchaEnabled && !trustedBypass && !tpsBypass) {
      String code =
          Security.CaptchaManager.generate(
              user.uuid,
              AuthCoreServer.config.lobby.captcha.length,
              AuthCoreServer.config.lobby.captcha.ttlMs);
      user.captchaVerified = false;

      AuthCoreServer.LOGGER.toUser(
          true, user.connection, AuthCoreServer.messages.promptUserCaptchaRequired, code);
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
    ServerPlayer player = this.user.player.get();
    ServerLevel world = (ServerLevel) player.level();
    BlockPos blockPos = player.blockPosition();

    // Limbo logic
    if (AuthCoreServer.config.lobby.limboConfig.enabled
        && (!this.user.isRegistered.get()
            || !AuthCoreServer.config.lobby.limboConfig.onlyOnFirstTime)) {

      String raw = AuthCoreServer.config.lobby.limboConfig.location.dimension.trim().toLowerCase();

      // Fabric uses Identifier + RegistryKey<World>
      Identifier id = Identifier.tryParse(raw);
      if (id == null) return;

      Object worldKey = net.ded3ec.compat.Compat.worldKey(id);

      world = net.ded3ec.compat.Compat.getWorld(server, worldKey);
      if (world == null) return;

      if (this.position == null) {

        blockPos =
            net.ded3ec.compat.Compat.blockPosFloored(
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

    ServerPlayer player = this.user.player.get();

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

    if (net.ded3ec.compat.Compat.getLatency(user.player.get()) >= 600)
      loginTimeoutMs = AuthCoreServer.config.lobby.timeout.timeoutAbove600LatencyMs;
    else if (net.ded3ec.compat.Compat.getLatency(user.player.get()) >= 400)
      loginTimeoutMs = AuthCoreServer.config.lobby.timeout.timeoutAbove400LatencyMs;
    else if (net.ded3ec.compat.Compat.getLatency(user.player.get()) >= 200)
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
   * Captures and restores the state of a {@link ServerPlayer}.
   *
   * <p>This static inner class stores inventory, health, experience, status effects, and spatial
   * data to ensure players can be returned exactly to where they were before the authentication
   * process started.
   */
  public static class Snapshot {

    private final ArrayList<ItemStack> inventory;
    private final ArrayList<MobEffectInstance> effects;

    private final int foodLevel;
    private final float saturation;

    private final int xpLevel;
    private final float xpProgress;
    private final int totalXp;

    private final BlockPos blockPos;
    private final float health;

    private final Object dimensionKey;

    private final int fireTicks;
    private final int frozenTicks;
    private final float fallDistance;

    private final GameType gameMode;

    private final boolean operator;
    private int opPermissionLevel = 0;

    /** Captures the current state of the player and applies lobby-specific status effects. */
    public Snapshot(ServerPlayer player) {

      MinecraftServer server = player.level().getServer();

      // --- POSITION & DIMENSION ---
      this.blockPos = player.blockPosition();
      this.dimensionKey = net.ded3ec.compat.Compat.worldRegistryKey(player.level());

      // --- EFFECTS ---
      this.effects = new ArrayList<>(player.getActiveEffects());

      // --- INVENTORY ---
      this.inventory = new ArrayList<>();
      if (AuthCoreServer.config.lobby.hideInventory) {
        Inventory inv = net.ded3ec.compat.Compat.getInventory(player);
        for (int i = 0; i < inv.getContainerSize(); i++) {
          ItemStack stack = inv.getItem(i);
          this.inventory.add(stack.copy());
        }
      }

      // --- HEALTH & FOOD ---
      this.health = player.getHealth();
      this.foodLevel = player.getFoodData().getFoodLevel();
      this.saturation = player.getFoodData().getSaturationLevel();

      // --- EXPERIENCE ---
      this.xpLevel = player.experienceLevel;
      this.xpProgress = player.experienceProgress;
      this.totalXp = player.totalExperience;

      // --- FIRE / FREEZE / FALL ---
      this.fireTicks = player.getRemainingFireTicks();
      this.frozenTicks = net.ded3ec.compat.Compat.getFrozenTicks(player);
      this.fallDistance = (float) player.fallDistance;

      // --- GAMEMODE ---
      this.gameMode = player.gameMode.getGameModeForPlayer();

      // --- OPERATOR STATUS ---
      this.operator = net.ded3ec.compat.Compat.isOperator(server, player);

      if (this.operator)
        this.opPermissionLevel = net.ded3ec.compat.Compat.getOperatorLevel(server, player);

      // --- CLEAR EFFECTS ---
      player.removeAllEffects();

      // --- APPLY LOBBY EFFECTS ---
      if (AuthCoreServer.config.lobby.invisibleUnauthorized)
        player.addEffect(
            new MobEffectInstance(
                MobEffects.INVISIBILITY, Integer.MAX_VALUE, 1, false, false));

      if (AuthCoreServer.config.lobby.applyBlindnessEffect)
        player.addEffect(
            new MobEffectInstance(MobEffects.BLINDNESS, Integer.MAX_VALUE, 1, false, false));

      // --- DAMAGE PROTECTION ---
      if (AuthCoreServer.config.lobby.preventDamage) {
        player.setInvulnerable(true);
        player.setHealth(player.getMaxHealth());
      }

      // --- SAFE OPERATORS ---
      if (this.operator && AuthCoreServer.config.lobby.safeOperators)
        net.ded3ec.compat.Compat.removeFromOperators(server, player);
    }

    /**
     * Reverts the player to the state stored in this snapshot.
     *
     * <p>Clears lobby status effects, restores the inventory, experience, health, and teleports the
     * player back to their original position and dimension.
     */
    public void reset(ServerPlayer player) {

      MinecraftServer server = player.level().getServer();

      // --- CLEAR LOBBY EFFECTS ---
      player.removeAllEffects();
      player.setInvulnerable(false);

      // --- RESTORE INVENTORY ---
      if (AuthCoreServer.config.lobby.hideInventory && inventory != null && !inventory.isEmpty()) {
        Inventory inv = net.ded3ec.compat.Compat.getInventory(player);
        inv.clearContent();

        for (int i = 0; i < inventory.size(); i++) inv.setItem(i, inventory.get(i).copy());
        player.containerMenu.broadcastChanges();
      }

      // --- RESTORE EFFECTS ---
      for (MobEffectInstance effect : effects)
        player.addEffect(new MobEffectInstance(effect));

      // --- RESTORE DIMENSION ---
      ServerLevel world = net.ded3ec.compat.Compat.getWorld(server, this.dimensionKey);
      if (world == null) return;

      this.teleport(player, this.blockPos, world);

      // --- RESTORE HEALTH / FOOD ---
      player.setHealth(health);
      player.getFoodData().setFoodLevel(foodLevel);
      player.getFoodData().setSaturation(saturation);

      // --- RESTORE EXPERIENCE ---
      player.experienceLevel = xpLevel;
      player.experienceProgress = xpProgress;
      player.totalExperience = totalXp;

      // --- RESTORE FIRE / FREEZE / FALL ---
      player.setRemainingFireTicks(fireTicks);
      net.ded3ec.compat.Compat.setFrozenTicks(player, frozenTicks);
      player.fallDistance = fallDistance;

      // --- RESTORE GAMEMODE ---
      net.ded3ec.compat.Compat.changeGameMode(player, gameMode);

      // --- RESTORE OPERATOR STATUS ---
      if (this.operator)
        net.ded3ec.compat.Compat.addToOperators(server, player, this.opPermissionLevel);
    }

    /**
     * Internal helper to execute the player teleportation with consistent offsets.
     *
     * @param player The player to teleport.
     * @param pos The target block position.
     * @param world The target world.
     */
    private void teleport(ServerPlayer player, BlockPos pos, ServerLevel world) {
      net.ded3ec.compat.Compat.teleport(
          player,
          world,
          pos.getX() + 0.5,
          pos.getY() + 0.5,
          pos.getZ() + 0.5,
          net.ded3ec.compat.Compat.getYaw(player),
          net.ded3ec.compat.Compat.getPitch(player));
    }

    /**
     * Calculates a safe landing position to prevent the player from suffocating or falling into the
     * void.
     *
     * <p>Accounts for player state (crouching, swimming, flying) and finds appropriate ground or
     * surface levels.
     */
    private BlockPos getTeleportPos(ServerPlayer player, BlockPos pos, ServerLevel world) {

      // Spectators can always teleport anywhere
      if (player.isSpectator()) return pos;

      BlockPos candidate = pos;

      boolean airborne =
          net.ded3ec.compat.Compat.getAbilities(player).flying
              || net.ded3ec.compat.Compat.isGliding(player)
              || player.isPassenger()
              || !player.onGround();

      boolean inGap =
          (net.ded3ec.compat.Compat.isGliding(player) || !player.onGround())
              && !world.getBlockState(pos).isAir()
              && world.getBlockState(pos.below()).canOcclude()
              && world.getBlockState(pos.above()).canOcclude();

      boolean inWater =
          player.isSwimming()
              || player.isInWater()
              || player.getPose() == Pose.SWIMMING;

      // 1. If unsafe or inside wall → find ground above
      if (inGap || player.isInWall() || !isBlockSafe(world, candidate)) {
        BlockPos safe = getGroundAbove(world, candidate);
        if (safe != null) candidate = safe.above();

      }

      // 2. Crouching → ensure headroom
      else if (player.getPose() == Pose.CROUCHING) {
        BlockPos safe = getGroundAbove(world, candidate);
        if (safe != null) candidate = safe.above();

      }

      // 3. Water → move to surface
      else if (inWater) {
        BlockPos surface = findWaterSurface(world, candidate);
        if (surface != null) candidate = surface.above();

      }

      // 4. Airborne → find ground below
      else if (airborne) {
        BlockPos ground = getGroundBelow(world, candidate);
        if (ground != null) candidate = ground.above();
      }

      // 5. Final safety check
      if (player.isInWall() || !isBlockSafe(world, candidate)) {
        BlockPos safe = getGroundAbove(world, candidate);
        if (safe != null) candidate = safe.above();
      }

      return candidate;
    }

    /**
     * Searches upwards for the surface of a body of water.
     *
     * @return The position of the water surface, or {@code null} if not found.
     */
    private BlockPos findWaterSurface(ServerLevel world, BlockPos origin) {

      BlockPos.MutableBlockPos check = origin.mutable();

      int topY =
          world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, origin.getX(), origin.getZ());

      // Move upward while still inside water
      while (check.getY() <= topY && world.getBlockState(check).is(Blocks.WATER))
        check.move(0, 1, 0);

      // If we exceeded world height, no surface found
      if (check.getY() > topY) return null;

      // The block below is the last water block
      return check.below();
    }

    /**
     * Searches downwards for the nearest solid ground.
     *
     * @return The ground position, or {@code null} if the void is reached.
     */
    private BlockPos getGroundBelow(ServerLevel world, BlockPos origin) {
      BlockPos.MutableBlockPos check = origin.mutable();

      while (check.getY() >= net.ded3ec.compat.Compat.getBottomY(world) && world.getBlockState(check.below()).isAir()) {
        check.move(0, -1, 0);

        if (isBlockSafe(world, check)) return check.immutable();
      }

      return null;
    }

    /**
     * Searches upwards for the nearest solid ground.
     *
     * @return The ground position, or {@code null} if the build limit is reached.
     */
    private BlockPos getGroundAbove(ServerLevel world, BlockPos origin) {
      BlockPos.MutableBlockPos check = origin.mutable();

      int topY =
          world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, origin.getX(), origin.getZ());

      while (check.getY() <= topY) {
        check.move(0, 1, 0);

        if (isBlockSafe(world, check)) return check.immutable();
      }

      return null;
    }

    /**
     * Validates if a block position is safe for a player to stand on without suffocating or
     * falling.
     *
     * @return {@code true} if the block and the two blocks above it allow for safe standing.
     */
    private boolean isBlockSafe(ServerLevel world, BlockPos pos) {

      BlockState state = world.getBlockState(pos);

      // A block is "standable" if it has a solid collision shape or is a known safe block
      boolean standable =
          state.canOcclude()
              || state.getBlock() instanceof LeavesBlock
              || state.getBlock() instanceof CarpetBlock
              || state.getBlock() instanceof ScaffoldingBlock
              || state.getBlock() instanceof SlimeBlock
              || state.getBlock() instanceof HoneyBlock
              || state.getBlock() instanceof FenceBlock
              || state.getBlock() instanceof WallBlock
              || state.getBlock() instanceof LadderBlock
              || state.getBlock() instanceof VineBlock
              || net.ded3ec.compat.Compat.isDripleaf(state)
              || state.is(Blocks.SNOW)
              || state.is(Blocks.SNOW_BLOCK)
              || !state.getCollisionShape(world, pos).isEmpty();

      // Must have air above for head + body
      boolean headroom =
          world.getBlockState(pos.above()).isAir() && world.getBlockState(pos.above(2)).isAir();

      return standable && headroom;
    }
  }
}
