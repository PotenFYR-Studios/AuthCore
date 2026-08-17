package net.ded3ec.mixin;

import net.ded3ec.events.ServerEvents;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fallback tick hook for servers WITHOUT fabric-api: injected at the vanilla server tick
 * end (MinecraftServer.tickServer RETURN). Only fires when neither the fabric-api
 * END_SERVER_TICK hook nor a loader-native event-bus hook registered (see
 * ServerEvents.fabricTickActive / ServerEvents.nativeTickActive), so there is no double
 * firing on any loader.
 */
/*? if fabric {*/
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
/*?}*/
/*? if fabric {*/
  @Environment(EnvType.SERVER)
  /*?}*/
@Mixin(MinecraftServer.class)
abstract class ServerEventsFallbackTickMixin {

  @Inject(
      method = "tickServer(Ljava/util/function/BooleanSupplier;)V",
      at = @At("RETURN"),
      require = 0)
  private void authCore$onTickFallback(java.util.function.BooleanSupplier hasTimeLeft, CallbackInfo ci) {
    if (ServerEvents.fabricTickActive || ServerEvents.nativeTickActive) return;
    try {
      ServerEvents.onEndServerTick((MinecraftServer) (Object) this);
    } catch (RuntimeException ignored) {
      // fallback hooks are best-effort
    }
  }
}
