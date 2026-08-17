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
      cancellable = true,
      require = 0)
  private void authCore$onRemoveStack(int slot, int count, CallbackInfoReturnable<ItemStack> cir) {

    Inventory inventory = (Inventory) (Object) this;
    Player player = inventory.player;

    if (player == null) return;

    User user = User.getUser(player);

    if (user != null && user.isInLobby.get() && !AuthCoreServer.config.lobby.allowItemDrop) {

      // Direct chat feedback only - a title send from inside Inventory.removeItem runs the
      // full style/packet chain on EVERY internal removal (recipe-book placement, crafting,
      // shift-clicks) and previously hung the server thread until the watchdog killed it.
      try {
        net.ded3ec.models.Messages.ColTemplate tpl =
            AuthCoreServer.messages.promptUserDropItemNotAllowed;
        String text =
            tpl.title.subtitle != null && !tpl.title.subtitle.text.isBlank()
                ? tpl.title.subtitle.text
                : tpl.title.text;
        if (text != null && !text.isBlank())
          net.ded3ec.compat.Compat.sendMessage((net.minecraft.server.level.ServerPlayer) player, net.ded3ec.compat.Compat.text(text), false);
      } catch (RuntimeException ignored) {
        // feedback is best-effort
      }

      // Sync inventory
      player.containerMenu.broadcastChanges();

      // Block removal
      cir.setReturnValue(ItemStack.EMPTY);
      cir.cancel();
    }
  }
}
