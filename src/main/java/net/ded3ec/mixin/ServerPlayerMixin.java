package net.ded3ec.mixin;

import java.util.UUID;

import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.User;

/*? if fabric {*/
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
/*?}*/
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Player guard mixins shared by every Minecraft version: item dropping and game-mode changes are
 * blocked while a player is in the auth lobby. (Teleport restriction lives in the
 * version-specific {@code ServerPlayerTeleportMixin}.)
 */
/*? if fabric {*/
  @Environment(EnvType.SERVER)
  /*?}*/
@Mixin(ServerPlayer.class)
abstract class ServerPlayerMixin {

  @Unique
  private ServerPlayer self() {
    return (ServerPlayer) (Object) this;
  }

  /** Prevent item dropping for jailed/lobby users. */
  @Inject(
      method = "drop",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
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

  /**
   * Prevent game mode changes for jailed/lobby users - 1.16.x shape (void return).
   *
   * <p>The same method returns boolean on 1.17+, so the two descriptor-based injects below
   * cover the whole range; whichever descriptor does not exist at runtime is skipped
   * (require = 0). The preprocessor cannot select the shape because one jar is built per
   * range and must run on every range endpoint.
   */
  @Inject(
      method = "setGameMode(Lnet/minecraft/world/level/GameType;)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$onChangeGameModeVoid(GameType newMode, CallbackInfo ci) {
    if (blockGameModeChange(newMode)) ci.cancel();
  }

  /** Prevent game mode changes for jailed/lobby users - 1.17+ shape (boolean return). */
  @Inject(
      method = "setGameMode(Lnet/minecraft/world/level/GameType;)Z",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$onChangeGameMode(GameType newMode, CallbackInfoReturnable<Boolean> cir) {
    if (blockGameModeChange(newMode)) {
      cir.setReturnValue(false);
      cir.cancel();
    }
  }

  /** Shared game-mode restriction check; returns true when the change must be blocked. */
  @Unique
  private boolean blockGameModeChange(GameType newMode) {
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
      return true;
    }
    return false;
  }
}
