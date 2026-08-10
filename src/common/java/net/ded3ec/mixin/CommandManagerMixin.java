package net.ded3ec.mixin;

import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.User;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lobby command restriction for Minecraft 1.20.6 - 1.21.x, where
 * {@code CommandManager.execute(ParseResults, String)} returns void.
 *
 * <p>The other classic shapes are handled by {@link CommandManagerSourceMixin} (1.16.5 - 1.18.2)
 * and {@link CommandManagerIntMixin} (1.19.4). The selector is written in stable intermediary
 * names with {@code remap = false} because the method id is the same on every classic version
 * while the descriptor (and refmap) differs.
 */
@Environment(EnvType.SERVER)
@Mixin(CommandManager.class)
public abstract class CommandManagerMixin {

  @Inject(
      method = "method_9249(Lcom/mojang/brigadier/ParseResults;Ljava/lang/String;)V",
      at = @At("HEAD"),
      cancellable = true,
      remap = false,
      require = 0)
  private void authCore$restrictCommands(
      com.mojang.brigadier.ParseResults<net.minecraft.server.command.ServerCommandSource> parseResults,
      String command,
      CallbackInfo ci) {

    ServerPlayerEntity player =
        net.ded3ec.network.McApiManager.PermissionUtil.resolvePlayer(
            parseResults.getContext().getSource());

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
