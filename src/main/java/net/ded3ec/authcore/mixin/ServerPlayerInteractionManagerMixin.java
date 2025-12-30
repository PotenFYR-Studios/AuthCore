package net.ded3ec.authcore.mixin;

import net.ded3ec.authcore.AuthCore;
import net.ded3ec.authcore.models.User;
import net.ded3ec.authcore.utils.Logger;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Mixin for ServerPlayerInteractionManager to handle player interactions in AuthCore. */
@Mixin(ServerPlayerInteractionManager.class)
abstract class ServerPlayerInteractionManagerMixin {

  /** The server player entity associated with this interaction manager. */
  @Shadow @Final protected ServerPlayerEntity player;

  /**
   * Intercept block breaking before the server processes it.
   *
   * @param pos the position of the block being broken
   * @param cir callback info returnable
   */
  @Inject(method = "tryBreakBlock", at = @At("HEAD"), cancellable = true)
  private void authCore$onTryBreakBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
    User user = User.users.get(player.getName().getString());

    if (user != null && user.isInLobby.get() && !AuthCore.config.lobby.allowBlockBreaking) {
      Logger.toUser(false, user.handler, AuthCore.messages.promptUserBreakBlockNotAllowed);

      cir.setReturnValue(false);
      cir.cancel();
    }
  }
}
