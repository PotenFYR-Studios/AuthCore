package net.ded3ec.mixin;

import java.util.UUID;

import net.ded3ec.models.User;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.LivingEntity;

import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.SERVER)
@Mixin(MobEntity.class)
abstract class MobEntityMixin {

  @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
  private void authCore$disableAggression(LivingEntity target, CallbackInfo ci) {

    if (target instanceof ServerPlayerEntity player) {

      UUID uuid = player.getUuid();
      String username = player.getName().getString();
      User user = User.getUser(username, uuid);

      if (user != null && user.isInLobby.get()) ci.cancel();
    }
  }
}
