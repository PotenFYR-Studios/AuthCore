package in.potenfyr.authcore.models;


import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import in.potenfyr.authcore.AuthCoreServer;
import in.potenfyr.authcore.util.TimeManager;
import in.potenfyr.authcore.security.Security;
import in.potenfyr.authcore.util.TaskScheduler;
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
import in.potenfyr.authcore.compat.Compat;

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

  /**
   * Set while AuthCore itself applies the limbo effects (invisibility / blindness): with
   * {@code lobby.prevent-status-effect} every other effect is refused for a limbo player
   * (LobbyEffectMixin), but the limbo's own effects must still go through.
   */
  public static final ThreadLocal<Boolean> APPLYING_LIMBO_EFFECTS =
      ThreadLocal.withInitial(() -> Boolean.FALSE);

  /** Applies an effect on behalf of the limbo itself (bypasses prevent-status-effect). */
  public static void applyLimboEffect(net.minecraft.world.entity.LivingEntity entity, MobEffectInstance effect) {
    if (entity == null || effect == null) return;
    APPLYING_LIMBO_EFFECTS.set(Boolean.TRUE);
    try {
      entity.addEffect(effect);
    } catch (RuntimeException ignored) {
      // could not apply effect
    } finally {
      APPLYING_LIMBO_EFFECTS.set(Boolean.FALSE);
    }
  }

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
   * Exact feet height of {@link #position} (NaN until computed). The block Y is only the feet
   * BLOCK: a partial support (slab/carpet/snow/stair) is walked 0.5-0.94 above its block floor, and
   * every anchor teleport / drift comparison must use the same exact height or the lobby guard
   * re-teleports the player every tick.
   */
  private double positionFeetY = Double.NaN;

  /**
   * True when the limbo teleport was skipped and the anchor is the player's own (exact) position -
   * a block-floor snap would embed a player standing on a partial support, so those anchors keep
   * the exact coordinates and the guard compares against them.
   */
  private boolean anchorExact;

  /** Position of the temporary anti-float platform (null when not placed). */
  private transient BlockPos platformPos = null;

  /** The block state that occupied {@link #platformPos} before the platform was placed. */
  private transient BlockState platformOriginal = null;

  /** The world the platform was placed in (restored there after auth). */
  private transient ServerLevel platformWorld = null;

  /**
   * Post-restore safety windows, keyed by player UUID.
   *
   * <p>The limbo restore must never be able to kill a player (issue #11). The collision-aware
   * landing is the primary guard; this window is the safety net for everything it cannot see
   * (a chunk that is still loading, a hazard column, a restore path that threw, a vanilla
   * rubber-band). While a player is inside the window the fall distance is pinned to zero and an
   * unsupported player is re-landed, so a restore can never accumulate lethal fall distance.
   */
  private static final java.util.Map<java.util.UUID, RestoreGuard> RESTORE_GUARDS =
      new ConcurrentHashMap<>();

  /** How long (ms) a restored player stays fall-protected while the landing settles. */
  private static final long RESTORE_GUARD_WINDOW_MS = 4_000L;

  /**
   * Last limbo-restore timestamp per player, kept briefly so a death can still be attributed to
   * the restore after the fall-protection window closed (a void fall that only becomes lethal
   * later is exactly the issue #11 report that needs evidence). Bounded: pruned on every tick.
   */
  private static final java.util.Map<java.util.UUID, Long> RECENT_RESTORES =
      new ConcurrentHashMap<>();

  /** How long (ms) a restore stays attributable in the death log. */
  private static final long RECENT_RESTORE_KEEP_MS = 120_000L;

  /** Minimum spacing (ms) between landing retries inside the restore window. */
  private static final long RESTORE_GUARD_RETRY_MS = 400L;

  /**
   * Upper bound on the anti-float platform restore retries while a player still stands on the
   * temporary block. After this many seconds the platform is restored anyway (a permanently
   * leaking barrier is worse than the one-block drop it causes).
   */
  private static final int PLATFORM_RESTORE_MAX_ATTEMPTS = 60;

  /** One restored player's fall-safety window. */
  private static final class RestoreGuard {
    final java.lang.ref.WeakReference<ServerPlayer> player;
    final long restoredAtMs;
    final long expiresAtMs;

    /** Dimension the restore target belongs to: a re-land must never use another level's geometry. */
    final Object dimension;

    long nextRetryMs;
    boolean landed;

    RestoreGuard(
        ServerPlayer player, long restoredAtMs, long expiresAtMs, Object dimension) {
      this.player = new java.lang.ref.WeakReference<>(player);
      this.restoredAtMs = restoredAtMs;
      this.expiresAtMs = expiresAtMs;
      this.dimension = dimension;
    }
  }

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
  private static Object[] authButtonArgs(User user) {
    boolean registered = user.isRegistered.get();
    StringBuilder cmd = new StringBuilder(registered ? "/login" : "/register");
    cmd.append(" <password>");
    // <confirm> only when password confirmation is actually required (it was always shown).
    if (!registered && AuthCoreServer.config.session.authentication.registerPasswordConfirmation)
      cmd.append(" <confirm>");
    // A 2FA code is part of the command only when this account has (or is being given) a TOTP
    // secret - the hint used to be shown to every player whenever TOTP support was enabled.
    boolean totp =
        AuthCoreServer.config.session.authentication.allowTOTPSupport
            && user.authSecret != null
            && !user.authSecret.isBlank();
    if (totp) cmd.append(" <2fa-code>");
    String hint = totp ? AuthCoreServer.messages.promptUserLobby2faHint.message.text : "";
    return new Object[] {cmd.toString(), hint == null ? "" : hint};
  }

  /** The configurable limbo hint (messages.conf: promptUserLobbyLoginButton / RegisterButton). */
  private static in.potenfyr.authcore.models.Messages.ColTemplate buildAuthButton(User user) {
    return user.isRegistered.get()
        ? AuthCoreServer.messages.promptUserLobbyLoginButton
        : AuthCoreServer.messages.promptUserLobbyRegisterButton;
  }


  /**
   * Runs a command for the player through the server dispatcher (era-safe - see
   * {@link in.potenfyr.authcore.compat.Compat#runCommand}).
   */
  private static void runCommand(net.minecraft.server.level.ServerPlayer player, String command) {
    in.potenfyr.authcore.compat.Compat.runCommand(player, command);
  }

  /**
   * Persists the player's PRE-LIMBO state to the crash-safe snapshot file. Restored on the next
   * join if the limbo session ended uncleanly (server crash).
   *
   * <p>Every value is taken from the {@link Snapshot}, never from the live player: this runs AFTER
   * the snapshot constructor applied the limbo (inventory emptied, effects replaced by the infinite
   * invisibility, Adventure forced, health topped up, operator removed). Serialising the live player
   * - as earlier builds did - persisted exactly that limbo state, so a crash during the limbo made
   * the recovery wipe the inventory, re-apply Adventure as the "original" game mode, grant a
   * permanent invisibility effect after login and drop the operator status for good.
   */
  public static boolean saveCrashSnapshot(
      ServerPlayer player, java.nio.file.Path file, Snapshot snap) {
    if (snap == null) return false;
    try {
      java.nio.file.Files.createDirectories(file.getParent());
      com.google.gson.JsonObject o = new com.google.gson.JsonObject();
      o.addProperty("format", 2);
      if (snap.gameMode != null) o.addProperty("gameMode", snap.gameMode.getName());
      o.addProperty("invulnerable", snap.invulnerable);
      o.addProperty("health", snap.health);
      o.addProperty("dead", snap.dead);
      o.addProperty("food", snap.foodLevel);
      o.addProperty("saturation", snap.saturation);
      o.addProperty("xpLevel", snap.xpLevel);
      o.addProperty("xpProgress", snap.xpProgress);
      o.addProperty("totalXp", snap.totalXp);
      o.addProperty("x", snap.x);
      o.addProperty("y", snap.y);
      o.addProperty("z", snap.z);
      o.addProperty("operator", snap.operator);
      o.addProperty("opLevel", snap.opPermissionLevel);
      o.addProperty("opBypass", snap.opBypassesPlayerLimit);
      if (snap.dimensionKey != null) {
        // ResourceKey.toString() = "ResourceKey[minecraft:dimension / minecraft:overworld]"
        // (identical format in every era) - extract the value location for the file.
        String s = String.valueOf(snap.dimensionKey);
        int sep = s.indexOf("/ ");
        int end = s.lastIndexOf(']');
        if (sep >= 0 && end > sep) o.addProperty("dimension", s.substring(sep + 2, end));
      }

      // Only the snapshot OWNS the items when the limbo hid the inventory. Without hide-inventory
      // the live inventory was never touched and vanilla's own player data is authoritative, so it
      // is not duplicated into the file (a recovery would otherwise roll it back).
      if (AuthCoreServer.config.lobby.hideInventory && snap.inventory != null) {
        com.google.gson.JsonArray inv = new com.google.gson.JsonArray();
        for (ItemStack stack : snap.inventory)
          inv.add(in.potenfyr.authcore.compat.Compat.itemStackToBase64(stack));
        o.add("inventory", inv);
      }

      com.google.gson.JsonArray fx = new com.google.gson.JsonArray();
      if (snap.effects != null)
        for (MobEffectInstance e : snap.effects)
          fx.add(in.potenfyr.authcore.compat.Compat.effectToBase64(e));
      o.add("effects", fx);

      // Atomic replace: a crash DURING the write must never leave a truncated file that the next
      // join cannot parse (the recovery would then be skipped and the limbo state would stick).
      java.nio.file.Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
      java.nio.file.Files.writeString(tmp, new com.google.gson.Gson().toJson(o));
      try {
        java.nio.file.Files.move(
            tmp,
            file,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      } catch (java.nio.file.AtomicMoveNotSupportedException notAtomic) {
        java.nio.file.Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
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

      // Files written before format 2 serialised the LIVE player after the limbo was applied (see
      // saveCrashSnapshot): their game mode, inventory and effects are the LIMBO's, not the
      // player's. Only the safe parts of such a file are applied - the forced Adventure mode is
      // replaced by the server default, the empty inventory and the infinite limbo effects are
      // never re-applied over the player data.
      boolean legacy = !o.has("format");
      if (o.has("gameMode")) {
        net.minecraft.world.level.GameType mode =
            net.minecraft.world.level.GameType.byName(
                o.get("gameMode").getAsString(), net.minecraft.world.level.GameType.SURVIVAL);
        if (legacy
            && mode == net.minecraft.world.level.GameType.ADVENTURE
            && AuthCoreServer.config.lobby.forceAdventureMode) {
          MinecraftServer srv = in.potenfyr.authcore.compat.Compat.getServer(player);
          mode =
              srv != null ? srv.getDefaultGameType() : net.minecraft.world.level.GameType.SURVIVAL;
        }
        in.potenfyr.authcore.compat.Compat.changeGameMode(player, mode);
      }

      // Restore (or conservatively clear) the invulnerability flag: a crash mid-limbo
      // leaves the limbo-forced flag persisted in the vanilla player data - without an
      // explicit reset here the fresh lock below would capture it as "pre-limbo" state
      // and re-grant permanent invulnerability after authentication. Snapshots written
      // before this field existed (no "invulnerable" key) clear the flag, matching the
      // old hardcoded-false restore.
      boolean preLobbyInvulnerable = o.has("invulnerable") && o.get("invulnerable").getAsBoolean();
      /*? if < 26.1 {*/
      player.setInvulnerable(preLobbyInvulnerable);
      /*?} else {*/
      /*in.potenfyr.authcore.compat.Compat.setInvulnerable(player, preLobbyInvulnerable);
      *//*?}*/

      net.minecraft.world.entity.player.Inventory liveInv =
          in.potenfyr.authcore.compat.Compat.getInventory(player);
      if (!legacy && o.has("inventory") && liveInv != null) {
        liveInv.clearContent();
        com.google.gson.JsonArray arr = o.getAsJsonArray("inventory");
        for (int i = 0; i < arr.size() && i < liveInv.getContainerSize(); i++)
          liveInv.setItem(i, in.potenfyr.authcore.compat.Compat.itemStackFromBase64(arr.get(i).getAsString()));
      }

      player.removeAllEffects();
      if (!legacy && o.has("effects"))
        for (com.google.gson.JsonElement e : o.getAsJsonArray("effects")) {
          net.minecraft.world.effect.MobEffectInstance fx =
              in.potenfyr.authcore.compat.Compat.effectFromBase64(e.getAsString());
          if (fx != null) player.addEffect(fx);
        }

      boolean wasDead = o.has("dead") && o.get("dead").getAsBoolean();
      if (o.has("health") && !wasDead && !legacy) {
        float hp = o.get("health").getAsFloat();
        // A dead (0 HP) snapshot is never "restored" to life (issue #11 - that is a free revive),
        // and a tampered/corrupt value must not restore NaN, negative or absurd health: those
        // keep the player's current health; everything else is capped at the max health.
        if (Float.isFinite(hp) && hp > 0.0f) player.setHealth(Math.min(hp, player.getMaxHealth()));
      }
      if (o.has("food")) player.getFoodData().setFoodLevel(o.get("food").getAsInt());
      if (o.has("saturation")) player.getFoodData().setSaturation(o.get("saturation").getAsFloat());
      if (o.has("xpLevel")) player.experienceLevel = o.get("xpLevel").getAsInt();
      if (o.has("xpProgress")) player.experienceProgress = o.get("xpProgress").getAsFloat();
      if (o.has("totalXp")) player.totalExperience = o.get("totalXp").getAsInt();

      if (o.has("dimension") && o.has("x") && o.has("y") && o.has("z")) {
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> key =
            in.potenfyr.authcore.compat.Compat.parseDimensionKey(o.get("dimension").getAsString());
        if (key != null) {
          net.minecraft.server.level.ServerLevel world =
              in.potenfyr.authcore.compat.Compat.getWorld(
                  in.potenfyr.authcore.compat.Compat.getServer(player),
                  key);
          if (world != null)
            in.potenfyr.authcore.compat.Compat.teleport(
                player,
                world,
                o.get("x").getAsDouble(),
                o.get("y").getAsDouble(),
                o.get("z").getAsDouble(),
                in.potenfyr.authcore.compat.Compat.getYaw(player),
                in.potenfyr.authcore.compat.Compat.getPitch(player));
        }
      }

      // Operator status removed by safe-operators at lock time (ops.json was edited) - give it back,
      // or a crash during the limbo would have deopped the account permanently.
      if (o.has("operator") && o.get("operator").getAsBoolean()) {
        MinecraftServer srv = in.potenfyr.authcore.compat.Compat.getServer(player);
        if (srv != null && !in.potenfyr.authcore.compat.Compat.isOperator(srv, player))
          in.potenfyr.authcore.compat.Compat.addToOperators(
              srv,
              player,
              o.has("opLevel") ? o.get("opLevel").getAsInt() : 4,
              o.has("opBypass") && o.get("opBypass").getAsBoolean());
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
      // Retry on the next tick. An online, unauthenticated player must NEVER be left outside the
      // limbo: isAuthenticated reads true for any ACTIVE player that is not in the lobby map, so
      // every guard would silently skip and the player could roam unauthenticated. If the entity
      // never becomes resolvable, kick the session instead of leaving it unrestricted.
      TaskScheduler.getInstance()
          .setTimeout(
              () -> {
                if (this.user.isInLobby.get() || !this.user.isActive) return;
                if (this.user.player.get() != null) {
                  this.lock();
                  return;
                }
                AuthCoreServer.LOGGER.debug(
                    false,
                    "Limbo lock failed for {} - player entity never became resolvable, kicking.",
                    this.user.username);
                AuthCoreServer.LOGGER.toKick(
                    false,
                    this.user.connection,
                    AuthCoreServer.messages.promptUserAuthenticationExpiredTimeout,
                    "a moment");
              },
              50L);
      return;
    }

    // Never re-lock an already-locked player: a second lock would capture the ALREADY
    // FORCED Adventure mode as the "original" game mode, so the unlock would restore
    // Adventure instead of the player's real mode - leaving them unable to break blocks
    // or attack anything after login ("the limbo state saved as permanent").
    if (this.user.isInLobby.get()) return;

    // A Lobby instance is REUSED for every session of the same account (User#lobby is created
    // once), so the previous session's limbo anchor must never leak into this one: a stale anchor
    // made the next lock teleport the player - and place the anti-float platform - at the PREVIOUS
    // session's coordinates while the unlock compared the platform against the NEW snapshot, so the
    // temporary barrier was never removed and could drop the player (issue #11 follow-up).
    this.position = null;
    this.positionFeetY = Double.NaN;
    this.anchorExact = false;
    this.platformPos = null;
    this.platformOriginal = null;
    this.platformWorld = null;

    // Publish the limbo entry BEFORE touching the player. isInLobby is DEFINED as map membership
    // (User#isInLobby), so an exception anywhere between the snapshot and the previously-late
    // registration left an ACTIVE, unauthenticated player OUTSIDE the limbo - isAuthenticated then
    // reads true and every limbo guard fast-paths out (free roam). Registering first and unwinding
    // with a kick on failure makes that structurally impossible.
    Lobby previous = Lobby.users.putIfAbsent(this.user.username, this);
    if (previous != null && previous != this) {
      // A previous session of this account leaked its entry (crash/stale leave): take it over.
      // Otherwise every later session of the account would read as "already in the limbo" and
      // never be restricted again.
      Lobby.users.put(this.user.username, this);
      AuthCoreServer.LOGGER.warn(
          false,
          "{} | replaced a stale limbo entry from a previous session",
          this.user.username);
    }

    try {
      lockSession(player, user);
      // The client got its command tree before the limbo existed: re-send it so /register or
      // /login show up (and every gameplay command the limbo blocks disappears).
      in.potenfyr.authcore.compat.Compat.refreshCommandTree(player);
    } catch (RuntimeException | LinkageError err) {
      // FAIL CLOSED: release the reservation, stop the armed tasks and kick. An unauthenticated
      // player must never stay online outside the limbo (that was the bypass window).
      Lobby.users.remove(this.user.username, this);
      this.cancel();
      AuthCoreServer.LOGGER.error(
          false,
          "{} | limbo lock failed - kicking to fail closed: {}",
          this.user.username,
          String.valueOf(err));
      AuthCoreServer.LOGGER.toKick(
          false,
          this.user.connection,
          AuthCoreServer.messages.promptUserAuthenticationExpiredTimeout,
          "a moment");
    }
  }

  /** The lock body (snapshot -> limbo teleport -> prompts/tasks), split out so a failure can be
   *  unwound and the player kicked instead of being left unguarded. */
  private void lockSession(ServerPlayer player, User user) {
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
    // Deleted on unlock; restored on the next join if it still exists. The snapshot's
    // captured PRE-LOBBY invulnerability flag is persisted alongside, so a crash mid-limbo
    // cannot leave the forced shield in the player data after the recovery.
    if (!saveCrashSnapshot(player, snapshotFile(user.uuid), this.snapshot))
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
      AuthCoreServer.LOGGER.toUser(
          true, user.connection, buildAuthButton(user), authButtonArgs(user));
    } catch (RuntimeException ignored) {
      // the clickable button line is best-effort
    }

    if (AuthCoreServer.config.lobby.timeout.enabled) this.handleTimeout();

    // Client companion: open its in-game login/register GUI. The protocol marker existed but was
    // never sent, so the companion GUI never opened on its own.
    try {
      in.potenfyr.authcore.network.AuthInterop.sendCompanion(
          player, in.potenfyr.authcore.security.ClientGuard.MSG_IN_LOBBY);
    } catch (RuntimeException | LinkageError ignored) {
      // companion signalling is best-effort
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
    if (server == null) server = in.potenfyr.authcore.compat.Compat.getServer(player);

    ServerLevel world = in.potenfyr.authcore.compat.Compat.playerLevel(player);
    BlockPos blockPos = player.blockPosition();

    // Limbo logic (best-effort: an invalid configured dimension falls through to the
    // player's current position instead of leaving the lobby unanchored).
    if (AuthCoreServer.config.lobby.limboConfig.enabled
        && (!this.user.isRegistered.get()
            || !AuthCoreServer.config.lobby.limboConfig.onlyOnFirstTime)) {

      String raw = AuthCoreServer.config.lobby.limboConfig.location.dimension.trim().toLowerCase();

      Object id = in.potenfyr.authcore.compat.Compat.tryParseDimension(raw);
      if (id != null) {
        Object worldKey = in.potenfyr.authcore.compat.Compat.worldKey(id);
        ServerLevel limboWorld =
            server != null ? in.potenfyr.authcore.compat.Compat.getWorld(server, worldKey) : null;
        if (limboWorld != null) {
          world = limboWorld;

          if (this.position == null) {
            blockPos =
                in.potenfyr.authcore.compat.Compat.blockPosFloored(
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
    if (this.position == null) {
      this.position = blockPos;
      this.anchorExact = true;
    }

    // Skip the actual teleport while the player's level is not resolvable yet (mid-join) -
    // the anchor is still stored, so the per-tick re-assert guard re-teleports as soon as
    // the level is available.
    if (world != null) {
      if (this.anchorExact && player != null) {
        // The limbo teleport was skipped (limbo disabled / only-on-first-time): keep the player's
        // EXACT position. A block-floor snap would embed a player standing on a partial support
        // (slab/carpet/stair) half a block - and the anchor/guard must use the same height.
        this.positionFeetY = player.getY();
        Snapshot.teleportExact(player, world, player.getX(), player.getY(), player.getZ(), blockPos);
      } else {
        // Exact feet height of the anchor (partial supports walk above their block floor); every
        // guard comparison must use the same height the teleport used.
        this.positionFeetY = Snapshot.exactFeetY(world, blockPos);
        Snapshot.teleport(player, blockPos, world);
      }
      AuthCoreServer.LOGGER.debug(
          false,
          "{} | limbo anchor {} (feet block), exact feet y={}, solid ground below={}",
          this.user.username,
          blockPos,
          this.positionFeetY,
          isSolidGround(world, blockPos.below()));
    }
  }

  /** The anchor feet height, falling back to the block Y when it was never computed. */
  private double anchorFeetY() {
    if (this.position == null) return 0.0;
    return Double.isNaN(this.positionFeetY) ? this.position.getY() : this.positionFeetY;
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
    ServerLevel world = in.potenfyr.authcore.compat.Compat.playerLevel(player);
    if (world == null) return;

    BlockPos anchor = this.position != null ? this.position : player.blockPosition();
    BlockPos below = anchor.below();

    // The limbo anchor is normally a safe standing position, so the block under it is REAL ground:
    // there is nothing to protect against. Replacing that block with an invisible BARRIER made the
    // player look like they floated over a hole (barriers have a collision shape but never occlude,
    // so vanilla even logs "standing on air" and re-sends the blocks) while preventing nothing.
    // Only build the platform when the player genuinely has nothing solid under them.
    if (isSolidGround(world, below)) return;

    // Diving case: when the anchor is inside a liquid, stand the player on a platform at
    // the surface (scan up to the first non-liquid block) instead of leaving them
    // underwater in the limbo.
    if (isLiquidBlock(world, below)) {
      BlockPos surface = below;
      int maxY = in.potenfyr.authcore.compat.Compat.getMaxBuildHeight(world);
      while (surface.getY() < maxY && isLiquidBlock(world, surface)) {
        surface = surface.above();
      }
      // Only an open surface may be stood on - never replace a solid roof (ice, cave ceiling).
      if (surface.getY() >= maxY || !world.getBlockState(surface).isAir()) return;
      below = surface;
      // The player is parked ON TOP of the new platform: move the anchor with them, otherwise the
      // per-tick re-assert would keep teleporting them back into the barrier block.
      this.position = below.above();
      this.positionFeetY = this.position.getY();
      in.potenfyr.authcore.compat.Compat.teleport(
          player,
          world,
          this.position.getX() + 0.5,
          this.position.getY(),
          this.position.getZ() + 0.5,
          in.potenfyr.authcore.compat.Compat.getYaw(player),
          in.potenfyr.authcore.compat.Compat.getPitch(player));
    }

    BlockState original = world.getBlockState(below);
    if (original.is(Blocks.BARRIER)) return; // another platform already occupies the spot
    if (isSolidGround(world, below)) return; // never hide real ground under the platform

    if (!world.setBlock(below, Blocks.BARRIER.defaultBlockState(), 3)) {
      // The block never became a barrier (outside the build range / unloaded chunk): keep NO
      // bookkeeping, otherwise the delayed restore would target a phantom platform.
      AuthCoreServer.LOGGER.warn(false, "Anti-float platform could not be placed at {}", below);
      return;
    }
    this.platformPos = below;
    this.platformOriginal = original;
    this.platformWorld = world;
    AuthCoreServer.LOGGER.debug(
        false,
        "{} | anti-float platform placed at {} (was {})",
        this.user.username,
        below,
        original);
  }

  /** Whether the block is real solid ground (a collision shape and no fluid) - a true support. */
  private static boolean isSolidGround(ServerLevel world, BlockPos pos) {
    if (world == null || pos == null) return false;
    BlockState state = world.getBlockState(pos);
    if (!state.getFluidState().isEmpty()) return false;
    // AuthCore's own temporary platform is NOT real ground: counting it as ground made the
    // platform bookkeeping skip it and leak a permanent invisible barrier.
    if (state.is(Blocks.BARRIER)) return false;
    return !state.getCollisionShape(world, pos).isEmpty();
  }

  /** Whether the block at the position is a liquid (water/lava). */
  private static boolean isLiquidBlock(ServerLevel world, BlockPos pos) {
    BlockState state = world.getBlockState(pos);
    return !state.isAir() && !state.getFluidState().isEmpty();
  }

  /**
   * Restores the original block at a platform position (only if our barrier is still there).
   *
   * <p>With {@code guardOccupied} the restore is deferred while a player is still standing on the
   * temporary platform: removing the block under a player is exactly the kind of mod-caused death
   * issue #11 is about. The deferred restore is retried a bounded number of times, then forced (a
   * permanently leaking barrier would be the worse bug).
   */
  private static void restorePlatformBlock(
      ServerLevel world, BlockPos pos, BlockState original, int attempt, boolean guardOccupied) {
    if (world == null || pos == null) return;
    BlockState current = world.getBlockState(pos);
    if (!current.is(Blocks.BARRIER)) return;

    if (guardOccupied) {
      ServerPlayer standing = playerStandingOn(world, pos);
      if (standing != null && attempt < PLATFORM_RESTORE_MAX_ATTEMPTS) {
        AuthCoreServer.LOGGER.debug(
            false,
            "Anti-float platform at {} kept - {} is still standing on it (retry {}).",
            pos,
            standing.getName().getString(),
            attempt + 1);
        TaskScheduler.getInstance()
            .setTimeout(() -> restorePlatformBlock(world, pos, original, attempt + 1, true), 1_000L);
        return;
      }
    }

    world.setBlock(pos, original, 3);
    AuthCoreServer.LOGGER.debug(false, "Anti-float platform restored at {} (was {})", pos, original);
  }

  /**
   * Resolves a live player standing on top of the given block, or null.
   *
   * <p>Bounding-box based on purpose: an exact feet-block match misses a player standing on the
   * platform edge, on a vehicle above it, or on a partial block resting on it - and removing the
   * block under any of those is a mod-caused fall.
   */
  private static ServerPlayer playerStandingOn(ServerLevel world, BlockPos pos) {
    if (world == null || pos == null) return null;
    net.minecraft.world.phys.AABB standingBox =
        new net.minecraft.world.phys.AABB(
            pos.getX(),
            pos.getY() + 1.0,
            pos.getZ(),
            pos.getX() + 1.0,
            pos.getY() + 3.0,
            pos.getZ() + 1.0);
    for (ServerPlayer p : world.players()) {
      if (p == null || p.isRemoved() || p.isSpectator()) continue;
      if (p.getBoundingBox().intersects(standingBox)) return p;
    }
    return null;
  }

  /**
   * Arms the post-restore fall-safety window for a just-restored player.
   *
   * <p>Idempotent and never throws: the window is only a safety net, so a failure here must never
   * affect the restore itself.
   */
  public static void armRestoreGuard(ServerPlayer player) {
    if (player == null) return;
    try {
      long now = System.currentTimeMillis();
      Object dimension =
          in.potenfyr.authcore.compat.Compat.worldRegistryKey(
              in.potenfyr.authcore.compat.Compat.playerLevel(player));
      RESTORE_GUARDS.put(
          player.getUUID(),
          new RestoreGuard(player, now, now + RESTORE_GUARD_WINDOW_MS, dimension));
      RECENT_RESTORES.put(player.getUUID(), Long.valueOf(now));
    } catch (RuntimeException | LinkageError ignored) {
      // the landing pass remains the primary guard
    }
  }

  /**
   * Last-resort restore used when {@link Snapshot#reset} throws: land the player safely, zero the
   * fall distance and arm the fall-safety window. Never throws.
   */
  private static void safeRestoreFallback(ServerPlayer player) {
    if (player == null) return;
    try {
      ServerLevel world = in.potenfyr.authcore.compat.Compat.playerLevel(player);
      if (world != null) Snapshot.safeLandIfNeeded(player, world);
    } catch (RuntimeException | LinkageError ignored) {
      // best-effort - the guard below is the safety net
    }
    try {
      in.potenfyr.authcore.compat.Compat.setFallDistance(player, 0);
    } catch (RuntimeException | LinkageError ignored) {
      // best-effort
    }
    armRestoreGuard(player);
  }

  /**
   * Extra death diagnostic (called from the death mixin): states whether the death happened inside
   * the post-restore safety window, i.e. whether the limbo restore is actually involved.
   *
   * <p>This is the line that makes issue #11 actionable on servers with
   * {@code /gamerule showDeathMessages false}, where vanilla sends an EMPTY death message and
   * nothing reaches the chat or the player's death screen.
   */
  public static void logDeathContext(ServerPlayer player, String cause) {
    if (player == null) return;
    try {
      Long restored = RECENT_RESTORES.get(player.getUUID());
      if (restored == null) return;
      boolean armed = RESTORE_GUARDS.containsKey(player.getUUID());
      AuthCoreServer.LOGGER.warn(
          false,
          "{} died {} ms after the limbo restore (cause={}, fall guard still armed={}) - if this "
              + "is unexpected, report this line on issue #11",
          player.getName().getString(),
          String.valueOf(System.currentTimeMillis() - restored.longValue()),
          cause,
          armed);
    } catch (RuntimeException ignored) {
      // diagnostics are best-effort
    }
  }

  /**
   * Per-tick driver for {@link #RESTORE_GUARDS} (called from the server tick handler).
   *
   * <p>Keeps the fall distance pinned to zero for a few seconds after a limbo restore and re-lands
   * a player the landing pass could not resolve. This is what makes "the restore killed me" an
   * impossibility even when a geometry probe is wrong (chunk not loaded yet, hazard floor, vanilla
   * rubber-band after the teleport) - issue #11.
   */
  public static void tickRestoreGuards(MinecraftServer server) {
    if (server == null) return;
    long now = System.currentTimeMillis();
    // Attribution window for the death diagnostic (bounded - pruned here every tick).
    if (!RECENT_RESTORES.isEmpty())
      RECENT_RESTORES
          .entrySet()
          .removeIf(e -> e.getValue() == null || now - e.getValue().longValue() > RECENT_RESTORE_KEEP_MS);
    if (RESTORE_GUARDS.isEmpty()) return;
    java.util.Iterator<java.util.Map.Entry<java.util.UUID, RestoreGuard>> it =
        RESTORE_GUARDS.entrySet().iterator();
    while (it.hasNext()) {
      java.util.Map.Entry<java.util.UUID, RestoreGuard> entry = it.next();
      RestoreGuard guard = entry.getValue();
      ServerPlayer player = guard.player.get();
      if (player == null || guard.expiresAtMs <= now || player.isRemoved() || !player.isAlive()) {
        it.remove();
        continue;
      }
      try {
        ServerLevel world = in.potenfyr.authcore.compat.Compat.playerLevel(player);
        if (world == null) continue;
        // Never re-land a player with another dimension's geometry (a teleport/portal while the
        // window was open). Keep the guard armed until it expires instead.
        if (guard.dimension != null) {
          Object current = in.potenfyr.authcore.compat.Compat.worldRegistryKey(world);
          if (current != null && !guard.dimension.equals(current)) continue;
        }
        // Whatever vanilla accumulated, it can no longer become lethal fall damage.
        in.potenfyr.authcore.compat.Compat.setFallDistance(player, 0);

        net.minecraft.world.entity.player.Abilities abilities =
            in.potenfyr.authcore.compat.Compat.getAbilities(player);
        boolean safe =
            player.isSpectator()
                || (abilities != null && abilities.flying)
                || player.isPassenger()
                || player.isInWater()
                || Snapshot.hasSupport(player, world);
        if (safe) {
          // The player is grounded (or flying/swimming/riding): the restore is done. Stop pinning
          // the fall distance so normal movement is never affected after this point.
          guard.landed = true;
          it.remove();
          continue;
        }
        if (now < guard.nextRetryMs) continue;
        guard.nextRetryMs = now + RESTORE_GUARD_RETRY_MS;
        BlockPos ground = Snapshot.getGroundBelow(world, player.blockPosition());
        if (ground != null) {
          Snapshot.teleport(player, ground.above(), world);
          guard.landed = true;
          it.remove();
          continue;
        }
        BlockPos fallback = Snapshot.resolveRestoreFallback(world, player.blockPosition());
        if (fallback != null) {
          Snapshot.teleport(player, fallback, world);
          guard.landed = true;
          it.remove();
        }
      } catch (RuntimeException | LinkageError ignored) {
        // the guard is best-effort - never break the server tick
      }
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
    //
    // The release is OWNERSHIP-CHECKED: a stale/second unlock must never delete a newer session's
    // limbo entry (Lobby instances are reused per account, and the old connection's leave can race
    // a rejoin). Only the session that owns the entry is allowed to restore its snapshot.
    Lobby owner = Lobby.users.get(this.user.username);
    if (owner != null && owner != this) {
      AuthCoreServer.LOGGER.debug(
          false,
          "{} | stale unlock ignored - another limbo session owns the entry",
          this.user.username);
      return;
    }
    Lobby.users.remove(this.user.username, this);

    AuthCoreServer.LOGGER.debug(
        false,
        "{} | limbo unlock - lobby.users.size={}",
        this.user.username,
        Lobby.users.size());

    // The restore teleport puts the player back at their pre-lobby position and the safe-landing
    // pass only ever searches THAT column. If AuthCore's own temporary anti-float platform sits in
    // that column (e.g. the default (0,64,0) limbo sits one block above the world spawn), it must
    // be removed FIRST: otherwise the player is either teleported into a solid block, or landed on
    // a temporary barrier that is deleted seconds later and drops them (issue #11 follow-up).
    if (player != null && this.snapshot != null && this.platformPos != null) {
      BlockPos restore = this.snapshot.blockPos;
      if (restore != null
          && this.platformWorld != null
          && this.platformWorld.dimension().equals(this.snapshot.dimensionKey)
          && this.platformPos.getX() == restore.getX()
          && this.platformPos.getZ() == restore.getZ()) {
        ServerPlayer otherStanding = playerStandingOn(this.platformWorld, this.platformPos);
        if (otherStanding != null && otherStanding != player) {
          // Another player is standing on the temporary platform: removing it here would drop
          // them. Leave it to the delayed restore (which re-checks occupancy) instead.
          AuthCoreServer.LOGGER.debug(
              false,
              "Anti-float platform kept - {} is standing on it (deferred restore will re-check).",
              otherStanding.getName().getString());
        } else {
          restorePlatformBlock(this.platformWorld, this.platformPos, this.platformOriginal, 0, false);
          this.platformPos = null;
          this.platformOriginal = null;
          this.platformWorld = null;
        }
      }
    }

    if (player != null && this.snapshot != null) {
      try {
        this.snapshot.reset(player);
      } catch (RuntimeException | LinkageError err) {
        // A restore failure must never leave an authenticated player unguarded, airborne or
        // restricted: log it (issue #11 wants this in latest.log, not only in debug mode) and run
        // the safe-restore fallback. LinkageError covers the era-mismatch failures
        // (NoSuchMethodError/NoSuchFieldError) that a cross-version build can hit.
        AuthCoreServer.LOGGER.error(
            false,
            "{} | limbo restore failed - applying the safe-restore fallback: {}",
            this.user.username,
            String.valueOf(err));
        safeRestoreFallback(player);
      }
    } else
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
              () -> restorePlatformBlock(world, pos, original, 0, true),
              delay);
    }

    // Guaranteed game-mode restore: the lobby forced Adventure and the unlock must ALWAYS
    // give the player back their real mode - even when the snapshot was missing or the
    // reset was skipped (the mixin guard is already inactive - the lobby map is cleared).
    if (player != null && this.originalGameMode != null)
      in.potenfyr.authcore.compat.Compat.changeGameMode(player, this.originalGameMode);

    // The limbo session ended cleanly - the crash-safe snapshot is no longer needed.
    try {
      java.nio.file.Files.deleteIfExists(snapshotFile(this.user.uuid));
    } catch (java.io.IOException ignored) {
      // snapshot cleanup is best-effort
    }

    // Client companion: close its login GUI.
    if (player != null) {
      try {
        in.potenfyr.authcore.network.AuthInterop.sendCompanion(
            player, in.potenfyr.authcore.security.ClientGuard.MSG_OUT_OF_LOBBY);
      } catch (RuntimeException | LinkageError ignored) {
        // companion signalling is best-effort
      }
    }

    AuthCoreServer.LOGGER.debug(
        false, "{} has been taken out from the lobby/restricted mode!", this.user.username);

    this.cancel();

    // Authenticated now: /login and /register go away, /account and gameplay commands appear.
    if (player != null && this.user.isActive)
      in.potenfyr.authcore.compat.Compat.refreshCommandTree(player);
  }

  /**
   * A player that joined DEAD respawned while still locked (the vanilla death screen has no chat,
   * so respawning is the only way they can reach /login). Records the vanilla respawn point for
   * the unlock and re-applies the limbo shield to the NEW entity - vanilla does not copy the
   * invulnerability flag across a respawn, so the respawned, unauthenticated player was otherwise
   * left damageable inside the limbo. Idempotent; never throws.
   */
  public void onRespawnInLimbo(ServerPlayer respawned) {
    if (respawned == null || this.snapshot == null || !this.snapshot.wasDead()) return;
    if (!(respawned.getHealth() > 0.0F)) return;
    try {
      this.snapshot.noteRespawn(respawned);
      if (AuthCoreServer.config.lobby.preventDamage) {
        /*? if < 26.1 {*/
        respawned.setInvulnerable(true);
        /*?} else {*/
        /*in.potenfyr.authcore.compat.Compat.setInvulnerable(respawned, true);
        *//*?}*/
      }
    } catch (RuntimeException | LinkageError err) {
      AuthCoreServer.LOGGER.debug(false, "limbo respawn bookkeeping failed: {}", String.valueOf(err));
    }
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

    ServerLevel world = in.potenfyr.authcore.compat.Compat.playerLevel(player);
    boolean ok =
        in.potenfyr.authcore.compat.Compat.teleport(
            player,
            world,
            this.position.getX() + 0.5,
            anchorFeetY(),
            this.position.getZ() + 0.5,
            in.potenfyr.authcore.compat.Compat.getYaw(player),
            in.potenfyr.authcore.compat.Compat.getPitch(player));
    // Guaranteed client correction - snaps the client back even if the teleport API above
    // failed or skipped the client notification on this version.
    in.potenfyr.authcore.compat.Compat.correctClientPosition(
        player,
        this.position.getX() + 0.5,
        anchorFeetY(),
        this.position.getZ() + 0.5,
        in.potenfyr.authcore.compat.Compat.getYaw(player),
        in.potenfyr.authcore.compat.Compat.getPitch(player));
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
    // Compare against the ANCHOR - never the LIVE entity position. The live position can itself
    // have drifted (a guard miss, a lagging tick, another mod moving the entity), and comparing
    // against it would let successive sub-0.5 packets walk the player away one tolerated step at
    // a time.
    double oldX;
    double oldY;
    double oldZ;
    if (this.position != null) {
      oldX = this.position.getX() + 0.5;
      oldY = anchorFeetY();
      oldZ = this.position.getZ() + 0.5;
    } else {
      ServerPlayer player = this.user.player.get();
      if (player == null) return false;
      oldX = player.getX();
      oldY = player.getY();
      oldZ = player.getZ();
    }

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
    // X/Z are the block CENTRE (the anchor teleport centres the player in the column), Y is the
    // exact feet height - the same convention the anchor teleport uses.
    double dx = x - (this.position.getX() + 0.5);
    double dy = y - anchorFeetY();
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

    if (in.potenfyr.authcore.compat.Compat.getLatency(user.player.get()) >= 600)
      loginTimeoutMs = AuthCoreServer.config.lobby.timeout.timeoutAbove600LatencyMs;
    else if (in.potenfyr.authcore.compat.Compat.getLatency(user.player.get()) >= 400)
      loginTimeoutMs = AuthCoreServer.config.lobby.timeout.timeoutAbove400LatencyMs;
    else if (in.potenfyr.authcore.compat.Compat.getLatency(user.player.get()) >= 200)
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

    /**
     * The EXACT pre-lobby feet position. The block position alone loses the sub-block placement,
     * and restoring a player to the block centre (+0.5 on every axis) pushed their head into the
     * ceiling of every two-block-high space (doorways, mineshaft tunnels) and embedded the feet in
     * partial blocks - both suffocation hazards the block-centre restore created itself.
     */
    private final double x;
    private final double y;
    private final double z;

    private final float health;

    /**
     * Whether the player was DEAD when the limbo captured them (health 0 - they disconnected on the
     * death screen, or were killed on logout by the combat-log punishment). A dead player is never
     * healed by the limbo and never "restored" back to life at their death position: that was the
     * remaining issue #11 path (1.0.2 restored health 0 after /login = instant death with no death
     * message; 1.0.3 clamped it to 1 HP = a free revive at the death spot, a death/combat-log
     * bypass). They keep the vanilla death screen and respawn normally instead.
     */
    private final boolean dead;

    private final Object dimensionKey;

    private final int fireTicks;
    private final int frozenTicks;

    private final GameType gameMode;

    private final boolean operator;
    private int opPermissionLevel = 0;
    private boolean opBypassesPlayerLimit = false;

    /**
     * The player's invulnerability flag BEFORE the limbo (captured even when
     * {@code lobby.prevent-damage} is disabled, so the unlock always restores the exact
     * pre-lobby value instead of a hardcoded {@code false} that would strip an
     * already-invulnerable player's god-mode flag).
     */
    private final boolean invulnerable;

    /** The vehicle the player was riding before the lobby (re-mounted on unlock). */
    private final net.minecraft.world.entity.Entity vehicle;

    /** Captures the current state of the player and applies lobby-specific status effects. */
    public Snapshot(ServerPlayer player) {

      MinecraftServer server = in.potenfyr.authcore.compat.Compat.getServer(player);

      // --- POSITION & DIMENSION ---
      this.blockPos = player.blockPosition();
      this.x = player.getX();
      this.y = player.getY();
      this.z = player.getZ();
      this.dimensionKey =
          in.potenfyr.authcore.compat.Compat.worldRegistryKey(
              in.potenfyr.authcore.compat.Compat.playerLevel(player));

      // --- EFFECTS ---
      this.effects = new ArrayList<>(player.getActiveEffects());

      // --- INVENTORY ---
      this.inventory = new ArrayList<>();
      if (AuthCoreServer.config.lobby.hideInventory) {
        Inventory inv = in.potenfyr.authcore.compat.Compat.getInventory(player);
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
      this.dead = isDeadState(this.health);
      this.foodLevel = player.getFoodData().getFoodLevel();
      this.saturation = player.getFoodData().getSaturationLevel();

      // --- EXPERIENCE ---
      this.xpLevel = player.experienceLevel;
      this.xpProgress = player.experienceProgress;
      this.totalXp = player.totalExperience;

      // --- FIRE / FREEZE ---
      this.fireTicks = player.getRemainingFireTicks();
      this.frozenTicks = in.potenfyr.authcore.compat.Compat.getFrozenTicks(player);

      // --- VEHICLE (restored safely on unlock) ---
      this.vehicle = player.getVehicle();

      // --- GAMEMODE ---
      this.gameMode = player.gameMode.getGameModeForPlayer();

      // --- OPERATOR STATUS ---
      this.operator = in.potenfyr.authcore.compat.Compat.isOperator(server, player);

      if (this.operator) {
        this.opPermissionLevel = in.potenfyr.authcore.compat.Compat.getOperatorLevel(server, player);
        this.opBypassesPlayerLimit =
            in.potenfyr.authcore.compat.Compat.getOperatorBypass(server, player);
      }

      // --- INVULNERABILITY (pre-lobby value, before the shield is applied below) ---
      /*? if < 26.1 {*/
      this.invulnerable = player.isInvulnerable();
      /*?} else {*/
      /*// 26.1+: ONE jar covers the whole open-ended >=26.1 range and 26.3 split the
      // invulnerability API (the shield's flag is isPermanentlyInvulnerable there) -
      // read through the runtime dispatch, never a compile-time name.
      this.invulnerable = in.potenfyr.authcore.compat.Compat.isInvulnerable(player);
      *//*?}*/

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
        in.potenfyr.authcore.compat.Compat.forceCloseInventory(player);
      } catch (RuntimeException ignored) {
        // best-effort - the click guards remain as the second layer
      }

      // --- APPLY LOBBY EFFECTS ---
      if (AuthCoreServer.config.lobby.invisibleUnauthorized)
        applyLimboEffect(
            player,
            new MobEffectInstance(
                MobEffects.INVISIBILITY, Integer.MAX_VALUE, 1, false, false));

      if (AuthCoreServer.config.lobby.applyBlindnessEffect)
        applyLimboEffect(
            player,
            new MobEffectInstance(MobEffects.BLINDNESS, Integer.MAX_VALUE, 1, false, false));

      // --- DAMAGE PROTECTION ---
      if (AuthCoreServer.config.lobby.preventDamage) {
        /*? if < 26.1 {*/
        // 1.16-1.21.11: the setter name is stable across the whole group - the direct
        // call is remapped by the build for intermediary/SRG runtimes.
        player.setInvulnerable(true);
        /*?} else {*/
        /*// 26.1+: ONE unobfuscated jar covers 26.1-26.3+, and 26.3 renamed the setter
        // (setInvulnerable -> setPermanentlyInvulnerable). A compile-time switch always
        // bakes the 26.3 name into the bytecode, so 26.1/26.2 servers crashed with
        // NoSuchMethodError during the join-time lock (issue #10) - dispatch at runtime.
        in.potenfyr.authcore.compat.Compat.setInvulnerable(player, true);
        *//*?}*/
        // NEVER heal a dead player: the heal revived players who joined dead (death screen /
        // combat-log kill), and the unlock then either killed them again at 0 HP or left them alive
        // at their death position (issue #11).
        if (!this.dead) player.setHealth(player.getMaxHealth());
      }

      // --- SAFE OPERATORS ---
      if (this.operator && AuthCoreServer.config.lobby.safeOperators)
        in.potenfyr.authcore.compat.Compat.removeFromOperators(server, player);

      // --- FORCE ADVENTURE MODE ---
      // The game-mode-change mixin only blocks changes AWAY from adventure while in the
      // lobby - the initial switch must be applied here or the player stays in their
      // previous mode (e.g. survival) and the setting is a no-op.
      if (AuthCoreServer.config.lobby.forceAdventureMode && this.gameMode != GameType.ADVENTURE)
        in.potenfyr.authcore.compat.Compat.changeGameMode(player, GameType.ADVENTURE);
    }

    /**
     * Reverts the player to the state stored in this snapshot.
     *
     * <p>Clears lobby status effects, restores the inventory, experience, health, and teleports the
     * player back to their original position and dimension.
     */
    public void reset(ServerPlayer player) {

      MinecraftServer server = in.potenfyr.authcore.compat.Compat.getServer(player);

      // --- CLEAR LOBBY EFFECTS ---
      player.removeAllEffects();
      /*? if < 26.1 {*/
      // Restore the CAPTURED pre-lobby flag - never a hardcoded false: a player who was
      // already invulnerable before the limbo (e.g. a god-mode mod using the vanilla
      // flag) must keep that state after authenticating.
      player.setInvulnerable(this.invulnerable);
      /*?} else {*/
      /*// 26.1+: runtime dispatch for the 26.3 setter rename (issue #10).
      in.potenfyr.authcore.compat.Compat.setInvulnerable(player, this.invulnerable);
      *//*?}*/

      // --- RESTORE GAMEMODE (FIRST - never skipped by a later failure) ---
      // The lobby forced Adventure; if any restore step below throws, the player must
      // still get their real game mode back - otherwise they stay unable to attack or
      // break blocks after login.
      in.potenfyr.authcore.compat.Compat.changeGameMode(player, gameMode);

      // --- RESTORE INVENTORY ---
      if (AuthCoreServer.config.lobby.hideInventory && inventory != null && !inventory.isEmpty()) {
        try {
          Inventory inv = in.potenfyr.authcore.compat.Compat.getInventory(player);
          inv.clearContent();

          for (int i = 0; i < inventory.size(); i++) inv.setItem(i, inventory.get(i).copy());
          player.containerMenu.broadcastChanges();
        } catch (RuntimeException ignored) {
          // inventory restore is best-effort
        }
      }

      // --- DEAD-ON-JOIN (issue #11) ---
      // The player was captured on the death screen. Vanilla semantics win: never bring them back
      // to life at the death position with the snapshot's 0 HP (instant death after /login, no
      // death message) or a clamped 1 HP (a free revive - a death/combat-log bypass).
      if (this.dead) {
        resetDead(player, server);
        return;
      }

      // --- RESTORE EFFECTS ---
      try {
        for (MobEffectInstance effect : effects)
          player.addEffect(new MobEffectInstance(effect));
      } catch (RuntimeException ignored) {
        // effect restore is best-effort
      }

      // --- RESTORE DIMENSION ---
      ServerLevel world = in.potenfyr.authcore.compat.Compat.getWorld(server, this.dimensionKey);

      // Only the teleport-dependent steps need the original world. The state restores below
      // (game mode, health, food, xp, fire/freeze, operator status) must ALWAYS run - skipping
      // them on a failed world lookup left logged-in players stuck in the limbo dimension in
      // Adventure mode, unable to attack or break blocks.
      if (world != null) {
        // Restore the EXACT feet position (see {@link #x}/{@link #y}/{@link #z}); the safe-landing
        // pass below only moves the player when that exact spot is genuinely unsafe.
        teleportExact(player, world, this.x, this.y, this.z, this.blockPos);

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
            && in.potenfyr.authcore.compat.Compat.entityLevel(this.vehicle) == world
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
      in.potenfyr.authcore.compat.Compat.setFallDistance(player, 0);

      // --- RESTORE HEALTH / FOOD ---
      // A snapshot captured while the player was dead (health 0, e.g. they disconnected on the
      // death screen) must never be restored verbatim: the player would authenticate at 0 HP and
      // the very first point of damage would kill them again - the "died right after logging in"
      // report of issue #11. Clamp to a living value on the CURRENT max health.
      player.setHealth(clampRestoredHealth(health, player.getMaxHealth()));
      player.getFoodData().setFoodLevel(foodLevel);
      player.getFoodData().setSaturation(saturation);

      // --- RESTORE EXPERIENCE ---
      player.experienceLevel = xpLevel;
      player.experienceProgress = xpProgress;
      player.totalExperience = totalXp;

      // --- RESTORE FIRE / FREEZE / FALL ---
      player.setRemainingFireTicks(fireTicks);
      in.potenfyr.authcore.compat.Compat.setFrozenTicks(player, frozenTicks);

      // --- RESTORE OPERATOR STATUS ---
      // Re-grant only when no admin explicitly deopped the account WHILE it sat in the
      // limbo (tracked by PlayerListOpMixin): the snapshot was captured at LOCK time, so
      // blindly restoring it would resurrect operator powers over a concurrent deop.
      if (this.operator && !ADMIN_DEOPS_DURING_LIMBO.remove(player.getUUID()))
        in.potenfyr.authcore.compat.Compat.addToOperators(
            server, player, this.opPermissionLevel, this.opBypassesPlayerLimit);

      // --- POST-RESTORE SAFETY WINDOW ---
      // Whatever the geometry did, the restore itself must never be lethal: pin the fall distance
      // for a few seconds and re-land the player if the landing was not resolvable (issue #11).
      armRestoreGuard(player);
    }

    /** Pure dead-state decision for a captured health value (NaN and non-positive are dead). */
    public static boolean isDeadState(float health) {
      return !(health > 0.0F);
    }

    /** Whether the player was dead when the limbo captured them. */
    public boolean wasDead() {
      return this.dead;
    }

    /** Respawn point recorded while a dead-on-join player respawned inside the limbo. */
    private volatile boolean respawnKnown;

    private volatile double respawnX;
    private volatile double respawnY;
    private volatile double respawnZ;
    private volatile Object respawnDimension;

    /**
     * Records where vanilla respawned a dead-on-join player (called right after the respawn and
     * before the limbo anchor moves the new entity), so the unlock can send them to their REAL
     * respawn point instead of leaving them at the limbo anchor or sending them back to the place
     * they died. Only the first respawn of the session is recorded.
     */
    void noteRespawn(ServerPlayer player) {
      if (!this.dead || this.respawnKnown || player == null) return;
      if (!(player.getHealth() > 0.0F)) return;
      this.respawnX = player.getX();
      this.respawnY = player.getY();
      this.respawnZ = player.getZ();
      this.respawnDimension =
          in.potenfyr.authcore.compat.Compat.worldRegistryKey(
              in.potenfyr.authcore.compat.Compat.playerLevel(player));
      this.respawnKnown = true;
    }

    /** Restore for a player that was captured dead (see {@link #dead}). Never revives them. */
    private void resetDead(ServerPlayer player, MinecraftServer server) {
      // Operator status is identity, not life state - restore it exactly like the normal path.
      if (this.operator && !ADMIN_DEOPS_DURING_LIMBO.remove(player.getUUID()))
        in.potenfyr.authcore.compat.Compat.addToOperators(
            server, player, this.opPermissionLevel, this.opBypassesPlayerLimit);

      if (!(player.getHealth() > 0.0F)) {
        // Still on the death screen: leave them dead. The vanilla respawn puts them at their
        // bed/anchor/world spawn exactly as if AuthCore was not installed. No health, position,
        // xp or effect is touched (restoring the xp would duplicate the orbs dropped on death).
        AuthCoreServer.LOGGER.info(
            true,
            "{} joined dead (death screen) - authenticated without a revive; they respawn normally",
            player.getName().getString());
        return;
      }

      // They respawned while in the limbo: health/food/xp already belong to the NEW life. Only put
      // them back at the respawn point vanilla chose (the limbo anchor moved them away from it).
      ServerLevel world =
          this.respawnKnown
              ? in.potenfyr.authcore.compat.Compat.getWorld(server, this.respawnDimension)
              : null;
      if (world != null) {
        teleportExact(player, world, this.respawnX, this.respawnY, this.respawnZ,
            in.potenfyr.authcore.compat.Compat.blockPosFloored(
                this.respawnX, this.respawnY, this.respawnZ));
        safeLandIfNeeded(player, world);
      } else {
        ServerLevel current = in.potenfyr.authcore.compat.Compat.playerLevel(player);
        if (current != null) safeLandIfNeeded(player, current);
      }
      in.potenfyr.authcore.compat.Compat.setFallDistance(player, 0);
      AuthCoreServer.LOGGER.info(
          true,
          "{} respawned during the limbo - restored to their respawn point ({})",
          player.getName().getString(),
          world != null ? "recorded" : "unknown - kept in place");
      armRestoreGuard(player);
    }

    /**
     * Guarantees the restored player is on safe ground, in water, or flying-capable - never
     * falling, suffocating or stranded (handles flying/riding/swimming restore edge cases).
     *
     * <p>The landing is TOTAL: when the column below the restore position has no usable surface
     * (a liquid or hazard column, a covered pocket, the void) the player is moved to the column's
     * surface instead of being left in the air. Leaving them was the remaining hole of the 1.0.2
     * fix - {@code getGroundBelow} returned {@code null}, no teleport was issued and the player
     * fell from their logout height (issue #11 follow-up).
     */
    private static void safeLandIfNeeded(ServerPlayer player, ServerLevel world) {
      try {
        // Spectators fly freely - never yank them to the ground.
        if (player.isSpectator()) return;

        // If the restore teleport did not take effect the player is still in the limbo level, and
        // the geometry under them belongs to a DIFFERENT world - never "land" them using it.
        ServerLevel current = in.potenfyr.authcore.compat.Compat.playerLevel(player);
        if (current == null || !current.dimension().equals(world.dimension())) {
          AuthCoreServer.LOGGER.warn(
              false,
              "{} could not be restored into the original world - the landing pass was skipped "
                  + "and the post-restore fall guard stays armed",
              player.getName().getString());
          return;
        }

        boolean flying =
            in.potenfyr.authcore.compat.Compat.getAbilities(player) != null
                && in.potenfyr.authcore.compat.Compat.getAbilities(player).flying;

        // Inside a wall (blocks may have changed while the player was in the lobby) -> find
        // safe ground above and move there instead of suffocating.
        if (player.isInWall()) {
          BlockPos safe = getGroundAbove(world, player.blockPosition());
          if (safe != null) {
            teleport(player, safe.above(), world);
            return;
          }
        }

        // Airborne in survival (was gliding / jumping / falling, or logged out mid-air) -> land
        // on safe ground.
        //
        // The "is the player standing on something" decision is deliberately WORLD-based and
        // never reads Entity#onGround(): vanilla only refreshes that flag inside Entity#move and
        // from incoming move packets, and the restore teleport above bypasses both. In the limbo
        // the client stands on the anti-float BARRIER platform and reports onGround=true, so at
        // unlock the flag still describes the LIMBO even when the restored position is high in
        // the air - the old check trusted it, skipped this landing and the player fell from their
        // logout height to their death (issue #11).
        if (flying
            || player.isPassenger()
            || player.isInWater()
            || hasSupport(player, world)) return;
        BlockPos ground = getGroundBelow(world, player.blockPosition());
        if (ground != null) {
          teleport(player, ground.above(), world);
          // INFO on purpose: the owner asked for the restore path to be visible in latest.log
          // when issue #11 recurs (the old line was DEBUG-only and never reached latest.log).
          AuthCoreServer.LOGGER.info(
              true,
              "{} was restored airborne - landed on safe ground at {}",
              player.getName().getString(),
              ground.above());
          return;
        }

        // No usable landing below (liquid/hazard column, covered pocket or the void). Do NOT leave
        // the player in the air: move them to the first safe position at/above the column surface.
        BlockPos from = player.blockPosition();
        BlockPos fallback = resolveRestoreFallback(world, from);
        if (fallback != null) {
          teleport(player, fallback, world);
          double moved = Math.sqrt(from.distSqr(fallback));
          if (moved > 32.0)
            // A long move is better than a death, but it must be visible in the log: the player is
            // being placed somewhere else entirely (lava sea, covered pocket, void column).
            AuthCoreServer.LOGGER.warn(
                false,
                "{} was restored airborne with no landing below - moved {} blocks to the column "
                    + "surface at {} (from {})",
                player.getName().getString(),
                String.valueOf(Math.round(moved)),
                fallback,
                from);
          else
            AuthCoreServer.LOGGER.info(
                true,
                "{} was restored airborne with no landing below - moved to the column surface at {}",
                player.getName().getString(),
                fallback);
        } else {
          AuthCoreServer.LOGGER.warn(
              false,
              "{} could not be placed on safe ground after the limbo restore - keeping the "
                  + "post-restore fall guard armed (position {})",
              player.getName().getString(),
              player.blockPosition());
        }
      } catch (RuntimeException | LinkageError err) {
        // safe-landing is best-effort - never break the unlock; the post-restore guard pins the
        // fall distance and retries the landing, so a throw here cannot become a fall death.
        AuthCoreServer.LOGGER.warn(
            false, "safe-landing failed: {}", String.valueOf(err));
      }
    }

    /**
     * Fallback landing used when the restore column has no usable surface below the player: the
     * first safe standing position at or above the column's motion-blocking surface, preferring a
     * water surface (water cancels fall damage) over anything hazardous, and finally the world
     * spawn. Never teleports a player into lava, fire, cactus or the void.
     *
     * @return the standing position to teleport to (feet exactly there), or {@code null}
     */
    private static BlockPos resolveRestoreFallback(ServerLevel world, BlockPos origin) {
      if (world == null || origin == null) return null;
      final int x = origin.getX();
      final int z = origin.getZ();
      final int bottom = in.potenfyr.authcore.compat.Compat.getBottomY(world);
      final int top = in.potenfyr.authcore.compat.Compat.getMaxBuildHeight(world);
      int surface = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
      if (surface < bottom) surface = bottom;
      for (int y = surface; y <= top; y++) {
        // NOTE: this is a FEET position (the restore teleport adds the +0.5 offset itself), so the
        // solid ground must be the block BELOW it - returning the ground block here would embed the
        // player half a block inside the terrain.
        BlockPos feet = new BlockPos(x, y, z);
        BlockState below = world.getBlockState(feet.below());
        // Standing on water is safe (water cancels fall damage and the player swims); lava/fire are
        // not - keep scanning upwards instead of dropping the player into them.
        if (!below.getFluidState().isEmpty()) {
          // Water is safe to stand on - but only if the feet cell itself has room (a water
          // surface under a low ceiling must not swallow the player).
          if (below.getFluidState().is(net.minecraft.tags.FluidTags.WATER)
              && hasPlayerClearance(world, feet)) return feet;
          continue;
        }
        if (isBlockSafe(world, feet.below())) return feet;
      }
      // Last resort: the world spawn column (still validated, never blindly used).
      BlockPos spawn = in.potenfyr.authcore.compat.Compat.sharedSpawnPos(world);
      if (spawn != null && isBlockSafe(world, spawn.below())) return spawn;
      return null;
    }

    /**
     * Clamps a snapshot health value to something a living player may be restored to.
     *
     * <p>Pure so the host test suite can pin the behaviour: a corrupted or stale snapshot (NaN, 0,
     * negative, absurdly large) must never restore a dead player as "alive but at 0 HP" - where the
     * first point of damage kills them again (issue #11) - and never grant above the current max
     * health either.
     *
     * @param snapshotHealth the health captured before the limbo
     * @param maxHealth the player's CURRENT max health
     * @return a finite health value in {@code [1, maxHealth]}
     */
    public static float clampRestoredHealth(float snapshotHealth, float maxHealth) {
      float max = Float.isFinite(maxHealth) && maxHealth > 0.0F ? maxHealth : 20.0F;
      if (!Float.isFinite(snapshotHealth) || snapshotHealth <= 0.0F) return max;
      return Math.min(Math.max(snapshotHealth, 1.0F), max);
    }

    /**
     * Whether the player is standing on (or within a fall-safe distance of) solid support at
     * their CURRENT position - computed from world geometry.
     *
     * <p>This is the world-aware replacement for
     * {@link net.minecraft.world.entity.Entity#onGround()}: that flag is only refreshed by
     * {@code Entity#move} and by incoming move packets, so right after a teleport it still reports
     * the PRE-teleport state. The limbo's anti-float platform makes it read {@code true} while the
     * player stands in the lobby, which used to hide a mid-air restore position and drop the player
     * to their death (issue #11).
     */
    private static boolean hasSupport(ServerPlayer player, ServerLevel world) {
      if (player == null || world == null) return false;
      // A player hanging on a ladder/vine/scaffolding is safe even though those blocks have no
      // collision shape - never yank them down to the ground far below.
      BlockPos feet = player.blockPosition();
      if (isClimbable(world, feet)
          || isClimbable(world, feet.above())
          || isClimbable(world, feet.above(2))) return true;
      // A cobweb catches the player (slow descent, no fall damage) - leave them in place.
      if (world.getBlockState(feet).is(Blocks.COBWEB)) return true;
      net.minecraft.world.phys.AABB box = player.getBoundingBox();
      return hasSupportBelow(
          box.minX,
          box.maxX,
          box.minY,
          box.minZ,
          box.maxZ,
          (x, y, z) -> {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = world.getBlockState(pos);
            // AuthCore's own anti-float platform is temporary (restored ~10s after the auth
            // flow) - it must never count as permanent ground here, or a restore position that
            // happens to overlap it would be left unsupported once the barrier is removed.
            if (state.is(Blocks.BARRIER)) return false;
            return !state.getCollisionShape(world, pos).isEmpty();
          });
    }

    /** Whether the block is something a player can safely hang on (ladder, vine, scaffolding). */
    private static boolean isClimbable(ServerLevel world, BlockPos pos) {
      BlockState state = world.getBlockState(pos);
      return state.getBlock() instanceof LadderBlock
          || state.getBlock() instanceof VineBlock
          || state.getBlock() instanceof ScaffoldingBlock;
    }

    /**
     * Minimal block-collision query for {@link #hasSupportBelow}. A plain functional interface
     * (instead of a Minecraft {@code Predicate<BlockPos>}) keeps the support geometry unit-testable
     * in a plain JVM without the game classes on the compile classpath.
     */
    @FunctionalInterface
    public interface BlockSupport {
      /** Whether the block at the given coordinates has a collision shape. */
      boolean solidAt(int x, int y, int z);
    }

    /**
     * Pure geometry form of {@link #hasSupport}: true when any block the player's bounding-box
     * footprint overlaps at the feet - or one block lower (tolerates the restore teleport's
     * +0.5 offset and partial blocks such as slabs, carpets and stairs) - has a collision shape.
     *
     * <p>Public so the host test suite can exercise the support decision against synthetic block
     * shapes without a live server (see {@code AuthCoreSecurityTests}).
     */
    public static boolean hasSupportBelow(
        double minX,
        double maxX,
        double minY,
        double minZ,
        double maxZ,
        BlockSupport support) {
      if (support == null) return false;
      // Just under the feet: a block whose top is exactly at the feet still counts.
      int topY = (int) Math.floor(minY - 0.05);
      int x0 = (int) Math.floor(minX + 1.0E-7);
      int x1 = (int) Math.floor(maxX - 1.0E-7);
      int z0 = (int) Math.floor(minZ + 1.0E-7);
      int z1 = (int) Math.floor(maxZ - 1.0E-7);
      for (int y = topY; y >= topY - 1; y--)
        for (int x = x0; x <= x1; x++)
          for (int z = z0; z <= z1; z++) if (support.solidAt(x, y, z)) return true;
      return false;
    }

    /**
     * Teleports the player so their FEET sit exactly on the bottom edge of {@code pos}.
     *
     * <p>{@code pos} is always a feet block (the block the player's legs occupy), never the ground
     * block below it - see {@link #getTeleportPos}. The vertical offset is deliberately 0: the old
     * {@code +0.5} placed the player half a block ABOVE the surface, so the client fell those 0.5
     * blocks and the lobby movement guard (which compares against the anchor) snapped it back on a
     * loop - the player was permanently falling/jumping in the limbo even when a block was right
     * under them. Restoring the exact surface also removes the same 0.5-block drop from the
     * post-login restore.
     *
     * @param player The player to teleport.
     * @param pos The target FEET block position.
     * @param world The target world.
     */
    private static void teleport(ServerPlayer player, BlockPos pos, ServerLevel world) {
      boolean ok =
          in.potenfyr.authcore.compat.Compat.teleport(
              player,
              world,
              pos.getX() + 0.5,
              exactFeetY(world, pos),
              pos.getZ() + 0.5,
              in.potenfyr.authcore.compat.Compat.getYaw(player),
              in.potenfyr.authcore.compat.Compat.getPitch(player));
      if (ok) {
        // A restore teleport must never carry a fall distance: vanilla turns the distance
        // accumulated since the last ground contact into fall damage on the next landing, and the
        // limbo position is not where the player is being restored to (issue #11).
        in.potenfyr.authcore.compat.Compat.setFallDistance(player, 0);
      } else
        AuthCoreServer.LOGGER.debug(
            false, "Lobby teleport failed for {} - teleport API unavailable on this version.",
            player.getName().getString());
    }

    /**
     * Exact feet height for a FEET block: the top of the support collision shape directly below it.
     *
     * <p>Full blocks put the feet exactly on the block floor, but partial supports (slabs, stairs,
     * carpets, snow layers, farmland, dirt paths) are walked on at their shape top above the block
     * floor. Using the block floor for those left the player half a block inside the support - and
     * when the lobby anchor/guard disagreed about the height it re-teleported the player every tick
     * (the "endlessly falling/jumping in the limbo" report).
     */
    public static double exactFeetY(ServerLevel world, BlockPos feetBlock) {
      if (world == null || feetBlock == null) return 0.0;
      BlockPos support = feetBlock.below();
      BlockState state = world.getBlockState(support);
      net.minecraft.world.phys.shapes.VoxelShape shape = state.getCollisionShape(world, support);
      if (shape.isEmpty()) return feetBlock.getY();
      double top = shape.max(net.minecraft.core.Direction.Axis.Y);
      if (!(top > 0.0)) return feetBlock.getY();
      return support.getY() + Math.min(top, 1.0);
    }

    /**
     * Teleports to an EXACT stored position (not the block centre), falling back to the block-centre
     * teleport when the exact call is unavailable on this runtime.
     */
    private static void teleportExact(
        ServerPlayer player, ServerLevel world, double x, double y, double z, BlockPos fallback) {
      boolean ok =
          in.potenfyr.authcore.compat.Compat.teleport(
              player,
              world,
              x,
              y,
              z,
              in.potenfyr.authcore.compat.Compat.getYaw(player),
              in.potenfyr.authcore.compat.Compat.getPitch(player));
      if (ok) {
        in.potenfyr.authcore.compat.Compat.setFallDistance(player, 0);
        return;
      }
      teleport(player, fallback, world);
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

      // World-based "standing on something" (never Entity#onGround() - see hasSupport): at this
      // point the player still stands at their pre-lobby position, so their own level is the
      // world to probe (the limbo world above may be a different dimension).
      boolean supported =
          hasSupport(player, in.potenfyr.authcore.compat.Compat.playerLevel(player));

      boolean airborne =
          in.potenfyr.authcore.compat.Compat.getAbilities(player).flying
              || in.potenfyr.authcore.compat.Compat.isGliding(player)
              || player.isPassenger()
              || !supported;

      boolean inGap =
          (in.potenfyr.authcore.compat.Compat.isGliding(player) || !supported)
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

      // Every teleport target is a FEET block: the block the player's legs occupy, with the ground
      // directly below it. A candidate that is itself standable is a GROUND block (the configured
      // limbo can point straight at the floor) - the feet belong one block above it. The old code
      // relied on the teleport's +0.5 offset to compensate, which left the player embedded in / half
      // a block above the surface and produced the endless limbo fall-and-snap loop.
      if (isBlockSafe(world, candidate)) candidate = candidate.above();

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
     * Searches downwards for the nearest safe landing surface.
     *
     * <p>Descends from the feet: air, non-solid decoration (tall grass, flowers, torches,
     * cobwebs...) and the temporary anti-float barrier are passed through / rejected, lava and
     * other hazards stop the search, and the first standable surface the player fits on is the
     * landing. A fluid stops the search as well - water is safe to fall into, lava is not, so the
     * caller's fallback ({@link #resolveRestoreFallback}) moves the player to a safe surface instead
     * of leaving them in the air.
     *
     * @return the standable block (the caller teleports to {@code ground.above()}), or
     *     {@code null} when there is no safe landing below.
     */
    private static BlockPos getGroundBelow(ServerLevel world, BlockPos origin) {
      final int x = origin.getX();
      final int z = origin.getZ();
      Integer groundY =
          findGroundBelow(
              origin.getY(),
              in.potenfyr.authcore.compat.Compat.getBottomY(world),
              y -> {
                BlockPos pos = new BlockPos(x, y, z);
                BlockState state = world.getBlockState(pos);
                // Never teleport the player onto (or through) the temporary anti-float platform.
                if (state.is(Blocks.BARRIER)) return GroundType.BLOCKED;
                // A hazard surface (fire, cactus, magma, campfire..., and lava which is a fluid and
                // therefore lethal) is never a landing: the whole point of the restore landing is
                // that the player does not get hurt by it.
                if (isHazardBlock(state)) return GroundType.BLOCKED;
                if (isStandable(world, pos, state))
                  return isBlockSafe(world, pos) ? GroundType.LANDING : GroundType.BLOCKED;
                if (!state.getFluidState().isEmpty()) return GroundType.FLUID;
                if (state.isAir()) return GroundType.AIR;
                return GroundType.PASSABLE;
              });
      return groundY == null ? null : new BlockPos(x, groundY, z);
    }

    /**
     * Whether the block hurts a player that lands on it (or stands in it). Kept deliberately small
     * and version-safe: only blocks that exist in every supported era (1.16+).
     */
    private static boolean isHazardBlock(BlockState state) {
      if (state == null) return false;
      return state.is(Blocks.FIRE)
          || state.is(Blocks.SOUL_FIRE)
          || state.is(Blocks.CACTUS)
          || state.is(Blocks.MAGMA_BLOCK)
          || state.is(Blocks.CAMPFIRE)
          || state.is(Blocks.SOUL_CAMPFIRE);
    }

    /** What a single block in a vertical column means for {@link #findGroundBelow}. */
    public enum GroundType {
      /** Air - keep descending. */
      AIR,
      /** Non-solid decoration (tall grass, flowers, torches...) - keep descending. */
      PASSABLE,
      /** Fluid - stop: the player falls into it naturally. */
      FLUID,
      /** Solid surface with headroom - the landing spot. */
      LANDING,
      /** Solid surface without headroom - stop, never teleport the player through it. */
      BLOCKED
    }

    /**
     * Pure vertical ground search used by {@link #getGroundBelow}.
     *
     * <p>The old implementation only descended while the block BELOW was air and then tested the
     * air block it had just moved into, so it could never return a landing surface - the
     * post-login restore therefore never landed a mid-air player (issue #11). Kept pure so the
     * host test suite can exercise the search against synthetic columns.
     *
     * @return the Y of the landing block, or {@code null} when there is none (fluid/blocked/void).
     */
    public static Integer findGroundBelow(int startY, int bottomY, BlockColumn column) {
      if (column == null) return null;
      for (int y = startY - 1; y >= bottomY; y--) {
        switch (column.typeAt(y)) {
          case AIR:
          case PASSABLE:
            break;
          case LANDING:
            return Integer.valueOf(y);
          default:
            // FLUID or BLOCKED: never teleport the player past this point.
            return null;
        }
      }
      return null;
    }

    /** One-block-wide vertical view for {@link #findGroundBelow}. */
    @FunctionalInterface
    public interface BlockColumn {
      /** Classifies the block at the given height. */
      GroundType typeAt(int y);
    }

    /**
     * Searches upwards for the nearest solid ground.
     *
     * @return The ground position, or {@code null} if the build limit is reached.
     */
    private static BlockPos getGroundAbove(ServerLevel world, BlockPos origin) {
      BlockPos.MutableBlockPos check = origin.mutable();

      int topY =
          world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, origin.getX(), origin.getZ());

      while (check.getY() <= topY) {
        check.move(0, 1, 0);

        if (isBlockSafe(world, check)) return check.immutable();
      }

      return null;
    }

    /** Whether a block can be stood on (full collision or a known standable shape). */
    private static boolean isStandable(ServerLevel world, BlockPos pos, BlockState state) {
      // AuthCore's own anti-float platform is temporary (removed seconds after the auth flow): it
      // must never be selected as a landing surface anywhere, or the player is dropped when it
      // disappears (issue #11 follow-up).
      if (state.is(Blocks.BARRIER)) return false;
      return state.canOcclude()
          || state.getBlock() instanceof LeavesBlock
          || state.getBlock() instanceof CarpetBlock
          || state.getBlock() instanceof ScaffoldingBlock
          || state.getBlock() instanceof SlimeBlock
          || state.getBlock() instanceof HoneyBlock
          || state.getBlock() instanceof FenceBlock
          || state.getBlock() instanceof WallBlock
          || state.getBlock() instanceof LadderBlock
          || state.getBlock() instanceof VineBlock
          || in.potenfyr.authcore.compat.Compat.isDripleaf(state)
          || state.is(Blocks.SNOW)
          || state.is(Blocks.SNOW_BLOCK)
          || !state.getCollisionShape(world, pos).isEmpty();
    }

    /**
     * Validates if a block position is safe for a player to stand on without suffocating or
     * falling.
     *
     * <p>Headroom is decided by COLLISION SHAPES, never by {@code isAir()}: vanilla only needs two
     * collision-free blocks of space for the 1.8-block-tall body, so tall grass, flowers, torches,
     * rails, signs, carpets and snow layers are perfectly safe to stand in. Requiring strict air
     * made every DECORATED surface below the restore position classify as BLOCKED,
     * {@code findGroundBelow} return {@code null} and the player stay in the air - the remaining
     * cause of the issue #11 reopen (players logging out mid-air over grass/flowers were dropped
     * from their logout height instead of being landed).
     *
     * @return {@code true} if the block and the two blocks above it allow for safe standing.
     */
    private static boolean isBlockSafe(ServerLevel world, BlockPos pos) {
      if (world == null || pos == null) return false;
      BlockState state = world.getBlockState(pos);
      // Hazard blocks are never a landing: cactus/magma/campfire hurt on contact and fire burns.
      // The post-restore guard only pins FALL damage, so a hazard landing is still a restore death.
      if (isHazardBlock(state)) return false;
      if (isHazardBlock(world.getBlockState(pos.above()))) return false;
      if (isHazardBlock(world.getBlockState(pos.above(2)))) return false;
      return isStandable(world, pos, state)
          && hasPlayerClearance(world, pos.above())
          // The HEAD cell must be fully collision-free: the body is 1.8 blocks tall, so a second
          // shallow partial shape there (stacked carpet/snow) squeezes it even though each cell
          // looks "shallow" on its own.
          && hasCollisionFreeSpace(world, pos.above(2));
    }

    /** Whether a block has no collision shape at all. */
    private static boolean hasCollisionFreeSpace(ServerLevel world, BlockPos pos) {
      if (world == null || pos == null) return false;
      return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }

    /**
     * Whether a player can occupy the block without suffocating: the block has no collision shape,
     * or only a shallow partial shape (snow layer, carpet, slab...) that vanilla simply pushes the
     * player up onto.
     */
    private static boolean hasPlayerClearance(ServerLevel world, BlockPos pos) {
      if (world == null || pos == null) return false;
      BlockState state = world.getBlockState(pos);
      net.minecraft.world.phys.shapes.VoxelShape shape = state.getCollisionShape(world, pos);
      if (shape.isEmpty()) return true;
      return isClearanceHeight(shape.max(net.minecraft.core.Direction.Axis.Y));
    }

    /**
     * Pure threshold used by {@link #hasPlayerClearance}: a collision shape up to this height is a
     * partial block (snow layer, carpet, slab, flower bed) that vanilla pushes the player up onto,
     * so it never blocks a landing. Public so the host test suite can pin the exact numbers that
     * made decorated ground classify as "blocked" before the fix (issue #11 reopen).
     */
    public static boolean isClearanceHeight(double collisionMaxY) {
      return collisionMaxY <= 0.6D;
    }
  }
}
