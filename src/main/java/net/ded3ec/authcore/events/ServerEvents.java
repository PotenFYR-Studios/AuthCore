package net.ded3ec.authcore.events;

import net.ded3ec.authcore.AuthCore;
import net.ded3ec.authcore.models.Lobby;
import net.ded3ec.authcore.models.User;
import net.ded3ec.authcore.utils.Logger;
import net.ded3ec.authcore.utils.Misc;
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
    User user = User.users.get(handler.player.getName().getString());

    // Check if the user is already online and handle duplicate login.
    if (user != null && user.isOnline) {
      Logger.toKick(
          false,
          handler,
          AuthCore.messages.anotherAccountLoggedIn,
          handler.player.getName().getString());
      return;
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
      user.kick(AuthCore.messages.proxyNotAllowed);
    else if (AuthCore.config.session.authentication.blockDuplicateLogin && user.isInLobby.get())
      user.kick(AuthCore.messages.duplicateLoginNotAllowed);
    else if (AuthCore.config.session.sessionFromSameIPOnly
        && user.isRegistered.get()
        && (!user.ipAddress.equals(handler.player.getIp())))
      user.kick(AuthCore.messages.differentIpLoginNotAllowed);
    else if (!AuthCore.config.session.authentication.allowCrackedPremiumNames
        && user.isPremiumUsername.get()
        && !user.isPremiumUuid.get()) user.kick(AuthCore.messages.premiumNameNotAllowed);
    else if (user.uuid.equals(handler.player.getUuid()) && user.isActiveSession.get()) {
      Logger.debug(true, "{} skipped the authentication and resumed his session!", user.username);
      user.login(handler.getPlayer());
    } else if (user.uuid.equals(handler.player.getUuid())
        && AuthCore.config.session.authentication.premiumAutoLogin
        && user.isPremium) {
      Logger.debug(true, "{} is a online player and skipped the authentication!", user.username);
      user.login(handler.getPlayer());
    } else if (user.lastKickedMs > 0
        && (AuthCore.config.session.cooldownAfterKickMs
            > (System.currentTimeMillis() - user.lastKickedMs)))
      user.kick(
          AuthCore.messages.cooldownAfterKickNotExpired,
          Misc.TimeConverter.toDuration(
              AuthCore.config.session.cooldownAfterKickMs
                  - (System.currentTimeMillis() - user.lastKickedMs)));
    else if (AuthCore.config.lobby.maxJailedUsers > 0
        && AuthCore.config.lobby.maxJailedUsers <= Lobby.users.size())
      user.kick(AuthCore.messages.maxJailedUsersReached);
    else user.lobby.lock(); // Lock the user in the lobby framework.
  }

  /**
   * Handles the event when a player leaves the server.
   *
   * @param handler The network handler for the player.
   * @param server The Minecraft server instance.
   */
  public static void onPlayerLeave(ServerPlayNetworkHandler handler, MinecraftServer server) {
    User user = User.users.get(handler.player.getName().getString());

    if (user != null) {
      if (user.isInLobby.get()) user.lobby.unlock(); // Unlock the user from the lobby.
      user.isOnline = false; // Mark the user as offline.
    }
  }

  /**
   * Handles the end of each server tick event.
   *
   * @param server The Minecraft server instance.
   */
  public static void onEndServerTick(MinecraftServer server) {
    Misc.TpsMeter.onTick(); // Update the TPS meter.
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
    User user = User.users.get(player.getName().getString());

    if (user != null && user.isInLobby.get()) {

      // Deny chatting if not allowed in the lobby.
      if (!AuthCore.config.lobby.allowChat)
        return Logger.toUser(false, user.handler, AuthCore.messages.chatNotAllowed);
    }

    return true; // Allow the message by default.
  }
}
