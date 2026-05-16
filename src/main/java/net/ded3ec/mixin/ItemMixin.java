package net.ded3ec.mixin;

import java.util.UUID;
import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.User;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Environment(EnvType.SERVER)
@Mixin(Item.class)
public abstract class ItemMixin {

  @Inject(method = "use", at = @At("HEAD"), cancellable = true)
  private void authCore$preventUse(
      World level, PlayerEntity player, Hand hand, CallbackInfoReturnable<ActionResult> cir) {

    UUID uuid = player.getUuid();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    if (user != null
        && user.isInLobby.get()
        && !AuthCoreServer.config.lobby.allowBlockInteraction) {

      // Block item use
      cir.setReturnValue(ActionResult.FAIL);

      // Sync inventory
      player.getInventory().markDirty();

    }
  }
}
