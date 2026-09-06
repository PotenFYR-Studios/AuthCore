package in.potenfyr.authcore.mixin;

import in.potenfyr.authcore.events.ServerEvents;
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

/*? if < 1.20.2 {*/
/*  @Inject(
      method = "placeNewPlayer(Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;)V",
      at = @At("TAIL"),
      require = 0)
  private void authCore$onJoinFallbackLegacy(
      Connection connection, ServerPlayer player, CallbackInfo ci) {
    onJoin(player);
  }
*//*?} else {*/
  @Inject(
      method = "placeNewPlayer(Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/network/CommonListenerCookie;)V",
      at = @At("TAIL"),
      require = 0)
  private void authCore$onJoinFallback(
      Connection connection,
      ServerPlayer player,
      net.minecraft.server.network.CommonListenerCookie cookie,
      CallbackInfo ci) {
    onJoin(player);
  }
/*?}*/

  private void onJoin(ServerPlayer player) {
    if (ServerEvents.fabricJoinActive || ServerEvents.nativeJoinActive) return;
    try {
      if (player.connection != null)
        ServerEvents.onPlayerJoin(player.connection);
    } catch (RuntimeException ignored) {
      // fallback hooks are best-effort
    }
  }
}
