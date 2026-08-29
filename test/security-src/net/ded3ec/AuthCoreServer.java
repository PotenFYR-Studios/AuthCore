package net.ded3ec;

import net.ded3ec.models.Config;
import net.ded3ec.models.Messages;
import net.ded3ec.util.Logger;

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

  private AuthCoreServer() {}
}
