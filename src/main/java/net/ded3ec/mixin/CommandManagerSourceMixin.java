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
 * Lobby command restriction for Minecraft 1.16.5 - 1.18.2, where
 * {@code CommandManager.execute(ServerCommandSource, String)} returns {@code int}.
 *
 * <p>Selector written in stable intermediary names with {@code remap = false}; see
 * {@link CommandManagerMixin} for the version split.
 */
/*? if fabric {*/
@Environment(EnvType.SERVER)
/*?}*/
@Mixin(net.minecraft.commands.Commands.class)
public abstract class CommandManagerSourceMixin {

  @Inject(
      method = {
        "method_9249(Lnet/minecraft/class_2168;Ljava/lang/String;)I", // yarn (1.16-1.18.2)
        "performCommand(Lnet/minecraft/commands/CommandSourceStack;Ljava/lang/String;)I" // mojmap (forge)
      },
      at = @At("HEAD"),
      cancellable = true,
      remap = false,
      require = 0)
  private void authCore$restrictCommands(
      CommandSourceStack source, String command, CallbackInfoReturnable<Integer> cir) {

    ServerPlayer player = net.ded3ec.network.McApiManager.PermissionUtil.resolvePlayer(source);

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
