package net.ded3ec.mixin;

import net.ded3ec.models.Config;
import net.ded3ec.models.Lobby;

import java.util.UUID;
import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.User;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mixin for {@link } to handle authentication-related events. */
@Environment(EnvType.SERVER)
@Mixin(AbstractContainerMenu.class)
abstract class ScreenHandlerMixin {

  @Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
  private void authCore$onSlotClick(
      int slotIndex, int button, ContainerInput actionType, Player player, CallbackInfo ci) {

    UUID uuid = player.getUUID();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    if (user == null) return;

    // Detect item drop attempts
    if (user.isInLobby.get()
        && !AuthCoreServer.config.lobby.allowItemDrop
        && (actionType == ContainerInput.THROW
            || actionType == ContainerInput.QUICK_CRAFT
            || (slotIndex >= 1 && slotIndex <= 4))) {

      ci.cancel();
      player.containerMenu.broadcastChanges();
    }

    // Detect item pickup attempts
    if (user.isInLobby.get()
        && !AuthCoreServer.config.lobby.allowItemPickup
        && actionType == ContainerInput.PICKUP) {

      ci.cancel();
      player.containerMenu.broadcastChanges();
    }
  }
}
