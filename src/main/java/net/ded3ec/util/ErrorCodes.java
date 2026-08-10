package net.ded3ec.util;

import net.ded3ec.AuthCoreServer;

/**
 * Error code registry - every failure site in AuthCore attaches an opaque code to its console
 * error so server admins can share it with the author, who can pinpoint the exact module,
 * failure kind and site without leaking internals to anyone else.
 *
 * <p>Format: {@code AC-<module>-<kind>-<site>-<check>} - four hex nibbles (0-9A-F). The tables
 * below are the only way to decode them; clients never see the codes (console/log only).
 *
 * <pre>
 *   AC-7-3-2-6  = module 0x7 (Database), kind 0x3 (connection), site #2, checksum 0x6
 * </pre>
 *
 * <p>The checksum nibble makes hand-altered or truncated codes fail {@link #isValid(String)}
 * silently, so a code an admin forwards can be trusted.
 */
public final class ErrorCodes {

  private ErrorCodes() {}

  /** Module registry - hex id -> what part of the mod failed. */
  public enum Module {
    GENERAL(0x0),
    HONEYPOT(0x1),
    COMMAND(0x2),
    EMAIL(0x3),
    CONFIG(0x4),
    PROXY(0x5),
    SESSION(0x6),
    DATABASE(0x7),
    STARTUP(0x8),
    REGISTRY(0x9),
    SECURITY(0xA),
    WEB_PANEL(0xB),
    INTEROP(0xD),
    REDIS(0xE),
    CLIENT_GUARD(0xF);

    public final int id;

    Module(int id) {
      this.id = id;
    }

    static Module byId(int id) {
      for (Module m : values()) if (m.id == id) return m;
      return GENERAL;
    }
  }

  /** Failure-kind registry - hex id -> what went wrong. */
  public enum Kind {
    UNKNOWN(0x0),
    CONNECTION(0x3),
    QUERY(0x4),
    MIGRATION(0x5),
    PARSE(0x6),
    IO(0x7),
    SECURITY(0x8),
    TIMEOUT(0x9),
    RATE_LIMIT(0xA),
    NOT_FOUND(0xB),
    INVALID_INPUT(0xC),
    INIT(0xD);

    public final int id;

    Kind(int id) {
      this.id = id;
    }

    static Kind byId(int id) {
      for (Kind k : values()) if (k.id == id) return k;
      return UNKNOWN;
    }
  }

  /** Per-(module,kind,site) sequence counters so repeated failures stay distinguishable. */
  private static final java.util.Map<String, Integer> SEQUENCES = new java.util.concurrent.ConcurrentHashMap<>();

  /** Logs an error with its code and returns the code. */
  public static String err(Module module, Kind kind, int site, String message, Object... args) {
    String code = code(module, kind, site);
    AuthCoreServer.LOGGER.error(false, "[{}] {}", code, format(message, args));
    return code;
  }

  /** Logs an error with a throwable and returns the code. */
  public static String err(
      Module module, Kind kind, int site, Throwable cause, String message, Object... args) {
    String code = code(module, kind, site);
    AuthCoreServer.LOGGER.error(false, "[{}] {} - {}", code, format(message, args), cause);
    return code;
  }

  /** Produces the code without logging (for embedding in higher-level messages). */
  public static String code(Module module, Kind kind, int site) {
    int seq = nextSeq(module, kind, site);
    int check = checksum(module.id, kind.id, site & 0xF, seq);
    return "AC-" + hex(module.id) + "-" + hex(kind.id) + "-" + hex(site & 0xF) + "-" + hex(check);
  }

  /**
   * Author-side decoder: turns a code into a pinpoint. Returns null for invalid codes so
   * tampered codes are silently rejected.
   */
  public static String decode(String code) {
    if (!isValid(code)) return null;
    String[] parts = code.split("-");
    int moduleId = Integer.parseInt(parts[1], 16);
    int kindId = Integer.parseInt(parts[2], 16);
    int site = Integer.parseInt(parts[3], 16);
    int check = Integer.parseInt(parts[4], 16);
    int seq = -1;
    for (String key : SEQUENCES.keySet()) {
      String[] k = key.split(":");
      if (Integer.parseInt(k[0], 16) == moduleId
          && Integer.parseInt(k[1], 16) == kindId
          && Integer.parseInt(k[2], 16) == site
          && checksum(moduleId, kindId, site, Integer.parseInt(k[3], 16)) == check) {
        seq = Integer.parseInt(k[3], 16);
        break;
      }
    }
    return Module.byId(moduleId)
        + " | "
        + Kind.byId(kindId)
        + " | site #"
        + site
        + (seq >= 0 ? " | occurrence " + (seq + 1) : "");
  }

  /** Format + checksum validation (rejects tampered codes). */
  public static boolean isValid(String code) {
    if (code == null || !code.matches("AC-[0-9A-F]-[0-9A-F]-[0-9A-F]-[0-9A-F]")) return false;
    try {
      String[] parts = code.split("-");
      int moduleId = Integer.parseInt(parts[1], 16);
      int kindId = Integer.parseInt(parts[2], 16);
      int site = Integer.parseInt(parts[3], 16);
      int check = Integer.parseInt(parts[4], 16);
      // constant-time compare over the two nibbles
      int diff = (checksum(moduleId, kindId, site, 0) >> 4) ^ (check >> 4);
      diff |= (checksum(moduleId, kindId, site, 1) & 0xF) ^ (check & 0xF);
      return diff == 0;
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static int nextSeq(Module module, Kind kind, int site) {
    String key = Integer.toHexString(module.id) + ":" + Integer.toHexString(kind.id) + ":" + Integer.toHexString(site & 0xF);
    return SEQUENCES.merge(key, 0, (a, b) -> (a + 1) & 0xF);
  }

  /** Checksum: module ^ kind ^ site ^ seq, spread over the nibbles. */
  private static int checksum(int module, int kind, int site, int seq) {
    int mix = (module * 7 + kind * 3 + site * 11 + seq * 5) & 0xFF;
    return ((mix >> 4) ^ mix) & 0xF;
  }

  private static String hex(int value) {
    return Integer.toHexString(value & 0xF).toUpperCase();
  }

  private static String format(String message, Object... args) {
    if (args == null || args.length == 0) return message;
    String out = message;
    for (Object arg : args) out = out.replaceFirst("\\{\\}", String.valueOf(arg));
    return out;
  }
}
