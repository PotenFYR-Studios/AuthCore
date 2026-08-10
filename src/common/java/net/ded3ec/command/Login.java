package net.ded3ec.command;

import net.ded3ec.models.Config;
import net.ded3ec.models.Lobby;
import net.ded3ec.models.Messages;
import net.ded3ec.util.Logger;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.string;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

import com.mojang.brigadier.CommandDispatcher;
import java.util.UUID;
import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.User;
import net.ded3ec.security.Encrypter;
import net.ded3ec.util.TimeManager;
import net.ded3ec.network.McApiManager;
import net.ded3ec.security.Security;
import net.ded3ec.security.SecurityLog;
import net.ded3ec.network.Webhook;
import net.ded3ec.compat.Compat;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.NotNull;

/** Handles the `/login` command for players to authenticate themselves on the server. */
public class Login {

  /**
   * Registers the `/login` command with the provided command dispatcher.
   *
   * @param dispatcher The command dispatcher to load the command with.
   */
  public static void load(CommandDispatcher<ServerCommandSource> dispatcher) {
    dispatcher.register(
        literal("login")
            .requires(
                (ctx) -> {
                  // Ensure the player exists and meets the conditions to use the command.
                  ServerPlayerEntity player = McApiManager.PermissionUtil.resolvePlayer(ctx);
                  if (player == null) return false;
                  UUID uuid = player.getUuid();
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
                    .executes(ctx -> execute(ctx.getSource(), getString(ctx, "password"), null, null))
                    .then(
                        // Add the "2fa-code" argument to the command.
                        argument("2fa-code", string())
                            .executes(
                                ctx ->
                                    execute(
                                        ctx.getSource(),
                                        getString(ctx, "password"),
                                        getString(ctx, "2fa-code"),
                                        null))
                            .then(
                                // Add the "captcha-code" argument to the command.
                                argument("captcha-code", string())
                                    .executes(
                                        ctx ->
                                            execute(
                                                ctx.getSource(),
                                                getString(ctx, "password"),
                                                getString(ctx, "2fa-code"),
                                                getString(ctx, "captcha-code")))))
                    .then(
                        // Add the "captcha-code" argument to the command.
                        argument("captcha-code", string())
                            .executes(
                                ctx ->
                                    execute(
                                        ctx.getSource(),
                                        getString(ctx, "password"),
                                        null,
                                        getString(ctx, "captcha-code"))))));
  }

  /**
   * Executes the `/login` command logic.
   *
   * @param source The source of the command, typically the player executing it.
   * @param password The password provided by the player.
   * @param authCode The 2fa code provided by the player.
   * @param captchaCode The captcha code provided by the player.
   * @return An integer result indicating the outcome of the command execution.
   */
  private static int execute(
      ServerCommandSource source, @NotNull String password, String authCode, String captchaCode) {
    try {
      // Retrieve the player executing the command.
      ServerPlayerEntity player = Compat.sourcePlayer(source);

      if (player == null)
        return AuthCoreServer.LOGGER.info(0, "This command can't be executed from console!");

      // Command cooldown (anti-spam)
      if (!net.ded3ec.security.RateLimiter.tryAcquire(
          "cmd:login:" + player.getUuid(),
          1,
          AuthCoreServer.config.session.rateLimit.commandCooldownMs))
        return AuthCoreServer.LOGGER.toUser(
            1, player.networkHandler, AuthCoreServer.messages.promptUserCommandCooldown, "a moment");

      // Log the usage of the `/login` command.
      AuthCoreServer.LOGGER.debug(
          0, "{} used '/login' command in the Server!", player.getName().getString());

      // Retrieve the user data associated with the player.
      UUID uuid = player.getUuid();
      String username = player.getName().getString();
      User user = User.getUser(username, uuid);

      // Handle the case where the user data is not found. A generic error is shown instead of
      // revealing whether the account exists (prevents username enumeration).
      if (user == null)
        return AuthCoreServer.LOGGER.toUser(
            1, player.networkHandler, AuthCoreServer.messages.promptUserInvalidCredentials);

      // Defense in depth: re-verify the command prerequisites at execution time (the
      // requires() predicate may have been evaluated earlier or bypassed)
      if (!user.isInLobby.get() || user.isAuthenticated.get())
        return AuthCoreServer.LOGGER.toUser(
            1, player.networkHandler, AuthCoreServer.messages.promptUserAlreadyRegistered);

      ++user.loginAttempts;

      // Account lock check (persistent auto-lock after repeated failures)
      if (AuthCoreServer.config.session.accountLock.enabled && user.isLocked()) {
        long remainingMs = user.lockUntilMs - System.currentTimeMillis();
        SecurityLog.log(
            "LOGIN_LOCKED",
            username + " tried to login while locked (" + TimeManager.toDuration(remainingMs) + " left)");
        return AuthCoreServer.LOGGER.toUser(
            1,
            player.networkHandler,
            AuthCoreServer.messages.promptUserAccountLocked,
            TimeManager.toDuration(Math.max(remainingMs, 1000)));
      }

      // Captcha verification (bot protection)
      if (AuthCoreServer.config.lobby.captcha.enabled) {
        if (captchaCode == null)
          return AuthCoreServer.LOGGER.toUser(
              1, player.networkHandler, AuthCoreServer.messages.promptUserCaptchaRequired, "?");
        if (!Security.CaptchaManager.verify(uuid, captchaCode)) {
          SecurityLog.log("LOGIN_CAPTCHA_FAIL", username + " failed the captcha");
          return AuthCoreServer.LOGGER.toUser(
              1, player.networkHandler, AuthCoreServer.messages.promptUserCaptchaWrong);
        }
      }

      // Check if the user has exceeded the maximum login attempts.
      if (user.loginAttempts >= AuthCoreServer.config.session.authentication.maxLoginAttempts)
        return handleBruteForce(user, player);
      else if (!user.isRegistered.get())
        // Inform the user if they are not registered.
        return AuthCoreServer.LOGGER.toUser(
            0, player.networkHandler, AuthCoreServer.messages.promptUserNotRegistered);
      else if (Encrypter.verify(password, user.password, user.passwordEncryption)) {
        // Authenticate the user if the password matches.

        if (AuthCoreServer.config.session.authentication.allowTOTPSupport && authCode == null)
          return AuthCoreServer.LOGGER.toUser(
              1, player.networkHandler, AuthCoreServer.messages.promptUserMissing2faCode);
        else if (AuthCoreServer.config.session.authentication.allowTOTPSupport
            && !Security.TOTPManager.verify(authCode, user.authSecret))
          return AuthCoreServer.LOGGER.toUser(
              1, player.networkHandler, AuthCoreServer.messages.promptUserWrong2faCode);

        AuthCoreServer.LOGGER.debug(
            1, "{} have authenticated in the Server!", player.getName().getString());
        user.login(player);

        // Security events (log + webhook + login history)
        int risk = user.riskScore;
      User.logLogin(user, player.getIp(), user.country.get(), "success", risk);
      SecurityLog.log("LOGIN_SUCCESS", username + " | IP: " + player.getIp() + " | Risk: " + risk);

      // Tell other mods / the proxy that this player is now authenticated
      net.ded3ec.network.AuthInterop.broadcast(player, true);
        if (risk >= AuthCoreServer.config.session.intelligence.alertRiskThreshold)
          Webhook.sendEmbed(
              "High-Risk Login",
              "**" + username + "** logged in with risk score **" + risk + "/100** from `" + player.getIp() + "`",
              0xE67E22);

        // Notify the user of successful login.
        return AuthCoreServer.LOGGER.toUser(
            1, player.networkHandler, AuthCoreServer.messages.promptUserLoggedInSuccessfully);
      } else {
        // Notify the user of an incorrect password.
        SecurityLog.log(
            "LOGIN_FAILED",
            username + " | IP: " + player.getIp() + " | Attempt: " + user.loginAttempts);
        User.logLogin(user, player.getIp(), user.country.get(), "failed", user.riskScore);

        return AuthCoreServer.LOGGER.toUser(
            1, player.networkHandler, AuthCoreServer.messages.promptUserWrongPassword);
      }
    } catch (Exception err) {
      // Log any errors encountered during command execution.
      return AuthCoreServer.LOGGER.error(0, "Faced Error in '/login' Command: ", err);
    }
  }

  /**
   * Applies the brute-force response: progressive punishment cooldown, optional account lock and
   * kick with the configured cooldown message.
   */
  private static int handleBruteForce(User user, ServerPlayerEntity player) {
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
        player.networkHandler,
        AuthCoreServer.messages.promptUserExceededLoginAttempts,
        AuthCoreServer.config.session.authentication.maxLoginAttempts,
        TimeManager.toDuration(cooldownMs));
  }
}
