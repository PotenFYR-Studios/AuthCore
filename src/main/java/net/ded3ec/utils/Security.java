package net.ded3ec.utils;

import com.j256.twofactorauth.TimeBasedOneTimePasswordUtil;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.wf.captcha.SpecCaptcha;
import com.wf.captcha.base.Captcha;
import net.ded3ec.AuthCoreServer;
import net.minecraft.server.network.ServerPlayerEntity;

import javax.imageio.ImageIO;

/**
 * Utility class containing security-related functionality for AuthCoreServer. Currently, provides
 * password complexity validation according to configurable server rules.
 */
public class Security {

  /**
   * Contains static methods for validating password complexity during registration or password
   * changes.
   */
  public static class Password {

    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
    private static final String DIGITS = "123456789";
    private static final String ALL = UPPER + LOWER + DIGITS;
    private static final SecureRandom RANDOM = new SecureRandom();

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
     * Validates a password against the server's configured password complexity rules.
     *
     * <p>The method checks the following criteria (if enabled in the configuration):
     *
     * <ul>
     *   <li>Minimum and maximum number of uppercase letters
     *   <li>Minimum and maximum number of lowercase letters
     *   <li>Minimum and maximum number of digits
     *   <li>Minimum and maximum total password length
     * </ul>
     *
     * <p>If any enabled rule is violated, an appropriate error message is sent to the player via
     * {@link Logger#toUser} and {@code false} is returned. If all checks pass, {@code true} is
     * returned.
     *
     * @param player the player attempting to load or change their password
     * @param password the password string to validate
     * @return {@code true} if the password satisfies all enabled complexity rules, {@code false}
     *     otherwise
     */
    public static boolean check(ServerPlayerEntity player, String password) {

      // Count uppercase letters using regex [A-Z]
      int uppercaseCount = (int) password.chars().filter(c -> c >= 'A' && c <= 'Z').count();

      // Count lowercase letters using regex [a-z]
      int lowercaseCount = (int) password.chars().filter(c -> c >= 'a' && c <= 'z').count();

      // Count digits using regex \d
      int digitsCount = (int) password.chars().filter(Character::isDigit).count();

      // Total length of the password
      int lengthCount = password.length();

      // Check uppercase letter requirements if the rule is enabled
      if (AuthCoreServer.config.passwordRules.upperCase.enabled
          && (uppercaseCount < AuthCoreServer.config.passwordRules.upperCase.min
              || uppercaseCount > AuthCoreServer.config.passwordRules.upperCase.max))
        return AuthCoreServer.LOGGER.toUser(
            false,
            player.networkHandler,
            AuthCoreServer.messages.promptUserUpperCaseNotPresent,
            AuthCoreServer.config.passwordRules.upperCase.min,
            AuthCoreServer.config.passwordRules.upperCase.max);

      // Check lowercase letter requirements if the rule is enabled
      if (AuthCoreServer.config.passwordRules.lowerCase.enabled
          && (lowercaseCount < AuthCoreServer.config.passwordRules.lowerCase.min
              || lowercaseCount > AuthCoreServer.config.passwordRules.lowerCase.max))
        return AuthCoreServer.LOGGER.toUser(
            false,
            player.networkHandler,
            AuthCoreServer.messages.promptUserLowerCaseNotPresent,
            AuthCoreServer.config.passwordRules.lowerCase.min,
            AuthCoreServer.config.passwordRules.lowerCase.max);

      // Check digit requirements if the rule is enabled
      if (AuthCoreServer.config.passwordRules.digits.enabled
          && (digitsCount < AuthCoreServer.config.passwordRules.digits.min
              || digitsCount > AuthCoreServer.config.passwordRules.digits.max))
        return AuthCoreServer.LOGGER.toUser(
            false,
            player.networkHandler,
            AuthCoreServer.messages.promptUserDigitNotPresent,
            AuthCoreServer.config.passwordRules.digits.min,
            AuthCoreServer.config.passwordRules.digits.max);

      // Check overall password length requirements if the rule is enabled
      if (AuthCoreServer.config.passwordRules.length.enabled
          && (lengthCount < AuthCoreServer.config.passwordRules.length.min
              || lengthCount > AuthCoreServer.config.passwordRules.length.max))
        return AuthCoreServer.LOGGER.toUser(
            false,
            player.networkHandler,
            AuthCoreServer.messages.promptUserPasswordLengthIssue,
            AuthCoreServer.config.passwordRules.length.min,
            AuthCoreServer.config.passwordRules.length.max);

      // All checks passed
      return true;
    }

    // Fisher–Yates shuffle for string
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

  public static class TOTPManager {

    public static boolean verify(String inputCode, String secret) {
      try {
        // Normalize input: remove spaces, ensure 6 digits
        String normalized = inputCode.trim();
        if (!normalized.matches("\\d{6}")) return false;

        int code = Integer.parseInt(normalized);
        return TimeBasedOneTimePasswordUtil.validateCurrentNumber(secret, code, 1);

      } catch (Exception e) {
        return false;
      }
    }

    public static String getQrUrl(String username, String secret) {
      return "https://api.qrserver.com/v1/create-qr-code/?size=200x200&data="
          + TimeBasedOneTimePasswordUtil.generateOtpAuthUrl("AuthCore:" + username, secret);
    }

    public static String getSecret() {
      return TimeBasedOneTimePasswordUtil.generateBase32Secret(64);
    }

    public static BufferedImage getQRImage(String url) throws IOException {
      try (InputStream in = URI.create(url).toURL().openStream()) {
        return ImageIO.read(in);
      }
    }

    public static byte[] toPngBytes(BufferedImage img) throws IOException {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ImageIO.write(img, "PNG", out);
      return out.toByteArray();
    }
  }

  public static class CaptchaManager {
    private static final Map<UUID, String> pending = new ConcurrentHashMap<>();

    public static byte[] generate(UUID playerId) {
      SpecCaptcha captcha = new SpecCaptcha(160, 60, 5);
      captcha.setCharType(Captcha.TYPE_DEFAULT);

      String answer = captcha.text();
      pending.put(playerId, answer);

      ByteArrayOutputStream streamOutput = new ByteArrayOutputStream();
      captcha.out(streamOutput); // correct API

      return streamOutput.toByteArray();
    }

    public static boolean verify(UUID playerId, String input) {
      String expected = pending.get(playerId);
      if (expected == null) return false;

      boolean ok = expected.equalsIgnoreCase(input);
      if (ok) pending.remove(playerId);
      return ok;
    }
  }
}
