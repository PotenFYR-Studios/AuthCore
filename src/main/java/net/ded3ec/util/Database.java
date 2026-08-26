package net.ded3ec.util;

import net.ded3ec.models.Config;
import net.ded3ec.models.User;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.ded3ec.AuthCoreServer;

/** Database class for database related management! */
public class Database {

  /** Supported SQL dialects. */
  public enum Dialect {
    SQLITE,
    MYSQL,
    POSTGRESQL
  }

  /** The currently active SQL dialect. */
  public static Dialect dialect = Dialect.SQLITE;

  /** Set when the schema migration could not be completed automatically. */
  public static volatile boolean migrationBlocked = false;

  /** SQL Connection with java driver. */
  public static volatile Connection connection;

  private Database() {}

  /**
   * Connect to the database (PostgreSQL/MySQL/SQLite).
   *
   * @return true if connection is successful, false otherwise
   */
  public static synchronized boolean connect() {
    if (AuthCoreServer.config == null) return false;

    AuthCoreServer.LOGGER.debug(
        false,
        "Database | connect() - postgres={}, mysql={} (fallback SQLite)",
        AuthCoreServer.config.database.postgres.enabled,
        AuthCoreServer.config.database.mysql.enabled);

    if (AuthCoreServer.config.database.postgres.enabled) return connectPostgres();
    if (AuthCoreServer.config.database.mysql.enabled) return connectMySql();
    return connectSqlite();
  }

  /** Connects to the PostgreSQL database (if not already connected and healthy). */
  private static boolean connectPostgres() {
    try {
      if (isHealthy()) return true;

      String host = AuthCoreServer.config.database.postgres.host.trim();
      String database = AuthCoreServer.config.database.postgres.database.trim();

      if (host.isEmpty() || database.isEmpty()) {
        return AuthCoreServer.LOGGER.error(
            false,
            "PostgreSQL is enabled but 'host' or 'database' is empty! Falling back to SQLite.");
      }

      AuthCoreServer.LOGGER.info(
          true,
          "Connecting to PostgreSQL database '{}' at {}:{} ...",
          database,
          host,
          AuthCoreServer.config.database.postgres.port);

      boolean ssl = AuthCoreServer.config.database.postgres.ssl;
      connection =
          DriverManager.getConnection(
              "jdbc:postgresql://"
                  + host
                  + ':'
                  + AuthCoreServer.config.database.postgres.port
                  + '/'
                  + database
                  + (ssl ? "?ssl=true&sslmode=require" : ""),
              AuthCoreServer.config.database.postgres.username,
              AuthCoreServer.config.database.postgres.password);

      dialect = Dialect.POSTGRESQL;
      return AuthCoreServer.LOGGER.info(true, "PostgreSQL database has been connected with AuthCore!");
    } catch (SQLException err) {
      return AuthCoreServer.LOGGER.error(
          false, "[" + net.ded3ec.util.ErrorCodes.code(net.ded3ec.util.ErrorCodes.Module.DATABASE, net.ded3ec.util.ErrorCodes.Kind.CONNECTION, 1) + "] PostgreSQL connection failed ({}): {}", err.getSQLState(), err.getLocalizedMessage());
    }
  }

  /** Connects to the MySQL/MariaDB database (if not already connected and healthy). */
  private static boolean connectMySql() {
    try {
      if (isHealthy()) return true;

      String host = AuthCoreServer.config.database.mysql.host.trim();
      String database = AuthCoreServer.config.database.mysql.database.trim();

      if (host.isEmpty() || database.isEmpty()) {
        return AuthCoreServer.LOGGER.error(
            false,
            "MySQL is enabled but 'host' or 'database' is empty! Falling back to SQLite.");
      }

      AuthCoreServer.LOGGER.info(
          true, "Connecting to MySQL database '{}' at {}:{} ...",
          database, host, AuthCoreServer.config.database.mysql.port);

      boolean ssl = AuthCoreServer.config.database.mysql.ssl;
      StringBuilder url =
          new StringBuilder("jdbc:mysql://")
              .append(host)
              .append(':')
              .append(AuthCoreServer.config.database.mysql.port)
              .append('/')
              .append(database)
              .append("?useSSL=").append(ssl)
              .append("&requireSSL=").append(ssl)
              .append("&serverTimezone=UTC")
              .append("&useUnicode=true")
              .append("&characterEncoding=UTF-8")
              .append("&connectionTimeZone=UTC")
              .append("&allowPublicKeyRetrieval=").append(!ssl);

      connection =
          DriverManager.getConnection(
              url.toString(),
              AuthCoreServer.config.database.mysql.username,
              AuthCoreServer.config.database.mysql.password);

      dialect = Dialect.MYSQL;
      return AuthCoreServer.LOGGER.info(true, "MySQL database has been connected with AuthCore!");
    } catch (SQLException err) {
      return AuthCoreServer.LOGGER.error(
          false,
          "[" + net.ded3ec.util.ErrorCodes.code(net.ded3ec.util.ErrorCodes.Module.DATABASE, net.ded3ec.util.ErrorCodes.Kind.CONNECTION, 2) + "] MySQL connection failed ({}): {}",
          err.getSQLState(),
          err.getLocalizedMessage());
    }
  }

