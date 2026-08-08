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

  private final org.slf4j.Logger logger;

  /** Thread-local user context set by the send methods for placeholder resolution. */
  private final ThreadLocal<User> lastUser = new ThreadLocal<>();

  public Logger(String MOD_ID) {
    this.logger = LoggerFactory.getLogger(MOD_ID);
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
    if (AuthCoreServer.config != null && AuthCoreServer.config.debugMode) this.logger.info(message, args);
    else this.logger.debug(message, args);

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
    this.logger.warn(message, args);
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
    this.logger.error(message, args);
    return value;
  }

  /**
   * Information Logging helper for authCore with Objects.
   *
   * @param message the info message
   * @param args the arguments for the message
   */
  public <T> T info(T value, String message, Object... args) {
    this.logger.info(message, args);
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

    if (user != null) this.toUser(false, connection, payload, args);

    // Capture the connection at scheduling time so a delayed disconnect always targets the
    // connection that was online when the kick was requested.
    ServerGamePacketListenerImpl target = connection;
    Component reason = net.ded3ec.compat.Compat.text(message).setStyle(getStyle(payload.logout));

    if (payload.logout.delaySec > 0)
      TaskScheduler.getInstance()
          .setTimeout(
              () -> {
                if (user != null && user.isActive && user.connection != null && user.connection == target)
                  disconnectSafely(target, reason);
              },
              payload.logout.delaySec * 1000L);
    else if (user != null && user.isActive && user.connection != null) disconnectSafely(target, reason);

    return value;
  }

  /** Disconnects a player connection, swallowing any state issues on disconnect. */
  private void disconnectSafely(ServerGamePacketListenerImpl connection, Component reason) {
    try {
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
   * Setting up Font for Text. Uses reflection so the font API (Identifier on 1.16-1.19.3,
   * StyleSpriteSource.Font on 1.19.4+) works on every Minecraft version.
   *
   * @param payload the template payload
   * @param style the current style
   * @return the updated style with font
   */
  private Style setFont(Messages.Template payload, Style style) {
    if (payload.font == null || payload.font.length < 2 || payload.font[0] == null) return style;
    try {
      String fontId =
          payload.font[0].equals("minecraft") && payload.font[1].equals("default")
              ? "minecraft:default"
              : payload.font[0] + ":" + payload.font[1];

      // 1.19.4+ : Style.withFont(StyleSpriteSource.Font)
      try {
        Class<?> fontClass = Class.forName("net.minecraft.text.StyleSpriteSource$Font");
        Object font = fontClass.getConstructor(net.minecraft.resources.Identifier.class).newInstance(
            net.minecraft.resources.Identifier.tryParse(fontId));
        return (Style) Style.class.getMethod("withFont", fontClass).invoke(style, font);
      } catch (ReflectiveOperationException ignored) {
        // fall through to the legacy API
      }

      // 1.16-1.19.3 : Style.withFont(Identifier)
      try {
        return (Style)
            Style.class
                .getMethod("withFont", net.minecraft.resources.Identifier.class)
                .invoke(style, net.minecraft.resources.Identifier.tryParse(fontId));
      } catch (ReflectiveOperationException ignored) {
        // font API not available
      }
    } catch (Exception err) {
      this.debug(style, "Invalid font '{}' in message config:", String.join(",", payload.font));
    }
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

    if (payload.message.delay > 0)
      TaskScheduler.getInstance()
          .setTimeout(
              () -> {
                if (user != null && user.isActive && user.connection != null)
                  connection.player.sendSystemMessage(
                      net.ded3ec.compat.Compat.text(message).setStyle(getStyle(payload.message)), false);
              },
              payload.message.delay * 1000L);
    else if (user != null && user.isActive && user.connection != null)
      connection.player.sendSystemMessage(
          net.ded3ec.compat.Compat.text(message).setStyle(getStyle(payload.message)), false);
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
          if (!(user != null && user.isActive && user.connection != null)) return;

          // TITLE + SUBTITLE + TIMES (version-agnostic packet API)
          net.ded3ec.compat.Compat.sendTitle(
              connection,
              net.ded3ec.compat.Compat.text(titleMessage)
                  .setStyle(getStyleWithShadow(payload.title)),
              payload.title.subtitle != null && !payload.title.subtitle.text.isBlank()
                  ? net.ded3ec.compat.Compat.text(format(payload.title.subtitle.text, args))
                      .setStyle(getStyleWithShadow(payload.title.subtitle))
                  : null,
              Math.abs(payload.title.fadeInSec * (int) TpsManager.get()),
              Math.abs(payload.title.staySec * (int) TpsManager.get()),
              Math.abs(payload.title.fadeOutSec * (int) TpsManager.get()));
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
                  connection.player.sendSystemMessage(
                      net.ded3ec.compat.Compat.text(message).setStyle(getStyle(payload.actionBar)), true);
              },
              payload.actionBar.delay * 1000L);
    else if (user != null && user.isActive && user.connection != null)
      connection.player.sendSystemMessage(
          net.ded3ec.compat.Compat.text(message).setStyle(getStyle(payload.actionBar)), true);
  }
}
