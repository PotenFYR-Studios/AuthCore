package net.ded3ec.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.User;
import net.ded3ec.security.Encrypter;

/**
 * Imports accounts from an AuthMe-style SQLite database into AuthCore.
 *
 * <p>Reads the {@code authme} table (username / realname / password / email /
 * registrationDate) and inserts each account that does not already exist. The stored hash is
 * kept as-is and the algorithm is inferred from its format (AuthMe {@code $SHA$}, bcrypt,
 * argon2, pbkdf2, plain hex digests) - the existing verification + transparent hash-upgrade
 * on the next successful login converts weak hashes to the configured algorithm.
 *
 * <p>Never overwrites existing accounts (conflicts are reported and skipped) and never
 * modifies the source database.
 */
public final class AuthMeImporter {

  private AuthMeImporter() {}

  /** Result of an import run. */
  public static final class ImportResult {
    public int imported = 0;
    public int skippedExisting = 0;
    public int skippedInvalid = 0;
    public String error = null;

    @Override
    public String toString() {
      return "Imported: " + imported + " account(s), skipped (already exist): "
          + skippedExisting + ", skipped (invalid rows): " + skippedInvalid;
    }
  }

  /**
   * Imports every account from an AuthMe SQLite database file.
   *
   * @param dbFile path to the AuthMe SQLite database (e.g. authme.db)
   * @return the import result (never null; {@code error} set on failure)
   */
  public static ImportResult importSqlite(Path dbFile) {
    ImportResult result = new ImportResult();
    if (dbFile == null || !Files.exists(dbFile)) {
      result.error = "File not found: " + dbFile;
      return result;
    }

    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath())) {
      boolean hasTable = false;
      try (Statement st = conn.createStatement();
          ResultSet rs = st.executeQuery(
              "SELECT name FROM sqlite_master WHERE type='table' AND name='authme'")) {
        hasTable = rs.next();
      }
      if (!hasTable) {
        result.error = "No 'authme' table found in " + dbFile + " - not an AuthMe database?";
        return result;
      }

      try (Statement st = conn.createStatement();
          ResultSet rs = st.executeQuery(
              "SELECT username, password, email, registrationDate FROM authme")) {

        while (rs.next()) {
          String username = rs.getString("username");
          String hash = rs.getString("password");
          String email = rs.getString("email");
          long registeredAt = rs.getLong("registrationDate");

          if (username == null || username.isBlank() || hash == null || hash.isBlank()) {
            result.skippedInvalid++;
            continue;
          }
          if (User.getUserByUsername(username) != null) {
            result.skippedExisting++;
            continue;
          }

          String algorithm = Encrypter.inferImportedAlgorithm(hash);
          if (algorithm == null) {
            result.skippedInvalid++;
            continue;
          }

          UUID uuid = UUID.nameUUIDFromBytes(
              ("OfflinePlayer:" + username).getBytes(java.nio.charset.StandardCharsets.UTF_8));
          User user = new User(uuid, username, registeredAt > 0 ? registeredAt : System.currentTimeMillis(), false);
          user.password = hash;
          user.passwordEncryption = algorithm;
          user.email = email != null && !email.isBlank() ? email : null;
          user.registeredAtMs = registeredAt > 0 ? registeredAt : System.currentTimeMillis();

          User.importUser(user);
          result.imported++;
        }
      }

      AuthCoreServer.LOGGER.info(true, "AuthMe import finished: {}", result);
      return result;
    } catch (Exception err) {
      result.error = "Import failed: " + err.getMessage();
      AuthCoreServer.LOGGER.error(null, "AuthMe import failed:", err);
      return result;
    }
  }
}
