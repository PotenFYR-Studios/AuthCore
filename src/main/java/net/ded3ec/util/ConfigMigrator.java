package net.ded3ec.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.Config;

/**
 * Version-gated configuration migrations.
 *
 * <p>Runs after {@code settings.conf} is loaded and before it is saved: every migration step
 * registered for a version newer than the stored {@code config.version} is applied in order,
 * then the version is bumped and the config persisted. New keys are already added
 * automatically by Configurate (shouldCopyDefaults); this runner handles the cases that need
 * real transforms (renames, value migration, structural changes).
 *
 * <p>Register a step by adding an entry to {@link #STEPS} keyed by the TARGET version string.
 * Steps must be idempotent and never throw (a failing step aborts the upgrade and keeps the
 * old version so it is retried on the next boot).
 */
public final class ConfigMigrator {

  private ConfigMigrator() {}

  /** Migration steps: target version → transform applied to the in-memory config. */
  private static final Map<String, Consumer<Config>> STEPS = new LinkedHashMap<>();
  // NOTE: no version-gated steps are registered. The config version is kept at 1.0.0 and
  // every migration is done by idempotent always-on logic (message-default refresh in
  // HoconConf.initialize + the blindness normalization below) so an admin's later config
  // choices are never overridden on a subsequent boot.

  /**
   * Always-on safety normalization: the lobby blindness effect blocks the chat window on
   * the client, which makes /register and /login unusable - it is force-disabled (with a
   * warning) whenever it is enabled. Admins who still want it can keep it off (the chat
   * caveat is documented on the setting itself).
   */
  public static void normalizeBlindness() {
    if (AuthCoreServer.config != null && AuthCoreServer.config.lobby.applyBlindnessEffect) {
      AuthCoreServer.config.lobby.applyBlindnessEffect = false;
      AuthCoreServer.LOGGER.warn(
          false,
          "lobby.applyBlindnessEffect was enabled but blindness blocks the chat window, "
              + "which is required for /register and /login - disabled automatically.");
    }
  }

  // NOTE: the message-enrichment migration was superseded by the always-on
  // refreshEnrichedMessageDefaults() call in HoconConf.initialize() (runs on every boot,
  // idempotent, preserves custom values).

  /**
   * Message keys whose multi-channel defaults were enriched (title + subtitle etc.). Includes
   * the password-validation templates (previously action-bar-only) - they show the "Violation
   * Detected" title and were missing from the 1.0.1 refresh.
   */
  private static final java.util.Set<String> ENRICHED_MESSAGE_KEYS =
      java.util.Set.of(
          "promptUserNotRegistered",
          "promptUserLoggedInSuccessfully",
          "promptUserWrongPassword",
          "promptUserRegisteredSuccessfully",
          "promptUserPremiumAutoLogin",
          "promptUserSessionResumed",
          "promptUserPasswordChangedSuccessfully",
          "promptUserWelcomeLobbyUser",
          "promptUserBreakBlockNotAllowed",
          "promptUserUseBlockNotAllowed",
          "promptUserUseItemNotAllowed",
          "promptUserPlayerMovementNotAllowed",
          "promptUserChatNotAllowed",
          "promptUserCommandExecutionNotAllowed",
          "promptUserDropItemNotAllowed",
          "promptUserUpperCaseNotPresent",
          "promptUserLowerCaseNotPresent",
          "promptUserDigitNotPresent",
          "promptUserPasswordLengthIssue");

