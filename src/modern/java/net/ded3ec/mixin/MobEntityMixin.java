package net.ded3ec.mixin;

import java.util.UUID;

import net.ded3ec.models.User;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.SERVER)
@Mixin(Mob.class)
abstract class MobEntityMixin {

  @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
  private void authCore$disableAggression(LivingEntity target, CallbackInfo ci) {

    if (target instanceof ServerPlayer player) {

      UUID uuid = player.getUUID();
      String username = player.getName().getString();
      User user = User.getUser(username, uuid);

      if (user != null && user.isInLobby.get()) ci.cancel();
    }
  }
}
