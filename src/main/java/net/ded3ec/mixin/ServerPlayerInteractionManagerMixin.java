package net.ded3ec.mixin;

import net.ded3ec.models.Config;
import net.ded3ec.models.Lobby;
import net.ded3ec.models.Messages;
import net.ded3ec.util.Logger;

import java.util.UUID;

import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.User;

/*? if fabric {*/
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
/*?}*/
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/*? if fabric {*/
  @Environment(EnvType.SERVER)
  /*?}*/
@Mixin(ServerPlayerGameMode.class)
abstract class ServerPlayerInteractionManagerMixin {

  @Shadow @Final protected ServerPlayer player;

  @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true)
  private void authCore$onTryBreakBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {

    UUID uuid = player.getUUID();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    if (user != null && user.isInLobby.get() && !AuthCoreServer.config.lobby.allowBlockBreaking) {
      AuthCoreServer.LOGGER.toUser(
          false, user.connection, AuthCoreServer.messages.promptUserBreakBlockNotAllowed);

      cir.setReturnValue(false);
      cir.cancel();
    }
  }
}
