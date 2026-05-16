package net.ded3ec.utils;

import java.util.UUID;

import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.Messages;
import net.ded3ec.models.User;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.slf4j.LoggerFactory;

/** Logger Utility for authCore! */
public class Logger {

  private final org.slf4j.Logger logger;

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
    if (AuthCoreServer.config.debugMode) this.logger.info(message, args);
    else logger.debug(message, args);

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
      T value, ServerPlayNetworkHandler connection, Messages.ColTemplate payload, Object... args) {

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
      T value, ServerPlayNetworkHandler connection, Messages.KickTemplate payload, Object... args) {
    UUID uuid = connection.getPlayer().getUuid();
    String username = connection.getPlayer().getName().getString();
    User user = User.getUser(username, uuid);
    String message = String.format(payload.logout.text, args);

    if (user != null) this.toUser(false, connection, payload, args);

    if (payload.logout.delaySec > 0)
      TaskScheduler.getInstance()
          .setTimeout(
              () -> {
                if (user != null && user.isActive)
                  connection.disconnect(
                      Text.translatable(message).setStyle(getStyle(payload.logout)));
              },
              payload.logout.delaySec * 1000L);
    else if (user != null && user.isActive && user.connection != null)
      connection.disconnect(Text.translatable(message).setStyle(getStyle(payload.logout)));

    return value;
  }

  /**
   * Get Empty Style with Shadow.
   *
   * @param payload the template payload
   * @return the style with shadow
   */
  private Style getStyleWithShadow(Messages.Template payload) {
    Style style = this.getStyle(payload);
    return setShadow(payload, style);
  }

  /**
   * Get Empty Style without Shadow.
   *
   * @param payload the template payload
   * @return the style
   */
  private Style getStyle(Messages.Template payload) {

    Style style =
        Style.EMPTY
            .withBold(payload.bold)
            .withItalic(payload.italic)
            .withUnderline(payload.underline)
            .withStrikethrough(payload.strikethrough)
            .withObfuscated(payload.obfuscate);

    style = setFont(payload, style);

    if (!payload.color.isBlank()) style = setColor(payload, style);

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
    if (payload.color.equalsIgnoreCase("red")) style = style.withColor(Formatting.RED);
    else if (payload.color.equalsIgnoreCase("green")) style = style.withColor(Formatting.GREEN);
    else if (payload.color.equalsIgnoreCase("gold")) style = style.withColor(Formatting.GOLD);
    else if (payload.color.equalsIgnoreCase("aqua")) style = style.withColor(Formatting.AQUA);
    else if (payload.color.equalsIgnoreCase("blue")) style = style.withColor(Formatting.BLUE);
    else if (payload.color.equalsIgnoreCase("yellow")) style = style.withColor(Formatting.YELLOW);
    else if (payload.color.equalsIgnoreCase("darkaqua"))
      style = style.withColor(Formatting.DARK_AQUA);
    else if (payload.color.equalsIgnoreCase("darkblue"))
      style = style.withColor(Formatting.DARK_BLUE);
    else if (payload.color.equalsIgnoreCase("gray")) style = style.withColor(Formatting.GRAY);
    else if (payload.color.equalsIgnoreCase("darkgreen"))
      style = style.withColor(Formatting.DARK_GREEN);
    else if (payload.color.equalsIgnoreCase("darkpurple"))
      style = style.withColor(Formatting.DARK_PURPLE);
    else if (payload.color.equalsIgnoreCase("darkred"))
      style = style.withColor(Formatting.DARK_RED);
    else if (payload.color.equalsIgnoreCase("darkgray"))
      style = style.withColor(Formatting.DARK_GRAY);
    else if (payload.color.equalsIgnoreCase("white")) style = style.withColor(Formatting.WHITE);

    // Hex colors (#RRGGBB)
    if (payload.color.startsWith("#") && payload.color.length() == 7) {
      try {
        int rgb = Integer.parseInt(payload.color.substring(1), 16);
        style = style.withColor(TextColor.fromRgb(rgb));

      } catch (NumberFormatException err) {
        this.error(false, "Faced error in SetColor Function:", payload.color);
      }
    }
    return style;
  }

  /**
   * Setting up Font for Text.
   *
   * @param payload the template payload
   * @param style the current style
   * @return the updated style with font
   */
  private Style setFont(Messages.Template payload, Style style) {
    return style.withFont(
        new StyleSpriteSource.Font(Identifier.of(payload.font[0], payload.font[1])));
  }

  /**
   * Setting up Shadow with Strength for Text.
   *
   * @param payload the template payload
   * @param style the current style
   * @return the updated style with shadow
   */
  private static Style setShadow(Messages.Template payload, Style style) {
    if (!payload.shadow) style.withoutShadow();
    else style.withShadowColor(payload.shadowStrength);
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
      ServerPlayNetworkHandler connection, Messages.ColTemplate payload, Object... args) {
    UUID uuid = connection.getPlayer().getUuid();
    String username = connection.getPlayer().getName().getString();
    User user = User.getUser(username, uuid);
    String message = String.format(payload.message.text, args);

    if (payload.message.delay > 0)
      TaskScheduler.getInstance()
          .setTimeout(
              () -> {
                if (user != null && user.isActive)
                  connection.player.sendMessage(
                      Text.translatable(message).setStyle(getStyle(payload.message)), false);
              },
              payload.message.delay);
    else if (user != null && user.isActive)
      connection.player.sendMessage(
          Text.translatable(message).setStyle(getStyle(payload.message)), false);
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
      ServerPlayNetworkHandler connection, Messages.ColTemplate payload, Object... args) {
    UUID uuid = connection.getPlayer().getUuid();
    String username = connection.getPlayer().getName().getString();
    User user = User.getUser(username, uuid);

    String titleMessage = String.format(payload.title.text, args);

    Runnable sendPackets =
        () -> {
          if (!(user != null && user.isActive && user.connection != null)) return;

          // TITLE
          connection.send(
              new TitleS2CPacket(
                  Text.literal(titleMessage).setStyle(getStyleWithShadow(payload.title))),
              null);

          // SUBTITLE
          if (!payload.title.subtitle.text.isBlank()) {
            String subtitleMessage = String.format(payload.title.subtitle.text, args);

            connection.send(
                new SubtitleS2CPacket(
                    Text.literal(subtitleMessage)
                        .setStyle(getStyleWithShadow(payload.title.subtitle))),
                null);
          }

          // TIMES
          connection.send(
              new TitleFadeS2CPacket(
                  Math.abs(payload.title.fadeInSec * (int) TpsManager.get()),
                  Math.abs(payload.title.staySec * (int) TpsManager.get()),
                  Math.abs(payload.title.fadeOutSec * (int) TpsManager.get())),
              null);
        };

    if (payload.title.delay > 0)
      TaskScheduler.getInstance().setTimeout(sendPackets, payload.title.delay);
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
      ServerPlayNetworkHandler connection, Messages.ColTemplate payload, Object... args) {
    UUID uuid = connection.getPlayer().getUuid();
    String username = connection.getPlayer().getName().getString();
    User user = User.getUser(username, uuid);
    String message = String.format(payload.actionBar.text, args);

    if (payload.actionBar.delay > 0)
      TaskScheduler.getInstance()
          .setTimeout(
              () -> {
                if (user != null && user.isActive)
                  connection.player.sendMessage(
                      Text.translatable(message).setStyle(getStyle(payload.actionBar)), true);
              },
              payload.actionBar.delay);
    else if (user != null && user.isActive)
      connection.player.sendMessage(
          Text.translatable(message).setStyle(getStyle(payload.actionBar)), true);
  }
}
