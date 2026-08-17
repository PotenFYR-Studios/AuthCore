package net.ded3ec.security;

import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.Config;
import net.ded3ec.models.User;
import net.ded3ec.util.TaskScheduler;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;

/**
 * THE single human-verification method (action captcha).
 *
 * <p>Risk-triggered and issued AFTER authentication, on EVERY login - a first login does not
 * make an account trusted, and an account owner can hand their credentials to a bot, so every
 * session is scored independently. After a successful login/register the player is OBSERVED
 * for a short window and scored across many independent signals:
 *
 * <ul>
 *   <li>ghost pattern (no look/move/click/chat/settings packet),
 *   <li>ClientGuard risk score (floods, probes, spoofs, anomalies),
 *   <li>instant login (typed within seconds of joining - bots use stored passwords),
 *   <li>failed attempts before success (guess-then-success),
 *   <li>missing 2FA on an account that has it configured,
 *   <li>fresh account age (bot-farm accounts),
 *   <li>fast reconnect loops.
 * </ul>
 *
 * <p>Trust signals subtract from the score: premium (Mojang-verified), valid companion session
 * token, previously trusted account, already passed the challenge this session. The player is
 * challenged only when the total reaches {@code bot-score-threshold}.
 *
 * <p>The challenge is a short PHYSICAL in-game task that only a real client produces:
 *
 * <ul>
 *   <li>SNEAK: hold the sneak key for N seconds (continuous shift packets).
 *   <li>JUMP: jump N times (ground-air transitions with upward velocity).
 *   <li>LOOK UP: look at the sky for N seconds (camera pitch packets).
 * </ul>
 *
 * <p>A scripted bot would need to synthesize the corresponding movement/look packet
 * streams - far beyond a chat-reading or screenshot-parsing farm. Progress is measured
 * server-side from the real entity state every tick, and the challenge expires with a kick
 * when the time limit is reached. On completion the user's {@code captchaVerified} flag is
 * set.
 */
public final class ActionCaptcha {

  public static final int CHALLENGE_SNEAK = 0;
  public static final int CHALLENGE_JUMP = 1;
  public static final int CHALLENGE_LOOK_UP = 2;

  /** uuid -> active challenge state. */
  private static final Map<UUID, Challenge> CHALLENGES = new ConcurrentHashMap<>();

  /** uuid -> last leave timestamp (fast-rejoin detection, bounded + pruned). */
  private static final Map<UUID, Long> LAST_LEAVE = new ConcurrentHashMap<>();

  private ActionCaptcha() {}

  private static final class Challenge {
    final int type;
    final double target;
    final long deadlineMs;
    double progress = 0.0;
    boolean airborne = false;
    boolean completed = false;
    long lastNoticeMs = 0;

    Challenge(int type, double target, long deadlineMs) {
      this.type = type;
      this.target = target;
      this.deadlineMs = deadlineMs;
    }
  }

  // ------------------------------------------------------------------ lifecycle

  /**
   * Called on every successful login/register (centralized in User.login so every path -
   * command login/register, premium auto-login, session resume, deferred premium
   * verification, SSO - is covered). Starts the observation window; no challenge yet.
   */
  public static void onLogin(ServerPlayer player) {
    User user = User.getUser(player);
    if (user == null || player == null) return;
    Config.Lobby.CaptchaConfig cfg = config();
    if (cfg == null || !cfg.enabled) return;

    AuthCoreServer.LOGGER.debug(
        false,
        "{} | human-verification observation window scheduled ({}s) - every login is scored",
        user.username,
        Math.max(5, cfg.observationWindowSec));

    TaskScheduler.getInstance()
        .setTimeout(
            () -> evaluate(player, user),
            Math.max(5, cfg.observationWindowSec) * 1000L);
  }

  /** Drops the pending challenge (disconnect). */
  public static void onLeave(ServerPlayer player) {
    if (player == null) return;
    UUID uuid = player.getUUID();
    Challenge challenge = CHALLENGES.remove(uuid);
    if (challenge != null && !challenge.completed) {
      User user = User.getUser(player);
      if (user != null)
        SecurityLog.log("CAPTCHA_ACTION_ABANDONED", user.username + " left with the captcha pending");
    }
    // Remember the leave time for the fast-rejoin signal; bound the map.
    LAST_LEAVE.put(uuid, System.currentTimeMillis());
    if (LAST_LEAVE.size() > 2048) {
      long cutoff = System.currentTimeMillis() - 3600_000L;
      LAST_LEAVE.entrySet().removeIf(e -> e.getValue() < cutoff);
    }
  }

