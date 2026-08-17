package net.ded3ec.util;

import net.ded3ec.models.Config;

import java.util.IllegalFormatException;
import java.util.UUID;

import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.Messages;
import net.ded3ec.models.User;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.slf4j.LoggerFactory;

/** Logger Utility for authCore! */
public class Logger {

  private static final boolean SLF4J_AVAILABLE = isSlf4jAvailable();
  private final org.slf4j.Logger logger;

  /** Thread-local user context set by the send methods for placeholder resolution. */
  private final ThreadLocal<User> lastUser = new ThreadLocal<>();

  /**
   * True when the runtime classpath provides slf4j. Modern Fabric setups bundle slf4j, but some
   * environments do not - AuthCore must boot there too.
   */
  private static boolean isSlf4jAvailable() {
    try {
      Class.forName("org.slf4j.LoggerFactory");
      return true;
    } catch (Throwable err) {
      return false;
    }
  }

  public Logger(String MOD_ID) {
    this.logger = SLF4J_AVAILABLE ? LoggerFactory.getLogger(MOD_ID) : null;
  }

  /** Fallback console output used when slf4j is not on the runtime classpath. */
  private void console(String level, String message, Object... args) {
    try {
      System.out.println("[AuthCore/" + level + "] " + String.format(message, args));
    } catch (IllegalFormatException err) {
      System.out.println("[AuthCore/" + level + "] " + message);
    }
  }

  /**
   * Debugging Log helper for authCore with arguments.
   *
   * @param <T> the return type
   * @param value the value to return
   * @param message the debug message
   * @param args the arguments for the message
   * @return the returnValue
   */
  public <T> T debug(T value, String message, Object... args) {
    if (AuthCoreServer.config != null && AuthCoreServer.config.debugMode) {
      if (logger != null) logger.info(message, args);
      else console("INFO", message, args);
    } else {
      if (logger != null) logger.debug(message, args);
      else console("DEBUG", message, args);
    }

    return value;
  }

  /**
   * Warning Log helper for authCore.
   *
   * @param <T> the return type
   * @param value the value to return
   * @param message the warning message
   * @param args the arguments for the message
   * @return the returnValue
   */
  public <T> T warn(T value, String message, Object... args) {
    if (logger != null) logger.warn(message, args);
    else console("WARN", message, args);
    return value;
  }

  /**
   * Error Logging helper for authCore with Objects.
   *
   * @param <T> the return type
   * @param value the value to return
   * @param message the error message
   * @param args the arguments for the message
   * @return the returnValue
   */
  public <T> T error(T value, String message, Object... args) {
    if (logger != null) logger.error(message, args);
    else console("ERROR", message, args);
    return value;
  }

  /**
   * Information Logging helper for authCore with Objects.
   *
   * @param message the info message
   * @param args the arguments for the message
   */
  public <T> T info(T value, String message, Object... args) {
    if (logger != null) logger.info(message, args);
    else console("INFO", message, args);
    return value;
  }

  /**
   * Records a lobby restriction violation: increments the player's violation counter,
   * shows the violation feedback plus how many violations remain before the kick, and
   * kicks the player once the configured limit is reached.
   *
   * @param <T> the return type
   * @param value the value to return
   * @param user the violating user (must be resolvable)
   * @param connection the server play network connection
   * @param payload the violation message payload
   * @param args the arguments for the message
   * @return the returnValue
   */
  public <T> T violation(
      T value, User user, ServerGamePacketListenerImpl connection, Messages.ColTemplate payload,
      Object... args) {
    if (user == null || connection == null || connection.player == null) return value;

    int limit = AuthCoreServer.config.lobby.maxViolationsBeforeKick;
    if (limit <= 0) return toUser(value, connection, payload, args);

    int count = user.incrementViolations();

    if (count >= limit) {
      net.ded3ec.security.SecurityLog.log(
          "LIMBO_VIOLATION_KICK",
          user.username + " exceeded the violation limit (" + limit + ") in the lobby");
      AuthCoreServer.LOGGER.warn(
          false,
          "{} kicked after {} lobby violations (limit {}).",
          user.username,
          count,
          limit);
      return toKick(value, connection, AuthCoreServer.messages.promptUserViolationsExceeded);
    }

    toUser(value, connection, payload, args);

    // Show how many violations remain before the kick
    int remaining = limit - count;
    try {
      toUser(
          value,
          connection,
          AuthCoreServer.messages.promptUserViolationRemaining,
          remaining);
    } catch (RuntimeException ignored) {
      // the remaining-count hint is best-effort
    }
    return value;
  }

