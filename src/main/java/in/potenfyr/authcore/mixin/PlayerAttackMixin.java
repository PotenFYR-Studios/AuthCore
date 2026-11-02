package in.potenfyr.authcore.mixin;

import in.potenfyr.authcore.events.EntityEvents;
import in.potenfyr.authcore.models.User;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Limbo melee-attack guard at the version-stable {@code Player#attack(Entity)} method.
 *
 * <p>26.1+ split melee attacks out of {@code ServerboundInteractPacket} into a dedicated
 * {@code ServerboundAttackPacket}, and the fabric-api attack callback is dead on those versions -
 * an unauthenticated player could attack players, mobs, item frames and armor stands freely.
 *
 * <p>Guarding the ATTACK METHOD (instead of the version-specific packet class) covers every
 * version without putting a version-specific class name into a mixin descriptor: a descriptor
 * naming a class that does not exist on an older endpoint of a range would fail to apply and
 * could take the whole mixin down. {@link ServerPlayerMixin} guards the same method for versions
 * where {@code ServerPlayer} overrides it; on versions where it does not (26.3), this injection
 * fires for players because they inherit {@code Player#attack}.
 */
/*? if fabric {*/
@net.fabricmc.api.Environment(net.fabricmc.api.EnvType.SERVER)
/*?}*/
@Mixin(Player.class)
@SuppressWarnings({"mapping", "unresolvable-target"})
abstract class PlayerAttackMixin {

  @Inject(
      method = "attack(Lnet/minecraft/world/entity/Entity;)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$onAttack(Entity target, CallbackInfo ci) {
    if (!((Object) this instanceof ServerPlayer player)) return;
    if (in.potenfyr.authcore.models.Lobby.users.isEmpty()) return; // fast path: nobody in limbo
    User user = User.getUser(player);
    if (user == null || !user.isInLobby.get()) return;
    if (target == null || EntityEvents.guardLobbyAttack(user, target)) ci.cancel();
  }
}
