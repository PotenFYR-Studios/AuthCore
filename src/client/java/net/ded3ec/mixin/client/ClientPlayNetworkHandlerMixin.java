package net.ded3ec.mixin.client;

import net.ded3ec.client.ClientAuthCore;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Auto-executes /login or /register shortly after joining an AuthCore server. */
@Environment(EnvType.CLIENT)
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {

  @Inject(method = "onGameJoin", at = @At("HEAD"))
  private void authCore(GameJoinS2CPacket packet, CallbackInfo ci) {
    ClientAuthCore.onJoined((ClientPlayNetworkHandler) (Object) this);
  }
}