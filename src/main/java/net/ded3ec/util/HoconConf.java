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
 * <p>The configuration is split across MULTIPLE files, each file owning one config block:
 *
 * <pre>
 *   settings.conf          - root-level settings (version, language, debugMode, logging, cache)
 *   lobby.conf             - the `lobby { ... }` block (limbo restrictions, captcha, timeout)
 *   session.conf           - the `session { ... }` block (auth flow, sessions, SSO, client guard)
 *   password-rules.conf    - the `passwordRules { ... }` block
 *   commands.conf          - the `commands { ... }` block (permissions per command)
 *   database.conf          - the `database { ... }` block (SQLite/MySQL/PostgreSQL/Redis)
 *   messages-&lt;lang&gt;.conf   - player-facing messages (messages.conf for English)
 * </pre>
 *
 * <p>When a section file exists it OVERRIDES the same block in {@code settings.conf} (the
 * legacy sections are stripped from settings.conf on save, so there is exactly one owner per
 * setting). On first boot every file is written with the bundled defaults, so admins see the
 * full structure immediately.
 */
public class HoconConf {

  private HoconConf() {}

  /** Config directory base. */
  private static final Path CONFIG_DIR = AuthCoreServer.configPath;

  /** Config block -> file mapping for the split configuration (deterministic order). */
  private static final java.util.LinkedHashMap<String, String> SECTION_FILES =
      new java.util.LinkedHashMap<>();

  static {
    SECTION_FILES.put("lobby", "lobby.conf");
    SECTION_FILES.put("session", "session.conf");
    SECTION_FILES.put("passwordRules", "password-rules.conf");
    SECTION_FILES.put("commands", "commands.conf");
    SECTION_FILES.put("database", "database.conf");
  }

  /** Builds a loader for a config file in the config directory. */
  private static HoconConfigurationLoader loader(String fileName) {
    return HoconConfigurationLoader.builder()
        .path(CONFIG_DIR.resolve(fileName))
        .defaultOptions(options -> options.shouldCopyDefaults(true))
        .build();
  }

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
      Path localePath = CONFIG_DIR.resolve(localeFile);

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
        .path(CONFIG_DIR.resolve(fileName))
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