  /**
   * Copies the fresh default for every enriched message template whose CURRENT value is still
   * the old single-channel default. Old defaults are identified by an EMPTY TITLE (they only
   * carried chat/action-bar text and had no title block at all) - templates the admin
   * customized (a non-default title) are left untouched.
   *
   * <p>Runs on EVERY boot (idempotent): the enriched titles must reach installs no matter
   * what state their messages file is in, independent of the version-gated migration steps.
   */
  public static void refreshEnrichedMessageDefaults() {
    try {
      net.ded3ec.models.Messages defaults = new net.ded3ec.models.Messages();
      java.lang.reflect.Field[] fields = net.ded3ec.models.Messages.class.getFields();
      boolean changed = false;

      for (java.lang.reflect.Field field : fields) {
        if (!ENRICHED_MESSAGE_KEYS.contains(field.getName())) continue;

        Object current = field.get(AuthCoreServer.messages);
        Object fresh = field.get(defaults);
        if (!(current instanceof net.ded3ec.models.Messages.ColTemplate cur)
            || !(fresh instanceof net.ded3ec.models.Messages.ColTemplate fr)) continue;

        boolean oldSingleChannel =
            cur.title == null
                || cur.title.text == null
                || cur.title.text.isBlank();

        if (oldSingleChannel) {
          // Preserve a custom title if present, otherwise copy the fresh default
          boolean hasCustomTitle =
              cur.title != null
                  && cur.title.text != null
                  && !cur.title.text.isBlank()
                  && !"Violation Detected!".equals(cur.title.text);
          if (!hasCustomTitle) {
            field.set(AuthCoreServer.messages, fr);
            changed = true;
          }
        } else {
          // Clear stale duplicated channels: older configs carried the SAME sentence in the
          // action-bar/message channel that the subtitle now shows (the enriched defaults
          // dropped those duplicates). A custom title is preserved - only the duplicate
          // channel text is removed.
          String subtitleText =
              cur.title != null && cur.title.subtitle != null
                  ? cur.title.subtitle.text
                  : null;
          if (subtitleText != null && !subtitleText.isBlank()) {
            if (cur.actionBar != null
                && cur.actionBar.text != null
                && subtitleText.equals(cur.actionBar.text)) {
              cur.actionBar.text = "";
              changed = true;
            }
            if (cur.message != null
                && cur.message.text != null
                && subtitleText.equals(cur.message.text)) {
              cur.message.text = "";
              changed = true;
            }
          }
        }
      }
      if (changed) {
        HoconConf.saveMessages();
        AuthCoreServer.LOGGER.info(
            true, "Message defaults refreshed for the enriched lobby/auth templates.");
      }

      // The login-success greeting now shows the player's nickname placeholder - upgrade
      // the old subtitle so existing configs greet with the nickname too.
      try {
        net.ded3ec.models.Messages.ColTemplate loggedIn =
            AuthCoreServer.messages.promptUserLoggedInSuccessfully;
        if (loggedIn != null
            && loggedIn.title != null
            && loggedIn.title.subtitle != null
            && "Welcome to the Server!".equals(loggedIn.title.subtitle.text)) {
          loggedIn.title.subtitle.text = "Welcome to the Server, %authcore_nickname%!";
          HoconConf.saveMessages();
        }
      } catch (RuntimeException ignored) {
        // greeting upgrade is best-effort
      }
    } catch (ReflectiveOperationException | RuntimeException err) {
      AuthCoreServer.LOGGER.debug(
          false, "Message-default refresh skipped (best-effort):", err);
    }
  }

  /**
   * Applies every pending migration step for the loaded config, bumps the version and
   * persists. Safe to call on every boot - no-ops when already current.
   */
  public static void migrate() {
    if (AuthCoreServer.config == null) return;

    String current = AuthCoreServer.config.version;
    if (current == null || current.isBlank()) current = "1.0.0";

    boolean changed = false;
    for (Map.Entry<String, Consumer<Config>> step : STEPS.entrySet()) {
      if (compareVersions(step.getKey(), current) <= 0) continue;

      try {
        step.getValue().accept(AuthCoreServer.config);
        changed = true;
      } catch (Exception err) {
        AuthCoreServer.LOGGER.error(
            false,
            "Config migration to '{}' failed (will retry on next boot):",
            step.getKey(),
            err);
        return;
      }
    }

    // The config version is intentionally kept at 1.0.0: every migration step is
    // idempotent (it no-ops once the value was transformed), so re-running them on every
    // boot is harmless and the stored version never accumulates migration noise. Any
    // previously bumped version is cleaned back to 1.0.0.
    if (!"1.0.0".equals(AuthCoreServer.config.version)) {
      AuthCoreServer.config.version = "1.0.0";
      changed = true;
      AuthCoreServer.LOGGER.info(
          true,
          "Config version normalized back to 1.0.0 (migration steps are idempotent).");
    }

    if (changed) HoconConf.saveConfig();
  }

  /** Simple dotted-numeric version compare ("1.0.1" vs "1.0.0"). Non-numeric parts are ignored. */
  private static int compareVersions(String a, String b) {
    String[] pa = a.split("\\.");
    String[] pb = b.split("\\.");
    int len = Math.max(pa.length, pb.length);
    for (int i = 0; i < len; i++) {
      int na = i < pa.length ? parseInt(pa[i]) : 0;
      int nb = i < pb.length ? parseInt(pb[i]) : 0;
      if (na != nb) return Integer.compare(na, nb);
    }
    return 0;
  }

  private static int parseInt(String part) {
    StringBuilder digits = new StringBuilder();
    for (char c : part.toCharArray())
      if (Character.isDigit(c)) digits.append(c);
    return digits.length() == 0 ? 0 : Integer.parseInt(digits.toString());
  }
}
