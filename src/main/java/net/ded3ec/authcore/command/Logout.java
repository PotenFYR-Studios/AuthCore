package net.ded3ec.authcore.command;

import static net.minecraft.server.command.CommandManager.literal;

import com.mojang.brigadier.CommandDispatcher;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.ded3ec.authcore.AuthCore;
import net.ded3ec.authcore.models.User;
import net.ded3ec.authcore.utils.Logger;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

/** Handles the `/logout` command for players to terminate their session on the server. */
public class Logout {

  /**
   * Registers the `/logout` command with the provided command dispatcher.
   *
   * @param dispatcher The command dispatcher to register the command with. This allows the server
   *     to recognize and handle the `/logout` command.
   */
  public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
    dispatcher.register(
        literal("logout")
            .requires(
                // Ensures the player has the required permissions to use the command.
                Permissions.require(
                    AuthCore.config.commands.logout.luckPermsNode,
                    PermissionLevel.fromLevel(AuthCore.config.commands.logout.permissionsLevel)))
            .executes(ctx -> execute(ctx.getSource()))); // Executes the command logic.
  }

  /**
   * Executes the `/logout` command logic.
   *
   * @param source The source of the command, typically the player executing it.
   * @return An integer result indicating the outcome of the command execution. Returns 1 for
   *     successful execution, or 0 for failure.
   */
  private static int execute(ServerCommandSource source) {
    try {
      // Retrieve the player executing the command.
      ServerPlayerEntity player = source.getPlayer();

      if (player == null) return 0; // Exit if no player is found.

      // Log the usage of the `/logout` command.
      Logger.debug(0, "{} used '/logout' command in the Server!", player.getName());

      // Retrieve the user data associated with the player.
      User user = User.users.get(player.getName().getString());

      // Handle the case where the user data is not found.
      if (user == null)
        return Logger.toKick(
            0,
            player.networkHandler,
            AuthCore.messages.userNotFoundData,
            player.getName().getString());
      else if (!user.isRegistered.get())
        // Inform the user if they are not registered.
        return Logger.toUser(
            0,
            player.networkHandler,
            AuthCore.messages.userNotRegistered,
            player.getName().getString());

      // Log the destruction of the user's session.
      Logger.debug(1, "{}'s session has been destroyed!", player.getName());
      Logger.toUser(1, player.networkHandler, AuthCore.messages.userLoggedOut);

      // Log the user out and terminate their session.
      user.logout(AuthCore.messages.sessionExpired);
      return 1; // Indicate successful execution.

    } catch (Exception err) {
      // Log any errors encountered during command execution.
      return Logger.error(0, "Faced Error in Logout Command: {}");
    }
  }
}
