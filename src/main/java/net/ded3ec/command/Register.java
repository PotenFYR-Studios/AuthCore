package net.ded3ec.command;

import net.ded3ec.models.Config;
import net.ded3ec.models.Lobby;
import net.ded3ec.models.Messages;
import net.ded3ec.network.Webhook;
import net.ded3ec.security.SecurityLog;
import net.ded3ec.util.Logger;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.string;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.CommandDispatcher;
import java.util.UUID;
import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.User;
import net.ded3ec.network.McApiManager;
import net.ded3ec.security.Security;
import net.ded3ec.util.TimeManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Handles the `/register` command for registering new players on the server. This command allows
 * players to register with a password and optionally confirm it.
 */
public class Register {

  /**
   * Registers the `/register (password) (confirm-password)` command with the provided dispatcher.
   *
   * @param dispatcher The command dispatcher to load the command with. This allows the server to
   *     recognize and handle the `/register` command.
   */
  public static void load(CommandDispatcher<CommandSourceStack> dispatcher) {
    dispatcher.register(
        literal("register")
            .requires(
                (ctx) -> {
                  ServerPlayer player = McApiManager.PermissionUtil.resolvePlayer(ctx);
                  if (player == null) return false;

                  UUID uuid = player.getUUID();
                  String username = player.getName().getString();
                  User user = User.getUser(username, uuid);

                  // Ensures the player is in the lobby and not already registered.
                  return user != null
                      && user.isInLobby.get()
                      && !user.isAuthenticated.get()
                      && !user.isRegistered.get()
                      && McApiManager.PermissionUtil.has(
                          player,
                          AuthCoreServer.config.commands.user.register.luckPermsNode,
                          AuthCoreServer.config.commands.user.register.permissionsLevel);
                })
            .then(
                argument("password", string())
                    .executes(
                        ctx ->
                            registerCommand(
                                ctx.getSource(), getString(ctx, "password"), null, null))
                    .then(
                        argument("confirm-password", string())
                            .requires(
                                ctx ->
                                    AuthCoreServer.config
                                        .session
                                        .authentication
                                        .registerPasswordConfirmation)
                            .executes(
                                ctx ->
                                    registerCommand(
                                        ctx.getSource(),
                                        getString(ctx, "password"),
                                        getString(ctx, "confirm-password"),
                                        null))
                            .then(
                                // Add the "2fa-code" argument to the command.
                                argument("2fa-code", string())
                                    .executes(
                                        ctx ->
                                            registerCommand(
                                                ctx.getSource(),
                                                getString(ctx, "password"),
                                                getString(ctx, "confirm-password"),
                                                getString(ctx, "2fa-code")))))
                    .then(
                        // Add the "2fa-code" argument to the command.
                        argument("2fa-code", string())
                            .executes(
                                ctx ->
                                    registerCommand(
                                        ctx.getSource(),
                                        getString(ctx, "password"),
                                        null,
                                        getString(ctx, "2fa-code"))))));
  }

  /**
   * Executes the `/register` command logic.
   *
   * @param source The source of the command, typically the player executing it.
   * @param password The password provided by the player.
   * @param confirmPassword The confirmation password provided by the player (nullable).
   * @param authCode The 2fa code provided by the player.
   * @return An integer result indicating the outcome of the command execution.
   */
  private static int registerCommand(
      CommandSourceStack source,
      @NotNull String password,
      @Nullable String confirmPassword,
      String authCode) {
    try {
      ServerPlayer player = net.ded3ec.compat.Compat.sourcePlayer(source);

      if (player == null)
        return AuthCoreServer.LOGGER.info(0, "This command can't be executed from console!");

      // Command cooldown (anti-spam)
      if (!net.ded3ec.security.RateLimiter.tryAcquire(
          "cmd:register:" + player.getUUID(),
          1,
          AuthCoreServer.config.session.rateLimit.commandCooldownMs))
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserCommandCooldown, "a moment");

      AuthCoreServer.LOGGER.debug(
          0, "{} used '/register' command in the Server!", player.getName().getString());

      UUID uuid = player.getUUID();
      String username = player.getName().getString();
      User user = User.getUser(username, uuid);

      // Handle cases where the user data is not found or the user is already registered.
      if (user == null)
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserInvalidCredentials);

