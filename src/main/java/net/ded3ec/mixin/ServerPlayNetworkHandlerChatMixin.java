package net.ded3ec.mixin;

import java.util.UUID;

import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.User;

/*? if fabric {*/
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
/*?}*/
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
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
/*? if fabric {*/
  @Environment(EnvType.SERVER)
  /*?}*/
@Pseudo
@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerPlayNetworkHandlerChatMixin {

  @Shadow public ServerPlayer player;

  @Inject(
      method = {
        "onGameMessage(Lnet/minecraft/network/protocol/game/ServerboundChatPacket;)V",
        "onChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;)V",
        "handleChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;)V"
      },
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$onChat(CallbackInfo ci) {

    UUID uuid = player.getUUID();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    // ClientGuard: chat-rate accounting (flood detection in the lobby).
    net.ded3ec.security.ClientGuard.recordChat(player, false);

    if (user != null && user.isInLobby.get() && !AuthCoreServer.config.lobby.allowChat) {

      AuthCoreServer.LOGGER.toUser(
          false, user.connection, AuthCoreServer.messages.promptUserChatNotAllowed);

      ci.cancel();
    }
  }
}
