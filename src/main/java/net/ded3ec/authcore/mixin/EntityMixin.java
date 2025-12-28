package net.ded3ec.authcore.mixin;

import net.ded3ec.authcore.AuthCore;
import net.ded3ec.authcore.models.User;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A mixin class for the Entity class to modify movement behavior. Specifically, it prevents
 * movement for players in the lobby if movement is not allowed by the server's configuration.
 */
@Mixin(Entity.class)
public abstract class EntityMixin {

  /**
   * Prevents players in the lobby from being moved by pistons or other forces if movement is
   * disabled in the server's configuration.
   *
   * @param type The type of movement being applied to the entity.
   * @param movement The vector representing the movement.
   * @param ci The callback information to control the method execution.
   */
  @Inject(method = "move", at = @At("HEAD"), cancellable = true)
  private void authCore$onMovement(MovementType type, Vec3d movement, CallbackInfo ci) {
    // Check if the entity is a player.
    if ((Object) this instanceof PlayerEntity player) {
      // Retrieve the user associated with the player.
      User user = User.users.get(player.getName().getString());

      // Current block position
      BlockPos currentPos = player.getBlockPos();

      // Predicted new block position after movement
      BlockPos newPos =
          currentPos.add(
              (int) Math.floor(movement.x),
              (int) Math.floor(movement.y),
              (int) Math.floor(movement.z));

      // Cancel movement if the user is in the lobby and movement is not allowed.
      if (user != null
          && user.isInLobby.get()
          && !AuthCore.config.lobby.allowMovement
          && !newPos.equals(currentPos)) ci.cancel();
    }
  }
}
