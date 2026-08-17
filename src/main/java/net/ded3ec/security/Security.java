package net.ded3ec.security;

import net.ded3ec.models.Config;
import net.ded3ec.models.Messages;
import net.ded3ec.models.User;
import net.ded3ec.util.Logger;
import net.minecraft.server.level.ServerPlayer;
import com.j256.twofactorauth.TimeBasedOneTimePasswordUtil;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.ded3ec.AuthCoreServer;

/**
 * Utility class containing security-related functionality for AuthCoreServer: password complexity
 * validation, TOTP (2FA) support and a lightweight text captcha with automatic expiration.
 */
public class Security {

  private Security() {}

  /** Contains static methods for validating password complexity during registration. */
  public static class Password {

    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
    private static final String DIGITS = "123456789";
    private static final String ALL = UPPER + LOWER + DIGITS;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Password() {}

    /** Generates a cryptographically random password of the given length. */
    public static String generate(int len) {
      if (len < 1 || len > 50) {
        throw new IllegalArgumentException("Password Length must be between 1 and 50");
      }

      StringBuilder sb = new StringBuilder(len);

      // Special handling for very short lengths
      if (len == 1) {
        sb.append(ALL.charAt(RANDOM.nextInt(ALL.length())));
        return sb.toString();
      }
      if (len == 2) {
        sb.append(UPPER.charAt(RANDOM.nextInt(UPPER.length())));
        sb.append(LOWER.charAt(RANDOM.nextInt(LOWER.length())));
        return shuffle(sb.toString());
      }

      // Ensure at least one of each category if len >= 3
      sb.append(UPPER.charAt(RANDOM.nextInt(UPPER.length())));
      sb.append(LOWER.charAt(RANDOM.nextInt(LOWER.length())));
      sb.append(DIGITS.charAt(RANDOM.nextInt(DIGITS.length())));

      // Fill the rest
      for (int i = sb.length(); i < len; i++) {
        sb.append(ALL.charAt(RANDOM.nextInt(ALL.length())));
      }

      // Shuffle result for randomness
      return shuffle(sb.toString());
    }

    /**
     * Validates a password against the server's configured complexity rules.
     *
     * <p>Checks (when enabled in the config): minimum and maximum uppercase letters,
     * lowercase letters, digits, and total length. On a violation, the matching error
     * message is sent to the player and {@code false} is returned.
     *
     * @param player the player attempting to load or change their password
     * @param password the password string to validate
     * @return {@code true} if the password satisfies all enabled complexity rules, {@code false}
     *     otherwise
     */
    public static boolean check(ServerPlayer player, String password) {
      if (password == null) {
        return AuthCoreServer.LOGGER.toUser(
            false, player.connection, AuthCoreServer.messages.promptUserPasswordIsBlank);
      }

      // Count uppercase letters
      int uppercaseCount = (int) password.chars().filter(c -> c >= 'A' && c <= 'Z').count();

      // Count lowercase letters
      int lowercaseCount = (int) password.chars().filter(c -> c >= 'a' && c <= 'z').count();

      // Count digits
      int digitsCount = (int) password.chars().filter(Character::isDigit).count();

      // Total length of the password
      int lengthCount = password.length();

      // Check uppercase letter requirements if the rule is enabled
      if (AuthCoreServer.config.passwordRules.upperCase.enabled
          && (uppercaseCount < AuthCoreServer.config.passwordRules.upperCase.min
              || uppercaseCount > AuthCoreServer.config.passwordRules.upperCase.max))
        return AuthCoreServer.LOGGER.toUser(
            false,
            player.connection,
            AuthCoreServer.messages.promptUserUpperCaseNotPresent,
            AuthCoreServer.config.passwordRules.upperCase.min,
            AuthCoreServer.config.passwordRules.upperCase.max);

      // Check lowercase letter requirements if the rule is enabled
      if (AuthCoreServer.config.passwordRules.lowerCase.enabled
          && (lowercaseCount < AuthCoreServer.config.passwordRules.lowerCase.min
              || lowercaseCount > AuthCoreServer.config.passwordRules.lowerCase.max))
        return AuthCoreServer.LOGGER.toUser(
            false,
            player.connection,
            AuthCoreServer.messages.promptUserLowerCaseNotPresent,
            AuthCoreServer.config.passwordRules.lowerCase.min,
            AuthCoreServer.config.passwordRules.lowerCase.max);

      // Check digit requirements if the rule is enabled
      if (AuthCoreServer.config.passwordRules.digits.enabled
          && (digitsCount < AuthCoreServer.config.passwordRules.digits.min
              || digitsCount > AuthCoreServer.config.passwordRules.digits.max))
        return AuthCoreServer.LOGGER.toUser(
            false,
            player.connection,
            AuthCoreServer.messages.promptUserDigitNotPresent,
            AuthCoreServer.config.passwordRules.digits.min,
            AuthCoreServer.config.passwordRules.digits.max);

      // Check overall password length requirements if the rule is enabled
      if (AuthCoreServer.config.passwordRules.length.enabled
          && (lengthCount < AuthCoreServer.config.passwordRules.length.min
              || lengthCount > AuthCoreServer.config.passwordRules.length.max))
        return AuthCoreServer.LOGGER.toUser(
            false,
            player.connection,
            AuthCoreServer.messages.promptUserPasswordLengthIssue,
            AuthCoreServer.config.passwordRules.length.min,
            AuthCoreServer.config.passwordRules.length.max);

      // All checks passed
      return true;
    }

