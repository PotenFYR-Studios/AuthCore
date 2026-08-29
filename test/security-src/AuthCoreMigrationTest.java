import java.nio.file.Files;
import java.nio.file.Path;
import net.ded3ec.AuthCoreServer;
import net.ded3ec.util.HoconConf;

/**
 * Verifies the message-enrichment migration end-to-end: an OLD-style messages.conf (blank
 * titles, action-bar-only validation messages) must be refreshed with the enriched defaults
 * ("Violation Detected!" titles) when the migration runs, and a re-boot with the bumped
 * config version must keep the enriched values.
 */
public class AuthCoreMigrationTest {

  public static void main(String[] args) throws Exception {
    Path dir = AuthCoreServer.configPath;
    Files.createDirectories(dir);

    // --- Boot 1: old config version + old-style messages ---------------------
    Files.writeString(dir.resolve("settings.conf"), "version = \"1.0.0\"\n");
    Files.writeString(
        dir.resolve("messages.conf"),
        """
        prompt-user-upper-case-not-present {
            title { text = "" }
            actionBar { text = "Password must contain between %1$d - %2$d uppercase letter!" }
        }
        prompt-user-lower-case-not-present {
            title { text = "" }
            actionBar { text = "Password must contain between %1$d - %2$d lowercase letter" }
        }
        prompt-user-digit-not-present {
            title { text = "" }
            actionBar { text = "Password must contain between %1$d - %2$d digits" }
        }
        prompt-user-password-length-issue {
            title { text = "" }
            actionBar { text = "Password length should be between %1$d - %2$d!" }
        }
        prompt-user-not-registered {
            title { text = "" }
            actionBar { text = "You are not registered" }
        }
        prompt-user-logged-in-successfully {
            title { text = "" }
            actionBar { text = "Logged in" }
        }
        """);

    HoconConf.initialize();

    check(
        "migration refreshes upper-case title",
        "Violation Detected!".equals(
            AuthCoreServer.messages.promptUserUpperCaseNotPresent.title.text));
    check(
        "migration refreshes lower-case title",
        "Violation Detected!".equals(
            AuthCoreServer.messages.promptUserLowerCaseNotPresent.title.text));
    check(
        "migration refreshes digit title",
        "Violation Detected!".equals(
            AuthCoreServer.messages.promptUserDigitNotPresent.title.text));
    check(
        "migration refreshes length title",
        "Violation Detected!".equals(
            AuthCoreServer.messages.promptUserPasswordLengthIssue.title.text));
    check(
        "migration refreshes lobby/auth title",
        "You are Not Registered!".equals(
            AuthCoreServer.messages.promptUserNotRegistered.title.text));
    check(
        "migration refreshes login-success title",
        "Logged In!".equals(
            AuthCoreServer.messages.promptUserLoggedInSuccessfully.title.text));
    check(
        "migration preserves subtitles",
        "Password must contain between %1$d - %2$d uppercase letter!".equals(
            AuthCoreServer.messages.promptUserUpperCaseNotPresent.title.subtitle.text));
    check(
        "config version stays at 1.0.0",
        "1.0.0".equals(AuthCoreServer.config.version));
    check(
        "blindness normalized off (chat must stay usable)",
        !AuthCoreServer.config.lobby.applyBlindnessEffect);
    check(
        "debug mode enabled by default",
        AuthCoreServer.config.debugMode);

    // The refreshed values must have been persisted to disk
    String persisted = Files.readString(dir.resolve("messages.conf"));
    check(
        "enriched titles persisted to messages.conf",
        persisted.contains("Violation Detected!"));

    // --- Boot 2: re-boot (values already migrated) -----------------------------
    HoconConf.initialize();

    check(
        "re-boot keeps enriched titles",
        "Violation Detected!".equals(
            AuthCoreServer.messages.promptUserUpperCaseNotPresent.title.text));
    check(
        "re-boot keeps lobby/auth titles",
        "You are Not Registered!".equals(
            AuthCoreServer.messages.promptUserNotRegistered.title.text));

    // --- Boot 3: custom title is preserved ------------------------------------
    Files.writeString(
        dir.resolve("messages.conf"),
        """
        prompt-user-upper-case-not-present {
            title {
                text = "Custom Violation"
                subtitle { text = "Custom subtitle" }
            }
        }
        """);
    HoconConf.initialize();
    check(
        "custom titles are preserved",
        "Custom Violation".equals(
            AuthCoreServer.messages.promptUserUpperCaseNotPresent.title.text));

    // --- Boot 4: stale duplicated channels are cleared -------------------------
    Files.writeString(
        dir.resolve("messages.conf"),
        """
        prompt-user-logged-in-successfully {
            title {
                text = "Logged In!"
                subtitle { text = "Welcome to the Server!" }
            }
            actionBar { text = "Welcome to the Server!" }
        }
        prompt-user-upper-case-not-present {
            title {
                text = "Violation Detected!"
                subtitle { text = "Password must contain between %1$d - %2$d uppercase letter!" }
            }
            actionBar { text = "Password must contain between %1$d - %2$d uppercase letter!" }
        }
        """);
    HoconConf.initialize();
    check(
        "stale duplicate action-bar cleared (login success)",
        "".equals(
            AuthCoreServer.messages.promptUserLoggedInSuccessfully.actionBar.text));
    check(
        "stale duplicate action-bar cleared (validation)",
        "".equals(
            AuthCoreServer.messages.promptUserUpperCaseNotPresent.actionBar.text));
    check(
        "custom title preserved while clearing duplicates",
        "Violation Detected!".equals(
            AuthCoreServer.messages.promptUserUpperCaseNotPresent.title.text));
    check(
        "duplicate cleanup persisted to messages.conf",
        !Files.readString(dir.resolve("messages.conf"))
            .contains("Password must contain between %1$d - %2$d uppercase letter!\"\n            }"));

    System.out.println();
    System.out.println(
        "MIGRATION TEST RESULT: " + passed + " passed, " + failed + " failed");
    if (failed > 0) System.exit(1);
  }

  private static int passed = 0;
  private static int failed = 0;

  private static void check(String name, boolean condition) {
    if (condition) {
      passed++;
      System.out.println("  [PASS] " + name);
    } else {
      failed++;
      System.out.println("  [FAIL] " + name);
    }
  }
}
