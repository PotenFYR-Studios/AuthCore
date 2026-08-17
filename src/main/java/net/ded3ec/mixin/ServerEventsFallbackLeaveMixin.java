package net.ded3ec.mixin;

import net.ded3ec.events.ServerEvents;
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

  @Inject(method = "handleDisconnection", at = @At("HEAD"), require = 0)
  private void authCore$onLeaveFallback(CallbackInfo ci) {
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
