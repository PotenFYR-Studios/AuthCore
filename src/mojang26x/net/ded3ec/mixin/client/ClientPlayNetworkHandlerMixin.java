package net.ded3ec.mixin.client;

import net.ded3ec.client.ClientAuthCore;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.multiplayer.ClientPacketListener;
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
}