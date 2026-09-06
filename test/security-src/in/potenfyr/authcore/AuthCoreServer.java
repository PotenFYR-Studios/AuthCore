package in.potenfyr.authcore;

import in.potenfyr.authcore.models.Config;
import in.potenfyr.authcore.models.Messages;
import in.potenfyr.authcore.util.Logger;

/**
 * Standalone stub used by the security test harness. It shadows the real AuthCoreServer class
 * (which requires Minecraft) so pure-logic components can be tested without a server.
 */
public final class AuthCoreServer {
  public static final String MOD_ID = "authcore";
  public static final Logger LOGGER = new Logger("authcore-test");
  public static final java.nio.file.Path configPath =
      java.nio.file.Path.of(System.getProperty("authcore.test.configdir", "build/test-config"));
  public static final java.util.concurrent.ExecutorService IO_EXECUTOR =
      java.util.concurrent.Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "AuthCore-Test-IO");
        t.setDaemon(true);
        return t;
      });
  public static Config config = new Config();
  public static Messages messages = new Messages();

  /** Mirrors the real boot-time version check (see AuthCoreServer.isTestedMinecraftVersion). */
  public static boolean isTestedMinecraftVersion(String gameVersion) {
    if (gameVersion == null || gameVersion.isBlank()) return false;
    if (gameVersion.startsWith("1.")) return true;
    java.util.regex.Matcher snap =
        java.util.regex.Pattern.compile("^(\\d{2})w\\d{2}[a-z]$").matcher(gameVersion);
    if (snap.find()) {
      int year = Integer.parseInt(snap.group(1));
      return year >= 23;
    }
    java.util.regex.Matcher major =
        java.util.regex.Pattern.compile("^(\\d+)").matcher(gameVersion);
    if (major.find()) {
      try {
        return Integer.parseInt(major.group(1)) >= 23;
      } catch (NumberFormatException ignored) {
      }
    }
    return false;
  }

  private AuthCoreServer() {}
}
