package net.ded3ec.command;

import net.ded3ec.models.Config;
import net.ded3ec.models.Lobby;
import net.ded3ec.models.Messages;
import net.ded3ec.util.Logger;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.string;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.CommandDispatcher;
import java.util.UUID;
import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.User;
import net.ded3ec.security.Encrypter;
import net.ded3ec.util.TimeManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.ded3ec.network.EmailSender;
import net.ded3ec.network.McApiManager;
import net.ded3ec.security.Security;
import net.ded3ec.security.SecurityLog;
import net.ded3ec.network.Webhook;
import org.jetbrains.annotations.NotNull;

/** Handles the `/login` command for players to authenticate themselves on the server. */
public class Login {

  /**
   * Registers the `/login` command with the provided command dispatcher.
   *
   * @param dispatcher The command dispatcher to load the command with.
   */
  public static void load(CommandDispatcher<CommandSourceStack> dispatcher) {
    dispatcher.register(
        literal("login")
            .requires(
                (ctx) -> {
                  // Ensure the player exists and meets the conditions to use the command.
                  ServerPlayer player = McApiManager.PermissionUtil.resolvePlayer(ctx);
                  if (player == null) return false;
                  UUID uuid = player.getUUID();
                  String username = player.getName().getString();
                  User user = User.getUser(username, uuid);

                  // Check if the player has the required permissions.
                  return user != null
                      && user.isInLobby.get()
                      && !user.isAuthenticated.get()
                      && user.isRegistered.get()
                      && McApiManager.PermissionUtil.has(
                          player,
                          AuthCoreServer.config.commands.user.login.luckPermsNode,
                          AuthCoreServer.config.commands.user.login.permissionsLevel);
                })
            .then(
                // Add the "password" argument to the command.
                argument("password", string())
                    .executes(ctx -> execute(ctx.getSource(), getString(ctx, "password"), null))
                    .then(
                        // Add the "2fa-code" argument to the command.
                        argument("2fa-code", string())
                            .executes(
                                ctx ->
                                    execute(
                                        ctx.getSource(),
                                        getString(ctx, "password"),
                                        getString(ctx, "2fa-code"))))));
  }

  /**
   * Executes the `/login` command logic.
   *
   * @param source The source of the command, typically the player executing it.
   * @param password The password provided by the player.
   * @param authCode The 2fa code provided by the player.
   * @return An integer result indicating the outcome of the command execution.
   */
  private static int execute(
      CommandSourceStack source, @NotNull String password, String authCode) {
    try {
      // Retrieve the player executing the command.
      ServerPlayer player = net.ded3ec.compat.Compat.sourcePlayer(source);

      if (player == null)
        return AuthCoreServer.LOGGER.info(0, "This command can't be executed from console!");

      // Command cooldown (anti-spam)
      if (!net.ded3ec.security.RateLimiter.tryAcquire(
          "cmd:login:" + player.getUUID(),
          1,
          AuthCoreServer.config.session.rateLimit.commandCooldownMs))
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserCommandCooldown, "a moment");

      // Log the usage of the `/login` command.
      AuthCoreServer.LOGGER.debug(
          0, "{} used '/login' command in the Server!", player.getName().getString());

      // Retrieve the user data associated with the player.
      UUID uuid = player.getUUID();
      String username = player.getName().getString();
      User user = User.getUser(username, uuid);

      // Handle the case where the user data is not found. A generic error is shown instead of
      // revealing whether the account exists (prevents username enumeration).
      if (user == null)
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserInvalidCredentials);