    // Fisher-Yates shuffle for string
    private static String shuffle(String input) {
      char[] a = input.toCharArray();
      for (int i = a.length - 1; i > 0; i--) {
        int j = RANDOM.nextInt(i + 1);
        char tmp = a[i];
        a[i] = a[j];
        a[j] = tmp;
      }
      return new String(a);
    }
  }

  /** TOTP (RFC 6238) two-factor authentication helpers backed by the two-factor-auth library. */
  public static class TOTPManager {

    private TOTPManager() {}

    /** Verifies a 6-digit TOTP code against the given base32 secret (allows ±1 time window). */
    public static boolean verify(String inputCode, String secret) {
      try {
        if (inputCode == null || secret == null) return false;

        // Normalize input: remove spaces, ensure 6 digits
        String normalized = inputCode.trim();
        if (!normalized.matches("\\d{6}")) return false;

        int code = Integer.parseInt(normalized);
        return TimeBasedOneTimePasswordUtil.validateCurrentNumber(secret, code, 1);
      } catch (Exception e) {
        return false;
      }
    }

    /** Builds the otpauth:// URL used to display the QR code. */
    public static String getQrUrl(String username, String secret) {
      return "https://api.qrserver.com/v1/create-qr-code/?size=200x200&data="
          + TimeBasedOneTimePasswordUtil.generateOtpAuthUrl("AuthCore:" + username, secret);
    }

    /** Generates a new random base32 TOTP secret. */
    public static String getSecret() {
      return TimeBasedOneTimePasswordUtil.generateBase32Secret(64);
    }
  }

/**
   * Backup recovery codes: random one-time codes used to regain access to an account.
   */
  public static class RecoveryCodes {

    private static final String CHARSET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private RecoveryCodes() {}

