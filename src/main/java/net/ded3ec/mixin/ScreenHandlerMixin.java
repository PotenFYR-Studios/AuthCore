package net.ded3ec.mixin;

import net.ded3ec.models.Config;
import net.ded3ec.models.Lobby;

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
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lobby slot-click restriction for the void-returning container click handler.
 *
 * <p>On 1.16 the handler returns {@link ItemStack} - that shape is covered by
 * {@link ScreenHandlerStackMixin}. The void descriptor below exists on 1.17+ only
 * (with {@code ClickType} on 1.17 - 1.21 and {@code ContainerInput} on 26.x), so the
 * injection is skipped with require = 0 wherever it does not match.
 */
/*? if fabric {*/
  @Environment(EnvType.SERVER)
  /*?}*/
@Mixin(AbstractContainerMenu.class)
abstract class ScreenHandlerMixin {

  @Inject(
      method =
          /*? if < 26 {*/
          "clicked(IILnet/minecraft/world/inventory/ClickType;Lnet/minecraft/world/entity/player/Player;)V",
          /*?} else {*/
          /*"clicked(IILnet/minecraft/world/inventory/ContainerInput;Lnet/minecraft/world/entity/player/Player;)V",
          *//*?}*/
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$onSlotClick(
      /*? if < 26 {*/
      int slotIndex, int button, ClickType actionType, Player player, CallbackInfo ci) {
      /*?} else {*/
      /*int slotIndex, int button, ContainerInput actionType, Player player, CallbackInfo ci) {
      *//*?}*/

    // ClientGuard: click-rate accounting (macro detection in the lobby).
    net.ded3ec.security.ClientGuard.recordClick(player);

    UUID uuid = player.getUUID();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    if (user == null) return;

    // Detect item drop attempts
    if (user.isInLobby.get()
        && !AuthCoreServer.config.lobby.allowItemDrop
        && (/*? if < 26 {*/actionType == ClickType.THROW
            || actionType == ClickType.QUICK_CRAFT
            || (slotIndex >= 1 && slotIndex <= 4))) {
      /*?} else {*//*actionType == ContainerInput.THROW
            || actionType == ContainerInput.QUICK_CRAFT
            || (slotIndex >= 1 && slotIndex <= 4))) {
      *//*?}*/
      ci.cancel();
      player.containerMenu.broadcastChanges();
    }

    // Detect item pickup attempts
    if (user.isInLobby.get()
        && !AuthCoreServer.config.lobby.allowItemPickup
        && actionType == /*? if < 26 {*/ClickType.PICKUP) {
      /*?} else {*//*ContainerInput.PICKUP) {
      *//*?}*/
      ci.cancel();
      player.containerMenu.broadcastChanges();
    }
  }
}
