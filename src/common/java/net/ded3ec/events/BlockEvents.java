package net.ded3ec.events;

import net.ded3ec.models.Config;
import net.ded3ec.models.Lobby;
import net.ded3ec.models.Messages;
import net.ded3ec.util.Logger;

import java.util.UUID;

import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.User;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.world.World;

public class BlockEvents {

  /** Handles block interaction events. */
  public static ActionResult onBlockUsage(
      PlayerEntity player, World world, Hand hand, BlockHitResult hitResult) {

    UUID uuid = player.getUuid();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    if (user != null
        && user.isInLobby.get()
        && !AuthCoreServer.config.lobby.allowBlockInteraction) {

      // Sync inventory
      player.currentScreenHandler.sendContentUpdates();

      return AuthCoreServer.LOGGER.toUser(
          net.ded3ec.compat.Compat.actionResultFail(), user.connection, AuthCoreServer.messages.promptUserUseBlockNotAllowed);
    }

    return net.ded3ec.compat.Compat.actionResultPass();
  }

  /** Handles item usage events. */
  public static ActionResult onItemUsage(PlayerEntity player, World world, Hand hand) {

    UUID uuid = player.getUuid();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    if (user == null) return net.ded3ec.compat.Compat.actionResultPass();

    // Prevent item usage
    if (user.isInLobby.get() && !AuthCoreServer.config.lobby.allowItemUse)
      return AuthCoreServer.LOGGER.toUser(
          net.ded3ec.compat.Compat.actionResultFail(), user.connection, AuthCoreServer.messages.promptUserUseItemNotAllowed);

    // Prevent item moving
    if (user.isInLobby.get() && !AuthCoreServer.config.lobby.allowItemMoving)
      return AuthCoreServer.LOGGER.toUser(
          net.ded3ec.compat.Compat.actionResultFail(),
          user.connection,
          AuthCoreServer.messages.promptUserShiftItemNotAllowed);

    return net.ded3ec.compat.Compat.actionResultPass();
  }
}
