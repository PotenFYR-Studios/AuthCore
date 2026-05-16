package net.ded3ec.mixin;

import com.mojang.authlib.GameProfile;

import java.util.Objects;
import java.util.UUID;

import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.User;
import net.ded3ec.utils.McApiManager;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.network.packet.c2s.login.LoginHelloC2SPacket;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.SERVER)
@Mixin(ServerLoginNetworkHandler.class)
abstract class ServerLoginNetworkHandlerMixin {

  @Shadow
  public abstract void disconnect(Text reason);

  @Shadow
  abstract void startVerify(GameProfile profile);

  @Inject(method = "onHello", at = @At("HEAD"), cancellable = true)
  private void authCore$handleHello(LoginHelloC2SPacket packet, CallbackInfo ci) {

    String username = packet.name();
    UUID uuid = packet.profileId();

    // 1.1 Premium check (normal offline parse)
    if (!UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes()).equals(uuid)) return;

    // 1.2 Premium check (normal Mojang auth)
    if (username.equals(McApiManager.getPremiumUsername(uuid))) return;

    User user = User.getUser(username, uuid);
    if (user != null && user.isPremium) return;

    // 2. Offline-mode disabled? → let vanilla handle it
    if (!(Objects.equals(AuthCoreServer.config.session.serverMode, "offline"))) return;

    // 3. Bedrock restriction
    if (!AuthCoreServer.config.session.authentication.allowBedrockPlayers
        && McApiManager.isBedrockPlayer(uuid)) {

      this.disconnect(
          Text.literal(AuthCoreServer.messages.promptUserBedrockPlayersNotAllowed.logout.text)
              .styled(style -> style.withColor(Formatting.RED)));

      ci.cancel();
      return;
    }

    // 4. Cancel vanilla Mojang authentication entirely
    ci.cancel();

    // 5. Floodgate requirement
    if (AuthCoreServer.config.session.authentication.allowBedrockPlayers
        && !FabricLoader.getInstance().isModLoaded("floodgate")) {

      this.disconnect(Text.literal("Authentication Failed: Floodgate is not installed!"));
      return;
    }

    AuthCoreServer.LOGGER.debug(
        "Detected offline-mode player \"{}(uuid: {})\" sending hello packet", username, uuid);

    // 6. Create offline GameProfile
    GameProfile profile = new GameProfile(uuid, username);

    // 7. Finalize login (1.21+)
    this.startVerify(profile);
  }
}
