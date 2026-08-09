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
