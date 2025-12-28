package net.ded3ec.authcore.mixin;

import net.ded3ec.authcore.models.User;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A mixin class for the MobEntity class to modify mob targeting behavior. Specifically, it prevents
 * mobs from becoming aggressive toward players who are in the lobby, based on the server's
 * configuration.
 */
@Mixin(MobEntity.class)
abstract class MobEntityMixin {

  /**
   * Prevents mobs from targeting players who are in the lobby. This is determined by checking the
   * player's lobby status.
   *
   * @param target The entity that the mob is attempting to target.
   * @param ci The callback information to control the method execution.
   */
  @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
  private void authCore$disableAggression(LivingEntity target, CallbackInfo ci) {
    // Check if the target is a player.
    if (target instanceof ServerPlayerEntity) {
      // Retrieve the user associated with the player.
      User user = User.users.get(target.getName().getString());

      // Cancel mob targeting if the user is in the lobby.
      if (user != null && user.isInLobby.get()) ci.cancel();
    }
  }
}
