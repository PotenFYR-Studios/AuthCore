package in.potenfyr.authcore.mixin;


import in.potenfyr.authcore.events.EntityEvents;
import in.potenfyr.authcore.models.User;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Packet-level limbo guard for entity ATTACKS and INTERACTIONS.
 *
 * <p>The attack/use restrictions previously lived ONLY in Fabric-API callbacks, so
 * Forge and NeoForge builds had NO enforcement at all: an unauthenticated lobby player
 * could melee-attack players/mobs freely, break armor stands/item frames and right-click
 * entities (villager trades, chest boats...). This mixin intercepts the raw
 * {@code ServerboundInteractPacket} on every loader - the packet every attack and entity
 * interaction rides - and applies the exact same per-entity-type rules through
 * {@link EntityEvents#guardLobbyAttack} / {@link EntityEvents#guardLobbyUse}.
 *
 * <p>Fail-closed: when the action type cannot be determined reflectively on an unknown
 * future version, the packet is cancelled while the player is in the lobby (the default
 * configuration blocks these actions anyway).
 */
/*? if fabric {*/
@net.fabricmc.api.Environment(net.fabricmc.api.EnvType.SERVER)
/*?}*/
@Mixin(ServerGamePacketListenerImpl.class)
@SuppressWarnings({"mapping", "unresolvable-target"})
abstract class ServerInteractPacketMixin {

  @Shadow public ServerPlayer player;

  @Inject(
      method =
          "handleInteract(Lnet/minecraft/network/protocol/game/ServerboundInteractPacket;)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$onInteract(
      net.minecraft.network.protocol.game.ServerboundInteractPacket packet, CallbackInfo ci) {

    if (player == null) return;
    if (in.potenfyr.authcore.models.Lobby.users.isEmpty()) return; // fast path: nobody in limbo
    User user = User.getUser(player);
    if (user == null || !user.isInLobby.get()) return;

    String actionKind = readActionKind(packet);

    // Unknown shape on a future version -> fail closed.
    if (actionKind == null) {
      in.potenfyr.authcore.AuthCoreServer.LOGGER.violation(
          false,
          user,
          user.connection,
          in.potenfyr.authcore.AuthCoreServer.messages.promptUserInteractEntityNotAllowed);
      ci.cancel();
      return;
    }

    Object target = readTargetEntity(packet);

    boolean blocked;
    if ("ATTACK".equals(actionKind)) {
      blocked =
          target == null || EntityEvents.guardLobbyAttack(user, (net.minecraft.world.entity.Entity) target);
    } else {
      blocked =
          target == null || EntityEvents.guardLobbyUse(user, (net.minecraft.world.entity.Entity) target);
    }

    if (blocked) ci.cancel();
  }

  // NOTE: 26.1+ split melee attacks out of ServerboundInteractPacket into a dedicated
  // ServerboundAttackPacket, which this packet never sees. That path is guarded at the
  // version-stable ATTACK METHOD level (PlayerAttackMixin / ServerPlayerMixin) instead of here:
  // a mixin descriptor naming the 26.x-only packet class could fail to apply on 26.1/26.2 if the
  // class were absent there, and mixin descriptors must only reference classes present across the
  // whole supported range.

  /** The packet's action kind - see {@link in.potenfyr.authcore.compat.InteractPackets}. */
  private static String readActionKind(
      net.minecraft.network.protocol.game.ServerboundInteractPacket packet) {
    return in.potenfyr.authcore.compat.InteractPackets.actionKind(packet);
  }

  /** The targeted entity (null when unresolvable - treated as blocked). */
  private Object readTargetEntity(net.minecraft.network.protocol.game.ServerboundInteractPacket packet) {
    return in.potenfyr.authcore.compat.InteractPackets.target(packet, player);
  }
}