  /**
   * Sending Message/Title/Subtitle to the Player in Minecraft Server!
   *
   * @param <T> the return type
   * @param value the value to return
   * @param connection the server play network connection
   * @param payload the color template payload
   * @param args the arguments for the message
   * @return the returnValue
   */
  public <T> T toUser(
      T value, ServerGamePacketListenerImpl connection, Messages.ColTemplate payload, Object... args) {

    if (connection == null || connection.player == null) return value;

    if (!payload.message.text.isBlank()) sendMessage(connection, payload, args);

    if (!payload.actionBar.text.isBlank()) sendActionBar(connection, payload, args);

    if (!payload.title.text.isBlank()) sendTitle(connection, payload, args);

    return value;
  }

  /**
   * Kicking Player Handler.
   *
   * @param <T> the return type
   * @param value the value to return
   * @param connection the server play network connection
   * @param payload the kick template payload
   * @param args the arguments for the message
   * @return the value
   */
  public <T> T toKick(
      T value, ServerGamePacketListenerImpl connection, Messages.KickTemplate payload, Object... args) {
    if (connection == null || connection.player == null || payload == null) return value;

    UUID uuid = connection.player.getUUID();
    String username = connection.player.getName().getString();
    User user = User.getUser(username, uuid);
    lastUser.set(user);
    String message = format(payload.logout.text, args);

    // Feedback channels (message/title/action bar) - only when the user is still resolvable
    if (user != null) this.toUser(false, connection, payload, args);

    // Capture the connection at scheduling time so a delayed disconnect always targets the
    // connection that was online when the kick was requested.
    ServerGamePacketListenerImpl target = connection;
    Component reason = net.ded3ec.compat.Compat.text(message).setStyle(getStyle(payload.logout));

    // The live connection is the source of truth for the disconnect: a user that was removed
    // from the cache/database in the meantime (unregister/delete) must never silently skip
    // the kick - the player would stay online with a broken account state.
    if (payload.logout.delaySec > 0)
      TaskScheduler.getInstance()
          .setTimeout(
              () -> {
                // The player entity + the user's active flag gate the disconnect; a user
                // that was deleted in the meantime (unregister/delete) must NOT prevent it.
                if (target.player != null && (user == null || user.isActive))
                  disconnectSafely(target, reason);
              },
              payload.logout.delaySec * 1000L);
    else if (target.player != null && (user == null || user.isActive))
      disconnectSafely(target, reason);

    return value;
  }

  /** Disconnects a player connection, swallowing any state issues on disconnect. */
  private void disconnectSafely(ServerGamePacketListenerImpl connection, Component reason) {
    try {
      // Never disconnect twice: a second disconnect (e.g. a queued kick racing the
      // client's own close) triggers vanilla's "handleDisconnection() called twice"
      // warning and can leave the connection in a broken state.
      if (connection.player != null && connection.player.hasDisconnected()) return;
      connection.disconnect(reason);
    } catch (Exception err) {
      this.debug(false, "Failed to disconnect a player gracefully:", err);
    }
  }

  /**
   * Formats a template string with arguments, resolving AuthCore placeholders first. Never
   * throws on invalid format strings (e.g. localized text containing '%' characters).
   */
  private String format(String template, Object... args) {
    if (template == null) return "";
    String resolved = resolvePlaceholders(template);
    try {
      return String.format(resolved, args);
    } catch (IllegalFormatException err) {
      this.debug(template, "Invalid format string ({}): {}", template, err.getMessage());
      return template;
    }
  }

