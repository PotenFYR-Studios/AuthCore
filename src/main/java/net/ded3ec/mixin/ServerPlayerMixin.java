package net.ded3ec.mixin;

import java.util.Set;
import java.util.UUID;

import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.User;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.GameMode;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Environment(EnvType.SERVER)
@Mixin(ServerPlayerEntity.class)
abstract class ServerPlayerMixin {

  @Unique
  private ServerPlayerEntity self() {
    return (ServerPlayerEntity) (Object) this;
  }

  /** Prevent item dropping for jailed/lobby users. */
  @Inject(method = "dropItem", at = @At("HEAD"), cancellable = true)
  private void authCore$preventDrop(
      ItemStack stack,
      boolean throwRandomly,
      boolean retainOwnership,
      CallbackInfoReturnable<ItemEntity> cir) {

    ServerPlayerEntity player = self();

    UUID uuid = player.getUuid();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    if (user != null && user.isInLobby.get() && !AuthCoreServer.config.lobby.allowItemDrop) {

      AuthCoreServer.LOGGER.toUser(
          false, user.connection, AuthCoreServer.messages.promptUserDropItemNotAllowed);

      // Sync inventory
      player.currentScreenHandler.sendContentUpdates();

      cir.setReturnValue(null);
      cir.cancel();
    }
  }

  /** Prevent game mode changes for jailed/lobby users. */
  @Inject(method = "changeGameMode", at = @At("HEAD"), cancellable = true)
  private void authCore$onChangeGameMode(GameMode newMode, CallbackInfoReturnable<Boolean> cir) {

    ServerPlayerEntity player = self();

    UUID uuid = player.getUuid();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    if (user != null
        && user.isInLobby.get()
        && AuthCoreServer.config.lobby.forceAdventureMode
        && newMode != GameMode.ADVENTURE) {

      AuthCoreServer.LOGGER.toUser(
          false, user.connection, AuthCoreServer.messages.promptUserChangeGameModeNotAllowed);

      cir.setReturnValue(false);
      cir.cancel();
    }
  }

  /** Prevent teleportation for jailed/lobby users. */
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

    ServerPlayerEntity player = self();

    UUID uuid = player.getUuid();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    if (user != null
        && user.isInLobby.get()
        && !AuthCoreServer.config.lobby.allowMovement
        && user.lobby.isOutsideOfLobbyPos(destX, destZ)) {

      cir.setReturnValue(false);
      cir.cancel();

      AuthCoreServer.LOGGER.toUser(
          false, user.connection, AuthCoreServer.messages.promptUserPlayerMovementNotAllowed);

      user.lobby.handleTeleport();
    }
  }
}
