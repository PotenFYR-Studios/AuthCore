package net.ded3ec.mixin;

import java.util.UUID;
import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.User;
/*? if fabric {*/
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
/*?}*/
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
/*? if < 26 {*/
import net.minecraft.world.inventory.ClickType;
/*?} else {*/
/*import net.minecraft.world.inventory.ContainerInput;
*//*?}*/
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Item-stack-returning variant of the lobby slot-click restriction.
 *
 * <p>Shared mixin config between the classic and modern builds requires this class to exist
 * in the modern jar. On the unobfuscated 26.x era the container click handler is
 * {@code AbstractContainerMenu#clicked(...)} (void), so the stack-returning descriptor listed
 * here never exists at runtime and the injection is skipped (require = 0). It is kept for
 * config compatibility and as a safety net if a future 26.x shape returns a stack.
 */
/*? if fabric {*/
  @Environment(EnvType.SERVER)
  /*?}*/
@Mixin(AbstractContainerMenu.class)
abstract class ScreenHandlerStackMixin {

  @Inject(
      method =
          "clicked(IILnet/minecraft/world/inventory/ClickType;Lnet/minecraft/world/entity/player/Player;)Lnet/minecraft/world/item/ItemStack;",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$onSlotClick(
      /*? if < 26 {*/
      int slotIndex, int button, ClickType actionType, Player player,
      /*?} else {*/
      /*int slotIndex, int button, ContainerInput actionType, Player player,
      *//*?}*/
      CallbackInfoReturnable<ItemStack> cir) {

    // ClientGuard: click-rate accounting (macro detection in the lobby).
    net.ded3ec.security.ClientGuard.recordClick(player);

    UUID uuid = player.getUUID();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    if (user == null) return;

    if (user.isInLobby.get()
        && !AuthCoreServer.config.lobby.allowItemDrop
        && (/*? if < 26 {*/actionType == ClickType.THROW
            || actionType == ClickType.QUICK_CRAFT
            || (slotIndex >= 1 && slotIndex <= 4))) {
      /*?} else {*//*actionType == ContainerInput.THROW
            || actionType == ContainerInput.QUICK_CRAFT
            || (slotIndex >= 1 && slotIndex <= 4))) {
      *//*?}*/
      cir.setReturnValue(ItemStack.EMPTY);
      player.containerMenu.broadcastChanges();
    }

    if (user.isInLobby.get()
        && !AuthCoreServer.config.lobby.allowItemPickup
        && actionType == /*? if < 26 {*/ClickType.PICKUP) {
      /*?} else {*//*ContainerInput.PICKUP) {
      *//*?}*/
      cir.setReturnValue(ItemStack.EMPTY);
      player.containerMenu.broadcastChanges();
    }
  }
}
