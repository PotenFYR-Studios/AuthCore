package in.potenfyr.authcore.security;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;
import in.potenfyr.authcore.AuthCoreServer;
import in.potenfyr.authcore.util.Database;

/**
 * Shared backup/export logic used by the /authcore backup|export commands and the automated
 * backup scheduler.
 */
public final class Backups {

  private Backups() {}

  /** Copies the SQLite database to the backups folder (works for SQLite only). */
  public static Path backupSqlite() throws Exception {
    // Queued (write-behind) user rows must be in the database before it is copied/exported.
    in.potenfyr.authcore.models.User.flushWrites(10_000L);
    Path dbFile =
        AuthCoreServer.configPath.resolve("database").resolve(AuthCoreServer.config.database.sqlite);
    Path backupDir = AuthCoreServer.configPath.resolve("backups");
    Files.createDirectories(backupDir);

    String stamp =
        new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new java.util.Date());
    Path backup = backupDir.resolve("authcore-" + stamp + ".db");
    // The database runs in WAL mode: committed pages can still live in the -wal file, so a plain
    // copy of the .db file produced an incomplete (or inconsistent) backup. VACUUM INTO writes a
    // consistent, self-contained snapshot through SQLite itself; if it is unavailable the WAL is
    // checkpointed into the main file before the copy.
    boolean snapshotted = false;
    synchronized (in.potenfyr.authcore.util.Database.monitor()) {
      if (in.potenfyr.authcore.util.Database.connect()) {
        try (java.sql.Statement st = in.potenfyr.authcore.util.Database.connection.createStatement()) {
          Files.deleteIfExists(backup);
          st.execute("VACUUM INTO '" + backup.toAbsolutePath().toString().replace("'", "''") + "'");
          snapshotted = Files.exists(backup);
        } catch (Exception vacuumUnavailable) {
          try (java.sql.Statement st =
              in.potenfyr.authcore.util.Database.connection.createStatement()) {
            st.execute("PRAGMA wal_checkpoint(TRUNCATE);");
          } catch (Exception ignored) {
            // copy below is still better than nothing
          }
        }
      }
    }
    if (!snapshotted) Files.copy(dbFile, backup, StandardCopyOption.REPLACE_EXISTING);
    SecurityLog.log("DB_BACKUP", "Created " + backup);
    return backup;
  }

  /** Exports all users to a JSON file (works with every database backend). */
  public static Path exportUsersJson() throws Exception {
    // Queued (write-behind) user rows must be in the database before it is copied/exported.
    in.potenfyr.authcore.models.User.flushWrites(10_000L);
    // Every account, page by page. The export used to take a single page of 1000 rows, so on a
    // MySQL/PostgreSQL server with more accounts the "backup" silently dropped the rest.
    java.util.ArrayList<in.potenfyr.authcore.models.User> users = new java.util.ArrayList<>();
    String after = null;
    while (true) {
      java.util.ArrayList<in.potenfyr.authcore.models.User> page =
          in.potenfyr.authcore.models.User.fetchPlayersAfterPublic(after, 1000);
      if (page.isEmpty()) break;
      users.addAll(page);
      String last = page.get(page.size() - 1).username;
      // A page that does not advance (null / repeated key) would loop forever - stop instead.
      if (last == null || last.equals(after)) break;
      after = last;
      if (page.size() < 1000) break;
    }
    com.google.gson.JsonArray list = new com.google.gson.JsonArray();
    for (in.potenfyr.authcore.models.User u : users) {
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
          // Both artifact kinds: the JSON exports (MySQL/PostgreSQL servers) were never pruned
          // and accumulated one file per backup interval forever.
          .filter(
              p -> {
                String name = p.getFileName().toString();
                return name.startsWith("authcore-") || name.startsWith("users-export-");
              })
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
