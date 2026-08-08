package net.ded3ec.mixin;

import java.util.UUID;

import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.User;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Player guard mixins shared by every Minecraft version: item dropping and game-mode changes are
 * blocked while a player is in the auth lobby. (Teleport restriction lives in the
 * version-specific {@code ServerPlayerTeleportMixin}.)
 */
@Environment(EnvType.SERVER)
@Mixin(ServerPlayer.class)
abstract class ServerPlayerMixin {

  @Unique
  private ServerPlayer self() {
    return (ServerPlayer) (Object) this;
  }

  /** Prevent item dropping for jailed/lobby users. */
  @Inject(method = "drop", at = @At("HEAD"), cancellable = true)
  private void authCore$preventDrop(
      ItemStack stack,
      boolean throwRandomly,
      boolean retainOwnership,
      CallbackInfoReturnable<ItemEntity> cir) {

    ServerPlayer player = self();

    UUID uuid = player.getUUID();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    if (user != null && user.isInLobby.get() && !AuthCoreServer.config.lobby.allowItemDrop) {

      AuthCoreServer.LOGGER.toUser(
          false, user.connection, AuthCoreServer.messages.promptUserDropItemNotAllowed);

      // Sync inventory
      player.containerMenu.broadcastChanges();

      cir.setReturnValue(null);
      cir.cancel();
    }
  }

  /** Prevent game mode changes for jailed/lobby users. */
  @Inject(method = "setGameMode", at = @At("HEAD"), cancellable = true)
  private void authCore$onChangeGameMode(GameType newMode, CallbackInfoReturnable<Boolean> cir) {

    ServerPlayer player = self();

    UUID uuid = player.getUUID();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    if (user != null
        && user.isInLobby.get()
        && AuthCoreServer.config.lobby.forceAdventureMode
        && newMode != GameType.ADVENTURE) {

      AuthCoreServer.LOGGER.toUser(
          false, user.connection, AuthCoreServer.messages.promptUserChangeGameModeNotAllowed);

      cir.setReturnValue(false);
      cir.cancel();
    }
  }
}