  // ------------------------------------------------------------------ evaluation

  /**
   * Multi-signal bot scoring. Every login is evaluated independently - a first login does not
   * make the account trusted, and the account owner could be running a bot with their own
   * credentials. The score is a weighted sum of bot signals minus trust signals; the player is
   * challenged only when the total reaches the configured threshold.
   */
  private static void evaluate(ServerPlayer player, User user) {
    if (player == null || user == null || !user.isActive) return;
    if (user.player.get() != player) return; // reconnected since
    Config.Lobby.CaptchaConfig cfg = config();
    if (cfg == null || !cfg.enabled) return;
    if (CHALLENGES.containsKey(player.getUUID())) return;

    // TPS-adaptive: never challenge during lag spikes (the config contract).
    if (cfg.disableWhenTpsBelow > 0
        && net.ded3ec.util.TpsManager.get() < cfg.disableWhenTpsBelow) {
      AuthCoreServer.LOGGER.debug(
          false,
          "{} | verification skipped - TPS {} below {}", user.username, net.ded3ec.util.TpsManager.get(), cfg.disableWhenTpsBelow);
      return;
    }

    int score = 0;
    StringBuilder breakdown = new StringBuilder();
    ClientGuard.Profile p = ClientGuard.profile(player);

    // ---- bot signals (positive) ------------------------------------------------

    // 1. Ghost pattern: none of the things real players always do.
    if (cfg.autoChallengeGhosts && isGhostLike(player, p)) {
      score += 40;
      breakdown.append("ghost+40 ");
    } else {
      breakdown.append("ghost0 ");
    }

    // 2. ClientGuard risk score (bounded - risk alone should rarely be decisive).
    if (p != null && p.risk > 0) {
      int riskPart = Math.min(p.risk, 50);
      score += riskPart;
      breakdown.append("risk+").append(riskPart).append(' ');
    } else {
      breakdown.append("risk0 ");
    }

    // 3. Instant login: typed the password within seconds of joining (bots auto-login).
    if (cfg.instantLoginSec > 0 && p != null) {
      long loginMs = user.lastAuthenticatedMs;
      long joinMs = p.joinMs;
      long tookMs = loginMs - joinMs;
      if (tookMs >= 0 && tookMs < cfg.instantLoginSec * 1000L) {
        score += 25;
        breakdown.append("instantLogin+25 (").append(tookMs / 1000L).append("s) ");
      } else {
        breakdown.append("instantLogin0 (").append(Math.max(0, tookMs) / 1000L).append("s) ");
      }
    }

    // 4. Guess-then-success: succeeded after 2+ failed attempts this session.
    if (user.loginAttempts >= 2) {
      score += cfg.failedAttemptScore;
      breakdown.append("failedAttempts+").append(cfg.failedAttemptScore)
          .append(" (").append(user.loginAttempts).append(" attempts) ");
    } else {
      breakdown.append("failedAttempts0 ");
    }

    // 5. Account has 2FA configured but the login skipped it (bots cannot answer TOTP).
    boolean mfaConfigured =
        AuthCoreServer.config.session.authentication.allowTOTPSupport
            && user.authSecret != null
            && !user.authSecret.isBlank();
    if (mfaConfigured && !user.mfaVerified) {
      score += cfg.missingMfaScore;
      breakdown.append("missingMfa+").append(cfg.missingMfaScore).append(' ');
    } else {
      breakdown.append("missingMfa0 ");
    }

    // 6. Fresh account (bot-farm accounts are usually new).
    long accountAgeMs = System.currentTimeMillis() - user.userCreatedMs;
    if (cfg.freshAccountHours > 0 && accountAgeMs < cfg.freshAccountHours * 3600_000L) {
      score += 15;
      breakdown.append("freshAccount+15 (").append(accountAgeMs / 3600_000L).append("h old) ");
    } else {
      breakdown.append("freshAccount0 ");
    }

    // 7. Fast rejoin: disconnected and came back within seconds (reconnect-bot loop).
    Long lastLeave = LAST_LEAVE.get(player.getUUID());
    if (cfg.fastRejoinSec > 0 && lastLeave != null) {
      long sinceLeave = System.currentTimeMillis() - lastLeave;
      if (sinceLeave < cfg.fastRejoinSec * 1000L) {
        score += 20;
        breakdown.append("fastRejoin+20 (").append(sinceLeave / 1000L).append("s) ");
      } else {
        breakdown.append("fastRejoin0 ");
      }
    }

    // ---- trust signals (negative) ------------------------------------------------

    // 8. Trusted account from a previous successful login.
    if (AuthCoreServer.config.session.trusted.enabled
        && user.trustedUntilMs > System.currentTimeMillis()) {
      score -= cfg.trustedScore;
      breakdown.append("trusted-").append(cfg.trustedScore).append(' ');
    }

    // 9. Premium (Mojang-verified at join by the server itself).
    if (user.isPremium) {
      score -= cfg.premiumScore;
      breakdown.append("premium-").append(cfg.premiumScore).append(' ');
    }

    // 10. Valid companion session-token resume (not just IP-only).
    if (p != null && p.sessionTokenClaim != null && user.verifySessionToken(p.sessionTokenClaim)) {
      score -= cfg.sessionTokenScore;
      breakdown.append("sessionToken-").append(cfg.sessionTokenScore).append(' ');
    }

    // 11. Already passed the challenge earlier in this session.
    if (user.captchaVerified) {
      score -= cfg.alreadyVerifiedScore;
      breakdown.append("alreadyVerified-").append(cfg.alreadyVerifiedScore).append(' ');
    }

    // ---- decision ---------------------------------------------------------------

    AuthCoreServer.LOGGER.debug(
        false,
        "{} | bot-score {} / threshold {} - [{}]",
        user.username,
        score,
        cfg.botScoreThreshold,
        breakdown.toString().trim());

    if (score >= cfg.botScoreThreshold) {
      SecurityLog.log(
          "CAPTCHA_ACTION_TRIGGERED",
          user.username + " | score " + score + " (threshold " + cfg.botScoreThreshold
              + ") - " + breakdown.toString().trim());
      AuthCoreServer.LOGGER.info(
          true,
          "Human verification issued for {} (score {}): {}.",
          user.username,
          score,
          breakdown.toString().trim());
      start(player, user, cfg);
    } else {
      SecurityLog.log(
          "CAPTCHA_ACTION_PASSED",
          user.username + " | score " + score + " below threshold " + cfg.botScoreThreshold);
    }
  }

