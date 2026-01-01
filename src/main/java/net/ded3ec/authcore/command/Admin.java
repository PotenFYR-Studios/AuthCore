package net.ded3ec.authcore.command;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.UUID;
import java.util.stream.Collectors;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.ded3ec.authcore.AuthCore;
import net.ded3ec.authcore.models.User;
import net.ded3ec.authcore.utils.HoconConf;
import net.ded3ec.authcore.utils.Logger;
import net.ded3ec.authcore.utils.Misc;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.argument.UuidArgumentType;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

/**
 * Registers and handles all administrative commands for the AuthCore mod.
 *
 * <p>This class provides a comprehensive set of server-side administration tools for managing
 * authentication-related data, player sessions, modes, passwords, and limbo spawn locations. All
 * commands are registered under the root literal {@code /authcore} and are protected by
 * configurable LuckPerms nodes and operator permission levels.
 */
public class Admin {

  /**
   * Registers all AuthCore administrative commands with the server's command dispatcher.
   *
   * <p>The command tree is structured as {@code /authcore <subcommand> [arguments]} and includes
   * functionality for reloading configuration, listing players, managing player data, sessions,
   * passwords, authentication modes, and setting the limbo spawn location.
   *
   * @param dispatcher the Brigadier command dispatcher provided by the Minecraft server
   */
  public static void load(CommandDispatcher<ServerCommandSource> dispatcher) {
    dispatcher.register(
        literal("authcore")
            .requires(
                Permissions.require(
                    AuthCore.config.commands.admin.reload.luckPermsNode,
                    PermissionLevel.fromLevel(
                        net.ded3ec.authcore.AuthCore.config
                            .commands
                            .admin
                            .reload
                            .permissionsLevel)))
            .then(
                literal("reload")
                    .requires(
                        Permissions.require(
                            net.ded3ec.authcore.AuthCore.config.commands.admin.reload.luckPermsNode,
                            PermissionLevel.fromLevel(
                                net.ded3ec.authcore.AuthCore.config
                                    .commands
                                    .admin
                                    .reload
                                    .permissionsLevel)))
                    .executes(ctx -> reloadCommand(ctx.getSource())))
            .then(
                literal("list")
                    .then(
                        literal("players")
                            .requires(
                                Permissions.require(
                                    net.ded3ec.authcore.AuthCore.config
                                        .commands
                                        .admin
                                        .listPlayers
                                        .luckPermsNode,
                                    PermissionLevel.fromLevel(
                                        net.ded3ec.authcore.AuthCore.config
                                            .commands
                                            .admin
                                            .listPlayers
                                            .permissionsLevel)))
                            .executes(ctx -> listPlayersCommand(ctx.getSource())))
                    .then(
                        literal("online-players")
                            .requires(
                                Permissions.require(
                                    net.ded3ec.authcore.AuthCore.config
                                        .commands
                                        .admin
                                        .listOnlineModePlayers
                                        .luckPermsNode,
                                    PermissionLevel.fromLevel(
                                        net.ded3ec.authcore.AuthCore.config
                                            .commands
                                            .admin
                                            .listOnlineModePlayers
                                            .permissionsLevel)))
                            .executes(ctx -> listOnlineModePlayersCommand(ctx.getSource())))
                    .then(
                        literal("offline-players")
                            .requires(
                                Permissions.require(
                                    net.ded3ec.authcore.AuthCore.config
                                        .commands
                                        .admin
                                        .listOfflineModePlayers
                                        .luckPermsNode,
                                    PermissionLevel.fromLevel(
                                        net.ded3ec.authcore.AuthCore.config
                                            .commands
                                            .admin
                                            .listOfflineModePlayers
                                            .permissionsLevel)))
                            .executes(ctx -> listOfflineModePlayersCommand(ctx.getSource()))))
            .then(
                literal("delete")
                    .then(
                        literal("player")
                            .then(
                                argument("player", EntityArgumentType.player())
                                    .requires(
                                        Permissions.require(
                                            net.ded3ec.authcore.AuthCore.config
                                                .commands
                                                .admin
                                                .deletePlayer
                                                .luckPermsNode,
                                            PermissionLevel.fromLevel(
                                                net.ded3ec.authcore.AuthCore.config
                                                    .commands
                                                    .admin
                                                    .deletePlayer
                                                    .permissionsLevel)))
                                    .executes(
                                        ctx ->
                                            deletePlayerCommand(
                                                ctx.getSource(),
                                                EntityArgumentType.getPlayer(ctx, "player"))))))
            .then(
                literal("destroy-session")
                    .then(
                        argument("player", EntityArgumentType.player())
                            .requires(
                                Permissions.require(
                                    net.ded3ec.authcore.AuthCore.config
                                        .commands
                                        .admin
                                        .destroyPlayerSession
                                        .luckPermsNode,
                                    PermissionLevel.fromLevel(
                                        net.ded3ec.authcore.AuthCore.config
                                            .commands
                                            .admin
                                            .destroyPlayerSession
                                            .permissionsLevel)))
                            .executes(
                                ctx ->
                                    destroyUserSessionCommand(
                                        ctx.getSource(),
                                        EntityArgumentType.getPlayer(ctx, "player")))))
            .then(
                literal("set-password")
                    .then(
                        argument("player", EntityArgumentType.player())
                            .then(
                                argument("new-password", StringArgumentType.string())
                                    .requires(
                                        Permissions.require(
                                            net.ded3ec.authcore.AuthCore.config
                                                .commands
                                                .admin
                                                .setPlayerPassword
                                                .luckPermsNode,
                                            PermissionLevel.fromLevel(
                                                net.ded3ec.authcore.AuthCore.config
                                                    .commands
                                                    .admin
                                                    .setPlayerPassword
                                                    .permissionsLevel)))
                                    .executes(
                                        ctx ->
                                            setPlayerNewPasswordCommand(
                                                ctx.getSource(),
                                                EntityArgumentType.getPlayer(ctx, "player"),
                                                StringArgumentType.getString(
                                                    ctx, "new-password"))))))
            .then(
                literal("whois")
                    .then(
                        argument("username", StringArgumentType.string())
                            .requires(
                                Permissions.require(
                                    net.ded3ec.authcore.AuthCore.config
                                        .commands
                                        .admin
                                        .whoisUsername
                                        .luckPermsNode,
                                    PermissionLevel.fromLevel(
                                        net.ded3ec.authcore.AuthCore.config
                                            .commands
                                            .admin
                                            .whoisUsername
                                            .permissionsLevel)))
                            .executes(
                                ctx ->
                                    WhoIsUserByUsernameCommand(
                                        ctx.getSource(),
                                        null,
                                        StringArgumentType.getString(ctx, "username"),
                                        null)))
                    .then(
                        argument("uuid", UuidArgumentType.uuid())
                            .requires(
                                Permissions.require(
                                    net.ded3ec.authcore.AuthCore.config
                                        .commands
                                        .admin
                                        .whoisUsername
                                        .luckPermsNode,
                                    PermissionLevel.fromLevel(
                                        net.ded3ec.authcore.AuthCore.config
                                            .commands
                                            .admin
                                            .whoisUsername
                                            .permissionsLevel)))
                            .executes(
                                ctx ->
                                    WhoIsUserByUsernameCommand(
                                        ctx.getSource(),
                                        null,
                                        null,
                                        UuidArgumentType.getUuid(ctx, "uuid"))))
                    .then(
                        argument("player", EntityArgumentType.player())
                            .requires(
                                Permissions.require(
                                    net.ded3ec.authcore.AuthCore.config
                                        .commands
                                        .admin
                                        .whoisUsername
                                        .luckPermsNode,
                                    PermissionLevel.fromLevel(
                                        net.ded3ec.authcore.AuthCore.config
                                            .commands
                                            .admin
                                            .whoisUsername
                                            .permissionsLevel)))
                            .executes(
                                ctx ->
                                    WhoIsUserByUsernameCommand(
                                        ctx.getSource(),
                                        EntityArgumentType.getPlayer(ctx, "player"),
                                        null,
                                        null))))
            .then(
                literal("set-mode")
                    .then(
                        literal("online")
                            .then(
                                argument("player", EntityArgumentType.player())
                                    .requires(
                                        Permissions.require(
                                            net.ded3ec.authcore.AuthCore.config
                                                .commands
                                                .admin
                                                .setOnlineModePlayer
                                                .luckPermsNode,
                                            PermissionLevel.fromLevel(
                                                net.ded3ec.authcore.AuthCore.config
                                                    .commands
                                                    .admin
                                                    .setOnlineModePlayer
                                                    .permissionsLevel)))
                                    .executes(
                                        ctx ->
                                            setOnlineModePlayerCommand(
                                                ctx.getSource(),
                                                EntityArgumentType.getPlayer(ctx, "player")))))
                    .then(
                        literal("offline")
                            .then(
                                argument("player", EntityArgumentType.player())
                                    .then(
                                        argument("new-password", StringArgumentType.string())
                                            .requires(
                                                Permissions.require(
                                                    net.ded3ec.authcore.AuthCore.config
                                                        .commands
                                                        .admin
                                                        .setOfflineModePlayer
                                                        .luckPermsNode,
                                                    PermissionLevel.fromLevel(
                                                        net.ded3ec.authcore.AuthCore.config
                                                            .commands
                                                            .admin
                                                            .setOfflineModePlayer
                                                            .permissionsLevel)))
                                            .executes(
                                                ctx ->
                                                    setOfflineModePlayerCommand(
                                                        ctx.getSource(),
                                                        EntityArgumentType.getPlayer(ctx, "player"),
                                                        StringArgumentType.getString(
                                                            ctx, "new-password")))))))
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
                                                        Permissions.require(
                                                            net.ded3ec.authcore.AuthCore.config
                                                                .commands
                                                                .admin
                                                                .setSpawnLocation
                                                                .luckPermsNode,
                                                            PermissionLevel.fromLevel(
                                                                net.ded3ec.authcore.AuthCore.config
                                                                    .commands
                                                                    .admin
                                                                    .setSpawnLocation
                                                                    .permissionsLevel)))
                                                    .executes(
                                                        ctx ->
                                                            SetLimboSpawnLocationCommand(
                                                                ctx.getSource(),
                                                                DoubleArgumentType.getDouble(
                                                                    ctx, "x-cord"),
                                                                DoubleArgumentType.getDouble(
                                                                    ctx, "y-cord"),
                                                                DoubleArgumentType.getDouble(
                                                                    ctx, "z-cord")))))))));
  }

  /**
   * Handles the {@code /authcore reload} command.
   *
   * <p>Reloads all configuration files for the AuthCore mod and notifies the command sender (player
   * or console) of the result.
   *
   * @param source the command source (player or console)
   * @return 1 on success, 0 on failure
   */
  private static int reloadCommand(ServerCommandSource source) {
    try {
      ServerPlayerEntity player = source.getPlayer();

      if (player != null)
        Logger.debug(
            1, "{} used '/authcore reload' command in the Server!", player.getName().getString());

      HoconConf.initialize();

      if (player != null)
        return Logger.toUser(
            1,
            player.networkHandler,
            net.ded3ec.authcore.AuthCore.messages.promptAdminReloadedConfiguration);
      else return Logger.info(1, "AuthCore configuration files has been reloaded successfully!");

    } catch (Exception err) {
      return Logger.error(0, "Faced Error in '/authcore reload' Command: ", err);
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
  private static int deletePlayerCommand(ServerCommandSource source, ServerPlayerEntity player) {
    try {
      ServerPlayerEntity sourcePlayer = source.getPlayer();

      if (sourcePlayer != null)
        Logger.debug(
            1,
            "{} used '/authcore delete player <player>' command in the Server!",
            sourcePlayer.getName().getString());

      if (player == null && sourcePlayer != null)
        return Logger.toUser(
            0,
            sourcePlayer.networkHandler,
            AuthCore.messages.promptMissingParameter,
            "player",
            "/authcore delete player <player>");
      else if (player == null)
        return Logger.info(
            0, "You are missing 'player' parameter in '/authcore delete player <player>' command!");

      UUID uuid = player.getUuid();
      String username = player.getName().getString();
      User user = User.getUser(username, uuid);

      if (user == null && sourcePlayer != null)
        return Logger.toUser(
            0, sourcePlayer.networkHandler, AuthCore.messages.promptUserNotFoundData);
      else if (user == null)
        return Logger.info(0, "User '{}' not Found in the database!", player.getName().getString());

      user.delete("Deleted User Data By an Administrator!", true);

      if (sourcePlayer != null)
        return Logger.toUser(
            0, sourcePlayer.networkHandler, AuthCore.messages.promptAdminUserDataDeleted);
      else
        return Logger.info(
            0,
            "User '{}' has been deleted from the database & server!",
            player.getName().getString());

    } catch (Exception err) {
      return Logger.error(0, "Faced Error in '/authcore delete player <player>' Command: ", err);
    }
  }

  /**
   * Handles the {@code /authcore list players} command.
   *
   * <p>Lists all registered player usernames stored in the AuthCore database.
   *
   * @param source the command source
   * @return 1 on success, 0 on failure
   */
  private static int listPlayersCommand(ServerCommandSource source) {
    try {
      ServerPlayerEntity player = source.getPlayer();

      if (player != null)
        Logger.debug(
            1,
            "{} used '/authcore list players' command in the Server!",
            player.getName().getString());

      String usersList =
          User.users.keySet().stream().map(UUID::toString).collect(Collectors.joining("\n "));

      if (player != null)
        return Logger.toUser(
            1,
            player.networkHandler,
            net.ded3ec.authcore.AuthCore.messages.promptAdminListOfPlayers,
            "Players",
            usersList);
      else return Logger.info(1, "List of Players in Authcore: " + usersList);

    } catch (Exception err) {
      return Logger.error(0, "Faced Error in '/authcore list players' Command: ", err);
    }
  }

  /**
   * Handles the {@code /authcore list online-players} command.
   *
   * <p>Lists all players currently registered as premium (online-mode) in the database.
   *
   * @param source the command source
   * @return 1 on success, 0 on failure
   */
  private static int listOnlineModePlayersCommand(ServerCommandSource source) {
    try {
      ServerPlayerEntity player = source.getPlayer();

      if (player != null)
        Logger.debug(
            1,
            "{} used '/authcore list online-players' command in the Server!",
            player.getName().getString());

      String usernames =
          User.users.values().stream()
              .filter(user -> user.isPremium)
              .map(user -> user.username)
              .collect(Collectors.joining("\n "));

      if (player != null)
        return Logger.toUser(
            1,
            player.networkHandler,
            net.ded3ec.authcore.AuthCore.messages.promptAdminListOfPlayers,
            "Online-Players",
            usernames);
      else return Logger.info(1, "List of Online-Players in Authcore: ", usernames);

    } catch (Exception err) {
      return Logger.error(0, "Faced Error in '/authcore list online-players' Command: ", err);
    }
  }

  /**
   * Handles the {@code /authcore list offline-players} command.
   *
   * <p>Lists all players currently registered as cracked (offline-mode) in the database.
   *
   * @param source the command source
   * @return 1 on success, 0 on failure
   */
  private static int listOfflineModePlayersCommand(ServerCommandSource source) {
    try {
      ServerPlayerEntity player = source.getPlayer();

      if (player != null)
        Logger.debug(
            1,
            "{} used '/authcore list offline-players' command in the Server!",
            player.getName().getString());

      String usernames =
          User.users.values().stream()
              .filter(user -> !user.isPremium)
              .map(user -> user.username)
              .collect(Collectors.joining("\n "));

      if (player != null)
        return Logger.toUser(
            1,
            player.networkHandler,
            net.ded3ec.authcore.AuthCore.messages.promptAdminListOfPlayers,
            "Offline-Players",
            usernames);
      else return Logger.info(1, "List of Offline-Players in Authcore: ", usernames);

    } catch (Exception err) {
      return Logger.error(0, "Faced Error in '/authcore list offline-players' Command: ", err);
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
      ServerCommandSource source, ServerPlayerEntity player) {
    try {
      ServerPlayerEntity sourcePlayer = source.getPlayer();

      if (sourcePlayer != null)
        Logger.debug(
            1,
            "{} used '/authcore destroy-session <player>' command in the Server!",
            sourcePlayer.getName().getString());

      if (player == null && sourcePlayer != null)
        return Logger.toUser(
            0,
            sourcePlayer.networkHandler,
            AuthCore.messages.promptMissingParameter,
            "player",
            "/authcore destroy-session <player>");
      else if (player == null)
        return Logger.info(
            0,
            "You are missing 'player' parameter in '/authcore destroy-session <player>' command!");

      UUID uuid = player.getUuid();
      String username = player.getName().getString();
      User user = User.getUser(username, uuid);

      if (user == null && sourcePlayer != null)
        return Logger.toUser(
            0, sourcePlayer.networkHandler, AuthCore.messages.promptAdminUserNotFound, username);
      else if (user == null)
        return Logger.info(0, "User '{}' not Found in the database!", username);

      if (!user.isActive && sourcePlayer != null)
        return Logger.toUser(
            0, sourcePlayer.networkHandler, AuthCore.messages.promptAdminUserIsNotActive);
      else if (!user.isActive)
        return Logger.info(0, "User '{}' is not Active in the Server!", username);

      user.kick(AuthCore.messages.promptUserKickedByAdmin);

      if (sourcePlayer != null)
        return Logger.toUser(
            1,
            sourcePlayer.networkHandler,
            net.ded3ec.authcore.AuthCore.messages.promptAdminUserSessionDestroyed,
            player.getName().getString());
      else return Logger.info(1, "User's session has been destroyed and kicked from the Server!");

    } catch (Exception err) {
      return Logger.error(0, "Faced Error in '/authcore destroy-session <player>' Command: ", err);
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
      ServerCommandSource source, ServerPlayerEntity player, String password) {
    try {
      ServerPlayerEntity sourcePlayer = source.getPlayer();

      if (sourcePlayer != null)
        Logger.debug(
            1,
            "{} used '/authcore set-password <player> <new-password>' command in the Server!",
            sourcePlayer.getName().getString());

      if (player == null && sourcePlayer != null)
        return Logger.toUser(
            0,
            sourcePlayer.networkHandler,
            AuthCore.messages.promptMissingParameter,
            "player",
            "/authcore set-password <player> <new-password>");
      else if (player == null)
        return Logger.info(
            0,
            "You are missing 'player' parameter in '/authcore set-password <player> <new-password>' command!");

      if (StringUtils.isBlank(password) && sourcePlayer != null)
        return Logger.toUser(
            0,
            sourcePlayer.networkHandler,
            AuthCore.messages.promptMissingParameter,
            "new-password",
            "/authcore set-password <player> <new-password>");
      else if (StringUtils.isBlank(password))
        return Logger.info(
            0,
            "You are missing 'new-password' parameter in '/authcore set-password <player> <new-password>' command!");

      UUID uuid = player.getUuid();
      String username = player.getName().getString();
      User user = User.getUser(username, uuid);

      if (user == null && sourcePlayer != null)
        return Logger.toUser(
            0, sourcePlayer.networkHandler, AuthCore.messages.promptUserNotFoundData);
      else if (user == null)
        return Logger.info(0, "User '{}' not Found in the database!", username);

      user.passwordEncryption = AuthCore.config.passwordRules.passwordHashAlgorithm;
      user.password =
          Misc.HashManager.hash(AuthCore.config.passwordRules.passwordHashAlgorithm, password);

      user.update("Password Change");

      if (sourcePlayer != null)
        return Logger.toUser(
            1,
            sourcePlayer.networkHandler,
            AuthCore.messages.promptAdminUserPasswordChangedSuccessfully,
            player.getName().getString());
      else return Logger.info(1, "User {}'s password has been changed Successfully!", username);

    } catch (Exception err) {
      return Logger.error(
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
      ServerCommandSource source,
      ServerPlayerEntity targetPlayer,
      @Nullable String username,
      @Nullable UUID uuid) {
    try {
      ServerPlayerEntity player = source.getPlayer();

      if (player != null)
        Logger.debug(
            1,
            "{} used '/authcore whois [<username>] [<uuid>] [<player>]' command in the Server!",
            player.getName().getString());

      if (targetPlayer != null) {
        username = targetPlayer.getName().getString();
        uuid = targetPlayer.getUuid();
      }

      User user = null;

      if (uuid != null) user = User.users.get(uuid);
      else if (username != null) user = User.getUserByUsername(username);

      Object uniqueId = username != null ? username : (uuid != null ? uuid : "Empty String!");

      if (user == null && player != null)
        return Logger.toUser(
            1,
            player.networkHandler,
            net.ded3ec.authcore.AuthCore.messages.promptAdminUserNotFound,
            uniqueId);
      else if (user == null)
        return Logger.info(
            1,
            "User {}'s data could not be found. Please tell them to register to the server.",
            uniqueId);
      else if (player != null)
        return Logger.toUser(
            1,
            player.networkHandler,
            net.ded3ec.authcore.AuthCore.messages.promptAdminWhoIsUser,
            user.username,
            user.uuid,
            user.isBedrock.get() ? "Bedrock" : "Java",
            user.isPremium ? "online-mode" : "offline-mode",
            user.ipAddress,
            user.isActive ? "Active" : "Offline",
            user.isRegistered.get()
                ? "True (" + Misc.TimeConverter.toHumanDate(user.registeredAtMs) + ")"
                : "False",
            user.country.get(),
            Misc.TimeConverter.toHumanDate(user.userCreatedMs),
            user.isAuthenticated.get()
                ? "True (" + Misc.TimeConverter.toHumanDate(user.lastAuthenticatedMs) + ")"
                : "False");
      else
        return Logger.info(
            1,
            "Information about '{}':\nUUID: {}\nPlatform: {}\nMode: {}\nIP-Address: {}\nStatus: {}\nOffline Registered: {}\nCountry: {}\nuser Created (date): {}\nAuthenticated: {}",
            user.username,
            user.uuid,
            user.isBedrock.get() ? "Bedrock" : "Java",
            user.isPremium ? "online-mode" : "offline-mode",
            user.ipAddress,
            user.isActive ? "Active" : "Offline",
            user.isRegistered.get()
                ? "True (" + Misc.TimeConverter.toHumanDate(user.registeredAtMs) + ")"
                : "False",
            user.country.get(),
            Misc.TimeConverter.toHumanDate(user.userCreatedMs),
            user.isAuthenticated.get()
                ? "True (" + Misc.TimeConverter.toHumanDate(user.lastAuthenticatedMs) + ")"
                : "False");

    } catch (Exception err) {
      return Logger.error(
          0, "Faced Error in '/authcore whois [<username>] [<uuid>] [<player>]' Command: ", err);
    }
  }

  /**
   * Handles the {@code /authcore set-mode online <player>} command.
   *
   * <p>Forces a player into premium (online-mode) authentication.
   *
   * @param source the command source
   * @param player the target player entity
   * @return 1 on success, 0 on failure
   */
  private static int setOnlineModePlayerCommand(
      ServerCommandSource source, ServerPlayerEntity player) {
    try {
      ServerPlayerEntity sourcePlayer = source.getPlayer();

      if (sourcePlayer != null)
        Logger.debug(
            1,
            "{} used '/authcore set-mode online <player>' command in the Server!",
            sourcePlayer.getName().getString());

      if (player == null && sourcePlayer != null)
        return Logger.toUser(
            0,
            sourcePlayer.networkHandler,
            AuthCore.messages.promptMissingParameter,
            "player",
            "/authcore set-mode online <player>");
      else if (player == null)
        return Logger.info(
            0,
            "You are missing 'player' parameter in '/authcore set-mode online <player>' command!");

      UUID uuid = player.getUuid();
      String username = player.getName().getString();
      User user = User.getUser(username, uuid);

      if (user == null && sourcePlayer != null)
        return Logger.toUser(
            0,
            sourcePlayer.networkHandler,
            AuthCore.messages.promptAdminUserNotFound,
            player.getName().getString());
      else if (user == null)
        return Logger.info(0, "User '{}' not Found in the database!", player.getName().getString());

      user.isPremium = true;
      user.update("Player Mode -> Online-mode");
      user.kick(AuthCore.messages.promptUserModeUpdated, "Online-mode");

      if (sourcePlayer != null)
        return Logger.toUser(
            1,
            sourcePlayer.networkHandler,
            net.ded3ec.authcore.AuthCore.messages.promptAdminChangeUserMode,
            player.getName().getString(),
            "Online-Mode");
      else
        return Logger.info(
            1, "User {}'s mode has been set to Online-mode!", player.getName().getString());

    } catch (Exception err) {
      return Logger.error(0, "Faced Error in '/authcore set-mode online <player>' Command: ", err);
    }
  }

  /**
   * Handles the {@code /authcore set-mode offline <player>} command.
   *
   * <p>Forces a player into cracked (offline-mode) authentication.
   *
   * @param source the command source
   * @param player the target player entity
   * @return 1 on success, 0 on failure
   */
  private static int setOfflineModePlayerCommand(
      ServerCommandSource source, ServerPlayerEntity player, String password) {
    try {
      ServerPlayerEntity sourcePlayer = source.getPlayer();

      if (sourcePlayer != null)
        Logger.debug(
            1,
            "{} used '/authcore set-mode offline <player>' command in the Server!",
            sourcePlayer.getName().getString());

      if (player == null && sourcePlayer != null)
        return Logger.toUser(
            0,
            sourcePlayer.networkHandler,
            AuthCore.messages.promptMissingParameter,
            "player",
            "/authcore set-mode offline <player>");
      else if (player == null)
        return Logger.info(
            0,
            "You are missing 'player' parameter in '/authcore set-mode offline <player>' command!");
      else if (StringUtils.isBlank(password) && sourcePlayer != null)
        return Logger.toUser(
            0,
            sourcePlayer.networkHandler,
            AuthCore.messages.promptMissingParameter,
            "new-password",
            "/authcore set-mode offline <player> <new-password>");
      else if (StringUtils.isBlank(password))
        return Logger.info(
            0,
            "You are missing 'new-password' parameter in '/authcore set-mode offline <player> <new-password>' command!");

      UUID uuid = player.getUuid();
      String username = player.getName().getString();
      User user = User.getUser(username, uuid);

      if (user == null && sourcePlayer != null)
        return Logger.toUser(
            0,
            sourcePlayer.networkHandler,
            AuthCore.messages.promptAdminUserNotFound,
            player.getName().getString());
      else if (user == null)
        return Logger.info(0, "User '{}' not Found in the database!", player.getName().getString());

      user.isPremium = false;
      user.passwordEncryption = AuthCore.config.passwordRules.passwordHashAlgorithm;
      user.password = Misc.HashManager.hash(user.passwordEncryption, password);

      user.update("Player Mode -> Offline-mode");
      user.kick(AuthCore.messages.promptUserModeUpdated, "Offline-mode");

      if (sourcePlayer != null)
        return Logger.toUser(
            1,
            sourcePlayer.networkHandler,
            net.ded3ec.authcore.AuthCore.messages.promptAdminChangeUserMode,
            player.getName().getString(),
            "Offline-Mode");
      else
        return Logger.info(
            1, "User {}'s mode has been set to Offline-mode!", player.getName().getString());

    } catch (Exception err) {
      return Logger.error(0, "Faced Error in '/authcore set-mode offline <player>' Command: ", err);
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
      ServerCommandSource source, double xcord, double ycord, double zcord) {
    try {
      ServerPlayerEntity player = source.getPlayer();

      if (player != null)
        Logger.debug(
            1,
            "{} used '/authcore set-spawn limbo <x-cord> <y-cord> <z-cord>' command in the Server!",
            player.getName().getString());

      if (player != null)
        AuthCore.config.lobby.limboConfig.location.dimension =
            player.getEntityWorld().getDimension().toString();

      if (xcord != AuthCore.config.lobby.limboConfig.location.x)
        AuthCore.config.lobby.limboConfig.location.x = xcord;

      if (ycord != AuthCore.config.lobby.limboConfig.location.y)
        AuthCore.config.lobby.limboConfig.location.y = ycord;

      if (zcord != AuthCore.config.lobby.limboConfig.location.z)
        AuthCore.config.lobby.limboConfig.location.z = zcord;

      HoconConf.saveConfig();
      HoconConf.loadConfig();

      if (player != null)
        return Logger.toUser(
            1,
            player.networkHandler,
            net.ded3ec.authcore.AuthCore.messages.promptAdminSpawnLocationUpdated,
            AuthCore.config.lobby.limboConfig.location.dimension,
            AuthCore.config.lobby.limboConfig.location.x,
            AuthCore.config.lobby.limboConfig.location.y,
            AuthCore.config.lobby.limboConfig.location.z);
      else
        return Logger.info(
            1,
            "New Spawn Location for Limbo has been configured to World: {} | X Coordinate: {} | Y Coordinate: {} | Z Coordinate: {}!",
            AuthCore.config.lobby.limboConfig.location.dimension,
            AuthCore.config.lobby.limboConfig.location.x,
            AuthCore.config.lobby.limboConfig.location.y,
            AuthCore.config.lobby.limboConfig.location.z);

    } catch (Exception err) {
      return Logger.error(
          0,
          "Faced Error in '/authcore set-spawn limbo <x-cord> <y-cord> <z-cord>' Command: {}",
          err);
    }
  }
}
