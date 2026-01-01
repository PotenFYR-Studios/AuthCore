package net.ded3ec.authcore.utils;

import java.util.UUID;
import net.ded3ec.authcore.AuthCore;
import net.ded3ec.authcore.models.Messages;
import net.ded3ec.authcore.models.User;
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

/** Logger Utility for authCore! */
public class Logger {

  /**
   * Debugging Log helper for authCore with arguments.
   *
   * @param <T> the return type
   * @param value the value to return
   * @param message the debug message
   * @param args the arguments for the message
   * @return the returnValue
   */
  public static <T> T debug(T value, String message, Object... args) {
    if (AuthCore.config.debugMode) AuthCore.LOGGER.info(message, args);
    else AuthCore.LOGGER.debug(message, args);

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
  public static <T> T error(T value, String message, Object... args) {
    AuthCore.LOGGER.error(message, args);
    return value;
  }

  /**
   * Information Logging helper for authCore with Objects.
   *
   * @param message the info message
   * @param args the arguments for the message
   */
  public static <T> T info(T value, String message, Object... args) {
    AuthCore.LOGGER.info(message, args);
    return value;
  }

  /**
   * Sending Message/Title/Subtitle to the Player in Minecraft Server!
   *
   * @param <T> the return type
   * @param value the value to return
   * @param network the server play network handler
   * @param payload the color template payload
   * @param args the arguments for the message
   * @return the returnValue
   */
  public static <T> T toUser(
      T value, ServerPlayNetworkHandler network, Messages.ColTemplate payload, Object... args) {

    if (!payload.message.text.isBlank()) sendMessage(network, payload, args);

    if (!payload.actionBar.text.isBlank()) sendActionBar(network, payload, args);

    if (!payload.title.text.isBlank()) sendTitle(network, payload, args);

    return value;
  }

  /**
   * Kicking Player Handler.
   *
   * @param <T> the return type
   * @param value the value to return
   * @param network the server play network handler
   * @param payload the kick template payload
   * @param args the arguments for the message
   * @return the value
   */
  public static <T> T toKick(
      T value, ServerPlayNetworkHandler network, Messages.KickTemplate payload, Object... args) {
    UUID uuid = network.getPlayer().getUuid();
    String username = network.getPlayer().getName().getString();
    User user = User.getUser(username, uuid);
    String message = String.format(payload.logout.text, args);

    if (user != null) Logger.toUser(false, user.handler, payload);

    if (payload.logout.delaySec > 0)
      Misc.TaskScheduler.getInstance()
          .setTimeout(
              () -> {
                if (user != null && user.isActive)
                  network.disconnect(Text.translatable(message).setStyle(getStyle(payload.logout)));
              },
              payload.logout.delaySec * 1000L);
    else if (user != null && user.isActive && user.handler != null)
      network.disconnect(Text.translatable(message).setStyle(getStyle(payload.logout)));

    return value;
  }

  /**
   * Get Empty Style with Shadow.
   *
   * @param payload the template payload
   * @return the style with shadow
   */
  private static Style getStyleWithShadow(Messages.Template payload) {
    Style style = getStyle(payload);
    return setShadow(payload, style);
  }

  /**
   * Get Empty Style without Shadow.
   *
   * @param payload the template payload
   * @return the style
   */
  private static Style getStyle(Messages.Template payload) {

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
  private static Style setColor(Messages.Template payload, Style style) {
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
        Logger.error(false, "Faced error in SetColor Function:", payload.color);
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
  private static Style setFont(Messages.Template payload, Style style) {
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
   * @param network the server play network handler
   * @param payload the color template payload
   * @param args the arguments for the message
   */
  private static void sendMessage(
      ServerPlayNetworkHandler network, Messages.ColTemplate payload, Object... args) {
    UUID uuid = network.getPlayer().getUuid();
    String username = network.getPlayer().getName().getString();
    User user = User.getUser(username, uuid);
    String message = String.format(payload.message.text, args);

    if (payload.message.delay > 0)
      Misc.TaskScheduler.getInstance()
          .setTimeout(
              () -> {
                if (user != null && user.isActive)
                  network.player.sendMessage(
                      Text.translatable(message).setStyle(getStyle(payload.message)), false);
              },
              payload.message.delay);
    else if (user != null && user.isActive)
      network.player.sendMessage(
          Text.translatable(message).setStyle(getStyle(payload.message)), false);
  }

  /**
   * Send Title with Subtitle to Player. Sends TitleS2CPacket for title, SubtitleS2CPacket for
   * subtitle, and TitleFadeS2CPacket for fade timing.
   *
   * @param network the server play network handler
   * @param payload the color template payload
   * @param args the arguments for the message
   */
  private static void sendTitle(
      ServerPlayNetworkHandler network, Messages.ColTemplate payload, Object... args) {
    UUID uuid = network.getPlayer().getUuid();
    String username = network.getPlayer().getName().getString();
    User user = User.getUser(username, uuid);
    String titleMessage = String.format(payload.title.text, args);

    if (payload.title.delay > 0) {
      Misc.TaskScheduler.getInstance()
          .setTimeout(
              () -> {
                if (!(user != null && user.isActive && user.handler != null)) return;
                network.sendPacket(
                    new TitleS2CPacket(
                        Text.translatable(titleMessage)
                            .setStyle(getStyleWithShadow(payload.title))));

                if (!payload.title.subtitle.text.isBlank()) {
                  String subtitleMessage = String.format(payload.title.subtitle.text, args);

                  network.sendPacket(
                      new SubtitleS2CPacket(
                          Text.translatable(subtitleMessage)
                              .setStyle(getStyleWithShadow(payload.title.subtitle))));
                }

                network.sendPacket(
                    new TitleFadeS2CPacket(
                        Math.abs(payload.title.fadeInSec * (int) Misc.TpsMeter.get()),
                        Math.abs(payload.title.staySec * (int) Misc.TpsMeter.get()),
                        Math.abs(payload.title.fadeOutSec * (int) Misc.TpsMeter.get())));
              },
              payload.title.delay);

    } else if (user != null && user.isActive && user.handler != null) {
      network.sendPacket(
          new TitleS2CPacket(
              Text.translatable(titleMessage).setStyle(getStyleWithShadow(payload.title))));

      if (!payload.title.subtitle.text.isBlank()) {
        String subtitleMessage = String.format(payload.title.subtitle.text, args);

        network.sendPacket(
            new SubtitleS2CPacket(
                Text.translatable(subtitleMessage)
                    .setStyle(getStyleWithShadow(payload.title.subtitle))));
      }

      network.sendPacket(
          new TitleFadeS2CPacket(
              Math.abs(payload.title.fadeInSec * (int) Misc.TpsMeter.get()),
              Math.abs(payload.title.staySec * (int) Misc.TpsMeter.get()),
              Math.abs(payload.title.fadeOutSec * (int) Misc.TpsMeter.get())));
    }
  }

  /**
   * Send Action Bar to Player.
   *
   * @param network the server play network handler
   * @param payload the color template payload
   * @param args the arguments for the message
   */
  private static void sendActionBar(
      ServerPlayNetworkHandler network, Messages.ColTemplate payload, Object... args) {
    UUID uuid = network.getPlayer().getUuid();
    String username = network.getPlayer().getName().getString();
    User user = User.getUser(username, uuid);
    String message = String.format(payload.actionBar.text, args);

    if (payload.actionBar.delay > 0)
      Misc.TaskScheduler.getInstance()
          .setTimeout(
              () -> {
                if (user != null && user.isActive)
                  network.player.sendMessage(
                      Text.translatable(message).setStyle(getStyle(payload.actionBar)), true);
              },
              payload.actionBar.delay);
    else if (user != null && user.isActive)
      network.player.sendMessage(
          Text.translatable(message).setStyle(getStyle(payload.actionBar)), true);
  }
}