      Files.createDirectories(CONFIG_DIR);
      Files.copy(in, target);
      AuthCoreServer.LOGGER.info(
          true,
          "Extracted bundled locale file for '{}' to '{}'.",
          language,
          CONFIG_DIR.resolve(localeFile));
    } catch (IOException err) {
      AuthCoreServer.LOGGER.error(
          false,
          "["
              + net.ded3ec.util.ErrorCodes.code(
                  net.ded3ec.util.ErrorCodes.Module.CONFIG, net.ded3ec.util.ErrorCodes.Kind.IO, 1)
              + "] Failed to extract bundled locale file '{}':",
          localeFile,
          err);
    }
  }

  /** Initialize the database manager! */
  public static void initialize() {
    try {
      if (!CONFIG_DIR.toFile().exists()) Files.createDirectories(CONFIG_DIR);

      loadConfig();

      // Messages must be loaded BEFORE the migration runner: message-default migrations
      // (e.g. the enriched title templates) transform the in-memory Messages model and then
      // persist it - with messages still null the refresh silently no-ops and existing
      // configs keep the old single-channel defaults forever.
      loadMessages();

      // Always-on, idempotent message-default refresh: fills the enriched titles into any
      // messages file that still holds the old single-channel defaults, regardless of the
      // stored config version (customized templates are preserved).
      net.ded3ec.util.ConfigMigrator.refreshEnrichedMessageDefaults();

      // Always-on safety normalization (blindness breaks the auth chat).
      net.ded3ec.util.ConfigMigrator.normalizeBlindness();

      // Version-gated migration runner (new keys are added automatically by Configurate;
      // this applies structural transforms between config versions and bumps the version).
      net.ded3ec.util.ConfigMigrator.migrate();

      saveConfig();
      saveMessages();

      AuthCoreServer.LOGGER.info(
          true, "Configuration from .conf files has been successfully loaded.");
    } catch (Exception err) {
      AuthCoreServer.LOGGER.error(
          false,
          "["
              + net.ded3ec.util.ErrorCodes.code(
                  net.ded3ec.util.ErrorCodes.Module.CONFIG, net.ded3ec.util.ErrorCodes.Kind.PARSE, 1)
              + "] Facing error while loading and saving .conf files: ",
          err);
    }
  }

  /**
   * Assembles the merged configuration tree: {@code settings.conf} provides the base (root
   * keys AND any legacy section copies); every existing section file then OVERRIDES its own
   * block on top. Missing section files fall back to the settings.conf copy / defaults.
   */
  private static org.spongepowered.configurate.ConfigurationNode buildMergedNode() {
    var root = loader("settings.conf").createNode();

    try {
      root.mergeFrom(loader("settings.conf").load());
    } catch (ConfigurateException err) {
      AuthCoreServer.LOGGER.error(
          false, "Facing error while loading configuration file 'settings.conf': ", err);
    }

    for (var entry : SECTION_FILES.entrySet()) {
      Path file = CONFIG_DIR.resolve(entry.getValue());
      if (!Files.exists(file)) continue;
      try {
        var section = HoconConfigurationLoader.builder()
            .path(file)
            .defaultOptions(options -> options.shouldCopyDefaults(true))
            .build()
            .load();
        if (section == null || section.empty()) continue;
        var wrapped = loader("settings.conf").createNode();
        wrapped.node(entry.getKey()).mergeFrom(section);
        root.mergeFrom(wrapped);
        AuthCoreServer.LOGGER.debug(
            true, "Config override applied from '{}' (block '{}').", entry.getValue(), entry.getKey());
      } catch (ConfigurateException err) {
        AuthCoreServer.LOGGER.error(
            false, "Facing error while loading configuration file '{}': ", entry.getValue(), err);
      }
    }

    return root;
  }

  /**
   * Loads the configuration from the split files. If a file does not exist, it is created with
   * the bundled defaults on the first save.
   */
  public static void loadConfig() {
    try {
      ObjectMapper<Config> mapper = ObjectMapper.factory().get(Config.class);

      var node = buildMergedNode();
      AuthCoreServer.config = mapper.load(node);

      // Apply distributed Redis config overrides on top of the local settings
      applyRedisConfigOverrides();

      AuthCoreServer.LOGGER.info(
          true,
          "Configuration from settings.conf + section files loaded successfully ({}).",
          String.join(", ", SECTION_FILES.values()));
    } catch (ConfigurateException err) {
      AuthCoreServer.LOGGER.error(
          false, "Facing error while loading the configuration files: ", err);
      AuthCoreServer.config = new Config();
    }
  }

  /**
   * Loads the messages for the configured language. If the locale file does not exist, it is
   * extracted from the jar resources first. Custom locales dropped into the config directory
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
      var defaultNode = loader("messages.conf").createNode();
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
      var node = buildMergedNode();

      // Write the override snippet to a temp file and load it
      Path tmp = CONFIG_DIR.resolve("redis-overrides.tmp");
      Files.createDirectories(CONFIG_DIR);
      Files.writeString(tmp, overrides);

      var overrideNode =
          HoconConfigurationLoader.builder()
              .path(tmp)
              .build()
              .load();
      Files.deleteIfExists(tmp);

      int applied = 0;
      for (var entry : overrideNode.childrenMap().entrySet()) {
        node.node(entry.getKey()).set(entry.getValue());
        applied++;
      }

      AuthCoreServer.config = mapper.load(node);

      AuthCoreServer.LOGGER.info(
          true, "Applied {} Redis config override(s) from authcore:config:overrides", applied);
    } catch (Exception err) {
      AuthCoreServer.LOGGER.error(
          false, "Failed to apply Redis config overrides:", err);
    }
  }

  /**
   * Manually saves the current in-memory config into the split files: every section file owns
   * one config block, and {@code settings.conf} keeps only the root-level keys (the owned
   * blocks are stripped, so every setting has exactly one owner).
   */
  public static void saveConfig() {
    if (AuthCoreServer.config == null) return;
    try {
      ObjectMapper<Config> mapper = ObjectMapper.factory().get(Config.class);

      var full = loader("settings.conf").createNode();
      mapper.save(AuthCoreServer.config, full);

      // Section files first - each owns one config block.
      for (var entry : SECTION_FILES.entrySet()) {
        var sectionNode = loader("settings.conf").createNode();
        org.spongepowered.configurate.ConfigurationNode child = full.node(entry.getKey());
        if (!child.empty()) sectionNode.node(entry.getKey()).set(child);
        loader(entry.getValue()).save(sectionNode);
      }

      // settings.conf keeps only the root-level keys.
      for (String key : SECTION_FILES.keySet()) full.removeChild(key);
      loader("settings.conf").save(full);

      AuthCoreServer.LOGGER.info(true, "Successfully saved configurations & default values");
    } catch (ConfigurateException err) {
      AuthCoreServer.LOGGER.error(false, "Facing error while saving configuration files: ", err);
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
