package in.potenfyr.authcore.mixin;

import in.potenfyr.authcore.AuthCoreServer;
import in.potenfyr.authcore.models.User;
import in.potenfyr.authcore.security.LobbyPacketPolicy;
import in.potenfyr.authcore.security.SecurityLog;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Second-layer lobby packet gate for {@link ServerGamePacketListenerImpl} handlers that have a real
 * side effect and were previously reachable by an unauthenticated (or hacked-client) player:
 *
 * <ul>
 *   <li>{@code handleUseItem} - ender pearls/chorus fruit teleport the player server-side (a
 *       movement escape hatch the move-packet guard cannot see), consumables/fireworks apply
 *       effects.
 *   <li>{@code handleEditBook} / {@code handleRenameItem} - mutate item data (NBT) straight through
 *       the container menus.
 *   <li>{@code handleContainerButtonClick} - enchanting/anvil button actions that generate or
 *       consume items.
 *   <li>{@code handleSelectTrade} - villager trades materialise items.
 *   <li>{@code handleSignUpdate} - writes a block entity in the world.
 *   <li>{@code handlePickItem*} - creative pick-block creates items from nothing.
 *   <li>{@code handleClientCommand} - respawn request (can relocate the player away from the limbo
 *       anchor, e.g. after a scripted death).
 *   <li>{@code handleTeleportToEntityPacket} / {@code handlePlayerCommand} (gliding) - movement
 *       helpers that bypass the raw move-packet guard.
 * </ul>
 *
 * <p>Decisions come from {@link LobbyPacketPolicy} (pure and covered by the in-mod simulation), so
 * the configuration matrix behaves exactly as documented. Every injection uses an exact descriptor
 * and {@code require = 0}: a method that does not exist on a future version is skipped instead of
 * crashing the server, and every body is wrapped so a malformed packet can never throw out of the
 * handler. When the decision cannot be evaluated the packet is cancelled (fail closed) - an
 * unauthenticated player has no legitimate reason to send it.
 */
/*? if fabric {*/
@net.fabricmc.api.Environment(net.fabricmc.api.EnvType.SERVER)
/*?}*/
@Mixin(ServerGamePacketListenerImpl.class)
@SuppressWarnings({"mapping", "unresolvable-target"})
abstract class ServerPlayNetworkHandlerLobbyGuardMixin {

  @Shadow public ServerPlayer player;

  /** Per-player log throttle: {lastLogMs, suppressedCount}. A hacked client can spam a blocked
   *  packet at 20 Hz - writing a security-log line for each one would bury real events and hammer
   *  the disk, so blocks are summarised at most once every few seconds. */
  private static final java.util.Map<java.util.UUID, long[]> LOG_THROTTLE =
      new java.util.concurrent.ConcurrentHashMap<>();
  private static final long LOG_INTERVAL_MS = 5_000L;
  private static final int LOG_THROTTLE_MAX_PLAYERS = 4_096;

  private static void logBlocked(
      ServerPlayer who, String packetName, LobbyPacketPolicy.Action action) {
    try {
      java.util.UUID id = who.getUUID();
      long now = System.currentTimeMillis();
      if (LOG_THROTTLE.size() > LOG_THROTTLE_MAX_PLAYERS) LOG_THROTTLE.clear();
      long[] entry = LOG_THROTTLE.computeIfAbsent(id, key -> new long[] {0L, 0L});
      String detail;
      synchronized (entry) {
        if (now - entry[0] < LOG_INTERVAL_MS) {
          entry[1]++;
          return;
        }
        long suppressed = entry[1];
        entry[0] = now;
        entry[1] = 0;
        detail = who.getName().getString() + " | " + packetName + " | "
            + LobbyPacketPolicy.describe(action);
        if (suppressed > 0) detail += " | suppressed=" + suppressed;
      }
      SecurityLog.log("LIMBO_PACKET_BLOCKED", detail);
    } catch (Throwable ignored) {
      // logging must never break packet handling
    }
  }

