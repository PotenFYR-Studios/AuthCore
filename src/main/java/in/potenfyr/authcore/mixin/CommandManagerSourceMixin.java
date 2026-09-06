package in.potenfyr.authcore.mixin;

import in.potenfyr.authcore.AuthCoreServer;
import in.potenfyr.authcore.models.User;
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

/*? if < 1.19 {*/
/*  @Inject(
      method = "performCommand(Lnet/minecraft/commands/CommandSourceStack;Ljava/lang/String;)I",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$restrictCommands(
      CommandSourceStack source, String command, CallbackInfoReturnable<Integer> cir) {

    ServerPlayer player = in.potenfyr.authcore.network.McApiManager.PermissionUtil.resolvePlayer(source);

    if (player == null) return;

    // ClientGuard: command-rate accounting (lobby command flood detection).
    in.potenfyr.authcore.security.ClientGuard.recordChat(player, true);

    User user = User.getUser(player);
    if (user == null || !user.isInLobby.get()) return;

    // Shared decision (identical to the packet layer and the modern dispatcher mixin) -
    // one source of truth so no layer can disagree with another.
    String root = command.split(" ")[0].toLowerCase();
    if (!in.potenfyr.authcore.security.Security.isCommandAllowedInLobby(user, command)) {
      AuthCoreServer.LOGGER.violation(
          false,
          user,
          player.connection,
          AuthCoreServer.messages.promptUserCommandExecutionNotAllowed,
          root);

      cir.setReturnValue(0);
    }
  }
*//*?}*/
}
