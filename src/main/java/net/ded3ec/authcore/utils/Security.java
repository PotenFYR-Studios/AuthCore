package net.ded3ec.authcore.utils;

import java.security.SecureRandom;
import net.ded3ec.authcore.AuthCore;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Utility class containing security-related functionality for AuthCore. Currently, provides
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
      if (AuthCore.config.passwordRules.upperCase.enabled
          && (uppercaseCount < AuthCore.config.passwordRules.upperCase.min
              || uppercaseCount > AuthCore.config.passwordRules.upperCase.max))
        return Logger.toUser(
            false,
            player.networkHandler,
            AuthCore.messages.promptUserUpperCaseNotPresent,
            AuthCore.config.passwordRules.upperCase.min,
            AuthCore.config.passwordRules.upperCase.max);

      // Check lowercase letter requirements if the rule is enabled
      if (AuthCore.config.passwordRules.lowerCase.enabled
          && (lowercaseCount < AuthCore.config.passwordRules.lowerCase.min
              || lowercaseCount > AuthCore.config.passwordRules.lowerCase.max))
        return Logger.toUser(
            false,
            player.networkHandler,
            AuthCore.messages.promptUserLowerCaseNotPresent,
            AuthCore.config.passwordRules.lowerCase.min,
            AuthCore.config.passwordRules.lowerCase.max);

      // Check digit requirements if the rule is enabled
      if (AuthCore.config.passwordRules.digits.enabled
          && (digitsCount < AuthCore.config.passwordRules.digits.min
              || digitsCount > AuthCore.config.passwordRules.digits.max))
        return Logger.toUser(
            false,
            player.networkHandler,
            AuthCore.messages.promptUserDigitNotPresent,
            AuthCore.config.passwordRules.digits.min,
            AuthCore.config.passwordRules.digits.max);

      // Check overall password length requirements if the rule is enabled
      if (AuthCore.config.passwordRules.length.enabled
          && (lengthCount < AuthCore.config.passwordRules.length.min
              || lengthCount > AuthCore.config.passwordRules.length.max))
        return Logger.toUser(
            false,
            player.networkHandler,
            AuthCore.messages.promptUserPasswordLengthIssue,
            AuthCore.config.passwordRules.length.min,
            AuthCore.config.passwordRules.length.max);

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
}
