package net.ded3ec.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import net.ded3ec.AuthCoreServer;

/**
 * Append-only security event log written to {@code config/authcore/security.log}. Rotates the
 * file automatically when it exceeds the configured size (keeps up to 3 rotated files).
 */
public final class SecurityLog {

  private SecurityLog() {}

  /** Appends a timestamped security event to the log file (disabled when no file is configured). */
  public static synchronized void log(String event, String detail) {
    if (AuthCoreServer.config == null
        || AuthCoreServer.config.session.security.logFile == null
        || AuthCoreServer.config.session.security.logFile.isBlank()) return;

    try {
      Path path = AuthCoreServer.configPath.resolve(AuthCoreServer.config.session.security.logFile);

      rotateIfNeeded(path);

      String line = Instant.now() + " | " + event + " | " + detail + System.lineSeparator();
      Files.createDirectories(AuthCoreServer.configPath);
      Files.write(path, line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException err) {
      AuthCoreServer.LOGGER.debug(null, "Failed to write security log:", err);
    }
  }

  /** Rotates the log file when it exceeds the configured maximum size. */
  private static void rotateIfNeeded(Path path) throws IOException {
    long maxBytes = AuthCoreServer.config.session.security.logMaxBytes;
    if (maxBytes <= 0 || !Files.exists(path) || Files.size(path) < maxBytes) return;

    for (int i = 3; i >= 1; i--) {
      Path from = path.resolveSibling(path.getFileName() + "." + i);
      Path to = path.resolveSibling(path.getFileName() + "." + (i + 1));
      if (Files.exists(from)) {
        Files.deleteIfExists(to);
        Files.move(from, to);
      }
    }
    Files.move(path, path.resolveSibling(path.getFileName() + ".1"));
    AuthCoreServer.LOGGER.info(true, "Security log rotated ({} bytes)", maxBytes);
  }
}
