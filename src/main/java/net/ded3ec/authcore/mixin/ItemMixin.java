package net.ded3ec.authcore.mixin;

import java.util.UUID;
import net.ded3ec.authcore.AuthCore;
import net.ded3ec.authcore.models.User;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A mixin class for the Item class to modify item usage behavior. Specifically, it prevents players
 * in the lobby from using items if item interaction is not allowed by the server's configuration.
 */
@Mixin(Item.class)
public abstract class ItemMixin {

  /**
   * Prevents players in the lobby from using items if the server's configuration disallows block
   * interaction for jailed users.
   *
   * @param world The world in which the item is being used.
   * @param player The player attempting to use the item.
   * @param hand The hand with which the item is being used.
   * @param cir The callback information to control the method execution.
   */
  @Inject(method = "use", at = @At("HEAD"), cancellable = true)
  private void authCore$preventUse(
      World world, PlayerEntity player, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
    // Retrieve the user associated with the player.
    UUID uuid = player.getUuid();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    // Cancel item usage if the user is in the lobby and block interaction is not allowed.
    if (user != null && user.isInLobby.get() && !AuthCore.config.lobby.allowBlockInteraction) {
      cir.setReturnValue(ActionResult.FAIL); // Prevent the item from being used.
      player.currentScreenHandler.sendContentUpdates(); // Update the player's screen handler.
      player.playerScreenHandler.updateToClient(); // Sync the screen handler with the client.
    }
  }
}
