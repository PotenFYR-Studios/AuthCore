package net.ded3ec.mixin;

import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.User;
/*? if fabric {*/
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
/*?}*/
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lobby command restriction for Minecraft 1.19.4, where
 * {@code CommandManager.execute(ParseResults, String)} returns {@code int}.
 *
 * <p>Selector written in stable intermediary names with {@code remap = false}; see
 * {@link CommandManagerMixin} for the version split.
 */
/*? if fabric {*/
@Environment(EnvType.SERVER)
/*?}*/
@Mixin(net.minecraft.commands.Commands.class)
public abstract class CommandManagerIntMixin {

  @Inject(
      method = {
        "method_9249(Lcom/mojang/brigadier/ParseResults;Ljava/lang/String;)I", // yarn (1.19.4)
        "performCommand(Lcom/mojang/brigadier/ParseResults;Ljava/lang/String;)I" // mojmap (forge 1.19.4)
      },
      at = @At("HEAD"),
      cancellable = true,
      remap = false,
      require = 0)
  private void authCore$restrictCommands(
      com.mojang.brigadier.ParseResults<CommandSourceStack> parseResults,
      String command,
      CallbackInfoReturnable<Integer> cir) {

    ServerPlayer player =
        net.ded3ec.network.McApiManager.PermissionUtil.resolvePlayer(
            parseResults.getContext().getSource());

    if (player == null) return;

    // ClientGuard: command-rate accounting (lobby command flood detection).
    net.ded3ec.security.ClientGuard.recordChat(player, true);

    User user = User.getUser(player);
    if (user == null || !user.isInLobby.get()) return;

    // Extract root command
    String root = command.split(" ")[0].toLowerCase();

    // Blacklist mode
    if (AuthCoreServer.config.lobby.useWhitelistAsBlacklist
        && AuthCoreServer.config.lobby.whitelistedCommands.contains(root)) {

      AuthCoreServer.LOGGER.violation(
          false,
          user,
          player.connection,
          AuthCoreServer.messages.promptUserCommandExecutionNotAllowed,
          root);

      cir.setReturnValue(0);
      return;
    }

    // Whitelist mode
    if (!AuthCoreServer.config.lobby.allowCommands
        && !AuthCoreServer.config.lobby.whitelistedCommands.contains(root)) {

      AuthCoreServer.LOGGER.violation(
          false,
          user,
          player.connection,
          AuthCoreServer.messages.promptUserCommandExecutionNotAllowed,
          root);

      cir.setReturnValue(0);
    }
  }
}
