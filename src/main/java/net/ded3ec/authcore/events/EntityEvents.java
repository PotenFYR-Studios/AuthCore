package net.ded3ec.authcore.events;

import java.util.UUID;
import net.ded3ec.authcore.AuthCore;
import net.ded3ec.authcore.models.User;
import net.ded3ec.authcore.utils.Logger;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.*;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.entity.vehicle.MinecartEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.world.World;
import org.jspecify.annotations.Nullable;

/** Handles entity-related events for the AuthCore mod. */
public class EntityEvents {

  /**
   * Handles the entity attack event.
   *
   * @param player The player attacking the entity.
   * @param world The world in which the attack occurs.
   * @param hand The hand used for the attack.
   * @param entity The entity being attacked.
   * @param entityHitResult The result of the entity hit, if applicable.
   * @return ActionResult.FAIL if the attack is disallowed based on the lobby configuration,
   *     otherwise ActionResult.PASS.
   */
  public static ActionResult onEntityAttack(
      PlayerEntity player,
      World world,
      Hand hand,
      Entity entity,
      @Nullable EntityHitResult entityHitResult) {
    UUID uuid = player.getUuid();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    if (user != null && user.isInLobby.get()) {
      // Prevent attacking players if disallowed
      if (entity instanceof PlayerEntity && !AuthCore.config.lobby.allowAttackingPlayer)
        return Logger.toUser(
            ActionResult.FAIL, user.handler, AuthCore.messages.promptUserAttackPlayerNotAllowed);

      // Prevent attacking hostile mobs if disallowed
      if (entity instanceof HostileEntity && !AuthCore.config.lobby.allowAttackingHostileMobs)
        return Logger.toUser(
            ActionResult.FAIL,
            user.handler,
            AuthCore.messages.promptUserAttackHostileMobsNotAllowed);

      // Prevent attacking animals if disallowed
      if (entity instanceof AnimalEntity && !AuthCore.config.lobby.allowAttackingAnimals)
        return Logger.toUser(
            ActionResult.FAIL, user.handler, AuthCore.messages.promptUserAttackAnimalNotAllowed);

      // Prevent attacking friendly mobs if disallowed
      if (entity instanceof PassiveEntity && !AuthCore.config.lobby.allowAttackingFriendlyMobs)
        return Logger.toUser(
            ActionResult.FAIL,
            user.handler,
            AuthCore.messages.promptUserAttackFriendlyMobsNotAllowed);

      // Prevent attacking neutral mobs if disallowed
      if (entity instanceof MobEntity
          && !(entity instanceof AnimalEntity)
          && !AuthCore.config.lobby.allowAttackNeutralMobs)
        return Logger.toUser(
            ActionResult.FAIL,
            user.handler,
            AuthCore.messages.promptUserAttackNeutralMobsNotAllowed);

      // Prevent attacking mountable entities if disallowed
      if (entity instanceof BoatEntity
          || entity instanceof MinecartEntity
          || entity instanceof AbstractHorseEntity
          || entity instanceof CamelEntity
          || entity instanceof PigEntity
          || entity instanceof StriderEntity && !AuthCore.config.lobby.allowAttackMountableEntity)
        return Logger.toUser(
            ActionResult.FAIL,
            user.handler,
            AuthCore.messages.promptUserInteractMountableEntityNotAllowed);

      // Prevent attacking any entity if disallowed
      if (entity instanceof Entity && !AuthCore.config.lobby.allowAttackEntity)
        return Logger.toUser(
            ActionResult.FAIL, user.handler, AuthCore.messages.promptUserInteractEntityNotAllowed);
    } else if (user != null) user.lastCombactDetectMs = System.currentTimeMillis();

    return ActionResult.PASS;
  }

