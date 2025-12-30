package net.ded3ec.authcore.command;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.string;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

import com.mojang.brigadier.CommandDispatcher;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.ded3ec.authcore.AuthCore;
import net.ded3ec.authcore.models.User;
import net.ded3ec.authcore.utils.Logger;
import net.ded3ec.authcore.utils.Misc;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.NotNull;

/** Handles the `/login` command for players to authenticate themselves on the server. */
public class Login {

  /**
   * Registers the `/login` command with the provided command dispatcher.
   *
   * @param dispatcher The command dispatcher to register the command with.
   */
  public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
    dispatcher.register(
        literal("login")
            .requires(
                (ctx) -> {
                  // Ensure the player exists and meets the conditions to use the command.
                  if (ctx.getPlayer() == null) return false;
                  User user = User.users.get(ctx.getPlayer().getName().getString());

                  // Check if the user is in the lobby and registered.
                  if (!(user.isInLobby.get() && user.isRegistered.get())) return false;
                  else
                    // Check if the player has the required permissions.
                    return Permissions.check(
                        ctx.getPlayer(),
                        AuthCore.config.commands.user.login.luckPermsNode,
                        PermissionLevel.fromLevel(
                            AuthCore.config.commands.user.login.permissionsLevel));
                })
            .then(
                // Add the "password" argument to the command.
                argument("password", string())
                    .executes(ctx -> execute(ctx.getSource(), getString(ctx, "password")))));
  }

  /**
   * Executes the `/login` command logic.
   *
   * @param source The source of the command, typically the player executing it.
   * @param password The password provided by the player.
   * @return An integer result indicating the outcome of the command execution.
   */
  private static int execute(ServerCommandSource source, @NotNull String password) {
    try {
      // Retrieve the player executing the command.
      ServerPlayerEntity player = source.getPlayer();

      if (player == null) return Logger.info(0, "This command can't be executed from console!");

      // Log the usage of the `/login` command.
      Logger.debug(0, "{} used '/login' command in the Server!", player.getName().getString());

      // Retrieve the user data associated with the player.
      User user = User.users.get(player.getName().getString());

      // Handle the case where the user data is not found.
      if (user == null)
        return Logger.toUser(0, player.networkHandler, AuthCore.messages.promptUserNotFoundData);
      else ++user.loginAttempts;

      // Check if the user has exceeded the maximum login attempts.
      if (user.loginAttempts >= AuthCore.config.session.authentication.maxLoginAttempts)
        return Logger.toKick(
            0,
            player.networkHandler,
            AuthCore.messages.promptUserExceededLoginAttempts,
            AuthCore.config.session.authentication.maxLoginAttempts,
            Misc.TimeConverter.toDuration(AuthCore.config.session.cooldownAfterKickMs));
      else if (!user.isRegistered.get())
        // Inform the user if they are not registered.
        return Logger.toUser(0, player.networkHandler, AuthCore.messages.promptUserNotRegistered);
      else if (Misc.HashManager.verify(password, user.password, user.passwordEncryption)) {
        // Authenticate the user if the password matches.

        Logger.debug(1, "{} have authenticated in the Server!", player.getName().getString());
        user.login(player);

        // Notify the user of successful login.
        return Logger.toUser(
            1, player.networkHandler, AuthCore.messages.promptUserLoggedInSuccessfully);
      } else
        // Notify the user of an incorrect password.
        return Logger.toUser(1, player.networkHandler, AuthCore.messages.promptUserWrongPassword);
    } catch (Exception err) {
      // Log any errors encountered during command execution.
      return Logger.error(0, "Faced Error in '/login' Command: {}", err);
    }
  }
}
