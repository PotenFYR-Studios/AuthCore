package in.potenfyr.authcore.mixin;

/*? if fabric {*/
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
/*?}*/
import in.potenfyr.authcore.AuthCoreServer;
import in.potenfyr.authcore.models.Lobby;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Death diagnostic for the limbo restore (issue #11).
 *
 * <p>Servers that run with {@code /gamerule showDeathMessages false} receive an EMPTY death message:
 * vanilla's {@code ServerPlayer#die} sends {@code ClientboundPlayerCombatKillPacket(id,
 * CommonComponents.EMPTY)} in that branch, so the death screen shows no cause line and nothing is
 * broadcast to chat - exactly the "it happened again and there was no death message" report. The
 * pre-1.0.3 build logged nothing either (its landing line was DEBUG-only, which never reaches
 * latest.log), which left the report unactionable.
 *
 * <p>This hook writes the REAL cause to the server log at INFO (reaches latest.log regardless of
 * the AuthCore debug setting): damage type, attacker type, position, dimension, health, fall
 * distance and whether the player died inside the post-restore safety window - the last field is
 * what tells a restore-caused death apart from a normal one.
 */
/*? if fabric {*/
  @Environment(EnvType.SERVER)
  /*?}*/
@Mixin(ServerPlayer.class)
@SuppressWarnings({"mapping", "unresolvable-target"})
abstract class ServerPlayerDeathMixin {

  @Inject(
      method = "die(Lnet/minecraft/world/damagesource/DamageSource;)V",
      at = @At("HEAD"),
      require = 0)
  private void authCore$logDeath(DamageSource source, CallbackInfo ci) {
    try {
      ServerPlayer player = (ServerPlayer) (Object) this;
      String msgId = source == null ? "unknown" : source.getMsgId();
      String attacker =
          source != null && source.getEntity() != null
              ? source.getEntity().getType().toString()
              : "none";
      /*? if < 1.20 {*/
      /*// 1.16-1.19: Entity#getLevel(); renamed to level() in 1.20.
      String dimension = in.potenfyr.authcore.compat.Compat.dimensionName(player.getLevel());
      *//*?} else {*/
      String dimension = in.potenfyr.authcore.compat.Compat.dimensionName(player.level());
      /*?}*/
      AuthCoreServer.LOGGER.info(
          true,
          "{} died: cause={} attacker={} pos={} dim={} health={} fallDistance={} inLimbo={}",
          player.getName().getString(),
          msgId,
          attacker,
          player.blockPosition(),
          dimension,
          player.getHealth(),
          in.potenfyr.authcore.compat.Compat.getFallDistance(player),
          Lobby.users.containsKey(player.getName().getString()));
      Lobby.logDeathContext(player, msgId);
    } catch (Throwable ignored) {
      // diagnostics must never break the death path
    }
  }
}
