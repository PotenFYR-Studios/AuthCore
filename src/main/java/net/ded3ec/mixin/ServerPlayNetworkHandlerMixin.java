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
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
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
@SuppressWarnings({"mapping", "unresolvable-target"})
abstract class ServerPlayNetworkHandlerMixin {

  @Shadow public ServerPlayer player;

  @Inject(
      method = "handleMovePlayer(Lnet/minecraft/network/protocol/game/ServerboundMovePlayerPacket;)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$onPlayerMove(ServerboundMovePlayerPacket packet, CallbackInfo ci) {

    User user = User.getUser(player);

    // ClientGuard: movement-rate accounting (flood detection) + look-rotation tracking
    // (bots never rotate - the human-verification observation uses this).
    net.ded3ec.security.ClientGuard.recordMove(player);
    try {
      if (packet.hasRotation()) net.ded3ec.security.ClientGuard.recordLook(player);
    } catch (Throwable ignored) {
      // look tracking is best-effort (hasRotation() is missing on some 1.16 patch versions)
    }
    if (user != null
        && user.isInLobby.get()
        && !AuthCoreServer.config.lobby.allowMovement
        && user.lobby.isOutsideOfLobbyPos(
            packet.getX(player.getX()), packet.getY(player.getY()), packet.getZ(player.getZ()))) {

      long now = System.currentTimeMillis();

      // Join-sync grace window: for the first moments after the lobby lock the client
      // legitimately lags the anchor while its spawn position syncs (join sequence,
      // teleport resync). The packet is STILL cancelled - only the violation message and
      // snap-back are deferred, so a missing move-cancel mixin can never widen this into
      // a real movement window.
      long graceMs = AuthCoreServer.config.lobby.movementGracePeriodMs;
      if (graceMs > 0 && now - user.lobby.lockedAtMs < graceMs) {
        ci.cancel();
        return;
      }

      // Vertical-drift detection: Y-only movement packets (vertical fly hacks) previously
      // slipped past the X/Z-only check - the client kept rendering a climb while the
      // server entity stayed anchored. Every axis is now compared and logged.
      if (Math.abs(packet.getY(player.getY()) - player.getY()) > 0.5)
        net.ded3ec.security.SecurityLog.log(
            "LIMBO_VERTICAL_FLIGHT",
            player.getName().getString() + " vertical movement attempted in limbo (Y="
                + player.getY() + " -> " + packet.getY(player.getY()) + ")");

      // Throttle the movement message to one per second - a stream of packets must not
      // spam the chat.
      if (user.lastMovementWarningMs == 0 || now - user.lastMovementWarningMs >= 1000) {
        user.lastMovementWarningMs = now;
        // Message-only feedback, NOT a counted violation: movement packets are a continuous
        // stream (20/s during the join/spawn sequence and falling), so counting them would
        // kick every player the moment they join. The kick-limit counter is reserved for
        // discrete violations (attacks, block breaks, clicks, chat, commands...).
        AuthCoreServer.LOGGER.toUser(
            false, user.connection, AuthCoreServer.messages.promptUserPlayerMovementNotAllowed);
      }

      // Cancel the packet BEFORE vanilla processes it: the server entity never moves, so
      // vanilla's own movement validation ("moved wrongly" / "moved too quickly") must not
      // see the client-driven position delta and flag the player as a hacker.
      ci.cancel();

      // Distance + throttle-based client correction: snap the client back to the anchor only
      // when it drifted beyond the configured radius - and at most once per correction
      // interval (teleportBack throttles internally). The old per-packet snap-back vibrated
      // the screen at up to 20Hz; between corrections the client's local view ghost-walks
      // slightly (the classic AuthMe behavior).
      if (user.lobby.isFarFromLobbyPos(
          packet.getX(player.getX()),
          packet.getY(player.getY()),
          packet.getZ(player.getZ()),
          AuthCoreServer.config.lobby.movementCorrectionRadius))
        user.lobby.teleportBack();
    }
  }

  /**
   * Vehicle-movement bypass: ride-move packets are NOT covered by handleMovePlayer - a
   * lobby player riding a boat/minecart (or a hacked client spoofing the packet) could
   * otherwise move freely. The vehicle entity is anchored server-side just like the player.
   */
  @Inject(
      method =
          "handleMoveVehicle(Lnet/minecraft/network/protocol/game/ServerboundMoveVehiclePacket;)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$onMoveVehicle(
      net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket packet, CallbackInfo ci) {
    if (player == null) return;
    User user = User.getUser(player);
    if (user == null || !user.isInLobby.get()) return;
    if (!AuthCoreServer.config.lobby.allowMovement) {
      long graceMs = AuthCoreServer.config.lobby.movementGracePeriodMs;
      if (graceMs > 0
          && System.currentTimeMillis() - user.lobby.lockedAtMs < graceMs) {
        ci.cancel();
        return;
      }
      ci.cancel();
      user.lobby.teleportBack();
    }
  }

  /**
   * Creative-inventory slot packets are blocked in the lobby REGARDLESS of game mode:
   * normally {@code force-adventure-mode} makes vanilla reject them itself, but with that
   * setting disabled an op-in-creative joining the limbo could otherwise spawn arbitrary
   * items straight into their inventory - bypassing every other restriction.
   */
  @Inject(
      method =
          "handleSetCreativeModeSlot(Lnet/minecraft/network/protocol/game/ServerboundSetCreativeModeSlotPacket;)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$onCreativeSlot(
      net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket packet,
      CallbackInfo ci) {

    if (player == null) return;
    User user = User.getUser(player);
    if (user != null && user.isInLobby.get()) ci.cancel();
  }

