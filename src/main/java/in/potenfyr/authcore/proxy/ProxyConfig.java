package in.potenfyr.authcore.proxy;

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

  // Full proxy-side auth: block players without a valid network session from reaching GAME
  // servers. The check runs when a player is about to CONNECT TO A BACKEND (not at proxy login -
  // a login-time gate can never be satisfied, because the player must first reach a backend to
  // authenticate). Unauthenticated players are sent to `auth-server` (or may stay on the server
  // they first land on when no auth server is configured); authenticated players move freely.
  // Fail-closed (default): when neither the proxy session cache nor Redis can confirm a session,
  // the player is blocked so an outage cannot bypass authentication entirely.
  public boolean blockUnauthenticated = false;
  public boolean failClosed = true;

  /**
   * The backend server unauthenticated players are allowed to join (the auth/hub server where
   * /login and /register are used). When set, the proxy redirects an unauthenticated player's
   * initial connect here and blocks transfers to every other server. Empty = the player may stay
   * on whichever server the proxy sent them to first, but cannot switch servers until they
   * authenticate.
   */
  public String authServer = "";

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
      cfg.failClosed = parseBool(props.getProperty("fail-closed"), cfg.failClosed);
      cfg.authServer = props.getProperty("auth-server", cfg.authServer).trim();
      cfg.redisHost = props.getProperty("redis-host", cfg.redisHost);
      cfg.redisPort = (int) parseLong(props.getProperty("redis-port"), cfg.redisPort);
      cfg.redisPassword = props.getProperty("redis-password", cfg.redisPassword);
      cfg.redisDatabase = (int) parseLong(props.getProperty("redis-database"), cfg.redisDatabase);
    } catch (Exception err) {
      throw new IllegalStateException("Cannot safely load AuthCore proxy configuration", err);
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
        + "\n# Full proxy-side auth: block players without a valid network session from reaching"
        + "\n# GAME servers. Checked when a player connects to a backend (after login), and satisfied"
        + "\n# by the backend AUTH_CHANGED interop messages (proxy session cache) or Redis."
        + "\nblock-unauthenticated=false"
        + "\n# Backend server unauthenticated players are allowed to join (the auth/hub server where"
        + "\n# /login and /register are used). The proxy redirects their initial connect here and"
        + "\n# blocks transfers to other servers until they authenticate. Empty = they may stay on"
        + "\n# the server the proxy sent them to first, but cannot switch servers before logging in."
        + "\nauth-server="
        + "\n# Fail-closed (default=true): block connections when no session can be confirmed."
        + "\n# Set to false for fail-open (allow through during Redis outages)."
        + "\nfail-closed=true"
        + "\nredis-host=127.0.0.1"
        + "\nredis-port=6379"
        + "\nredis-password="
        + "\nredis-database=0"
        + "\n";
  }

  private static boolean parseBool(String value, boolean fallback) {
    if (value == null) return fallback;
    if ("true".equalsIgnoreCase(value.trim())) return true;
    if ("false".equalsIgnoreCase(value.trim())) return false;
    throw new IllegalArgumentException("Expected true or false, got: " + value);
  }

  private static long parseLong(String value, long fallback) {
    try {
      return value == null ? fallback : Long.parseLong(value.trim());
    } catch (NumberFormatException err) {
      return fallback;
    }
  }
}
