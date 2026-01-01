package net.ded3ec.authcore.mixin;

import java.util.UUID;
import net.ded3ec.authcore.AuthCore;
import net.ded3ec.authcore.models.User;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A mixin class for the LivingEntity class to modify behavior related to status effects.
 * Specifically, it prevents or modifies the addition and removal of status effects for players in
 * the lobby based on the server's configuration.
 */
@Mixin(LivingEntity.class)
abstract class LivingEntityMixin {

  /**
   * Prevents the removal of certain status effects for players in the lobby if the server's
   * configuration disallows it. For example, invisibility and blindness effects are retained for
   * jailed users.
   *
   * @param effect The status effect being removed.
   * @param cir The callback information to control the method execution.
   */
  @Inject(method = "removeStatusEffect", at = @At("HEAD"), cancellable = true)
  private void authCore$onRemoveStatusEffect(
      RegistryEntry<StatusEffect> effect, CallbackInfoReturnable<Boolean> cir) {
    ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;

    // Retrieve the user associated with the player.
    UUID uuid = player.getUuid();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    // Check if the user is in the lobby and handle specific status effects.
    if (user != null && user.isInLobby.get()) {

      // Prevent removal of invisibility for jailed users if configured.
      if (AuthCore.config.lobby.invisibleUnauthorized
          && effect.value() == StatusEffects.INVISIBILITY) {
        player.setInvisible(true);
        cir.setReturnValue(false);
        cir.cancel();
      }

      // Prevent removal of blindness for jailed users if configured.
      else if (AuthCore.config.lobby.applyBlindnessEffect
          && effect.value() == StatusEffects.BLINDNESS) {
        cir.setReturnValue(false);
        cir.cancel();
      }
    }
  }

  /**
   * Prevents the addition of status effects for players in the lobby if the server's configuration
   * disallows it.
   *
   * @param effect The status effect being added.
   * @param cir The callback information to control the method execution.
   */
  @Inject(method = "addStatusEffect", at = @At("HEAD"), cancellable = true)
  private void authCore$onAddStatusEffect(
      StatusEffectInstance effect, CallbackInfoReturnable<Boolean> cir) {
    // Check if the entity is a player.
    if ((Object) this instanceof ServerPlayerEntity player) {
      // Retrieve the user associated with the player.
      UUID uuid = player.getUuid();
      String username = player.getName().getString();
      User user = User.getUser(username, uuid);

      // Prevent addition of status effects for jailed users if configured.
      if (user != null && user.isInLobby.get() && AuthCore.config.lobby.preventStatusEffect) {
        cir.setReturnValue(false);
        cir.cancel();
      }
    }
  }
}