  /**
   * PACKET-level command gate (independent second layer behind the Brigadier dispatcher
   * mixin): cancels the raw {@code ServerboundCommandPacket} before vanilla dispatches it.
   * If the dispatcher injection ever silently misses on an unmapped future version, this
   * layer still blocks every non-whitelisted command for lobby players - both layers share
   * {@code Security.isCommandAllowedInLobby}.
   */
  @Inject(
      method =
          "handleCommand(Lnet/minecraft/network/protocol/game/ServerboundCommandPacket;)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$onCommandPacket(
      Object packet, CallbackInfo ci) {

    // NOTE: the packet parameter is deliberately typed Object - the command packet class
    // moved between mapping packages across versions (protocol.game vs protocol.common),
    // and a hard reference breaks compilation on one side of the range. The @Inject
    // descriptor above is matched at RUNTIME (require = 0), and the command string is
    // already read reflectively below, so no concrete type is needed here.

    if (player == null) return;
    User user = User.getUser(player);
    if (user == null || !user.isInLobby.get()) return;

    String command = readCommandString(packet);
    if (!net.ded3ec.security.Security.isCommandAllowedInLobby(user, command)) {
      net.ded3ec.AuthCoreServer.LOGGER.violation(
          false,
          user,
          player.connection,
          AuthCoreServer.messages.promptUserCommandExecutionNotAllowed,
          command == null ? "?" : command.split(" ")[0].toLowerCase());
      ci.cancel();
    }
  }

  /** Reads the command string from the packet (record accessor or getter, by version). */
  private static String readCommandString(Object packet) {
    for (String m : new String[] {"command", "getCommand"}) {
      try {
        Object value = packet.getClass().getMethod(m).invoke(packet);
        if (value instanceof String s) return s;
      } catch (ReflectiveOperationException | RuntimeException ignored) {
        // try the next accessor
      }
    }
    return null;
  }

  /**
   * Blocks recipe-book auto-placement in the lobby. The recipe book
   * ({@code ServerboundPlaceRecipePacket}) bypasses the slot-click guards entirely and
   * moves items into the crafting grid server-side - this was both a crafting hole and
   * the trigger of the server-thread hang (the Inventory.removeItem drop-mixin fired a
   * title send from inside the placement).
   */
  @Inject(
      method =
          "handlePlaceRecipe(Lnet/minecraft/network/protocol/game/ServerboundPlaceRecipePacket;)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$onPlaceRecipe(
      net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket packet, CallbackInfo ci) {

    User user = User.getUser(player);

    if (user != null
        && user.isInLobby.get()
        && !AuthCoreServer.config.lobby.allowCrafting) {
      net.ded3ec.compat.Compat.forceCloseInventory(player);
      ci.cancel();
    }
  }

  /** Client settings watchdog: real clients always send this packet right after joining. */
  @Inject(
      method =
          /*? if < 1.20.2 {*/
          "handleClientInformation(Lnet/minecraft/network/protocol/game/ServerboundClientInformationPacket;)V",
          /*?} else {*/
          /*"handleClientInformation(Lnet/minecraft/network/protocol/common/ServerboundClientInformationPacket;)V",
          *//*?}*/
      at = @At("HEAD"),
      require = 0)
  private void authCore$onClientInformation(
      ServerboundClientInformationPacket packet, CallbackInfo ci) {
    net.ded3ec.security.ClientGuard.recordSettings(player);
  }

  /** Custom payloads: brand capture, rate/oversize caps, companion attestation messages. */
  @Inject(
      method =
          /*? if < 1.20.2 {*/
          "handleCustomPayload(Lnet/minecraft/network/protocol/game/ServerboundCustomPayloadPacket;)V",
          /*?} else {*/
          /*"handleCustomPayload(Lnet/minecraft/network/protocol/common/ServerboundCustomPayloadPacket;)V",
          *//*?}*/
      at = @At("HEAD"),
      require = 0)
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
  @Inject(
      method =
          "handleCommandSuggestions(Lnet/minecraft/network/protocol/game/ServerboundCommandSuggestionPacket;)V",
      at = @At("HEAD"),
      require = 0)
  private void authCore$onCommandSuggestions(
      ServerboundCommandSuggestionPacket packet, CallbackInfo ci) {

    User user = User.getUser(player);
    if (user != null && user.isInLobby.get())
      net.ded3ec.security.ClientGuard.recordTabProbe(player);
  }

  /** Blocks offhand-item swapping in the lobby (item-swapping restriction). */
  @Inject(
      method =
          "handlePlayerAction(Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket;)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$onPlayerAction(
      ServerboundPlayerActionPacket packet, CallbackInfo ci) {

    if (packet.getAction() != ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND)
      return;

    User user = User.getUser(player);

    if (user != null
        && user.isInLobby.get()
        && !AuthCoreServer.config.lobby.allowItemSwapping) {

      AuthCoreServer.LOGGER.violation(
          false,
          user,
          user.connection, AuthCoreServer.messages.promptUserShiftItemNotAllowed);
      ci.cancel();
    }
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
