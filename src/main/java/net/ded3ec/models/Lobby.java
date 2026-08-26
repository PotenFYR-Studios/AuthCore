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
 * The limbo: the locked-down state every unauthenticated player is held in.
 *
 * <p>When a player is locked here they lose almost all interaction (movement, chat, commands,
 * blocks, items, attacks), are blinded and anchored to one spot, and get reminded to
 * register or login. A {@link Snapshot} of everything they had before is kept so that on
 * unlock they are restored exactly (position, inventory, effects, game mode, operator
 * status, even their vehicle), safely.
 */
public class Lobby {

  /**
   * A global registry of users currently restricted within a lobby instance, mapped by username
   * (thread-safe; accessed from netty and server threads).
   */
  public static Map<String, Lobby> users = new ConcurrentHashMap<>();

  /**
   * Accounts an admin explicitly deopped WHILE they were locked in the limbo (thread-safe).
   * The limbo itself removes operator status when {@code lobby.safe-operators} is enabled, so
   * the unlock restore must skip re-granting op powers for these - otherwise a stale
   * snapshot would resurrect them over the admin's decision.
   */
  public static final Set<UUID> ADMIN_DEOPS_DURING_LIMBO = ConcurrentHashMap.newKeySet();

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

  /** Position of the temporary anti-float platform (null when not placed). */
  private transient BlockPos platformPos = null;

  /** The block state that occupied {@link #platformPos} before the platform was placed. */
  private transient BlockState platformOriginal = null;

  /** The world the platform was placed in (restored there after auth). */
  private transient ServerLevel platformWorld = null;

  /** Timestamp of the lobby lock - opens the join-sync grace window for movement checks. */
  public volatile long lockedAtMs = 0;

  /** Timestamp of the last client position correction (throttles the snap-back). */
  public volatile long lastCorrectionMs = 0;

  /** The player's REAL game mode before the lobby forced Adventure (restore fallback). */
  private net.minecraft.world.level.GameType originalGameMode;

  /**
   * The crash-safe limbo snapshot file for a player. Written at lock, deleted at unlock -
   * if it still exists when the player joins again, the previous limbo session ended
   * without a clean unlock (server crash) and the saved pre-limbo state is restored
   * before the fresh lock captures it.
   */
  public static java.nio.file.Path snapshotFile(java.util.UUID uuid) {
    return AuthCoreServer.configPath.resolve("limbo").resolve(uuid + ".json");
  }

  /**
   * Builds the context-aware clickable auth button: the command shape matches the server's
   * actual authentication requirements (password confirmation, 2FA, captcha), so the
   * player always sees exactly what they need - never a generic hint that misses a step.
   */
  private static net.ded3ec.models.Messages.ColTemplate buildAuthButton(User user) {
    net.ded3ec.models.Messages.ColTemplate button =
        new net.ded3ec.models.Messages.ColTemplate();
    button.message = new net.ded3ec.models.Messages.Message();
    button.actionBar = new net.ded3ec.models.Messages.ActionBar();

    boolean registered = user.isRegistered.get();
    StringBuilder cmd = new StringBuilder(registered ? "/login" : "/register");
    cmd.append(registered ? " <password>" : " <password> <confirm>");
    StringBuilder hint = new StringBuilder();
    if (AuthCoreServer.config.session.authentication.allowTOTPSupport) {
      cmd.append(" <2fa-code>");
      hint.append(" A 2FA code is required.");
    }

    button.message.text = registered ? "Click here to login" : "Click here to register";
    button.message.color = "YELLOW";
    button.message.clickCommand = cmd + " ";
    button.actionBar.text =
        "Command: "
            + cmd
            + " - "
            + (registered ? "authenticate your account" : "create your account")
            + hint;
    button.actionBar.color = "YELLOW";
    return button;
  }

  /**
   * Runs a command for the player through the server dispatcher (era-safe - see
   * {@link net.ded3ec.compat.Compat#runCommand}).
   */
  private static void runCommand(net.minecraft.server.level.ServerPlayer player, String command) {
    net.ded3ec.compat.Compat.runCommand(player, command);
  }

