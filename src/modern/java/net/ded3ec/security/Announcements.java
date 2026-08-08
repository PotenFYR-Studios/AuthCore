package net.ded3ec.security;

import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.User;
import net.ded3ec.models.Messages;
import net.ded3ec.util.TaskScheduler;

/**
 * Rotating server announcements shown to authenticated players. One entry is broadcast every
 * {@code lobby.announcement-interval-sec} seconds, rotating through the configured list.
 */
public final class Announcements {

  private static int index = 0;

  private Announcements() {}

  /** Broadcasts the next announcement to all authenticated players. */
  public static void rotateAndBroadcast() {
    var list = AuthCoreServer.config.lobby.announcements;
    if (list == null || list.isEmpty()) return;

    String text = list.get(Math.floorMod(index++, list.size()));
    if (text == null || text.isBlank()) return;

    for (User user : User.users.values()) {
      if (user == null || !user.isActive || !user.isAuthenticated.get() || user.connection == null)
        continue;

      String resolved = text.replace("%player%", String.valueOf(user.username));
      Messages.ColTemplate template = new Messages.ColTemplate();
      template.message.text = resolved;
      template.message.color = "YELLOW";

      AuthCoreServer.LOGGER.toUser(true, user.connection, template);
    }
  }

  /** Starts the rotation task (called after config load). */
  public static void start() {
    if (AuthCoreServer.config.lobby.announcements != null
        && !AuthCoreServer.config.lobby.announcements.isEmpty())
      TaskScheduler.getInstance()
          .setInterval(
              Announcements::rotateAndBroadcast,
              Math.max(AuthCoreServer.config.lobby.announcementIntervalSec, 10) * 1000L);
  }
}
