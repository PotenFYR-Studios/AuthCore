package net.ded3ec.mixin.client;

import net.ded3ec.client.ClientAuthCore;
import net.ded3ec.client.LoginScreen;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.CookieStorage;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Shows the AuthCore login screen before connecting to protected servers. */
@Environment(EnvType.CLIENT)
@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {

  @Inject(method = "connect", at = @At("HEAD"), cancellable = true)
  private static void authCore(
      Screen parent, MinecraftClient client, ServerAddress address, ServerInfo info,
      boolean quickPlay, CookieStorage cookieStorage, CallbackInfo ci) {
    if (!ClientAuthCore.shouldIntercept(address.getAddress())) return;
    ClientAuthCore.lastServer = new ClientAuthCore.ServerContext(address, info, quickPlay, cookieStorage);
    client.setScreen(new LoginScreen(parent, address, info, quickPlay, cookieStorage));
    ci.cancel();
  }
}