  /** Connects to the local SQLite database (if not already connected and healthy). */
  private static boolean connectSqlite() {
    try {
      if (isHealthy()) return true;

      AuthCoreServer.LOGGER.info(
          true, "Connecting to SQLite database '{}' ...", AuthCoreServer.config.database.sqlite);

      Path dbPath =
          AuthCoreServer.configPath
              .resolve("database")
              .resolve(AuthCoreServer.config.database.sqlite);

      if (dbPath.getParent() != null) {
        try {
          Files.createDirectories(dbPath.getParent());
        } catch (IOException err) {
          return AuthCoreServer.LOGGER.error(
              false, "[" + net.ded3ec.util.ErrorCodes.code(net.ded3ec.util.ErrorCodes.Module.DATABASE, net.ded3ec.util.ErrorCodes.Kind.IO, 1) + "] SQLite database is facing an error while creating database file:", err);
        }
      }

      connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());

      // Low-resource SQLite tuning: WAL journaling reduces fsync stalls (smoother ticks on
      // 1-core boxes), synchronous=NORMAL keeps crash-safety while cutting disk writes, and
      // a bounded page cache keeps SQLite's memory footprint small.
      try (Statement pragma = connection.createStatement()) {
        pragma.execute("PRAGMA journal_mode=WAL;");
        pragma.execute("PRAGMA synchronous=NORMAL;");
        pragma.execute("PRAGMA cache_size=-2000;"); // ~2 MB max page cache
      } catch (SQLException ignored) {
        // pragmas are best-effort - the connection still works without them
      }

      dialect = Dialect.SQLITE;
      return AuthCoreServer.LOGGER.info(true, "SQLite database has been connected with AuthCore!");
    } catch (SQLException err) {
      return AuthCoreServer.LOGGER.error(
          false,
          "[" + net.ded3ec.util.ErrorCodes.code(net.ded3ec.util.ErrorCodes.Module.DATABASE, net.ded3ec.util.ErrorCodes.Kind.CONNECTION, 3) + "] SQLite connection failed: {}",
          err.getLocalizedMessage());
    }
  }

  /** Checks whether the current connection is still open and usable. */
  private static boolean isHealthy() {
    try {
      return connection != null
          && !connection.isClosed()
          && connection.isValid(2);
    } catch (SQLException err) {
      return false;
    }
  }

  /**
   * Dialect-aware UPSERT statement for the USERS table. The old hard-coded SQLite
   * "INSERT OR REPLACE" silently failed on MySQL/PostgreSQL - this is the fix.
   *
   * @return the upsert SQL with 9 placeholders (uuid, username, password, authSecret, mode,
   *     ipAddress, passwordEncryption, userCreatedMs, registeredMs)
   */
  public static String usersUpsertSql() {
    String columns =
        "(uuid, username, password, authSecret, mode, ipAddress, passwordEncryption, "
            + "userCreatedMs, registeredMs)";
    String values = "VALUES(?,?,?,?,?,?,?,?,?)";

    return switch (dialect) {
      case SQLITE -> "INSERT OR REPLACE INTO USERS " + columns + " " + values;
      case MYSQL ->
          "INSERT INTO USERS "
              + columns
              + " "
              + values
              + " ON DUPLICATE KEY UPDATE username=VALUES(username), password=VALUES(password),"
              + " authSecret=VALUES(authSecret), mode=VALUES(mode), ipAddress=VALUES(ipAddress),"
              + " passwordEncryption=VALUES(passwordEncryption), userCreatedMs=VALUES(userCreatedMs),"
              + " registeredMs=VALUES(registeredMs)";
      case POSTGRESQL ->
          "INSERT INTO USERS "
              + columns
              + " "
              + values
              + " ON CONFLICT (uuid) DO UPDATE SET username=EXCLUDED.username,"
              + " password=EXCLUDED.password, authSecret=EXCLUDED.authSecret, mode=EXCLUDED.mode,"
              + " ipAddress=EXCLUDED.ipAddress, passwordEncryption=EXCLUDED.passwordEncryption,"
              + " userCreatedMs=EXCLUDED.userCreatedMs, registeredMs=EXCLUDED.registeredMs";
    };
  }

  /** Load the Database creation and replace! */
  public static synchronized void load() {
    if (!connect()) return;

    try (Statement stmt = connection.createStatement()) {
      // DIALECT-AWARE DDL: the previous hard-coded SQLite syntax silently BRICKED MySQL and
      // PostgreSQL servers at boot - "AUTOINCREMENT" does not exist there (MySQL wants
      // AUTO_INCREMENT, PostgreSQL wants BIGSERIAL) and a TEXT primary key is illegal in
      // MySQL without a key length. The thrown SQLException set migrationBlocked=true,
      // which suspends login/register for the WHOLE server. Fresh installs on every
      // dialect now boot correctly; existing databases are untouched (IF NOT EXISTS).
      boolean mysql = dialect == Dialect.MYSQL;
      String uuidColumn = mysql ? "uuid VARCHAR(36) NOT NULL PRIMARY KEY" : "uuid TEXT NOT NULL PRIMARY KEY";
      String historyId =
          switch (dialect) {
            case MYSQL -> "id BIGINT PRIMARY KEY AUTO_INCREMENT";
            case POSTGRESQL -> "id BIGSERIAL PRIMARY KEY";
            case SQLITE -> "id INTEGER PRIMARY KEY AUTOINCREMENT";
          };

      stmt.execute(
          "CREATE TABLE IF NOT EXISTS USERS (\n"
              + "           "
              + uuidColumn
              + ",\n"
              + "           username TEXT ,\n"
              + "           password TEXT,\n"
              + "           authSecret TEXT,\n"
              + "           mode TEXT,\n"
              + "           ipAddress TEXT,\n"
              + "           passwordEncryption TEXT,\n"
              + "           userCreatedMs BIGINT,\n"
              + "           registeredMs BIGINT\n"
              + "       )");

      stmt.execute(
          "CREATE TABLE IF NOT EXISTS LOGIN_HISTORY (\n"
              + "           "
              + historyId
              + ",\n"
              + "           uuid TEXT,\n"
              + "           username TEXT,\n"
              + "           ip TEXT,\n"
              + "           country TEXT,\n"
              + "           mode TEXT,\n"
              + "           result TEXT,\n"
              + "           riskScore INTEGER,\n"
              + "           ts BIGINT\n"
              + "       )");

      AuthCoreServer.LOGGER.info(true, "Created users database if it doesn't exist!");

      // Define expected columns + types
      Map<String, String> expected = new java.util.LinkedHashMap<>();
      expected.put("uuid", "TEXT");
      expected.put("username", "TEXT");
      expected.put("password", "TEXT");
      expected.put("authSecret", "TEXT");
      expected.put("mode", "TEXT");
      expected.put("ipAddress", "TEXT");
      expected.put("passwordEncryption", "TEXT");
      expected.put("userCreatedMs", "BIGINT");
      expected.put("registeredMs", "BIGINT");
      expected.put("recoveryCodes", "TEXT");
      expected.put("lockUntilMs", "BIGINT");
      expected.put("email", "TEXT");
      expected.put("nickname", "TEXT");
      expected.put("deviceFingerprint", "TEXT");
      expected.put("trustedUntilMs", "BIGINT");
      expected.put("discordId", "TEXT");

      // SQLite exposes schema info via PRAGMA; MySQL/PostgreSQL use information_schema.
      Set<String> existing = readExistingColumns(stmt);

      // Add missing columns (schema migration for older databases)
      for (var entry : expected.entrySet())
        if (!existing.contains(entry.getKey())) {
          String sql =
              "ALTER TABLE USERS ADD COLUMN " + entry.getKey() + " " + entry.getValue() + ";";
          stmt.execute(sql);
          AuthCoreServer.LOGGER.info(true, "Added missing column: " + entry.getKey());
        }

      AuthCoreServer.LOGGER.info(true, "Users database initialized and patched!");

    } catch (SQLException err) {
      migrationBlocked = true;
      AuthCoreServer.LOGGER.error(false, "User's database connection is facing an error!", err);
      printMigrationGuidance();
    }
  }

  /**
   * Prints clear, actionable migration instructions when the schema cannot be patched
   * automatically. AuthCore keeps running but refuses registration/login until the admin fixes
   * the database.
   */
  public static void printMigrationGuidance() {
    AuthCoreServer.LOGGER.error(false, "==========================================================");
    AuthCoreServer.LOGGER.error(
        false, " AuthCore DATABASE MIGRATION REQUIRED - automatic migration FAILED.");
    AuthCoreServer.LOGGER.error(
        false, " Registration/login are DISABLED until the schema is fixed.");
    AuthCoreServer.LOGGER.error(false, " What to do (pick one):");
    AuthCoreServer.LOGGER.error(
        false,
        "   1. Back up your database file, then DELETE it and let AuthCore recreate it:");
    AuthCoreServer.LOGGER.error(
        false,
        "      config/authcore/database/" + AuthCoreServer.config.database.sqlite);
    AuthCoreServer.LOGGER.error(
        false, "   2. Or run the missing ALTER TABLE statements manually (see the log above).");
    AuthCoreServer.LOGGER.error(
        false, "   3. Or restore a backup from config/authcore/backups/.");
    AuthCoreServer.LOGGER.error(
        false,
        " Reference: https://github.com/DawnOfDedSec/AuthCore/blob/main/changelogs/changelog.md");
    AuthCoreServer.LOGGER.error(false, "==========================================================");
  }

  /** Reads the existing column names of the USERS table (dialect-aware). */
  private static Set<String> readExistingColumns(Statement stmt) throws SQLException {
    Set<String> existing = new HashSet<>();

    if (dialect == Dialect.SQLITE) {
      try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(USERS);")) {
        while (rs.next()) existing.add(rs.getString("name"));
      }
    } else {
      String schema = dialect == Dialect.POSTGRESQL ? "public" : connection.getCatalog();
      // Parameterized (never string-built): the schema/catalog value comes from server
      // configuration, and a quote inside a database name would otherwise break - or
      // inject into - the metadata query.
      try (PreparedStatement ps =
              connection.prepareStatement(
                  "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = ?"
                      + " AND TABLE_SCHEMA = ?");
          ResultSet rs = execColumnQuery(ps, schema)) {
        while (rs.next()) existing.add(rs.getString(1));
      }
    }
    return existing;
  }

  /** Binds the schema parameter and runs the information_schema column query. */
  private static ResultSet execColumnQuery(PreparedStatement ps, String schema) throws SQLException {
    ps.setString(1, "USERS");
    ps.setString(2, schema == null ? "" : schema);
    return ps.executeQuery();
  }

  // ------------------------------------------------------------------ name index

  private static volatile java.util.List<String> REGISTERED_NAMES = java.util.Collections.emptyList();
  private static volatile long REGISTERED_NAMES_AT_MS = 0L;

  /** Cached list of registered usernames (5 min TTL) - used by the confusable-name guard. */
  public static java.util.List<String> getRegisteredNames() {
    long now = System.currentTimeMillis();
    if (now - REGISTERED_NAMES_AT_MS < 5 * 60 * 1000L && !REGISTERED_NAMES.isEmpty())
      return REGISTERED_NAMES;
    try {
      java.util.List<String> names = new java.util.ArrayList<>();
      if (connection != null && !connection.isClosed()) {
        try (Statement stmt = connection.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT username FROM USERS WHERE username IS NOT NULL")) {
          while (rs.next()) {
            String name = rs.getString(1);
            if (name != null && !name.isBlank()) names.add(name);
          }
        }
      }
      REGISTERED_NAMES = java.util.Collections.unmodifiableList(names);
      REGISTERED_NAMES_AT_MS = now;
      return REGISTERED_NAMES;
    } catch (SQLException e) {
      AuthCoreServer.LOGGER.error(
          false,
          "["
              + net.ded3ec.util.ErrorCodes.code(
                  net.ded3ec.util.ErrorCodes.Module.DATABASE, net.ded3ec.util.ErrorCodes.Kind.QUERY, 2)
              + "] Failed to load registered names: {}",
          e.getLocalizedMessage());
      return REGISTERED_NAMES;
    }
  }

  /**
   * Auto-migration: clears the premium ("online-mode") flag from every account. Used once per
   * boot when the server is detected to run in offline-mode - on such servers no account can
   * be premium (the server authenticates nobody), so stale flags from earlier builds must not
   * keep bypassing registration. Genuinely online-mode accounts are re-flagged automatically when
   * the server runs online-mode again.
   *
   * @return the number of accounts downgraded
   */
  public static int downgradePremiumAccounts() {
    if (!connect()) return 0;
    try (Statement stmt = connection.createStatement()) {
      return stmt.executeUpdate("UPDATE USERS SET mode = 'offline-mode' WHERE mode = 'online-mode'");
    } catch (SQLException err) {
      AuthCoreServer.LOGGER.debug(
          false, "Online-mode flag auto-migration failed (best-effort):", err);
      return 0;
    }
  }
}
