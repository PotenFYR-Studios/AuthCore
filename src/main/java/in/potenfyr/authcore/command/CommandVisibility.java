package in.potenfyr.authcore.command;

import in.potenfyr.authcore.AuthCoreServer;
import in.potenfyr.authcore.models.Config;
import in.potenfyr.authcore.models.Messages;
import in.potenfyr.authcore.models.User;
import in.potenfyr.authcore.network.McApiManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/**
 * Single source of truth for WHEN an AuthCore command applies.
 *
 * <p>Each command has one <b>state rule</b> that returns {@code null} when the command makes sense
 * right now, or the message (from messages.conf) explaining why not - "already logged in",
 * "log in first", "feature not available" ... The rule is used twice:
 *
 * <ul>
 *   <li><b>visibility</b> ({@code can*}, the Brigadier {@code requires()}): a regular player is
 *       offered a command only while its rule passes. <b>Admins</b> (console, vanilla OP level 2+,
 *       or any AuthCore admin permission) always see every AuthCore command they hold the
 *       permission for;
 *   <li><b>execution</b> ({@link #guard}, wrapped around every AuthCore executor by {@link
 *       CommandTree#guardExecution}): whoever can see a command but runs it in the wrong state gets
 *       the rule's message instead of the command running.
 * </ul>
 *
 * <p>The tree is re-sent on every state change (limbo enter/leave, any login, Discord link change,
 * {@code /authcore reload}) by {@code Compat.refreshCommandTree}.
 */
public final class CommandVisibility {

  private CommandVisibility() {}

  /** Why a command does not apply right now. */
  public static final class Denial {
    final Messages.ColTemplate message;
    final Object[] args;

    Denial(Messages.ColTemplate message, Object... args) {
      this.message = message;
      this.args = args;
    }
  }

  /** A state rule: {@code null} = the command applies. */
  @FunctionalInterface
  public interface Rule {
    Denial check(ServerPlayer player, User user);
  }

  static ServerPlayer player(Object ctx) {
    return McApiManager.PermissionUtil.resolvePlayer(ctx);
  }

  static User user(ServerPlayer player) {
    if (player == null) return null;
    return User.getUser(player.getName().getString(), player.getUUID());
  }

  private static Messages msg() {
    return AuthCoreServer.messages;
  }

  private static boolean allowed(ServerPlayer player, Config.CommandPermissions perm) {
    return perm == null || McApiManager.PermissionUtil.has(player, perm.luckPermsNode, perm.permissionsLevel);
  }

  private static boolean inLimbo(User user) {
    return user != null && user.isActive && user.isInLobby.get() && !user.isAuthenticated.get();
  }

  private static boolean authenticated(User user) {
    return user != null && user.isActive && !user.isInLobby.get() && user.isAuthenticated.get();
  }

  private static Config.UserCommands perms() {
    return AuthCoreServer.config.commands.user;
  }

  // ------------------------------------------------------------------ admins

  /**
   * Admins see every AuthCore command they hold the permission for, in every state (the executor
   * guard explains why one does not apply): vanilla OP level 2+ or any AuthCore admin permission.
   */
  public static boolean isAdmin(Object ctx) {
    if (player(ctx) == null) return true; // console / command block / function
    if (McApiManager.PermissionUtil.hasForSource(ctx, "", 2)) return true;
    return holdsAnyAdminPermission(ctx);
  }

  private static boolean adminPerm(Object ctx, Config.CommandPermissions perm) {
    return perm != null && McApiManager.PermissionUtil.hasForSource(ctx, perm.luckPermsNode, perm.permissionsLevel);
  }

