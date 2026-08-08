package net.ded3ec.security;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;
import net.ded3ec.AuthCoreServer;
import net.ded3ec.util.Database;

/**
 * Shared backup/export logic used by the /authcore backup|export commands and the automated
 * backup scheduler.
 */
public final class Backups {

  private Backups() {}

  /** Copies the SQLite database to the backups folder (works for SQLite only). */
  public static Path backupSqlite() throws Exception {
    Path dbFile =
        AuthCoreServer.configPath.resolve("database").resolve(AuthCoreServer.config.database.sqlite);
    Path backupDir = AuthCoreServer.configPath.resolve("backups");
    Files.createDirectories(backupDir);

    String stamp =
        new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new java.util.Date());
    Path backup = backupDir.resolve("authcore-" + stamp + ".db");
    Files.copy(dbFile, backup, StandardCopyOption.REPLACE_EXISTING);
    SecurityLog.log("DB_BACKUP", "Created " + backup);
    return backup;
  }

  /** Exports all users to a JSON file (works with every database backend). */
  public static Path exportUsersJson() throws Exception {
    java.util.ArrayList<net.ded3ec.models.User> users =
        net.ded3ec.models.User.fetchPlayersPublic(1000, null);
    com.google.gson.JsonArray list = new com.google.gson.JsonArray();
    for (net.ded3ec.models.User u : users) {
      com.google.gson.JsonObject o = new com.google.gson.JsonObject();
      o.addProperty("uuid", u.uuid.toString());
      o.addProperty("username", u.username);
      o.addProperty("email", u.email);
      o.addProperty("nickname", u.nickname);
      o.addProperty("mode", u.isPremium ? "online-mode" : "offline-mode");
      o.addProperty("ip", u.ipAddress);
      o.addProperty("country", u.country.get());
      o.addProperty("registered", u.isRegistered.get());
      o.addProperty("registeredAtMs", u.registeredAtMs);
      o.addProperty("userCreatedMs", u.userCreatedMs);
      o.addProperty("risk", u.riskScore);
      o.addProperty("locked", u.isLocked());
      list.add(o);
    }

    Path backupDir = AuthCoreServer.configPath.resolve("backups");
    Files.createDirectories(backupDir);
    String stamp =
        new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new java.util.Date());
    Path out = backupDir.resolve("users-export-" + stamp + ".json");
    Files.writeString(
        out, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(list));
    SecurityLog.log("DB_EXPORT", "Exported " + list.size() + " users to " + out);
    return out;
  }

  /** Runs the automated backup (if configured) and prunes old backups. */
  public static void runAutomaticBackup() {
    if (AuthCoreServer.config == null || AuthCoreServer.config.session.backup.intervalHours <= 0)
      return;

    try {
      if (Database.dialect == Database.Dialect.SQLITE) {
        backupSqlite();
      } else {
        exportUsersJson();
      }
      pruneBackups(AuthCoreServer.config.session.backup.keep);
    } catch (Exception err) {
      AuthCoreServer.LOGGER.error(false, "Automated backup failed:", err);
    }
  }

  /** Deletes the oldest backups beyond the configured keep count. */
  private static void pruneBackups(int keep) {
    if (keep <= 0) return;
    Path backupDir = AuthCoreServer.configPath.resolve("backups");
    if (!Files.isDirectory(backupDir)) return;

    try (Stream<Path> files = Files.list(backupDir)) {
      files
          .filter(p -> p.getFileName().toString().startsWith("authcore-"))
          .sorted(Comparator.comparingLong(Backups::lastModified).reversed())
          .skip(keep)
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                  AuthCoreServer.LOGGER.debug(true, "Pruned old backup: {}", p.getFileName());
                } catch (Exception ignored) {
                  // best effort
                }
              });
    } catch (Exception ignored) {
      // best effort
    }
  }

  private static long lastModified(Path p) {
    try {
      return Files.getLastModifiedTime(p).toMillis();
    } catch (Exception e) {
      return 0;
    }
  }
}
