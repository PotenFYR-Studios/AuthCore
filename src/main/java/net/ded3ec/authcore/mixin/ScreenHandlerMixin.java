package net.ded3ec.authcore.mixin;

import java.util.UUID;
import net.ded3ec.authcore.AuthCore;
import net.ded3ec.authcore.models.User;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin for {@link ScreenHandler} to handle authentication-related events in Minecraft. This mixin
 * intercepts inventory slot click events to enforce lobby restrictions on item interactions.
 */
@Mixin(ScreenHandler.class)
abstract class ScreenHandlerMixin {

  /**
   * Injects into the {@code onSlotClick} method to prevent unauthorized item drops and pickups when
   * the player is in the lobby, based on the configuration settings.
   *
   * @param slotIndex the index of the slot that was clicked
   * @param button the button that was pressed (e.g., left or right click)
   * @param actionType the type of action performed (e.g., PICKUP, THROW)
   * @param player the player entity performing the action
   * @param ci the callback info, used to cancel the event if necessary
   */
  @Inject(method = "onSlotClick", at = @At("HEAD"), cancellable = true)
  private void authCore$onSlotClick(
      int slotIndex, int button, SlotActionType actionType, PlayerEntity player, CallbackInfo ci) {

    UUID uuid = player.getUuid();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    // Item drop event detection.
    if (user != null
        && user.isInLobby.get()
        && !AuthCore.config.lobby.allowItemDrop
        && (actionType == SlotActionType.THROW
            || actionType == SlotActionType.QUICK_CRAFT
            || (slotIndex >= 1 && slotIndex <= 4))) {
      ci.cancel();

      player.currentScreenHandler.sendContentUpdates();
      player.playerScreenHandler.updateToClient();
    }

    // Item pickup event detection.
    if (user != null
        && user.isInLobby.get()
        && !AuthCore.config.lobby.allowItemPickup
        && actionType == SlotActionType.PICKUP) {
      ci.cancel();

      player.currentScreenHandler.sendContentUpdates();
      player.playerScreenHandler.updateToClient();
    }
  }
}