      if (user.isRegistered.get())
        return AuthCoreServer.LOGGER.toUser(
            0, player.connection, AuthCoreServer.messages.promptUserAlreadyRegistered);

      // Defense in depth: re-verify the command prerequisites at execution time
      if (!user.isInLobby.get() || user.isAuthenticated.get())
        return AuthCoreServer.LOGGER.toUser(
            0, player.connection, AuthCoreServer.messages.promptUserAlreadyRegistered);

      // Account lock check (persistent auto-lock after repeated failed logins)
      if (AuthCoreServer.config.session.accountLock.enabled && user.isLocked()) {
        long remainingMs = user.lockUntilMs - System.currentTimeMillis();
        return AuthCoreServer.LOGGER.toUser(
            1,
            player.connection,
            AuthCoreServer.messages.promptUserAccountLocked,
            TimeManager.toDuration(Math.max(remainingMs, 1000)));
      }

      // Mass-registration penalty: this IP was flagged as a registration farm.
      if (net.ded3ec.security.AuthIntelligence.isRegistrationBlocked(player.getIpAddress()))
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserInvalidCredentials);

      // Validate the password and load the user if valid.
      if (checkPassword(player, password, confirmPassword)) {

        // 2FA at registration applies ONLY to re-registering an account that already has a
        // TOTP secret (e.g. after admin deletion + same-name rejoin). A FRESH account has no
        // secret yet (it is generated after registration), so demanding a code there would
        // make registration impossible. Mirrors the mfaConfigured pattern of /login.
        boolean mfaConfigured =
            AuthCoreServer.config.session.authentication.allowTOTPSupport
                && user.authSecret != null
                && !user.authSecret.isBlank();
        if (mfaConfigured && authCode == null)
          return AuthCoreServer.LOGGER.toUser(
              1, player.connection, AuthCoreServer.messages.promptUserMissing2faCode);
        else if (mfaConfigured && !Security.TOTPManager.verify(authCode, user.authSecret))
          return AuthCoreServer.LOGGER.toUser(
              1, player.connection, AuthCoreServer.messages.promptUserWrong2faCode);

        AuthCoreServer.LOGGER.debug(
            1, "{} has been registered to the Server!", player.getName().getString());
        AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserRegisteredSuccessfully);

        AuthCoreServer.LOGGER.debug(
            false,
            "{} | /register completed from {} (2FA configured: {})",
            username,
            player.getIpAddress(),
            AuthCoreServer.config.session.authentication.allowTOTPSupport);

        user.register(player, password); // broadcasts the auth state on login

        net.ded3ec.security.SecurityLog.log("REGISTER", username + " | IP: " + player.getIpAddress());
        net.ded3ec.security.AuthIntelligence.recordRegistration(username, player.getIpAddress());
        net.ded3ec.network.Webhook.send(
            ":white_check_mark: **" + username + "** registered on the server.");
        return 1;
      }

      return 0;
    } catch (Exception err) {
      return AuthCoreServer.LOGGER.error(0, "Faced Error in '/register' Command: ", err);
    }
  }

  /**
   * Validates the password and confirmation password (if required).
   *
   * @param player The player attempting to load.
   * @param password The password provided by the player.
   * @param confirmPassword The confirmation password provided by the player (nullable).
   * @return True if the password is valid, false otherwise.
   */
  private static boolean checkPassword(
      @NotNull ServerPlayer player,
      @NotNull String password,
      @Nullable String confirmPassword) {
    if (StringUtils.isBlank(password))
      return AuthCoreServer.LOGGER.toUser(
          false, player.connection, AuthCoreServer.messages.promptUserPasswordIsBlank);
    else if (AuthCoreServer.config.session.authentication.registerPasswordConfirmation
        && StringUtils.isBlank(confirmPassword))
      return AuthCoreServer.LOGGER.toUser(
          false, player.connection, AuthCoreServer.messages.promptUserConfirmPasswordIsBlank);
    else if (AuthCoreServer.config.session.authentication.registerPasswordConfirmation
        && !password.equals(confirmPassword))
      return AuthCoreServer.LOGGER.toUser(
          false, player.connection, AuthCoreServer.messages.promptUserPasswordDoesNotMatch);
    else return (Security.Password.check(player, password));
  }
}
