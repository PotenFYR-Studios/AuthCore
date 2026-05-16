package net.ded3ec.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.ded3ec.AuthCoreServer;

/** Database class for database related management! */
public class Database {

  /** SQL Connection with java driver. */
  public static Connection connection;

  /**
   * Connect to the database (mySQL/SQLite).
   *
   * @return true if connection is successful, false otherwise
   */
  public static boolean connect() {

    if (AuthCoreServer.config.database.mysql.enabled) {
      try {
        if (connection != null && !connection.isClosed()) return true;

        AuthCoreServer.LOGGER.debug(
            true,
            "Connecting to Mysql database: {}:{}",
            AuthCoreServer.config.database.mysql.host,
            AuthCoreServer.config.database.mysql.port);

        // Construct JDBC URL for MySQL with SSL and timezone settings
        connection =
            DriverManager.getConnection(
                "jdbc:mysql://"
                    + AuthCoreServer.config.database.mysql.host
                    + ":"
                    + AuthCoreServer.config.database.mysql.port
                    + "/"
                    + AuthCoreServer.config.database.mysql.database
                    + "?useSSL="
                    + (Boolean.toString(AuthCoreServer.config.database.mysql.ssl))
                    + "&serverTimezone=UT",
                AuthCoreServer.config.database.mysql.username,
                AuthCoreServer.config.database.mysql.password);

        return AuthCoreServer.LOGGER.debug(
            true, "Mysql database has been connected with authCore!");
      } catch (SQLException err) {
        return AuthCoreServer.LOGGER.error(
            false,
            "Mysql database connection is facing an error while connecting to database: {}",
            err.getLocalizedMessage());
      }
    } else {
      try {
        if (connection != null && !connection.isClosed()) return true;

        AuthCoreServer.LOGGER.debug(
            true, "Connecting to SQLite database: {}", AuthCoreServer.config.database.sqlite);

        Path dbPath =
            AuthCoreServer.configPath
                .resolve("database")
                .resolve(AuthCoreServer.config.database.sqlite);

        if (!dbPath.isAbsolute())
          try {
            Files.createDirectories(dbPath.getParent());

            AuthCoreServer.LOGGER.debug(
                true,
                "Created the SQLite database file: {}",
                AuthCoreServer.config.database.sqlite);
          } catch (IOException err) {
            return AuthCoreServer.LOGGER.error(
                false, "SQLite database is facing an error while creating database file:", err);
          }

        connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);

        return AuthCoreServer.LOGGER.debug(
            true, "SQLite database has been connected with authCore!");
      } catch (SQLException err) {
        return AuthCoreServer.LOGGER.error(
            false,
            "SQLite database connection is facing an error while connecting to database: {}",
            err.getLocalizedMessage());
      }
    }
  }

  /** Load the Database creation and replace! */
  public static void load() {
    Database.connect();

    try (Statement stmt = connection.createStatement()) {
      stmt.execute(
          """
                    CREATE TABLE IF NOT EXISTS USERS (
                               uuid TEXT NOT NULL PRIMARY KEY,
                               username TEXT ,
                               password TEXT,
                               authSecret TEXT,
                               mode TEXT,
                               ipAddress TEXT,
                               passwordEncryption TEXT,
                               userCreatedMs BIGINT,
                               registeredMs BIGINT
                           );
                    """);

      AuthCoreServer.LOGGER.info(true, "Created users database if it doesn't exist!");

      // Define expected columns + types
      Map<String, String> expected =
          Map.of(
              "uuid", "TEXT",
              "username", "TEXT",
              "password", "TEXT",
              "authSecret", "TEXT",
              "mode", "TEXT",
              "ipAddress", "TEXT",
              "passwordEncryption", "TEXT",
              "userCreatedMs", "BIGINT",
              "registeredMs", "BIGINT");

      // Read existing columns
      Set<String> existing = new HashSet<>();
      try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(USERS);")) {
        while (rs.next()) existing.add(rs.getString("name"));
      }

      // Add missing columns
      for (var entry : expected.entrySet())
        if (!existing.contains(entry.getKey())) {
          String sql =
              "ALTER TABLE USERS ADD COLUMN " + entry.getKey() + " " + entry.getValue() + ";";
          stmt.execute(sql);
          AuthCoreServer.LOGGER.info(true, "Added missing column: " + entry.getKey());
        }

      AuthCoreServer.LOGGER.info(true, "Users database initialized and patched!");

    } catch (SQLException err) {
      AuthCoreServer.LOGGER.error(false, "User's database connection is facing an error!", err);
    }
  }
}
