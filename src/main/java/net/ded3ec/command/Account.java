package net.ded3ec.command;

import net.ded3ec.models.Config;
import net.ded3ec.models.Lobby;
import net.ded3ec.models.Messages;
import net.ded3ec.network.EmailSender;
import net.ded3ec.network.Webhook;
import net.ded3ec.security.SecurityLog;
import net.ded3ec.util.Database;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.UUID;
import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.User;
import net.ded3ec.security.Encrypter;
import net.ded3ec.util.Logger;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.ded3ec.network.McApiManager;
import net.ded3ec.security.Security;
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
   * Registers the {@code /account} command and its subcommands with the server's command
   * dispatcher.
   *
   * <p>Supported subcommands:
   *
   * <ul>
   *   <li>{@code logout} - Ends the player's current session and forces re-authentication.
   *   <li>{@code password set <new-password>} - Changes the player's password after validation.
   *   <li>{@code unregister} - Permanently removes the player's registration from the database.
   * </ul>
   *
   * <p>Each subcommand enforces its own permission and authentication-state checks.
   *
   * @param dispatcher the Brigadier command dispatcher provided by the Minecraft server
   */
  public static void load(CommandDispatcher<CommandSourceStack> dispatcher) {
    dispatcher.register(
        literal("account")
            .then(
                literal("logout")
                    .requires(
                        (ctx) -> {
                          ServerPlayer player = McApiManager.PermissionUtil.resolvePlayer(ctx);
                          if (player == null) return false;
                          UUID uuid = player.getUUID();
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
                          ServerPlayer player = McApiManager.PermissionUtil.resolvePlayer(ctx);
                          if (player == null) return false;
                          UUID uuid = player.getUUID();
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
                                  ServerPlayer player = McApiManager.PermissionUtil.resolvePlayer(ctx);
                                  if (player == null) return false;
                                  UUID uuid = player.getUUID();
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
                literal("set-mode")
                    .then(
                        literal("online")
                            .requires(
                                (ctx) -> {
                                  ServerPlayer player = McApiManager.PermissionUtil.resolvePlayer(ctx);
                                  if (player == null) return false;
                                  UUID uuid = player.getUUID();
                                  String username = player.getName().getString();
                                  User user = User.getUser(username, uuid);

                                  return user != null
                                      && user.isAuthenticated.get()
                                      && McApiManager.PermissionUtil.has(
                                          player,
                                          AuthCoreServer.config.commands.user.setMode.luckPermsNode,
                                          AuthCoreServer.config.commands.user.setMode.permissionsLevel);
                                })
                            .executes(ctx -> setOnlineModeCommand(ctx.getSource())))
                    .then(
                        literal("offline")
                            .requires(
                                (ctx) -> {
                                  ServerPlayer player = McApiManager.PermissionUtil.resolvePlayer(ctx);
                                  if (player == null) return false;
                                  UUID uuid = player.getUUID();
                                  String username = player.getName().getString();
                                  User user = User.getUser(username, uuid);

                                  return user != null
                                      && user.isAuthenticated.get()
                                      && McApiManager.PermissionUtil.has(
                                          player,
                                          AuthCoreServer.config.commands.user.setMode.luckPermsNode,
                                          AuthCoreServer.config.commands.user.setMode.permissionsLevel);
                                })
                            .executes(ctx -> setOfflineModeCommand(ctx.getSource()))))
            .then(
                literal("codes")
                    .requires(
                        (ctx) -> {
                          ServerPlayer player = McApiManager.PermissionUtil.resolvePlayer(ctx);
                          if (player == null) return false;
                          UUID uuid = player.getUUID();
                          String username = player.getName().getString();
                          User user = User.getUser(username, uuid);

                          // isAuthenticated is REQUIRED here (unlike the pre-auth
                          // subcommands): recovery codes double as a login second factor,
                          // so a lobby player must never be able to print or regenerate
                          // them before authenticating.
                          return user != null
                              && user.isAuthenticated.get()
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
                                  ServerPlayer player = McApiManager.PermissionUtil.resolvePlayer(ctx);
                                  if (player == null) return false;
                                  UUID uuid = player.getUUID();
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
                                  ServerPlayer player = McApiManager.PermissionUtil.resolvePlayer(ctx);
                                  if (player == null) return false;
                                  UUID uuid = player.getUUID();
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
                                  ServerPlayer player = McApiManager.PermissionUtil.resolvePlayer(ctx);
                                  if (player == null) return false;
                                  UUID uuid = player.getUUID();
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
  private static int setNicknameCommand(CommandSourceStack source, String name) {
    try {
      ServerPlayer player = net.ded3ec.compat.Compat.sourcePlayer(source);
      if (player == null)
        return AuthCoreServer.LOGGER.info(0, "This command can't be executed from console!");

      String trimmed = name.trim();
      if (trimmed.length() < 2 || trimmed.length() > 24 || trimmed.matches(".*\\s+.*")) {
        net.ded3ec.security.SecurityLog.log("NICKNAME_INVALID", player.getName().getString() + " tried to set invalid nickname");
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserNicknameInvalid);
      }
      // No markup/quote characters: nicknames are displayed in chat, exports and the web
      // panel - a permissive charset would allow stored XSS payloads like <svg/onload=...>
      if (trimmed.matches(".*[<>&\"'`/\\\\;].*")) {
        net.ded3ec.security.SecurityLog.log("NICKNAME_INVALID", player.getName().getString() + " tried to set invalid nickname");
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserNicknameInvalid);
      }

      UUID uuid = player.getUUID();
      String username = player.getName().getString();
      User user = User.getUser(username, uuid);

      if (user == null)
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserInvalidCredentials);

      // Unique nicknames (default on): no two players can copy each other's nickname.
      if (AuthCoreServer.config.session.authentication.uniqueNicknames
          && User.isNicknameTaken(trimmed, uuid)) {
        net.ded3ec.security.SecurityLog.log(
            "NICKNAME_TAKEN", username + " tried to use nickname '" + trimmed + "'");
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserNicknameTaken);
      }

      user.nickname = trimmed;
      user.update("Nickname set");

      return AuthCoreServer.LOGGER.toUser(
          1, player.connection, AuthCoreServer.messages.promptUserNicknameSet, trimmed);
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
  private static int setEmailCommand(CommandSourceStack source, String address) {
    try {
      ServerPlayer player = net.ded3ec.compat.Compat.sourcePlayer(source);
      if (player == null)
        return AuthCoreServer.LOGGER.info(0, "This command can't be executed from console!");

      UUID uuid = player.getUUID();
      String username = player.getName().getString();
      User user = User.getUser(username, uuid);

      if (user == null)
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserInvalidCredentials);

      if (!net.ded3ec.security.Security.EmailRecovery.isValidEmail(address)) {
        net.ded3ec.security.SecurityLog.log("EMAIL_INVALID", username + " tried to set invalid email");
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserEmailInvalid);
      }

      user.email = address.trim().toLowerCase(java.util.Locale.ROOT);
      user.update("Email set");
      net.ded3ec.security.SecurityLog.log("EMAIL_SET", username + " set email " + user.email);

      return AuthCoreServer.LOGGER.toUser(
          1, player.connection, AuthCoreServer.messages.promptUserEmailSet, user.email);
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
  private static int recoveryRequestCommand(CommandSourceStack source, String email) {
    try {
      ServerPlayer player = net.ded3ec.compat.Compat.sourcePlayer(source);
      if (player == null)
        return AuthCoreServer.LOGGER.info(0, "This command can't be executed from console!");

      String username = player.getName().getString();
      User user = User.getUser(username, player.getUUID());

      if (user == null)
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserInvalidCredentials);

      // Recovery emails are expensive (SMTP) and attacker-abusable: without a limiter a
      // lobby player could spam this command and bomb any address. One request per minute
      // per player (EmailRecovery additionally enforces its own per-email cooldown).
      if (!net.ded3ec.security.RateLimiter.tryAcquire("recover:" + player.getUUID(), 1, 60_000L))
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserCommandCooldown, "a minute");

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
          player.connection,
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
      CommandSourceStack source, String email, String code, String newPassword) {
    try {
      ServerPlayer player = net.ded3ec.compat.Compat.sourcePlayer(source);
      if (player == null)
        return AuthCoreServer.LOGGER.info(0, "This command can't be executed from console!");

      String username = player.getName().getString();
      User user = User.getUser(username, player.getUUID());

      if (user == null)
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserInvalidCredentials);

      String normalized = email.trim().toLowerCase(java.util.Locale.ROOT);
      boolean matches = user.email != null && user.email.equalsIgnoreCase(normalized);

      if (!matches || !net.ded3ec.security.Security.EmailRecovery.verifyCode(normalized, code)) {
        net.ded3ec.security.SecurityLog.log(
            "RECOVERY_FAILED", username + " provided a wrong recovery code");
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserRecoveryInvalid);
      }

      if (StringUtils.isBlank(newPassword))
        return AuthCoreServer.LOGGER.toUser(
            0, player.connection, AuthCoreServer.messages.promptUserPasswordIsBlank);

      // Validate against the password policy (message shown to the player on failure)
      if (!net.ded3ec.security.Security.Password.check(player, newPassword)) return 0;

      user.passwordEncryption = AuthCoreServer.config.passwordRules.passwordHashAlgorithm;
      user.password = net.ded3ec.security.Encrypter.hash(user.passwordEncryption, newPassword);
      if (user.password == null) {
        // Never leave the account unregistered because hashing failed
        user.passwordEncryption = null;
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserPasswordIsBlank);
      }
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
          1, player.connection, AuthCoreServer.messages.promptUserRecoveryCompleted);
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
  private static int recoveryCodesCommand(CommandSourceStack source) {
    try {
      ServerPlayer player = net.ded3ec.compat.Compat.sourcePlayer(source);
      if (player == null)
        return AuthCoreServer.LOGGER.info(0, "This command can't be executed from console!");

      UUID uuid = player.getUUID();
      String username = player.getName().getString();
      User user = User.getUser(username, uuid);

      if (user == null)
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserInvalidCredentials);

      String codes = user.recoveryCodes;
      if (codes == null || codes.isBlank()) {
        codes = net.ded3ec.security.Security.RecoveryCodes.generate(8);
        user.recoveryCodes = codes;
        user.update("Generated recovery codes");
      }

      net.ded3ec.security.SecurityLog.log("RECOVERY_CODES_VIEWED", username);
      return AuthCoreServer.LOGGER.toUser(
          1, player.connection, AuthCoreServer.messages.promptUserRecoveryCodes, codes);
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
  private static int logoutCommand(CommandSourceStack source) {
    try {
      ServerPlayer player = net.ded3ec.compat.Compat.sourcePlayer(source);

      if (player == null)
        return AuthCoreServer.LOGGER.info(0, "This command can't be executed from console!");

      AuthCoreServer.LOGGER.debug(
          0, "{} used '/account logout' command in the Server!", player.getName().getString());

      UUID uuid = player.getUUID();
      String username = player.getName().getString();
      User user = User.getUser(username, uuid);

      if (user == null)
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserInvalidCredentials);

      AuthCoreServer.LOGGER.debug(
          1, "{}'s session has been destroyed!", player.getName().getString());
      AuthCoreServer.LOGGER.toUser(
          1, player.connection, AuthCoreServer.messages.promptUserLoggedOut);

      user.logout(AuthCoreServer.messages.promptUserLogoutComplete);

      // Tell other mods / the proxy that this player is no longer authenticated
      net.ded3ec.network.AuthInterop.broadcast(player, false);
      return 1;

    } catch (Exception err) {
      return AuthCoreServer.LOGGER.error(0, "Faced Error in '/account logout' Command: ", err);
    }
  }

  /**
   * Handles execution of the {@code /account set-mode online} subcommand.
   *
   * <p>Switches the player's own account to online-mode authentication. On the next
   * join the server verifies the account: online-mode players are auto-logged-in, players that
   * fail the Mojang session check are treated as offline-mode (register/login). The active session
   * is destroyed so the change takes effect immediately.
   *
   * @param source the command source (must be a player)
   * @return 1 on success, 0 on error or invalid state
   */
  private static int setOnlineModeCommand(CommandSourceStack source) {
    try {
      ServerPlayer player = net.ded3ec.compat.Compat.sourcePlayer(source);

      if (player == null)
        return AuthCoreServer.LOGGER.info(0, "This command can't be executed from console!");

      AuthCoreServer.LOGGER.debug(
          0, "{} used '/account set-mode online' command in the Server!", player.getName().getString());

      UUID uuid = player.getUUID();
      String username = player.getName().getString();
      User user = User.getUser(username, uuid);

      if (user == null)
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserInvalidCredentials);

      net.ded3ec.security.SecurityLog.log(
          "MODE_SET_ONLINE", username + " switched their account to online-mode");

      user.isPremium = true;
      user.update("Player Mode -> Online-mode");
      user.logout(AuthCoreServer.messages.promptUserModeSetToOnline);

      return 1;
    } catch (Exception err) {
      return AuthCoreServer.LOGGER.error(
          0, "Faced Error in '/account set-mode online' Command: ", err);
    }
  }

  /**
   * Handles execution of the {@code /account set-mode offline} subcommand.
   *
   * <p>Switches the player's own account to offline-mode (password login) authentication. A
   * player who ALREADY has a stored password keeps it and logs in with it; only premium
   * auto-login accounts (null password) are asked to register one. The active session is
   * destroyed so the change takes effect on the next join.
   *
   * @param source the command source (must be a player)
   * @return 1 on success, 0 on error or invalid state
   */
  private static int setOfflineModeCommand(CommandSourceStack source) {
    try {
      ServerPlayer player = net.ded3ec.compat.Compat.sourcePlayer(source);

      if (player == null)
        return AuthCoreServer.LOGGER.info(0, "This command can't be executed from console!");

      AuthCoreServer.LOGGER.debug(
          0, "{} used '/account set-mode offline' command in the Server!", player.getName().getString());

      UUID uuid = player.getUUID();
      String username = player.getName().getString();
      User user = User.getUser(username, uuid);

      if (user == null)
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserInvalidCredentials);

      net.ded3ec.security.SecurityLog.log(
          "MODE_SET_OFFLINE", username + " switched their account to offline-mode");

      // Offline-mode accounts authenticate with a password. A player who already registered
      // one keeps it (they just log in with it); only auto-login accounts with a null
      // password are asked to register a new one.
      boolean hadPassword = !StringUtils.isBlank(user.password);
      user.isPremium = false;
      user.update("Player Mode -> Offline-mode");
      user.logout(
          hadPassword
              ? AuthCoreServer.messages.promptUserModeSetToOfflineLogin
              : AuthCoreServer.messages.promptUserModeSetToOffline);

      return 1;
    } catch (Exception err) {
      return AuthCoreServer.LOGGER.error(
          0, "Faced Error in '/account set-mode offline' Command: ", err);
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
  private static int setPasswordCommand(CommandSourceStack source, String password) {
    try {
      ServerPlayer player = net.ded3ec.compat.Compat.sourcePlayer(source);

      if (player == null)
        return AuthCoreServer.LOGGER.info(0, "This command can't be executed from console!");

      AuthCoreServer.LOGGER.debug(
          1,
          "{} used '/account set-password <new-password>' command in the Server!",
          player.getName().getString());

      UUID uuid = player.getUUID();
      String username = player.getName().getString();
      User user = User.getUser(username, uuid);

      if (!(user != null && user.isRegistered.get()))
        return AuthCoreServer.LOGGER.toUser(
            0, player.connection, AuthCoreServer.messages.promptUserNotRegistered);

      // MFA step-up: players with 2FA enabled must have satisfied a second factor in this
      // session before sensitive actions (password change) are allowed.
      boolean mfaConfigured =
          AuthCoreServer.config.session.authentication.allowTOTPSupport
              && user.authSecret != null
              && !user.authSecret.isBlank();
      if (AuthCoreServer.config.session.authentication.requireMfaForSensitive
          && mfaConfigured
          && !user.mfaVerified) {
        net.ded3ec.security.SecurityLog.log(
            "MFA_STEPUP_BLOCKED", username + " attempted a sensitive action without a verified second factor");
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserMissing2faCode);
      }

      String tempPassword = Encrypter.hash(user.passwordEncryption, password);

      if (checkNewPassword(player, tempPassword, user.password)) {
        user.passwordEncryption = AuthCoreServer.config.passwordRules.passwordHashAlgorithm;
        user.password =
            Encrypter.hash(AuthCoreServer.config.passwordRules.passwordHashAlgorithm, password);

        if (user.password == null) {
          // Never leave the account unregistered because hashing failed
          user.passwordEncryption = null;
          return AuthCoreServer.LOGGER.toUser(
              1, player.connection, AuthCoreServer.messages.promptUserPasswordIsBlank);
        }

        user.update("Password Change");

        // Auth intelligence: post-login account-takeover pattern.
        net.ded3ec.security.AuthIntelligence.recordPasswordChange(user, player.getIpAddress());

        AuthCoreServer.LOGGER.debug(
            1, "{} password has been updated in the database!", user.username);
        return AuthCoreServer.LOGGER.toUser(
            1,
            player.connection,
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
  private static int unregisterCommand(CommandSourceStack source) {
    try {
      ServerPlayer player = net.ded3ec.compat.Compat.sourcePlayer(source);

      if (player == null)
        return AuthCoreServer.LOGGER.info(0, "This command can't be executed from console!");

      AuthCoreServer.LOGGER.debug(
          1, "{} used '/account unregister' command in the Server!", player.getName().getString());

      UUID uuid = player.getUUID();
      String username = player.getName().getString();
      User user = User.getUser(username, uuid);
      Object uniqueId =
          user != null && username != null ? username : (uuid != null ? uuid : "Empty String!");

      if (user == null)
        return AuthCoreServer.LOGGER.toUser(
            0, player.connection, AuthCoreServer.messages.promptAdminUserNotFound, uniqueId);
      else if (!user.isRegistered.get())
        return AuthCoreServer.LOGGER.toUser(
            0, player.connection, AuthCoreServer.messages.promptUserNotRegistered);

      // Kick the player BEFORE deleting their data: toKick resolves the user from the
      // cache, so deleting first would make the lookup return null and silently skip the
      // disconnect - the player would stay online with a deleted account and no feedback.
      int result =
          AuthCoreServer.LOGGER.toKick(
              1, player.connection, AuthCoreServer.messages.promptUserUnRegisteredSuccessfully);

      user.delete("Unregistered by the User", false);

      return result;
    } catch (Exception err) {
      return AuthCoreServer.LOGGER.error(0, "Faced Error in '/account unregister' Command: ", err);
    }
  }

  /**
   * Validates a proposed new password against the server-defined policy.
   *
   * <p>Checks that the password is not blank, is not a reuse of the current one when
   * {@code allowReuse} is disabled, and meets the configured complexity requirements.
   * Failure feedback is sent to the player.
   *
   * @param player the player attempting the password change
   * @param newPassword the hashed candidate password
   * @param oldPassword the current hashed password (for reuse checking)
   * @return {@code true} if the new password passes all checks, {@code false} otherwise
   */
  private static boolean checkNewPassword(
      @NotNull ServerPlayer player,
      @NotNull String newPassword,
      @NotNull String oldPassword) {
    if (StringUtils.isBlank(newPassword))
      return AuthCoreServer.LOGGER.toUser(
          false, player.connection, AuthCoreServer.messages.promptUserPasswordIsBlank);
    else if (!AuthCoreServer.config.passwordRules.allowReuse && newPassword.equals(oldPassword))
      return AuthCoreServer.LOGGER.toUser(
          false, player.connection, AuthCoreServer.messages.promptUserDuplicatePassword);
    else return (Security.Password.check(player, newPassword));
  }
}
