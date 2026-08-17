package net.ded3ec.mixin;

import net.ded3ec.events.ServerEvents;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fallback join hook for servers WITHOUT fabric-api: injected at the vanilla join point
 * (PlayerList.placeNewPlayer). Only fires when neither the fabric-api JOIN hook nor a
 * loader-native event-bus hook registered (see ServerEvents.fabricJoinActive /
 * ServerEvents.nativeJoinActive), so there is no double firing on any loader.
 */
/*? if fabric {*/
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
/*?}*/
/*? if fabric {*/
  @Environment(EnvType.SERVER)
  /*?}*/
@Mixin(PlayerList.class)
@SuppressWarnings({"mapping", "unresolvable-target"})
abstract class ServerEventsFallbackJoinMixin {

  @Inject(
      method =
          /*? if < 1.19 {*/
          /*"placeNewPlayer(Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;)V",
          *//*?} else {*/
          "placeNewPlayer(Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/network/CommonListenerCookie;)V",
          /*?}*/
      at = @At("TAIL"),
      require = 0)
  private void authCore$onJoinFallback(
      Connection connection, ServerPlayer player,
      /*? if < 1.19 {*/
      /*CallbackInfo ci) {
      *//*?} else {*/
      net.minecraft.server.network.CommonListenerCookie cookie, CallbackInfo ci) {
      /*?}*/
    if (ServerEvents.fabricJoinActive || ServerEvents.nativeJoinActive) return;
    try {
      if (player.connection != null)
        ServerEvents.onPlayerJoin(player.connection);
    } catch (RuntimeException ignored) {
      // fallback hooks are best-effort
    }
  }
}