  /**
   * Persists the player's pre-limbo state (game mode, inventory, effects, health/food/xp,
   * position) to the crash-safe snapshot file. Restored on the next join if the limbo
   * session ended uncleanly (server crash).
   */
  public static boolean saveCrashSnapshot(ServerPlayer player, java.nio.file.Path file) {
    try {
      java.nio.file.Files.createDirectories(file.getParent());
      com.google.gson.JsonObject o = new com.google.gson.JsonObject();
      o.addProperty("gameMode", player.gameMode.getGameModeForPlayer().getName());
      o.addProperty("health", player.getHealth());
      o.addProperty("food", player.getFoodData().getFoodLevel());
      o.addProperty("saturation", player.getFoodData().getSaturationLevel());
      o.addProperty("xpLevel", player.experienceLevel);
      o.addProperty("xpProgress", player.experienceProgress);
      o.addProperty("totalXp", player.totalExperience);
      o.addProperty("x", player.getX());
      o.addProperty("y", player.getY());
      o.addProperty("z", player.getZ());
      Object dimKey =
          net.ded3ec.compat.Compat.worldRegistryKey(
              net.ded3ec.compat.Compat.playerLevel(player));
      if (dimKey != null) {
        // ResourceKey.toString() = "ResourceKey[minecraft:dimension / minecraft:overworld]"
        // (identical format in every era) - extract the value location for the file.
        String s = String.valueOf(dimKey);
        int sep = s.indexOf("/ ");
        int end = s.lastIndexOf(']');
        if (sep >= 0 && end > sep) o.addProperty("dimension", s.substring(sep + 2, end));
      }

      com.google.gson.JsonArray inv = new com.google.gson.JsonArray();
      net.minecraft.world.entity.player.Inventory liveInv =
          net.ded3ec.compat.Compat.getInventory(player);
      if (liveInv != null)
        for (int i = 0; i < liveInv.getContainerSize(); i++)
          inv.add(net.ded3ec.compat.Compat.itemStackToBase64(liveInv.getItem(i)));
      o.add("inventory", inv);

      com.google.gson.JsonArray fx = new com.google.gson.JsonArray();
      for (net.minecraft.world.effect.MobEffectInstance e : player.getActiveEffects())
        fx.add(net.ded3ec.compat.Compat.effectToBase64(e));
      o.add("effects", fx);

      java.nio.file.Files.writeString(file, new com.google.gson.Gson().toJson(o));
      return true;
    } catch (Exception err) {
      return false;
    }
  }

