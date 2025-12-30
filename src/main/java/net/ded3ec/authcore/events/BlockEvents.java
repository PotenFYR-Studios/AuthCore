package net.ded3ec.authcore.events;

import net.ded3ec.authcore.AuthCore;
import net.ded3ec.authcore.models.User;
import net.ded3ec.authcore.utils.Logger;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.world.World;

/** Handles block and item interaction events for the AuthCore mod. */
public class BlockEvents {

  /**
   * Handles the block interaction event.
   *
   * @param player The player interacting with the block.
   * @param world The world in which the interaction occurs.
   * @param hand The hand used for the interaction.
   * @param blockHitResult The result of the block hit.
   * @return ActionResult.FAIL if the player is in the lobby and block interaction is not allowed,
   *     otherwise ActionResult.PASS.
   */
  public static ActionResult onBlockUsage(
      PlayerEntity player, World world, Hand hand, BlockHitResult blockHitResult) {
    User user = User.users.get(player.getName().getString());

    // Prevent block interaction if the user is in the lobby and block interaction is disallowed
    if (user != null && user.isInLobby.get() && !AuthCore.config.lobby.allowBlockInteraction) {
      player.currentScreenHandler.sendContentUpdates();
      player.playerScreenHandler.updateToClient();

      return Logger.toUser(ActionResult.FAIL, user.handler, AuthCore.messages.promptUserUseBlockNotAllowed);
    } else return ActionResult.PASS;
  }

  /**
   * Handles the item usage event.
   *
   * @param player The player using the item.
   * @param world The world in which the usage occurs.
   * @param hand The hand used for the item usage.
   * @return ActionResult.FAIL if the player is in the lobby and item usage or item moving is not
   *     allowed, otherwise ActionResult.PASS.
   */
  public static ActionResult onItemUsage(PlayerEntity player, World world, Hand hand) {
    User user = User.users.get(player.getName().getString());

    // Prevent item usage if the user is in the lobby and item usage is disallowed
    if (user != null && user.isInLobby.get() && !AuthCore.config.lobby.allowItemUse)
      return Logger.toUser(ActionResult.FAIL, user.handler, AuthCore.messages.promptUserUseItemNotAllowed);

    // Prevent item moving if the user is in the lobby and item moving is disallowed
    if (user != null && user.isInLobby.get() && !AuthCore.config.lobby.allowItemMoving)
      return Logger.toUser(ActionResult.FAIL, user.handler, AuthCore.messages.promptUserShiftItemNotAllowed);

    return ActionResult.PASS;
  }
}
