package net.ded3ec.mixin;

import java.util.UUID;

import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.User;

/*? if fabric {*/
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
/*?}*/
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Extra lobby restrictions: mounting vehicles/mobs and sleeping are blocked while a player
 * is unauthenticated, even on versions/loaders where the entity-use callbacks do not fire.
 * (Dismounting happens in {@code Lobby.Snapshot} on lock.)
 *
 * <p>The inject handlers deliberately declare NO method arguments (only the callback), so
 * they match every {@code startRiding}/{@code startSleeping} overload on every Minecraft
 * version - a version-specific signature (e.g. the 26.x 3-arg startRiding) can never cause
 * a descriptor-mismatch boot crash. The {@code instanceof} guard keeps inherited targets
 * (where the method is only declared on a superclass) safe for non-player entities.
 */
/*? if fabric {*/
  @Environment(EnvType.SERVER)
  /*?}*/
@Mixin(ServerPlayer.class)
@SuppressWarnings({"mapping", "unresolvable-target"})
abstract class ServerPlayerRestrictionsMixin {

  /** Blocks mounting (boats, horses, minecarts...) while in the lobby. */
  @Inject(method = "startRiding", at = @At("HEAD"), cancellable = true, require = 0)
  private void authCore$blockRiding(CallbackInfoReturnable<Boolean> cir) {
    if (!((Object) this instanceof ServerPlayer player)) return;

    User user = User.getUser(player);

    if (user != null
        && user.isInLobby.get()
        && !AuthCoreServer.config.lobby.allowMountableInteractWith) {
      AuthCoreServer.LOGGER.violation(
          false,
          user,
          user.connection, AuthCoreServer.messages.promptUserInteractMountableEntityNotAllowed);
      cir.setReturnValue(false);
      cir.cancel();
    }
  }

  /** Blocks sleeping in beds while in the lobby. */
  @Inject(method = "startSleeping", at = @At("HEAD"), cancellable = true, require = 0)
  private void authCore$blockSleeping(CallbackInfo ci) {
    if (!((Object) this instanceof ServerPlayer player)) return;

    User user = User.getUser(player);

    if (user != null
        && user.isInLobby.get()
        && !AuthCoreServer.config.lobby.allowSleeping) {
      AuthCoreServer.LOGGER.violation(
          false,
          user,
          user.connection, AuthCoreServer.messages.promptUserSleepNotAllowed);
      ci.cancel();
    }
  }

  /** Blocks elytra gliding while in the lobby - hack clients use it to "fly". */
  @Inject(method = "startFallFlying", at = @At("HEAD"), cancellable = true, require = 0)
  private void authCore$blockElytra(CallbackInfo ci) {
    if (!((Object) this instanceof ServerPlayer player)) return;

    User user = User.getUser(player);

    if (user != null && user.isInLobby.get()) {
      // Revoke any already-active glide state, then cancel the new one
      net.ded3ec.compat.Compat.stopFallFlying(player);
      ci.cancel();
    }
  }

  /**
   * Blocks jumping while lobby movement is fully disabled. The move-cancel mixin already
   * stops the resulting motion - this additionally stops the jump intent itself so clients
   * never see a jump "succeed" (sprint-jump / high-jump hacks).
   */
  @Inject(method = "jumpFromGround", at = @At("HEAD"), cancellable = true, require = 0)
  private void authCore$blockJump(CallbackInfo ci) {
    if (!((Object) this instanceof ServerPlayer player)) return;

    User user = User.getUser(player);

    if (user != null
        && user.isInLobby.get()
        && !AuthCoreServer.config.lobby.allowMovement) {
      ci.cancel();
    }
  }
}
