package in.potenfyr.authcore.util;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Input/output hardening helpers: log-injection prevention, username validation and path
 * containment.
 *
 * <p>Every value that reaches a log line, a file name or an SQL/path fragment is either produced by
 * vanilla (and therefore bounded) or comes from a client packet, a database row written by an
 * earlier version, a config file or a web panel request. This class makes the unsafe direction
 * impossible regardless of the source:
 *
 * <ul>
 *   <li>{@link #logDetail(String)} strips CR/LF and other control characters and caps the length, so
 *       a crafted username/command/IP can never forge an extra {@code security.log} entry (log
 *       injection) or blow up the file with a multi-megabyte "line".
 *   <li>{@link #isSafeUsername(String)} rejects names vanilla's own client can never produce
 *       (control characters, whitespace, path separators, absurd length). A hacked client can send
 *       any hello name; those reach the database, the chat and staff tooling, where a Unicode
 *       lookalike or an embedded newline is an impersonation/log-forgery vector.
 *   <li>{@link #isSafeDiscordId(String)} accepts only a numeric Discord snowflake, because the id
 *       is embedded in outbound Discord API URLs.
 *   <li>{@link #resolveUnder(Path, String)} refuses to resolve a configured relative name outside
 *       its intended directory ({@code ../} traversal), returning null instead of a path that could
 *       overwrite arbitrary files.
 * </ul>
 *
 * <p>Pure functions over strings/paths: no I/O, no state, no external libraries - trivially testable
 * by the in-mod simulation suite and the host suite.
 */
public final class Sanitizer {

  /** Maximum length of a log detail (keeps one line in a predictable size range). */
  public static final int MAX_LOG_DETAIL = 512;
  /** Maximum length of a log event name. */
  public static final int MAX_LOG_EVENT = 64;
  /** Vanilla Java Edition username limit. */
  public static final int MAX_USERNAME_LENGTH = 16;

  private Sanitizer() {}

  /** A single safe log token: control characters removed, length bounded. */
  public static String logToken(String value) {
    return sanitize(value, 64);
  }

  /** A safe log detail: control characters (including CR/LF) removed, length bounded. */
  public static String logDetail(String value) {
    return sanitize(value, MAX_LOG_DETAIL);
  }

  /** A safe event name: upper-case alphanumerics/underscore only, length bounded. */
  public static String logEvent(String value) {
    String sanitized = sanitize(value, MAX_LOG_EVENT);
    StringBuilder out = new StringBuilder(sanitized.length());
    for (int i = 0; i < sanitized.length(); i++) {
      char character = sanitized.charAt(i);
      out.append(Character.isLetterOrDigit(character) || character == '_' || character == '-'
          ? Character.toUpperCase(character)
          : '_');
    }
    return out.toString();
  }

  /**
   * Strips every ISO control character (this is what defeats log injection: no CR, no LF, no ANSI
   * escape, no NUL) and caps the length. Printable non-ASCII is preserved so legitimate names stay
   * readable.
   */
  public static String sanitize(String value, int maxLength) {
    if (value == null) return "";
    StringBuilder out = new StringBuilder(Math.min(value.length(), Math.max(1, maxLength)));
    for (int i = 0; i < value.length() && out.length() < maxLength; i++) {
      char character = value.charAt(i);
      if (Character.isISOControl(character)) continue;
      // Also drop the Unicode line/paragraph separators: some log viewers treat them as newlines.
      if (character == '\u2028' || character == '\u2029') continue;
      out.append(character);
    }
    return out.toString();
  }

  /** Whether a username is something a real Minecraft Java client could have sent. */
  public static boolean isSafeUsername(String name) {
    if (name == null || name.isEmpty() || name.length() > MAX_USERNAME_LENGTH) return false;
    for (int i = 0; i < name.length(); i++) {
      char character = name.charAt(i);
      boolean allowed =
          (character >= 'a' && character <= 'z')
              || (character >= 'A' && character <= 'Z')
              || (character >= '0' && character <= '9')
              || character == '_'
              || character == '.'
              || character == '-';
      if (!allowed) return false;
    }
    return true;
  }

  /** Numeric Discord snowflake (17-20 digits). */
  public static boolean isSafeDiscordId(String id) {
    if (id == null || id.length() < 15 || id.length() > 21) return false;
    for (int i = 0; i < id.length(); i++) {
      if (!Character.isDigit(id.charAt(i))) return false;
    }
    return true;
  }

  /** A safe file name: no separators, no traversal, printable characters only. */
  public static String safeFileName(String name) {
    String sanitized = sanitize(name, 128).trim();
    sanitized = sanitized.replace('/', '_').replace('\\', '_');
    while (sanitized.contains("..")) sanitized = sanitized.replace("..", "__");
    return sanitized;
  }

  /**
   * Resolves {@code relative} under {@code base} and returns null when the result escapes it
   * (e.g. {@code ../../etc/passwd}) or the input is blank.
   */
  public static Path resolveUnder(Path base, String relative) {
    if (base == null || relative == null || relative.isBlank()) return null;
    try {
      Path path = base.resolve(relative).toAbsolutePath().normalize();
      Path normalizedBase = base.toAbsolutePath().normalize();
      if (!path.startsWith(normalizedBase)) return null;
      return path;
    } catch (RuntimeException invalidPath) {
      return null;
    }
  }

  /** Lower-case form used for name indexes (locale-independent on purpose). */
  public static String lowerName(String name) {
    return name == null ? "" : name.toLowerCase(Locale.ROOT);
  }
}
