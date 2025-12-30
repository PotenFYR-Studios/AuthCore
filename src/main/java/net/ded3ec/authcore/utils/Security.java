package net.ded3ec.authcore.utils;

import java.util.regex.Pattern;
import net.ded3ec.authcore.AuthCore;
import net.minecraft.server.network.ServerPlayerEntity;

public class Security {

  /**
   * Checks the complexity of the password based on server rules.
   *
   * @param player The player attempting to register.
   * @param password The password to validate.
   * @return True if the password meets the complexity requirements, false otherwise.
   */
  public static boolean getPasswordComplexity(ServerPlayerEntity player, String password) {

    // Uppercases count using regex
    int uppercaseCount = Pattern.compile("[A-Z]").matcher(password).results().toArray().length;

    // Lowercases count using regex
    int lowercaseCount = Pattern.compile("[a-z]").matcher(password).results().toArray().length;

    // Digits count using regex
    int digitsCount = Pattern.compile("\\d").matcher(password).results().toArray().length;

    // Password length
    int lengthCount = password.length();

    // Checks uppercase in the password
    if (AuthCore.config.passwordRules.upperCase.enabled
        && (uppercaseCount < AuthCore.config.passwordRules.upperCase.min
            || uppercaseCount > AuthCore.config.passwordRules.upperCase.max))
      return Logger.toUser(
          false,
          player.networkHandler,
          AuthCore.messages.promptUserUpperCaseNotPresent,
          AuthCore.config.passwordRules.upperCase.min,
          AuthCore.config.passwordRules.upperCase.max);

    // Checks lowercase in the password
    else if (AuthCore.config.passwordRules.lowerCase.enabled
        && (lowercaseCount < AuthCore.config.passwordRules.lowerCase.min
            || lowercaseCount > AuthCore.config.passwordRules.lowerCase.max))
      return Logger.toUser(
          false,
          player.networkHandler,
          AuthCore.messages.promptUserLowerCaseNotPresent,
          AuthCore.config.passwordRules.lowerCase.min,
          AuthCore.config.passwordRules.lowerCase.max);

    // Checks digits in the password
    else if (AuthCore.config.passwordRules.digits.enabled
        && (digitsCount < AuthCore.config.passwordRules.digits.min
            || digitsCount > AuthCore.config.passwordRules.digits.max))
      return Logger.toUser(
          false,
          player.networkHandler,
          AuthCore.messages.promptUserDigitNotPresent,
          AuthCore.config.passwordRules.digits.min,
          AuthCore.config.passwordRules.digits.max);

    // Checks length of the password
    else if (AuthCore.config.passwordRules.length.enabled
        && (lengthCount < AuthCore.config.passwordRules.length.min
            || lengthCount > AuthCore.config.passwordRules.length.max))
      return Logger.toUser(
          false,
          player.networkHandler,
          AuthCore.messages.promptUserPasswordLengthIssue,
          AuthCore.config.passwordRules.length.min,
          AuthCore.config.passwordRules.length.max);
    else return true;
  }
}