  private static boolean holdsAnyAdminPermission(Object ctx) {
    Config.AdminCommands admin = AuthCoreServer.config.commands.admin;
    for (java.lang.reflect.Field field : Config.AdminCommands.class.getFields()) {
      if (!Config.CommandPermissions.class.isAssignableFrom(field.getType())) continue;
      try {
        if (field.get(admin) instanceof Config.CommandPermissions perm && adminPerm(ctx, perm)) return true;
      } catch (IllegalAccessException ignored) {
        // public field - cannot happen
      }
    }
    return McApiManager.PermissionUtil.hasForSource(
        ctx, admin.simulate.luckPermsNode, admin.simulate.permissionsLevel);
  }

  // ------------------------------------------------------------------ rule plumbing

  /** Visibility: permission AND (rule passes OR the source is an admin). */
  private static boolean visible(Object ctx, Config.CommandPermissions perm, Rule rule) {
    ServerPlayer p = player(ctx);
    if (p == null) return false; // player-only commands
    User u = user(p);
    if (u == null || !allowed(p, perm)) return false;
    return rule.check(p, u) == null || isAdmin(ctx);
  }

  /**
   * Execution guard used by {@link CommandTree#guardExecution}: {@code null} = run the command,
   * otherwise the rule's message was sent and the returned value is the command result.
   */
  public static Integer guard(CommandSourceStack source, Rule rule) {
    ServerPlayer p = player(source);
    if (p == null) return consoleFeatureOk(rule) ? null : 0;
    User u = user(p);
    if (u == null) return null; // executors report a missing user themselves
    Denial denial = rule.check(p, u);
    if (denial == null) return null;
    AuthCoreServer.LOGGER.toUser(1, p.connection, denial.message, denial.args);
    return 0;
  }

  // ------------------------------------------------------------------ rules: before login

  /** /register: unregistered account in the login limbo. */
  public static final Rule REGISTER =
      (p, u) -> {
        if (authenticated(u)) return new Denial(msg().promptUserAlreadyLoggedIn);
        if (u.isRegistered.get()) return new Denial(msg().promptUserAlreadyRegisteredUseLogin);
        if (!inLimbo(u)) return new Denial(msg().promptUserAlreadyLoggedIn);
        return null;
      };

  /** /login: registered account in the login limbo. */
  public static final Rule LOGIN =
      (p, u) -> {
        if (authenticated(u)) return new Denial(msg().promptUserAlreadyLoggedIn);
        if (!u.isRegistered.get()) return new Denial(msg().promptUserNotRegistered);
        if (!inLimbo(u)) return new Denial(msg().promptUserAlreadyLoggedIn);
        return null;
      };

  /**
   * /account recover: registered account in the limbo, SMTP configured. Whether the account HAS an
   * email is deliberately not part of the rule (that would reveal it to anyone joining as the name).
   */
  public static final Rule RECOVER =
      (p, u) -> {
        if (authenticated(u)) return new Denial(msg().promptUserAlreadyLoggedIn);
        if (!u.isRegistered.get()) return new Denial(msg().promptUserNotRegistered);
        if (!in.potenfyr.authcore.network.EmailSender.isEnabled())
          return new Denial(msg().promptUserFeatureUnavailable);
        if (!inLimbo(u)) return new Denial(msg().promptUserAlreadyLoggedIn);
        return null;
      };

  // ------------------------------------------------------------------ rules: after login

  private static Denial needLogin(User u) {
    return authenticated(u) ? null : new Denial(msg().promptUserLoginRequired);
  }

  private static Denial needPassword(User u) {
    Denial d = needLogin(u);
    if (d != null) return d;
    return u.isRegistered.get() ? null : new Denial(msg().promptUserNoPasswordAccount);
  }

  /** /account logout, unregister, set-password: logged in, password account. */
  public static final Rule PASSWORD_ACCOUNT = (p, u) -> needPassword(u);

  /** /account nickname, admin commands run by a player: logged in. */
  public static final Rule LOGGED_IN = (p, u) -> needLogin(u);

