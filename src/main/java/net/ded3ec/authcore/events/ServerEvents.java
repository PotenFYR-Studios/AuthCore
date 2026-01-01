package net.ded3ec.authcore.events;

import java.util.UUID;
import net.ded3ec.authcore.AuthCore;
import net.ded3ec.authcore.models.Lobby;
import net.ded3ec.authcore.models.User;
import net.ded3ec.authcore.utils.Logger;
import net.ded3ec.authcore.utils.Misc;
import net.ded3ec.authcore.utils.Security;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.network.message.MessageType;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;

/** Handles various server events for the Minecraft server. */
public class ServerEvents {

  /**
   * Handles the event when a player joins the server.
   *
   * @param handler The network handler for the player.
   * @param sender The packet sender.
   * @param server The Minecraft server instance.
   */
  public static void onPlayerJoin(
      ServerPlayNetworkHandler handler, PacketSender sender, MinecraftServer server) {
    UUID uuid = handler.getPlayer().getUuid();
    String username = handler.getPlayer().getName().getString();
    User user = User.getUser(username, uuid);

    // Check if the user is already online and handle duplicate login.
    if (user != null && user.isActive) {
      if (user.isInLobby.get() && AuthCore.config.session.authentication.blockDuplicateRegister) {
        Logger.toKick(false, handler, AuthCore.messages.promptUserAnotherAccountIsRegistering);
        return;
      } else if (user.isAuthenticated.get()
          && AuthCore.config.session.authentication.blockDuplicateSession) {
        Logger.toKick(false, handler, AuthCore.messages.promptUserAnotherAccountSession);
        return;
      }
    } else if (user == null)
      user =
          new User(
              handler.player.getUuid(),
              handler.player.getName().getString(),
              System.currentTimeMillis(),
              handler
                  .player
                  .getName()
                  .getString()
                  .equals(Misc.getPremiumUsername(handler.player.getUuid())));

    // Establish the user's connection and session.
    user.connect(handler);

    // Handle various authentication and session rules.
    if (!AuthCore.config.session.authentication.allowProxyUsers && user.isProxy.get())
      Logger.toKick(false, handler, AuthCore.messages.promptUserProxyNotAllowed);
    else if (AuthCore.config.session.sessionFromSameIPOnly
        && user.isRegistered.get()
        && (user.ipAddress != null)
        && (!user.ipAddress.equals(handler.player.getIp())))
      Logger.toKick(false, handler, AuthCore.messages.promptUserDifferentIpLoginNotAllowed);
    else if (!AuthCore.config.session.authentication.allowCrackedPremiumNames
        && Misc.getPreimumUuid(user.username) != null
        && Misc.getPremiumUsername(handler.getPlayer().getUuid()) == null)
      user.kick(AuthCore.messages.promptUserPremiumNameNotAllowed);
    else if (user.uuid.equals(handler.player.getUuid())
        && (user.ipAddress != null)
        && (user.ipAddress.equals(handler.player.getIp()))
        && user.isActiveSession.get()) {
      Logger.debug(true, "{} skipped the authentication and resumed his session!", user.username);
      Logger.toUser(true, handler, AuthCore.messages.promptUserSessionResumed);

      user.login(handler.getPlayer());

    } else if (user.uuid.equals(handler.player.getUuid())
        && AuthCore.config.session.authentication.premiumAutoLogin
        && user.isPremium) {
      Logger.debug(true, "{} is a online player and skipped the authentication!", user.username);
      Logger.toUser(true, handler, AuthCore.messages.promptUserPremiumAutoLogin);

      if (AuthCore.config.session.authentication.premiumAutoRegister && !user.isRegistered.get())
        user.register(handler.getPlayer(), Security.Password.generate(20));
      else if (user.isRegistered.get()) user.login(handler.getPlayer());
      else user.lobby.lock();

    } else if (!user.uuid.equals(handler.player.getUuid())
        && user.isPremium
        && AuthCore.config.session.authentication.premiumAutoLogin)
      user.kick(AuthCore.messages.promptUserPremiumDifferentUUID);
    else if (user.lastKickedMs > 0
        && (AuthCore.config.session.cooldownAfterKickMs
            > (System.currentTimeMillis() - user.lastKickedMs)))
      user.kick(
          AuthCore.messages.promptUserCooldownAfterKickNotExpired,
          Misc.TimeConverter.toDuration(
              (System.currentTimeMillis() - user.lastKickedMs)
                  - AuthCore.config.session.cooldownAfterKickMs));
    else if (AuthCore.config.lobby.maxLobbyUsers > 0
        && AuthCore.config.lobby.maxLobbyUsers <= Lobby.users.size())
      user.kick(AuthCore.messages.promptUserMaxLobbyUsersReached);
    else user.lobby.lock();
  }

  /**
   * Handles the event when a player leaves the server.
   *
   * @param handler The network handler for the player.
   * @param server The Minecraft server instance.
   */
  public static void onPlayerLeave(ServerPlayNetworkHandler handler, MinecraftServer server) {
    UUID uuid = handler.getPlayer().getUuid();
    String username = handler.getPlayer().getName().getString();
    User user = User.getUser(username, uuid);

    if (user != null) {
      if (user.isInLobby.get()) user.lobby.unlock(); // Unlock the user from the lobby.

      user.isActive = false; // Mark the user as offline.

      user.isInCombactPenalty =
          user.lastCombactDetectMs > 0
              && AuthCore.config.session.combatTimeout > 0
              && ((System.currentTimeMillis() - user.lastCombactDetectMs)
                  < AuthCore.config.session.combatTimeout);

      if (user.isInCombactPenalty)
        Logger.debug(true, "{} is suspicious & tried to skip death penalty!", user.username);

      user.update("Player Leave Cache");
    }
  }

  /**
   * Handles the end of each server tick event.
   *
   * @param server The Minecraft server instance.
   */
  public static void onEndServerTick(MinecraftServer server) {
    Misc.TpsMeter.onTick();
    Misc.TaskScheduler.getInstance().onTick();
  }

  /**
   * Handles the event when a player sends a chat message.
   *
   * @param message The signed chat message.
   * @param player The player sending the message.
   * @param parameters The message type parameters.
   * @return True if the message is allowed, false otherwise.
   */
  public static boolean onAllowChatMessage(
      SignedMessage message, ServerPlayerEntity player, MessageType.Parameters parameters) {
    UUID uuid = player.getUuid();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    if (user != null && user.isInLobby.get()) {

      // Deny chatting if not allowed in the lobby.
      if (!AuthCore.config.lobby.allowChat)
        return Logger.toUser(false, user.handler, AuthCore.messages.promptUserChatNotAllowed);
    }

    return true; // Allow the message by default.
  }
}
