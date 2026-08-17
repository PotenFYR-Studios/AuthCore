package net.ded3ec.security;

import net.ded3ec.models.User;
import net.ded3ec.util.Logger;

import com.password4j.Argon2Function;
import com.password4j.BcryptFunction;
import com.password4j.MessageDigestFunction;
import com.password4j.ScryptFunction;
import com.password4j.types.Argon2;
import com.password4j.types.Bcrypt;
import com.password4j.types.Hmac;

import java.util.Locale;
import net.ded3ec.AuthCoreServer;

/**
 * Password hashing and verification using the Password4j library.
 *
 * <p>Every hash is generated with a fresh, cryptographically random per-user salt that is embedded
 * inside the resulting hash string (self-contained format), so verification works across server
 * restarts without storing the salt separately. All algorithms listed in the configuration are
 * supported, including memory-hard Argon2id (default) and BCrypt.
 */
public final class Encrypter {

  /** Argon2id instance (memory-hard, GPU-resistant). 64 MiB memory, 3 iterations, 16-byte hash. */
  private static final Argon2Function ARGON2 =
      Argon2Function.getInstance(65536, 3, 1, 16, Argon2.ID);

  /** BCrypt instance with cost factor 12. */
  private static final BcryptFunction BCRYPT = BcryptFunction.getInstance(Bcrypt.B, 12);

  /** Scrypt instance: N=16384, r=8, p=1, dkLen=32. */
  private static final ScryptFunction SCRYPT = ScryptFunction.getInstance(16384, 8, 1, 32);

  /** SHA-256 message digest (fast - only for legacy compatibility, not for new passwords). */
  private static final MessageDigestFunction SHA256 = MessageDigestFunction.getInstance("SHA-256");

  /** SHA-512 message digest (fast - only for legacy compatibility, not for new passwords). */
  private static final MessageDigestFunction SHA512 = MessageDigestFunction.getInstance("SHA-512");

  /** PBKDF2 with HMAC-SHA256, 100_000 iterations, 32-byte key (self-contained format). */
  private static final int PBKDF2_ITERATIONS = 100_000;

  /**
   * Hashes a plain-text password using the given algorithm. A random per-user salt is generated
   * automatically and embedded into the returned hash string.
   *
   * @param algorithm the hashing algorithm name (e.g. "argon2", "bcrypt")
   * @param password the plain-text password to hash
   * @return the self-contained hashed password string, or {@code null} if the algorithm is
   *     unknown or arguments are invalid
   */
  public static String hash(String algorithm, String password) {
    if (algorithm == null || password == null || password.isEmpty()) return null;

    try {
      String algo = algorithm.toLowerCase(Locale.ROOT);
      if ("pbkdf2".equals(algo)) return pbkdf2Hash(password);

      String result =
          switch (algo) {
            // Argon2 gets an explicit cryptographically-random 16-byte salt (the library's
            // default is 64 bytes; 16 is the spec-conformant size and keeps every hash
            // self-contained and verifiable across restarts).
            case "argon2" -> ARGON2.hash(password, argon2Salt()).getResult();
            case "bcrypt" -> BCRYPT.hash(password).getResult();
            case "scrypt" -> SCRYPT.hash(password).getResult();
            case "sha-256" -> SHA256.hash(password).getResult();
            case "sha-512" -> SHA512.hash(password).getResult();
            case "md5" -> SHA256.hash(password).getResult();
            default -> null;
          };

      // Fallback: never store a password un-hashed or unusable - fall back to Argon2id
      if (result == null) {
        AuthCoreServer.LOGGER.warn(
            false,
            "Unknown password-hash-algorithm '{}' - falling back to argon2 for this hash.",
            algorithm);
        result = ARGON2.hash(password, argon2Salt()).getResult();
      }
      return result;
    } catch (Exception err) {
      AuthCoreServer.LOGGER.error(null, "Failed to hash password with '{}':", algorithm, err);
      // Last-resort fallback so registration never fails silently
      try {
        return ARGON2.hash(password, argon2Salt()).getResult();
      } catch (Exception fallbackErr) {
        return null;
      }
    }
  }