  /**
   * Resolves AuthCore placeholders in message templates:
   * {@code %authcore_username%}, {@code %authcore_registered%}, {@code %authcore_premium%},
   * {@code %authcore_online%}, {@code %authcore_country%}, {@code %authcore_risk%},
   * {@code %authcore_locked%}, {@code %authcore_nickname%}.
   */
  private String resolvePlaceholders(String template) {
    if (!template.contains("%authcore_")) return template;

    User user = this.lastUser != null ? this.lastUser.get() : null;
    if (user == null) {
      // Remove unresolved tokens gracefully
      return template.replaceAll("%authcore_[a-z_]+%", "");
    }

    String result = template
        .replace("%authcore_username%", String.valueOf(user.username))
        .replace("%authcore_nickname%", user.nickname != null ? user.nickname : String.valueOf(user.username))
        .replace("%authcore_registered%", String.valueOf(user.isRegistered.get()))
        .replace("%authcore_premium%", String.valueOf(user.isPremium))
        .replace("%authcore_online%", String.valueOf(user.isActive))
        .replace("%authcore_country%", user.country.get() != null ? user.country.get() : "?")
        .replace("%authcore_risk%", String.valueOf(user.riskScore))
        .replace("%authcore_locked%", String.valueOf(user.isLocked()));
    return result;
  }

  /**
   * Get Empty Style with Shadow.
   *
   * @param payload the template payload
   * @return the style with shadow
   */
  private Style getStyleWithShadow(Messages.Template payload) {
    Style style = this.getStyle(payload);
    return net.ded3ec.compat.Compat.applyShadow(style, payload.shadow, payload.shadowStrength);
  }

  /**
   * Get Empty Style without Shadow.
   *
   * @param payload the template payload
   * @return the style
   */
  private Style getStyle(Messages.Template payload) {
    if (payload == null) return Style.EMPTY;

    Style style =
        net.ded3ec.compat.Compat.applyStyleFlags(
            Style.EMPTY,
            payload.bold,
            payload.italic,
            payload.underline,
            payload.strikethrough,
            payload.obfuscate);

    style = setFont(payload, style);

    if (payload.color != null && !payload.color.isBlank()) style = setColor(payload, style);

    return style;
  }

  /**
   * Setting up color for the Text. Maps string color names to Minecraft Formatting enums.
   *
   * @param payload the template payload
   * @param style the current style
   * @return the updated style with color
   */
  private Style setColor(Messages.Template payload, Style style) {
    String color = payload.color;
    if (color.equalsIgnoreCase("red")) style = style.withColor(ChatFormatting.RED);
    else if (color.equalsIgnoreCase("green")) style = style.withColor(ChatFormatting.GREEN);
    else if (color.equalsIgnoreCase("gold")) style = style.withColor(ChatFormatting.GOLD);
    else if (color.equalsIgnoreCase("aqua")) style = style.withColor(ChatFormatting.AQUA);
    else if (color.equalsIgnoreCase("blue")) style = style.withColor(ChatFormatting.BLUE);
    else if (color.equalsIgnoreCase("yellow")) style = style.withColor(ChatFormatting.YELLOW);
    else if (color.equalsIgnoreCase("darkaqua")) style = style.withColor(ChatFormatting.DARK_AQUA);
    else if (color.equalsIgnoreCase("darkblue")) style = style.withColor(ChatFormatting.DARK_BLUE);
    else if (color.equalsIgnoreCase("gray")) style = style.withColor(ChatFormatting.GRAY);
    else if (color.equalsIgnoreCase("darkgreen")) style = style.withColor(ChatFormatting.DARK_GREEN);
    else if (color.equalsIgnoreCase("darkpurple")) style = style.withColor(ChatFormatting.DARK_PURPLE);
    else if (color.equalsIgnoreCase("darkred")) style = style.withColor(ChatFormatting.DARK_RED);
    else if (color.equalsIgnoreCase("darkgray")) style = style.withColor(ChatFormatting.DARK_GRAY);
    else if (color.equalsIgnoreCase("white")) style = style.withColor(ChatFormatting.WHITE);

    // Hex colors (#RRGGBB)
    if (color.startsWith("#") && color.length() == 7) {
      try {
        int rgb = Integer.parseInt(color.substring(1), 16);
        style = style.withColor(TextColor.fromRgb(rgb));
      } catch (NumberFormatException err) {
        this.error(false, "Faced error in SetColor Function:", color);
      }
    }
    return style;
  }