  /**
   * Applies a saved crash snapshot back onto the player (game mode, inventory, effects,
   * health/food/xp, position - each best-effort). Called right before the fresh lobby
   * lock captures the state, so a crashed limbo session can never leave the player stuck.
   */
  public static boolean restoreCrashSnapshot(ServerPlayer player, java.nio.file.Path file) {
    try {
      com.google.gson.JsonObject o =
          com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(file))
              .getAsJsonObject();

      if (o.has("gameMode")) {
        net.minecraft.world.level.GameType mode =
            net.minecraft.world.level.GameType.byName(
                o.get("gameMode").getAsString(), net.minecraft.world.level.GameType.SURVIVAL);
        net.ded3ec.compat.Compat.changeGameMode(player, mode);
      }

      net.minecraft.world.entity.player.Inventory liveInv =
          net.ded3ec.compat.Compat.getInventory(player);
      if (o.has("inventory") && liveInv != null) {
        liveInv.clearContent();
        com.google.gson.JsonArray arr = o.getAsJsonArray("inventory");
        for (int i = 0; i < arr.size() && i < liveInv.getContainerSize(); i++)
          liveInv.setItem(i, net.ded3ec.compat.Compat.itemStackFromBase64(arr.get(i).getAsString()));
      }

      player.removeAllEffects();
      if (o.has("effects"))
        for (com.google.gson.JsonElement e : o.getAsJsonArray("effects")) {
          net.minecraft.world.effect.MobEffectInstance fx =
              net.ded3ec.compat.Compat.effectFromBase64(e.getAsString());
          if (fx != null) player.addEffect(fx);
        }

      if (o.has("health")) {
        float hp = o.get("health").getAsFloat();
        // Clamp: a tampered/corrupt snapshot must not restore NaN, negative or absurd
        // health (instant-death or god-mode on rejoin).
        if (!Float.isFinite(hp) || hp <= 0.0f) hp = player.getMaxHealth();
        player.setHealth(Math.min(hp, player.getMaxHealth()));
      }
      if (o.has("food")) player.getFoodData().setFoodLevel(o.get("food").getAsInt());
      if (o.has("saturation")) player.getFoodData().setSaturation(o.get("saturation").getAsFloat());
      if (o.has("xpLevel")) player.experienceLevel = o.get("xpLevel").getAsInt();
      if (o.has("xpProgress")) player.experienceProgress = o.get("xpProgress").getAsFloat();
      if (o.has("totalXp")) player.totalExperience = o.get("totalXp").getAsInt();

      if (o.has("dimension") && o.has("x") && o.has("y") && o.has("z")) {
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> key =
            net.ded3ec.compat.Compat.parseDimensionKey(o.get("dimension").getAsString());
        if (key != null) {
          net.minecraft.server.level.ServerLevel world =
              net.ded3ec.compat.Compat.getWorld(
                  net.ded3ec.compat.Compat.getServer(player),
                  key);
          if (world != null)
            net.ded3ec.compat.Compat.teleport(
                player,
                world,
                o.get("x").getAsDouble(),
                o.get("y").getAsDouble(),
                o.get("z").getAsDouble(),
                net.ded3ec.compat.Compat.getYaw(player),
                net.ded3ec.compat.Compat.getPitch(player));
        }
      }

      player.containerMenu.broadcastChanges();
      return true;
    } catch (Exception err) {
      return false;
    }
  }

  /**
   * Constructs a new Lobby instance for a specific user.
   *
   * @param user The user to be managed by this lobby instance.
   */
  public Lobby(User user) {
    this.user = user;
  }

  /** Debug label for a single limbo guard: {@code "allowed"} or {@code "LOCKED"}. */
  private static String guardState(boolean allowed) {
    return allowed ? "allowed" : "LOCKED";
  }

  /** Human lobby-capacity line for the debug banner, e.g. {@code "35% used (7/20)"}. */
  private static String lobbyUsage() {
    int max = AuthCoreServer.config.lobby.maxLobbyUsers;
    int used = Lobby.users.size();
    if (max <= 0) return used + " online (unlimited)";
    long pct = Math.round(100.0 * used / max);
    return pct + "% used (" + used + "/" + max + ")";
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

    // If the player entity is not resolvable yet (connection mid-setup on some loader
    // combinations), defer the lock instead of throwing - a mid-join NPE here aborts
    // placeNewPlayer and drops the player. The state stays clean: no snapshot is taken,
    // so no crash-recovery leftovers are created either.
    if (player == null) {
      AuthCoreServer.LOGGER.debug(
          false,
          "Deferred limbo lock for {} - player entity not resolvable yet.",
          user.username);
      return;
    }

    // Never re-lock an already-locked player: a second lock would capture the ALREADY
    // FORCED Adventure mode as the "original" game mode, so the unlock would restore
    // Adventure instead of the player's real mode - leaving them unable to break blocks
    // or attack anything after login ("the limbo state saved as permanent").
    if (this.user.isInLobby.get()) return;

    // Fresh violation budget per limbo session: a kicked player must start over with a
    // clean count - otherwise the FIRST violation after rejoining would trip the limit
    // that was already reached in the previous session.
    this.user.violationCount = 0;

    // Remember the player's REAL game mode independently of the snapshot, so the unlock
    // can always restore it even if the snapshot was lost or a restore step failed.
    this.originalGameMode = player.gameMode.getGameModeForPlayer();

    this.snapshot = new Snapshot(player);
    this.lockedAtMs = System.currentTimeMillis();

    AuthCoreServer.LOGGER.debug(
        false,
        "{} | limbo lock - registered={}, online-mode={}, lobby.users.size={}",
        this.user.username,
        this.user.isRegistered.get(),
        this.user.isPremium,
        Lobby.users.size());

    // Crash-safe snapshot: persist the pre-limbo state to disk so a server crash while
    // the player is in limbo cannot leave them stuck (vanilla saves the FORCED Adventure
    // mode + emptied inventory, so a plain rejoin would restore the limbo state forever).
    // Deleted on unlock; restored on the next join if it still exists.
    if (!saveCrashSnapshot(player, snapshotFile(user.uuid)))
      AuthCoreServer.LOGGER.debug(
          false, "Failed to persist limbo snapshot for {}", user.username);

    this.handleTeleport();

    // Anti-float platform: a player who logged out mid-air (or underwater) would
    // otherwise be kicked by vanilla's "Flying is not enabled" floating check before
    // they can authenticate. Place an invisible BARRIER under the limbo anchor and
    // restore the original block shortly after the auth flow completes.
    if (AuthCoreServer.config.lobby.antiFloatPlatform) placeLimboPlatform();

    AuthCoreServer.LOGGER.debug(
        false,
        "Limbo guards armed for {}:\n"
            + "  Movement      : {}\n"
            + "  Chat          : {}\n"
            + "  Commands      : {}\n"
            + "  Block break   : {}\n"
            + "  Block use     : {}\n"
            + "  Item use      : {}\n"
            + "  Item drop     : {}\n"
            + "  Attacks       : {}\n"
            + "  Lobby usage   : {}",
        user.username,
        guardState(AuthCoreServer.config.lobby.allowMovement),
        guardState(AuthCoreServer.config.lobby.allowChat),
        guardState(AuthCoreServer.config.lobby.allowCommands),
        guardState(AuthCoreServer.config.lobby.allowBlockBreaking),
        guardState(AuthCoreServer.config.lobby.allowBlockInteraction),
        guardState(AuthCoreServer.config.lobby.allowItemUse),
        guardState(AuthCoreServer.config.lobby.allowItemDrop),
        guardState(AuthCoreServer.config.lobby.allowAttackingPlayer),
        lobbyUsage());

    AuthCoreServer.LOGGER.toUser(
        true, user.connection, AuthCoreServer.messages.promptUserWelcomeLobbyUser);

    // Immediate clickable auth "button" in chat (context-aware): the command shape matches
    // the server's configured authentication requirements (2FA/captcha/password rules), so
    // the player always sees the exact command they need. Click it and it fills the input.
    try {
      AuthCoreServer.LOGGER.toUser(true, user.connection, buildAuthButton(user));
    } catch (RuntimeException ignored) {
      // the clickable button line is best-effort
    }

    if (AuthCoreServer.config.lobby.timeout.enabled) this.handleTimeout();

    // ATOMIC registration: putIfAbsent closes the theoretical double-lock window where two
    // threads pass the isInLobby fast-path above simultaneously - the loser unwinds its
    // armed tasks and leaves without corrupting the winner's snapshot state.
    if (Lobby.users.putIfAbsent(this.user.username, this) != null) {
      AuthCoreServer.LOGGER.debug(
          false, "{} | duplicate limbo lock raced - keeping the first lock", this.user.username);
      this.cancel();
      return;
    }

    // Handle 2FA: generate a TOTP secret and send the setup link on first join
    if (!user.isRegistered.get()
        && AuthCoreServer.config.session.authentication.allowTOTPSupport
        && user.authSecret == null) {
      user.authSecret = Security.TOTPManager.getSecret();
      String qrURL = Security.TOTPManager.getQrUrl(user.username, user.authSecret);

      AuthCoreServer.LOGGER.toUser(
          true, user.connection, AuthCoreServer.messages.promptUser2faSetup, qrURL);
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

    ServerPlayer player = this.user.player.get();
    if (player == null) return;

    // Resolve the server instance defensively: the world/player suppliers can legitimately
    // resolve to null while a player is mid-join (loader-dependent), which used to NPE here
    // ("Cannot invoke ServerLevel.getServer() because Supplier.get() is null") and aborted
    // placeNewPlayer. Fall back to the reflective player lookup, then stay in the player's
    // current level when no server is resolvable - the limbo teleport is best-effort anyway.
    MinecraftServer server = this.user.server.get();
    if (server == null) server = net.ded3ec.compat.Compat.getServer(player);

    ServerLevel world = net.ded3ec.compat.Compat.playerLevel(player);
    BlockPos blockPos = player.blockPosition();

    // Limbo logic (best-effort: an invalid configured dimension falls through to the
    // player's current position instead of leaving the lobby unanchored).
    if (AuthCoreServer.config.lobby.limboConfig.enabled
        && (!this.user.isRegistered.get()
            || !AuthCoreServer.config.lobby.limboConfig.onlyOnFirstTime)) {

      String raw = AuthCoreServer.config.lobby.limboConfig.location.dimension.trim().toLowerCase();

      Object id = net.ded3ec.compat.Compat.tryParseDimension(raw);
      if (id != null) {
        Object worldKey = net.ded3ec.compat.Compat.worldKey(id);
        ServerLevel limboWorld =
            server != null ? net.ded3ec.compat.Compat.getWorld(server, worldKey) : null;
        if (limboWorld != null) {
          world = limboWorld;

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
      }
    }

    // Always anchor the lobby position - even when the limbo teleport was skipped (e.g. the
    // returning-player "only-on-first-time" rule). Without an anchor, teleport-back and the
    // per-tick re-assert guard are no-ops and the client can run free on its own prediction
    // while the server entity stays frozen.
    if (this.position == null) this.position = blockPos;

    // Skip the actual teleport while the player's level is not resolvable yet (mid-join) -
    // the anchor is still stored, so the per-tick re-assert guard re-teleports as soon as
    // the level is available.
    if (world != null) this.snapshot.teleport(player, blockPos, world);
  }

  /**
   * Places the invisible BARRIER anti-float platform under the limbo player so vanilla's
   * "Flying is not enabled" floating kick can never hit an unauthenticated player who
   * logged out mid-air. Diving players are stood on a platform at the liquid surface
   * instead of being left underwater. The original block is saved and restored after the
   * authentication flow completes (see {@link #unlock()}).
   */
  public void placeLimboPlatform() {
    ServerPlayer player = this.user.player.get();
    if (player == null) return;
    ServerLevel world = net.ded3ec.compat.Compat.playerLevel(player);
    if (world == null) return;

    BlockPos anchor = this.position != null ? this.position : player.blockPosition();
    BlockPos below = anchor.below();

    // Diving case: when the anchor is inside a liquid, stand the player on a platform at
    // the surface (scan up to the first non-liquid block) instead of leaving them
    // underwater in the limbo.
    if (isLiquidBlock(world, below)) {
      BlockPos surface = below;
      while (surface.getY() < net.ded3ec.compat.Compat.getMaxBuildHeight(world)
          && isLiquidBlock(world, surface)) {
        surface = surface.above();
      }
      if (surface.getY() < net.ded3ec.compat.Compat.getMaxBuildHeight(world)) {
        below = surface;
        // Park the player on the platform, just above the surface.
        net.ded3ec.compat.Compat.teleport(
            player,
            world,
            anchor.getX() + 0.5,
            below.getY() + 1.0,
            anchor.getZ() + 0.5,
            net.ded3ec.compat.Compat.getYaw(player),
            net.ded3ec.compat.Compat.getPitch(player));
      }
    }

    BlockState original = world.getBlockState(below);
    if (original.is(Blocks.BARRIER)) return; // another platform already occupies the spot

    this.platformPos = below;
    this.platformOriginal = original;
    this.platformWorld = world;
    world.setBlock(below, Blocks.BARRIER.defaultBlockState(), 3);
    AuthCoreServer.LOGGER.debug(
        false,
        "{} | anti-float platform placed at {} (was {})",
        this.user.username,
        below,
        original);
  }

  /** Whether the block at the position is a liquid (water/lava). */
  private static boolean isLiquidBlock(ServerLevel world, BlockPos pos) {
    BlockState state = world.getBlockState(pos);
    return !state.isAir() && !state.getFluidState().isEmpty();
  }

  /** Restores the original block at a platform position (only if our barrier is still there). */
  private static void restorePlatformBlock(ServerLevel world, BlockPos pos, BlockState original) {
    if (world == null || pos == null) return;
    BlockState current = world.getBlockState(pos);
    if (current.is(Blocks.BARRIER)) {
      world.setBlock(pos, original, 3);
      AuthCoreServer.LOGGER.debug(false, "Anti-float platform restored at {} (was {})", pos, original);
    }
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

    // Leave the restricted state FIRST: every attack/block/movement guard gates on
    // isInLobby, so a failed restore must never keep the player restricted after login.
    Lobby.users.remove(this.user.username);

    AuthCoreServer.LOGGER.debug(
        false,
        "{} | limbo unlock - lobby.users.size={}",
        this.user.username,
        Lobby.users.size());

    if (player != null && this.snapshot != null) this.snapshot.reset(player);
    else
      AuthCoreServer.LOGGER.debug(
          false,
          "{} left the lobby without a state restore (player or snapshot missing).",
          this.user.username);

    // The auth flow is complete - restore the invisible anti-float platform to its
    // original block shortly afterwards (the player has a moment to land/stabilize).
    if (this.platformPos != null) {
      final BlockPos pos = this.platformPos;
      final BlockState original = this.platformOriginal;
      final ServerLevel world = this.platformWorld;
      this.platformPos = null;
      this.platformOriginal = null;
      this.platformWorld = null;
      long delay = Math.max(AuthCoreServer.config.lobby.antiFloatPlatformDelayMs, 1000);
      TaskScheduler.getInstance()
          .setTimeout(
              () -> restorePlatformBlock(world, pos, original),
              delay);
    }

    // Guaranteed game-mode restore: the lobby forced Adventure and the unlock must ALWAYS
    // give the player back their real mode - even when the snapshot was missing or the
    // reset was skipped (the mixin guard is already inactive - the lobby map is cleared).
    if (player != null && this.originalGameMode != null)
      net.ded3ec.compat.Compat.changeGameMode(player, this.originalGameMode);

    // The limbo session ended cleanly - the crash-safe snapshot is no longer needed.
    try {
      java.nio.file.Files.deleteIfExists(snapshotFile(this.user.uuid));
    } catch (java.io.IOException ignored) {
      // snapshot cleanup is best-effort
    }

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
   * Teleports the player directly back to the anchored lobby position. Used on movement
   * violations - the anchor was already validated as safe when the lobby locked, so this is
   * cheaper and more reliable than re-running the full safe-position search.
   *
   * <p>Throttled by {@code lobby.movementCorrectionIntervalMs}: no matter how fast the
   * client spams movement packets, the snap-back fires at most once per interval. The old
   * per-packet correction sent up to 40 position packets per second and vibrated the
   * player's screen (rubber-banding at 20Hz).
   */
  public void teleportBack() {
    ServerPlayer player = this.user.player.get();
    if (player == null || this.position == null) return;

    long now = System.currentTimeMillis();
    if (now - this.lastCorrectionMs
        < AuthCoreServer.config.lobby.movementCorrectionIntervalMs) return;
    this.lastCorrectionMs = now;

    // Zero the client-visible velocity so a corrected player does not keep sliding/falling
    // into the next violation while the correction is throttled.
    try {
      player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
    } catch (RuntimeException ignored) {
      // velocity zeroing is best-effort
    }

    ServerLevel world = net.ded3ec.compat.Compat.playerLevel(player);
    boolean ok =
        net.ded3ec.compat.Compat.teleport(
            player,
            world,
            this.position.getX() + 0.5,
            this.position.getY() + 0.5,
            this.position.getZ() + 0.5,
            net.ded3ec.compat.Compat.getYaw(player),
            net.ded3ec.compat.Compat.getPitch(player));
    // Guaranteed client correction - snaps the client back even if the teleport API above
    // failed or skipped the client notification on this version.
    net.ded3ec.compat.Compat.correctClientPosition(
        player,
        this.position.getX() + 0.5,
        this.position.getY() + 0.5,
        this.position.getZ() + 0.5,
        net.ded3ec.compat.Compat.getYaw(player),
        net.ded3ec.compat.Compat.getPitch(player));
    if (!ok)
      AuthCoreServer.LOGGER.debug(
          false, "Lobby teleport-back failed for {} - the client may be out of sync.",
          this.user.username);
  }

  /**
   * Checks if the player has moved significantly from their restricted lobby position on
   * ANY axis (X, Y or Z). Sub-block jitter (the client's own position sync) is not a
   * violation - only real movement beyond the tolerance counts.
   *
   * @param newX The new X coordinate from the movement packet.
   * @param newY The new Y coordinate from the movement packet.
   * @param newZ The new Z coordinate from the movement packet.
   * @return {@code true} if the player has moved beyond the tolerance on any axis.
   */
  public boolean isOutsideOfLobbyPos(double newX, double newY, double newZ) {
    // Movement by jailed user event detection!
    double oldX = this.user.player.get().getX();
    double oldY = this.user.player.get().getY();
    double oldZ = this.user.player.get().getZ();

    // Sub-block position jitter is not a violation (client sync)
    return (Math.abs(newX - oldX) > 0.5)
        || (Math.abs(newY - oldY) > 0.5)
        || (Math.abs(newZ - oldZ) > 0.5);
  }

  /**
   * Whether the given position is more than ~1 block away from the anchored lobby position
   * (the spot the player was teleported to on lock) on ANY axis. Used by the per-tick
   * re-assert guard - unlike {@link #isOutsideOfLobbyPos} it compares against the ANCHOR in
   * 3D, so any drift (packet bypass, hack-client flight, teleports, server-side physics)
   * is detected and reverted on every axis.
   */
  public boolean isFarFromLobbyPos(double x, double y, double z) {
    return isFarFromLobbyPos(x, y, z, 1.0);
  }

  /**
   * Radius-aware variant used for the client snap-back decision: the movement packets are
   * always cancelled (the server entity never leaves the anchor), but the client-side
   * position correction only fires once the client drifted beyond the given radius.
   */
  public boolean isFarFromLobbyPos(double x, double y, double z, double radius) {
    if (this.position == null) return false;
    double dx = x - (this.position.getX() + 0.5);
    double dy = y - (this.position.getY() + 0.5);
    double dz = z - (this.position.getZ() + 0.5);
    return (dx * dx + dy * dy + dz * dz) > (radius * radius);
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

    private final GameType gameMode;

    private final boolean operator;
    private int opPermissionLevel = 0;

    /** The vehicle the player was riding before the lobby (re-mounted on unlock). */
    private final net.minecraft.world.entity.Entity vehicle;

    /** Captures the current state of the player and applies lobby-specific status effects. */
    public Snapshot(ServerPlayer player) {

      MinecraftServer server = net.ded3ec.compat.Compat.getServer(player);

      // --- POSITION & DIMENSION ---
      this.blockPos = player.blockPosition();
      this.dimensionKey =
          net.ded3ec.compat.Compat.worldRegistryKey(
              net.ded3ec.compat.Compat.playerLevel(player));

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

        // Empty the player's inventory for the whole lobby session. Previously only the
        // COPY was taken and the live inventory was left intact - the client could still
        // move/equip those items (shift-clicks slipped past the click guards), which
        // desynced the client's view and left GHOST ITEMS (e.g. an elytra visually worn
        // but absent server-side) after the restore. A truly empty inventory cannot be
        // manipulated, so the restore always matches the captured state.
        inv.clearContent();
        player.containerMenu.broadcastChanges();
      }

      // --- HEALTH & FOOD ---
      this.health = player.getHealth();
      this.foodLevel = player.getFoodData().getFoodLevel();
      this.saturation = player.getFoodData().getSaturationLevel();

      // --- EXPERIENCE ---
      this.xpLevel = player.experienceLevel;
      this.xpProgress = player.experienceProgress;
      this.totalXp = player.totalExperience;

      // --- FIRE / FREEZE ---
      this.fireTicks = player.getRemainingFireTicks();
      this.frozenTicks = net.ded3ec.compat.Compat.getFrozenTicks(player);

      // --- VEHICLE (restored safely on unlock) ---
      this.vehicle = player.getVehicle();

      // --- GAMEMODE ---
      this.gameMode = player.gameMode.getGameModeForPlayer();

      // --- OPERATOR STATUS ---
      this.operator = net.ded3ec.compat.Compat.isOperator(server, player);

      if (this.operator)
        this.opPermissionLevel = net.ded3ec.compat.Compat.getOperatorLevel(server, player);

      // --- CLEAR EFFECTS ---
      player.removeAllEffects();

      // --- DISMOUNT ---
      // A player riding a vehicle into the lobby must not keep riding (the lobby locks
      // movement and blocks mounting - restore control of their entity).
      if (player.isPassenger()) player.stopRiding();

      // --- CLOSE OPEN CONTAINERS ---
      // A chest/hopper/dispenser menu left open across the lock would keep flowing items:
      // carried stacks could be deposited and shift-clicks could pull from the world
      // container while unauthenticated. Force-close it before anything else runs.
      try {
        net.ded3ec.compat.Compat.forceCloseInventory(player);
      } catch (RuntimeException ignored) {
        // best-effort - the click guards remain as the second layer
      }

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

      // --- FORCE ADVENTURE MODE ---
      // The game-mode-change mixin only blocks changes AWAY from adventure while in the
      // lobby - the initial switch must be applied here or the player stays in their
      // previous mode (e.g. survival) and the setting is a no-op.
      if (AuthCoreServer.config.lobby.forceAdventureMode && this.gameMode != GameType.ADVENTURE)
        net.ded3ec.compat.Compat.changeGameMode(player, GameType.ADVENTURE);
    }

    /**
     * Reverts the player to the state stored in this snapshot.
     *
     * <p>Clears lobby status effects, restores the inventory, experience, health, and teleports the
     * player back to their original position and dimension.
     */
    public void reset(ServerPlayer player) {

      MinecraftServer server = net.ded3ec.compat.Compat.getServer(player);

      // --- CLEAR LOBBY EFFECTS ---
      player.removeAllEffects();
      player.setInvulnerable(false);

      // --- RESTORE GAMEMODE (FIRST - never skipped by a later failure) ---
      // The lobby forced Adventure; if any restore step below throws, the player must
      // still get their real game mode back - otherwise they stay unable to attack or
      // break blocks after login.
      net.ded3ec.compat.Compat.changeGameMode(player, gameMode);

      // --- RESTORE INVENTORY ---
      if (AuthCoreServer.config.lobby.hideInventory && inventory != null && !inventory.isEmpty()) {
        try {
          Inventory inv = net.ded3ec.compat.Compat.getInventory(player);
          inv.clearContent();

          for (int i = 0; i < inventory.size(); i++) inv.setItem(i, inventory.get(i).copy());
          player.containerMenu.broadcastChanges();
        } catch (RuntimeException ignored) {
          // inventory restore is best-effort
        }
      }

      // --- RESTORE EFFECTS ---
      try {
        for (MobEffectInstance effect : effects)
          player.addEffect(new MobEffectInstance(effect));
      } catch (RuntimeException ignored) {
        // effect restore is best-effort
      }

      // --- RESTORE DIMENSION ---
      ServerLevel world = net.ded3ec.compat.Compat.getWorld(server, this.dimensionKey);

      // Only the teleport-dependent steps need the original world. The state restores below
      // (game mode, health, food, xp, fire/freeze, operator status) must ALWAYS run - skipping
      // them on a failed world lookup left logged-in players stuck in the limbo dimension in
      // Adventure mode, unable to attack or break blocks.
      if (world != null) {
        this.teleport(player, this.blockPos, world);

        // --- SAFE RESTORE (flying / riding / swimming) ---
        // The player must NEVER die or be stranded by the restore itself:
        //  - airborne in survival (e.g. was gliding with an elytra or mid-jump) -> land on the
        //    nearest safe ground instead of falling
        //  - inside a wall (block placed while they were in the lobby) -> move to safe ground
        //  - in water -> stay in the water column, never suffocate, never fall
        safeLandIfNeeded(player, world);

        // Re-mount the vehicle the player was riding before the lobby (if it is still alive
        // and in the same world). After unlock the lobby mounting restriction no longer applies.
        if (this.vehicle != null
            && this.vehicle.isAlive()
            && net.ded3ec.compat.Compat.entityLevel(this.vehicle) == world
            && !player.isPassenger()) {
          try {
            player.startRiding(this.vehicle);
          } catch (RuntimeException ignored) {
            // mount restore is best-effort
          }
        }
      } else
        AuthCoreServer.LOGGER.debug(
            false,
            "Lobby restore for {} could not resolve the original world - state restored in place.",
            player.getName().getString());

      // Never deal fall damage from the restore itself (reflective: the fallDistance field is
      // float on 1.16-1.21.x but double on 1.21.11+/26.x - a direct assignment throws
      // NoSuchFieldError on the older endpoints of the range jar)
      net.ded3ec.compat.Compat.setFallDistance(player, 0);

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

      // --- RESTORE OPERATOR STATUS ---
      // Re-grant only when no admin explicitly deopped the account WHILE it sat in the
      // limbo (tracked by PlayerListOpMixin): the snapshot was captured at LOCK time, so
      // blindly restoring it would resurrect operator powers over a concurrent deop.
      if (this.operator && !ADMIN_DEOPS_DURING_LIMBO.remove(player.getUUID()))
        net.ded3ec.compat.Compat.addToOperators(server, player, this.opPermissionLevel);
    }

    /**
     * Guarantees the restored player is on safe ground, in water, or flying-capable - never
     * falling, suffocating or stranded (handles flying/riding/swimming restore edge cases).
     */
    private void safeLandIfNeeded(ServerPlayer player, ServerLevel world) {
      try {
        boolean flying =
            net.ded3ec.compat.Compat.getAbilities(player) != null
                && net.ded3ec.compat.Compat.getAbilities(player).flying;

        // Inside a wall (blocks may have changed while the player was in the lobby) -> find
        // safe ground above and move there instead of suffocating.
        if (player.isInWall()) {
          BlockPos safe = getGroundAbove(world, player.blockPosition());
          if (safe != null) {
            this.teleport(player, safe.above(), world);
            return;
          }
        }

        // Airborne in survival (was gliding / jumping / falling) -> land on safe ground.
        if (!flying
            && !player.isInWater()
            && !/*? if < 1.20.2 {*//*player.isOnGround()
            *//*?} else {*/player.onGround()/*?}*/) {
          BlockPos ground = getGroundBelow(world, player.blockPosition());
          if (ground != null) {
            this.teleport(player, ground.above(), world);
          }
        }
      } catch (RuntimeException ignored) {
        // safe-landing is best-effort - never break the unlock
      }
    }

    /**
     * Internal helper to execute the player teleportation with consistent offsets.
     *
     * @param player The player to teleport.
     * @param pos The target block position.
     * @param world The target world.
     */
    private void teleport(ServerPlayer player, BlockPos pos, ServerLevel world) {
      boolean ok =
          net.ded3ec.compat.Compat.teleport(
              player,
              world,
              pos.getX() + 0.5,
              pos.getY() + 0.5,
              pos.getZ() + 0.5,
              net.ded3ec.compat.Compat.getYaw(player),
              net.ded3ec.compat.Compat.getPitch(player));
      if (!ok)
        AuthCoreServer.LOGGER.debug(
            false, "Lobby teleport failed for {} - teleport API unavailable on this version.",
            player.getName().getString());
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
              || !/*? if < 1.20.2 {*//*player.isOnGround()
              *//*?} else {*/player.onGround()/*?}*/;

      boolean inGap =
          (net.ded3ec.compat.Compat.isGliding(player) || !/*? if < 1.20.2 {*//*player.isOnGround()
              *//*?} else {*/player.onGround()/*?}*/)
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
