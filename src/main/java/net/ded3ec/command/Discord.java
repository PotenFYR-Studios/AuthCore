package net.ded3ec.command;

import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.CommandDispatcher;
import java.security.SecureRandom;
import java.util.UUID;
import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.User;
import net.ded3ec.network.McApiManager;
import net.ded3ec.network.RedisManager;
import net.ded3ec.network.Webhook;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/**
 * Discord account linking.
 *
 * <p>{@code /discord link} generates a short link code, publishes it to the role-sync webhook
 * (and stores it in Redis), and the player sends the code to the server's Discord bot. The bot
 * completes the link by calling the web panel API ({@code POST /api/action} with
 * {@code action: "link"}) or the AuthCoreApi. The bot never touches the database - the backend
 * (this mod) executes every write; the bot talks to the backend over Redis + this API.
 */
public class Discord {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String CODE_CHARSET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

  private Discord() {}

  /** Registers the /discord command tree. */
  public static void load(CommandDispatcher<CommandSourceStack> dispatcher) {
    dispatcher.register(
        literal("discord")
            .then(
                literal("link")
                    .requires(
                        (ctx) -> {
                          ServerPlayer player = McApiManager.PermissionUtil.resolvePlayer(ctx);
                          if (player == null) return false;
                          User user = User.getUser(player.getName().getString(), player.getUUID());
                          return user != null
                              && user.isRegistered.get()
                              && McApiManager.PermissionUtil.has(
                                  player,
                                  AuthCoreServer.config.commands.user.logout.luckPermsNode,
                                  AuthCoreServer.config.commands.user.logout.permissionsLevel);
                        })
                    .executes(ctx -> linkCommand(ctx.getSource())))
            .then(
                literal("unlink")
                    .requires(
                        (ctx) -> {
                          ServerPlayer player = McApiManager.PermissionUtil.resolvePlayer(ctx);
                          if (player == null) return false;
                          User user = User.getUser(player.getName().getString(), player.getUUID());
                          return user != null
                              && user.isRegistered.get()
                              && McApiManager.PermissionUtil.has(
                                  player,
                                  AuthCoreServer.config.commands.user.logout.luckPermsNode,
                                  AuthCoreServer.config.commands.user.logout.permissionsLevel);
                        })
                    .executes(ctx -> unlinkCommand(ctx.getSource()))));
  }

  /** Generates a link code and publishes it (webhook + Redis) for the Discord bot. */
  private static int linkCommand(CommandSourceStack source) {
    try {
      ServerPlayer player = net.ded3ec.compat.Compat.sourcePlayer(source);
      if (player == null)
        return AuthCoreServer.LOGGER.info(0, "This command can't be executed from console!");

      User user = User.getUser(player.getName().getString(), player.getUUID());
      if (user == null)
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserInvalidCredentials);

      if (user.discordId != null && !user.discordId.isEmpty())
        return AuthCoreServer.LOGGER.toUser(
            1,
            player.connection,
            AuthCoreServer.messages.promptUserDiscordAlreadyLinked);

      // Link codes are broadcast to the role-sync webhook: without a limiter the command
      // spams the webhook (and burns Redis keys). One code per minute per player.
      if (!net.ded3ec.security.RateLimiter.tryAcquire("discordlink:" + player.getUUID(), 1, 60_000L))
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserCommandCooldown, "a minute");

      StringBuilder code = new StringBuilder(6);
      for (int i = 0; i < 6; i++)
        code.append(CODE_CHARSET.charAt(RANDOM.nextInt(CODE_CHARSET.length())));

      // Store the code for the bot (Redis when available, otherwise local-only webhook flow)
      RedisManager.storeDiscordLinkCode(code.toString(), user.username);

      Webhook.send(
          ":link: **Discord link code** for `" + user.username + "`: **" + code + "** "
              + "(valid 10 minutes - send it to the server's Discord bot to link your account)");

      net.ded3ec.security.SecurityLog.log(
          "DISCORD_LINK_CODE", user.username + " requested a Discord link code");

      return AuthCoreServer.LOGGER.toUser(
          1, player.connection, AuthCoreServer.messages.promptUserDiscordLinkCode, code);
    } catch (Exception err) {
      return AuthCoreServer.LOGGER.error(0, "Faced Error in '/discord link' Command: ", err);
    }
  }

  /** Removes the Discord link from the account. */
  private static int unlinkCommand(CommandSourceStack source) {
    try {
      ServerPlayer player = net.ded3ec.compat.Compat.sourcePlayer(source);
      if (player == null)
        return AuthCoreServer.LOGGER.info(0, "This command can't be executed from console!");

      User user = User.getUser(player.getName().getString(), player.getUUID());
      if (user == null)
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserInvalidCredentials);

      if (user.discordId == null || user.discordId.isEmpty())
        return AuthCoreServer.LOGGER.toUser(
            1, player.connection, AuthCoreServer.messages.promptUserDiscordNotLinked);

      user.discordId = null;
      user.update("Discord unlinked");
      net.ded3ec.security.SecurityLog.log("DISCORD_UNLINK", user.username + " unlinked Discord");

      return AuthCoreServer.LOGGER.toUser(
          1, player.connection, AuthCoreServer.messages.promptUserDiscordUnlinked);
    } catch (Exception err) {
      return AuthCoreServer.LOGGER.error(0, "Faced Error in '/discord unlink' Command: ", err);
    }
  }
}
