package net.ded3ec.mixin;

/*? if fabric {*/
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
/*?}*/
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Universal combat-log detection on the VANILLA damage path - loader-independent and
 * version-agnostic (the fabric-api damage callback alone only covers fabric).
 *
 * <p>The {@code hurt}/{@code actuallyHurt} descriptors below are matched at RUNTIME by the
 * mixin (string descriptors, never compile-checked): the boolean {@code hurt} covers
 * 1.16-1.21.1, the void {@code hurt} covers 1.21.2+ / 26.x, and the ServerLevel
 * {@code actuallyHurt} covers 1.20.5+ (including the 1.20.5-1.21.1 gap). Every inject is
 * {@code require = 0} - whichever descriptor does not exist on the running version is
 * skipped. Damage marks the player's combat window when the attacker type matches the
 * configured combat-log rules (players / mobs / environmental).
 */
/*? if fabric {*/
  @Environment(EnvType.SERVER)
  /*?}*/
@Mixin(LivingEntity.class)
abstract class ServerPlayerCombatMixin {

  /** 1.16 - 1.21.1 shape: boolean hurt(DamageSource, float). */
  @Inject(
      method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
      at = @At("HEAD"),
      require = 0)
  private void authCore$onHurtLegacy(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
    recordCombat(source);
  }

  /** 1.21.2+ / 26.x shape: void hurt(DamageSource, float). */
  @Inject(
      method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)V",
      at = @At("HEAD"),
      require = 0)
  private void authCore$onHurt(DamageSource source, float amount, CallbackInfo ci) {
    recordCombat(source);
  }

  /** 1.20.5+ shape: void actuallyHurt(ServerLevel, DamageSource, float) - also covers 1.20.5-1.21.1. */
  @Inject(
      method =
          "actuallyHurt(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;F)V",
      at = @At("HEAD"),
      require = 0)
  private void authCore$onActuallyHurt(
      net.minecraft.server.level.ServerLevel level,
      DamageSource source,
      float amount,
      CallbackInfo ci) {
    recordCombat(source);
  }

  /** Marks the player's combat window when the damage source matches the combat rules. */
  private void recordCombat(DamageSource source) {
    if (!((Object) this instanceof net.minecraft.server.level.ServerPlayer player)) return;
    net.ded3ec.models.User user = net.ded3ec.models.User.getUser(player);
    if (user == null || user.isInLobby.get()) return;
    if (net.ded3ec.AuthCoreServer.config == null
        || !net.ded3ec.AuthCoreServer.config.session.combatLog.enabled) return;

    net.minecraft.world.entity.Entity attacker = source.getEntity();
    net.ded3ec.models.Config.Session.CombatLogConfig cfg =
        net.ded3ec.AuthCoreServer.config.session.combatLog;
    boolean fromPlayer = attacker instanceof net.minecraft.server.level.ServerPlayer;
    boolean fromMob = attacker instanceof Mob;

    if ((fromPlayer && cfg.detectFromPlayers)
        || (fromMob && cfg.detectFromMobs)
        || (attacker == null && cfg.detectFromEnvironmental))
      user.lastCombatDetectMs = System.currentTimeMillis();
  }
}
