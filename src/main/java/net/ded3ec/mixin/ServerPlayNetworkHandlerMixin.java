package net.ded3ec.mixin;

import net.ded3ec.models.Config;
import net.ded3ec.models.Lobby;
import net.ded3ec.models.Messages;
import net.ded3ec.util.Logger;

import java.util.UUID;

import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.User;

/*? if fabric {*/
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
/*?}*/
/*? if < 1.20.2 {*/
/*import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundClientInformationPacket;
*//*?} else {*/
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
/*?}*/
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/*? if fabric {*/
  @Environment(EnvType.SERVER)
  /*?}*/
@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerPlayNetworkHandlerMixin {

  @Shadow public ServerPlayer player;

  @Inject(method = "handleMovePlayer", at = @At("HEAD"))
  private void authCore$onPlayerMove(ServerboundMovePlayerPacket packet, CallbackInfo ci) {

    UUID uuid = player.getUUID();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    // ClientGuard: movement-rate accounting (flood detection).
    net.ded3ec.security.ClientGuard.recordMove(player);

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

  /** Client settings watchdog: real clients always send this packet right after joining. */
  @Inject(method = "handleClientInformation", at = @At("HEAD"), require = 0)
  private void authCore$onClientInformation(
      ServerboundClientInformationPacket packet, CallbackInfo ci) {
    net.ded3ec.security.ClientGuard.recordSettings(player);
  }

  /** Custom payloads: brand capture, rate/oversize caps, companion attestation messages. */
  @Inject(method = "handleCustomPayload", at = @At("HEAD"), require = 0)
  private void authCore$onCustomPayload(
      ServerboundCustomPayloadPacket packet, CallbackInfo ci) {

    net.ded3ec.security.ClientGuard.Profile profile =
        net.ded3ec.security.ClientGuard.profile(player);
    if (profile == null) return;

    String channel = readChannel(packet);
    if (channel == null) return;
    String data = net.ded3ec.compat.Compat.readCustomPayloadData(packet);

    net.ded3ec.security.ClientGuard.recordPayload(
        player, channel, data != null ? data.length() : 0);

    if ("minecraft:brand".equals(channel) && data != null)
      net.ded3ec.security.ClientGuard.recordBrand(player, data);

    if (AuthCoreServer.config != null
        && AuthCoreServer.config.session.interop.enabled
        && channel.equals(AuthCoreServer.config.session.interop.channel)
        && data != null)
      net.ded3ec.network.AuthInterop.handleCompanion(player, data);
  }

  /** Command-tab-completion probing in the lobby is a reconnaissance signal. */
  @Inject(method = "handleCommandSuggestions", at = @At("HEAD"), require = 0)
  private void authCore$onCommandSuggestions(
      ServerboundCommandSuggestionPacket packet, CallbackInfo ci) {

    User user = User.getUser(player.getName().getString(), player.getUUID());
    if (user != null && user.isInLobby.get())
      net.ded3ec.security.ClientGuard.recordTabProbe(player);
  }

  /**
   * Reads the channel identifier from a custom payload packet across every version
   * (getName on 1.20.2+, getIdentifier before that).
   */
  private static String readChannel(Object packet) {
    for (String m : new String[] {"getName", "getIdentifier"}) {
      try {
        Object value = packet.getClass().getMethod(m).invoke(packet);
        if (value != null) return value.toString();
      } catch (ReflectiveOperationException ignored) {
        // try the next name
      }
    }
    return null;
  }
}
