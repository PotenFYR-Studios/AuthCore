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
 * on 1.16-1.20.4, the signed-message handler on 1.19.3+, {@code handleChat} on 26.x). All
 * descriptors are listed as injection targets; only the ones present on the running version
 * apply, and the restriction only depends on the player state.
 *
 * <p>On 1.16-1.18.2 chat commands are sent as chat packets starting with "/" - those must NOT
 * be cancelled here: the lobby command whitelist is enforced at the command dispatcher (auth
 * commands like /login and /register stay allowed). Only plain chat is blocked for lobby
 * players when {@code allowChat} is disabled.
 */
/*? if fabric {*/
  @Environment(EnvType.SERVER)
  /*?}*/
@Pseudo
@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerPlayNetworkHandlerChatMixin {

  @Shadow public ServerPlayer player;

  /** Modern (1.19.3+ / 26.x) chat handlers - commands are separate packets there. */
  @Inject(
      method = {
        "onChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;)V",
        "handleChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;)V",
        "handleChat(Lnet/minecraft/network/protocol/game/ServerboundChatPacket;)V" // 26.x
      },
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$onChat(CallbackInfo ci) {
    restrictChat(ci);
  }

  /** Legacy (1.16-1.18.2) chat handler - commands ride chat packets there. */
  @Inject(
      method = "onGameMessage(Lnet/minecraft/network/protocol/game/ServerboundChatPacket;)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$onChatLegacy(
      net.minecraft.network.protocol.game.ServerboundChatPacket packet, CallbackInfo ci) {

    // On 1.16-1.18.2 commands are chat packets starting with "/". Let them through - the
    // command dispatcher enforces the lobby whitelist (auth commands remain usable).
    String message = readChatMessage(packet);
    if (message != null && message.startsWith("/")) return;

    restrictChat(ci);
  }

  /** Shared restriction logic for every version. */
  private void restrictChat(CallbackInfo ci) {
    User user = User.getUser(player);

    // ClientGuard: chat-rate accounting (flood detection in the lobby).
    net.ded3ec.security.ClientGuard.recordChat(player, false);

    if (user != null && user.isInLobby.get() && !AuthCoreServer.config.lobby.allowChat) {

      AuthCoreServer.LOGGER.violation(
          false,
          user,
          user.connection, AuthCoreServer.messages.promptUserChatNotAllowed);

      ci.cancel();
    }
  }

  /** Reads the chat text from the packet across every version (message()/getMessage()). */
  private static String readChatMessage(Object packet) {
    if (packet == null) return null;
    for (String m : new String[] {"message", "getMessage", "getStrippedMessage"}) {
      try {
        Object value = packet.getClass().getMethod(m).invoke(packet);
        if (value instanceof String s) return s;
      } catch (ReflectiveOperationException ignored) {
        // try the next name
      }
    }
    return null;
  }
}
