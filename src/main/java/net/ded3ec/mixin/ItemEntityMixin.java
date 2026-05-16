package net.ded3ec.mixin;

import java.util.UUID;
import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.User;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents players in the lobby from picking up items if item pickup is disabled. */
@Environment(EnvType.SERVER)
@Mixin(ItemEntity.class)
public class ItemEntityMixin {

  @Inject(method = "onPlayerCollision", at = @At("HEAD"), cancellable = true)
  private void authCore$onPlayerPickup(PlayerEntity player, CallbackInfo ci) {

    UUID uuid = player.getUuid();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    if (user != null && user.isInLobby.get() && !AuthCoreServer.config.lobby.allowItemPickup) {

      // Block pickup
      ci.cancel();

      // Sync inventory
      player.currentScreenHandler.sendContentUpdates();
    }
  }
}