      // Defense in depth: re-verify the command prerequisites at execution time (the
      // requires() predicate may have been evaluated earlier or bypassed)
      if (!user.isInLobby.get() || user.isAuthenticated.get())
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserAlreadyRegistered);

      ++user.loginAttempts;

      AuthCoreServer.LOGGER.debug(
          false,
          "{} | /login attempt #{} from {}",
          username,
          user.loginAttempts,
          player.getIpAddress());

      // Account lock check (persistent auto-lock after repeated failures)
      if (AuthCoreServer.config.session.accountLock.enabled && user.isLocked()) {
        long remainingMs = user.lockUntilMs - System.currentTimeMillis();
        AuthCoreServer.LOGGER.debug(
            false, "{} | /login blocked - account locked ({} left)", username, TimeManager.toDuration(Math.max(remainingMs, 1000)));
        SecurityLog.log(
            "LOGIN_LOCKED",
            username + " tried to login while locked (" + TimeManager.toDuration(remainingMs) + " left)");
        return AuthCoreServer.LOGGER.toUser(
            1,
            player.connection,
            AuthCoreServer.messages.promptUserAccountLocked,
            TimeManager.toDuration(Math.max(remainingMs, 1000)));
      }

      // Check if the user has exceeded the maximum login attempts.
      if (user.loginAttempts >= AuthCoreServer.config.session.authentication.maxLoginAttempts) {
        AuthCoreServer.LOGGER.debug(
            false,
            "{} | /login brute-force limit reached ({} attempts) - kicking",
            username,
            user.loginAttempts);
        return handleBruteForce(user, player);
      } else if (!user.isRegistered.get())
        // Inform the user if they are not registered.
        return AuthCoreServer.LOGGER.toUser(
            0, player.connection, AuthCoreServer.messages.promptUserNotRegistered);
      else if (Encrypter.verify(password, user.password, user.passwordEncryption)) {
        AuthCoreServer.LOGGER.debug(
            false, "{} | /login password verified - authenticating", username);
        // Authenticate the user if the password matches.

        // Transparent password-hash upgrade: weak (md5 / sha-256 / sha-512) or outdated
        // stored hashes - including AuthMe-style imported ones - are re-hashed with the
        // configured algorithm on the next successful login (best-effort; a failure never
        // blocks the login).
        upgradeHashIfNeeded(user, password);

        boolean mfaConfigured =
            AuthCoreServer.config.session.authentication.allowTOTPSupport
                && user.authSecret != null
                && !user.authSecret.isBlank();
        boolean emailMfa =
            AuthCoreServer.config.session.authentication.emailOtpSupport
                && user.email != null
                && !user.email.isBlank();

        if (mfaConfigured || emailMfa) {
          if (authCode == null || authCode.isBlank()) {
            // Missing the second factor: for email OTP, issue + send the code on this attempt.
            if (emailMfa && !mfaConfigured && !Security.EmailOtp.isPending(uuid)) {
              String code = Security.EmailOtp.issue(uuid);
              EmailSender.sendAsync(
                  user.email,
                  "AuthCore - Login verification code",
                  "Hello " + username + ",\n\nYour login verification code is: " + code
                      + "\nIt expires in 10 minutes. If this wasn't you, contact the server staff immediately.");
              SecurityLog.log("EMAIL_OTP_SENT", username + " | " + user.email);
            }
            return AuthCoreServer.LOGGER.toUser(
                1, player.connection, AuthCoreServer.messages.promptUserMissing2faCode);
          }

          boolean mfaOk =
              (mfaConfigured && Security.TOTPManager.verify(authCode, user.authSecret))
                  || (mfaConfigured && Security.RecoveryCodes.verifyAndConsume(user, authCode))
                  || (emailMfa && Security.EmailOtp.verify(uuid, authCode));
          if (!mfaOk) {
            // 2FA brute force: the password was correct, the code was not - the account is
            // likely compromised and the attacker is guessing the second factor.
            net.ded3ec.security.AuthIntelligence.recordFailed2fa(
                uuid, username, player.getIpAddress());

            // Hard stop after a small number of wrong codes: the 6-digit space is only
            // 1M, so an unbounded guess budget (in-memory counters die on restart/cache
            // eviction) is brute-forceable. Destroying the session forces a FULL re-login
            // (password + code) and counts as a failed attempt for the account lock.
            user.failed2faAttempts++;
            if (user.failed2faAttempts >= 5) {
              user.failed2faAttempts = 0;
              SecurityLog.log(
                  "2FA_LOCKOUT",
                  username + " | " + user.failed2faAttempts + " wrong 2FA codes this session");
              Webhook.sendEmbed(
                  "AuthCore - 2FA Brute Force",
                  "**" + username + "** had its session destroyed after repeated wrong "
                      + "2FA codes from `" + player.getIpAddress() + "`.",
                  0xE74C3C);
              handleBruteForce(user, player);
              return 1;
            }
            return AuthCoreServer.LOGGER.toUser(
                1, player.connection, AuthCoreServer.messages.promptUserWrong2faCode);
          } else {
            user.mfaVerified = true;
            user.failed2faAttempts = 0;
          }
        }

        AuthCoreServer.LOGGER.debug(
            1, "{} have authenticated in the Server!", player.getName().getString());
        user.login(player); // broadcasts the auth state to other mods / the proxy

        // Security events (log + webhook + login history - the history row is written by
        // user.login() so every login path is recorded consistently)
        int risk = user.riskScore;
        SecurityLog.log("LOGIN_SUCCESS", username + " | IP: " + player.getIpAddress() + " | Risk: " + risk);

        if (risk >= AuthCoreServer.config.session.intelligence.alertRiskThreshold)
          Webhook.sendEmbed(
              "High-Risk Login",
              "**" + username + "** logged in with risk score **" + risk + "/100** from `" + player.getIpAddress() + "`",
              0xE67E22);

        // Auth intelligence: guess-then-success detection.
        net.ded3ec.security.AuthIntelligence.recordSuccessfulLogin(user, player.getIpAddress());

        // Notify the user of successful login.
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserLoggedInSuccessfully);
      } else {
        // Notify the user of an incorrect password.
        SecurityLog.log(
            "LOGIN_FAILED",
            username + " | IP: " + player.getIpAddress() + " | Attempt: " + user.loginAttempts);
        User.logLogin(user, player.getIpAddress(), user.country.get(), "failed", user.riskScore);

        // Auth intelligence: password spraying + per-IP login floods.
        net.ded3ec.security.AuthIntelligence.recordFailedPassword(
            username, player.getIpAddress(), password);

        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserWrongPassword);
      }
    } catch (Exception err) {
      // Log any errors encountered during command execution.
      return AuthCoreServer.LOGGER.error(0, "Faced Error in '/login' Command: ", err);
    }
  }

  /**
   * Transparent password-hash upgrade: when the stored hash uses a weak algorithm (md5,
   * sha-256, sha-512) or an algorithm different from the configured one (e.g. AuthMe-style
   * imported hashes), it is re-hashed with the configured algorithm and persisted.
   * Best-effort: any failure is logged at debug and never blocks the login.
   */
  private static void upgradeHashIfNeeded(User user, String password) {
    try {
      String configured = AuthCoreServer.config.passwordRules.passwordHashAlgorithm;
      if (configured == null || configured.isBlank()) return;
      if (net.ded3ec.security.Encrypter.isWeakAlgorithm(configured)) return; // never downgrade

      String storedAlgo = user.passwordEncryption;
      boolean weak = net.ded3ec.security.Encrypter.isWeakAlgorithm(storedAlgo);
      boolean outdated = storedAlgo == null || !storedAlgo.equalsIgnoreCase(configured);
      if (!weak && !outdated) return;

      String fresh = net.ded3ec.security.Encrypter.hash(configured, password);
      if (fresh == null || fresh.equals(user.password)) return;

      user.passwordEncryption = configured;
      user.password = fresh;
      user.update("Password hash upgraded on login");
      SecurityLog.log(
          "PASSWORD_HASH_UPGRADED",
          user.username + " hash upgraded from '" + storedAlgo + "' to '" + configured + "'");
    } catch (Exception err) {
      AuthCoreServer.LOGGER.debug(
          false, "Password hash upgrade failed for {}:", user != null ? user.username : "?", err);
    }
  }

  /**
   * Applies the brute-force response: progressive punishment cooldown, optional account lock and
   * kick with the configured cooldown message.
   */
  private static int handleBruteForce(User user, ServerPlayer player) {
    long cooldownMs = AuthCoreServer.config.session.cooldownAfterKickMs;

    // Progressive (exponential) punishment: base * multiplier ^ offenses
    if (AuthCoreServer.config.session.progressivePunishment.enabled) {
      double factor =
          Math.pow(
              AuthCoreServer.config.session.progressivePunishment.multiplier,
              Math.min(user.kickAttempts, 10));
      cooldownMs =
          (long)
              Math.min(
                  AuthCoreServer.config.session.progressivePunishment.baseCooldownMs * factor,
                  AuthCoreServer.config.session.progressivePunishment.maxCooldownMs);
    }

    // Persistent account lock
    if (AuthCoreServer.config.session.accountLock.enabled
        && user.loginAttempts >= AuthCoreServer.config.session.accountLock.maxFailedLogins)
      user.lock(AuthCoreServer.config.session.accountLock.lockDurationMs);

    SecurityLog.log(
        "LOGIN_BRUTE_FORCE",
        user.username + " exceeded " + user.loginAttempts + " attempts | cooldown " + TimeManager.toDuration(cooldownMs));
    Webhook.sendEmbed(
        "Brute Force Detected",
        "**" + user.username + "** exceeded the maximum login attempts. Kicked for " + TimeManager.toDuration(cooldownMs) + ".",
        0xE74C3C);

    return AuthCoreServer.LOGGER.toKick(
        0,
        player.connection,
        AuthCoreServer.messages.promptUserExceededLoginAttempts,
        AuthCoreServer.config.session.authentication.maxLoginAttempts,
        TimeManager.toDuration(cooldownMs));
  }
}
