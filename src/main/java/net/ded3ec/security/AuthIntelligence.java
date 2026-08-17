package net.ded3ec.security;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.Config;
import net.ded3ec.models.User;

/**
 * Authentication-intelligence: detections that harden the LOGIN and LOGOUT process itself.
 *
 * <p>Every detection writes a security log + webhook alert and, where configured, blocks the
 * offending IP for a while. All counters are time-windowed and self-pruning, so memory stays
 * bounded no matter how many attempts arrive.
 *
 * <p>Detections: password spraying, per-IP login floods, 2FA brute force, guess-then-success
 * logins, mass registration, session-token replay and post-login account takeover patterns.
 * All configurable under {@code session.auth-intelligence}.
 */
public final class AuthIntelligence {

  private AuthIntelligence() {}

  // ------------------------------------------------------------------ windows

  /** Sliding-window timestamp list (bounded by pruning on access). */
  private static final class Window {
    final long windowMs;
    final java.util.ArrayDeque<Long> stamps = new java.util.ArrayDeque<>();

    Window(long windowMs) {
      this.windowMs = windowMs;
    }

    /** Adds a stamp and returns how many events are inside the window. */
    synchronized int add() {
      long now = System.currentTimeMillis();
      long cutoff = now - windowMs;
      while (!stamps.isEmpty() && stamps.peekFirst() < cutoff) stamps.pollFirst();
      stamps.addLast(now);
      return stamps.size();
    }

    synchronized int count() {
      long cutoff = System.currentTimeMillis() - windowMs;
      while (!stamps.isEmpty() && stamps.peekFirst() < cutoff) stamps.pollFirst();
      return stamps.size();
    }
  }

  // ------------------------------------------------------------------ state

  /** password -> (window, accounts that tried it). */
  private static final Map<String, SprayEntry> PASSWORD_SPRAY = new ConcurrentHashMap<>();

  private static final class SprayEntry {
    final Window window;
    final java.util.Set<String> accounts =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    SprayEntry(long windowMs) {
      this.window = new Window(windowMs);
    }
  }

  /** ip -> failed-login window. */
  private static final Map<String, Window> IP_LOGIN_FAILURES = new ConcurrentHashMap<>();

  /** account uuid -> failed-2FA window. */
  private static final Map<UUID, Window> ACCOUNT_2FA_FAILURES = new ConcurrentHashMap<>();

  /** ip -> registrations window. */
  private static final Map<String, Window> IP_REGISTRATIONS = new ConcurrentHashMap<>();

  /** ip -> blocked-until timestamp. */
  private static final Map<String, Long> IP_BLOCKS = new ConcurrentHashMap<>();

  // ------------------------------------------------------------------ alerts

  private static void alert(String event, String message) {
    AuthCoreServer.LOGGER.debug(false, "AuthIntelligence {} - {}", event, message);
    SecurityLog.log(event, message);
    AuthCoreServer.LOGGER.info(true, "{} - {}", event, message);
    net.ded3ec.network.Webhook.sendEmbed("AuthCore - " + event, message, 0xE74C3C);
  }

  private static boolean isIpBlocked(String ip) {
    Long until = IP_BLOCKS.get(ip);
    if (until == null) return false;
    if (until > System.currentTimeMillis()) return true;
    IP_BLOCKS.remove(ip);
    return false;
  }

  private static void blockIp(String ip, int minutes) {
    if (minutes > 0 && ip != null && !ip.isBlank())
      IP_BLOCKS.put(ip, System.currentTimeMillis() + minutes * 60_000L);
  }

  // ------------------------------------------------------------------ login