  /**
   * Setting up Font for Text. Deliberately a NO-OP: the font API keeps changing shape per
   * version ({@code ResourceLocation} -> {@code StyleSpriteSource.Font} ->
   * {@code FontDescription}) and the previous reflective lookups threw
   * NoSuchMethodException on every call, which hung the server thread in
   * fillInStackTrace until the watchdog killed the server. The default font is used.
   *
   * @param payload the template payload
   * @param style the current style
   * @return the unchanged style
   */
  private Style setFont(Messages.Template payload, Style style) {
    return style;
  }


  /**
   * Send Chat Message to Player.
   *
   * @param connection the server play network connection
   * @param payload the color template payload
   * @param args the arguments for the message
   */
  private void sendMessage(
      ServerGamePacketListenerImpl connection, Messages.ColTemplate payload, Object... args) {
    UUID uuid = connection.player.getUUID();
    String username = connection.player.getName().getString();
    User user = User.getUser(username, uuid);
    lastUser.set(user);
    String message = format(payload.message.text, args);

    // Clickable "button" support: when the template defines a click command, the chat
    // message runs/suggests it on click (fallback-safe - plain text otherwise). Clickable
    // buttons are underlined so players recognize them as interactive.
    net.minecraft.network.chat.Style style = getStyle(payload.message);
    if (payload.message.clickCommand != null && !payload.message.clickCommand.isBlank())
      style = style.withUnderlined(true);
    net.minecraft.network.chat.Component component =
        net.ded3ec.compat.Compat.withClickCommand(
            net.ded3ec.compat.Compat.text(message).setStyle(style),
            payload.message.clickCommand);

    if (payload.message.delay > 0)
      TaskScheduler.getInstance()
          .setTimeout(
              () -> {
                if (user != null && user.isActive && user.connection != null)
                  sendChatMessage(connection.player, component);
              },
              payload.message.delay * 1000L);
    else if (user != null && user.isActive && user.connection != null)
      sendChatMessage(connection.player, component);
  }

  /**
   * Sends a system chat message across every era: {@code sendSystemMessage(Component, boolean)}
   * (1.19.4+) with a reflective fallback to {@code displayClientMessage(Component, boolean)}
   * (1.16-1.19.3) - the G2 jar is compiled against 1.21.11 and must run on 1.19.0-1.19.3 too.
   */
  private static void sendChatMessage(
      net.minecraft.server.level.ServerPlayer player, net.minecraft.network.chat.Component component) {
    /*? if < 1.19.4 {*/
    /*try {
      player.displayClientMessage(component, false);
    } catch (RuntimeException ignored) {
      // message send is best-effort
    }
    *//*?} else {*/
    try {
      player.sendSystemMessage(component, false);
      return;
    } catch (Throwable ignored) {
      // 1.19.0-1.19.3 mid-range: displayClientMessage
    }
    try {
      player.getClass().getMethod("displayClientMessage", net.minecraft.network.chat.Component.class, boolean.class)
          .invoke(player, component, false);
    } catch (ReflectiveOperationException | RuntimeException ignored) {
      // message send is best-effort
    }
    /*?}*/
  }

