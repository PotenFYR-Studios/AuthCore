package net.ded3ec.authcore.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;

import net.ded3ec.authcore.AuthCore;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Database class for database related management!
 */
public class Database {

    /**
     * SQL Connection with java driver.
     */
    public static Connection connection;

    /**
     * Connect to the database (mySQL/SQLite).
     * @return true if connection is successful, false otherwise
     */
    public static boolean connect() {

        if (AuthCore.config.database.mysql.enabled) {
            try {
                if (connection != null && !connection.isClosed()) return true;

                Logger.debug(true, "Connecting to Mysql database: {}:{}", AuthCore.config.database.mysql.host, AuthCore.config.database.mysql.port);

                // Construct JDBC URL for MySQL with SSL and timezone settings
                connection = DriverManager.getConnection("jdbc:mysql://" + AuthCore.config.database.mysql.host + ":" + AuthCore.config.database.mysql.port + "/" + AuthCore.config.database.mysql.database + "?useSSL=" + (AuthCore.config.database.mysql.ssl ? "true" : "false") + "&serverTimezone=UT", AuthCore.config.database.mysql.username, AuthCore.config.database.mysql.password);

                return Logger.debug(true, "Mysql database has been connected with authCore!");
            } catch (SQLException err) {
                return Logger.error(false, "Mysql database connection is facing an error while connecting to database: {}", err.getLocalizedMessage());
            }
        }

        else {
            try {
                if (connection != null && !connection.isClosed()) return true;

                Logger.debug(true, "Connecting to SQLite database: {}", AuthCore.config.database.sqlite);

                Path dbPath = AuthCore.configPath.resolve("database").resolve(AuthCore.config.database.sqlite);

                if (!dbPath.isAbsolute()) try {
                    Files.createDirectories(dbPath.getParent());

                    Logger.debug(true, "Created the SQLite database file: {}", AuthCore.config.database.sqlite);
                } catch (IOException err) {
                    return Logger.error(false, "SQLite database is facing an error while creating database file:", err);
                }

                connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);

                return Logger.debug(true, "SQLite database has been connected with authCore!");
            } catch (SQLException err) {
                return Logger.error(false, "SQLite database connection is facing an error while connecting to database: {}", err.getLocalizedMessage());
            }
        }
    }

    /**
     * Load the Database creation and replace!
     */
    public static void load() {
        Database.connect();

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS USERS (
                               username TEXT PRIMARY KEY,
                               uuid TEXT NOT NULL,
                               password TEXT,
                               mode TEXT,
                               ipAddress TEXT,
                               passwordEncryption TEXT,
                               userCreatedMs BIGINT
                           );
                    """);

            Logger.info(true,"Created users database if it doesn't exist!");
        } catch (SQLException err) {
            Logger.error(false, "User's database connection is facing an error!", err);
        }
    }
}
