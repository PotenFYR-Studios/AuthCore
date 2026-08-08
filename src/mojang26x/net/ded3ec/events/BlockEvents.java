package net.ded3ec.events;

import net.ded3ec.models.Config;
import net.ded3ec.models.Lobby;
import net.ded3ec.models.Messages;
import net.ded3ec.util.Logger;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import java.util.UUID;

import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.User;

public class BlockEvents {

  /** Handles block interaction events. */
  public static InteractionResult onBlockUsage(
      Player player, Level world, InteractionHand hand, BlockHitResult hitResult) {

    UUID uuid = player.getUUID();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    if (user != null
        && user.isInLobby.get()
        && !AuthCoreServer.config.lobby.allowBlockInteraction) {

      // Sync inventory
      player.containerMenu.broadcastChanges();

      return AuthCoreServer.LOGGER.toUser(
          InteractionResult.FAIL, user.connection, AuthCoreServer.messages.promptUserUseBlockNotAllowed);
    }

    return InteractionResult.PASS;
  }

  /** Handles item usage events. */
  public static InteractionResult onItemUsage(Player player, Level world, InteractionHand hand) {

    UUID uuid = player.getUUID();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    if (user == null) return InteractionResult.PASS;

    // Prevent item usage
    if (user.isInLobby.get() && !AuthCoreServer.config.lobby.allowItemUse)
      return AuthCoreServer.LOGGER.toUser(
          InteractionResult.FAIL, user.connection, AuthCoreServer.messages.promptUserUseItemNotAllowed);

    // Prevent item moving
    if (user.isInLobby.get() && !AuthCoreServer.config.lobby.allowItemMoving)
      return AuthCoreServer.LOGGER.toUser(
          InteractionResult.FAIL,
          user.connection,
          AuthCoreServer.messages.promptUserShiftItemNotAllowed);

    return InteractionResult.PASS;
  }
}
