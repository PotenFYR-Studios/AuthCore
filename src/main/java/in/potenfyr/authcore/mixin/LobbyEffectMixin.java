package in.potenfyr.authcore.mixin;

import in.potenfyr.authcore.AuthCoreServer;
import in.potenfyr.authcore.models.Lobby;
import in.potenfyr.authcore.models.User;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code lobby.prevent-status-effect}: no status effect can be applied to an unauthenticated
 * player sitting in the limbo (potions/splash/lingering clouds, beacons, wither, /effect, other
 * mods). The setting existed but was read nowhere. The limbo's own invisibility / blindness go
 * through {@link Lobby#applyLimboEffect}, which is exempt; the unlock restore runs after the player
 * left the limbo, so their own saved effects are never blocked.
 */
/*? if fabric {*/
@net.fabricmc.api.Environment(net.fabricmc.api.EnvType.SERVER)
/*?}*/
@Mixin(LivingEntity.class)
@SuppressWarnings({"mapping", "unresolvable-target"})
abstract class LobbyEffectMixin {

  @Inject(
      method =
          "addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$blockLimboEffect(
      MobEffectInstance effect,
      net.minecraft.world.entity.Entity source,
      CallbackInfoReturnable<Boolean> cir) {
    if (shouldBlock()) cir.setReturnValue(false);
  }

  /** 1.16 shape (single-argument addEffect holds the logic there). */
  @Inject(
      method = "addEffect(Lnet/minecraft/world/effect/MobEffectInstance;)Z",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$blockLimboEffectLegacy(
      MobEffectInstance effect, CallbackInfoReturnable<Boolean> cir) {
    if (shouldBlock()) cir.setReturnValue(false);
  }

  private boolean shouldBlock() {
    try {
      if (!((Object) this instanceof ServerPlayer player)) return false;
      if (Lobby.users.isEmpty() || Lobby.APPLYING_LIMBO_EFFECTS.get()) return false;
      if (AuthCoreServer.config == null || !AuthCoreServer.config.lobby.preventStatusEffect)
        return false;
      User user = User.getUser(player);
      return user != null && user.isInLobby.get();
    } catch (Throwable ignored) {
      return false; // never break effect application on a broken state
    }
  }
}