  /**
   * Handles the entity interaction event.
   *
   * @param player The player interacting with the entity.
   * @param world The world in which the interaction occurs.
   * @param hand The hand used for the interaction.
   * @param entity The entity being interacted with.
   * @param entityHitResult The result of the entity hit, if applicable.
   * @return ActionResult.FAIL if the interaction is disallowed based on the lobby configuration,
   *     otherwise ActionResult.PASS.
   */
  public static ActionResult onEntityUse(
      PlayerEntity player,
      World world,
      Hand hand,
      Entity entity,
      @Nullable EntityHitResult entityHitResult) {
    UUID uuid = player.getUuid();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    if (user != null && user.isInLobby.get()) {
      // Prevent interacting with players if disallowed
      if (entity instanceof PlayerEntity && !AuthCore.config.lobby.allowPlayerInteractWith)
        return Logger.toUser(
            ActionResult.FAIL, user.handler, AuthCore.messages.promptUserInteractPlayersNotAllowed);

      // Prevent interacting with hostile mobs if disallowed
      if (entity instanceof HostileEntity && !AuthCore.config.lobby.allowHostileMobsInteractWith)
        return Logger.toUser(
            ActionResult.FAIL,
            user.handler,
            AuthCore.messages.promptUserInteractHostileMobsNotAllowed);

      // Prevent interacting with animals if disallowed
      if (entity instanceof AnimalEntity && !AuthCore.config.lobby.allowAnimalInteractWith)
        return Logger.toUser(
            ActionResult.FAIL, user.handler, AuthCore.messages.promptUserInteractAnimalsNotAllowed);

      // Prevent interacting with friendly mobs if disallowed
      if (entity instanceof PassiveEntity && !AuthCore.config.lobby.allowFriendlyMobsInteractWith)
        return Logger.toUser(
            ActionResult.FAIL,
            user.handler,
            AuthCore.messages.promptUserInteractFriendlyMobsNotAllowed);

      // Prevent interacting with neutral mobs if disallowed
      if (entity instanceof MobEntity
          && !(entity instanceof AnimalEntity)
          && !AuthCore.config.lobby.allowNeutralMobsInteractWith)
        return Logger.toUser(
            ActionResult.FAIL,
            user.handler,
            AuthCore.messages.promptUserInteractNeutralMobsNotAllowed);

      // Prevent interacting with mountable entities if disallowed
      if (entity instanceof BoatEntity
          || entity instanceof MinecartEntity
          || entity instanceof AbstractHorseEntity
          || entity instanceof CamelEntity
          || entity instanceof PigEntity
          || entity instanceof StriderEntity && !AuthCore.config.lobby.allowMountableInteractWith)
        return Logger.toUser(
            ActionResult.FAIL,
            user.handler,
            AuthCore.messages.promptUserInteractMountableEntityNotAllowed);

      // Prevent interacting with any entity if disallowed
      if (entity instanceof Entity && !AuthCore.config.lobby.allowEntityInteractWith)
        return Logger.toUser(
            ActionResult.FAIL, user.handler, AuthCore.messages.promptUserInteractEntityNotAllowed);
    }

    return ActionResult.PASS;
  }

  /**
   * Handles the entity damage event.
   *
   * @param entity The entity being damaged.
   * @param damageSource The source of the damage.
   * @param v The amount of damage dealt.
   * @return true if the damage is allowed, false otherwise.
   */
  public static boolean onEntityDamage(LivingEntity entity, DamageSource damageSource, float v) {
    if (!(entity instanceof ServerPlayerEntity)) return true;

    UUID uuid = entity.getUuid();
    String username = entity.getName().getString();
    User user = User.getUser(username, uuid);

    if (user != null && user.isInLobby.get()) {
      // Prevent damage from mobs if disallowed
      if (damageSource.getAttacker() instanceof MobEntity)
        return AuthCore.config.lobby.allowMobDamage;

      // Prevent damage from players if disallowed
      else if (AuthCore.config.lobby.preventPlayerDamage
          && (damageSource.getAttacker() instanceof ServerPlayerEntity))
        return Logger.toUser(
            false,
            ((ServerPlayerEntity) damageSource.getAttacker()).networkHandler,
            AuthCore.messages.promptUserAttackLobbyUserNotAllowed);

      // Prevent all damage if disallowed
      else return !(AuthCore.config.lobby.preventDamage);
    } else if (user != null) user.lastCombactDetectMs = System.currentTimeMillis();

    return true;
  }
}
