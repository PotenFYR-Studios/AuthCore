package net.ded3ec.mixin;

import com.mojang.brigadier.ParseResults;
import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.User;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.SERVER)
@Mixin(CommandManager.class)
public abstract class CommandManagerMixin {

  @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
  private void authCore$restrictCommands(
          ParseResults<ServerCommandSource> parseResults, String command, CallbackInfo ci) {

    ServerPlayerEntity player = parseResults.getContext().getSource().getPlayer();

    if (player == null) return;

    User user = User.getUser(player.getName().getString(), player.getUuid());
    if (user == null || !user.isInLobby.get()) return;

    // Extract root command
    String root = command.split(" ")[0].toLowerCase();

    // Blacklist mode
    if (AuthCoreServer.config.lobby.useWhitelistAsBlacklist
        && AuthCoreServer.config.lobby.whitelistedCommands.contains(root)) {

      AuthCoreServer.LOGGER.toUser(
          false,
          player.networkHandler,
          AuthCoreServer.messages.promptUserCommandExecutionNotAllowed,
          root);

      ci.cancel();
      return;
    }

    // Whitelist mode
    if (!AuthCoreServer.config.lobby.allowCommands
        && !AuthCoreServer.config.lobby.whitelistedCommands.contains(root)) {

      AuthCoreServer.LOGGER.toUser(
          false,
          player.networkHandler,
          AuthCoreServer.messages.promptUserCommandExecutionNotAllowed,
          root);

      ci.cancel();
    }
  }
}
