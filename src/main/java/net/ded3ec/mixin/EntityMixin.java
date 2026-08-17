package net.ded3ec.mixin;

import java.util.UUID;
import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.User;
/*? if fabric {*/
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
/*?}*/
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lobby movement guard.
 *
 * <p>With movement fully disabled the player is anchored: ANY real movement vector is
 * canceled (vanilla itself ignores vectors with lengthSqr &lt;= 1e-7), which closes every
 * bypass of the old block-precision check - sub-block steps, per-tick vertical deltas
 * ("vanilla fly"), high-jump and elytra drift. When basic movement is allowed, walking
 * stays possible but flight (flying ability or elytra glide) is still canceled.
 */
/*? if fabric {*/
  @Environment(EnvType.SERVER)
  /*?}*/
@Mixin(Entity.class)
public abstract class EntityMixin {

  @Inject(
      method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$onMovement(MoverType type, Vec3 movement, CallbackInfo ci) {

    // Only apply to players
    if ((Object) this instanceof Player player) {

      User user = User.getUser(player);

      if (user == null || !user.isInLobby.get()) return;

      // Vanilla treats sub-1e-7 vectors as no movement - use the same threshold so
      // server-side no-ops are never blocked.
      double distSq =
          movement.x * movement.x + movement.y * movement.y + movement.z * movement.z;
      if (distSq <= 1.0E-7) return;

      boolean anchored = !AuthCoreServer.config.lobby.allowMovement;

      // Flight is never part of the lobby's allowed movement - cancel moves made
      // while the player holds the flying ability or an active elytra glide.
      boolean flying =
          !anchored
              && (player.isFallFlying()
                  || (net.ded3ec.compat.Compat.getAbilities(player) != null
                      && net.ded3ec.compat.Compat.getAbilities(player).flying));

      if (anchored || flying) {
        ci.cancel();
      }
    }
  }
}
