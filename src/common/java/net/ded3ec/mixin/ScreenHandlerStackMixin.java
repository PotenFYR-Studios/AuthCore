package net.ded3ec.mixin;

import net.ded3ec.models.Config;
import net.ded3ec.models.Lobby;

import java.util.UUID;
import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.User;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lobby item interaction restriction for Minecraft 1.16.x, where
 * {@code ScreenHandler#onSlotClick} returns {@code ItemStack}.
 *
 * <p>On 1.17.1+ the same method returns void - that shape is handled by
 * {@link ScreenHandlerMixin}. Only the descriptor matching the running version exists,
 * so the other class is skipped (require = 0) without errors.
 */
@Environment(EnvType.SERVER)
@Mixin(ScreenHandler.class)
abstract class ScreenHandlerStackMixin {

  @Inject(
      method = "onSlotClick(IILnet/minecraft/screen/slot/SlotActionType;Lnet/minecraft/entity/player/PlayerEntity;)Lnet/minecraft/item/ItemStack;",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$onSlotClick(
      int slotIndex, int button, SlotActionType actionType, PlayerEntity player,
      CallbackInfoReturnable<ItemStack> cir) {

    UUID uuid = player.getUuid();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    if (user == null) return;

    // Detect item drop attempts
    if (user.isInLobby.get()
        && !AuthCoreServer.config.lobby.allowItemDrop
        && (actionType == SlotActionType.THROW
            || actionType == SlotActionType.QUICK_CRAFT
            || (slotIndex >= 1 && slotIndex <= 4))) {

      cir.setReturnValue(ItemStack.EMPTY);
      player.currentScreenHandler.sendContentUpdates();
    }

    // Detect item pickup attempts
    if (user.isInLobby.get()
        && !AuthCoreServer.config.lobby.allowItemPickup
        && actionType == SlotActionType.PICKUP) {

      cir.setReturnValue(ItemStack.EMPTY);
      player.currentScreenHandler.sendContentUpdates();
    }
  }
}
