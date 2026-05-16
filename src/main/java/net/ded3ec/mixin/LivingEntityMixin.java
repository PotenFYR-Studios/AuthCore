package net.ded3ec.mixin;

import java.util.UUID;

import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.User;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.Entity;

import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Environment(EnvType.SERVER)
@Mixin(LivingEntity.class)
abstract class LivingEntityMixin {

  @Inject(method = "removeStatusEffect", at = @At("HEAD"), cancellable = true)
  private void authCore$onRemoveStatusEffect(
      RegistryEntry<StatusEffect> effect, CallbackInfoReturnable<Boolean> cir) {

    if (!((Object) this instanceof ServerPlayerEntity player)) return;

    UUID uuid = player.getUuid();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    if (user != null && user.isInLobby.get()) {

      // Prevent removal of invisibility
      if (AuthCoreServer.config.lobby.invisibleUnauthorized
          && effect == StatusEffects.INVISIBILITY) {

        player.setInvisible(true);
        cir.setReturnValue(false);
        cir.cancel();
      }

      // Prevent removal of blindness
      if (AuthCoreServer.config.lobby.applyBlindnessEffect && effect == StatusEffects.BLINDNESS) {
        cir.setReturnValue(false);
        cir.cancel();
      }
    }
  }

  @Inject(
      method =
          "addStatusEffect(Lnet/minecraft/entity/effect/StatusEffectInstance;Lnet/minecraft/entity/Entity;)Z",
      at = @At("HEAD"),
      cancellable = true)
  private void authCore$onAddStatusEffect(
      StatusEffectInstance effect, Entity source, CallbackInfoReturnable<Boolean> cir) {

    if (!((Object) this instanceof ServerPlayerEntity player)) return;

    UUID uuid = player.getUuid();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    if (user != null && user.isInLobby.get() && AuthCoreServer.config.lobby.preventStatusEffect) {
      cir.setReturnValue(false);
      cir.cancel();
    }
  }
}
