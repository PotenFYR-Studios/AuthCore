package in.potenfyr.authcore.mixin;

import java.util.UUID;
import in.potenfyr.authcore.AuthCoreServer;
import in.potenfyr.authcore.models.User;
/*? if fabric {*/
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
/*?}*/
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
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
                  || (in.potenfyr.authcore.compat.Compat.getAbilities(player) != null
                      && in.potenfyr.authcore.compat.Compat.getAbilities(player).flying));

      if (anchored || flying) {
        ci.cancel();
      }
    }
  }

  /**
   * Portal travel guard: an unauthenticated player must never be carried to another dimension by
   * a portal (the configured limbo anchor can sit in or next to one). This hooks the per-tick
   * portal driver - AuthCore's own teleports go through {@code teleportTo}/{@code teleport}, not
   * this method, so the limbo teleport and the post-login restore are unaffected.
   *
   * <p>The driver was renamed from {@code handleNetherPortal} to {@code handlePortal} in the
   * 1.20 portal refactor, so each era's build must target the name its mappings actually contain:
   * a single mixin listing both names makes the annotation processor warn that one of them
   * cannot be remapped for every variant.
   */
  /*? if < 1.20 {*/
  /*@Inject(method = "handleNetherPortal()V", at = @At("HEAD"), cancellable = true, require = 0)
  private void authCore$blockNetherPortal(CallbackInfo ci) {
    if (blockLobbyPortal()) ci.cancel();
  }
  *//*?} else {*/
  @Inject(method = "handlePortal()V", at = @At("HEAD"), cancellable = true, require = 0)
  private void authCore$blockPortal(CallbackInfo ci) {
    if (blockLobbyPortal()) ci.cancel();
  }
  /*?}*/

  /** True when this entity is a player currently locked in the limbo. */
  @Unique
  private boolean blockLobbyPortal() {
    if (!((Object) this instanceof Player player)) return false;
    User user = User.getUser(player);
    if (user != null && user.isInLobby.get()) {
      AuthCoreServer.LOGGER.debug(
          false, "{} | portal travel blocked in the limbo", user.username);
      return true;
    }
    return false;
  }
}
