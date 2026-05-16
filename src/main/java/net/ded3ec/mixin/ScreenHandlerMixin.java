package net.ded3ec.mixin;

import java.util.UUID;
import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.User;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mixin for {@link } to handle authentication-related events. */
@Environment(EnvType.SERVER)
@Mixin(ScreenHandler.class)
abstract class ScreenHandlerMixin {

  @Inject(method = "onSlotClick", at = @At("HEAD"), cancellable = true)
  private void authCore$onSlotClick(
      int slotIndex, int button, SlotActionType actionType, PlayerEntity player, CallbackInfo ci) {

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

      ci.cancel();
      player.currentScreenHandler.sendContentUpdates();
    }

    // Detect item pickup attempts
    if (user.isInLobby.get()
        && !AuthCoreServer.config.lobby.allowItemPickup
        && actionType == SlotActionType.PICKUP) {

      ci.cancel();
      player.currentScreenHandler.sendContentUpdates();
    }
  }
}