  /** Fresh cryptographically-random 16-byte salt for Argon2 (base64 - embedded in the hash). */
  private static String argon2Salt() {
    return java.util.Base64.getEncoder().encodeToString(
        com.password4j.SaltGenerator.generate(16));
  }

  /**
   * Verifies a plain-text password against a stored self-contained hash string.
   *
   * @param password the plain-text password to check
   * @param storedHash the stored hashed password (salt is embedded in the string)
   * @param algorithm the hashing algorithm name that was used when the hash was created
   * @return {@code true} if the password matches, {@code false} otherwise
   */
  public static boolean verify(String password, String storedHash, String algorithm) {
    if (storedHash == null || password == null || storedHash.isEmpty()) return false;

    // Try the declared algorithm first (self-contained hashes).
    if (tryVerify(password, storedHash, algorithm)) return true;

    // Legacy/foreign rows (accounts migrated from older mod versions or AuthMe-style plugins,
    // or a mis-declared algorithm) trip password4j's parser - e.g. BadParametersException
    // "Bad salt length" / "Invalid salt version" when a bcrypt check is run against a hash
    // whose embedded salt does not match the expected size. Falling back through the supported
    // algorithms lets those accounts still authenticate instead of erroring in the console.
    for (String alt :
        new String[] {"argon2", "bcrypt", "scrypt", "pbkdf2", "sha-512", "sha-256", "md5"}) {
      if (algorithm != null && alt.equalsIgnoreCase(algorithm)) continue;
      if (tryVerify(password, storedHash, alt)) return true;
    }
    return false;
  }

  /** Single-algorithm verification that never throws (parse/salt errors are logged at debug). */
  private static boolean tryVerify(String password, String storedHash, String algorithm) {
    if (algorithm == null || storedHash == null || password == null || storedHash.isEmpty())
      return false;

    try {
      String algo = algorithm.toLowerCase(Locale.ROOT);

      // AuthMe-style legacy hashes (imported accounts): "$SHA$<salt>$<sha256(sha256(pw)+salt)>"
      if (algo.startsWith("authme") || storedHash.startsWith("$SHA$"))
        return authMeShaVerify(password, storedHash);

      if ("pbkdf2".equals(algo)) return pbkdf2Verify(password, storedHash);

      return switch (algo) {
        case "argon2" -> ARGON2.check(password, storedHash);
        case "bcrypt" -> BCRYPT.check(password, storedHash);
        case "scrypt" -> SCRYPT.check(password, storedHash);
        case "sha-256" -> SHA256.check(password, storedHash);
        case "sha-512" -> SHA512.check(password, storedHash);
        case "md5" -> SHA256.check(password, storedHash);
        default -> false;
      };
    } catch (Exception err) {
      return AuthCoreServer.LOGGER.debug(
          false,
          "Stored hash '{}' is not valid for algorithm '{}' - trying other algorithms:",
          summarize(storedHash),
          algorithm,
          err);
    }
  }

