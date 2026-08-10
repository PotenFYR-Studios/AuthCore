package net.ded3ec.mixin;

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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lobby command restriction for Minecraft 1.16.5 - 1.18.2, where
 * {@code CommandManager.execute(ServerCommandSource, String)} returns {@code int}.
 *
 * <p>Selector written in stable intermediary names with {@code remap = false}; see
 * {@link CommandManagerMixin} for the version split.
 */
@Environment(EnvType.SERVER)
@Mixin(CommandManager.class)
public abstract class CommandManagerSourceMixin {

  @Inject(
      method = "method_9249(Lnet/minecraft/class_2168;Ljava/lang/String;)I",
      at = @At("HEAD"),
      cancellable = true,
      remap = false,
      require = 0)
  private void authCore$restrictCommands(
      ServerCommandSource source,
      String command,
      CallbackInfoReturnable<Integer> cir) {

    ServerPlayerEntity player =
        net.ded3ec.network.McApiManager.PermissionUtil.resolvePlayer(source);

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

      cir.setReturnValue(0);
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

      cir.setReturnValue(0);
    }
  }
}
