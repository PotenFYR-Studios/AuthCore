package net.ded3ec.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.Config;
import net.ded3ec.models.Messages;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;
import org.spongepowered.configurate.objectmapping.ObjectMapper;

/**
 * HOCON (.conf) configuration manager.
 *
 * <p>Loads {@code settings.conf} and the selected {@code messages-<lang>.conf} (or {@code
 * messages.conf} for English). If a locale file does not exist yet, it is extracted from the mod
 * jar's bundled resources (e.g. {@code /assets/authcore/lang/messages-zh.conf}).
 */
public class HoconConf {

  private HoconConf() {}

  /** The Configurate loader for HOCON files. */
  private static final HoconConfigurationLoader CONFIGPARSER =
      HoconConfigurationLoader.builder()
          .path(AuthCoreServer.configPath.resolve("settings.conf"))
          .defaultOptions(options -> options.shouldCopyDefaults(true))
          .build();

  /**
   * Resolves the correct messages file for the configured language, extracting the bundled
   * locale from the jar when the file does not exist yet.
   *
   * @return the loader for the resolved messages file
   */
  private static HoconConfigurationLoader resolveMessagesLoader() {
    String language =
        (AuthCoreServer.config != null && AuthCoreServer.config.language != null)
            ? AuthCoreServer.config.language.trim().toLowerCase()
            : "en";

    String fileName = "messages.conf";
    if (!language.isEmpty() && !"en".equals(language)) {
      String localeFile = "messages-" + language + ".conf";
      Path localePath = AuthCoreServer.configPath.resolve(localeFile);

      if (!Files.exists(localePath)) extractBundledLocale(language, localeFile, localePath);

      if (Files.exists(localePath)) fileName = localeFile;
      else
        AuthCoreServer.LOGGER.warn(
            false,
            "Language '{}' has no bundled or existing locale file '{}' - falling back to English.",
            language,
            localeFile);
    }

    return HoconConfigurationLoader.builder()
        .path(AuthCoreServer.configPath.resolve(fileName))
        .defaultOptions(options -> options.shouldCopyDefaults(true))
        .build();
  }

  /** Copies the bundled locale file from the jar resources into the config directory. */
  private static void extractBundledLocale(String language, String localeFile, Path target) {
    String resource = "/assets/authcore/lang/" + localeFile;

    try (InputStream in = HoconConf.class.getResourceAsStream(resource)) {
      if (in == null) {
        AuthCoreServer.LOGGER.warn(
            false, "Bundled locale file '{}' was not found in the mod jar.", resource);
        return;
      }

      Files.createDirectories(AuthCoreServer.configPath);
      Files.copy(in, target);
      AuthCoreServer.LOGGER.info(
          true,
          "Extracted bundled locale file for '{}' to '{}'.",
          language,
          AuthCoreServer.configPath.resolve(localeFile));
    } catch (IOException err) {
      AuthCoreServer.LOGGER.error(
          false, "Failed to extract bundled locale file '{}':", localeFile, err);
    }
  }

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

      // Optional SEPARATE database config: config/authcore/database.conf - a small file that
      // only overrides the `database { ... }` block (SQLite/MySQL/PostgreSQL/Redis), letting
      // admins keep credentials outside settings.conf.
      applyDatabaseConfigOverrides(node);

      // Apply distributed Redis config overrides on top of the local settings
      applyRedisConfigOverrides();

