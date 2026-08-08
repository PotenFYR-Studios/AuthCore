package net.ded3ec.command;

import net.ded3ec.models.Config;
import net.ded3ec.models.Lobby;
import net.ded3ec.models.Messages;
import net.ded3ec.network.EmailSender;
import net.ded3ec.network.Webhook;
import net.ded3ec.security.SecurityLog;
import net.ded3ec.util.Database;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.UUID;
import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.User;
import net.ded3ec.security.Encrypter;
import net.ded3ec.util.Logger;
import net.ded3ec.network.McApiManager;
import net.ded3ec.security.Security;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;

/**
 * Command handler for the {@code /account} command and its subcommands.
 *
 * <p>This command allows authenticated players to manage their account-related actions on the
 * server, including logging out of their current session, changing their password, or completely
 * unregistering their account from the authentication system.
 *
 * <p>All subcommands are restricted to player execution only (console is blocked) and require the
 * appropriate permission level as well as the configured LuckPerms permission node.
 */
public class Account {

  /**
   * Registers the {@code /account} command and all its subcommands with the server's command
   * dispatcher.
   *
   * <p>Supported subcommands:
   *
   * <ul>
   *   <li>{@code logout} – Ends the player's current session and forces re-authentication.
   *   <li>{@code password set <new-password>} – Changes the player's password after validation.
   *   <li>{@code unregister} – Permanently removes the player's registration from the database.
   * </ul>
   *
   * <p>Each subcommand enforces its own permission requirements and authentication state checks.
   *
   * @param dispatcher the Brigadier command dispatcher provided by the Minecraft server
   */
  public static void load(CommandDispatcher<ServerCommandSource> dispatcher) {
    dispatcher.register(
        literal("account")
            .then(
                literal("logout")
                    .requires(
                        (ctx) -> {
                          ServerPlayerEntity player = McApiManager.PermissionUtil.resolvePlayer(ctx);
                          if (player == null) return false;
                          UUID uuid = player.getUuid();
                          String username = player.getName().getString();
                          User user = User.getUser(username, uuid);

                          return user != null
                              && user.isActive
                              && McApiManager.PermissionUtil.has(
                                  player,
                                  AuthCoreServer.config.commands.user.logout.luckPermsNode,
                                  AuthCoreServer.config.commands.user.logout.permissionsLevel);
                        })
                    .executes(ctx -> logoutCommand(ctx.getSource())))
            .then(
                literal("unregister")
                    .requires(
                        (ctx) -> {
                          ServerPlayerEntity player = McApiManager.PermissionUtil.resolvePlayer(ctx);
                          if (player == null) return false;
                          UUID uuid = player.getUuid();
                          String username = player.getName().getString();
                          User user = User.getUser(username, uuid);

                          return user != null
                              && user.isAuthenticated.get()
                              && user.isRegistered.get()
                              && McApiManager.PermissionUtil.has(
                                  player,
                                  AuthCoreServer.config.commands.user.unregister.luckPermsNode,
                                  AuthCoreServer.config.commands.user.unregister.permissionsLevel);
                        })
                    .executes(ctx -> unregisterCommand(ctx.getSource())))
            .then(
                literal("set-password")
                    .then(
                        argument("new-password", StringArgumentType.string())
                            .requires(
                                (ctx) -> {
                                  ServerPlayerEntity player = McApiManager.PermissionUtil.resolvePlayer(ctx);
                                  if (player == null) return false;
                                  UUID uuid = player.getUuid();
                                  String username = player.getName().getString();
                                  User user = User.getUser(username, uuid);

                                  return user != null
                                      && user.isRegistered.get()
                                      && user.isAuthenticated.get()
                                      && McApiManager.PermissionUtil.has(
                                          player,
                                          AuthCoreServer.config
                                              .commands
                                              .user
                                              .changePassword
                                              .luckPermsNode,
                                          AuthCoreServer.config
                                              .commands
                                              .user
                                              .changePassword
                                              .permissionsLevel);
                                })
                            .executes(
                                ctx ->
                                    setPasswordCommand(
                                        ctx.getSource(), getString(ctx, "new-password")))))
            .then(
                literal("codes")
                    .requires(
                        (ctx) -> {
                          ServerPlayerEntity player = McApiManager.PermissionUtil.resolvePlayer(ctx);
                          if (player == null) return false;
                          UUID uuid = player.getUuid();
                          String username = player.getName().getString();
                          User user = User.getUser(username, uuid);

                          return user != null
                              && user.isRegistered.get()
                              && McApiManager.PermissionUtil.has(
                                  player,
                                  AuthCoreServer.config.commands.user.logout.luckPermsNode,
                                  AuthCoreServer.config.commands.user.logout.permissionsLevel);
                        })
                    .executes(ctx -> recoveryCodesCommand(ctx.getSource())))
            .then(
                literal("email")
                    .then(
                        argument("address", StringArgumentType.string())
                            .requires(
                                (ctx) -> {
                                  ServerPlayerEntity player = McApiManager.PermissionUtil.resolvePlayer(ctx);
                                  if (player == null) return false;
                                  UUID uuid = player.getUuid();
                                  String username = player.getName().getString();
                                  User user = User.getUser(username, uuid);

                                  return user != null
                                      && user.isAuthenticated.get()
                                      && user.isRegistered.get()
                                      && McApiManager.PermissionUtil.has(
                                          player,
                                          AuthCoreServer.config.commands.user.logout.luckPermsNode,
                                          AuthCoreServer.config.commands.user.logout.permissionsLevel);
                                })
                            .executes(
                                ctx ->
                                    setEmailCommand(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "address")))))
            .then(
                literal("nickname")
                    .then(
                        argument("name", StringArgumentType.string())
                            .requires(
                                (ctx) -> {
                                  ServerPlayerEntity player = McApiManager.PermissionUtil.resolvePlayer(ctx);
                                  if (player == null) return false;
                                  UUID uuid = player.getUuid();
                                  String username = player.getName().getString();
                                  User user = User.getUser(username, uuid);

                                  return user != null
                                      && user.isAuthenticated.get()
                                      && user.isRegistered.get()
                                      && McApiManager.PermissionUtil.has(
                                          player,
                                          AuthCoreServer.config.commands.user.logout.luckPermsNode,
                                          AuthCoreServer.config.commands.user.logout.permissionsLevel);
                                })
                            .executes(
                                ctx ->
                                    setNicknameCommand(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name")))))
            .then(
                literal("recover")
                    .then(
                        argument("email", StringArgumentType.string())
                            .requires(
                                (ctx) -> {
                                  ServerPlayerEntity player = McApiManager.PermissionUtil.resolvePlayer(ctx);
                                  if (player == null) return false;
                                  UUID uuid = player.getUuid();
                                  String username = player.getName().getString();
                                  User user = User.getUser(username, uuid);

                                  return user != null
                                      && user.isRegistered.get()
                                      && !user.isAuthenticated.get()
                                      && McApiManager.PermissionUtil.has(
                                          player,
                                          AuthCoreServer.config.commands.user.logout.luckPermsNode,
                                          AuthCoreServer.config.commands.user.logout.permissionsLevel);
                                })
                            .executes(
                                ctx ->
                                    recoveryRequestCommand(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "email")))
                            .then(
                                argument("code", StringArgumentType.string())
                                    .then(
                                        argument("new-password", StringArgumentType.string())
                                            .executes(
                                                ctx ->
                                                    recoveryCompleteCommand(
                                                        ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "email"),
                                                        StringArgumentType.getString(ctx, "code"),
                                                        StringArgumentType.getString(
                                                            ctx, "new-password"))))))));
  }

  /**
   * Sets the player's display nickname.
   *
   * @param source the command source (must be a player)
   * @param name the new nickname
   * @return 1 on success, 0 on failure
   */
  private static int setNicknameCommand(ServerCommandSource source, String name) {
    try {
      ServerPlayerEntity player = source.getPlayer();
      if (player == null)
        return AuthCoreServer.LOGGER.info(0, "This command can't be executed from console!");

      String trimmed = name.trim();
      if (trimmed.length() < 2 || trimmed.length() > 24 || trimmed.matches(".*\\s+.*")) {
        net.ded3ec.security.SecurityLog.log("NICKNAME_INVALID", player.getName().getString() + " tried to set invalid nickname");
        return AuthCoreServer.LOGGER.toUser(
            1, player.networkHandler, AuthCoreServer.messages.promptUserEmailInvalid);
      }

      UUID uuid = player.getUuid();
      String username = player.getName().getString();
      User user = User.getUser(username, uuid);

      if (user == null)
        return AuthCoreServer.LOGGER.toUser(
            1, player.networkHandler, AuthCoreServer.messages.promptUserInvalidCredentials);

      user.nickname = trimmed;
      user.update("Nickname set");

      return AuthCoreServer.LOGGER.toUser(
          1, player.networkHandler, AuthCoreServer.messages.promptUserNicknameSet, trimmed);
    } catch (Exception err) {
      return AuthCoreServer.LOGGER.error(0, "Faced Error in '/account nickname' Command: ", err);
    }
  }

  /**
   * Sets the account email (used for login alerts and password recovery).
   *
   * @param source the command source (must be a player)
   * @param address the email address
   * @return 1 on success, 0 on failure
   */
  private static int setEmailCommand(ServerCommandSource source, String address) {
    try {
      ServerPlayerEntity player = source.getPlayer();
      if (player == null)
        return AuthCoreServer.LOGGER.info(0, "This command can't be executed from console!");

      UUID uuid = player.getUuid();
      String username = player.getName().getString();
      User user = User.getUser(username, uuid);

      if (user == null)
        return AuthCoreServer.LOGGER.toUser(
            1, player.networkHandler, AuthCoreServer.messages.promptUserInvalidCredentials);

      if (!net.ded3ec.security.Security.EmailRecovery.isValidEmail(address)) {
        net.ded3ec.security.SecurityLog.log("EMAIL_INVALID", username + " tried to set invalid email");
        return AuthCoreServer.LOGGER.toUser(
            1, player.networkHandler, AuthCoreServer.messages.promptUserEmailInvalid);
      }

      user.email = address.trim().toLowerCase(java.util.Locale.ROOT);
      user.update("Email set");
      net.ded3ec.security.SecurityLog.log("EMAIL_SET", username + " set email " + user.email);

      return AuthCoreServer.LOGGER.toUser(
          1, player.networkHandler, AuthCoreServer.messages.promptUserEmailSet, user.email);
    } catch (Exception err) {
      return AuthCoreServer.LOGGER.error(0, "Faced Error in '/account email' Command: ", err);
    }
  }

  /**
   * Requests a password recovery code by email. Only works for players in the lobby (they
   * forgot their password).
   *
   * @param source the command source (must be a player)
   * @param email the registered email address
   * @return 1 on success, 0 on failure
   */
  private static int recoveryRequestCommand(ServerCommandSource source, String email) {
    try {
      ServerPlayerEntity player = source.getPlayer();
      if (player == null)
        return AuthCoreServer.LOGGER.info(0, "This command can't be executed from console!");

      String username = player.getName().getString();
      User user = User.getUser(username, player.getUuid());

      if (user == null)
        return AuthCoreServer.LOGGER.toUser(
            1, player.networkHandler, AuthCoreServer.messages.promptUserInvalidCredentials);

      // Never reveal whether an email matches: always respond the same way.
      String normalized = email.trim().toLowerCase(java.util.Locale.ROOT);
      boolean matches = user.email != null && user.email.equalsIgnoreCase(normalized);

      if (matches && net.ded3ec.network.EmailSender.isEnabled()) {
        String code = net.ded3ec.security.Security.EmailRecovery.generateCode(normalized);
        if (code != null) {
          net.ded3ec.network.EmailSender.sendAsync(
              user.email,
              "AuthCore - Password recovery",
              "Your AuthCore recovery code is: " + code + "\n"
                  + "Use it within 15 minutes:\n"
                  + "/account recover <email> <code> <new-password>\n\n"
                  + "If you did not request this, you can ignore this email.");
          net.ded3ec.security.SecurityLog.log("RECOVERY_SENT", username + " requested a recovery code");
        }
      }

      return AuthCoreServer.LOGGER.toUser(
          1,
          player.networkHandler,
          AuthCoreServer.messages.promptUserEmailRecoverySent,
          email);
    } catch (Exception err) {
      return AuthCoreServer.LOGGER.error(0, "Faced Error in '/account recover' Command: ", err);
    }
  }

  /**
   * Completes password recovery with the emailed code.
   *
   * @param source the command source (must be a player)
   * @param email the registered email address
   * @param code the recovery code from the email
   * @param newPassword the new password
   * @return 1 on success, 0 on failure
   */
  private static int recoveryCompleteCommand(
      ServerCommandSource source, String email, String code, String newPassword) {
    try {
      ServerPlayerEntity player = source.getPlayer();
      if (player == null)
        return AuthCoreServer.LOGGER.info(0, "This command can't be executed from console!");

      String username = player.getName().getString();
      User user = User.getUser(username, player.getUuid());

      if (user == null)
        return AuthCoreServer.LOGGER.toUser(
            1, player.networkHandler, AuthCoreServer.messages.promptUserInvalidCredentials);

      String normalized = email.trim().toLowerCase(java.util.Locale.ROOT);
      boolean matches = user.email != null && user.email.equalsIgnoreCase(normalized);

      if (!matches || !net.ded3ec.security.Security.EmailRecovery.verifyCode(normalized, code)) {
        net.ded3ec.security.SecurityLog.log(
            "RECOVERY_FAILED", username + " provided a wrong recovery code");
        return AuthCoreServer.LOGGER.toUser(
            1, player.networkHandler, AuthCoreServer.messages.promptUserRecoveryInvalid);
      }

      if (StringUtils.isBlank(newPassword))
        return AuthCoreServer.LOGGER.toUser(
            0, player.networkHandler, AuthCoreServer.messages.promptUserPasswordIsBlank);

      // Validate against the password policy (message shown to the player on failure)
      if (!net.ded3ec.security.Security.Password.check(player, newPassword)) return 0;

      user.passwordEncryption = AuthCoreServer.config.passwordRules.passwordHashAlgorithm;
      user.password = net.ded3ec.security.Encrypter.hash(user.passwordEncryption, newPassword);
      user.loginAttempts = 0;
      user.authSecret = null; // 2FA secret must be re-set up after recovery
      user.update("Password recovered via email");

      net.ded3ec.security.SecurityLog.log(
          "RECOVERY_COMPLETED", username + " recovered their password via email");
      net.ded3ec.network.Webhook.sendEmbed(
          "Password Recovered",
          "**" + username + "** recovered their password via email code.",
          0x2ECC71);

      return AuthCoreServer.LOGGER.toUser(
          1, player.networkHandler, AuthCoreServer.messages.promptUserRecoveryCompleted);
    } catch (Exception err) {
      return AuthCoreServer.LOGGER.error(0, "Faced Error in '/account recover' Command: ", err);
    }
  }

  /**
   * Displays the player's backup recovery codes (used for account recovery).
   *
   * @param source the command source (must be a player)
   * @return 1 on success, 0 on failure
   */
  private static int recoveryCodesCommand(ServerCommandSource source) {
    try {
      ServerPlayerEntity player = source.getPlayer();
      if (player == null)
        return AuthCoreServer.LOGGER.info(0, "This command can't be executed from console!");

      UUID uuid = player.getUuid();
      String username = player.getName().getString();
      User user = User.getUser(username, uuid);

      if (user == null)
        return AuthCoreServer.LOGGER.toUser(
            1, player.networkHandler, AuthCoreServer.messages.promptUserInvalidCredentials);

      String codes = user.recoveryCodes;
      if (codes == null || codes.isBlank()) {
        codes = net.ded3ec.security.Security.RecoveryCodes.generate(8);
        user.recoveryCodes = codes;
        user.update("Generated recovery codes");
      }

      net.ded3ec.security.SecurityLog.log("RECOVERY_CODES_VIEWED", username);
      return AuthCoreServer.LOGGER.toUser(
          1, player.networkHandler, AuthCoreServer.messages.promptUserRecoveryCodes, codes);
    } catch (Exception err) {
      return AuthCoreServer.LOGGER.error(0, "Faced Error in '/account codes' Command: ", err);
    }
  }

  /**
   * Handles execution of the {@code /account logout} subcommand.
   *
   * <p>Destroys the player's active authentication session, sends feedback messages, and forces the
   * player to re-authenticate for protected actions.
   *
   * @param source the command source (must be a player)
   * @return 1 on success, 0 if the command cannot be executed or an error occurs
   */
  private static int logoutCommand(ServerCommandSource source) {
    try {
      ServerPlayerEntity player = source.getPlayer();

      if (player == null)
        return AuthCoreServer.LOGGER.info(0, "This command can't be executed from console!");

      AuthCoreServer.LOGGER.debug(
          0, "{} used '/account logout' command in the Server!", player.getName().getString());

      UUID uuid = player.getUuid();
      String username = player.getName().getString();
      User user = User.getUser(username, uuid);

      if (user == null)
        return AuthCoreServer.LOGGER.toUser(
            1, player.networkHandler, AuthCoreServer.messages.promptUserInvalidCredentials);

      AuthCoreServer.LOGGER.debug(
          1, "{}'s session has been destroyed!", player.getName().getString());
      AuthCoreServer.LOGGER.toUser(
          1, player.networkHandler, AuthCoreServer.messages.promptUserLoggedOut);

      user.logout(AuthCoreServer.messages.promptUserSessionExpired);
      return 1;

    } catch (Exception err) {
      return AuthCoreServer.LOGGER.error(0, "Faced Error in '/account logout' Command: ", err);
    }
  }

  /**
   * Handles execution of the {@code /account set-password <new-password>} subcommand.
   *
   * <p>Validates the new password against configured rules, re-hashes it using the server's chosen
   * algorithm, updates the database entry, and informs the player of the result.
   *
   * @param source the command source (must be a player)
   * @param password the raw new password entered by the player
   * @return 1 on successful password change, 0 on validation failure or error
   */
  private static int setPasswordCommand(ServerCommandSource source, String password) {
    try {
      ServerPlayerEntity player = source.getPlayer();

      if (player == null)
        return AuthCoreServer.LOGGER.info(0, "This command can't be executed from console!");

      AuthCoreServer.LOGGER.debug(
          1,
          "{} used '/account set-password <new-password>' command in the Server!",
          player.getName().getString());

      UUID uuid = player.getUuid();
      String username = player.getName().getString();
      User user = User.getUser(username, uuid);

      if (!(user != null && user.isRegistered.get()))
        return AuthCoreServer.LOGGER.toUser(
            0, player.networkHandler, AuthCoreServer.messages.promptUserNotRegistered);

      String tempPassword = Encrypter.hash(user.passwordEncryption, password);

      if (checkNewPassword(player, tempPassword, user.password)) {
        user.passwordEncryption = AuthCoreServer.config.passwordRules.passwordHashAlgorithm;
        user.password =
            Encrypter.hash(AuthCoreServer.config.passwordRules.passwordHashAlgorithm, password);

        user.update("Password Change");

        AuthCoreServer.LOGGER.debug(
            1, "{} password has been updated in the database!", user.username);
        return AuthCoreServer.LOGGER.toUser(
            1,
            player.networkHandler,
            AuthCoreServer.messages.promptUserPasswordChangedSuccessfully);

      } else return 0;
    } catch (Exception err) {
      return AuthCoreServer.LOGGER.error(
          0, "Faced Error in '/account set-password <new-password>' Command: ", err);
    }
  }

  /**
   * Handles execution of the {@code /account unregister} subcommand.
   *
   * <p>Removes the player's password and encryption method from the database, effectively
   * unregistering their account. The player will be required to load again to authenticate.
   *
   * @param source the command source (must be a player)
   * @return 1 on successful unregistration, 0 on error or invalid state
   */
  private static int unregisterCommand(ServerCommandSource source) {
    try {
      ServerPlayerEntity player = source.getPlayer();

      if (player == null)
        return AuthCoreServer.LOGGER.info(0, "This command can't be executed from console!");

      AuthCoreServer.LOGGER.debug(
          1, "{} used '/account unregister' command in the Server!", player.getName().getString());

      UUID uuid = player.getUuid();
      String username = player.getName().getString();
      User user = User.getUser(username, uuid);
      Object uniqueId =
          user != null && username != null ? username : (uuid != null ? uuid : "Empty String!");

      if (user == null)
        return AuthCoreServer.LOGGER.toUser(
            0, player.networkHandler, AuthCoreServer.messages.promptAdminUserNotFound, uniqueId);
      else if (!user.isRegistered.get())
        return AuthCoreServer.LOGGER.toUser(
            0, player.networkHandler, AuthCoreServer.messages.promptUserNotRegistered);

      user.delete("Unregistered by the User", false);

      return AuthCoreServer.LOGGER.toKick(
          1, player.networkHandler, AuthCoreServer.messages.promptUserUnRegisteredSuccessfully);
    } catch (Exception err) {
      return AuthCoreServer.LOGGER.error(0, "Faced Error in '/account unregister' Command: ", err);
    }
  }

  /**
   * Validates a proposed new password against server-defined policies.
   *
   * <p>Validation steps include:
   *
   * <ul>
   *   <li>Ensuring the password is not blank
   *   <li>Preventing reuse if {@code allowReuse} is disabled in configuration
   *   <li>Checking complexity requirements.
   * </ul>
   *
   * <p>Appropriate feedback messages are sent to the player when validation fails.
   *
   * @param player the player attempting the password change
   * @param newPassword the hashed candidate password
   * @param oldPassword the current hashed password (for reuse checking)
   * @return {@code true} if the new password passes all checks, {@code false} otherwise
   */
  private static boolean checkNewPassword(
      @NotNull ServerPlayerEntity player,
      @NotNull String newPassword,
      @NotNull String oldPassword) {
    if (StringUtils.isBlank(newPassword))
      return AuthCoreServer.LOGGER.toUser(
          false, player.networkHandler, AuthCoreServer.messages.promptUserPasswordIsBlank);
    else if (!AuthCoreServer.config.passwordRules.allowReuse && newPassword.equals(oldPassword))
      return AuthCoreServer.LOGGER.toUser(
          false, player.networkHandler, AuthCoreServer.messages.promptUserDuplicatePassword);
    else return (Security.Password.check(player, newPassword));
  }
}