    /**
     * Generates {@code count} recovery codes formatted as "XXXXX-XXXXX".
     *
     * @param count number of codes
     * @return comma-separated codes
     */
    public static String generate(int count) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < count; i++) {
        if (i > 0) sb.append(',');
        sb.append(generateCode(10));
      }
      return sb.toString();
    }

    private static String generateCode(int length) {
      StringBuilder sb = new StringBuilder(length + 1);
      for (int i = 0; i < length; i++) {
        if (i == 5) sb.append('-');
        sb.append(CHARSET.charAt(RANDOM.nextInt(CHARSET.length())));
      }
      return sb.toString();
    }

    /**
     * Constant-time verification of a recovery code; a valid code is consumed (single-use)
     * and persisted. Used as the fallback second factor when the TOTP code is lost.
     */
    public static boolean verifyAndConsume(net.ded3ec.models.User user, String input) {
      if (user == null || user.recoveryCodes == null || user.recoveryCodes.isBlank() || input == null)
        return false;
      String normalized = input.trim().toUpperCase(java.util.Locale.ROOT).replace("-", "");
      String[] storedCodes = user.recoveryCodes.split(",");
      for (int i = 0; i < storedCodes.length; i++) {
        String stored = storedCodes[i].trim().toUpperCase(java.util.Locale.ROOT).replace("-", "");
        if (!stored.isEmpty() && constantTimeEquals(stored, normalized)) {
          java.util.List<String> remaining = new java.util.ArrayList<>();
          for (int j = 0; j < storedCodes.length; j++) {
            String other = storedCodes[j].trim();
            if (j == i || other.isEmpty()) continue;
            remaining.add(other);
          }
          user.recoveryCodes = String.join(",", remaining);
          user.update("Recovery code consumed");
          net.ded3ec.security.SecurityLog.log("RECOVERY_CODE_USED", user.username + " | " + (remaining.size()) + " codes left");
          return true;
        }
      }
      return false;
    }
  }

  /**
   * Email one-time-password second factor. Codes are 6 digits, hashed in memory with a
   * 10 minute TTL and single-use. Requires the SMTP config to be enabled.
   */
  public static class EmailOtp {

    private static final long TTL_MS = 10 * 60 * 1000L;
    private static final java.util.concurrent.ConcurrentHashMap<java.util.UUID, long[]> STORE =
        new java.util.concurrent.ConcurrentHashMap<>(); // uuid -> {expiryMs, sha256-hash}
    private static final int MAX_ATTEMPTS = 5;

    private EmailOtp() {}

    /** Generates, stores and returns a 6-digit code for the player. */
    public static String issue(java.util.UUID uuid) {
      String code = String.format("%06d", new java.security.SecureRandom().nextInt(1_000_000));
      long hash = Long.parseLong(sha256(code).substring(0, 15), 16);
      STORE.put(uuid, new long[] {System.currentTimeMillis() + TTL_MS, hash, 0});
      return code;
    }

    /** Verifies a submitted code (constant-time, single-use, attempt-limited). */
    public static boolean verify(java.util.UUID uuid, String input) {
      if (uuid == null || input == null) return false;
      long[] entry = STORE.get(uuid);
      if (entry == null) return false;
      if (System.currentTimeMillis() > entry[0]) {
        STORE.remove(uuid);
        return false;
      }
      if (entry[2] >= MAX_ATTEMPTS) {
        STORE.remove(uuid);
        return false;
      }
      long expected = Long.parseLong(sha256(input.trim()).substring(0, 15), 16);
      boolean ok = (entry[1] ^ expected) == 0;
      entry[2]++;
      if (ok) STORE.remove(uuid);
      return ok;
    }

    /** Whether a code is pending for the player (sent but not yet consumed). */
    public static boolean isPending(java.util.UUID uuid) {
      long[] entry = STORE.get(uuid);
      if (entry == null) return false;
      if (System.currentTimeMillis() > entry[0]) {
        STORE.remove(uuid);
        return false;
      }
      return true;
    }

    public static void clear(java.util.UUID uuid) {
      STORE.remove(uuid);
    }

    private static String sha256(String value) {
      try {
        byte[] digest =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
      } catch (Exception e) {
        return "";
      }
    }
  }

  /**
   * Network-based device fingerprint: a SHA-256 hash of the login origin (IP + country). Not a
   * hardware fingerprint (impossible server-side) but a strong "same network/device" signal used
   * to detect account sharing and trading.
   */
  public static class DeviceFingerprint {

    private DeviceFingerprint() {}

    /** Computes the fingerprint for an IP + country combination. */
    public static String compute(String ip, String country) {
      try {
        String input = (ip == null ? "" : ip) + "|" + (country == null ? "" : country);
        byte[] digest =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString().substring(0, 16);
      } catch (Exception e) {
        return null;
      }
    }
  }

  /**
   * Login risk scoring (0-100). Signals: new IP, new country, weak password hashing, missing
   * TOTP, recently created account. Used for admin alerts and login history.
   */
  public static class RiskScore {
    private RiskScore() {}

    /**
     * Computes a risk score for a login attempt.
     *
     * @param user the user account
     * @param currentIp the IP the login came from
     * @param currentCountry the GeoIP country (may be null)
     * @return score from 0 (normal) to 100 (high risk)
     */
    public static int compute(net.ded3ec.models.User user, String currentIp, String currentCountry) {
      if (user == null) return 100;

      int score = 0;

      // New IP for this account
      if (user.lastLoginIp != null
          && !user.lastLoginIp.isEmpty()
          && !user.lastLoginIp.equals(currentIp)) score += 20;

      // New country for this account (strong signal of account sharing / theft)
      if (currentCountry != null
          && user.lastLoginCountry != null
          && !user.lastLoginCountry.isEmpty()
          && !user.lastLoginCountry.equalsIgnoreCase(currentCountry)) score += 30;

      // Weak password hashing algorithm
      String algo = user.passwordEncryption;
      if (algo != null) {
        String a = algo.toLowerCase(Locale.ROOT);
        if (a.equals("md5") || a.equals("sha-256") || a.equals("sha-512")) score += 10;
      }

      // No 2FA on the account
      if (AuthCoreServer.config != null
          && AuthCoreServer.config.session.authentication.allowTOTPSupport
          && user.authSecret == null) score += 5;

      // Very recently created account
      if (user.registeredAtMs > 0
          && (System.currentTimeMillis() - user.registeredAtMs) < 24 * 60 * 60 * 1000L) score += 5;

      return Math.min(score, 100);
    }
  }

  /**
   * Email-based password recovery: generates short-lived codes for accounts with a registered
   * email address. Codes are single-use and expire after 15 minutes.
   */
  public static class EmailRecovery {

    private static final long CODE_TTL_MS = 15 * 60 * 1000L;
    private static final long COOLDOWN_MS = 60 * 1000L;
    private static final int MAX_ATTEMPTS = 5;
    private static final int MAX_PENDING = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Maps email (lowercase) -> code + expiry. */
    private static final Map<String, CodeEntry> pending = new ConcurrentHashMap<>();

    private EmailRecovery() {}

    /**
     * Generates a recovery code for the given email.
     *
     * @return the 6-digit code, or {@code null} if the code could not be stored
     */
    public static String generateCode(String email) {
      if (email == null) return null;

      purgeExpired();
      if (pending.size() >= MAX_PENDING) pending.clear();

      // Anti-spam: at most one code per email per cooldown window
      String key = normalize(email);
      long now = System.currentTimeMillis();
      CodeEntry existing = pending.get(key);
      if (existing != null && now - existing.createdAtMs < COOLDOWN_MS) return null;

      String code = String.format("%06d", RANDOM.nextInt(1_000_000));
      pending.put(key, new CodeEntry(code));
      return code;
    }

    /**
     * Validates and consumes a recovery code.
     *
     * @return {@code true} if the code was correct and not expired
     */
    public static boolean verifyCode(String email, String code) {
      if (email == null || code == null) return false;

      String key = normalize(email);
      CodeEntry entry = pending.get(key);
      if (entry == null || entry.isExpired()) {
        pending.remove(key);
        return false;
      }

      // Anti-brute-force: a code may only be tried a few times before it is revoked
      if (entry.attempts >= MAX_ATTEMPTS) {
        pending.remove(key);
        return false;
      }

      entry.attempts++;
      boolean matches =
          java.security.MessageDigest.isEqual(
              entry.code.getBytes(java.nio.charset.StandardCharsets.UTF_8),
              code.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8));

      if (matches) pending.remove(key);
      return matches;
    }

    /** Basic email format check. */
    public static boolean isValidEmail(String email) {
      return email != null
          && email.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
          && email.length() <= 254;
    }

    private static String normalize(String email) {
      return email.trim().toLowerCase(Locale.ROOT);
    }

    /** Removes expired entries to bound memory usage. */
    private static void purgeExpired() {
      long now = System.currentTimeMillis();
      pending.values().removeIf(entry -> entry.expiresAtMs < now);
    }

    private static final class CodeEntry {
      final String code;
      final long expiresAtMs;
      final long createdAtMs;
      int attempts;

      CodeEntry(String code) {
        this.code = code;
        this.createdAtMs = System.currentTimeMillis();
        this.expiresAtMs = createdAtMs + CODE_TTL_MS;
      }

      boolean isExpired() {
        return System.currentTimeMillis() > expiresAtMs;
      }
    }
  }

  private static boolean constantTimeEquals(String a, String b) {
    if (a == null || b == null || a.length() != b.length()) return false;
    int diff = 0;
    for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
    return diff == 0;
  }
}