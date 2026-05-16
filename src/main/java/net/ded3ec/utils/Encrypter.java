package net.ded3ec.utils;

import com.password4j.*;
import com.password4j.types.Hmac;

import java.util.Arrays;

public class Encrypter {

  /**
   * Salt value for hashing algorithm. Generated salt used across all hashing operations for
   * security.
   */
  private static final String salt = Arrays.toString(SaltGenerator.generate(32));

  /**
   * Argon2 based instance to hash and verify. Configured with specific parameters for Argon2
   * hashing.
   */
  private static final Argon2Function argon2 =
      Argon2Function.getInstance(65536, 3, 1, 16, com.password4j.types.Argon2.ID);

  /** Bcrypt based instance to hash and verify. Uses Bcrypt with cost factor 12. */
  private static final BcryptFunction bcrypt =
      BcryptFunction.getInstance(com.password4j.types.Bcrypt.B, 12);

  /** Scrypt based instance to hash and verify. Configured with N=16384, r=8, p=1, dkLen=32. */
  private static final ScryptFunction scrypt = ScryptFunction.getInstance(16384, 8, 1, 32);

  /**
   * PBKDF based instance to hash and verify. Uses PBKDF2 with SHA-256, 10000 iterations, 32-byte
   * key.
   */
  private static final PBKDF2Function pbkdf2 = PBKDF2Function.getInstance(Hmac.SHA256, 10000, 32);

  /** SHA256 based instance to hash and verify. Uses SHA-256 message digest. */
  private static final MessageDigestFunction sha256 = MessageDigestFunction.getInstance("SHA-256");

  /** SHA512 based instance to hash and verify. Uses SHA-512 message digest. */
  private static final MessageDigestFunction sha512 = MessageDigestFunction.getInstance("SHA-512");

  /**
   * MD5 based instance to hash and verify. Uses MD5 message digest (not recommended for security).
   */
  private static final MessageDigestFunction md5 = MessageDigestFunction.getInstance("MD5");

  /**
   * Hashing the plain text as per HashManager algorithm. Selects the hashing algorithm based on the
   * provided string and hashes the password.
   *
   * @param algorithm the hashing algorithm name (e.g., "argon2", "bcrypt")
   * @param password the plain text password to hash
   * @return the hashed password string or null if invalid
   */
  public static String hash(String algorithm, String password) {
    if (algorithm == null) return null;
    else if (password == null) return null;

    return switch (algorithm.toLowerCase()) {
      case "argon2" -> argon2.hash(password, Encrypter.salt).getResult();
      case "bcrypt" -> bcrypt.hash(password, Encrypter.salt).getResult();
      case "pbkdf2" -> pbkdf2.hash(password, Encrypter.salt).getResult();
      case "sha-256" -> sha256.hash(password, Encrypter.salt).getResult();
      case "sha-512" -> sha512.hash(password, Encrypter.salt).getResult();
      case "md5" -> md5.hash(password, Encrypter.salt).getResult();
      case "scrypt" -> scrypt.hash(password, Encrypter.salt).getResult();
      default -> null;
    };
  }

  /**
   * Verify the plain text with stored hash as per HashManager algorithm. Checks if the plain
   * password matches the stored hash using the specified algorithm.
   *
   * @param password the plain text password
   * @param storedHash the stored hashed password
   * @param algorithm the hashing algorithm name
   * @return true if the password matches, false otherwise
   */
  public static boolean verify(String password, String storedHash, String algorithm) {
    if (algorithm == null) return false;
    else if (password == null) return false;

    return switch (algorithm.toLowerCase()) {
      case "argon2" -> argon2.check(password, storedHash);
      case "bcrypt" -> bcrypt.check(password, storedHash);
      case "pbkdf2" -> pbkdf2.check(password, storedHash);
      case "sha-256" -> sha256.check(password, storedHash);
      case "sha-512" -> sha512.check(password, storedHash);
      case "md5" -> md5.check(password, storedHash);
      case "scrypt" -> scrypt.check(password, storedHash);
      default -> false;
    };
  }
}
