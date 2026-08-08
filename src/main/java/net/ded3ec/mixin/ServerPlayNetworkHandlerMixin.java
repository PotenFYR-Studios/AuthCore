package net.ded3ec.mixin;

import net.ded3ec.models.Config;
import net.ded3ec.models.Lobby;
import net.ded3ec.models.Messages;
import net.ded3ec.util.Logger;

import java.util.UUID;

import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.User;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.SERVER)
@Mixin(ServerPlayNetworkHandler.class)
abstract class ServerPlayNetworkHandlerMixin {

  @Shadow public ServerPlayerEntity player;

  @Inject(method = "onPlayerMove", at = @At("HEAD"))
  private void authCore$onPlayerMove(PlayerMoveC2SPacket packet, CallbackInfo ci) {

    UUID uuid = player.getUuid();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    if (user != null
        && user.isInLobby.get()
        && !AuthCoreServer.config.lobby.allowMovement
        && user.lobby.isOutsideOfLobbyPos(packet.getX(player.getX()), packet.getZ(player.getZ()))) {

      AuthCoreServer.LOGGER.toUser(
          false, user.connection, AuthCoreServer.messages.promptUserPlayerMovementNotAllowed);

      // Teleport player back to lobby spawn
      user.lobby.handleTeleport();
    }
  }
}