  /**
   * Verifies an AuthMe {@code $SHA$<salt>$<hash>} hash (sha256(sha256(password) + salt),
   * hex-encoded). Used for accounts imported from AuthMe-style plugins; the hash-upgrade on
   * the next successful login re-hashes them to the configured algorithm.
   */
  private static boolean authMeShaVerify(String password, String storedHash) {
    if (password == null || storedHash == null) return false;
    String[] parts = storedHash.split("\\$");
    if (parts.length != 4 || !"SHA".equals(parts[1])) return false;
    String salt = parts[2];
    if (salt == null || salt.isEmpty()) return false;

    try {
      java.security.MessageDigest digest =
          java.security.MessageDigest.getInstance("SHA-256");
      String first = toHex(digest.digest(password.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      byte[] second = digest.digest((first + salt).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return java.security.MessageDigest.isEqual(parts[3].getBytes(java.nio.charset.StandardCharsets.US_ASCII), toHex(second).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    } catch (Exception err) {
      return false;
    }
  }

  private static String toHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes)
      sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
    return sb.toString();
  }

  /**
   * Whether a hash algorithm is considered weak (legacy): md5 and the plain SHA digests.
   * Accounts hashed with these are flagged by whois and transparently re-hashed with the
   * configured algorithm on their next successful login.
   */
  public static boolean isWeakAlgorithm(String algorithm) {
    if (algorithm == null) return true;
    String a = algorithm.toLowerCase(Locale.ROOT);
    return a.equals("md5") || a.equals("sha-256") || a.equals("sha-512");
  }

  /** Infers the AuthCore algorithm from an imported (AuthMe-style) hash string. */
  public static String inferImportedAlgorithm(String storedHash) {
    if (storedHash == null || storedHash.isBlank()) return null;
    String h = storedHash;
    if (h.startsWith("$SHA$")) return "authme-sha";
    if (h.startsWith("$2a$") || h.startsWith("$2b$") || h.startsWith("$2y$")) return "bcrypt";
    if (h.startsWith("$argon2")) return "argon2";
    if (h.startsWith("$pbkdf2")) return "pbkdf2";
    if (h.startsWith("$scrypt")) return "scrypt";
    if (h.matches("(?i)[0-9a-f]{64}")) return "sha-256";
    if (h.matches("(?i)[0-9a-f]{128}")) return "sha-512";
    if (h.matches("(?i)[0-9a-f]{32}")) return "md5";
    return null;
  }

  /** Truncated preview of a stored hash for log lines (never log full password hashes). */
  private static String summarize(String storedHash) {
    if (storedHash == null) return "null";
    return storedHash.length() <= 24 ? storedHash : storedHash.substring(0, 24) + "...";
  }

  // ------------------------------------------------------------------
  // PBKDF2 (self-contained "$pbkdf2-sha256$<iter>$<saltB64>$<hashB64>" format,
  // implemented on the JDK SecretKeyFactory - deterministic and fast).
  // ------------------------------------------------------------------

  private static String pbkdf2Hash(String password) {
    try {
      byte[] salt = com.password4j.SaltGenerator.generate(16);
      byte[] derived = pbkdf2Derive(password, salt, PBKDF2_ITERATIONS);
      return "$pbkdf2-sha256$"
          + PBKDF2_ITERATIONS
          + "$"
          + java.util.Base64.getEncoder().encodeToString(salt)
          + "$"
          + java.util.Base64.getEncoder().encodeToString(derived);
    } catch (Exception err) {
      return AuthCoreServer.LOGGER.error(null, "PBKDF2 hashing failed:", err);
    }
  }

  private static boolean pbkdf2Verify(String password, String storedHash) {
    try {
      String[] parts = storedHash.split("\\$");
      if (parts.length != 5 || !"pbkdf2-sha256".equals(parts[1])) return false;

      int iterations = Integer.parseInt(parts[2]);
      if (iterations <= 0 || iterations > 10_000_000) return false;

      byte[] salt = java.util.Base64.getDecoder().decode(parts[3]);
      byte[] expected = java.util.Base64.getDecoder().decode(parts[4]);
      byte[] actual = pbkdf2Derive(password, salt, iterations);

      return java.security.MessageDigest.isEqual(expected, actual);
    } catch (Exception err) {
      return false;
    }
  }

  private static byte[] pbkdf2Derive(String password, byte[] salt, int iterations)
      throws Exception {
    javax.crypto.SecretKeyFactory factory =
        javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
    javax.crypto.spec.PBEKeySpec spec =
        new javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, iterations, 256);
    return factory.generateSecret(spec).getEncoded();
  }

  private Encrypter() {}
}
