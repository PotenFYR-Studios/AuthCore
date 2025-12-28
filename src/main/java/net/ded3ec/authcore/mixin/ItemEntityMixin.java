package net.ded3ec.authcore.mixin;

import net.ded3ec.authcore.AuthCore;
import net.ded3ec.authcore.models.User;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A mixin class for the ItemEntity class to modify item pickup behavior. Specifically, it prevents
 * players in the lobby from picking up items if item pickup is not allowed by the server's
 * configuration.
 */
@Mixin(ItemEntity.class)
public class ItemEntityMixin {

  /**
   * Prevents players in the lobby from picking up items if the server's configuration disallows
   * item pickup for jailed users.
   *
   * @param player The player attempting to pick up the item.
   * @param ci The callback information to control the method execution.
   */
  @Inject(method = "onPlayerCollision", at = @At("HEAD"), cancellable = true)
  private void authCore$onPlayerPickup(PlayerEntity player, CallbackInfo ci) {
    // Retrieve the user associated with the player.
    User user = User.users.get(player.getName().getString());

    // Cancel item pickup if the user is in the lobby and item pickup is not allowed.
    if (user != null && user.isInLobby.get() && !AuthCore.config.lobby.allowItemPickup) {
      ci.cancel(); // Cancel the item pickup event.
      player.currentScreenHandler.sendContentUpdates(); // Update the player's screen handler.
      player.playerScreenHandler.updateToClient(); // Sync the screen handler with the client.
    }
  }
}
