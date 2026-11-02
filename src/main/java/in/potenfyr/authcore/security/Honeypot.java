package in.potenfyr.authcore.security;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import in.potenfyr.authcore.AuthCoreServer;

/**
 * Fake-server honeypot: listens on a separate port and treats every connection as an attacker
 * scan. The connecting IP is logged and automatically appended as a "deny" rule to
 * {@code ip-rules.conf}, permanently banning the scanner.
 */
public final class Honeypot {

  private static volatile ServerSocket server;

  /** IPs already banned this boot (dedupe: a scanner must not grow the rules file per hit). */
  private static final java.util.Set<String> BANNED =
      java.util.concurrent.ConcurrentHashMap.newKeySet();

  private Honeypot() {}

  /** Starts the honeypot listener (no-op when disabled). */
  public static synchronized void start() {
    if (server != null) return;
    var cfg = AuthCoreServer.config.session.honeypot;
    if (!cfg.enabled) return;

    try {
      server = new ServerSocket();
      // Bind address is configurable (default 0.0.0.0 - a honeypot is meant to be reachable);
      // operators who do not want it exposed on every interface can pin it to one address.
      String host = cfg.host == null || cfg.host.isBlank() ? "0.0.0.0" : cfg.host.trim();
      server.bind(new InetSocketAddress(host, cfg.port));

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
    // Dedupe per boot: a port scanner can hit the honeypot thousands of times per second, and
    // every hit used to append another identical rule line + reload the whole rules file.
    if (ip == null) return;
    // Bounded dedupe set: a botnet with millions of source IPs must never grow memory forever.
    // The ip-rules file already persists the bans, so a coarse reset only costs a duplicate line.
    if (BANNED.size() > 4096) BANNED.clear();
    if (!BANNED.add(ip)) return;

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
      // IpRules.load() is a lock-free copy-on-write reload, so this honeypot thread never blocks
      // the server thread's per-join isDenied() check.
      IpRules.load();
    } catch (IOException err) {
      AuthCoreServer.LOGGER.error(false, "Failed to write honeypot ban rule:", err);
    }
  }
}
