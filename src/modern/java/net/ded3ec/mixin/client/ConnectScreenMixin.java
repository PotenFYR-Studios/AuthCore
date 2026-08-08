package net.ded3ec.mixin.client;

import net.ded3ec.client.ClientAuthCore;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Shows the AuthCore login screen before connecting to protected servers. */
@Environment(EnvType.CLIENT)
@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {

  @Inject(method = "startConnecting", at = @At("HEAD"), cancellable = true)
  private static void authCore(
      Screen parent, Minecraft client, ServerAddress address, ServerData info,
      boolean quickPlay, TransferState cookieStorage, CallbackInfo ci) {
    if (!ClientAuthCore.shouldIntercept(address.getHost())) return;
    ClientAuthCore.lastServer =
        new ClientAuthCore.ServerContext(address, info, quickPlay, cookieStorage);
    ClientAuthCore.openLoginScreen(parent, client, address, info, quickPlay, cookieStorage);
    ci.cancel();
  }
}