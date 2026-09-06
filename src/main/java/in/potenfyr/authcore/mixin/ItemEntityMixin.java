package in.potenfyr.authcore.mixin;

import in.potenfyr.authcore.models.Config;
import in.potenfyr.authcore.models.Lobby;

import java.util.UUID;
import in.potenfyr.authcore.AuthCoreServer;
import in.potenfyr.authcore.models.User;
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

  @Inject(
      method = "playerTouch(Lnet/minecraft/world/entity/player/Player;)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$onPlayerPickup(Player player, CallbackInfo ci) {

    User user = User.getUser(player);

    if (user != null && user.isInLobby.get() && !AuthCoreServer.config.lobby.allowItemPickup) {

      // Block pickup
      ci.cancel();

      // Sync inventory
      player.containerMenu.broadcastChanges();
    }
  }
}