  @Inject(
      method =
          "handleUseItem(Lnet/minecraft/network/protocol/game/ServerboundUseItemPacket;)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$guardUseItem(
      net.minecraft.network.protocol.game.ServerboundUseItemPacket packet, CallbackInfo ci) {
    guard(LobbyPacketPolicy.Action.ITEM_USE, "USE_ITEM", ci, true);
  }

  /**
   * Block interaction/placement (right-click a block). The ServerPlayerGameMode inject covers the
   * actual placement, but it is a single descriptor that can silently miss on a future signature
   * change - this packet-level layer keeps the lobby closed even then. Descriptor-only (no packet
   * type reference) so it compiles on every era and simply skips where the handler is absent.
   */
  @Inject(
      method =
          "handleUseItemOn(Lnet/minecraft/network/protocol/game/ServerboundUseItemOnPacket;)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$guardUseItemOn(CallbackInfo ci) {
    guard(LobbyPacketPolicy.Action.ITEM_USE, "USE_ITEM_ON", ci, false);
  }

  @Inject(
      method =
          "handleEditBook(Lnet/minecraft/network/protocol/game/ServerboundEditBookPacket;)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$guardEditBook(
      net.minecraft.network.protocol.game.ServerboundEditBookPacket packet, CallbackInfo ci) {
    guard(LobbyPacketPolicy.Action.ITEM_MUTATION, "EDIT_BOOK", ci, false);
  }

  @Inject(
      method =
          "handleRenameItem(Lnet/minecraft/network/protocol/game/ServerboundRenameItemPacket;)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$guardRenameItem(
      net.minecraft.network.protocol.game.ServerboundRenameItemPacket packet, CallbackInfo ci) {
    guard(LobbyPacketPolicy.Action.ITEM_MUTATION, "RENAME_ITEM", ci, true);
  }

  @Inject(
      method =
          "handleContainerButtonClick(Lnet/minecraft/network/protocol/game/ServerboundContainerButtonClickPacket;)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$guardContainerButton(
      net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket packet,
      CallbackInfo ci) {
    guard(LobbyPacketPolicy.Action.CONTAINER_ACTION, "CONTAINER_BUTTON", ci, true);
  }

  @Inject(
      method =
          "handleSelectTrade(Lnet/minecraft/network/protocol/game/ServerboundSelectTradePacket;)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$guardSelectTrade(
      net.minecraft.network.protocol.game.ServerboundSelectTradePacket packet, CallbackInfo ci) {
    guard(LobbyPacketPolicy.Action.TRADE, "SELECT_TRADE", ci, true);
  }

  @Inject(
      method =
          "handleSignUpdate(Lnet/minecraft/network/protocol/game/ServerboundSignUpdatePacket;)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$guardSignUpdate(
      net.minecraft.network.protocol.game.ServerboundSignUpdatePacket packet, CallbackInfo ci) {
    guard(LobbyPacketPolicy.Action.SIGN_EDIT, "SIGN_UPDATE", ci, false);
  }

  @Inject(
      method =
          "handleClientCommand(Lnet/minecraft/network/protocol/game/ServerboundClientCommandPacket;)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$guardClientCommand(
      net.minecraft.network.protocol.game.ServerboundClientCommandPacket packet, CallbackInfo ci) {
    // Only a dead player may respawn. Cancelling an alive player's request is a no-op in vanilla,
    // while cancelling a DEAD one would soft-lock the lobby - so only guard the alive case.
    try {
      if (player != null && player.getHealth() > 0.0F) {
        guard(LobbyPacketPolicy.Action.RESPAWN, "CLIENT_COMMAND", ci, false);
      }
    } catch (Throwable ignored) {
      // health unavailable - leave vanilla behaviour in place
    }
  }

