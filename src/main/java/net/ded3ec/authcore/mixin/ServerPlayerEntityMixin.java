package net.ded3ec.authcore.mixin;

import com.mojang.authlib.GameProfile;
import java.util.Set;
import net.ded3ec.authcore.AuthCore;
import net.ded3ec.authcore.models.User;
import net.ded3ec.authcore.utils.Logger;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Server Player Entity Handler for AuthCore in Minecraft Functions/Events! */
@Mixin(ServerPlayerEntity.class)
abstract class ServerPlayerEntityMixin extends PlayerEntity {

  /**
   * Mixin class instance shadowing.
   *
   * @param world the world
   * @param profile the game profile
   */
  public ServerPlayerEntityMixin(World world, GameProfile profile) {
    super(world, profile);
  }

  /**
   * Prevents dropping items for users in the lobby if configured.
   *
   * @param stack the item stack being dropped
   * @param throwRandomly whether the item is thrown randomly
   * @param retainOwnership whether ownership is retained
   * @param cir callback info returnable
   */
  @Inject(method = "dropItem", at = @At("HEAD"), cancellable = true)
  private void preventDrop(
      ItemStack stack,
      boolean throwRandomly,
      boolean retainOwnership,
      CallbackInfoReturnable<ItemEntity> cir) {
    ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;
    User user = User.users.get(player.getName().getString());

    // Changing game mode by jailed user's detection!
    if (user != null && user.isInLobby.get() && !AuthCore.config.lobby.allowItemDrop) {
      Logger.toUser(false, user.handler, AuthCore.messages.dropItemNotAllowed);

      player.currentScreenHandler.sendContentUpdates();
      player.playerScreenHandler.updateToClient();

      cir.setReturnValue(null);
    }
  }

  /**
   * Handles game mode changes for authenticated users.
   *
   * @param newGameMode the new game mode
   * @param cir callback info returnable
   */
  @Inject(method = "changeGameMode", at = @At("HEAD"), cancellable = true)
  private void authCore$onChangeGameMode(
      GameMode newGameMode, CallbackInfoReturnable<Boolean> cir) {
    ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;
    User user = User.users.get(player.getName().getString());

    // Changing game mode by jailed user's detection!
    if (user != null
        && user.isInLobby.get()
        && AuthCore.config.lobby.forceAdventureMode
        && newGameMode != GameMode.ADVENTURE) {

      Logger.toUser(false, user.handler, AuthCore.messages.changeGameModeNotAllowed);
      cir.setReturnValue(false);
    }
  }

  /**
   * Prevents teleportation for users in the lobby if movement is not allowed.
   *
   * @param world the target server world
   * @param destX destination x coordinate
   * @param destY destination y coordinate
   * @param destZ destination z coordinate
   * @param flags position flags
   * @param yaw yaw angle
   * @param pitch pitch angle
   * @param resetCamera whether to reset camera
   * @param cir callback info returnable
   */
  @Inject(method = "teleport", at = @At("HEAD"), cancellable = true)
  private void authCore$onTeleport(
      ServerWorld world,
      double destX,
      double destY,
      double destZ,
      Set<PositionFlag> flags,
      float yaw,
      float pitch,
      boolean resetCamera,
      CallbackInfoReturnable<Boolean> cir) {
    ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;
    User user = User.users.get(player.getName().getString());

    // Jailed User's status oldEffects detection!
    if (user != null
        && user.isInLobby.get()
        && !AuthCore.config.lobby.allowMovement
        && user.lobby.isOutsideOfLobbyPos(destX, destZ)) {
      cir.setReturnValue(false);
      cir.cancel();

      Logger.toUser(false, user.handler, AuthCore.messages.playerMovementNotAllowed);
      user.lobby.teleportToLobby();
    }
  }
}