  /** /account set-mode online: logged in, offline-mode account, premium auto-login enabled. */
  public static final Rule MODE_ONLINE =
      (p, u) -> {
        Denial d = needLogin(u);
        if (d != null) return d;
        if (u.isPremium) return new Denial(msg().promptUserModeAlreadyOnline);
        if (!AuthCoreServer.config.session.authentication.premiumAutoLogin)
          return new Denial(msg().promptUserFeatureUnavailable);
        return null;
      };

  /** /account set-mode offline: logged in, online-mode (premium) account. */
  public static final Rule MODE_OFFLINE =
      (p, u) -> {
        Denial d = needLogin(u);
        if (d != null) return d;
        if (!u.isPremium) return new Denial(msg().promptUserModeAlreadyOffline);
        return null;
      };

  /** /account codes: logged in, password account with TOTP set up (codes are a TOTP backup). */
  public static final Rule RECOVERY_CODES =
      (p, u) -> {
        Denial d = needPassword(u);
        if (d != null) return d;
        if (!AuthCoreServer.config.session.authentication.allowTOTPSupport
            || u.authSecret == null
            || u.authSecret.isBlank()) return new Denial(msg().promptUserFeatureUnavailable);
        return null;
      };

  /** /account email: logged in, password account, SMTP configured. */
  public static final Rule EMAIL =
      (p, u) -> {
        Denial d = needPassword(u);
        if (d != null) return d;
        if (!in.potenfyr.authcore.network.EmailSender.isEnabled())
          return new Denial(msg().promptUserFeatureUnavailable);
        return null;
      };

  // ------------------------------------------------------------------ rules: discord

  /** A link code can reach the Discord bot (Redis, or the security / role-sync webhook). */
  private static boolean discordAvailable() {
    var cfg = AuthCoreServer.config;
    if (cfg.database.redis.enabled) return true;
    var sec = cfg.session.security;
    return (sec.webhookUrl != null && !sec.webhookUrl.isBlank())
        || (sec.roleSyncWebhookUrl != null && !sec.roleSyncWebhookUrl.isBlank());
  }

  private static boolean linked(User u) {
    return u.discordId != null && !u.discordId.isEmpty();
  }

  public static final Rule DISCORD_LINK =
      (p, u) -> {
        Denial d = needLogin(u);
        if (d != null) return d;
        if (linked(u)) return new Denial(msg().promptUserDiscordAlreadyLinked);
        if (!discordAvailable()) return new Denial(msg().promptUserFeatureUnavailable);
        return null;
      };

  public static final Rule DISCORD_UNLINK =
      (p, u) -> {
        Denial d = needLogin(u);
        if (d != null) return d;
        if (!linked(u)) return new Denial(msg().promptUserDiscordNotLinked);
        return null;
      };

  // ------------------------------------------------------------------ requires() predicates

  public static boolean canRegister(Object ctx) {
    return visible(ctx, perms().register, REGISTER);
  }

  public static boolean canLogin(Object ctx) {
    return visible(ctx, perms().login, LOGIN);
  }

  public static boolean canRecover(Object ctx) {
    return visible(ctx, perms().logout, RECOVER);
  }

  public static boolean canLogout(Object ctx) {
    return visible(ctx, perms().logout, PASSWORD_ACCOUNT);
  }

  public static boolean canUnregister(Object ctx) {
    return visible(ctx, perms().unregister, PASSWORD_ACCOUNT);
  }

  public static boolean canChangePassword(Object ctx) {
    return visible(ctx, perms().changePassword, PASSWORD_ACCOUNT);
  }

  public static boolean canSetModeOnline(Object ctx) {
    return visible(ctx, perms().setMode, MODE_ONLINE);
  }

  public static boolean canSetModeOffline(Object ctx) {
    return visible(ctx, perms().setMode, MODE_OFFLINE);
  }

  public static boolean canViewRecoveryCodes(Object ctx) {
    return visible(ctx, perms().logout, RECOVERY_CODES);
  }

  public static boolean canSetEmail(Object ctx) {
    return visible(ctx, perms().logout, EMAIL);
  }

