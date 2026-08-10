package net.ded3ec.mixin;

import net.ded3ec.models.Config;
import net.ded3ec.models.Lobby;
import net.ded3ec.models.Messages;
import net.ded3ec.util.Logger;

import java.util.UUID;
import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.User;
/*? if fabric {*/
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
/*?}*/
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents players in the lobby from removing items from their inventory if item dropping is not
 * allowed.
 */
/*? if fabric {*/
  @Environment(EnvType.SERVER)
  /*?}*/
@Mixin(Inventory.class)
abstract class InventoryMixin {

  @Inject(
      method = "removeItem(II)Lnet/minecraft/world/item/ItemStack;",
      at = @At("HEAD"),
      cancellable = true)
  private void authCore$onRemoveStack(int slot, int count, CallbackInfoReturnable<ItemStack> cir) {

    Inventory inventory = (Inventory) (Object) this;
    Player player = inventory.player;

    if (player == null) return;

    UUID uuid = player.getUUID();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    if (user != null && user.isInLobby.get() && !AuthCoreServer.config.lobby.allowItemDrop) {

      AuthCoreServer.LOGGER.toUser(
          false, user.connection, AuthCoreServer.messages.promptUserDropItemNotAllowed);

      // Sync inventory
      player.containerMenu.broadcastChanges();

      // Block removal
      cir.setReturnValue(ItemStack.EMPTY);
      cir.cancel();
    }
  }
}
