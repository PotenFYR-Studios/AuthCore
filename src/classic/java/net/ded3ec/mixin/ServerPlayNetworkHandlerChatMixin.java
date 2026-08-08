package net.ded3ec.mixin;

import java.util.UUID;

import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.User;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Universal chat restriction for lobby players (1.16 - 26.x).
 *
 * <p>The chat packet handler changed across versions ({@code onGameMessage(ChatMessageC2SPacket)}
 * on 1.16-1.20.4, the signed-message handler on 1.19.3+). Both method descriptors are listed as
 * injection targets; only the one present on the running version applies, and the handler needs
 * no packet argument because the restriction only depends on the player state.
 */
@Environment(EnvType.SERVER)
@Mixin(ServerPlayNetworkHandler.class)
abstract class ServerPlayNetworkHandlerChatMixin {

  @Shadow public ServerPlayerEntity player;

  @Inject(
      method = {
        "onGameMessage(Lnet/minecraft/network/packet/c2s/play/ChatMessageC2SPacket;)V",
        "onChatMessage(Lnet/minecraft/network/message/SignedMessage;)V",
        "handleChatMessage(Lnet/minecraft/network/message/SignedMessage;)V"
      },
      at = @At("HEAD"),
      cancellable = true)
  private void authCore$onChat(CallbackInfo ci) {

    UUID uuid = player.getUuid();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    if (user != null && user.isInLobby.get() && !AuthCoreServer.config.lobby.allowChat) {

      AuthCoreServer.LOGGER.toUser(
          false, user.connection, AuthCoreServer.messages.promptUserChatNotAllowed);

      ci.cancel();
    }
  }
}
