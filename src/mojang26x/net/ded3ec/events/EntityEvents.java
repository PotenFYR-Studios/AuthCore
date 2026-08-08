package net.ded3ec.events;

import net.ded3ec.models.Config;
import net.ded3ec.models.Lobby;
import net.ded3ec.models.Messages;
import net.ded3ec.util.Logger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import java.util.UUID;

import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.User;
import org.jetbrains.annotations.Nullable;

public class EntityEvents {

  /** Handles entity attack events. */
  public static InteractionResult onEntityAttack(
      Player player,
      Level world,
      InteractionHand hand,
      Entity entity,
      @Nullable EntityHitResult hitResult) {

    UUID uuid = player.getUUID();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    if (user != null && user.isInLobby.get()) {

      // Prevent attacking players
      if (entity instanceof ServerPlayer && !AuthCoreServer.config.lobby.allowAttackingPlayer)
        return AuthCoreServer.LOGGER.toUser(
            InteractionResult.FAIL,
            user.connection,
            AuthCoreServer.messages.promptUserAttackPlayerNotAllowed);

      // Prevent attacking hostile mobs
      if (entity.getType().getCategory() == MobCategory.MONSTER
          && !AuthCoreServer.config.lobby.allowAttackingHostileMobs)
        return AuthCoreServer.LOGGER.toUser(
            InteractionResult.FAIL,
            user.connection,
            AuthCoreServer.messages.promptUserAttackHostileMobsNotAllowed);

      // Prevent attacking animals
      if (entity instanceof Animal && !AuthCoreServer.config.lobby.allowAttackingAnimals)
        return AuthCoreServer.LOGGER.toUser(
            InteractionResult.FAIL,
            user.connection,
            AuthCoreServer.messages.promptUserAttackAnimalNotAllowed);

      // Prevent attacking friendly mobs
      if (entity.getType().getCategory() == MobCategory.CREATURE
          && !AuthCoreServer.config.lobby.allowAttackingFriendlyMobs)
        return AuthCoreServer.LOGGER.toUser(
            InteractionResult.FAIL,
            user.connection,
            AuthCoreServer.messages.promptUserAttackFriendlyMobsNotAllowed);

      // Prevent attacking neutral mobs
      if (entity instanceof Mob
          && !(entity instanceof Animal)
          && !AuthCoreServer.config.lobby.allowAttackNeutralMobs)
        return AuthCoreServer.LOGGER.toUser(
            InteractionResult.FAIL,
            user.connection,
            AuthCoreServer.messages.promptUserAttackNeutralMobsNotAllowed);

      // Prevent attacking mountable entities
      if (net.ded3ec.compat.Compat.isMountable(entity)
          && !AuthCoreServer.config.lobby.allowAttackMountableEntity)
        return AuthCoreServer.LOGGER.toUser(
            InteractionResult.FAIL,
            user.connection,
            AuthCoreServer.messages.promptUserInteractMountableEntityNotAllowed);

      // Prevent attacking any entity
      if (!AuthCoreServer.config.lobby.allowAttackEntity)
        return AuthCoreServer.LOGGER.toUser(
            InteractionResult.FAIL,
            user.connection,
            AuthCoreServer.messages.promptUserInteractEntityNotAllowed);

    } else if (user != null) user.lastCombatDetectMs = System.currentTimeMillis();

    return InteractionResult.PASS;
  }

  /** Handles entity interaction events. */
  public static InteractionResult onEntityUse(
      Player player,
      Level world,
      InteractionHand hand,
      Entity entity,
      @Nullable EntityHitResult hitResult) {

    UUID uuid = player.getUUID();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    if (user != null && user.isInLobby.get()) {

      if (entity instanceof ServerPlayer
          && !AuthCoreServer.config.lobby.allowPlayerInteractWith)
        return AuthCoreServer.LOGGER.toUser(
            InteractionResult.FAIL,
            user.connection,
            AuthCoreServer.messages.promptUserInteractPlayersNotAllowed);

      if (entity.getType().getCategory() == MobCategory.MONSTER
          && !AuthCoreServer.config.lobby.allowHostileMobsInteractWith)
        return AuthCoreServer.LOGGER.toUser(
            InteractionResult.FAIL,
            user.connection,
            AuthCoreServer.messages.promptUserInteractHostileMobsNotAllowed);

      if (entity instanceof Animal && !AuthCoreServer.config.lobby.allowAnimalInteractWith)
        return AuthCoreServer.LOGGER.toUser(
            InteractionResult.FAIL,
            user.connection,
            AuthCoreServer.messages.promptUserInteractAnimalsNotAllowed);

      if (entity.getType().getCategory() == MobCategory.CREATURE
          && !AuthCoreServer.config.lobby.allowFriendlyMobsInteractWith)
        return AuthCoreServer.LOGGER.toUser(
            InteractionResult.FAIL,
            user.connection,
            AuthCoreServer.messages.promptUserInteractFriendlyMobsNotAllowed);

      if (entity instanceof Mob
          && !(entity instanceof Animal)
          && !AuthCoreServer.config.lobby.allowNeutralMobsInteractWith)
        return AuthCoreServer.LOGGER.toUser(
            InteractionResult.FAIL,
            user.connection,
            AuthCoreServer.messages.promptUserInteractNeutralMobsNotAllowed);

      if (net.ded3ec.compat.Compat.isMountable(entity)
          && !AuthCoreServer.config.lobby.allowMountableInteractWith)
        return AuthCoreServer.LOGGER.toUser(
            InteractionResult.FAIL,
            user.connection,
            AuthCoreServer.messages.promptUserInteractMountableEntityNotAllowed);

      if (!AuthCoreServer.config.lobby.allowEntityInteractWith)
        return AuthCoreServer.LOGGER.toUser(
            InteractionResult.FAIL,
            user.connection,
            AuthCoreServer.messages.promptUserInteractEntityNotAllowed);
    }

    return InteractionResult.PASS;
  }

  /** Handles entity damage events. */
  public static boolean onEntityDamage(LivingEntity entity, DamageSource source, float amount) {

    if (!(entity instanceof ServerPlayer player)) return true;

    UUID uuid = player.getUUID();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    if (user != null && user.isInLobby.get()) {

      // Mob damage
      if (source.getEntity() instanceof Mob)
        return AuthCoreServer.config.lobby.allowMobDamage;

      // Player damage
      if (AuthCoreServer.config.lobby.preventPlayerDamage
          && source.getEntity() instanceof ServerPlayer attacker)
        return AuthCoreServer.LOGGER.toUser(
            false,
            attacker.connection,
            AuthCoreServer.messages.promptUserAttackLobbyUserNotAllowed);

      // All damage
      return !AuthCoreServer.config.lobby.preventDamage;

    } else if (user != null) user.lastCombatDetectMs = System.currentTimeMillis();

    return true;
  }
}
