package in.potenfyr.authcore.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import in.potenfyr.authcore.AuthCoreServer;
import in.potenfyr.authcore.util.Sanitizer;

/**
 * Append-only security event log written to {@code config/authcore/security.log}. Rotates the
 * file automatically when it exceeds the configured size (keeps up to 3 rotated files).
 *
 * <p>Every field is sanitised through {@link Sanitizer}: a username, command string, IP or Discord
 * id that contains CR/LF (or any other control character) can no longer forge extra log lines or
 * inject terminal escapes, and a multi-megabyte value cannot turn one event into a giant line. The
 * configured file name is resolved under the config directory, so a compromised or mistyped config
 * cannot make the logger overwrite an arbitrary file.
 */
public final class SecurityLog {

  private SecurityLog() {}

  /** Appends a timestamped security event to the log file (disabled when no file is configured). */
  public static synchronized void log(String event, String detail) {
    if (AuthCoreServer.config == null
        || AuthCoreServer.config.session.security.logFile == null
        || AuthCoreServer.config.session.security.logFile.isBlank()) return;

    try {
      Path path =
          Sanitizer.resolveUnder(
              AuthCoreServer.configPath, AuthCoreServer.config.session.security.logFile);
      if (path == null) {
        AuthCoreServer.LOGGER.debug(
            false, "Security log file escapes the config directory - entry not written.");
        return;
      }

      rotateIfNeeded(path);

      String line =
          Instant.now()
              + " | "
              + Sanitizer.logEvent(event)
              + " | "
              + Sanitizer.logDetail(detail)
              + System.lineSeparator();
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
