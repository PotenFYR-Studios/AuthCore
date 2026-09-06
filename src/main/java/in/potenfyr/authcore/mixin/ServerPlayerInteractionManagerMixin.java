package in.potenfyr.authcore.mixin;

import java.util.UUID;

import in.potenfyr.authcore.AuthCoreServer;
import in.potenfyr.authcore.models.User;

/*? if fabric {*/
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
/*?}*/
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lobby block restrictions (breaking + interaction/placement + item use).
 *
 * <p>Every inject uses an EXACT descriptor and {@code require = 0}: on versions where the
 * method does not exist or has a different shape (e.g. 1.16-1.21 {@code destroyBlock} vs
 * 26.x {@code handleBlockBreakAction}/{@code destroyAndAck}) the inject is skipped instead
 * of crashing the server at class load. Handler arguments always match the descriptor, so a
 * version-specific signature can never cause a descriptor-mismatch failure.
 */
/*? if fabric {*/
  @Environment(EnvType.SERVER)
  /*?}*/
@Mixin(ServerPlayerGameMode.class)
@SuppressWarnings({"mapping", "unresolvable-target"})
abstract class ServerPlayerInteractionManagerMixin {

  @Shadow @Final protected ServerPlayer player;

  /** 1.16-1.21 block breaking. */
  @Inject(
      method = "destroyBlock(Lnet/minecraft/core/BlockPos;)Z",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$onTryBreakBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {

    User user = User.getUser(player);

    if (user != null && user.isInLobby.get() && !AuthCoreServer.config.lobby.allowBlockBreaking) {
      AuthCoreServer.LOGGER.violation(
          false,
          user,
          user.connection, AuthCoreServer.messages.promptUserBreakBlockNotAllowed);

      cir.setReturnValue(false);
      cir.cancel();
    }
  }

/*? if >= 26 {*/
/*  @Inject(
      method =
          "handleBlockBreakAction(Lnet/minecraft/core/BlockPos;Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket$Action;Lnet/minecraft/core/Direction;II)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$onBlockBreakAction(
      BlockPos pos,
      ServerboundPlayerActionPacket.Action action,
      Direction direction,
      int sequence,
      int sequence2,
      CallbackInfo ci) {

    if (action != ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
        && action != ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK
        && action != ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK)
      return;

    User user = User.getUser(player);

    if (user != null && user.isInLobby.get() && !AuthCoreServer.config.lobby.allowBlockBreaking) {
      AuthCoreServer.LOGGER.violation(
          false,
          user,
          user.connection, AuthCoreServer.messages.promptUserBreakBlockNotAllowed);
      ci.cancel();
    }
  }

  @Inject(
      method = "destroyAndAck(Lnet/minecraft/core/BlockPos;ILjava/lang/String;)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$onDestroyAndAck(
      BlockPos pos, int sequence, String reason, CallbackInfo ci) {

    User user = User.getUser(player);

    if (user != null && user.isInLobby.get() && !AuthCoreServer.config.lobby.allowBlockBreaking) {
      AuthCoreServer.LOGGER.violation(
          false,
          user,
          user.connection, AuthCoreServer.messages.promptUserBreakBlockNotAllowed);
      ci.cancel();
    }
  }
*//*?}*/

  /** Block interaction AND placement (right-click on blocks) - every version and loader. */
  @Inject(
      method =
          "useItemOn(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$onUseItemOn(
      ServerPlayer player,
      Level level,
      ItemStack stack,
      InteractionHand hand,
      BlockHitResult hitResult,
      CallbackInfoReturnable<InteractionResult> cir) {

    User user = User.getUser(player);

    if (user != null
        && user.isInLobby.get()
        && !AuthCoreServer.config.lobby.allowBlockInteraction) {

      AuthCoreServer.LOGGER.violation(
          false,
          user,
          user.connection, AuthCoreServer.messages.promptUserUseBlockNotAllowed);

      cir.setReturnValue(in.potenfyr.authcore.compat.Compat.actionResultFail());
      cir.cancel();
    }
  }

  /**
   * Item usage in the hand (eating, drinking, bow, fishing rod, ender pearl...) - enforced at
   * the game-mode level so it works on EVERY loader (the fabric-api UseItemCallback alone
   * only covered fabric before).
   */
  @Inject(
      method =
          "useItem(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$onUseItem(
      ServerPlayer player,
      Level level,
      ItemStack stack,
      InteractionHand hand,
      CallbackInfoReturnable<InteractionResult> cir) {

    User user = User.getUser(player);

    if (user != null && user.isInLobby.get() && !AuthCoreServer.config.lobby.allowItemUse) {

      AuthCoreServer.LOGGER.violation(
          false,
          user,
          user.connection, AuthCoreServer.messages.promptUserUseItemNotAllowed);

      cir.setReturnValue(in.potenfyr.authcore.compat.Compat.actionResultFail());
      cir.cancel();
    }
  }
}
