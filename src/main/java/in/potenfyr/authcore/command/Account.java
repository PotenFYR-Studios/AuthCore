package in.potenfyr.authcore.command;

import in.potenfyr.authcore.network.EmailSender;
import in.potenfyr.authcore.network.Webhook;
import in.potenfyr.authcore.security.SecurityLog;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.UUID;
import in.potenfyr.authcore.AuthCoreServer;
import in.potenfyr.authcore.models.User;
import in.potenfyr.authcore.security.Encrypter;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import in.potenfyr.authcore.network.McApiManager;
import in.potenfyr.authcore.security.Security;
import org.apache.commons.lang3.StringUtils;

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
    var root = dispatcher.register(
        literal("account")
            .requires(CommandVisibility::canUseAccount)
            .then(
                literal("logout")
                    .requires(CommandVisibility::canLogout)
                    .executes(ctx -> logoutCommand(ctx.getSource())))
            .then(
                literal("unregister")
                    .requires(CommandVisibility::canUnregister)
                    .executes(ctx -> unregisterCommand(ctx.getSource())))
            .then(
                literal("set-password")
                    .then(
                        argument("new-password", StringArgumentType.string())
                            .requires(CommandVisibility::canChangePassword)
                            .executes(
                                ctx ->
                                    setPasswordCommand(
                                        ctx.getSource(), getString(ctx, "new-password")))))
            .then(
                literal("set-mode")
                    .then(
                        literal("online")
                            .requires(CommandVisibility::canSetModeOnline)
                            .executes(ctx -> setOnlineModeCommand(ctx.getSource())))
                    .then(
                        literal("offline")
                            .requires(CommandVisibility::canSetModeOffline)
                            .executes(ctx -> setOfflineModeCommand(ctx.getSource()))))
            .then(
                literal("codes")
                    .requires(CommandVisibility::canViewRecoveryCodes)
                    .executes(ctx -> recoveryCodesCommand(ctx.getSource())))
            .then(
                literal("email")
                    .then(
                        argument("address", StringArgumentType.string())
                            .requires(CommandVisibility::canSetEmail)
                            .executes(
                                ctx ->
                                    setEmailCommand(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "address")))))
            .then(
                literal("nickname")
                    .then(
                        argument("name", StringArgumentType.string())
                            .requires(CommandVisibility::canSetNickname)
                            .executes(
                                ctx ->
                                    setNicknameCommand(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name")))))
            .then(
                literal("recover")
                    .then(
                        argument("email", StringArgumentType.string())
                            .requires(CommandVisibility::canRecover)
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
    // Every subcommand runs only in its state; otherwise the player is told why.
    CommandTree.guardChild(root, "logout", CommandVisibility.PASSWORD_ACCOUNT);
    CommandTree.guardChild(root, "unregister", CommandVisibility.PASSWORD_ACCOUNT);
    CommandTree.guardChild(root, "set-password", CommandVisibility.PASSWORD_ACCOUNT);
    CommandTree.guardChild(root.getChild("set-mode"), "online", CommandVisibility.MODE_ONLINE);
    CommandTree.guardChild(root.getChild("set-mode"), "offline", CommandVisibility.MODE_OFFLINE);
    CommandTree.guardChild(root, "codes", CommandVisibility.RECOVERY_CODES);
    CommandTree.guardChild(root, "email", CommandVisibility.EMAIL);
    CommandTree.guardChild(root, "nickname", CommandVisibility.LOGGED_IN);
    CommandTree.guardChild(root, "recover", CommandVisibility.RECOVER);
    // e.g. "set-mode" with both modes hidden must not show as a dead end.
    CommandTree.pruneUnusable(root);
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
      ServerPlayer player = in.potenfyr.authcore.compat.Compat.sourcePlayer(source);
      if (player == null)
        return AuthCoreServer.LOGGER.info(0, "This command can't be executed from console!");

      String trimmed = name.trim();
      if (trimmed.length() < 2 || trimmed.length() > 24 || trimmed.matches(".*\\s+.*")) {
        in.potenfyr.authcore.security.SecurityLog.log("NICKNAME_INVALID", player.getName().getString() + " tried to set invalid nickname");
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserNicknameInvalid);
      }
      // No markup/quote characters: nicknames are displayed in chat, exports and the web
      // panel - a permissive charset would allow stored XSS payloads like <svg/onload=...>
      if (trimmed.matches(".*[<>&\"'`/\\\\;].*")) {
        in.potenfyr.authcore.security.SecurityLog.log("NICKNAME_INVALID", player.getName().getString() + " tried to set invalid nickname");
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
        in.potenfyr.authcore.security.SecurityLog.log(
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
      ServerPlayer player = in.potenfyr.authcore.compat.Compat.sourcePlayer(source);
      if (player == null)
        return AuthCoreServer.LOGGER.info(0, "This command can't be executed from console!");

      UUID uuid = player.getUUID();
      String username = player.getName().getString();
      User user = User.getUser(username, uuid);

      if (user == null)
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserInvalidCredentials);

      // The recovery email IS a password-reset channel: changing it from a resumed (IP-only)
      // session, or without the second factor, let anyone behind the same IP point recovery at
      // their own inbox and take the account over with /account recover.
      if (!sensitiveStepUp(user, player, "/account email")) return 0;

      if (!in.potenfyr.authcore.security.Security.EmailRecovery.isValidEmail(address)) {
        in.potenfyr.authcore.security.SecurityLog.log("EMAIL_INVALID", username + " tried to set invalid email");
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserEmailInvalid);
      }

      user.email = address.trim().toLowerCase(java.util.Locale.ROOT);
      user.update("Email set");
      in.potenfyr.authcore.security.SecurityLog.log("EMAIL_SET", username + " set email " + user.email);

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
      ServerPlayer player = in.potenfyr.authcore.compat.Compat.sourcePlayer(source);
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
      if (!in.potenfyr.authcore.security.RateLimiter.tryAcquire("recover:" + player.getUUID(), 1, 60_000L))
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserCommandCooldown, "a minute");

      // Never reveal whether an email matches: always respond the same way.
      String normalized = email.trim().toLowerCase(java.util.Locale.ROOT);
      boolean matches = user.email != null && user.email.equalsIgnoreCase(normalized);

      if (matches && in.potenfyr.authcore.network.EmailSender.isEnabled()) {
        String code =
            in.potenfyr.authcore.security.Security.EmailRecovery.generateCode(user.uuid, normalized);
        if (code != null) {
          in.potenfyr.authcore.network.EmailSender.sendAsync(
              user.email,
              "AuthCore - Password recovery",
              "Your AuthCore recovery code is: " + code + "\n"
                  + "Use it within 15 minutes:\n"
                  + "/account recover <email> <code> <new-password>\n\n"
                  + "If you did not request this, you can ignore this email.");
          in.potenfyr.authcore.security.SecurityLog.log("RECOVERY_SENT", username + " requested a recovery code");
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
      ServerPlayer player = in.potenfyr.authcore.compat.Compat.sourcePlayer(source);
      if (player == null)
        return AuthCoreServer.LOGGER.info(0, "This command can't be executed from console!");

      String username = player.getName().getString();
      User user = User.getUser(username, player.getUUID());

      if (user == null)
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserInvalidCredentials);

      String normalized = email.trim().toLowerCase(java.util.Locale.ROOT);
      boolean matches = user.email != null && user.email.equalsIgnoreCase(normalized);

      // Per-account attempt budget on top of the per-code cap (a fresh code every minute would
      // otherwise reset the guess budget indefinitely).
      if (!in.potenfyr.authcore.security.RateLimiter.tryAcquire(
          "sec:recoververify:" + user.uuid, 10, 60 * 60_000L))
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserCommandCooldown, "an hour");

      // Policy check BEFORE the code is verified: verifyCode() consumes the single-use code, so
      // a new password that then failed the length/complexity rules burned the code and the
      // player had to wait for another email. The policy is public, so checking it first
      // reveals nothing. (The history check stays behind the code: it would otherwise let
      // anyone in the limbo test candidate OLD passwords of the account.)
      if (StringUtils.isBlank(newPassword))
        return AuthCoreServer.LOGGER.toUser(
            0, player.connection, AuthCoreServer.messages.promptUserPasswordIsBlank);
      if (!in.potenfyr.authcore.security.Security.Password.check(player, newPassword)) return 0;

      if (!matches
          || !in.potenfyr.authcore.security.Security.EmailRecovery.verifyCode(user.uuid, normalized, code)) {
        in.potenfyr.authcore.security.SecurityLog.log(
            "RECOVERY_FAILED", username + " provided a wrong recovery code");
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserRecoveryInvalid);
      }

      if (StringUtils.isBlank(newPassword))
        return AuthCoreServer.LOGGER.toUser(
            0, player.connection, AuthCoreServer.messages.promptUserPasswordIsBlank);

      // Validate against the password policy (message shown to the player on failure)
      if (!in.potenfyr.authcore.security.Security.Password.check(player, newPassword)) return 0;

      // Same password-history rules as /account set-password: the recovery flow must not be a way
      // around history-size (reusing a previous password).
      if (user.isPasswordInHistory(newPassword))
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserDuplicatePassword);
      user.recordPasswordHistory();
      String previousHash = user.password;
      String previousAlgo = user.passwordEncryption;
      user.passwordEncryption = in.potenfyr.authcore.security.Encrypter.newCredentialAlgorithm(AuthCoreServer.config.passwordRules.passwordHashAlgorithm);
      user.password = in.potenfyr.authcore.security.Encrypter.hash(user.passwordEncryption, newPassword);
      if (user.password == null) {
        // Never leave the account without its credentials because hashing failed: the old code
        // nulled the algorithm and left password=null - i.e. silently UNREGISTERED the account.
        user.password = previousHash;
        user.passwordEncryption = previousAlgo;
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserPasswordIsBlank);
      }
      user.loginAttempts = 0;
      user.failedLoginStreak = 0;
      user.kickCooldownUntilMs = 0;
      if (user.lockUntilMs != 0) user.lockUntilMs = 0; // the email proved ownership
      user.authSecret = null; // 2FA secret must be re-set up after recovery
      user.recoveryCodes = null; // tied to the old 2FA setup
      user.sessionTokenHash = null; // any companion session token from before is void
      user.mfaVerified = false;
      in.potenfyr.authcore.security.Security.TOTPManager.clearReplayHistory(user.uuid);
      in.potenfyr.authcore.network.RedisManager.removeSession(user.uuid);
      user.update("Password recovered via email");

      in.potenfyr.authcore.security.SecurityLog.log(
          "RECOVERY_COMPLETED", username + " recovered their password via email");
      in.potenfyr.authcore.network.Webhook.sendEmbed(
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
  /**
   * Step-up gate for sensitive account changes, matching /account set-password: an account with a
   * password must have proven it on THIS connection (not a silent same-IP session resume), and an
   * account with 2FA must have satisfied the second factor this session when
   * {@code require-mfa-for-sensitive} is on. Premium accounts without a password are proven by
   * Mojang and pass the first check. Sends the denial message itself.
   */
  private static boolean sensitiveStepUp(User user, ServerPlayer player, String action) {
    if (user.password != null && !user.passwordProvenOn(player.connection)) {
      in.potenfyr.authcore.security.SecurityLog.log(
          "REAUTH_REQUIRED", user.username + " tried " + action + " in a resumed session");
      AuthCoreServer.LOGGER.toUser(
          1, player.connection, AuthCoreServer.messages.promptUserReauthRequired);
      return false;
    }
    boolean mfaConfigured =
        AuthCoreServer.config.session.authentication.allowTOTPSupport
            && user.authSecret != null
            && !user.authSecret.isBlank();
    if (AuthCoreServer.config.session.authentication.requireMfaForSensitive
        && mfaConfigured
        && !user.mfaVerified) {
      in.potenfyr.authcore.security.SecurityLog.log(
          "MFA_STEPUP_BLOCKED", user.username + " attempted " + action + " without a verified second factor");
      AuthCoreServer.LOGGER.toUser(
          1, player.connection, AuthCoreServer.messages.promptUserMissing2faCode);
      return false;
    }
    return true;
  }

  private static int recoveryCodesCommand(CommandSourceStack source) {
    try {
      ServerPlayer player = in.potenfyr.authcore.compat.Compat.sourcePlayer(source);
      if (player == null)
        return AuthCoreServer.LOGGER.info(0, "This command can't be executed from console!");

      UUID uuid = player.getUUID();
      String username = player.getName().getString();
      User user = User.getUser(username, uuid);

      if (user == null)
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserInvalidCredentials);

      // Recovery codes are a login second factor: printing them from a resumed session or
      // without the current second factor bypassed 2FA entirely.
      if (!sensitiveStepUp(user, player, "/account codes")) return 0;

      String codes = user.recoveryCodes;
      if (codes == null || codes.isBlank()) {
        codes = in.potenfyr.authcore.security.Security.RecoveryCodes.generate(8);
        user.recoveryCodes = codes;
        user.update("Generated recovery codes");
      }

      in.potenfyr.authcore.security.SecurityLog.log("RECOVERY_CODES_VIEWED", username);
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
      ServerPlayer player = in.potenfyr.authcore.compat.Compat.sourcePlayer(source);

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
      in.potenfyr.authcore.network.AuthInterop.broadcast(player, false);
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
      ServerPlayer player = in.potenfyr.authcore.compat.Compat.sourcePlayer(source);

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

      // Same step-up as the other account changes: a silently resumed session must not be able
      // to flip the authentication mode of the account.
      if (!sensitiveStepUp(user, player, "/account set-mode online")) return 0;

      in.potenfyr.authcore.security.SecurityLog.log(
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
      ServerPlayer player = in.potenfyr.authcore.compat.Compat.sourcePlayer(source);

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

      if (!sensitiveStepUp(user, player, "/account set-mode offline")) return 0;

      in.potenfyr.authcore.security.SecurityLog.log(
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
      ServerPlayer player = in.potenfyr.authcore.compat.Compat.sourcePlayer(source);

      if (player == null)
        return AuthCoreServer.LOGGER.info(0, "This command can't be executed from console!");

      AuthCoreServer.LOGGER.debug(
          1,
          "{} used '/account set-password <new-password>' command in the Server!",
          player.getName().getString());

      // The policy + reuse + history verification run several memory-hard hashes on the server
      // thread; throttle the command so an authenticated player cannot spam it into a stall.
      if (!in.potenfyr.authcore.security.RateLimiter.tryAcquire(
          "cmd:setpassword:" + player.getUUID(), 1, 5000L))
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserCommandCooldown, "a moment");

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
        in.potenfyr.authcore.security.SecurityLog.log(
            "MFA_STEPUP_BLOCKED", username + " attempted a sensitive action without a verified second factor");
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserMissing2faCode);
      }

      // Step-up: a resumed session must prove the password on THIS connection first.
      if (!user.passwordProvenOn(player.connection)) {
        in.potenfyr.authcore.security.SecurityLog.log(
            "REAUTH_REQUIRED", username + " tried /account set-password in a resumed session");
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserReauthRequired);
      }

      // Validate the RAW password, never a hash: Security.Password.check inspects the characters
      // the player actually typed. Hashing first (the old flow) ran the complexity policy against
      // the hash string, so almost any weak password passed.
      if (!Security.Password.check(player, password)) return 0;

      // Everything below runs memory-hard hashes: the reuse check, up to history-size history
      // verifications and the new hash - hundreds of ms of server-thread stall per command before.
      // They run on the IO pool; the swap is applied on the server thread, and only when the
      // stored hash is still the one the checks ran against (a concurrent change always wins).
      if (!user.passwordCheckInFlight.compareAndSet(false, true))
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserCommandCooldown, "a moment");
      final String currentHash = user.password;
      final String currentAlgo = user.passwordEncryption;
      final String newAlgo =
          in.potenfyr.authcore.security.Encrypter.newCredentialAlgorithm(
              AuthCoreServer.config.passwordRules.passwordHashAlgorithm);
      final net.minecraft.server.MinecraftServer server = AuthCoreServer.server;
      final ServerPlayer target = player;
      Runnable work =
          () -> {
            int verdict; // 0 = ok, 1 = reuse/history, 2 = hash failure
            String fresh = null;
            try {
              if (!AuthCoreServer.config.passwordRules.allowReuse
                  && currentHash != null
                  && Encrypter.verify(password, currentHash, currentAlgo)) verdict = 1;
              else if (user.isPasswordInHistory(password)) verdict = 1;
              else {
                fresh = Encrypter.hash(newAlgo, password);
                verdict = fresh == null ? 2 : 0;
              }
            } catch (Throwable err) {
              verdict = 2;
            }
            final int result = verdict;
            final String newHash = fresh;
            Runnable apply =
                () -> {
                  try {
                    if (result == 1) {
                      AuthCoreServer.LOGGER.toUser(
                          1, target.connection, AuthCoreServer.messages.promptUserDuplicatePassword);
                      return;
                    }
                    if (result == 2 || !java.util.Objects.equals(user.password, currentHash)) {
                      // hashing failed, or the password changed meanwhile - never half-apply
                      AuthCoreServer.LOGGER.toUser(
                          1, target.connection, AuthCoreServer.messages.promptUserPasswordIsBlank);
                      return;
                    }
                    // Keep the current password in the history before replacing it.
                    user.recordPasswordHistory();
                    user.passwordEncryption = newAlgo;
                    user.password = newHash;
                    user.update("Password Change");
                    in.potenfyr.authcore.security.AuthIntelligence.recordPasswordChange(
                        user, target.getIpAddress());
                    AuthCoreServer.LOGGER.debug(
                        1, "{} password has been updated in the database!", user.username);
                    AuthCoreServer.LOGGER.toUser(
                        1,
                        target.connection,
                        AuthCoreServer.messages.promptUserPasswordChangedSuccessfully);
                  } finally {
                    user.passwordCheckInFlight.set(false);
                  }
                };
            if (server != null) server.execute(apply);
            else apply.run();
          };
      try {
        if (server != null) AuthCoreServer.IO_EXECUTOR.execute(work);
        else work.run();
      } catch (java.util.concurrent.RejectedExecutionException busy) {
        user.passwordCheckInFlight.set(false);
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserCommandCooldown, "a moment");
      }
      return 1;
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
      ServerPlayer player = in.potenfyr.authcore.compat.Compat.sourcePlayer(source);

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

      // Deleting the account is the most destructive credential action: it needs the password
      // proven on THIS connection (a resumed session could otherwise delete the account and
      // register a password of its own) and the same MFA step-up as a password change.
      if (!user.passwordProvenOn(player.connection)) {
        in.potenfyr.authcore.security.SecurityLog.log(
            "REAUTH_REQUIRED", username + " tried /account unregister in a resumed session");
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserReauthRequired);
      }
      boolean unregisterMfa =
          AuthCoreServer.config.session.authentication.allowTOTPSupport
              && user.authSecret != null
              && !user.authSecret.isBlank();
      if (AuthCoreServer.config.session.authentication.requireMfaForSensitive
          && unregisterMfa
          && !user.mfaVerified) {
        in.potenfyr.authcore.security.SecurityLog.log(
            "MFA_STEPUP_BLOCKED", username + " attempted /account unregister without a verified second factor");
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserMissing2faCode);
      }

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
}