  /** Called on every FAILED password attempt. Detects password spraying and login floods. */
  public static void recordFailedPassword(String username, String ip, String password) {
    Config.Session.AuthIntelligenceConfig cfg = config();
    if (cfg == null) return;

    // Per-IP login flood (and temporary block).
    Config.Session.LoginFloodConfig flood = cfg.loginFlood;
    if (flood.enabled && ip != null && !ip.isBlank()) {
      Window w =
          IP_LOGIN_FAILURES.computeIfAbsent(ip, k -> new Window(flood.windowMin * 60_000L));
      int inWindow = w.add();
      AuthCoreServer.LOGGER.debug(
          false,
          "{} | failed login from {} ({} failures in {}min window)",
          username,
          ip,
          inWindow,
          flood.windowMin);
      if (inWindow >= flood.maxFailuresPerIp && !isIpBlocked(ip)) {
        alert(
            "LOGIN_FLOOD",
            "**" + username + "** from `" + ip + "` exceeded " + flood.maxFailuresPerIp
                + " failed logins in " + flood.windowMin + " minutes.");
        blockIp(ip, flood.blockMinutes);
        AuthCoreServer.LOGGER.debug(
            false, "{} | IP {} blocked for {} minutes (login flood)", username, ip, flood.blockMinutes);
      }
    }

    // Password spraying: the same password against many different accounts.
    Config.Session.PasswordSprayConfig spray = cfg.passwordSpray;
    if (!spray.enabled || password == null || password.isBlank()) return;
    SprayEntry entry =
        PASSWORD_SPRAY.computeIfAbsent(
            password, k -> new SprayEntry(spray.windowMin * 60_000L));
    if (entry.window.add() <= 1) entry.accounts.clear();
    entry.accounts.add(username);
    if (entry.accounts.size() >= spray.maxAccounts) {
      alert(
          "PASSWORD_SPRAY",
          "Password tried against **" + entry.accounts.size() + "** different accounts within "
              + spray.windowMin + " minutes (credential stuffing): `" + password + "`");
      PASSWORD_SPRAY.remove(password);
    }
  }

  /** Called when the password was RIGHT but the 2FA code was WRONG (ATO in progress). */
  public static void recordFailed2fa(UUID accountUuid, String username, String ip) {
    Config.Session.AuthIntelligenceConfig cfg = config();
    if (cfg == null || accountUuid == null) return;
    Config.Session.TotpBruteforceConfig totp = cfg.totpBruteforce;
    if (!totp.enabled) return;
    Window w =
        ACCOUNT_2FA_FAILURES.computeIfAbsent(accountUuid, k -> new Window(totp.windowMin * 60_000L));
    int inWindow = w.add();
    AuthCoreServer.LOGGER.debug(
        false,
        "{} | wrong 2FA code from {} ({} failures in {}min window)",
        username,
        ip,
        inWindow,
        totp.windowMin);
    if (inWindow >= totp.maxFailures) {
      alert(
          "TOTP_BRUTEFORCE",
          "**" + username + "** (`" + ip + "`) failed the 2FA code " + totp.maxFailures
              + " times in " + totp.windowMin + " minutes. The password is already compromised "
              + "- the account holder should be notified.");
      ACCOUNT_2FA_FAILURES.remove(accountUuid);
    }
  }

  /** Called on a SUCCESSFUL login. Detects guess-then-success logins. */
  public static void recordSuccessfulLogin(User user, String ip) {
    Config.Session.AuthIntelligenceConfig cfg = config();
    if (cfg == null || user == null) return;
    Config.Session.SuccessAfterFailuresConfig guess = cfg.successAfterFailures;
    if (guess.enabled && user.loginAttempts >= guess.minFailures) {
      alert(
          "LOGIN_AFTER_BRUTEFORCE",
          "**" + user.username + "** logged in successfully from `" + ip + "` after "
              + user.loginAttempts + " failed attempts (guess-then-success - possible compromise).");
    }
  }

  // ------------------------------------------------------------------ register