      AuthCoreServer.LOGGER.info(true, "Configuration from settings.conf loaded successfully.");
    } catch (ConfigurateException err) {
      AuthCoreServer.LOGGER.error(
          false, "Facing error while loading configuration file 'settings.conf': ", err);
      AuthCoreServer.config = new Config();
    }
  }

  /** Merges {@code database.conf} (if present) over the loaded configuration node. */
  private static void applyDatabaseConfigOverrides(org.spongepowered.configurate.ConfigurationNode node) {
    try {
      java.nio.file.Path dbConf = AuthCoreServer.configPath.resolve("database.conf");
      if (!java.nio.file.Files.exists(dbConf)) return;

      var override =
          HoconConfigurationLoader.builder().path(dbConf).build().load();
      // database.conf wins over settings.conf for the `database` block:
      // merge settings INTO the override (settings fill gaps only).
      override.mergeFrom(node);
      AuthCoreServer.config = ObjectMapper.factory().get(Config.class).load(override);
      AuthCoreServer.LOGGER.info(
          true, "Database overrides applied from database.conf (separate config file).");
    } catch (Exception err) {
      AuthCoreServer.LOGGER.error(
          false, "Failed to load optional 'database.conf' - using settings.conf database block.", err);
    }
  }

  /**
   * Loads the messages for the configured language. If the locale file does not exist, it is
   * extracted from the mod jar resources first. Custom locales dropped into the config directory
   * are picked up automatically and checked for completeness.
   */
  public static void loadMessages() {
    try {
      ObjectMapper<Messages> mapper = ObjectMapper.factory().get(Messages.class);

      HoconConfigurationLoader loader = resolveMessagesLoader();
      var node = loader.load();
      AuthCoreServer.messages = mapper.load(node);
      mapper.save(AuthCoreServer.messages, node);

      String language =
          AuthCoreServer.config != null && AuthCoreServer.config.language != null
              ? AuthCoreServer.config.language
              : "en";

      if (!"en".equalsIgnoreCase(language)) checkLocaleCompleteness(language);

      AuthCoreServer.LOGGER.info(
          true, "Messages loaded successfully (language: '{}').", language);
    } catch (ConfigurateException err) {
      AuthCoreServer.LOGGER.error(false, "Facing error while loading messages file: ", err);
      if (AuthCoreServer.messages == null) AuthCoreServer.messages = new Messages();
    }
  }

  /**
   * Compares the loaded locale against the English defaults and warns about missing keys, so
   * admins adding custom locale files know exactly what still needs translating.
   */
  private static void checkLocaleCompleteness(String language) {
    try {
      ObjectMapper<Messages> mapper = ObjectMapper.factory().get(Messages.class);

      // English defaults as the reference
      Messages defaults = new Messages();
      var defaultNode = CONFIGPARSER.createNode();
      mapper.save(defaults, defaultNode);

      var localeNode = resolveMessagesLoader().load();
      java.util.List<String> missing = new java.util.ArrayList<>();
      for (var entry : defaultNode.childrenMap().entrySet()) {
        if (!localeNode.hasChild(entry.getKey())) missing.add(entry.getKey().toString());
      }

      if (!missing.isEmpty())
        AuthCoreServer.LOGGER.warn(
            false,
            "Locale '{}' is missing {} message key(s) - they will fall back to English: {}",
            language,
            missing.size(),
            String.join(", ", missing));
    } catch (Exception ignored) {
      // completeness check is best-effort
    }
  }

  /**
   * Applies optional Redis-distributed config overrides on top of the local settings (for server
   * networks). The override is a HOCON snippet stored under {@code authcore:config:overrides}.
   */
  private static void applyRedisConfigOverrides() {
    if (AuthCoreServer.config == null
        || !AuthCoreServer.config.database.redis.enabled) return;

    String overrides = net.ded3ec.network.RedisManager.getConfigOverrides();
    if (overrides == null || overrides.isBlank()) return;

    try {
      ObjectMapper<Config> mapper = ObjectMapper.factory().get(Config.class);
      var node = CONFIGPARSER.load();

      // Parse the HOCON override snippet and merge it over the local settings
      var overrideLoader =
          HoconConfigurationLoader.builder()
              .source(
                  () ->
                      java.nio.file.Files.newBufferedReader(
                          java.nio.file.Path.of(
                              AuthCoreServer.configPath.toString(),
                              "..",
                              "redis-overrides.tmp")))
              .build();

      // Write the override snippet to a temp file and load it (source() takes a reader)
      java.nio.file.Path tmp =
          AuthCoreServer.configPath.resolve("redis-overrides.tmp");
      java.nio.file.Files.createDirectories(AuthCoreServer.configPath);
      java.nio.file.Files.writeString(tmp, overrides);

      var overrideNode =
          HoconConfigurationLoader.builder()
              .path(tmp)
              .build()
              .load();

      int applied = 0;
      for (var entry : overrideNode.childrenMap().entrySet()) {
        node.node(entry.getKey()).set(entry.getValue());
        applied++;
      }
      java.nio.file.Files.deleteIfExists(tmp);

      AuthCoreServer.config = mapper.load(node);
      mapper.save(AuthCoreServer.config, node);

      AuthCoreServer.LOGGER.info(
          true, "Applied {} Redis config override(s) from authcore:config:overrides", applied);
    } catch (Exception err) {
      AuthCoreServer.LOGGER.error(
          false, "Failed to apply Redis config overrides:", err);
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

  /** Manually saves the current in-memory messages to the file. */
  public static void saveMessages() {
    if (AuthCoreServer.messages == null) return;
    try {
      ObjectMapper<Messages> mapper = ObjectMapper.factory().get(Messages.class);
      HoconConfigurationLoader loader = resolveMessagesLoader();

      var node = loader.createNode();
      mapper.save(AuthCoreServer.messages, node);
      loader.save(node);
    } catch (ConfigurateException err) {
      AuthCoreServer.LOGGER.error(false, "Facing error while saving messages file: ", err);
    }
  }
}
