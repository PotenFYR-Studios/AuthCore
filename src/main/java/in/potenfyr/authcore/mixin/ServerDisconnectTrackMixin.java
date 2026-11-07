package in.potenfyr.authcore.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks every SERVER-initiated play disconnect (kick, ban, duplicate login, keep-alive timeout,
 * anti-cheat/spam kicks) so the combat-log punishment never kills a player the server itself
 * removed - only a disconnect the player chose is a combat log (issue #11).
 *
 * <p>Vanilla only calls the listener's {@code disconnect(...)} for server-side decisions; a client
 * that closes the connection goes straight to {@code onDisconnect}. The {@code DisconnectionDetails}
 * parameter is {@link Coerce}d to {@code Object} so this class never references a type that older
 * runtimes inside the range jar do not have; {@code require = 0} keeps every era booting, and
 * {@link Pseudo} keeps 1.19-1.20.1 (no ServerCommonPacketListenerImpl yet) from logging a
 * missing-target WARN that the host harness would count as a failure.
 */
@Pseudo
/*? if fabric {*/
@net.fabricmc.api.Environment(net.fabricmc.api.EnvType.SERVER)
/*?}*/
/*? if >= 1.20.2 {*/
@Mixin(net.minecraft.server.network.ServerCommonPacketListenerImpl.class)
/*?} else {*/
/*@Mixin(net.minecraft.server.network.ServerGamePacketListenerImpl.class)
*//*?}*/
@SuppressWarnings({"mapping", "unresolvable-target"})
abstract class ServerDisconnectTrackMixin {

  @Inject(
      method = "disconnect(Lnet/minecraft/network/chat/Component;)V",
      at = @At("HEAD"),
      require = 0)
  private void authCore$markKick(net.minecraft.network.chat.Component reason, CallbackInfo ci) {
    mark();
  }

  /*? if >= 1.21 {*/
  @Inject(
      method = "disconnect(Lnet/minecraft/network/DisconnectionDetails;)V",
      at = @At("HEAD"),
      require = 0)
  private void authCore$markKickDetails(@Coerce Object details, CallbackInfo ci) {
    mark();
  }
  /*?}*/

  private void mark() {
    try {
      if ((Object) this instanceof net.minecraft.server.network.ServerGamePacketListenerImpl play)
        in.potenfyr.authcore.events.ServerEvents.markServerInitiatedDisconnect(play);
    } catch (Throwable ignored) {
      // bookkeeping only - never break a disconnect
    }
  }
}
