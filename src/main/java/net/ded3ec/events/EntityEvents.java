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

  /**
   * Loader-neutral limbo guard for ENTITY ATTACKS: applies the configured per-entity-type
   * restrictions and reports the violation. Returns {@code true} when the attack must be
   * blocked.
   *
   * <p>Shared by the Fabric attack callback AND the packet-level interact mixin, so
   * Forge/NeoForge builds (which have no fabric-api callbacks) enforce exactly the same
   * rules - previously attacks were completely unenforced there.
   */
  public static boolean guardLobbyAttack(User user, Entity entity) {

    // Prevent attacking players
    if (entity instanceof ServerPlayer && !AuthCoreServer.config.lobby.allowAttackingPlayer) {
      AuthCoreServer.LOGGER.violation(
          false,
          user,
          user.connection,
          AuthCoreServer.messages.promptUserAttackPlayerNotAllowed);
      return true;
    }
    if (entity.getType().getCategory() == MobCategory.MONSTER
        && !AuthCoreServer.config.lobby.allowAttackingHostileMobs) {
      AuthCoreServer.LOGGER.violation(
          false,
          user,
          user.connection,
          AuthCoreServer.messages.promptUserAttackHostileMobsNotAllowed);
      return true;
    }

    // Prevent attacking animals
    if (entity instanceof Animal && !AuthCoreServer.config.lobby.allowAttackingAnimals) {
      AuthCoreServer.LOGGER.violation(
          false,
          user,
          user.connection,
          AuthCoreServer.messages.promptUserAttackAnimalNotAllowed);
      return true;
    }

    // Prevent attacking friendly mobs
    if (entity.getType().getCategory() == MobCategory.CREATURE
        && !AuthCoreServer.config.lobby.allowAttackingFriendlyMobs) {
      AuthCoreServer.LOGGER.violation(
          false,
          user,
          user.connection,
          AuthCoreServer.messages.promptUserAttackFriendlyMobsNotAllowed);
      return true;
    }

    // Prevent attacking neutral mobs
    if (entity instanceof Mob
        && !(entity instanceof Animal)
        && !AuthCoreServer.config.lobby.allowAttackNeutralMobs) {
      AuthCoreServer.LOGGER.violation(
          false,
          user,
          user.connection,
          AuthCoreServer.messages.promptUserAttackNeutralMobsNotAllowed);
      return true;
    }

    // Prevent attacking mountable entities
    if (net.ded3ec.compat.Compat.isMountable(entity)
        && !AuthCoreServer.config.lobby.allowAttackMountableEntity) {
      AuthCoreServer.LOGGER.violation(
          false,
          user,
          user.connection,
          AuthCoreServer.messages.promptUserInteractMountableEntityNotAllowed);
      return true;
    }

    // Prevent attacking any entity
    if (!AuthCoreServer.config.lobby.allowAttackEntity) {
      AuthCoreServer.LOGGER.violation(
          false,
          user,
          user.connection,
          AuthCoreServer.messages.promptUserInteractEntityNotAllowed);
      return true;
    }

    return false;
  }

  /**
   * Loader-neutral limbo guard for ENTITY INTERACTIONS (right-click): same contract as
   * {@link #guardLobbyAttack} - shared by the Fabric use-entity callback AND the packet
   * mixin so every loader enforces identical rules.
   */
  public static boolean guardLobbyUse(User user, Entity entity) {

    if (entity instanceof ServerPlayer && !AuthCoreServer.config.lobby.allowPlayerInteractWith) {
      AuthCoreServer.LOGGER.violation(
          false,
          user,
          user.connection,
          AuthCoreServer.messages.promptUserInteractPlayersNotAllowed);
      return true;
    }

    if (entity.getType().getCategory() == MobCategory.MONSTER
        && !AuthCoreServer.config.lobby.allowHostileMobsInteractWith) {
      AuthCoreServer.LOGGER.violation(
          false,
          user,
          user.connection,
          AuthCoreServer.messages.promptUserInteractHostileMobsNotAllowed);
      return true;
    }

    if (entity instanceof Animal && !AuthCoreServer.config.lobby.allowAnimalInteractWith) {
      AuthCoreServer.LOGGER.violation(
          false,
          user,
          user.connection,
          AuthCoreServer.messages.promptUserInteractAnimalsNotAllowed);
      return true;
    }

    if (entity.getType().getCategory() == MobCategory.CREATURE
        && !AuthCoreServer.config.lobby.allowFriendlyMobsInteractWith) {
      AuthCoreServer.LOGGER.violation(
          false,
          user,
          user.connection,
          AuthCoreServer.messages.promptUserInteractFriendlyMobsNotAllowed);
      return true;
    }

    if (entity instanceof Mob
        && !(entity instanceof Animal)
        && !AuthCoreServer.config.lobby.allowNeutralMobsInteractWith) {
      AuthCoreServer.LOGGER.violation(
          false,
          user,
          user.connection,
          AuthCoreServer.messages.promptUserInteractNeutralMobsNotAllowed);
      return true;
    }

    if (net.ded3ec.compat.Compat.isMountable(entity)
        && !AuthCoreServer.config.lobby.allowMountableInteractWith) {
      AuthCoreServer.LOGGER.violation(
          false,
          user,
          user.connection,
          AuthCoreServer.messages.promptUserInteractMountableEntityNotAllowed);
      return true;
    }

    if (!AuthCoreServer.config.lobby.allowEntityInteractWith) {
      AuthCoreServer.LOGGER.violation(
          false,
          user,
          user.connection,
          AuthCoreServer.messages.promptUserInteractEntityNotAllowed);
      return true;
    }

    return false;
  }

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

      if (guardLobbyAttack(user, entity))
        return net.ded3ec.compat.Compat.actionResultFail();

    } else if (user != null) {
      // Combat tag for the ATTACKER (config-aware): leaving mid-fight is combat-logging
      // whether the player initiated it or was hit - the same source rules apply.
      net.ded3ec.models.Config.Session.CombatLogConfig cfg = AuthCoreServer.config.session.combatLog;
      if (cfg.enabled) {
        boolean fromPlayer = entity instanceof ServerPlayer;
        boolean fromMob = entity instanceof net.minecraft.world.entity.Mob;
        if ((fromPlayer && cfg.detectFromPlayers)
            || (fromMob && cfg.detectFromMobs)
            || (!fromPlayer && !fromMob && cfg.detectFromEnvironmental))
          user.lastCombatDetectMs = System.currentTimeMillis();
      }
    }

    // Diagnostic: when an authenticated player's attack passes through, log the state so
    // "can't hit mobs after login" reports can be pinpointed (isInLobby / game mode).
    if (user != null && !user.isInLobby.get())
      AuthCoreServer.LOGGER.debug(
          true,
          "Attack allowed for {} (inLobby={}, entity={})",
          username,
          user.isInLobby.get(),
          entity.getType());

    return net.ded3ec.compat.Compat.actionResultPass();
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

      if (guardLobbyUse(user, entity))
        return net.ded3ec.compat.Compat.actionResultFail();
    }

    return net.ded3ec.compat.Compat.actionResultPass();
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
        return AuthCoreServer.LOGGER.violation(
            false,
            user,
            attacker.connection,
            AuthCoreServer.messages.promptUserAttackLobbyUserNotAllowed);

      // All damage
      return !AuthCoreServer.config.lobby.preventDamage;

    } else if (user != null) {
      // Combat tag for the VICTIM (config-aware; the mixin covers every loader - this
      // fabric-callback path keeps the middle 1.19-1.21.1 fabric range covered too).
      net.ded3ec.models.Config.Session.CombatLogConfig cfg = AuthCoreServer.config.session.combatLog;
      if (cfg.enabled) {
        net.minecraft.world.entity.Entity attacker = source.getEntity();
        boolean fromPlayer = attacker instanceof ServerPlayer;
        boolean fromMob = attacker instanceof Mob;
        if ((fromPlayer && cfg.detectFromPlayers)
            || (fromMob && cfg.detectFromMobs)
            || (attacker == null && cfg.detectFromEnvironmental))
          user.lastCombatDetectMs = System.currentTimeMillis();
      }
    }

    return true;
  }
}
