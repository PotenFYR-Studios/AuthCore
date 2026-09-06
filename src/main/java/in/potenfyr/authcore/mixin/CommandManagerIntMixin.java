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

/*? if >= 1.19 && < 1.20 {*/
/*  @Inject(
      method = "performCommand(Lcom/mojang/brigadier/ParseResults;Ljava/lang/String;)I",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$restrictCommands(
      com.mojang.brigadier.ParseResults<CommandSourceStack> parseResults,
      String command,
      CallbackInfoReturnable<Integer> cir) {

    ServerPlayer player =
        in.potenfyr.authcore.network.McApiManager.PermissionUtil.resolvePlayer(
            parseResults.getContext().getSource());

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
