package in.potenfyr.authcore.mixin;

import in.potenfyr.authcore.models.Config;
import in.potenfyr.authcore.models.Lobby;
import in.potenfyr.authcore.models.Messages;
import in.potenfyr.authcore.util.Logger;

import com.mojang.brigadier.ParseResults;
import in.potenfyr.authcore.AuthCoreServer;
import in.potenfyr.authcore.models.User;
/*? if fabric {*/
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
/*?}*/
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/*? if fabric {*/
  @Environment(EnvType.SERVER)
  /*?}*/
@Mixin(Commands.class)
public abstract class CommandManagerMixin {

/*? if >= 1.19.4 {*/
  @Inject(
      method = "performCommand(Lcom/mojang/brigadier/ParseResults;Ljava/lang/String;)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$restrictCommands(
          ParseResults<CommandSourceStack> parseResults, String command, CallbackInfo ci) {

    ServerPlayer player =
        in.potenfyr.authcore.network.McApiManager.PermissionUtil.resolvePlayer(
            parseResults.getContext().getSource());

    if (player == null) return;

    // ClientGuard: command-rate accounting (lobby command flood detection).
    in.potenfyr.authcore.security.ClientGuard.recordChat(player, true);

    User user = User.getUser(player);
    if (user == null || !user.isInLobby.get()) return;

    // Shared decision (also enforced at the PACKET layer - see
    // ServerPlayNetworkHandlerMixin.authCore$onCommandPacket): two independent layers so
    // a silent injection miss can never grant unrestricted commands.
    if (!in.potenfyr.authcore.security.Security.isCommandAllowedInLobby(user, command)) {
      String root = command.split(" ")[0].toLowerCase();
      AuthCoreServer.LOGGER.violation(
          false,
          user,
          player.connection,
          AuthCoreServer.messages.promptUserCommandExecutionNotAllowed,
          root);

      ci.cancel();
    }
  }
/*?}*/
}
