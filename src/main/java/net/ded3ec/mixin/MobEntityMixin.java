package net.ded3ec.mixin;

import java.util.UUID;

import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.User;
/*? if fabric {*/
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
/*?}*/
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/*? if fabric {*/
  @Environment(EnvType.SERVER)
  /*?}*/
@Mixin(Mob.class)
@SuppressWarnings({"mapping", "unresolvable-target"})
abstract class MobEntityMixin {

  @Inject(
      method = "setTarget(Lnet/minecraft/world/entity/LivingEntity;)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$disableAggression(LivingEntity target, CallbackInfo ci) {

    if (target instanceof ServerPlayer player) {

      User user = User.getUser(player);

      // Respect the allow-mob-damage config: only stop mobs from targeting lobby
      // players when the config forbids mob damage against them.
      if (user != null
          && user.isInLobby.get()
          && !AuthCoreServer.config.lobby.allowMobDamage)
        ci.cancel();
    }
  }
}