  /** A player is ghost-like when they did none of the things real players always do. */
  private static boolean isGhostLike(ServerPlayer player, ClientGuard.Profile p) {
    if (p == null) return true; // no profile at all is itself suspicious
    boolean looked = ClientGuard.hasLookedRecently(player, 60_000L);
    boolean clicked = p.clicks.count() > 0;
    boolean chatted = p.chatCount > 0 || p.commandCount > 0;
    boolean moved = p.moves.count() > 3; // a few move packets (join sync) is not "moving"
    boolean settings = p.settingsSeen;
    return !looked && !clicked && !chatted && !moved && !settings;
  }

  // ------------------------------------------------------------------ challenge

  /** Starts a random action challenge for the flagged player. */
  private static void start(
      ServerPlayer player, User user, Config.Lobby.CaptchaConfig cfg) {
    CHALLENGES.remove(player.getUUID());
    user.captchaVerified = false;

    int type = pickRandomType();
    double target = targetFor(type, cfg);
    Challenge challenge =
        new Challenge(type, target, System.currentTimeMillis() + Math.max(5_000L, cfg.actionTtlMs));
    CHALLENGES.put(player.getUUID(), challenge);

    String task = taskText(type, target, cfg);
    send(player, "Human verification required!", ChatFormatting.GOLD);
    send(player, task, ChatFormatting.YELLOW);

    SecurityLog.log(
        "CAPTCHA_ACTION_STARTED",
        user.username + " | " + task + " | TTL " + Math.max(5_000L, cfg.actionTtlMs) + "ms");
    AuthCoreServer.LOGGER.info(
        true, "Action captcha issued for {}: {}.", user.username, task);
  }

