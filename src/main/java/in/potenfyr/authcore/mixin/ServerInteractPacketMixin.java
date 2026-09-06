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

  /** Reads the enum name of the packet's action ("ATTACK" / "INTERACT" / "INTERACT_AT"). */
  private static String readActionKind(Object packet) {
    try {
      Object action = packet.getClass().getMethod("getAction").invoke(packet);
      if (action == null) return null;
      Object type = action.getClass().getMethod("getType").invoke(action);
      if (type instanceof Enum<?> enumType) {
        String name = enumType.name();
        if ("INTERACT_AT".equals(name)) return "INTERACT"; // same rules, hit-position variant
        return name;
      }
    } catch (ReflectiveOperationException | RuntimeException ignored) {
      // fall through - unknown packet shape
    }
    return null;
  }

  /** Resolves the targeted entity from the packet id against the player's world. */
  private Object readTargetEntity(Object packet) {
    try {
      Object result =
          packet
              .getClass()
              .getMethod("getEntity", net.minecraft.world.level.Level.class)
              .invoke(packet, in.potenfyr.authcore.compat.Compat.playerLevel(player));
      if (result instanceof net.minecraft.world.entity.Entity entity) return entity;
    } catch (ReflectiveOperationException | RuntimeException ignored) {
      // fall through
    }
    return null;
  }
}
