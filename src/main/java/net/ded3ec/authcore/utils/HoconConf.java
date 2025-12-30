package net.ded3ec.authcore.utils;

import java.nio.file.Files;
import net.ded3ec.authcore.AuthCore;
import net.ded3ec.authcore.models.Config;
import net.ded3ec.authcore.models.Messages;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;
import org.spongepowered.configurate.objectmapping.ObjectMapper;

/** .conf utility for managing the files. */
public class HoconConf {

  /** The Configurate loader for HOCON files. */
  private static final HoconConfigurationLoader CONFIGLOADER =
      HoconConfigurationLoader.builder()
          .path(AuthCore.configPath.resolve("settings.conf"))
          .defaultOptions(options -> options.shouldCopyDefaults(true))
          .build();

  /** The Configurate loader for HOCON files. */
  private static final HoconConfigurationLoader MESSAGELOADER =
      HoconConfigurationLoader.builder()
          .path(AuthCore.configPath.resolve("messages.conf"))
          .defaultOptions(options -> options.shouldCopyDefaults(true))
          .build();

  /** Initialize the database manager! */
  public static void initialize() {
    try {
      if (!AuthCore.configPath.toFile().exists()) Files.createDirectories(AuthCore.configPath);

      loadConfig();
      saveConfig();

      loadMessages();
      saveMessages();

      Logger.info(true, "Configuration from .conf files has been successfully loaded.");
    } catch (Exception err) {
      Logger.error(false, "Facing error while loading and saving .conf files: ", err);
    }
  }

  /**
   * Loads the configuration from the file. If the file does not exist, a new one is created using
   */
  public static void loadConfig() {
    try {
      ObjectMapper<Config> mapper = ObjectMapper.factory().get(Config.class);

      var node = CONFIGLOADER.load();
      AuthCore.config = mapper.load(node);
      mapper.save(AuthCore.config, node);

      Logger.info(true, "Configuration from settings.conf loaded successfully.");
    } catch (ConfigurateException err) {
      Logger.error(false, "Facing error while loading configuration file 'settings.conf': ", err);
      AuthCore.config = new Config();
    }
  }

  /**
   * Loads the configuration from the file. If the file does not exist, a new one is created using
   */
  public static void loadMessages() {
    try {
      ObjectMapper<Messages> mapper = ObjectMapper.factory().get(Messages.class);

      var node = MESSAGELOADER.load();
      AuthCore.messages = mapper.load(node);
      mapper.save(AuthCore.messages, node);

      Logger.info(true, "Successfully saved messages̥ & default values");
    } catch (ConfigurateException err) {
      Logger.error(false, "Facing error while loading messages file: ", err);
      AuthCore.config = new Config();
    }
  }

  /** Manually saves the current in-memory config to the file. Use this after programmatically */
  public static void saveConfig() {
    if (AuthCore.config == null) return;
    try {
      ObjectMapper<Config> mapper = ObjectMapper.factory().get(Config.class);

      var node = CONFIGLOADER.createNode();
      mapper.save(AuthCore.config, node);
      CONFIGLOADER.save(node);

      Logger.info(true, "Successfully saved configurations & default values");
    } catch (ConfigurateException err) {
      Logger.error(false, "Facing error while saving configuration file: ", err);
    }
  }

  /** Manually saves the current in-memory config to the file. Use this after programmatically. */
  public static void saveMessages() {
    if (AuthCore.messages == null) return;
    try {
      ObjectMapper<Messages> mapper = ObjectMapper.factory().get(Messages.class);

      var node = MESSAGELOADER.createNode();
      mapper.save(AuthCore.messages, node);
      MESSAGELOADER.save(node);
    } catch (ConfigurateException err) {
      Logger.error(false, "Facing error while saving messages file: ", err);
    }
  }
}
