package in.potenfyr.authcore.mixin;

import in.potenfyr.authcore.util.ReflectCache;

import in.potenfyr.authcore.models.Lobby;


import in.potenfyr.authcore.AuthCoreServer;
import in.potenfyr.authcore.models.User;

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
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
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

    if (in.potenfyr.authcore.models.Lobby.users.isEmpty()) return; // fast path: nobody in limbo
    User user = User.getUser(player);

    // ClientGuard: movement-rate accounting (flood detection) + look-rotation tracking
    // (bots never rotate - the human-verification observation uses this).
    in.potenfyr.authcore.security.ClientGuard.recordMove(player);
    try {
      if (packet.hasRotation()) {
        // Pass rotation deltas for bot-pattern detection (best-effort; some 1.16 versions
        // lack getXRot/getYRot - those fall through silently).
        try {
          in.potenfyr.authcore.security.ClientGuard.recordLook(
              player, packet.getXRot(0.0f), packet.getYRot(0.0f));
        } catch (Throwable ignored) {
          in.potenfyr.authcore.security.ClientGuard.recordLook(player);
        }
      }
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
        in.potenfyr.authcore.security.SecurityLog.log(
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
    if (in.potenfyr.authcore.models.Lobby.users.isEmpty()) return; // fast path: nobody in limbo
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
   * Inventory-click guard. The lobby config promises "EVERY inventory click is blocked" while
   * unauthenticated, but the only enforcement used to be {@code Inventory.removeItem} (keyed on
   * allow-item-drop) - with allow-item-drop enabled (and default hide-inventory=false) an
   * unauthenticated player could move/equip items and hand-craft in the 2x2 grid. Cancel the raw
   * container click instead and resync the client.
   */
  @Inject(
      method =
          "handleContainerClick(Lnet/minecraft/network/protocol/game/ServerboundContainerClickPacket;)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$onContainerClick(
      ServerboundContainerClickPacket packet, CallbackInfo ci) {

    if (player == null) return;
    if (in.potenfyr.authcore.models.Lobby.users.isEmpty()) return; // fast path: nobody in limbo
    User user = User.getUser(player);
    if (user == null || !user.isInLobby.get()) return;

    // ClientGuard click-flood accounting (lobby.click-rate): the detector existed but was never
    // fed, so the CLICK_FLOOD signal could not fire.
    try {
      in.potenfyr.authcore.security.ClientGuard.recordClick(player);
    } catch (RuntimeException ignored) {
      // detection is advisory - never break the click guard
    }

    boolean block = !AuthCoreServer.config.lobby.allowItemMoving;
    if (!block && !AuthCoreServer.config.lobby.allowCrafting)
      block = isPlayerCraftingClick(packet);
    if (!block) return;

    ci.cancel();
    try {
      // Keep the client in sync after the cancelled click (otherwise the ghost items the
      // click would have created desync the inventory UI).
      player.containerMenu.broadcastChanges();
    } catch (RuntimeException ignored) {
      // resync is best-effort
    }
  }

  /** True for a click on the player's own 2x2 crafting grid or its result slot. */
  private boolean isPlayerCraftingClick(Object packet) {
    if (player == null || player.containerMenu == null) return false;
    // Version-agnostic: the player inventory menu's simple class name is stable ("InventoryMenu").
    if (!player.containerMenu.getClass().getSimpleName().contains("InventoryMenu")) return false;
    int slot = readClickSlot(packet);
    return slot >= 0 && slot <= 4; // 0 = result slot, 1..4 = the 2x2 grid
  }

  /** Reads the clicked slot index across mapping eras ({@code slotNum()} / {@code getSlot()}). */
  private static int readClickSlot(Object packet) {
    // Direct, build-remapped accessor first (intermediary survives the getSlotNum -> slotNum record
    // rename). The name-only lookup below never resolved on Fabric/Forge, so the player's own 2x2
    // crafting grid was never recognised there and lobby.allow-crafting had no effect.
    if (packet instanceof net.minecraft.network.protocol.game.ServerboundContainerClickPacket click) {
      try {
        /*? if >= 1.21.5 {*/
        return click.slotNum();
        /*?} else {*/
        /*return click.getSlotNum();
        *//*?}*/
      } catch (RuntimeException | LinkageError otherName) {
        // Mojang-named runtime on the other side of the rename - by name below
      }
    }
    for (String m : new String[] {"slotNum", "getSlotNum", "getSlot"}) {
      try {
        Object value = in.potenfyr.authcore.util.ReflectCache.method(packet.getClass(), m).invoke(packet);
        if (value instanceof Integer i) return i;
        if (value instanceof Short s) return s;
      } catch (ReflectiveOperationException | RuntimeException ignored) {
        // try the next accessor
      }
    }
    return -1;
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
    if (in.potenfyr.authcore.models.Lobby.users.isEmpty()) return; // fast path: nobody in limbo
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
/*? if >= 1.19 {*/
  @Inject(
      method =
          "handleChatCommand(Lnet/minecraft/network/protocol/game/ServerboundChatCommandPacket;)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$onCommandPacket(
      net.minecraft.network.protocol.game.ServerboundChatCommandPacket packet, CallbackInfo ci) {

    if (player == null) return;
    if (in.potenfyr.authcore.models.Lobby.users.isEmpty()) return; // fast path: nobody in limbo
    User user = User.getUser(player);
    if (user == null || !user.isInLobby.get()) return;

    String command = readCommandString(packet);
    if (!in.potenfyr.authcore.security.Security.isCommandAllowedInLobby(user, command)) {
      in.potenfyr.authcore.AuthCoreServer.LOGGER.violation(
          false,
          user,
          player.connection,
          AuthCoreServer.messages.promptUserCommandExecutionNotAllowed,
          command == null ? "?" : command.split(" ")[0].toLowerCase());

      ci.cancel();
    }
  }
/*?}*/

  /**
   * Packet-level gate for SIGNED chat commands (1.19.1+). Same second layer as the unsigned
   * handler above: the dispatcher mixins block commands too, but a version whose dispatcher
   * injection silently misses must not also lose the packet-level defense.
   */
  /*? if >= 1.19.1 {*/
  @Inject(
      method =
          "handleSignedChatCommand(Lnet/minecraft/network/protocol/game/ServerboundChatCommandSignedPacket;)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$onSignedCommandPacket(
      net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket packet,
      CallbackInfo ci) {

    if (player == null) return;
    if (in.potenfyr.authcore.models.Lobby.users.isEmpty()) return; // fast path: nobody in limbo
    User user = User.getUser(player);
    if (user == null || !user.isInLobby.get()) return;

    String command = readCommandString(packet);
    if (!in.potenfyr.authcore.security.Security.isCommandAllowedInLobby(user, command)) {
      in.potenfyr.authcore.AuthCoreServer.LOGGER.violation(
          false,
          user,
          player.connection,
          AuthCoreServer.messages.promptUserCommandExecutionNotAllowed,
          command == null ? "?" : command.split(" ")[0].toLowerCase());
      ci.cancel();
    }
  }
  /*?}*/

  /** Reads the raw command string from a ServerboundCommandPacket across eras. */
  private static String readCommandString(Object packet) {
    if (packet == null) return "";
    for (String m : new String[] {"command", "getCommand"}) {
      try {
        Object value = in.potenfyr.authcore.util.ReflectCache.method(packet.getClass(), m).invoke(packet);
        if (value instanceof String s) return s;
      } catch (Throwable ignored) {
        // try the next accessor
      }
    }
    for (java.lang.reflect.Field f : packet.getClass().getDeclaredFields()) {
      try {
        if (f.getType() == String.class) {
          f.setAccessible(true);
          Object value = f.get(packet);
          if (value instanceof String s) return s;
        }
      } catch (Throwable ignored) {}
    }
    for (java.lang.reflect.Method m : packet.getClass().getDeclaredMethods()) {
      try {
        if (m.getParameterCount() == 0 && m.getReturnType() == String.class) {
          m.setAccessible(true);
          Object value = m.invoke(packet);
          if (value instanceof String s) return s;
        }
      } catch (Throwable ignored) {}
    }
    return "";
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

    if (in.potenfyr.authcore.models.Lobby.users.isEmpty()) return; // fast path: nobody in limbo
    User user = User.getUser(player);

    if (user != null
        && user.isInLobby.get()
        && !AuthCoreServer.config.lobby.allowCrafting) {
      in.potenfyr.authcore.compat.Compat.forceCloseInventory(player);
      ci.cancel();
    }
  }

/*? if fabric || >= 1.20.2 {*/
/*? if < 1.20.2 {*/
/*  @Inject(
      method = "handleClientInformation(Lnet/minecraft/network/protocol/game/ServerboundClientInformationPacket;)V",
      at = @At("HEAD"),
      require = 0)
  private void authCore$onClientInformationLegacy(
      net.minecraft.network.protocol.game.ServerboundClientInformationPacket packet,
      CallbackInfo ci) {
    onClientInfo();
  }

  @Inject(
      method = "handleCustomPayload(Lnet/minecraft/network/protocol/game/ServerboundCustomPayloadPacket;)V",
      at = @At("HEAD"),
      require = 0)
  private void authCore$onCustomPayloadLegacy(
      net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket packet,
      CallbackInfo ci) {
    onPayload(packet);
  }
*//*?} else {*/
  /** Client settings watchdog: real clients always send this packet right after joining. */
  @Inject(
      method = "handleClientInformation(Lnet/minecraft/network/protocol/common/ServerboundClientInformationPacket;)V",
      at = @At("HEAD"),
      require = 0)
  private void authCore$onClientInformation(
      ServerboundClientInformationPacket packet, CallbackInfo ci) {
    onClientInfo();
  }

  /** Custom payloads: brand capture, rate/oversize caps, companion attestation messages. */
  @Inject(
      method = "handleCustomPayload(Lnet/minecraft/network/protocol/common/ServerboundCustomPayloadPacket;)V",
      at = @At("HEAD"),
      require = 0)
  private void authCore$onCustomPayload(
      ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
    onPayload(packet);
  }
/*?}*/

  private void onClientInfo() {
    in.potenfyr.authcore.security.ClientGuard.recordSettings(player);
    in.potenfyr.authcore.events.ServerEvents.trackPacket(
        player.connection, "SETTINGS");
  }

  private void onPayload(Object packet) {
    in.potenfyr.authcore.security.ClientGuard.Profile profile =
        in.potenfyr.authcore.security.ClientGuard.profile(player);
    if (profile == null) return;

    String channel = readChannel(packet);
    if (channel == null) return;
    String data = in.potenfyr.authcore.compat.Compat.readCustomPayloadData(packet);

    in.potenfyr.authcore.security.ClientGuard.recordPayload(
        player, channel, data != null ? data.length() : 0);

    if ("minecraft:brand".equals(channel) && data != null)
      in.potenfyr.authcore.security.ClientGuard.recordBrand(player, data);

    if (AuthCoreServer.config != null
        && AuthCoreServer.config.session.interop.enabled
        && channel.equals(AuthCoreServer.config.session.interop.channel)
        && data != null)
      in.potenfyr.authcore.network.AuthInterop.handleCompanion(player, data);
  }
/*?}*/

  /** Command-tab-completion probing in the lobby is a reconnaissance signal. */
  @Inject(
      method =
          "handleCustomCommandSuggestions(Lnet/minecraft/network/protocol/game/ServerboundCommandSuggestionPacket;)V",
      at = @At("HEAD"),
      require = 0)
  private void authCore$onCommandSuggestions(
      ServerboundCommandSuggestionPacket packet, CallbackInfo ci) {

    if (in.potenfyr.authcore.models.Lobby.users.isEmpty()) return; // fast path: nobody in limbo
    User user = User.getUser(player);
    if (user != null && user.isInLobby.get())
      in.potenfyr.authcore.security.ClientGuard.recordTabProbe(player);
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

    if (player == null) return;
    if (in.potenfyr.authcore.models.Lobby.users.isEmpty()) return; // fast path: nobody in limbo
    User user = User.getUser(player);
    if (user == null || !user.isInLobby.get()) return;

    ServerboundPlayerActionPacket.Action action = packet.getAction();

    // Offhand swap restriction.
    if (action == ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND) {
      if (!AuthCoreServer.config.lobby.allowItemSwapping) {
        AuthCoreServer.LOGGER.violation(
            false,
            user,
            user.connection, AuthCoreServer.messages.promptUserShiftItemNotAllowed);
        ci.cancel();
      }
      return;
    }

    // Drop key (Q / Ctrl+Q). Blocking the raw packet covers every version - including 26.3,
    // where ServerPlayer.drop()'s descriptor changed - so no version-specific mixin descriptor is
    // needed for the drop restriction.
    if ((action == ServerboundPlayerActionPacket.Action.DROP_ITEM
            || action == ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS)
        && !AuthCoreServer.config.lobby.allowItemDrop) {
      AuthCoreServer.LOGGER.violation(
          false,
          user,
          user.connection, AuthCoreServer.messages.promptUserDropItemNotAllowed);
      ci.cancel();
      try {
        // The client predicted the drop; resync so the item reappears in the inventory UI.
        player.containerMenu.broadcastChanges();
      } catch (RuntimeException ignored) {
        // resync is best-effort
      }
    }
  }

  /**
   * Reads the channel identifier from a custom payload packet across every version
   * (getName on 1.20.2+, getIdentifier before that).
   */
  private static String readChannel(Object packet) {
    for (String m : new String[] {"getName", "getIdentifier"}) {
      try {
        Object value = in.potenfyr.authcore.util.ReflectCache.method(packet.getClass(), m).invoke(packet);
        if (value != null) return value.toString();
      } catch (ReflectiveOperationException ignored) {
        // try the next name
      }
    }
    return null;
  }
}
