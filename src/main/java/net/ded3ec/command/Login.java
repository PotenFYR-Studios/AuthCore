package net.ded3ec.command;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.string;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

import com.mojang.brigadier.CommandDispatcher;
import java.util.UUID;
import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.User;
import net.ded3ec.utils.Encrypter;
import net.ded3ec.utils.TimeManager;
import net.ded3ec.utils.McApiManager;
import net.ded3ec.utils.Security;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.NotNull;

/** Handles the `/login` command for players to authenticate themselves on the server. */
public class Login {

  /**
   * Registers the `/login` command with the provided command dispatcher.
   *
   * @param dispatcher The command dispatcher to load the command with.
   */
  public static void load(CommandDispatcher<ServerCommandSource> dispatcher) {
    dispatcher.register(
        literal("login")
            .requires(
                (ctx) -> {
                  // Ensure the player exists and meets the conditions to use the command.
                  if (ctx.getPlayer() == null) return false;
                  UUID uuid = ctx.getPlayer().getUuid();
                  String username = ctx.getPlayer().getName().getString();
                  User user = User.getUser(username, uuid);

                  // Check if the player has the required permissions.
                  return user != null
                      && user.isInLobby.get()
                      && !user.isAuthenticated.get()
                      && user.isRegistered.get()
                      && McApiManager.PermissionUtil.has(
                          ctx.getPlayer(),
                          AuthCoreServer.config.commands.user.login.luckPermsNode,
                          AuthCoreServer.config.commands.user.login.permissionsLevel);
                })
            .then(
                // Add the "password" argument to the command.
                argument("password", string())
                    .executes(ctx -> execute(ctx.getSource(), getString(ctx, "password"), null))
                    .then(
                        // Add the "2fa-code" argument to the command.
                        argument("2fa-code", string())
                            .executes(
                                ctx ->
                                    execute(
                                        ctx.getSource(),
                                        getString(ctx, "password"),
                                        getString(ctx, "2fa-code"))))));
  }

  /**
   * Executes the `/login` command logic.
   *
   * @param source The source of the command, typically the player executing it.
   * @param password The password provided by the player.
   * @param authCode The 2fa code provided by the player.
   * @return An integer result indicating the outcome of the command execution.
   */
  private static int execute(
      ServerCommandSource source, @NotNull String password, String authCode) {
    try {
      // Retrieve the player executing the command.
      ServerPlayerEntity player = source.getPlayer();

      if (player == null)
        return AuthCoreServer.LOGGER.info(0, "This command can't be executed from console!");

      // Log the usage of the `/login` command.
      AuthCoreServer.LOGGER.debug(
          0, "{} used '/login' command in the Server!", player.getName().getString());

      // Retrieve the user data associated with the player.
      UUID uuid = player.getUuid();
      String username = player.getName().getString();
      User user = User.getUser(username, uuid);

      // Handle the case where the user data is not found.
      if (user == null)
        return AuthCoreServer.LOGGER.toUser(
            0, player.networkHandler, AuthCoreServer.messages.promptUserNotFoundData);
      else ++user.loginAttempts;

      // Check if the user has exceeded the maximum login attempts.
      if (user.loginAttempts >= AuthCoreServer.config.session.authentication.maxLoginAttempts)
        return AuthCoreServer.LOGGER.toKick(
            0,
            player.networkHandler,
            AuthCoreServer.messages.promptUserExceededLoginAttempts,
            AuthCoreServer.config.session.authentication.maxLoginAttempts,
            TimeManager.toDuration(AuthCoreServer.config.session.cooldownAfterKickMs));
      else if (!user.isRegistered.get())
        // Inform the user if they are not registered.
        return AuthCoreServer.LOGGER.toUser(
            0, player.networkHandler, AuthCoreServer.messages.promptUserNotRegistered);
      else if (Encrypter.verify(password, user.password, user.passwordEncryption)) {
        // Authenticate the user if the password matches.

        if (AuthCoreServer.config.session.authentication.allowTOTPSupport && authCode == null)
          return AuthCoreServer.LOGGER.toUser(
              1, player.networkHandler, AuthCoreServer.messages.promptUserMissing2faCode);
        else if (AuthCoreServer.config.session.authentication.allowTOTPSupport
            && Security.TOTPManager.verify(authCode, user.authSecret))
          return AuthCoreServer.LOGGER.toUser(
              1, player.networkHandler, AuthCoreServer.messages.promptUserWrong2faCode);

        AuthCoreServer.LOGGER.debug(
            1, "{} have authenticated in the Server!", player.getName().getString());
        user.login(player);

        // Notify the user of successful login.
        return AuthCoreServer.LOGGER.toUser(
            1, player.networkHandler, AuthCoreServer.messages.promptUserLoggedInSuccessfully);
      } else
        // Notify the user of an incorrect password.
        return AuthCoreServer.LOGGER.toUser(
            1, player.networkHandler, AuthCoreServer.messages.promptUserWrongPassword);
    } catch (Exception err) {
      // Log any errors encountered during command execution.
      return AuthCoreServer.LOGGER.error(0, "Faced Error in '/login' Command: ", err);
    }
  }
}
