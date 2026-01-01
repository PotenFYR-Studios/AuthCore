package net.ded3ec.authcore.mixin;

import com.mojang.brigadier.ParseResults;
import net.ded3ec.authcore.AuthCore;
import net.ded3ec.authcore.models.User;
import net.ded3ec.authcore.utils.Logger;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * A mixin class for the CommandManager to restrict command execution based on the user's lobby
 * status and the server's configuration.
 */
@Mixin(CommandManager.class)
public abstract class CommandManagerMixin {

  /**
   * Restricts the execution of commands for players in the lobby based on the server's
   * configuration. Commands can be allowed or denied depending on whether the whitelist is used as
   * a blacklist or not.
   *
   * @param parseResults The parsed results of the command execution.
   * @param command The command string being executed.
   * @param ci The callback information to control the method execution.
   */
  @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
  private void authCore$restrictCommands(
      ParseResults<ServerCommandSource> parseResults, String command, CallbackInfo ci) {
    // Extract the root command and the player executing it.
    String root = command.split(" ")[0].toLowerCase();
    ServerPlayerEntity player = parseResults.getContext().getSource().getPlayer();

    // Check if the player is in the lobby and restrict commands accordingly.
    if (player != null) {
        UUID uuid = player.getUuid();
        String username = player.getName().getString();
        User user = User.getUser(username, uuid);

      if (user.isInLobby.get()) {

        if (AuthCore.config.lobby.useWhitelistAsBlacklist
            && AuthCore.config.lobby.whitelistedCommands.contains(root)) {
          Logger.toUser(
              false, player.networkHandler, AuthCore.messages.promptUserCommandExecutionNotAllowed, root);
          ci.cancel();
        }

        // Restrict commands if the lobby does not allow them.
        else if (!AuthCore.config.lobby.whitelistedCommands.contains(root)
            && !AuthCore.config.lobby.allowCommands) {
          Logger.toUser(
              false, player.networkHandler, AuthCore.messages.promptUserCommandExecutionNotAllowed, root);
          ci.cancel();
        }
      }
    }
  }
}
