package in.potenfyr.authcore.compat;

import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Reads {@link ServerboundInteractPacket} through its own API, never by member name.
 *
 * <p>The limbo's entity-interaction guard used reflective "getAction"/"getType"/"getEntity"
 * lookups that exist in no supported runtime (the action is private since 1.17 and exposed through
 * {@code dispatch(Handler)}; the target getter is {@code getTarget(ServerLevel)}), so every packet
 * read as "unknown" and was cancelled - {@code lobby.allow-entity-interact-with} never took effect.
 * Kept outside the mixin package: an anonymous class declared inside a mixin is not loadable.
 */
public final class InteractPackets {

  private InteractPackets() {}

  /** "ATTACK" / "INTERACT", or null when the shape is unreadable (callers fail closed). */
  public static String actionKind(ServerboundInteractPacket packet) {
    if (packet == null) return null;
    try {
      /*? if >= 26.1 {*/
      /*// 26.x moved melee attacks to their own packet: this one is always an interaction.
      return "INTERACT";
      *//*?} else {*/
      final String[] kind = {null};
      packet.dispatch(
          new ServerboundInteractPacket.Handler() {
            @Override
            public void onInteraction(net.minecraft.world.InteractionHand hand) {
              kind[0] = "INTERACT";
            }

            @Override
            public void onInteraction(
                net.minecraft.world.InteractionHand hand, net.minecraft.world.phys.Vec3 pos) {
              kind[0] = "INTERACT";
            }

            @Override
            public void onAttack() {
              kind[0] = "ATTACK";
            }
          });
      return kind[0];
      /*?}*/
    } catch (RuntimeException | LinkageError unknownShape) {
      return null;
    }
  }

  /** The targeted entity in the player's level, or null. */
  public static Object target(ServerboundInteractPacket packet, ServerPlayer player) {
    if (packet == null || player == null) return null;
    try {
      ServerLevel level = Compat.playerLevel(player);
      if (level == null) return null;
      /*? if >= 26.1 {*/
      /*return level.getEntity(packet.entityId());
      *//*?} else {*/
      return packet.getTarget(level);
      /*?}*/
    } catch (RuntimeException | LinkageError unknownShape) {
      return null;
    }
  }
}