  /** Called on a successful registration. Detects mass registration (bot farms). */
  public static void recordRegistration(String username, String ip) {
    Config.Session.AuthIntelligenceConfig cfg = config();
    if (cfg == null || ip == null || ip.isBlank()) return;
    Config.Session.RegistrationFarmConfig farm = cfg.registrationFarm;
    if (!farm.enabled) return;
    Window w =
        IP_REGISTRATIONS.computeIfAbsent(ip, k -> new Window(farm.windowMin * 60_000L));
    int inWindow = w.add();
    AuthCoreServer.LOGGER.debug(
        false,
        "{} | registration from {} ({} in {}min window)",
        username,
        ip,
        inWindow,
        farm.windowMin);
    if (inWindow >= farm.maxAccountsPerIp && !isIpBlocked(ip)) {
      alert(
          "REGISTRATION_FARM",
          "`" + ip + "` registered " + farm.maxAccountsPerIp + " accounts in "
              + farm.windowMin + " minutes (last: **" + username + "**).");
      blockIp(ip, farm.blockMinutes);
      AuthCoreServer.LOGGER.debug(
          false, "{} | IP {} blocked for {} minutes (registration farm)", username, ip, farm.blockMinutes);
    }
  }

  /** Whether the IP is currently blocked from registering (mass-registration penalty). */
  public static boolean isRegistrationBlocked(String ip) {
    if (ip == null || ip.isBlank()) return false;
    Config.Session.AuthIntelligenceConfig cfg = config();
    if (cfg == null || !cfg.registrationFarm.enabled) return false;
    return isIpBlocked(ip);
  }

  // ------------------------------------------------------------------ session

  /** Called when a session token is claimed. Detects token replay from a different IP. */
  public static void recordSessionClaim(User user, String claimIp) {
    Config.Session.AuthIntelligenceConfig cfg = config();
    if (cfg == null || user == null || claimIp == null || claimIp.isBlank()) return;
    if (!cfg.sessionReplay.enabled) return;
    String issuedIp = user.lastLoginIp;
    AuthCoreServer.LOGGER.debug(
        false,
        "{} | session token claimed from {} (issued to {})",
        user.username,
        claimIp,
        issuedIp == null || issuedIp.isBlank() ? "unknown" : issuedIp);
    if (issuedIp != null && !issuedIp.isBlank() && !issuedIp.equals(claimIp)) {
      alert(
          "SESSION_REPLAY",
          "**" + user.username + "** presented a session token from `" + claimIp
              + "` but the session was issued to `" + issuedIp + "` (possible token theft).");
    }
  }

  // ------------------------------------------------------------------ account changes

  /** Called on a password change. Detects the post-login account-takeover pattern. */
  public static void recordPasswordChange(User user, String ip) {
    Config.Session.AuthIntelligenceConfig cfg = config();
    if (cfg == null || user == null) return;
    Config.Session.AtoPatternConfig ato = cfg.atoPattern;
    if (!ato.enabled) return;

    // ATO pattern: a successful login from a NEW network, then a password change soon after.
    long lastLogin = user.lastAuthenticatedMs;
    String lastLoginIp = user.lastLoginIp;
    AuthCoreServer.LOGGER.debug(
        false,
        "{} | password change from {} (last login {} from {})",
        user.username,
        ip,
        lastLogin <= 0 ? "n/a" : net.ded3ec.util.TimeManager.toDuration(System.currentTimeMillis() - lastLogin) + " ago",
        lastLoginIp == null || lastLoginIp.isBlank() ? "unknown" : lastLoginIp);
    if (lastLogin <= 0 || lastLoginIp == null || lastLoginIp.isBlank()) return;
    boolean withinWindow =
        System.currentTimeMillis() - lastLogin < ato.windowMin * 60_000L;
    boolean newNetwork =
        ip != null && !ip.isBlank() && !lastLoginIp.equals(ip);
    if (withinWindow && newNetwork) {
      alert(
          "POSSIBLE_ATO",
          "**" + user.username + "** changed their password from `" + ip + "` within "
              + ato.windowMin + " minutes of a login from `" + lastLoginIp
              + "` (possible account takeover).");
    }
  }

  // ------------------------------------------------------------------ helpers

  private static Config.Session.AuthIntelligenceConfig config() {
    return AuthCoreServer.config != null ? AuthCoreServer.config.session.authIntelligence : null;
  }
}
