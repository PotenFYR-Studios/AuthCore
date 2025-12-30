package net.ded3ec.authcore.mixin;

import net.ded3ec.authcore.AuthCore;
import net.ded3ec.authcore.models.User;
import net.ded3ec.authcore.utils.Logger;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A mixin class for the PlayerInventory class to modify inventory behavior. Specifically, it
 * prevents players in the lobby from removing items from their inventory if item dropping is not
 * allowed by the server's configuration.
 */
@Mixin(PlayerInventory.class)
abstract class PlayerInventoryMixin {

  /**
   * Prevents players in the lobby from removing items from their inventory if the server's
   * configuration disallows item dropping for jailed users.
   *
   * @param slot The inventory slot from which the item is being removed.
   * @param amount The amount of the item being removed.
   * @param cir The callback information to control the method execution.
   */
  @Inject(method = "removeStack", at = @At("HEAD"), cancellable = true)
  private void authCore$onRemoveStack(int slot, int amount, CallbackInfoReturnable<ItemStack> cir) {

    // Cast the current object to PlayerInventory.
    PlayerInventory inventory = (PlayerInventory) (Object) this;

    // Retrieve the user associated with the player.
    User user = User.users.get(inventory.player.getName().getString());

    // Prevent item removal if the user is in the lobby and item dropping is not allowed.
    if (user != null && user.isInLobby.get() && !AuthCore.config.lobby.allowItemDrop) {
      // Notify the user that item dropping is not allowed.
      Logger.toUser(false, user.handler, AuthCore.messages.promptUserDropItemNotAllowed);

      // Update the player's screen handler to reflect the change.
      inventory.player.currentScreenHandler.sendContentUpdates();
      inventory.player.playerScreenHandler.updateToClient();

      // Return an empty ItemStack to prevent the item from being removed.
      cir.setReturnValue(ItemStack.EMPTY);
    }
  }
}
