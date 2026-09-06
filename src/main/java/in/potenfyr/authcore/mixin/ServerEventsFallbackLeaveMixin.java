package in.potenfyr.authcore.mixin;

import in.potenfyr.authcore.events.ServerEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fallback leave hook for servers WITHOUT fabric-api: injected at the vanilla disconnect
 * point (handleDisconnection). Only fires when neither the fabric-api DISCONNECT hook nor a
 * loader-native event-bus hook registered (see ServerEvents.fabricLeaveActive /
 * ServerEvents.nativeLeaveActive), so there is no double firing on any loader.
 */
/*? if >= 1.20.2 {*/
import net.minecraft.network.DisconnectionDetails;
/*?}*/
/*? if fabric {*/
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
/*?}*/
/*? if fabric {*/
  @Environment(EnvType.SERVER)
  /*?}*/
@Mixin(ServerGamePacketListenerImpl.class)
@SuppressWarnings({"mapping", "unresolvable-target"})
abstract class ServerEventsFallbackLeaveMixin {

/*? if < 1.20.2 {*/
/*  @Inject(
      method = "onDisconnect(Lnet/minecraft/network/chat/Component;)V",
      at = @At("HEAD"),
      require = 0)
  private void authCore$onLeaveFallbackLegacy(
      net.minecraft.network.chat.Component reason, CallbackInfo ci) {
    handleLeave();
  }
*//*?} else {*/
  @Inject(
      method = "onDisconnect(Lnet/minecraft/network/DisconnectionDetails;)V",
      at = @At("HEAD"),
      require = 0)
  private void authCore$onLeaveFallback(
      DisconnectionDetails details, CallbackInfo ci) {
    handleLeave();
  }
/*?}*/

  private void handleLeave() {
    if (ServerEvents.fabricLeaveActive || ServerEvents.nativeLeaveActive) return;
    try {
      ServerPlayer player = ((ServerGamePacketListenerImpl) (Object) this).player;
      if (player != null && player.connection != null)
        ServerEvents.onPlayerLeave(player.connection);
    } catch (RuntimeException ignored) {
      // fallback hooks are best-effort
    }
  }
}