  /**
   * Send Title with Subtitle to Player. Sends TitleS2CPacket for title, SubtitleS2CPacket for
   * subtitle, and TitleFadeS2CPacket for fade timing.
   *
   * @param connection the server play network connection
   * @param payload the color template payload
   * @param args the arguments for the message
   */
  private void sendTitle(
      ServerGamePacketListenerImpl connection, Messages.ColTemplate payload, Object... args) {
    UUID uuid = connection.player.getUUID();
    String username = connection.player.getName().getString();
    User user = User.getUser(username, uuid);
    lastUser.set(user);
    String titleMessage = format(payload.title.text, args);

    Runnable sendPackets =
        () -> {
          if (!(user != null && user.isActive && user.connection != null)) {
            this.warn(
                false,
                "Title '{}' not sent to '{}' - user={}, isActive={}, connection={}",
                titleMessage,
                username,
                user != null,
                user != null && user.isActive,
                user != null && user.connection != null);
            return;
          }

          // Ticks per second for the fade timings (never below 1 so titles always render)
          int tps = Math.max(1, (int) TpsManager.get());

          // TITLE + SUBTITLE + TIMES (version-agnostic packet API)
          boolean sent =
              net.ded3ec.compat.Compat.sendTitle(
                  connection,
                  net.ded3ec.compat.Compat.text(titleMessage)
                      .setStyle(getStyleWithShadow(payload.title)),
                  payload.title.subtitle != null && !payload.title.subtitle.text.isBlank()
                      ? net.ded3ec.compat.Compat.text(format(payload.title.subtitle.text, args))
                          .setStyle(getStyleWithShadow(payload.title.subtitle))
                      : null,
                  Math.abs(payload.title.fadeInSec * tps),
                  Math.abs(payload.title.staySec * tps),
                  Math.abs(payload.title.fadeOutSec * tps));

          // Safety net: when no title API matched on this version, deliver the title text as
          // a chat message so the content is never silently lost.
          if (!sent) {
            try {
              /*? if < 1.19.4 {*/
              /*connection.player.displayClientMessage(
                  net.ded3ec.compat.Compat.text(titleMessage)
                      .setStyle(getStyleWithShadow(payload.title)),
                  false);
              if (payload.title.subtitle != null && !payload.title.subtitle.text.isBlank())
                connection.player.displayClientMessage(
                    net.ded3ec.compat.Compat.text(format(payload.title.subtitle.text, args))
                        .setStyle(getStyleWithShadow(payload.title.subtitle)),
                    false);
              *//*?} else {*/
              connection.player.sendSystemMessage(
                  net.ded3ec.compat.Compat.text(titleMessage)
                      .setStyle(getStyleWithShadow(payload.title)),
                  false);
              if (payload.title.subtitle != null && !payload.title.subtitle.text.isBlank())
                connection.player.sendSystemMessage(
                    net.ded3ec.compat.Compat.text(format(payload.title.subtitle.text, args))
                        .setStyle(getStyleWithShadow(payload.title.subtitle)),
                    false);
              /*?}*/
            } catch (RuntimeException fallbackErr) {
              this.warn(
                  false,
                  "Title + chat fallback both failed for '{}' - the player received no "
                      + "feedback. Reported error: {}",
                  titleMessage,
                  fallbackErr.toString());
            }
          }
        };

    if (payload.title.delay > 0)
      TaskScheduler.getInstance().setTimeout(sendPackets, payload.title.delay * 1000L);
    else sendPackets.run();
  }

  /**
   * Send Action Bar to Player.
   *
   * @param connection the server play network connection
   * @param payload the color template payload
   * @param args the arguments for the message
   */
  private void sendActionBar(
      ServerGamePacketListenerImpl connection, Messages.ColTemplate payload, Object... args) {
    UUID uuid = connection.player.getUUID();
    String username = connection.player.getName().getString();
    User user = User.getUser(username, uuid);
    lastUser.set(user);
    String message = format(payload.actionBar.text, args);

    if (payload.actionBar.delay > 0)
      TaskScheduler.getInstance()
          .setTimeout(
              () -> {
                if (user != null && user.isActive && user.connection != null)
                  net.ded3ec.compat.Compat.sendSystemMessage(
                      connection.player,
                      net.ded3ec.compat.Compat.text(message)
                          .setStyle(getStyleWithShadow(payload.actionBar)),
                      true);
              },
              payload.actionBar.delay * 1000L);
    else if (user != null && user.isActive && user.connection != null)
      net.ded3ec.compat.Compat.sendSystemMessage(
          connection.player,
          net.ded3ec.compat.Compat.text(message).setStyle(getStyleWithShadow(payload.actionBar)),
          true);
  }
}
