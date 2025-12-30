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
import net.ded3ec.authcore.utils.Security;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
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
   * @param dispatcher The command dispatcher to register the command with. This allows the server
   *     to recognize and handle the `/register` command.
   */
  public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
    dispatcher.register(
        literal("register")
            .requires(
                (ctx) -> {
                  if (ctx.getPlayer() == null) return false;
                  User user = User.users.get(ctx.getPlayer().getName().getString());

                  // Ensures the player is in the lobby and not already registered.
                  if (!(user.isInLobby.get() && !user.isRegistered.get())) return false;
                  else
                    return Permissions.check(
                        ctx.getPlayer(),
                        AuthCore.config.commands.user.register.luckPermsNode,
                        PermissionLevel.fromLevel(
                            AuthCore.config.commands.user.register.permissionsLevel));
                })
            .then(
                argument("password", string())
                    .executes(
                        ctx -> registerCommand(ctx.getSource(), getString(ctx, "password"), null))
                    .then(
                        argument("confirm-password", string())
                            .requires(
                                ctx ->
                                    AuthCore.config
                                        .session
                                        .authentication
                                        .registerPasswordConfirmation)
                            .executes(
                                ctx ->
                                    registerCommand(
                                        ctx.getSource(),
                                        getString(ctx, "password"),
                                        getString(ctx, "confirm-password"))))));
  }

  /**
   * Executes the `/register` command logic.
   *
   * @param source The source of the command, typically the player executing it.
   * @param password The password provided by the player.
   * @param confirmPassword The confirmation password provided by the player (nullable).
   * @return An integer result indicating the outcome of the command execution.
   */
  private static int registerCommand(
      ServerCommandSource source, @NotNull String password, @Nullable String confirmPassword) {
    try {
      ServerPlayerEntity player = source.getPlayer();

      if (player == null) return Logger.info(0, "This command can't be executed from console!");

      Logger.debug(0, "{} used '/register' command in the Server!", player.getName().getString());

      User user = User.users.get(player.getName().getString());

      // Handle cases where the user data is not found or the user is already registered.
      if (user == null)
        return Logger.toKick(0, player.networkHandler, AuthCore.messages.promptUserNotFoundData);

      if (user.isRegistered.get())
        return Logger.toUser(
            0, player.networkHandler, AuthCore.messages.promptUserAlreadyRegistered);

      // Validate the password and register the user if valid.
      if (checkPassword(player, password, confirmPassword)) {

        Logger.debug(1, "{} has been registered to the Server!", player.getName().getString());
        Logger.toUser(1, player.networkHandler, AuthCore.messages.promptUserRegisteredSuccessfully);

        user.register(player, password);
        return 1;
      }

      return 0;
    } catch (Exception err) {
      return Logger.error(0, "Faced Error in '/register' Command: {}", err);
    }
  }

  /**
   * Validates the password and confirmation password (if required).
   *
   * @param player The player attempting to register.
   * @param password The password provided by the player.
   * @param confirmPassword The confirmation password provided by the player (nullable).
   * @return True if the password is valid, false otherwise.
   */
  private static boolean checkPassword(
      @NotNull ServerPlayerEntity player,
      @NotNull String password,
      @Nullable String confirmPassword) {
    if (StringUtils.isBlank(password))
      return Logger.toUser(
          false, player.networkHandler, AuthCore.messages.promptUserPasswordIsBlank);
    else if (AuthCore.config.session.authentication.registerPasswordConfirmation
        && StringUtils.isBlank(confirmPassword))
      return Logger.toUser(
          false, player.networkHandler, AuthCore.messages.promptUserConfirmPasswordIsBlank);
    else if (AuthCore.config.session.authentication.registerPasswordConfirmation
        && !password.equals(confirmPassword))
      return Logger.toUser(
          false, player.networkHandler, AuthCore.messages.promptUserPasswordDoesNotMatch);
    else return (Security.getPasswordComplexity(player, password));
  }
}
