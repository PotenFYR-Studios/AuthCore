package net.ded3ec.authcore.command;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.regex.Pattern;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.ded3ec.authcore.AuthCore;
import net.ded3ec.authcore.models.User;
import net.ded3ec.authcore.utils.Logger;
import net.ded3ec.authcore.utils.Misc;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.Nullable;

/**
 * Handles the `/password` command for managing player passwords on the server. This includes
 * setting a new password for the player or another target player.
 */
public class Password {

  /**
   * Registers the `/password set (target) (new-password)` command with the provided dispatcher.
   *
   * @param dispatcher The command dispatcher to register the command with. This allows the server
   *     to recognize and handle the `/password` command.
   */
  public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
    dispatcher.register(
        literal("password")
            .then(
                literal("set")
                    .then(
                        // Adds the "new-password" argument for the command.
                        argument("new-password", StringArgumentType.string())
                            .requires(
                                (ctx) -> {
                                  // Ensures the player exists and meets the conditions to use the
                                  // command.
                                  if (ctx.getPlayer() == null) return false;
                                  User user = User.users.get(ctx.getPlayer().getName().getString());

                                  // Checks if the user is registered and has the required
                                  // permissions.
                                  if (!user.isRegistered.get()) return false;
                                  else
                                    return Permissions.check(
                                        ctx.getPlayer(),
                                        AuthCore.config.commands.changepassword.luckPermsNode,
                                        PermissionLevel.fromLevel(
                                            AuthCore.config
                                                .commands
                                                .changepassword
                                                .permissionsLevel));
                                })
                            // Executes the command logic for the current player.
                            .executes(
                                ctx ->
                                    execute(ctx.getSource(), getString(ctx, "new-password"), null)))
                    .then(
                        // Adds the "target" argument for specifying another player.
                        argument("target", EntityArgumentType.player())
                            .then(
                                // Adds the "new-password" argument for the target player.
                                argument("new-password", StringArgumentType.string())
                                    .requires(
                                        // Ensures the player has the required permissions to change
                                        // another player's password.
                                        Permissions.require(
                                            AuthCore.config
                                                .commands
                                                .changePasswordTarget
                                                .luckPermsNode,
                                            PermissionLevel.fromLevel(
                                                AuthCore.config
                                                    .commands
                                                    .changePasswordTarget
                                                    .permissionsLevel)))
                                    // Executes the command logic for the target player.
                                    .executes(
                                        ctx ->
                                            execute(
                                                ctx.getSource(),
                                                getString(ctx, "new-password"),
                                                EntityArgumentType.getPlayer(ctx, "target")))))));
  }

  /**
   * Executes the `/password set` command logic.
   *
   * @param source The source of the command, typically the player executing it.
   * @param password The new password to be set.
   * @param target The target player whose password is being changed (nullable).
   * @return An integer result indicating the outcome of the command execution.
   */
  private static int execute(
      ServerCommandSource source, String password, @Nullable ServerPlayerEntity target) {
    try {
      ServerPlayerEntity sourcePlayer = source.getPlayer();
      if (sourcePlayer == null) return 0;

      Logger.debug(1, "{} used '/password set' command in the Server!", sourcePlayer.getName());
      User sourceUser = User.users.get(sourcePlayer.getName().getString());

      // Checks if the source player is registered.
      if (!sourceUser.isRegistered.get())
        return Logger.toUser(
            0,
            sourcePlayer.networkHandler,
            AuthCore.messages.userNotRegistered,
            sourcePlayer.getName().getString());

      if (target != null) {
        User targetUser = User.users.get(target.getName().getString());

        // Checks if the target player is registered.
        if (!targetUser.isRegistered.get())
          return Logger.toUser(
              0,
              sourcePlayer.networkHandler,
              AuthCore.messages.userNotFound,
              target.getName().getString());

        // Validates and updates the target player's password.
        if (checkPassword(target, password, targetUser.password)) {
          targetUser.passwordEncryption = AuthCore.config.passwordRules.passwordHashAlgorithm;
          targetUser.password =
              Misc.HashManager.hash(AuthCore.config.passwordRules.passwordHashAlgorithm, password);

          targetUser.update("Password Change");

          Logger.debug(
              1,
              "{} password has been updated in the database by {}",
              target.getName(),
              source.getName());
          return Logger.toUser(
              1, target.networkHandler, AuthCore.messages.passwordChanged, target.getName());
        } else return 0;
      }

      // Validates and updates the source player's password.
      if (checkPassword(source.getPlayer(), password, sourceUser.password)) {
        sourceUser.passwordEncryption = AuthCore.config.passwordRules.passwordHashAlgorithm;
        sourceUser.password =
            Misc.HashManager.hash(AuthCore.config.passwordRules.passwordHashAlgorithm, password);

        sourceUser.update("Password Change");

        Logger.debug(1, "{} password has been updated in the database!", source.getName());
        return Logger.toUser(
            1,
            source.getPlayer().networkHandler,
            AuthCore.messages.passwordChanged,
            source.getPlayer().getName());

      } else return 0;
    } catch (Exception err) {
      return Logger.error(0, "Faced Error in Change Password Command: {}", err);
    }
  }

  /**
   * Validates the new password against the old password and server rules.
   *
   * @param player The player whose password is being validated.
   * @param newPassword The new password to validate.
   * @param oldPassword The old password to compare against.
   * @return True if the password is valid, false otherwise.
   */
  private static boolean checkPassword(
      @NotNull ServerPlayerEntity player,
      @NotNull String newPassword,
      @NotNull String oldPassword) {
    if (StringUtils.isBlank(newPassword))
      return Logger.toUser(false, player.networkHandler, AuthCore.messages.passwordIsBlank);
    else if (!AuthCore.config.passwordRules.allowReuse && newPassword.equals(oldPassword))
      return Logger.toUser(false, player.networkHandler, AuthCore.messages.duplicatePassword);
    else return (checkPassComplexity(player, newPassword));
  }

  /**
   * Checks the complexity of the password based on server rules.
   *
   * @param player The player attempting to register.
   * @param password The password to validate.
   * @return True if the password meets the complexity requirements, false otherwise.
   */
  private static boolean checkPassComplexity(
      @NotNull ServerPlayerEntity player, @NotNull String password) {

    // Uppercases count using regex
    int uppercaseCount = Pattern.compile("[A-Z]").matcher(password).results().toArray().length;

    // Lowercases count using regex
    int lowercaseCount = Pattern.compile("[a-z]").matcher(password).results().toArray().length;

    // Digits count using regex
    int digitsCount = Pattern.compile("\\d").matcher(password).results().toArray().length;

    // Password length
    int lengthCount = password.length();

    // Checks uppercase in the password
    if (AuthCore.config.passwordRules.upperCase.enabled
        && (uppercaseCount <= AuthCore.config.passwordRules.upperCase.min
            || uppercaseCount >= AuthCore.config.passwordRules.upperCase.max))
      return Logger.toUser(
          false,
          player.networkHandler,
          AuthCore.messages.upperCaseNotPresent,
          AuthCore.config.passwordRules.upperCase.min,
          AuthCore.config.passwordRules.upperCase.max);

    // Checks lowercase in the password
    else if (AuthCore.config.passwordRules.lowerCase.enabled
        && (lowercaseCount <= AuthCore.config.passwordRules.lowerCase.min
            || lowercaseCount >= AuthCore.config.passwordRules.lowerCase.max))
      return Logger.toUser(
          false,
          player.networkHandler,
          AuthCore.messages.lowerCaseNotPresent,
          AuthCore.config.passwordRules.lowerCase.min,
          AuthCore.config.passwordRules.lowerCase.max);

    // Checks digits in the password
    else if (AuthCore.config.passwordRules.digits.enabled
        && (digitsCount <= AuthCore.config.passwordRules.digits.min
            || digitsCount >= AuthCore.config.passwordRules.digits.max))
      return Logger.toUser(
          false,
          player.networkHandler,
          AuthCore.messages.digitNotPresent,
          AuthCore.config.passwordRules.digits.min,
          AuthCore.config.passwordRules.digits.max);

    // Checks length of the password
    else if (AuthCore.config.passwordRules.length.enabled
        && (lengthCount <= AuthCore.config.passwordRules.length.min
            || lengthCount >= AuthCore.config.passwordRules.length.max))
      return Logger.toUser(
          false,
          player.networkHandler,
          AuthCore.messages.PasswordLengthIssue,
          AuthCore.config.passwordRules.length.min,
          AuthCore.config.passwordRules.length.max);
    else return true;
  }
}
