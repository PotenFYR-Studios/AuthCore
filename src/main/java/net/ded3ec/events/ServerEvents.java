package net.ded3ec.events;

import java.util.Objects;
import java.util.UUID;

import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.Lobby;
import net.ded3ec.models.User;
import net.ded3ec.utils.TimeManager;
import net.ded3ec.utils.*;

import net.fabricmc.fabric.api.networking.v1.PacketSender;

import net.minecraft.network.message.SignedMessage;

import net.minecraft.network.message.MessageType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;

public class ServerEvents {

  /** Player join handler */
  public static void onPlayerJoin(
      ServerPlayNetworkHandler connection, PacketSender sender, MinecraftServer server) {

    ServerPlayerEntity player = connection.player;

    UUID uuid = player.getUuid();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    // Duplicate login handling
    if (user != null && user.isActive) {

      if (user.isInLobby.get()
          && AuthCoreServer.config.session.authentication.blockDuplicateRegister) {
        AuthCoreServer.LOGGER.toKick(
            false, connection, AuthCoreServer.messages.promptUserAnotherAccountIsRegistering);
        return;
      }

      if (user.isAuthenticated.get()
          && AuthCoreServer.config.session.authentication.blockDuplicateSession) {
        AuthCoreServer.LOGGER.toKick(
            false, connection, AuthCoreServer.messages.promptUserAnotherAccountSession);
        return;
      }

    } else if (user == null)
      user =
          new User(
              uuid,
              username,
              System.currentTimeMillis(),
              (Objects.equals(AuthCoreServer.config.session.serverMode, "online"))
                  || username.equals(McApiManager.getPremiumUsername(uuid)));

    // Establish connection
    user.connect(connection);

    // Proxy restriction
    if (!AuthCoreServer.config.session.authentication.allowProxyUsers && user.isProxy.get()) {
      AuthCoreServer.LOGGER.toKick(
          false, connection, AuthCoreServer.messages.promptUserProxyNotAllowed);
      return;
    }

    // Same-IP restriction
    if (AuthCoreServer.config.session.sessionFromSameIPOnly
        && user.isRegistered.get()
        && user.ipAddress != null
        && !user.ipAddress.equals(player.getIp())) {

      AuthCoreServer.LOGGER.toKick(
          false, connection, AuthCoreServer.messages.promptUserDifferentIpLoginNotAllowed);
      return;
    }

    // Premium-name restriction
    if (!AuthCoreServer.config.session.authentication.allowOnlineNameByOffline
        && McApiManager.getPremiumUuid(user.username) != null
        && McApiManager.getPremiumUsername(uuid) == null) {

      user.kick(AuthCoreServer.messages.promptUserPremiumNameNotAllowed);
      return;
    }

    // Resume active session
    if (user.uuid.equals(uuid)
        && user.ipAddress != null
        && user.ipAddress.equals(player.getIp())
        && user.isActiveSession.get()) {

      AuthCoreServer.LOGGER.debug(
          true, "{} skipped authentication and resumed session!", user.username);

      AuthCoreServer.LOGGER.toUser(
          true, connection, AuthCoreServer.messages.promptUserSessionResumed);

      user.login(player);
      return;
    }

    // Premium auto-login
    if (user.uuid.equals(uuid)
        && AuthCoreServer.config.session.authentication.premiumAutoLogin
        && user.isPremium) {

      AuthCoreServer.LOGGER.debug(true, "{} is premium and skipped authentication!", user.username);

      AuthCoreServer.LOGGER.toUser(
          true, connection, AuthCoreServer.messages.promptUserPremiumAutoLogin);

      if (AuthCoreServer.config.session.authentication.premiumAutoRegister
          && !user.isRegistered.get()) user.register(player, Security.Password.generate(20));
      else if (user.isRegistered.get()) user.login(player);
      else user.lobby.lock();

      return;
    }

    // Premium UUID mismatch
    if (!user.uuid.equals(uuid)
        && user.isPremium
        && AuthCoreServer.config.session.authentication.premiumAutoLogin) {

      user.kick(AuthCoreServer.messages.promptUserPremiumDifferentUUID);
      return;
    }

    // Kick cooldown
    if (user.lastKickedMs > 0
        && AuthCoreServer.config.session.cooldownAfterKickMs
            > (System.currentTimeMillis() - user.lastKickedMs)) {

      user.kick(
          AuthCoreServer.messages.promptUserCooldownAfterKickNotExpired,
          TimeManager.toDuration(
              (System.currentTimeMillis() - user.lastKickedMs)
                  - AuthCoreServer.config.session.cooldownAfterKickMs));
      return;
    }

    // Lobby full
    if (AuthCoreServer.config.lobby.maxLobbyUsers > 0
        && AuthCoreServer.config.lobby.maxLobbyUsers <= Lobby.users.size()) {

      user.kick(AuthCoreServer.messages.promptUserMaxLobbyUsersReached);
      return;
    }

    // Default: lock user in lobby
    user.lobby.lock();
  }

  /** Player leave handler */
  public static void onPlayerLeave(ServerPlayNetworkHandler connection, MinecraftServer server) {

    ServerPlayerEntity player = connection.player;

    UUID uuid = player.getUuid();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    if (user != null) {

      if (user.isInLobby.get()) user.lobby.unlock();

      user.isActive = false;

      user.isInCombatPenalty =
          user.lastCombatDetectMs > 0
              && AuthCoreServer.config.session.combatTimeout > 0
              && ((System.currentTimeMillis() - user.lastCombatDetectMs)
                  < AuthCoreServer.config.session.combatTimeout);

      if (user.isInCombatPenalty)
        AuthCoreServer.LOGGER.debug(true, "{} attempted to skip death penalty!", user.username);

      user.update("Player Leave Cache");
    }
  }

  /** Server tick handler */
  public static void onEndServerTick(MinecraftServer server) {
    TpsManager.onTick();
    TaskScheduler.getInstance().onTick();
  }

  /** Chat message handler */
  public static boolean onAllowChatMessage(
      SignedMessage message, ServerPlayerEntity player, MessageType.Parameters params) {

    UUID uuid = player.getUuid();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    if (user != null && user.isInLobby.get()) {

      if (!AuthCoreServer.config.lobby.allowChat)
        return AuthCoreServer.LOGGER.toUser(
            false, player.networkHandler, AuthCoreServer.messages.promptUserChatNotAllowed);
    }

    return true;
  }
}
