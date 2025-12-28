package net.ded3ec.authcore.command;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.UUID;
import java.util.regex.Pattern;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.ded3ec.authcore.AuthCore;
import net.ded3ec.authcore.models.User;
import net.ded3ec.authcore.utils.Logger;
import net.ded3ec.authcore.utils.Misc;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.Nullable;

/**
 * Handles the `/account` command for transferring player accounts between cracked and premium
 * modes. This command allows players to switch their account type and update their credentials
 * accordingly.
 */
public class Account {

  /**
   * Registers the `/account` command with the provided dispatcher.
   *
   * @param dispatcher The command dispatcher to register the command with. This allows the server
   *     to recognize and handle the `/transfer` command.
   */
  public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
    dispatcher.register(
        literal("account")
            .requires(
                (ctx) -> {
                  if (ctx.getPlayer() == null) return false;
                  User user = User.users.get(ctx.getPlayer().getName().getString());

                  return !user.isInLobby.get()
                      && Permissions.check(
                          ctx.getPlayer(),
                          AuthCore.config.commands.transferAccount.luckPermsNode,
                          PermissionLevel.fromLevel(
                              AuthCore.config.commands.transferAccount.permissionsLevel));
                })
            .then(
                literal("cracked")
                    .requires(
                        (ctx) -> {
                          if (ctx.getPlayer() == null) return false;
                          User user = User.users.get(ctx.getPlayer().getName().getString());

                          if (!user.isInLobby.get() && user.isRegistered.get()) return false;
                          else
                            return Permissions.check(
                                ctx.getPlayer(),
                                AuthCore.config.commands.transferAccount.luckPermsNode,
                                PermissionLevel.fromLevel(
                                    AuthCore.config.commands.transferAccount.permissionsLevel));
                        })
                    .then(
                        argument("new-password", StringArgumentType.string())
                            .executes(
                                ctx ->
                                    execute(
                                        ctx.getSource(),
                                        "cracked",
                                        StringArgumentType.getString(ctx, "new-password")))))
            .then(
                literal("premium")
                    .requires(
                        (ctx) -> {
                          if (ctx.getPlayer() == null) return false;
                          User user = User.users.get(ctx.getPlayer().getName().getString());

                          if (!user.isInLobby.get() && !user.isRegistered.get()) return false;
                          else
                            return Permissions.check(
                                ctx.getPlayer(),
                                AuthCore.config.commands.transferAccount.luckPermsNode,
                                PermissionLevel.fromLevel(
                                    AuthCore.config.commands.transferAccount.permissionsLevel));
                        })
                    .executes(ctx -> execute(ctx.getSource(), "premium", null))));
  }

  /**
   * Executes the `/transfer` command logic.
   *
   * @param source The source of the command, typically the player executing it.
   * @param type The type of transfer, either "cracked" or "premium".
   * @param password The new password for cracked accounts (nullable).
   * @return An integer result indicating the outcome of the command execution.
   */
  private static int execute(ServerCommandSource source, String type, @Nullable String password) {
    try {
      ServerPlayerEntity player = source.getPlayer();
      if (player == null) return 0;

      Logger.debug(1, "{} used '/transfer' command in the Server!", player.getName());
      User user = User.users.get(player.getName().getString());

      if (user == null)
        return Logger.toKick(
            0,
            player.networkHandler,
            AuthCore.messages.userNotFoundData,
            player.getName().getString());
      else if (!user.isRegistered.get())
        return Logger.toUser(
            0,
            player.networkHandler,
            AuthCore.messages.userNotRegistered,
            player.getName().getString());

      if (type.equalsIgnoreCase("premium")) {
        if (user.isPremium)
          return Logger.toUser(
              0,
              player.networkHandler,
              AuthCore.messages.userIsInSameMode,
              player.getName().getString(),
              "online-mode");

        UUID premiumUUID = Misc.getPreimumUuid(user.username);

        if (premiumUUID == null)
          return Logger.toUser(
              0,
              player.networkHandler,
              AuthCore.messages.usernameIsNotPremium,
              player.getName().getString());

        user.isPremium = true;
        user.uuid = premiumUUID;
        player.setUuid(premiumUUID);

        user.update("Account Transfer to Premium");

        return Logger.toUser(
            1,
            player.networkHandler,
            AuthCore.messages.transferredToPremiumAccount,
            player.getName().getString());

      } else {
        if (!user.isPremium)
          return Logger.toUser(
              0,
              player.networkHandler,
              AuthCore.messages.userIsInSameMode,
              player.getName().getString(),
              "offline-mode");
        else if (password == null)
          return Logger.toUser(0, player.networkHandler, AuthCore.messages.passwordIsBlank);
        else if (checkPassword(source.getPlayer(), password)) {

          user.isPremium = false;
          user.uuid = UUID.randomUUID();
          player.setUuid(user.uuid);

          user.update("Account Transfer to Cracked");

          return Logger.toUser(
              1,
              player.networkHandler,
              AuthCore.messages.transferredToCrackedAccount,
              player.getName().getString());

        } else return 0;
      }

    } catch (Exception err) {
      return Logger.error(0, "Faced Error in Transfer of Account Command: {}", err);
    }
  }

  /**
   * Validates the password for cracked accounts.
   *
   * @param player The player attempting to transfer their account.
   * @param password The password to validate.
   * @return True if the password is valid, false otherwise.
   */
  private static boolean checkPassword(
      @NotNull ServerPlayerEntity player, @NotNull String password) {
    if (StringUtils.isBlank(password))
      return Logger.toUser(false, player.networkHandler, AuthCore.messages.passwordIsBlank);
    else return (checkPassComplexity(player, password));
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
