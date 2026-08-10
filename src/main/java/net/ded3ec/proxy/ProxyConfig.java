package net.ded3ec.proxy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Proxy-mode configuration - a SEPARATE config file ({@code config/authcore-proxy.properties})
 * created automatically when the jar runs on BungeeCord or Velocity. Zero-dependency
 * properties format so the proxy plugin needs no shaded libraries.
 */
public final class ProxyConfig {

  public static final long DEFAULT_TIMEOUT = 60 * 60 * 1000L;

  public boolean enabled = true;
  public String kickMessage = "You must log in on the main server first.";
  public long sessionTimeoutMs = DEFAULT_TIMEOUT;
  public boolean logEvents = true;

  // Full proxy-side auth: block players without a valid Redis session before they reach
  // any backend. Fail-open: when Redis is unreachable, connections are allowed.
  public boolean blockUnauthenticated = false;
  public String redisHost = "127.0.0.1";
  public int redisPort = 6379;
  public String redisPassword = "";
  public int redisDatabase = 0;

  private ProxyConfig() {}

  /** Loads (or creates with defaults) the proxy config file. */
  public static ProxyConfig load(Path configDir) {
    ProxyConfig cfg = new ProxyConfig();
    try {
      Files.createDirectories(configDir);
      Path file = configDir.resolve("authcore-proxy.properties");
      if (!Files.exists(file)) {
        Files.write(file, defaults().getBytes(StandardCharsets.UTF_8));
      }
      Properties props = new Properties();
      try (java.io.Reader reader =
          Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
        props.load(reader);
      }
      cfg.enabled = parseBool(props.getProperty("enabled"), cfg.enabled);
      cfg.kickMessage = props.getProperty("kick-message", cfg.kickMessage);
      cfg.sessionTimeoutMs = parseLong(props.getProperty("session-timeout-ms"), cfg.sessionTimeoutMs);
      cfg.logEvents = parseBool(props.getProperty("log-events"), cfg.logEvents);
      cfg.blockUnauthenticated = parseBool(props.getProperty("block-unauthenticated"), cfg.blockUnauthenticated);
      cfg.redisHost = props.getProperty("redis-host", cfg.redisHost);
      cfg.redisPort = (int) parseLong(props.getProperty("redis-port"), cfg.redisPort);
      cfg.redisPassword = props.getProperty("redis-password", cfg.redisPassword);
      cfg.redisDatabase = (int) parseLong(props.getProperty("redis-database"), cfg.redisDatabase);
    } catch (Exception err) {
      // config is best-effort - defaults keep the plugin functional
    }
    return cfg;
  }

  private static String defaults() {
    return "# AuthCore proxy-mode configuration (BungeeCord / Velocity)"
        + "\n# Created automatically when the jar runs as a proxy plugin."
        + "\nenabled=true"
        + "\nkick-message=You must log in on the main server first."
        + "\nsession-timeout-ms=3600000"
        + "\nlog-events=true"
        + "\n"
        + "\n# Full proxy-side auth: block players without a valid Redis session"
        + "\n# (authcore:session:<uuid>) BEFORE they reach any backend. Fail-open."
        + "\nblock-unauthenticated=false"
        + "\nredis-host=127.0.0.1"
        + "\nredis-port=6379"
        + "\nredis-password="
        + "\nredis-database=0"
        + "\n";
  }

  private static boolean parseBool(String value, boolean fallback) {
    return value == null ? fallback : Boolean.parseBoolean(value.trim());
  }

  private static long parseLong(String value, long fallback) {
    try {
      return value == null ? fallback : Long.parseLong(value.trim());
    } catch (NumberFormatException err) {
      return fallback;
    }
  }
}