  public static boolean canSetNickname(Object ctx) {
    return visible(ctx, perms().logout, LOGGED_IN);
  }

  /** The /account root is offered only while at least one subcommand is. */
  public static boolean canUseAccount(Object ctx) {
    return canRecover(ctx)
        || canLogout(ctx)
        || canUnregister(ctx)
        || canChangePassword(ctx)
        || canSetModeOnline(ctx)
        || canSetModeOffline(ctx)
        || canViewRecoveryCodes(ctx)
        || canSetEmail(ctx)
        || canSetNickname(ctx);
  }

  public static boolean canDiscordLink(Object ctx) {
    return visible(ctx, perms().logout, DISCORD_LINK);
  }

  public static boolean canDiscordUnlink(Object ctx) {
    return visible(ctx, perms().logout, DISCORD_UNLINK);
  }

  public static boolean canUseDiscord(Object ctx) {
    return canDiscordLink(ctx) || canDiscordUnlink(ctx);
  }

  // ------------------------------------------------------------------ admin (/authcore)

  /**
   * /authcore root: visible to anyone holding ANY admin permission (it used to require the reload
   * permission, locking out staff with narrower nodes). Each subcommand keeps its own permission;
   * a player must be logged in to RUN any of them ({@link #LOGGED_IN} guard) - an admin name
   * joining the limbo keeps its LuckPerms nodes and must not act before proving the password.
   */
  public static boolean canUseAdmin(Object ctx) {
    return player(ctx) == null || holdsAnyAdminPermission(ctx);
  }

  /** /authcore map-image: reload-level permission (it had no check of its own). */
  public static boolean canMapImage(Object ctx) {
    return adminPerm(ctx, AuthCoreServer.config.commands.admin.reload);
  }

  public static final Rule MAP_IMAGE =
      (p, u) -> {
        Denial d = needLogin(u);
        if (d != null) return d;
        return AuthCoreServer.config.lobby.mapImage.enabled
            ? null
            : new Denial(msg().promptUserFeatureUnavailable);
      };

  /** /authcore network-ban|network-unban permission. */
  public static boolean canNetworkBan(Object ctx) {
    return adminPerm(ctx, AuthCoreServer.config.commands.admin.networkBan);
  }

  public static final Rule NETWORK_BAN =
      (p, u) -> {
        Denial d = needLogin(u);
        if (d != null) return d;
        return AuthCoreServer.config.database.redis.enabled
            ? null
            : new Denial(msg().promptUserFeatureUnavailable);
      };

  /** /authcore simulate permission (shown to its holders even while disabled). */
  public static boolean canSimulate(Object ctx) {
    var s = AuthCoreServer.config.commands.admin.simulate;
    return McApiManager.PermissionUtil.hasForSource(ctx, s.luckPermsNode, s.permissionsLevel);
  }

  public static final Rule SIMULATE =
      (p, u) -> {
        Denial d = needLogin(u);
        if (d != null) return d;
        return AuthCoreServer.config.commands.admin.simulate.enabled
            ? null
            : new Denial(msg().promptUserFeatureUnavailable);
      };

  /** Feature gates for console sources (no player to message - logged to the console instead). */
  private static boolean consoleFeatureOk(Rule rule) {
    String reason = null;
    if (rule == MAP_IMAGE && !AuthCoreServer.config.lobby.mapImage.enabled)
      reason = "Map images are disabled (lobby.map-image.enabled = false).";
    else if (rule == NETWORK_BAN && !AuthCoreServer.config.database.redis.enabled)
      reason = "Network bans need Redis (database.redis.enabled = true).";
    else if (rule == SIMULATE && !AuthCoreServer.config.commands.admin.simulate.enabled)
      reason = "/authcore simulate is disabled (commands.admin.simulate.enabled = false).";
    if (reason == null) return true;
    AuthCoreServer.LOGGER.info(0, reason);
    return false;
  }
}