  /** Per-tick driver (called from the server tick loop). */
  public static void tickAll() {
    if (CHALLENGES.isEmpty()) return;
    long now = System.currentTimeMillis();
    for (Map.Entry<UUID, Challenge> entry : new java.util.ArrayList<>(CHALLENGES.entrySet())) {
      Challenge challenge = entry.getValue();
      if (challenge.completed) continue;

      User user = User.users.get(entry.getKey());
      ServerPlayer player = user != null ? user.player.get() : null;
      if (player == null || user == null || !user.isActive) {
        CHALLENGES.remove(entry.getKey());
        continue;
      }

      if (now >= challenge.deadlineMs) {
        CHALLENGES.remove(entry.getKey());
        SecurityLog.log("CAPTCHA_ACTION_TIMEOUT", user.username + " did not finish the captcha in time");
        AuthCoreServer.LOGGER.info(
            true, "{} did not finish the action captcha in time - kicked.", user.username);
        AuthCoreServer.LOGGER.debug(
            false, "{} | captcha timeout (progress {}/{}, deadline passed)", user.username, challenge.progress, challenge.target);
        AuthCoreServer.LOGGER.toKick(
            false,
            user.connection,
            AuthCoreServer.messages.promptUserCaptchaActionExpired);
        continue;
      }

      if (measure(player, challenge, now)) {
        challenge.completed = true;
        CHALLENGES.remove(entry.getKey());
        user.captchaVerified = true;
        SecurityLog.log("CAPTCHA_ACTION_PASSED", user.username + " completed the action captcha");
        AuthCoreServer.LOGGER.info(true, "{} completed the action captcha.", user.username);
        AuthCoreServer.LOGGER.debug(
            false, "{} | captcha completed in {}ms", user.username, now - (challenge.deadlineMs - config().actionTtlMs));
        send(player, "Captcha passed - verified as human!", ChatFormatting.GREEN);
      }
    }
  }

  // ------------------------------------------------------------------ measurement

  /** Measures one tick of progress. Returns true when the challenge is complete. */
  private static boolean measure(ServerPlayer player, Challenge challenge, long now) {
    double dt = 0.05; // 20 ticks per second
    switch (challenge.type) {
      case CHALLENGE_SNEAK:
        if (player.isShiftKeyDown()) challenge.progress += dt;
        else challenge.progress = Math.max(0.0, challenge.progress - dt * 2.0); // stopping decays
        break;
      case CHALLENGE_JUMP:
        // Count ground -> air transitions with an upward velocity as a jump.
        boolean grounded =
            /*? if < 1.20.2 {*/
            /*player.isOnGround()
            *//*?} else {*/
            player.onGround()/*?}*/;
        if (!grounded && !challenge.airborne && player.getDeltaMovement().y() > 0.0) {
          challenge.airborne = true;
          challenge.progress += 1.0;
        } else if (grounded) {
          challenge.airborne = false;
        }
        break;
      case CHALLENGE_LOOK_UP:
        // Looking up (camera pitched towards the sky).
        if (player.getXRot() < -40.0f) challenge.progress += dt;
        else challenge.progress = Math.max(0.0, challenge.progress - dt * 2.0);
        break;
      default:
        break;
    }
    return challenge.progress >= challenge.target;
  }

  // ------------------------------------------------------------------ helpers

  private static int pickRandomType() {
    int[] types = {CHALLENGE_SNEAK, CHALLENGE_JUMP, CHALLENGE_LOOK_UP};
    return types[new Random().nextInt(types.length)];
  }

  private static double targetFor(int type, Config.Lobby.CaptchaConfig cfg) {
    switch (type) {
      case CHALLENGE_SNEAK:
        return Math.max(1, cfg.actionSneakSeconds);
      case CHALLENGE_JUMP:
        return Math.max(1, cfg.actionJumpCount);
      case CHALLENGE_LOOK_UP:
        return Math.max(1, cfg.actionLookUpSeconds);
      default:
        return 1;
    }
  }

  private static String taskText(int type, double target, Config.Lobby.CaptchaConfig cfg) {
    switch (type) {
      case CHALLENGE_SNEAK:
        return String.format("Hold SNEAK (Shift) for %.0f seconds!", target);
      case CHALLENGE_JUMP:
        return String.format("Jump %.0f times!", target);
      case CHALLENGE_LOOK_UP:
        return String.format("Look UP at the sky for %.0f seconds!", target);
      default:
        return "Move to prove you are human!";
    }
  }

  /** Sends a colored system chat message to the player (version-agnostic). */
  private static void send(ServerPlayer player, String text, ChatFormatting color) {
    try {
      net.minecraft.network.chat.Style style = net.minecraft.network.chat.Style.EMPTY;
      if (color != null) style = style.withColor(color);
      net.minecraft.network.chat.Component component =
          net.ded3ec.compat.Compat.text(text).setStyle(style);
      net.ded3ec.compat.Compat.sendSystemMessage(player, component, false);
    } catch (Throwable ignored) {
      // message send is best-effort
    }
  }

  private static Config.Lobby.CaptchaConfig config() {
    return AuthCoreServer.config != null ? AuthCoreServer.config.lobby.captcha : null;
  }
}