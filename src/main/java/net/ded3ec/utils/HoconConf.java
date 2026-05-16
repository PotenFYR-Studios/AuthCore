package net.ded3ec.utils;

import java.nio.file.Files;

import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.Config;
import net.ded3ec.models.Messages;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;
import org.spongepowered.configurate.objectmapping.ObjectMapper;

/** .conf utility for managing the files. */
public class HoconConf {

  /** The Configurate loader for HOCON files. */
  private static final HoconConfigurationLoader CONFIGPARSER =
      HoconConfigurationLoader.builder()
          .path(AuthCoreServer.configPath.resolve("settings.conf"))
          .defaultOptions(options -> options.shouldCopyDefaults(true))
          .build();

  /** The Configurate loader for HOCON files. */
  private static final HoconConfigurationLoader MESSAGELOADER =
      HoconConfigurationLoader.builder()
          .path(AuthCoreServer.configPath.resolve("messages.conf"))
          .defaultOptions(options -> options.shouldCopyDefaults(true))
          .build();

  /** Initialize the database manager! */
  public static void initialize() {
    try {
      if (!AuthCoreServer.configPath.toFile().exists())
        Files.createDirectories(AuthCoreServer.configPath);

      loadConfig();
      saveConfig();

      loadMessages();
      saveMessages();

      AuthCoreServer.LOGGER.info(
          true, "Configuration from .conf files has been successfully loaded.");
    } catch (Exception err) {
      AuthCoreServer.LOGGER.error(
          false, "Facing error while loading and saving .conf files: ", err);
    }
  }

  /**
   * Loads the configuration from the file. If the file does not exist, a new one is created using
   */
  public static void loadConfig() {
    try {
      ObjectMapper<Config> mapper = ObjectMapper.factory().get(Config.class);

      var node = CONFIGPARSER.load();
      AuthCoreServer.config = mapper.load(node);
      mapper.save(AuthCoreServer.config, node);

      AuthCoreServer.LOGGER.info(true, "Configuration from settings.conf loaded successfully.");
    } catch (ConfigurateException err) {
      AuthCoreServer.LOGGER.error(
          false, "Facing error while loading configuration file 'settings.conf': ", err);
      AuthCoreServer.config = new Config();
    }
  }

  /**
   * Loads the configuration from the file. If the file does not exist, a new one is created using
   */
  public static void loadMessages() {
    try {
      ObjectMapper<Messages> mapper = ObjectMapper.factory().get(Messages.class);

      var node = MESSAGELOADER.load();
      AuthCoreServer.messages = mapper.load(node);
      mapper.save(AuthCoreServer.messages, node);

      AuthCoreServer.LOGGER.info(true, "Successfully saved messages̥ & default values");
    } catch (ConfigurateException err) {
      AuthCoreServer.LOGGER.error(false, "Facing error while loading messages file: ", err);
      AuthCoreServer.config = new Config();
    }
  }

  /** Manually saves the current in-memory config to the file. Use this after programmatically */
  public static void saveConfig() {
    if (AuthCoreServer.config == null) return;
    try {
      ObjectMapper<Config> mapper = ObjectMapper.factory().get(Config.class);

      var node = CONFIGPARSER.createNode();
      mapper.save(AuthCoreServer.config, node);
      CONFIGPARSER.save(node);

      AuthCoreServer.LOGGER.info(true, "Successfully saved configurations & default values");
    } catch (ConfigurateException err) {
      AuthCoreServer.LOGGER.error(false, "Facing error while saving configuration file: ", err);
    }
  }

  /** Manually saves the current in-memory config to the file. Use this after programmatically. */
  public static void saveMessages() {
    if (AuthCoreServer.messages == null) return;
    try {
      ObjectMapper<Messages> mapper = ObjectMapper.factory().get(Messages.class);

      var node = MESSAGELOADER.createNode();
      mapper.save(AuthCoreServer.messages, node);
      MESSAGELOADER.save(node);
    } catch (ConfigurateException err) {
      AuthCoreServer.LOGGER.error(false, "Facing error while saving messages file: ", err);
    }
  }
}
