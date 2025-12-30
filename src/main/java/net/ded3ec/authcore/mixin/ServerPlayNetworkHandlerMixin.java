package net.ded3ec.authcore.mixin;

import net.ded3ec.authcore.AuthCore;
import net.ded3ec.authcore.models.User;
import net.ded3ec.authcore.utils.Logger;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Server Play Network Handler for AuthCore in Minecraft Functions/Events! */
@Mixin(ServerPlayNetworkHandler.class)
abstract class ServerPlayNetworkHandlerMixin {

  /** Mixin player object shadowing. */
  @Shadow public ServerPlayerEntity player;

  /**
   * Player moving event in the server!
   *
   * @param packet the player move packet
   * @param ci callback info
   */
  @Inject(method = "onPlayerMove", at = @At("HEAD"))
  private void authCore$onPlayerMove(PlayerMoveC2SPacket packet, CallbackInfo ci) {
    User user = User.users.get(this.player.getName().getString());

    if (user != null
        && user.isInLobby.get()
        && !AuthCore.config.lobby.allowMovement
        && user.lobby.isOutsideOfLobbyPos(packet.getX(player.getX()), packet.getZ(player.getZ()))) {
      Logger.toUser(false, user.handler, AuthCore.messages.promptUserPlayerMovementNotAllowed);
      user.lobby.teleportToLobby();
    }
  }
}
