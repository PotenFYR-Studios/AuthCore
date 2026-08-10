package net.ded3ec.events;

import net.ded3ec.models.Config;
import net.ded3ec.models.Lobby;
import net.ded3ec.models.Messages;
import net.ded3ec.util.Logger;

import java.util.UUID;

import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.User;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.Nullable;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.entity.vehicle.MinecartEntity;

import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.world.World;
import net.minecraft.entity.damage.DamageSource;

public class EntityEvents {

  /** Handles entity attack events. */
  public static ActionResult onEntityAttack(
      PlayerEntity player,
      World world,
      Hand hand,
      Entity entity,
      @Nullable EntityHitResult hitResult) {

    UUID uuid = player.getUuid();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    if (user != null && user.isInLobby.get()) {

      // Prevent attacking players
      if (entity instanceof ServerPlayerEntity && !AuthCoreServer.config.lobby.allowAttackingPlayer)
        return AuthCoreServer.LOGGER.toUser(
            net.ded3ec.compat.Compat.actionResultFail(),
            user.connection,
            AuthCoreServer.messages.promptUserAttackPlayerNotAllowed);

      // Prevent attacking hostile mobs
      if (entity.getType().getSpawnGroup() == SpawnGroup.MONSTER
          && !AuthCoreServer.config.lobby.allowAttackingHostileMobs)
        return AuthCoreServer.LOGGER.toUser(
            net.ded3ec.compat.Compat.actionResultFail(),
            user.connection,
            AuthCoreServer.messages.promptUserAttackHostileMobsNotAllowed);

      // Prevent attacking animals
      if (entity instanceof AnimalEntity && !AuthCoreServer.config.lobby.allowAttackingAnimals)
        return AuthCoreServer.LOGGER.toUser(
            net.ded3ec.compat.Compat.actionResultFail(),
            user.connection,
            AuthCoreServer.messages.promptUserAttackAnimalNotAllowed);

      // Prevent attacking friendly mobs
      if (entity.getType().getSpawnGroup() == SpawnGroup.CREATURE
          && !AuthCoreServer.config.lobby.allowAttackingFriendlyMobs)
        return AuthCoreServer.LOGGER.toUser(
            net.ded3ec.compat.Compat.actionResultFail(),
            user.connection,
            AuthCoreServer.messages.promptUserAttackFriendlyMobsNotAllowed);

      // Prevent attacking neutral mobs
      if (entity instanceof MobEntity
          && !(entity instanceof AnimalEntity)
          && !AuthCoreServer.config.lobby.allowAttackNeutralMobs)
        return AuthCoreServer.LOGGER.toUser(
            net.ded3ec.compat.Compat.actionResultFail(),
            user.connection,
            AuthCoreServer.messages.promptUserAttackNeutralMobsNotAllowed);

      // Prevent attacking mountable entities
      if (net.ded3ec.compat.Compat.isMountable(entity)
          && !AuthCoreServer.config.lobby.allowAttackMountableEntity)
        return AuthCoreServer.LOGGER.toUser(
            net.ded3ec.compat.Compat.actionResultFail(),
            user.connection,
            AuthCoreServer.messages.promptUserInteractMountableEntityNotAllowed);

      // Prevent attacking any entity
      if (!AuthCoreServer.config.lobby.allowAttackEntity)
        return AuthCoreServer.LOGGER.toUser(
            net.ded3ec.compat.Compat.actionResultFail(),
            user.connection,
            AuthCoreServer.messages.promptUserInteractEntityNotAllowed);

    } else if (user != null) user.lastCombatDetectMs = System.currentTimeMillis();

    return net.ded3ec.compat.Compat.actionResultPass();
  }

  /** Handles entity interaction events. */
  public static ActionResult onEntityUse(
      PlayerEntity player,
      World world,
      Hand hand,
      Entity entity,
      @Nullable EntityHitResult hitResult) {

    UUID uuid = player.getUuid();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    if (user != null && user.isInLobby.get()) {

      if (entity instanceof ServerPlayerEntity
          && !AuthCoreServer.config.lobby.allowPlayerInteractWith)
        return AuthCoreServer.LOGGER.toUser(
            net.ded3ec.compat.Compat.actionResultFail(),
            user.connection,
            AuthCoreServer.messages.promptUserInteractPlayersNotAllowed);

      if (entity.getType().getSpawnGroup() == SpawnGroup.MONSTER
          && !AuthCoreServer.config.lobby.allowHostileMobsInteractWith)
        return AuthCoreServer.LOGGER.toUser(
            net.ded3ec.compat.Compat.actionResultFail(),
            user.connection,
            AuthCoreServer.messages.promptUserInteractHostileMobsNotAllowed);

      if (entity instanceof AnimalEntity && !AuthCoreServer.config.lobby.allowAnimalInteractWith)
        return AuthCoreServer.LOGGER.toUser(
            net.ded3ec.compat.Compat.actionResultFail(),
            user.connection,
            AuthCoreServer.messages.promptUserInteractAnimalsNotAllowed);

      if (entity.getType().getSpawnGroup() == SpawnGroup.CREATURE
          && !AuthCoreServer.config.lobby.allowFriendlyMobsInteractWith)
        return AuthCoreServer.LOGGER.toUser(
            net.ded3ec.compat.Compat.actionResultFail(),
            user.connection,
            AuthCoreServer.messages.promptUserInteractFriendlyMobsNotAllowed);

      if (entity instanceof MobEntity
          && !(entity instanceof AnimalEntity)
          && !AuthCoreServer.config.lobby.allowNeutralMobsInteractWith)
        return AuthCoreServer.LOGGER.toUser(
            net.ded3ec.compat.Compat.actionResultFail(),
            user.connection,
            AuthCoreServer.messages.promptUserInteractNeutralMobsNotAllowed);

      if (net.ded3ec.compat.Compat.isMountable(entity)
          && !AuthCoreServer.config.lobby.allowMountableInteractWith)
        return AuthCoreServer.LOGGER.toUser(
            net.ded3ec.compat.Compat.actionResultFail(),
            user.connection,
            AuthCoreServer.messages.promptUserInteractMountableEntityNotAllowed);

      if (!AuthCoreServer.config.lobby.allowEntityInteractWith)
        return AuthCoreServer.LOGGER.toUser(
            net.ded3ec.compat.Compat.actionResultFail(),
            user.connection,
            AuthCoreServer.messages.promptUserInteractEntityNotAllowed);
    }

    return net.ded3ec.compat.Compat.actionResultPass();
  }

  /** Handles entity damage events. */
  public static boolean onEntityDamage(LivingEntity entity, DamageSource source, float amount) {

    if (!(entity instanceof ServerPlayerEntity player)) return true;

    UUID uuid = player.getUuid();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    if (user != null && user.isInLobby.get()) {

      // Mob damage
      if (source.getAttacker() instanceof MobEntity)
        return AuthCoreServer.config.lobby.allowMobDamage;

      // Player damage
      if (AuthCoreServer.config.lobby.preventPlayerDamage
          && source.getAttacker() instanceof ServerPlayerEntity attacker)
        return AuthCoreServer.LOGGER.toUser(
            false,
            attacker.networkHandler,
            AuthCoreServer.messages.promptUserAttackLobbyUserNotAllowed);

      // All damage
      return !AuthCoreServer.config.lobby.preventDamage;

    } else if (user != null) user.lastCombatDetectMs = System.currentTimeMillis();

    return true;
  }
}
