package in.potenfyr.authcore.mixin;


import in.potenfyr.authcore.AuthCoreServer;
import in.potenfyr.authcore.models.User;

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
@SuppressWarnings({"mapping", "unresolvable-target"})
abstract class ServerPlayerMixin {

  @Unique
  private ServerPlayer self() {
    return (ServerPlayer) (Object) this;
  }

  /** Prevent item dropping for jailed/lobby users. */
  @Inject(
      method = "drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$preventDrop(
      ItemStack stack,
      boolean throwRandomly,
      boolean retainOwnership,
      CallbackInfoReturnable<ItemEntity> cir) {

    if (blockLobbyDrop()) {
      cir.setReturnValue(null);
      cir.cancel();
    }
  }

  // NOTE: 26.3 changed drop()'s ownership boolean to a Prediction enum. No version-specific
  // descriptor is used here on purpose: a mixin descriptor naming net.minecraft.util.Prediction
  // would fail to apply on 26.1/26.2 if that class is absent there (it would take the whole mixin
  // down). The player-initiated drop is instead blocked at the stable packet level in
  // ServerPlayNetworkHandlerMixin#handlePlayerAction (DROP_ITEM / DROP_ALL_ITEMS), which covers
  // every version; InventoryMixin#removeItem remains the second backstop.

  /** Shared lobby item-drop restriction; returns true when the drop must be blocked. */
  @Unique
  private boolean blockLobbyDrop() {    ServerPlayer player = self();
    User user = User.getUser(player);

    if (user != null && user.isInLobby.get() && !AuthCoreServer.config.lobby.allowItemDrop) {
      AuthCoreServer.LOGGER.violation(
          false,
          user,
          user.connection, AuthCoreServer.messages.promptUserDropItemNotAllowed);

      // Sync inventory
      player.containerMenu.broadcastChanges();
      return true;
    }
    return false;
  }

  /**
   * Melee-attack guard on ServerPlayer (it overrides Player#attack on most versions). The 26.3
   * path where ServerPlayer does NOT override attack() is covered by PlayerAttackMixin.
   */
  @Inject(
      method = "attack(Lnet/minecraft/world/entity/Entity;)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$onAttack(net.minecraft.world.entity.Entity target, CallbackInfo ci) {
    ServerPlayer player = self();
    User user = User.getUser(player);
    if (user == null || !user.isInLobby.get()) return;
    if (target == null
        || in.potenfyr.authcore.events.EntityEvents.guardLobbyAttack(user, target)) ci.cancel();
  }

  /** Prevent game mode changes for jailed/lobby users (boolean return on 1.16 through 26.x). */
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

    User user = User.getUser(player);

    if (user != null
        && user.isInLobby.get()
        && AuthCoreServer.config.lobby.forceAdventureMode
        && newMode != GameType.ADVENTURE) {

      AuthCoreServer.LOGGER.violation(
          false,
          user,
          user.connection, AuthCoreServer.messages.promptUserChangeGameModeNotAllowed);
      return true;
    }
    return false;
  }

  @Inject(
      method = "startSleeping(Lnet/minecraft/core/BlockPos;)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$blockSleeping(net.minecraft.core.BlockPos pos, CallbackInfo ci) {
    if (blockSleeping()) ci.cancel();
  }

  // 26.x: startSleeping returns a boolean - the void descriptor above matches nothing there.
  /*? if >= 26.1 {*/
  /*@Inject(
      method = "startSleeping(Lnet/minecraft/core/BlockPos;)Z",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$blockSleepingModern(
      net.minecraft.core.BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
    if (blockSleeping()) cir.setReturnValue(false);
  }
  *//*?}*/

  @Unique
  private boolean blockSleeping() {
    ServerPlayer player = self();
    User user = User.getUser(player);
    if (user != null && user.isInLobby.get() && !AuthCoreServer.config.lobby.allowSleeping) {
      AuthCoreServer.LOGGER.violation(
          false,
          user,
          user.connection,
          AuthCoreServer.messages.promptUserSleepNotAllowed);
      return true;
    }
    return false;
  }
}

