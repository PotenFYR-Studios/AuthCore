package net.ded3ec.mixin.client;

/*? if fabric {*/

import net.ded3ec.client.ClientAuthCore;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.multiplayer.ClientPacketListener;
/*? if < 1.20.2 {*/
/*import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
*//*?} else {*/
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
/*?}*/
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Auto-executes /login or /register shortly after joining an AuthCore server. */
@Environment(EnvType.CLIENT)
@Mixin(ClientPacketListener.class)
public abstract class ClientPlayNetworkHandlerMixin {

  @Inject(method = "handleLogin", at = @At("HEAD"))
  private void authCore(ClientboundLoginPacket packet, CallbackInfo ci) {
    ClientAuthCore.onJoined((ClientPacketListener) (Object) this);
  }

  /** Companion attestation + session-token payloads from the server. */
  @Inject(method = "handleCustomPayload", at = @At("HEAD"), require = 0)
  private void authCore$onPayload(ClientboundCustomPayloadPacket packet, CallbackInfo ci) {
    ClientAuthCore.onServerPayload((ClientPacketListener) (Object) this, packet);
  }
}
/*?}*/
