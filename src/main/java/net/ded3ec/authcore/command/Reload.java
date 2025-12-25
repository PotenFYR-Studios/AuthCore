package net.ded3ec.authcore.command;

import static net.minecraft.server.command.CommandManager.literal;

import com.mojang.brigadier.CommandDispatcher;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.ded3ec.authcore.AuthCore;
import net.ded3ec.authcore.utils.ConfigUtil;
import net.ded3ec.authcore.utils.Logger;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Handles the `/reloadauthCore` command for reloading the AuthCore configuration. This command allows
 * authorized players to reload the configuration files dynamically.
 */
public class Reload {

  /**
   * Registers the `/reloadauthCore` command with the provided dispatcher.
   *
   * @param dispatcher The command dispatcher to register the command with. This allows the server
   *     to recognize and handle the `/reloadauthCore` command.
   */
  public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
    dispatcher.register(
        literal("reloadauthCore")
            .requires(
                Permissions.require(
                    AuthCore.config.commands.reload.luckPermsNode,
                    PermissionLevel.fromLevel(AuthCore.config.commands.reload.permissionsLevel)))
            .executes(ctx -> execute(ctx.getSource())));
  }

  /**
   * Executes the `/reloadauthCore` command logic.
   *
   * @param source The source of the command, typically the player executing it.
   * @return An integer result indicating the outcome of the command execution. Returns 1 for
   *     successful execution, or 0 for failure.
   */
  private static int execute(ServerCommandSource source) {
    try {
      // Retrieve the player executing the command.
      ServerPlayerEntity player = source.getPlayer();

      // Exit if no player is found.
      if (player == null) return 0;

      // Log the usage of the `/reloadauthCore` command.
      Logger.debug(1, "{} used '/reloadauthCore' command in the Server!", player.getName());

      // Reinitialize the configuration files.
      ConfigUtil.initialize();

      // Notify the player of successful configuration reload.
      return Logger.toUser(1, player.networkHandler, AuthCore.messages.reloadedConfiguration);
    } catch (Exception err) {
      // Log any errors encountered during command execution.
      return Logger.error(0, "Faced Error in Reload Command: {}", err);
    }
  }
}
