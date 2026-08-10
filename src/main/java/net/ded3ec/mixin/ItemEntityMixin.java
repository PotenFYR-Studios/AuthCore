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
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents players in the lobby from picking up items if item pickup is disabled. */
/*? if fabric {*/
  @Environment(EnvType.SERVER)
  /*?}*/
@Mixin(ItemEntity.class)
public class ItemEntityMixin {

  @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
  private void authCore$onPlayerPickup(Player player, CallbackInfo ci) {

    UUID uuid = player.getUUID();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    if (user != null && user.isInLobby.get() && !AuthCoreServer.config.lobby.allowItemPickup) {

      // Block pickup
      ci.cancel();

      // Sync inventory
      player.containerMenu.broadcastChanges();
    }
  }
}
