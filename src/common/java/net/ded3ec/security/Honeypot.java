package net.ded3ec.security;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import net.ded3ec.AuthCoreServer;

/**
 * Fake-server honeypot: listens on a separate port and treats every connection as an attacker
 * scan. The connecting IP is logged and automatically appended as a "deny" rule to
 * {@code ip-rules.conf}, permanently banning the scanner.
 */
public final class Honeypot {

  private static volatile ServerSocket server;

  private Honeypot() {}

  /** Starts the honeypot listener (no-op when disabled). */
  public static synchronized void start() {
    if (server != null) return;
    var cfg = AuthCoreServer.config.session.honeypot;
    if (!cfg.enabled) return;

    try {
      server = new ServerSocket();
      server.bind(new InetSocketAddress("0.0.0.0", cfg.port));

      Thread thread =
          new Thread(
              () -> {
                while (server != null && !server.isClosed()) {
                  try (Socket socket = server.accept()) {
                    String ip = socket.getInetAddress().getHostAddress();
                    ban(ip);
                    socket.close();
                  } catch (IOException ignored) {
                    // listener closed or transient error - keep waiting
                  }
                }
              },
              "AuthCore-Honeypot");
      thread.setDaemon(true);
      thread.start();

      AuthCoreServer.LOGGER.warn(
          false,
          "Honeypot listening on port {} - every connection will be auto-banned!",
          cfg.port);
    } catch (IOException err) {
      AuthCoreServer.LOGGER.error(false, "Failed to start the honeypot listener:", err);
      server = null;
    }
  }

  /** Stops the honeypot listener. */
  public static synchronized void stop() {
    if (server != null) {
      try {
        server.close();
      } catch (IOException ignored) {
        // already closed
      }
      server = null;
    }
  }

  /** Logs the attack and appends a permanent deny rule for the IP. */
  private static void ban(String ip) {
    AuthCoreServer.LOGGER.warn(
        true, "Honeypot: connection from {} - IP banned automatically.", ip);
    SecurityLog.log("HONEYPOT_HIT", ip + " connected to the honeypot - banned");

    try {
      Path rules =
          AuthCoreServer.configPath.resolve(
              AuthCoreServer.config.session.security.ipRulesFile);
      Files.createDirectories(AuthCoreServer.configPath);
      Files.writeString(
          rules,
          "deny " + ip + System.lineSeparator(),
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
      IpRules.load();
    } catch (IOException err) {
      AuthCoreServer.LOGGER.error(false, "Failed to write honeypot ban rule:", err);
    }
  }
}