  @Inject(
      method =
          "handleTeleportToEntityPacket(Lnet/minecraft/network/protocol/game/ServerboundTeleportToEntityPacket;)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$guardTeleportToEntity(
      net.minecraft.network.protocol.game.ServerboundTeleportToEntityPacket packet,
      CallbackInfo ci) {
    guard(LobbyPacketPolicy.Action.MOVEMENT, "TELEPORT_TO_ENTITY", ci, false);
  }

  /**
   * Player-command packet: only the gliding start is movement. Sneaking/sprinting must keep
   * working - the human-verification captcha measures real sneak/jump input.
   */
  @Inject(
      method =
          "handlePlayerCommand(Lnet/minecraft/network/protocol/game/ServerboundPlayerCommandPacket;)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$guardPlayerCommand(
      net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket packet, CallbackInfo ci) {
    try {
      LobbyPacketPolicy.Action action =
          LobbyPacketPolicy.classifyPlayerCommand(readActionName(packet));
      if (action != null) guard(action, "PLAYER_COMMAND", ci, false);
    } catch (Throwable malformed) {
      // A packet whose action cannot be decoded is not trusted: cancel it in the lobby.
      guard(LobbyPacketPolicy.Action.UNKNOWN, "PLAYER_COMMAND", ci, false);
    }
  }

  /*? if < 1.21.4 {*/
  /*@Inject(
      method =
          "handlePickItem(Lnet/minecraft/network/protocol/game/ServerboundPickItemPacket;)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$guardPickItem(
      net.minecraft.network.protocol.game.ServerboundPickItemPacket packet, CallbackInfo ci) {
    guard(LobbyPacketPolicy.Action.PICK_ITEM, "PICK_ITEM", ci, true);
  }
  *//*?} else {*/
  @Inject(
      method =
          "handlePickItemFromBlock(Lnet/minecraft/network/protocol/game/ServerboundPickItemFromBlockPacket;)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$guardPickItemFromBlock(
      net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket packet,
      CallbackInfo ci) {
    guard(LobbyPacketPolicy.Action.PICK_ITEM, "PICK_ITEM_BLOCK", ci, true);
  }

  @Inject(
      method =
          "handlePickItemFromEntity(Lnet/minecraft/network/protocol/game/ServerboundPickItemFromEntityPacket;)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$guardPickItemFromEntity(
      net.minecraft.network.protocol.game.ServerboundPickItemFromEntityPacket packet,
      CallbackInfo ci) {
    guard(LobbyPacketPolicy.Action.PICK_ITEM, "PICK_ITEM_ENTITY", ci, true);
  }
  /*?}*/

  /**
   * Shared gate: resolves the lobby user, applies the pure policy and cancels the packet before
   * vanilla acts on it. Never throws: a broken game object must not take the connection down.
   */
  private void guard(
      LobbyPacketPolicy.Action action, String packetName, CallbackInfo ci, boolean resync) {
    boolean blocked;
    boolean inLobby = false;
    try {
      if (player == null) return;
      if (in.potenfyr.authcore.models.Lobby.users.isEmpty()) return; // fast path
      User user = User.getUser(player);
      if (user == null || !user.isInLobby.get()) return;
      inLobby = true;
      blocked = LobbyPacketPolicy.blocked(action, AuthCoreServer.config.lobby);
    } catch (Throwable brokenState) {
      // Fail closed: in the lobby, an unevaluable packet is not allowed through.
      blocked = true;
    }
    if (!blocked) return;
    ci.cancel();
    if (inLobby && player != null) logBlocked(player, packetName, action);
    if (resync) {
      try {
        player.containerMenu.broadcastChanges();
      } catch (RuntimeException ignored) {
        // resync is best-effort
      }
    }
  }

  /** Reads the {@code Action} name of a player-command packet without a compile-time enum name. */
  private static String readActionName(Object packet) {
    try {
      Object action = in.potenfyr.authcore.util.ReflectCache.method(packet.getClass(), "getAction")
          .invoke(packet);
      return action == null ? null : action.toString();
    } catch (ReflectiveOperationException | RuntimeException unreadable) {
      return null; // unknown action -> fail closed in the lobby
    }
  }
}
