package in.potenfyr.authcore.mixin;

import java.util.UUID;

import in.potenfyr.authcore.AuthCoreServer;
import in.potenfyr.authcore.models.User;

/*? if fabric {*/
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
/*?}*/
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Extra lobby restrictions: mounting vehicles/mobs and sleeping are blocked while a player
 * is unauthenticated, even on versions/loaders where the entity-use callbacks do not fire.
 * (Dismounting happens in {@code Lobby.Snapshot} on lock.)
 *
 * <p>Injection points cover every {@code startRiding} era: the 1-arg final entrypoint exists on
 * every Minecraft version, and the force overloads are gated per version (2-arg before 1.21.9,
 * 3-arg since 1.21.9) so every descriptor resolves in the refmap - a version-specific
 * signature can never cause a remap warning or a descriptor-mismatch boot crash. The
 * {@code instanceof} guard keeps inherited targets safe for non-player entities.
 */
import net.minecraft.world.entity.player.Player;
/*? if fabric {*/
  @Environment(EnvType.SERVER)
  /*?}*/
@Mixin(net.minecraft.world.entity.Entity.class)
@SuppressWarnings({"mapping", "unresolvable-target"})
abstract class ServerPlayerRestrictionsMixin {

  /** Blocks mounting (boats, horses, minecarts...) while in the lobby. */
  @Inject(
      method = "startRiding(Lnet/minecraft/world/entity/Entity;)Z",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$blockRiding(
      net.minecraft.world.entity.Entity vehicle,
      CallbackInfoReturnable<Boolean> cir) {
    if (blockRidingShared()) {
      cir.setReturnValue(false);
      cir.cancel();
    }
  }

  /*? if >= 1.21.9 {*/
  /** 1.21.9+ force-overload (2-arg shape was replaced by a 3-arg one). */
  @Inject(
      method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$blockRidingForced(
      net.minecraft.world.entity.Entity vehicle,
      boolean force,
      boolean broadcast,
      CallbackInfoReturnable<Boolean> cir) {
    if (blockRidingShared()) {
      cir.setReturnValue(false);
      cir.cancel();
    }
  }
  /*?} else {*/
/*  @Inject(
      method = "startRiding(Lnet/minecraft/world/entity/Entity;Z)Z",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$blockRidingForced(
      net.minecraft.world.entity.Entity vehicle,
      boolean force,
      CallbackInfoReturnable<Boolean> cir) {
    if (blockRidingShared()) {
      cir.setReturnValue(false);
      cir.cancel();
    }
  }
*//*?}*/

  /** Shared mount restriction check; returns true when the mount must be blocked. */
  @Unique
  private boolean blockRidingShared() {
    if (!((Object) this instanceof ServerPlayer player)) return false;

    User user = User.getUser(player);

    if (user != null
        && user.isInLobby.get()
        && !AuthCoreServer.config.lobby.allowMountableInteractWith) {
      AuthCoreServer.LOGGER.violation(
          false,
          user,
          user.connection,
          AuthCoreServer.messages.promptUserInteractMountableEntityNotAllowed);
      return true;
    }
    return false;
  }
}
