package net.ded3ec.command;

import net.ded3ec.models.Config;
import net.ded3ec.models.Lobby;
import net.ded3ec.security.Security;
import net.ded3ec.security.SecurityLog;
import net.ded3ec.util.Logger;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.UUID;
import java.util.stream.Collectors;
import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.Messages;
import net.ded3ec.models.User;
import net.ded3ec.util.Database;
import net.ded3ec.security.Encrypter;
import net.ded3ec.util.TimeManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.server.level.ServerPlayer;
import net.ded3ec.util.HoconConf;
import net.ded3ec.network.McApiManager;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;


/**
 * Registers and handles all administrative commands for the AuthCoreServer mod.
 *
 * <p>This class provides a comprehensive set of server-side administration tools for managing
 * authentication-related data, player sessions, modes, passwords, and limbo spawn locations. All
 * commands are registered under the root literal {@code /authcore} and are protected by
 * configurable LuckPerms nodes and operator permission levels.
 */
public class Admin {

  /**
   * Registers all AuthCoreServer administrative commands with the server's command dispatcher.
   *
   * <p>The command tree is structured as {@code /authcore <subcommand> [arguments]} and includes
   * functionality for reloading configuration, listing players, managing player data, sessions,
   * passwords, authentication modes, and setting the limbo spawn location.
   *
   * @param dispatcher the Brigadier command dispatcher provided by the Minecraft server
   */
  public static void load(CommandDispatcher<CommandSourceStack> dispatcher) {
    dispatcher.register(
        literal("authcore")
            .requires(
                ctx ->
                    McApiManager.PermissionUtil.hasForSource(                        ctx,
                        AuthCoreServer.config.commands.admin.reload.luckPermsNode,
                        AuthCoreServer.config.commands.admin.reload.permissionsLevel))
            .then(
                literal("reload")
                    .requires(
                        ctx ->
                            McApiManager.PermissionUtil.hasForSource(                                ctx,
                                AuthCoreServer.config.commands.admin.reload.luckPermsNode,
                                AuthCoreServer.config.commands.admin.reload.permissionsLevel))
                    .executes(ctx -> reloadCommand(ctx.getSource())))
            .then(
                literal("list")
                    .then(
                        literal("players")
                            .requires(
                                ctx ->
                                    McApiManager.PermissionUtil.hasForSource(                                        ctx,
                                        AuthCoreServer.config
                                            .commands
                                            .admin
                                            .listPlayers
                                            .luckPermsNode,
                                        AuthCoreServer.config
                                            .commands
                                            .admin
                                            .listPlayers
                                            .permissionsLevel))
                            .executes(ctx -> listPlayersCommand(ctx.getSource())))
                    .then(
                        literal("online-players")
                            .requires(
                                ctx ->
                                    McApiManager.PermissionUtil.hasForSource(                                        ctx,
                                        AuthCoreServer.config
                                            .commands
                                            .admin
                                            .listOnlineModePlayers
                                            .luckPermsNode,
                                        AuthCoreServer.config
                                            .commands
                                            .admin
                                            .listOnlineModePlayers
                                            .permissionsLevel))
                            .executes(ctx -> listOnlineModePlayersCommand(ctx.getSource())))
                    .then(
                        literal("offline-players")
                            .requires(
                                ctx ->
                                    McApiManager.PermissionUtil.hasForSource(                                        ctx,
                                        AuthCoreServer.config
                                            .commands
                                            .admin
                                            .listOfflineModePlayers
                                            .luckPermsNode,
                                        AuthCoreServer.config
                                            .commands
                                            .admin
                                            .listOfflineModePlayers
                                            .permissionsLevel))
                            .executes(ctx -> listOfflineModePlayersCommand(ctx.getSource()))))
            .then(
                literal("delete")
                    .then(
                        literal("player")
                            .then(
                                argument("player", EntityArgument.player())
                                    .requires(
                                        ctx ->
                                            McApiManager.PermissionUtil.hasForSource(                                                ctx,
                                                AuthCoreServer.config
                                                    .commands
                                                    .admin
                                                    .deletePlayer
                                                    .luckPermsNode,
                                                AuthCoreServer.config
                                                    .commands
                                                    .admin
                                                    .deletePlayer
                                                    .permissionsLevel))
                                    .executes(
                                        ctx ->
                                            deletePlayerCommand(
                                                ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "player"))))))
            .then(
                literal("destroy-session")
                    .then(
                        argument("player", EntityArgument.player())
                            .requires(
                                ctx ->
                                    McApiManager.PermissionUtil.hasForSource(                                        ctx,
                                        AuthCoreServer.config
                                            .commands
                                            .admin
                                            .destroyPlayerSession
                                            .luckPermsNode,
                                        AuthCoreServer.config
                                            .commands
                                            .admin
                                            .destroyPlayerSession
                                            .permissionsLevel))
                            .executes(
                                ctx ->
                                    destroyUserSessionCommand(
                                        ctx.getSource(),
                                        EntityArgument.getPlayer(ctx, "player")))))
            .then(
                literal("set-password")
                    .then(
                        argument("player", EntityArgument.player())
                            .then(
                                argument("new-password", StringArgumentType.string())
                                    .requires(
                                        ctx ->
                                            McApiManager.PermissionUtil.hasForSource(                                                ctx,
                                                AuthCoreServer.config
                                                    .commands
                                                    .admin
                                                    .setPlayerPassword
                                                    .luckPermsNode,
                                                AuthCoreServer.config
                                                    .commands
                                                    .admin
                                                    .setPlayerPassword
                                                    .permissionsLevel))
                                    .executes(
                                        ctx ->
                                            setPlayerNewPasswordCommand(
                                                ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "player"),
                                                StringArgumentType.getString(
                                                    ctx, "new-password"))))))
            .then(
                literal("whois")
                    .then(
                        argument("username", StringArgumentType.string())
                            .requires(
                                ctx ->
                                    McApiManager.PermissionUtil.hasForSource(                                        ctx,
                                        AuthCoreServer.config
                                            .commands
                                            .admin
                                            .whoisUsername
                                            .luckPermsNode,
                                        AuthCoreServer.config
                                            .commands
                                            .admin
                                            .whoisUsername
                                            .permissionsLevel))
                            .executes(
                                ctx ->
                                    WhoIsUserByUsernameCommand(
                                        ctx.getSource(),
                                        null,
                                        StringArgumentType.getString(ctx, "username"),
                                        null)))
                    .then(
                        argument("uuid", UuidArgument.uuid())
                            .requires(
                                ctx ->
                                    McApiManager.PermissionUtil.hasForSource(                                        ctx,
                                        AuthCoreServer.config
                                            .commands
                                            .admin
                                            .whoisUsername
                                            .luckPermsNode,
                                        AuthCoreServer.config
                                            .commands
                                            .admin
                                            .whoisUsername
                                            .permissionsLevel))
                            .executes(
                                ctx ->
                                    WhoIsUserByUsernameCommand(
                                        ctx.getSource(),
                                        null,
                                        null,
                                        UuidArgument.getUuid(ctx, "uuid"))))
                    .then(
                        argument("player", EntityArgument.player())
                            .requires(
                                ctx ->
                                    McApiManager.PermissionUtil.hasForSource(                                        ctx,
                                        AuthCoreServer.config
                                            .commands
                                            .admin
                                            .whoisUsername
                                            .luckPermsNode,
                                        AuthCoreServer.config
                                            .commands
                                            .admin
                                            .whoisUsername
                                            .permissionsLevel))
                            .executes(
                                ctx ->
                                    WhoIsUserByUsernameCommand(
                                        ctx.getSource(),
                                        EntityArgument.getPlayer(ctx, "player"),
                                        null,
                                        null))))
            .then(
                literal("set-mode")
                    .then(
                        literal("online")
                            .then(
                                argument("player", EntityArgument.player())
                                    .requires(
                                        ctx ->
                                            McApiManager.PermissionUtil.hasForSource(                                                ctx,
                                                AuthCoreServer.config
                                                    .commands
                                                    .admin
                                                    .setOnlineModePlayer
                                                    .luckPermsNode,
                                                AuthCoreServer.config
                                                    .commands
                                                    .admin
                                                    .setOnlineModePlayer
                                                    .permissionsLevel))
                                    .executes(
                                        ctx ->
                                            setOnlineModePlayerCommand(
                                                ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "player")))))
                    .then(
                        literal("offline")
                            .then(
                                argument("player", EntityArgument.player())
                                    .requires(
                                        ctx ->
                                            McApiManager.PermissionUtil.hasForSource(                                                        ctx,
                                                AuthCoreServer.config
                                                    .commands
                                                    .admin
                                                    .setOfflineModePlayer
                                                    .luckPermsNode,
                                                AuthCoreServer.config
                                                    .commands
                                                    .admin
                                                    .setOfflineModePlayer
                                                    .permissionsLevel))
                                    .executes(
                                        ctx ->
                                            setOfflineModePlayerCommand(
                                                ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "player"))))))
            .then(
                literal("set-spawn")
                    .then(
                        literal("limbo")
                            .then(
                                argument("x-cord", DoubleArgumentType.doubleArg())
                                    .then(
                                        argument("y-cord", DoubleArgumentType.doubleArg())
                                            .then(
                                                argument("z-cord", DoubleArgumentType.doubleArg())
                                                    .requires(
                                                        ctx ->
                                                            McApiManager.PermissionUtil.hasForSource(                                                                ctx,
                                                                AuthCoreServer.config
                                                                    .commands
                                                                    .admin
                                                                    .setSpawnLocation
                                                                    .luckPermsNode,
                                                                AuthCoreServer.config
                                                                    .commands
                                                                    .admin
                                                                    .setSpawnLocation
                                                                    .permissionsLevel))
                                                     .executes(
                                                         ctx ->
                                                             SetLimboSpawnLocationCommand(
                                                                 ctx.getSource(),
                                                                 DoubleArgumentType.getDouble(
                                                                     ctx, "x-cord"),
                                                                 DoubleArgumentType.getDouble(
                                                                      ctx, "y-cord"),
                                                                 DoubleArgumentType.getDouble(
                                                                      ctx, "z-cord"))))))))
            .then(
                literal("backup")
                    .requires(
                        ctx ->
                            McApiManager.PermissionUtil.hasForSource(
                                ctx,
                                AuthCoreServer.config.commands.admin.reload.luckPermsNode,
                                AuthCoreServer.config.commands.admin.reload.permissionsLevel))
                    .executes(ctx -> backupDatabaseCommand(ctx.getSource())))
            .then(
                literal("export")
                    .requires(
                        ctx ->
                            McApiManager.PermissionUtil.hasForSource(
                                ctx,
                                AuthCoreServer.config.commands.admin.reload.luckPermsNode,
                                AuthCoreServer.config.commands.admin.reload.permissionsLevel))
                    .executes(ctx -> exportUsersCommand(ctx.getSource())))
            .then(
                literal("resetpw")
                    .then(
                        argument("player", EntityArgument.player())
                            .then(
                                argument("new-password", StringArgumentType.string())
                                    .requires(
                                        ctx ->
                                            McApiManager.PermissionUtil.hasForSource(
                                                ctx,
                                                AuthCoreServer.config.commands.admin.setPlayerPassword.luckPermsNode,
                                                AuthCoreServer.config.commands.admin.setPlayerPassword.permissionsLevel))
                                    .executes(
                                        ctx ->
                                            setPlayerNewPasswordCommand(
                                                ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "player"),
                                                StringArgumentType.getString(
                                                    ctx, "new-password"))))))
            .then(
                literal("maintenance")
                    .requires(
                        ctx ->
                            McApiManager.PermissionUtil.hasForSource(
                                ctx,
                                AuthCoreServer.config.commands.admin.reload.luckPermsNode,
                                AuthCoreServer.config.commands.admin.reload.permissionsLevel))
                    .then(
                        literal("on")
                            .executes(ctx -> maintenanceCommand(ctx.getSource(), true)))
                    .then(
                        literal("off")
                            .executes(ctx -> maintenanceCommand(ctx.getSource(), false))))
            .then(
                literal("validate")
                    .requires(
                        ctx ->
                            McApiManager.PermissionUtil.hasForSource(
                                ctx,
                                AuthCoreServer.config.commands.admin.reload.luckPermsNode,
                                AuthCoreServer.config.commands.admin.reload.permissionsLevel))
                    .executes(ctx -> validateConfigCommand(ctx.getSource())))
            .then(
                literal("compat")
                    .requires(
                        ctx ->
                            McApiManager.PermissionUtil.hasForSource(
                                ctx,
                                AuthCoreServer.config.commands.admin.reload.luckPermsNode,
                                AuthCoreServer.config.commands.admin.reload.permissionsLevel))
                    .executes(ctx -> compatReportCommand(ctx.getSource())))
            .then(
                literal("history")
                    .then(
                        argument("player", EntityArgument.player())
                            .requires(
                                ctx ->
                                    McApiManager.PermissionUtil.hasForSource(
                                        ctx,
                                        AuthCoreServer.config.commands.admin.whoisUsername.luckPermsNode,
                                        AuthCoreServer.config.commands.admin.whoisUsername.permissionsLevel))
                             .executes(
                                 ctx ->
                                     loginHistoryCommand(
                                         ctx.getSource(),
                                         EntityArgument.getPlayer(ctx, "player")))))
            .then(
                literal("import")
                    .requires(
                        ctx ->
                            McApiManager.PermissionUtil.hasForSource(
                                ctx,
                                AuthCoreServer.config.commands.admin.reload.luckPermsNode,
                                AuthCoreServer.config.commands.admin.reload.permissionsLevel))
                    .then(
                        literal("authme")
                            .then(
                                argument("database-file", StringArgumentType.string())
                                    .executes(
                                        ctx ->
                                            importAuthMeCommand(
                                                ctx.getSource(),
                                                StringArgumentType.getString(
                                                    ctx, "database-file")))))));
  }

  /**
   * Creates a backup copy of the local SQLite database (MySQL/PostgreSQL users get a console
   * notice instead - use the DB tooling of the respective server).
   *
   * @param source the command source (player or console)
   * @return 1 on success, 0 on failure
   */
  private static int backupDatabaseCommand(CommandSourceStack source) {
    try {
      ServerPlayer player = net.ded3ec.compat.Compat.sourcePlayer(source);

      if (player != null)
        AuthCoreServer.LOGGER.debug(
            1, "{} used '/authcore backup' command in the Server!", player.getName().getString());

      if (Database.dialect != Database.Dialect.SQLITE) {
        String message =
            "Database backup is only supported for SQLite. Use your "
                + Database.dialect
                + " server's own backup tooling.";
        return sendPlainMessage(source, player, message);
      }

      java.nio.file.Path dbFile =
          AuthCoreServer.configPath.resolve("database").resolve(AuthCoreServer.config.database.sqlite);
      java.nio.file.Path backupDir = AuthCoreServer.configPath.resolve("backups");
      java.nio.file.Files.createDirectories(backupDir);

      String stamp =
          new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new java.util.Date());
      java.nio.file.Path backup = backupDir.resolve("authcore-" + stamp + ".db");

      java.nio.file.Files.copy(dbFile, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

      net.ded3ec.security.SecurityLog.log("DB_BACKUP", "Created " + backup);

      if (player != null)
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptAdminBackupDone, backup);
      return AuthCoreServer.LOGGER.info(1, "Database backup created: {}", backup);
    } catch (Exception err) {
      return AuthCoreServer.LOGGER.error(
          0, "Faced Error in '/authcore backup' Command: ", err);
    }
  }

  /**
   * Shows the recent login history for a player.
   *
   * @param source the command source
   * @param target the target player
   * @return 1 on success, 0 on failure
   */
  private static int loginHistoryCommand(CommandSourceStack source, ServerPlayer target) {
    try {
      ServerPlayer player = net.ded3ec.compat.Compat.sourcePlayer(source);

      UUID uuid = target.getUUID();
      User user = User.getUser(target.getName().getString(), uuid);

      if (user == null) {
        String message = "User '" + target.getName().getString() + "' is not in the AuthCore cache.";
        return sendPlainMessage(source, player, message);
      }

      java.util.ArrayList<String> history = User.fetchLoginHistory(uuid, 10);
      String detail =
          history.isEmpty()
              ? "No login history recorded yet."
              : String.join("\n", history);

      if (player != null)
        return AuthCoreServer.LOGGER.toUser(
            1,
            player.connection,
            AuthCoreServer.messages.promptAdminLoginHistory,
            user.username,
            detail);
      return AuthCoreServer.LOGGER.info(
          1, "Login history for '{}':\n{}", user.username, detail);
    } catch (Exception err) {
      return AuthCoreServer.LOGGER.error(
          0, "Faced Error in '/authcore history' Command: ", err);
    }
  }

  /**
   * Exports all user accounts to a JSON file in the backups directory (works with every
   * database backend).
   *
   * @param source the command source (player or console)
   * @return 1 on success, 0 on failure
   */
  private static int exportUsersCommand(CommandSourceStack source) {
    try {
      ServerPlayer player = net.ded3ec.compat.Compat.sourcePlayer(source);

      if (player != null)
        AuthCoreServer.LOGGER.debug(
            1, "{} used '/authcore export' command in the Server!", player.getName().getString());

      com.google.gson.JsonArray list = new com.google.gson.JsonArray();
      for (User u : User.users.values()) {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("uuid", u.uuid.toString());
        o.addProperty("username", u.username);
        o.addProperty("email", u.email);
        o.addProperty("nickname", u.nickname);
        o.addProperty("mode", u.isPremium ? "online-mode" : "offline-mode");
        o.addProperty("ip", u.ipAddress);
        o.addProperty("country", u.country.get());
        o.addProperty("registered", u.isRegistered.get());
        o.addProperty("registeredAtMs", u.registeredAtMs);
        o.addProperty("userCreatedMs", u.userCreatedMs);
        o.addProperty("risk", u.riskScore);
        o.addProperty("locked", u.isLocked());
        list.add(o);
      }

      java.nio.file.Path backupDir = AuthCoreServer.configPath.resolve("backups");
      java.nio.file.Files.createDirectories(backupDir);
      String stamp =
          new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new java.util.Date());
      java.nio.file.Path out = backupDir.resolve("users-export-" + stamp + ".json");
      java.nio.file.Files.writeString(
          out, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(list));

      net.ded3ec.security.SecurityLog.log("DB_EXPORT", "Exported " + list.size() + " users to " + out);

      if (player != null)
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptAdminBackupDone, out);
      return AuthCoreServer.LOGGER.info(
          1, "Exported {} user(s) to {}", list.size(), out);
    } catch (Exception err) {
      return AuthCoreServer.LOGGER.error(
          0, "Faced Error in '/authcore export' Command: ", err);
    }
  }

  /**
   * Toggles maintenance mode (blocks all joins with a custom message).
   *
   * @param source the command source
   * @param enabled whether maintenance should be on or off
   * @return 1 on success, 0 on failure
   */
  private static int maintenanceCommand(CommandSourceStack source, boolean enabled) {
    try {
      AuthCoreServer.config.session.maintenance.enabled = enabled;
      net.ded3ec.util.HoconConf.saveConfig();

      String msgText = enabled ? "Maintenance mode ENABLED - joins are blocked." : "Maintenance mode disabled.";
      ServerPlayer player = net.ded3ec.compat.Compat.sourcePlayer(source);
      if (player != null)
        return AuthCoreServer.LOGGER.toUser(1, player.connection, new Messages.ColTemplate() {
          {
            message.text = msgText;
            message.color = enabled ? "YELLOW" : "GREEN";
          }
        });
      return AuthCoreServer.LOGGER.info(1, msgText);
    } catch (Exception err) {
      return AuthCoreServer.LOGGER.error(0, "Faced Error in '/authcore maintenance' Command: ", err);
    }
  }

  /**
   * Validates the current configuration and reports problems (ports, algorithms, database,
   * webhooks).
   *
   * @param source the command source
   * @return 1 on success, 0 on failure
   */
  private static int validateConfigCommand(CommandSourceStack source) {
    try {
      StringBuilder report = new StringBuilder("AuthCore configuration validation:\n");
      int issues = 0;

      // Language
      String lang = AuthCoreServer.config.language;
      java.util.Set<String> known =
          java.util.Set.of("en", "zh", "es", "de", "fr", "pt", "ru");
      if (lang == null || lang.isBlank()) { report.append("  [WARN] language is empty (falls back to en)\n"); issues++; }
      else if (!known.contains(lang.toLowerCase())) report.append("  [INFO] language '" + lang + "' is custom (messages-" + lang + ".conf expected)\n");

      // Password hashing
      String algo = AuthCoreServer.config.passwordRules.passwordHashAlgorithm;
      java.util.Set<String> algos = java.util.Set.of("argon2", "bcrypt", "scrypt", "pbkdf2", "sha-256", "sha-512");
      if (!algos.contains(algo == null ? "" : algo.toLowerCase())) {
        report.append("  [ERROR] password-hash-algorithm '" + algo + "' is invalid\n");
        issues++;
      }

      // Ports
      int panelPort = AuthCoreServer.config.session.webPanel.port;
      if (panelPort <= 0 || panelPort > 65535) { report.append("  [ERROR] web-panel.port out of range\n"); issues++; }
      if (AuthCoreServer.config.session.honeypot.enabled
          && (AuthCoreServer.config.session.honeypot.port <= 0 || AuthCoreServer.config.session.honeypot.port > 65535)) {
        report.append("  [ERROR] honeypot.port out of range\n");
        issues++;
      }

      // Database
      if (AuthCoreServer.config.database.mysql.enabled
          && (AuthCoreServer.config.database.mysql.host.isBlank()
              || AuthCoreServer.config.database.mysql.database.isBlank())) {
        report.append("  [ERROR] MySQL enabled but host/database missing\n");
        issues++;
      }
      if (AuthCoreServer.config.database.postgres.enabled
          && (AuthCoreServer.config.database.postgres.host.isBlank()
              || AuthCoreServer.config.database.postgres.database.isBlank())) {
        report.append("  [ERROR] PostgreSQL enabled but host/database missing\n");
        issues++;
      }

      // Web panel
      if (AuthCoreServer.config.session.webPanel.enabled) {
        String token = AuthCoreServer.config.session.webPanel.token;
        String tokenFile = AuthCoreServer.config.session.webPanel.tokenFile;
        if ((token == null || token.isBlank()) && (tokenFile == null || tokenFile.isBlank()))
          report.append("  [ERROR] web panel enabled but no token/token-file set - panel will NOT start\n");
      }

      // Webhook
      if (net.ded3ec.network.Webhook.isEnabled()) {
        String url = AuthCoreServer.config.session.security.webhookUrl;
        if (!url.startsWith("https://") && !url.startsWith("http://"))
          report.append("  [WARN] webhook-url does not look like a URL\n");
      }

      // Server mode is always taken from server.properties automatically - there is no
      // config override to validate.
      if (AuthCoreServer.isServerOnline())
        report.append("  [INFO] server-mode: online (detected from server.properties)\n");
      else
        report.append("  [INFO] server-mode: offline (detected from server.properties)\n");

      report.append("Validation finished with " + issues + " issue(s).");
      final String finalReport = report.toString();
      final boolean hasIssues = issues > 0;

      ServerPlayer player = net.ded3ec.compat.Compat.sourcePlayer(source);
      if (player != null)
        return AuthCoreServer.LOGGER.toUser(1, player.connection, new Messages.ColTemplate() {
          {
            message.text = finalReport;
            message.color = hasIssues ? "RED" : "GREEN";
          }
        });
      return AuthCoreServer.LOGGER.info(1, finalReport);
    } catch (Exception err) {
      return AuthCoreServer.LOGGER.error(0, "Faced Error in '/authcore validate' Command: ", err);
    }
  }

  /**
   * Compatibility report: loader, version groups and the state of every optional integration
   * (DiscordSRV, InteractiveChat) plus the loader-specific hooks in use.
   */
  private static int compatReportCommand(CommandSourceStack source) {
    try {
      StringBuilder report = new StringBuilder();
      report.append("AuthCore compatibility report:\n");
      report.append("  Minecraft     : ").append(net.ded3ec.compat.Compat.getGameVersion()).append('\n');
      report.append("  Loader        : ").append(net.ded3ec.compat.Compat.getLoaderVersion()).append('\n');
      report.append("  Config Ver    : ").append(AuthCoreServer.config != null ? AuthCoreServer.config.version : "?").append('\n');
      report.append("  Server Mode   : ").append(AuthCoreServer.isServerOnline() ? "online" : "offline (detected)").append('\n');

      String integrations = net.ded3ec.integration.ModIntegrations.status();
      for (String line : integrations.split("\n"))
        if (!line.isBlank()) report.append("  ").append(line).append('\n');

      report.append("  Hooks         : ").append("commands ").append(net.ded3ec.util.FabricHooks.class.getSimpleName()).append(" / mixins / fabric-api reflectively\n");
      report.append("  Compat note   : restrictions apply ONLY to lobby players - authenticated players and other mods are unaffected.\n");

      ServerPlayer player = net.ded3ec.compat.Compat.sourcePlayer(source);
      if (player != null)
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, new Messages.ColTemplate() {
              {
                message.text = report.toString();
                message.color = "AQUA";
              }
            });
      return AuthCoreServer.LOGGER.info(1, report.toString());
    } catch (Exception err) {
      return AuthCoreServer.LOGGER.error(0, "Faced Error in '/authcore compat' Command: ", err);
    }
  }

  /**
   * Imports accounts from an AuthMe-style SQLite database ({@code /authcore import authme <file>}).
   * Existing accounts are never overwritten; weak legacy hashes are re-hashed to the configured
   * algorithm automatically on each account's next successful login.
   *
   * @param source the command source (player or console)
   * @param dbFilePath path to the AuthMe SQLite database file
   * @return 1 on success, 0 on failure
   */
  private static int importAuthMeCommand(CommandSourceStack source, String dbFilePath) {
    try {
      ServerPlayer player = net.ded3ec.compat.Compat.sourcePlayer(source);

      if (dbFilePath == null || dbFilePath.isBlank()) {
        String usage = "Usage: /authcore import authme <path-to-authme.db>";
        return player != null
            ? AuthCoreServer.LOGGER.toUser(
                0, player.connection, new Messages.ColTemplate() {
                  {
                    message.text = usage;
                    message.color = "RED";
                  }
                })
            : AuthCoreServer.LOGGER.info(0, usage);
      }

      java.nio.file.Path path = java.nio.file.Path.of(dbFilePath);
      if (!path.isAbsolute())
        path = AuthCoreServer.configPath.getParent().resolve(dbFilePath);

      net.ded3ec.util.AuthMeImporter.ImportResult result =
          net.ded3ec.util.AuthMeImporter.importSqlite(path);

      String report;
      if (result.error != null) report = result.error;
      else
        report =
            result
                + " - weak legacy hashes are upgraded automatically on each account's next login.";

      SecurityLog.log("IMPORT_AUTHME", report);
      if (player != null)
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, new Messages.ColTemplate() {
              {
                message.text = report;
                message.color = result.error != null ? "RED" : "GREEN";
              }
            });
      return AuthCoreServer.LOGGER.info(result.error != null ? 0 : 1, report);
    } catch (Exception err) {
      return AuthCoreServer.LOGGER.error(0, "Faced Error in '/authcore import' Command: ", err);
    }
  }

  /** Sends a plain red chat message to a player, or logs it to the console. */
  private static int sendPlainMessage(CommandSourceStack source, ServerPlayer player, String text) {    Messages.ColTemplate template = new Messages.ColTemplate();
    template.message.text = text;
    template.message.color = "RED";

    if (player != null) return AuthCoreServer.LOGGER.toUser(0, player.connection, template);
    return AuthCoreServer.LOGGER.info(0, text);
  }

  /**
   * Handles the {@code /authcore reload} command.
   *
   * <p>Reloads all configuration files for the AuthCoreServer mod and notifies the command sender
   * (player or console) of the result.
   *
   * @param source the command source (player or console)
   * @return 1 on success, 0 on failure
   */
  private static int reloadCommand(CommandSourceStack source) {
    try {
      ServerPlayer player = net.ded3ec.compat.Compat.sourcePlayer(source);

      if (player != null)
        AuthCoreServer.LOGGER.debug(
            1, "{} used '/authcore reload' command in the Server!", player.getName().getString());

      HoconConf.initialize();

      if (player != null)
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptAdminReloadedConfiguration);
      else
        return AuthCoreServer.LOGGER.info(
            1, "AuthCoreServer configuration files has been reloaded successfully!");

    } catch (Exception err) {
      return AuthCoreServer.LOGGER.error(0, "Faced Error in '/authcore reload' Command: ", err);
    }
  }

  /**
   * Handles the {@code /authcore delete player <player>} command.
   *
   * <p>Permanently removes a player's authentication data from the database and kicks them if
   * online.
   *
   * @param source the command source
   * @param player the target player entity
   * @return 1 on success, 0 on failure or invalid arguments
   */
  private static int deletePlayerCommand(CommandSourceStack source, ServerPlayer player) {
    try {
      ServerPlayer sourcePlayer = net.ded3ec.compat.Compat.sourcePlayer(source);

      if (sourcePlayer != null)
        AuthCoreServer.LOGGER.debug(
            1,
            "{} used '/authcore delete player <player>' command in the Server!",
            sourcePlayer.getName().getString());

      if (player == null && sourcePlayer != null)
        return AuthCoreServer.LOGGER.toUser(
            0,
            sourcePlayer.connection,
            AuthCoreServer.messages.promptMissingParameter,
            "player");
      else if (player == null)
        return AuthCoreServer.LOGGER.info(
            0, "You are missing 'player' parameter in '/authcore delete player <player>' command!");

      UUID uuid = player.getUUID();
      String username = player.getName().getString();
      User user = User.getUser(username, uuid);

      if (user == null && sourcePlayer != null)
        return AuthCoreServer.LOGGER.toUser(
            0, sourcePlayer.connection, AuthCoreServer.messages.promptUserNotFoundData);
      else       if (user == null)
        return AuthCoreServer.LOGGER.info(
            0, "User '{}' not Found in the database!", player.getName().getString());

      user.kick(AuthCoreServer.messages.promptUserDataDeleted, "Server");
      user.delete("Deleted User Data By an Administrator!", true);

      if (sourcePlayer != null)
        return AuthCoreServer.LOGGER.toUser(
            0,
            sourcePlayer.connection,
            AuthCoreServer.messages.promptAdminUserDataDeleted,
            player.getName().getString());
      else
        return AuthCoreServer.LOGGER.info(
            0,
            "User '{}' has been deleted from the database & server!",
            player.getName().getString());

    } catch (Exception err) {
      return AuthCoreServer.LOGGER.error(
          0, "Faced Error in '/authcore delete player <player>' Command: ", err);
    }
  }

  /**
   * Handles the {@code /authcore list players} command.
   *
   * <p>Lists all registered player usernames stored in the AuthCoreServer database.
   *
   * @param source the command source
   * @return 1 on success, 0 on failure
   */
  private static int listPlayersCommand(CommandSourceStack source) {
    try {
      ServerPlayer player = net.ded3ec.compat.Compat.sourcePlayer(source);

      if (player != null)
        AuthCoreServer.LOGGER.debug(
            1,
            "{} used '/authcore list players' command in the Server!",
            player.getName().getString());

      String usersList =
          User.users.keySet().stream().map(UUID::toString).collect(Collectors.joining("\n "));

      if (player != null)
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptAdminListOfPlayers, "Players");
      else return AuthCoreServer.LOGGER.info(1, "List of Players in Authcore: " + usersList);

    } catch (Exception err) {
      return AuthCoreServer.LOGGER.error(
          0, "Faced Error in '/authcore list players' Command: ", err);
    }
  }

  /**
   * Handles the {@code /authcore list online-players} command.
   *
   * <p>Lists all players currently registered as online-mode in the database.
   *
   * @param source the command source
   * @return 1 on success, 0 on failure
   */
  private static int listOnlineModePlayersCommand(CommandSourceStack source) {
    try {
      ServerPlayer player = net.ded3ec.compat.Compat.sourcePlayer(source);

      if (player != null)
        AuthCoreServer.LOGGER.debug(
            1,
            "{} used '/authcore list online-players' command in the Server!",
            player.getName().getString());

      String usernames =
          User.users.values().stream()
              .filter(user -> user.isPremium)
              .map(user -> user.username)
              .collect(Collectors.joining("\n "));

      if (player != null)
        return AuthCoreServer.LOGGER.toUser(
            1,
            player.connection,
            AuthCoreServer.messages.promptAdminListOfPlayers,
            "Online-Players");
      else return AuthCoreServer.LOGGER.info(1, "List of Online-Players in Authcore: ", usernames);

    } catch (Exception err) {
      return AuthCoreServer.LOGGER.error(
          0, "Faced Error in '/authcore list online-players' Command: ", err);
    }
  }

  /**
   * Handles the {@code /authcore list offline-players} command.
   *
   * <p>Lists all players currently registered as offline-mode in the database.
   *
   * @param source the command source
   * @return 1 on success, 0 on failure
   */
  private static int listOfflineModePlayersCommand(CommandSourceStack source) {
    try {
      ServerPlayer player = net.ded3ec.compat.Compat.sourcePlayer(source);

      if (player != null)
        AuthCoreServer.LOGGER.debug(
            1,
            "{} used '/authcore list offline-players' command in the Server!",
            player.getName().getString());

      String usernames =
          User.users.values().stream()
              .filter(user -> !user.isPremium)
              .map(user -> user.username)
              .collect(Collectors.joining("\n "));

      if (player != null)
        return AuthCoreServer.LOGGER.toUser(
            1,
            player.connection,
            AuthCoreServer.messages.promptAdminListOfPlayers,
            "Offline-Players");
      else return AuthCoreServer.LOGGER.info(1, "List of Offline-Players in Authcore: ", usernames);

    } catch (Exception err) {
      return AuthCoreServer.LOGGER.error(
          0, "Faced Error in '/authcore list offline-players' Command: ", err);
    }
  }

  /**
   * Handles the {@code /authcore destroy-session <player>} command.
   *
   * <p>Forces termination of a player's active session by kicking them from the server.
   *
   * @param source the command source
   * @param player the target player entity
   * @return 1 on success, 0 on failure or invalid state
   */
  private static int destroyUserSessionCommand(
      CommandSourceStack source, ServerPlayer player) {
    try {
      ServerPlayer sourcePlayer = net.ded3ec.compat.Compat.sourcePlayer(source);

      if (sourcePlayer != null)
        AuthCoreServer.LOGGER.debug(
            1,
            "{} used '/authcore destroy-session <player>' command in the Server!",
            sourcePlayer.getName().getString());

      if (player == null && sourcePlayer != null)
        return AuthCoreServer.LOGGER.toUser(
            0,
            sourcePlayer.connection,
            AuthCoreServer.messages.promptMissingParameter,
            "player");
      else if (player == null)
        return AuthCoreServer.LOGGER.info(
            0,
            "You are missing 'player' parameter in '/authcore destroy-session <player>' command!");

      UUID uuid = player.getUUID();
      String username = player.getName().getString();
      User user = User.getUser(username, uuid);

      if (user == null && sourcePlayer != null)
        return AuthCoreServer.LOGGER.toUser(
            0,
            sourcePlayer.connection,
            AuthCoreServer.messages.promptAdminUserNotFound,
            username);
      else if (user == null)
        return AuthCoreServer.LOGGER.info(0, "User '{}' not Found in the database!", username);

      if (!user.isActive && sourcePlayer != null)
        return AuthCoreServer.LOGGER.toUser(
            0, sourcePlayer.connection, AuthCoreServer.messages.promptAdminUserIsNotActive);
      else if (!user.isActive)
        return AuthCoreServer.LOGGER.info(0, "User '{}' is not Active in the Server!", username);

      // logout() destroys the session (lastAuthenticatedMs = 0 + Redis removal) before
      // kicking - a plain kick would let the player resume the session on rejoin and the
      // "destroy session" command would be a no-op.
      user.logout(AuthCoreServer.messages.promptUserKickedByAdmin);

      // Tell other mods / the proxy that this player is no longer authenticated
      net.ded3ec.network.AuthInterop.broadcast(player, false);

      if (sourcePlayer != null)
        return AuthCoreServer.LOGGER.toUser(
            1,
            sourcePlayer.connection,
            AuthCoreServer.messages.promptAdminUserSessionDestroyed,
            player.getName().getString());
      else
        return AuthCoreServer.LOGGER.info(
            1, "User's session has been destroyed and kicked from the Server!");

    } catch (Exception err) {
      return AuthCoreServer.LOGGER.error(
          0, "Faced Error in '/authcore destroy-session <player>' Command: ", err);
    }
  }

  /**
   * Handles the {@code /authcore set-password <player> <new-password>} command.
   *
   * <p>Administratively changes a player's password using the configured hashing algorithm.
   *
   * @param source the command source
   * @param player the target player entity
   * @param password the new plain-text password
   * @return 1 on success, 0 on failure or invalid arguments
   */
  private static int setPlayerNewPasswordCommand(
      CommandSourceStack source, ServerPlayer player, String password) {
    try {
      ServerPlayer sourcePlayer = net.ded3ec.compat.Compat.sourcePlayer(source);

      if (sourcePlayer != null)
        AuthCoreServer.LOGGER.debug(
            1,
            "{} used '/authcore set-password <player> <new-password>' command in the Server!",
            sourcePlayer.getName().getString());

      if (player == null && sourcePlayer != null)
        return AuthCoreServer.LOGGER.toUser(
            0,
            sourcePlayer.connection,
            AuthCoreServer.messages.promptMissingParameter,
            "player");
      else if (player == null)
        return AuthCoreServer.LOGGER.info(
            0,
            "You are missing 'player' parameter in '/authcore set-password <player> <new-password>' command!");

      if (StringUtils.isBlank(password) && sourcePlayer != null)
        return AuthCoreServer.LOGGER.toUser(
            0,
            sourcePlayer.connection,
            AuthCoreServer.messages.promptMissingParameter,
            "new-password");
      else if (StringUtils.isBlank(password))
        return AuthCoreServer.LOGGER.info(
            0,
            "You are missing 'new-password' parameter in '/authcore set-password <player> <new-password>' command!");

      UUID uuid = player.getUUID();
      String username = player.getName().getString();
      User user = User.getUser(username, uuid);

      if (user == null && sourcePlayer != null)
        return AuthCoreServer.LOGGER.toUser(
            0, sourcePlayer.connection, AuthCoreServer.messages.promptUserNotFoundData);
      else if (user == null)
        return AuthCoreServer.LOGGER.info(0, "User '{}' not Found in the database!", username);

      user.passwordEncryption = AuthCoreServer.config.passwordRules.passwordHashAlgorithm;
      user.password =
          Encrypter.hash(AuthCoreServer.config.passwordRules.passwordHashAlgorithm, password);

      if (user.password == null) {
        // Never leave the account unregistered because hashing failed
        user.passwordEncryption = null;
        return sourcePlayer != null
            ? AuthCoreServer.LOGGER.toUser(
                0, sourcePlayer.connection, AuthCoreServer.messages.promptUserPasswordIsBlank)
            : AuthCoreServer.LOGGER.info(0, "Failed to hash the new password for '{}'!", username);
      }

      user.update("Password Change");

      if (sourcePlayer != null)
        return AuthCoreServer.LOGGER.toUser(
            1,
            sourcePlayer.connection,
            AuthCoreServer.messages.promptAdminUserPasswordChangedSuccessfully,
            player.getName().getString());
      else
        return AuthCoreServer.LOGGER.info(
            1, "User {}'s password has been changed Successfully!", username);

    } catch (Exception err) {
      return AuthCoreServer.LOGGER.error(
          0, "Faced Error in '/authcore set-password <player> <new-password>' Command: ", err);
    }
  }

  /**
   * Handles the {@code /authcore whois [<username>] [<uuid>] [<player>]} command.
   *
   * <p>Displays detailed information about a registered user, including UUID, authentication mode,
   * IP address, activity status, registration state, country, and platform (Java/Bedrock).
   *
   * @param source the command source
   * @param username the username to query
   * @return 1 on success, 0 on failure or missing arguments
   */
  private static int WhoIsUserByUsernameCommand(
      CommandSourceStack source,
      ServerPlayer targetPlayer,
      @Nullable String username,
      @Nullable UUID uuid) {
    try {
      ServerPlayer player = net.ded3ec.compat.Compat.sourcePlayer(source);

      if (player != null)
        AuthCoreServer.LOGGER.debug(
            1,
            "{} used '/authcore whois [<username>] [<uuid>] [<player>]' command in the Server!",
            player.getName().getString());

      if (targetPlayer != null) {
        username = targetPlayer.getName().getString();
        uuid = targetPlayer.getUUID();
      }

      User user = null;

      if (uuid != null) user = User.users.get(uuid);
      else if (username != null) user = User.getUserByUsername(username);

      Object uniqueId = username != null ? username : (uuid != null ? uuid : "Empty String!");

      if (user == null && player != null)
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptAdminUserNotFound, uniqueId);
      else if (user == null)
        return AuthCoreServer.LOGGER.info(
            1,
            "User {}'s data could not be found. Please tell them to register to the server.",
            uniqueId);
      else if (player != null)
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptAdminWhoIsUser, user.username);
      else
        return AuthCoreServer.LOGGER.info(
            1,
            "Information about '{}':\nUUID: {}\nPlatform: {}\nMode: {}\nIP-Address: {}\nStatus: {}\nOffline Registered: {}\nCountry: {}\nuser Created (date): {}\nAuthenticated: {}\nClientGuard: {}\nRisk Score: {}\nSignals: {}",
            user.username,
            user.uuid,
            user.isBedrock.get() ? "Bedrock" : "Java",
            user.isPremium ? "online-mode" : "offline-mode",
            user.ipAddress,
            user.isActive ? "Active" : "Offline",
            user.isRegistered.get()
                ? "True (" + TimeManager.toHumanDate(user.registeredAtMs) + ")"
                : "False",
            user.country.get(),
            TimeManager.toHumanDate(user.userCreatedMs),
            user.isAuthenticated.get()
                ? "True (" + TimeManager.toHumanDate(user.lastAuthenticatedMs) + ")"
                : "False",
            net.ded3ec.security.ClientGuard.profile(user.uuid) != null
                ? "tracked (brand="
                    + net.ded3ec.security.ClientGuard.profile(user.uuid).brand
                    + ", settings="
                    + net.ded3ec.security.ClientGuard.profile(user.uuid).settingsSeen
                    + ", companion="
                    + net.ded3ec.security.ClientGuard.profile(user.uuid).claimsCompanion
                    + "/"
                    + net.ded3ec.security.ClientGuard.profile(user.uuid).attestationOk
                    + ")"
                : "untracked (offline)",
            net.ded3ec.security.ClientGuard.profile(user.uuid) != null
                ? net.ded3ec.security.ClientGuard.profile(user.uuid).risk
                : 0,
            net.ded3ec.security.ClientGuard.profile(user.uuid) != null
                ? net.ded3ec.security.ClientGuard.profile(user.uuid).signals
                : "[]");

    } catch (Exception err) {
      return AuthCoreServer.LOGGER.error(
          0, "Faced Error in '/authcore whois [<username>] [<uuid>] [<player>]' Command: ", err);
    }
  }

  /**
   * Handles the {@code /authcore set-mode online <player>} command.
   *
   * <p>Forces a player into online-mode authentication.
   *
   * @param source the command source
   * @param player the target player entity
   * @return 1 on success, 0 on failure
   */
  private static int setOnlineModePlayerCommand(
      CommandSourceStack source, ServerPlayer player) {
    try {
      ServerPlayer sourcePlayer = net.ded3ec.compat.Compat.sourcePlayer(source);

      if (sourcePlayer != null)
        AuthCoreServer.LOGGER.debug(
            1,
            "{} used '/authcore set-mode online <player>' command in the Server!",
            sourcePlayer.getName().getString());

      if (player == null && sourcePlayer != null)
        return AuthCoreServer.LOGGER.toUser(
            0,
            sourcePlayer.connection,
            AuthCoreServer.messages.promptMissingParameter,
            "player");
      else if (player == null)
        return AuthCoreServer.LOGGER.info(
            0,
            "You are missing 'player' parameter in '/authcore set-mode online <player>' command!");

      UUID uuid = player.getUUID();
      String username = player.getName().getString();
      User user = User.getUser(username, uuid);

      if (user == null && sourcePlayer != null)
        return AuthCoreServer.LOGGER.toUser(
            0,
            sourcePlayer.connection,
            AuthCoreServer.messages.promptAdminUserNotFound,
            player.getName().getString());
      else if (user == null)
        return AuthCoreServer.LOGGER.info(
            0, "User '{}' not Found in the database!", player.getName().getString());

      user.isPremium = true;
      user.update("Player Mode -> Online-mode");
      user.logout(AuthCoreServer.messages.promptUserModeSetToOnline);

      if (sourcePlayer != null)
        return AuthCoreServer.LOGGER.toUser(
            1,
            sourcePlayer.connection,
            AuthCoreServer.messages.promptAdminChangeUserMode,
            player.getName().getString(),
            "automatic login");
      else
        return AuthCoreServer.LOGGER.info(
            1, "User {}'s mode has been set to Online-mode!", player.getName().getString());

    } catch (Exception err) {
      return AuthCoreServer.LOGGER.error(
          0, "Faced Error in '/authcore set-mode online <player>' Command: ", err);
    }
  }

  /**
   * Handles the {@code /authcore set-mode offline <player>} command.
   *
   * <p>Forces a player into offline-mode (password login) authentication. A player who
   * already has a stored password keeps it and logs in with it; only auto-login accounts
   * with a null password are asked to register a new one with {@code /register}.
   *
   * @param source the command source
   * @param player the target player entity
   * @return 1 on success, 0 on failure
   */
  private static int setOfflineModePlayerCommand(
      CommandSourceStack source, ServerPlayer player) {
    try {
      ServerPlayer sourcePlayer = net.ded3ec.compat.Compat.sourcePlayer(source);

      if (sourcePlayer != null)
        AuthCoreServer.LOGGER.debug(
            1,
            "{} used '/authcore set-mode offline <player>' command in the Server!",
            sourcePlayer.getName().getString());

      if (player == null && sourcePlayer != null)
        return AuthCoreServer.LOGGER.toUser(
            0,
            sourcePlayer.connection,
            AuthCoreServer.messages.promptMissingParameter,
            "player");
      else if (player == null)
        return AuthCoreServer.LOGGER.info(
            0,
            "You are missing 'player' parameter in '/authcore set-mode offline <player>' command!");

      UUID uuid = player.getUUID();
      String username = player.getName().getString();
      User user = User.getUser(username, uuid);

      if (user == null && sourcePlayer != null)
        return AuthCoreServer.LOGGER.toUser(
            0,
            sourcePlayer.connection,
            AuthCoreServer.messages.promptAdminUserNotFound,
            player.getName().getString());
      else if (user == null)
        return AuthCoreServer.LOGGER.info(
            0, "User '{}' not Found in the database!", player.getName().getString());

      // Offline-mode accounts authenticate with a password. A player who already registered
      // one keeps it (they just log in with it); only auto-login accounts with a null
      // password are asked to register a new one. The session is destroyed either way so the
      // mode change takes effect on the next join.
      boolean hadPassword = !StringUtils.isBlank(user.password);
      user.isPremium = false;
      user.update("Player Mode -> Offline-mode");
      user.logout(
          hadPassword
              ? AuthCoreServer.messages.promptUserModeSetToOfflineLogin
              : AuthCoreServer.messages.promptUserModeSetToOffline);

      if (sourcePlayer != null)
        return AuthCoreServer.LOGGER.toUser(
            1,
            sourcePlayer.connection,
            AuthCoreServer.messages.promptAdminChangeUserMode,
            player.getName().getString(),
            "password login");
      else
        return AuthCoreServer.LOGGER.info(
            1, "User {}'s mode has been set to Offline-mode!", player.getName().getString());

    } catch (Exception err) {
      return AuthCoreServer.LOGGER.error(
          0, "Faced Error in '/authcore set-mode offline <player>' Command: ", err);
    }
  }

  /**
   * Handles the {@code /authcore set-spawn limbo <x> <y> <z>} command.
   *
   * <p>Updates and saves the spawn location used for the limbo (authentication) dimension.
   *
   * @param source the command source
   * @param xcord the X coordinate
   * @param ycord the Y coordinate
   * @param zcord the Z coordinate
   * @return 1 on success, 0 on failure
   */
  private static int SetLimboSpawnLocationCommand(
      CommandSourceStack source, double xcord, double ycord, double zcord) {
    try {
      ServerPlayer player = net.ded3ec.compat.Compat.sourcePlayer(source);

      if (player != null)
        AuthCoreServer.LOGGER.debug(
            1,
            "{} used '/authcore set-spawn limbo <x-cord> <y-cord> <z-cord>' command in the Server!",
            player.getName().getString());

      if (player != null) {
        Object dimKey =
            net.ded3ec.compat.Compat.worldRegistryKey(
                net.ded3ec.compat.Compat.playerLevel(player));
        if (dimKey != null) {
          // ResourceKey.toString() = "ResourceKey[minecraft:dimension / minecraft:overworld]"
          String s = String.valueOf(dimKey);
          int sep = s.indexOf("/ ");
          int end = s.lastIndexOf(']');
          if (sep >= 0 && end > sep)
            AuthCoreServer.config.lobby.limboConfig.location.dimension =
                s.substring(sep + 2, end);
        }
      }

      if (xcord != AuthCoreServer.config.lobby.limboConfig.location.x)
        AuthCoreServer.config.lobby.limboConfig.location.x = xcord;

      if (ycord != AuthCoreServer.config.lobby.limboConfig.location.y)
        AuthCoreServer.config.lobby.limboConfig.location.y = ycord;

      if (zcord != AuthCoreServer.config.lobby.limboConfig.location.z)
        AuthCoreServer.config.lobby.limboConfig.location.z = zcord;

      HoconConf.saveConfig();
      HoconConf.loadConfig();

      if (player != null)
        return AuthCoreServer.LOGGER.toUser(
            1,
            player.connection,
            AuthCoreServer.messages.promptAdminSpawnLocationUpdated,
            AuthCoreServer.config.lobby.limboConfig.location.dimension);
      else
        return AuthCoreServer.LOGGER.info(
            1,
            "New Spawn Location for Limbo has been configured to World: {} | X Coordinate: {} | Y Coordinate: {} | Z Coordinate: {}!",
            AuthCoreServer.config.lobby.limboConfig.location.dimension,
            AuthCoreServer.config.lobby.limboConfig.location.x,
            AuthCoreServer.config.lobby.limboConfig.location.y,
            AuthCoreServer.config.lobby.limboConfig.location.z);

    } catch (Exception err) {
      return AuthCoreServer.LOGGER.error(
          0,
          "Faced Error in '/authcore set-spawn limbo <x-cord> <y-cord> <z-cord>' Command: {}",
          err);
    }
  }
}
