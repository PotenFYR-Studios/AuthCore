package net.ded3ec.mixin;

import java.util.UUID;
import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.User;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents movement for players in the lobby if movement is disabled. */
@Environment(EnvType.SERVER)
@Mixin(Entity.class)
public abstract class EntityMixin {

  @Inject(method = "move", at = @At("HEAD"), cancellable = true)
  private void authCore$onMovement(MovementType type, Vec3d movement, CallbackInfo ci) {

    // Only apply to players
    if ((Object) this instanceof PlayerEntity player) {

      UUID uuid = player.getUuid();
      String username = player.getName().getString();
      User user = User.getUser(username, uuid);

      if (user == null) return;

      BlockPos currentPos = player.getBlockPos();

      // Predict new block position
      BlockPos newPos =
          currentPos.add(
              (int) Math.floor(movement.x),
              (int) Math.floor(movement.y),
              (int) Math.floor(movement.z));

      // Cancel movement if movement is disabled and position would change
      if (user.isInLobby.get()
          && !AuthCoreServer.config.lobby.allowMovement
          && !newPos.equals(currentPos)) {

        ci.cancel();
      }
    }
  }
}
