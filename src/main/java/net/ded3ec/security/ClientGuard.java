package net.ded3ec.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.Config;
import net.ded3ec.models.Messages;
import net.ded3ec.models.User;
import net.minecraft.server.level.ServerPlayer;


/**
 * ClientGuard - the anti-bypass detection engine.
 *
 * <p>Builds a per-player behavioral profile from packet-level and gameplay signals and turns
 * them into a weighted risk score with a decision matrix (alert / 2FA-required / kick).
 * The companion mod (client side of the SAME jar) is always OPTIONAL: every check is
 * risk-based, and vanilla clients keep the normal chat-login flow - they only accrue small
 * risk penalties where a signal genuinely suggests automation.
 *
 * <p>Signals tracked: brand anomaly, missing client settings, ghost behaviour, packet/click/
 * chat/payload floods, tab-completion probing, companion attestation failures, confusable
 * names, concurrent logins and session-token mismatches.
 */
public final class ClientGuard {

  private ClientGuard() {}

  /** Shared attestation key - raised barrier, not a secret (same jar ships to clients). */
  public static final String ATTESTATION_KEY = "authcore-attest-v1:9f2c1d8e7a6b5c4d3e2f1a0b";

  /** Companion protocol version - bump when the payloads change. */
  public static final int PROTOCOL_VERSION = 1;

  /** Payload markers exchanged on the interop channel. */
  public static final String MSG_HELLO = "HELLO";
  public static final String MSG_CHALLENGE = "CHALLENGE";
  public static final String MSG_CHALLENGE_RESP = "CHALLENGE_RESP";
  public static final String MSG_SESSION_TOKEN = "SESSION_TOKEN";
  public static final String MSG_SESSION_TOKEN_ECHO = "SESSION_TOKEN_ECHO";

  /** Individual detection signals (each maps to a risk weight). */
  public enum Signal {
    BRAND_ANOMALY(15, "client brand is missing or looks automated"),
    NO_SETTINGS(15, "client never sent the settings packet"),
    GHOST(20, "no chat/auth/interaction since join (ghost client)"),
    MOVE_FLOOD(25, "movement packet rate exceeded"),
    CLICK_FLOOD(15, "inventory click rate exceeded in lobby"),
    CHAT_FLOOD(10, "chat rate exceeded in lobby"),
    PAYLOAD_FLOOD(10, "custom payload rate exceeded"),
    UNKNOWN_CHANNEL(10, "unregistered channel payloads in lobby"),
    TAB_PROBE(10, "command-tab-completion probing in lobby"),
    COMPANION_SPOOF(40, "claimed the AuthCore companion but failed attestation"),
    ATTESTATION_LOST(20, "passed attestation but failed a re-challenge"),
    NAME_CONFUSABLE(20, "username is confusable with a registered account"),
    CONCURRENT_LOGIN(25, "same account connected from a different connection"),
    SESSION_TOKEN_MISSING(10, "companion claimed session resume without a token"),
    VANILLA_RESUME(10, "session resumed by IP only (no companion token)"),
    OVERSIZED_PAYLOAD(15, "custom payload exceeded the lobby size limit");

    public final int weight;
    public final String description;

    Signal(int weight, String description) {
      this.weight = weight;
      this.description = description;
    }
  }

  /** Simple per-second rate counter (thread-safe). */
  static final class RateCounter {
    private final long[] window = {0L, 0L}; // [windowStartMs, count]
    private final long spanMs;

    RateCounter(long spanMs) {
      this.spanMs = spanMs;
    }

    int bump() {
      long now = System.currentTimeMillis();
      synchronized (this) {
        if (now - window[0] >= spanMs) {
          window[0] = now;
          window[1] = 0;
        }
        return (int) ++window[1];
      }
    }

    int count() {
      long now = System.currentTimeMillis();
      synchronized (this) {
        if (now - window[0] >= spanMs) return 0;
        return (int) window[1];
      }
    }
  }

  /** Per-player behavioral profile. */
  public static final class Profile {
    public final UUID uuid;
    public final String username;
    public final long joinMs;
    public volatile String brand = "";
    public volatile boolean settingsSeen = false;
    public volatile long lastActivityMs;
    public volatile int chatCount = 0;
    public volatile int commandCount = 0;

    // rate counters (per-second windows)
    final RateCounter moves = new RateCounter(1000);
    final RateCounter clicks = new RateCounter(1000);
    final RateCounter payloads = new RateCounter(1000);
    final RateCounter chats = new RateCounter(1000);

    // companion attestation state
    public volatile boolean claimsCompanion = false;
    public volatile boolean attestationOk = false;
    public volatile String pendingNonce = null;
    public volatile long challengeIssuedAtMs = 0L;
    public volatile String sessionTokenClaim = null;

    // signals + risk
    public final Set<Signal> signals = ConcurrentHashMap.newKeySet();
    public volatile int risk = 0;
    public volatile boolean notified = false;
    public volatile boolean ghostArmed = false;

    Profile(UUID uuid, String username) {
      this.uuid = uuid;
      this.username = username;
      this.joinMs = System.currentTimeMillis();
      this.lastActivityMs = this.joinMs;
    }

    public void addSignal(Signal signal) {
      if (signals.add(signal)) {
        SecurityLog.log("CLIENT_SIGNAL", username + " | " + signal.name() + " (" + signal.description + ")");
        net.ded3ec.network.Webhook.sendEmbed(
            "AuthCore - Client Signal",
            "**" + username + "** (" + uuid + ") triggered `" + signal.name() + "` - "
                + signal.description + " (risk " + risk + ")",
            0xE67E22);
        recomputeRisk();
      }
    }

    public void recomputeRisk() {
      int total = 0;
      for (Signal s : signals) total += s.weight;
      Config.Session.ClientGuardConfig cfg = config();
      if (cfg != null && signals.contains(Signal.COMPANION_SPOOF))
        total = total - Signal.COMPANION_SPOOF.weight + cfg.companionSpoofRisk;
      if (cfg != null && signals.contains(Signal.VANILLA_RESUME))
        total = total - Signal.VANILLA_RESUME.weight + cfg.vanillaResumeRisk;
      risk = Math.min(100, total);
    }

    public void touch() {
      lastActivityMs = System.currentTimeMillis();
    }
  }

  private static final Map<UUID, Profile> PROFILES = new ConcurrentHashMap<>();

  // ------------------------------------------------------------------ lifecycle

  public static Profile onJoin(ServerPlayer player) {
    Profile p = new Profile(player.getUUID(), player.getName().getString());
    PROFILES.put(p.uuid, p);
    return p;
  }

  public static void onLeave(UUID uuid) {
    PROFILES.remove(uuid);
  }

  public static Profile profile(UUID uuid) {
    return PROFILES.get(uuid);
  }

  public static Profile profile(net.minecraft.world.entity.player.Player player) {
    return profile(player.getUUID());
  }

  // --------------------------------------------------------------- rate hooks

  public static void recordMove(ServerPlayer player) {
    Profile p = profile(player);
    if (p == null) return;
    p.touch();
    Config.Session.ClientGuardConfig cfg = config();
    if (cfg != null && cfg.movePacketRatePerSec > 0 && p.moves.bump() > cfg.movePacketRatePerSec)
      p.addSignal(Signal.MOVE_FLOOD);
  }

  public static void recordClick(net.minecraft.world.entity.player.Player player) {
    Profile p = profile(player);
    if (p == null) return;
    p.touch();
    Config.Session.ClientGuardConfig cfg = config();
    if (cfg != null && cfg.lobbyClickRatePerSec > 0 && p.clicks.bump() > cfg.lobbyClickRatePerSec)
      p.addSignal(Signal.CLICK_FLOOD);
  }

  public static void recordPayload(ServerPlayer player, String channel, int bytes) {
    Profile p = profile(player);
    if (p == null) return;
    p.touch();
    Config.Session.ClientGuardConfig cfg = config();
    if (cfg == null || !cfg.enabled) return;
    if (cfg.maxPayloadBytes > 0 && bytes > cfg.maxPayloadBytes) {
      p.addSignal(Signal.OVERSIZED_PAYLOAD);
      return;
    }
    if (cfg.payloadRatePerSec > 0 && p.payloads.bump() > cfg.payloadRatePerSec)
      p.addSignal(Signal.PAYLOAD_FLOOD);
    if (channel != null
        && !channel.equals("minecraft:brand")
        && !channel.equals("minecraft:register")
        && !channel.equals("minecraft:unregister")
        && !channel.startsWith("authcore:")
        && !channel.startsWith("bungeecord:"))
      p.addSignal(Signal.UNKNOWN_CHANNEL);
  }

  public static void recordChat(ServerPlayer player, boolean command) {
    Profile p = profile(player);
    if (p == null) return;
    p.touch();
    if (command) {
      p.commandCount++;
      return;
    }
    p.chatCount++;
    Config.Session.ClientGuardConfig cfg = config();
    if (cfg != null && cfg.lobbyChatRatePerSec > 0 && p.chats.bump() > cfg.lobbyChatRatePerSec)
      p.addSignal(Signal.CHAT_FLOOD);
  }

  public static void recordTabProbe(ServerPlayer player) {
    Profile p = profile(player);
    if (p == null) return;
    p.addSignal(Signal.TAB_PROBE);
  }

  public static void recordBrand(ServerPlayer player, String brand) {
    Profile p = profile(player);
    if (p == null) return;
    p.brand = brand == null ? "" : brand;
    p.touch();
    // Vanilla clients always send a brand ("vanilla"); empty or odd ones are suspicious.
    if (brand == null || brand.isBlank() || brand.length() > 64) p.addSignal(Signal.BRAND_ANOMALY);
  }

  public static void recordSettings(ServerPlayer player) {
    Profile p = profile(player);
    if (p == null) return;
    p.settingsSeen = true;
    p.touch();
  }

  // ------------------------------------------------------- companion attestation

  /** Sends a fresh challenge to a companion-claiming client. */
  public static void issueChallenge(ServerPlayer player) {
    Profile p = profile(player);
    if (p == null) return;
    String nonce = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    p.pendingNonce = nonce;
    p.challengeIssuedAtMs = System.currentTimeMillis();
    net.ded3ec.network.AuthInterop.sendCompanion(
        player, MSG_CHALLENGE + "|" + PROTOCOL_VERSION + "|" + nonce);
  }

  /** Verifies a challenge response (constant-time). Returns true when valid. */
  public static boolean verifyChallenge(ServerPlayer player, String mac) {
    Profile p = profile(player);
    if (p == null || p.pendingNonce == null || mac == null) return false;
    String expected = hmac(p.pendingNonce + ":" + player.getUUID());
    p.pendingNonce = null;
    boolean ok = constantTimeEquals(expected, mac);
    if (ok) {
      p.attestationOk = true;
      p.signals.remove(Signal.COMPANION_SPOOF);
      p.recomputeRisk();
    } else {
      p.addSignal(Signal.COMPANION_SPOOF);
    }
    return ok;
  }

  /** HMAC-SHA256 over the payload using the shared attestation key (hex). */
  public static String hmac(String payload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(ATTESTATION_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] out = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(out.length * 2);
      for (byte b : out) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      return sb.toString();
    } catch (Exception e) {
      return "";
    }
  }

  // --------------------------------------------------------------- session tokens

  /** Records the session-token claim from a companion client (verified later). */
  public static void claimSessionToken(ServerPlayer player, String token) {
    Profile p = profile(player);
    if (p == null) return;
    p.sessionTokenClaim = token;
    p.touch();
  }

  /**
   * Post-join verification (called a few seconds after join): a companion-claiming client
   * must present a valid session token when resuming; otherwise the session is revoked.
   * Also handles network-wide SSO trust (Redis) when enabled.
   */
  public static void verifySessionClaim(ServerPlayer player) {
    Profile p = profile(player);
    if (p == null || player == null) return;
    User user = User.getUser(p.username, p.uuid);
    if (user == null) return;

    // Network-wide SSO: a remote server holds an active login for this player. Trust it
    // when the companion presents the matching ticket token (or when trustVanilla is set).
    if (AuthCoreServer.config.session.sso.enabled && user.isRegistered.get()) {
      boolean remoteTrusted = false;
      if (p.sessionTokenClaim != null && net.ded3ec.network.RedisManager.verifySso(p.uuid, p.sessionTokenClaim)) {
        remoteTrusted = true;
      } else if (AuthCoreServer.config.session.sso.trustVanilla
          && net.ded3ec.network.RedisManager.hasSso(p.uuid)) {
        remoteTrusted = true;
      }
      if (remoteTrusted && !user.isAuthenticated.get()) {
        AuthCoreServer.LOGGER.info(
            true, "{} authenticated via network SSO (Redis)", p.username);
        net.ded3ec.security.SecurityLog.log("SSO_LOGIN", p.username + " | trusted from the network");
        net.ded3ec.network.RedisManager.publishEvent(
            "sso-login", p.username, "authenticated via network single sign-on");
        user.login(player);
        return;
      }
    }

    if (p.claimsCompanion) {
      if (p.sessionTokenClaim != null && user.verifySessionToken(p.sessionTokenClaim)) {
        // full-trust resume - clear the vanilla-resume penalty
        p.signals.remove(Signal.VANILLA_RESUME);
        p.recomputeRisk();
        AuthCoreServer.LOGGER.debug(
            true, "{} resumed session with a valid companion session token", p.username);
      } else {
        p.addSignal(Signal.SESSION_TOKEN_MISSING);
        // the client claims the companion but cannot prove the session - revoke it
        if (user.isActiveSession.get()) {
          AuthCoreServer.LOGGER.toKick(false, player.connection, AuthCoreServer.messages.promptUserSessionExpired);
          AuthCoreServer.LOGGER.warn(
              false,
              "{} claimed a companion session resume without a valid token - session revoked",
              p.username);
        }
      }
    } else if (user.isActiveSession.get()) {
      // vanilla client resuming by IP only - small penalty, keep the flow working
      Config.Session.ClientGuardConfig cfg = config();
      if (cfg != null && cfg.requireTokenForResume) {
        p.addSignal(Signal.VANILLA_RESUME);
        AuthCoreServer.LOGGER.debug(
            true, "{} resumed session by IP only (vanilla client) - risk penalty applied", p.username);
      }
    }
  }

  // ------------------------------------------------------------------ tick loop

  /** Per-tick driver: ghost/settings watchdogs, re-challenges, risk enforcement. */
  public static void tick(net.minecraft.server.MinecraftServer server) {
    Config.Session.ClientGuardConfig cfg = config();
    if (cfg == null || !cfg.enabled) return;

    long now = System.currentTimeMillis();
    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
      Profile p = profile(player);
      if (p == null) continue;
      User user = User.getUser(p.username, p.uuid);
      boolean inLobby = user != null && user.isInLobby.get();

      // ---- settings watchdog ------------------------------------------------
      if (cfg.settingsTimeoutSec > 0
          && !p.settingsSeen
          && now - p.joinMs > cfg.settingsTimeoutSec * 1000L
          && inLobby) {
        p.addSignal(Signal.NO_SETTINGS);
        p.settingsSeen = true; // only signal once
      }

      // ---- ghost detection ---------------------------------------------------
      if (cfg.ghostKickAfterSec > 0 && inLobby) {
        long idle = now - p.lastActivityMs;
        if (!p.ghostArmed && idle > (cfg.ghostKickAfterSec - 5) * 1000L) {
          p.addSignal(Signal.GHOST);
          p.ghostArmed = true;
        }
        if (idle > cfg.ghostKickAfterSec * 1000L) {
          AuthCoreServer.LOGGER.toKick(false, player.connection, AuthCoreServer.messages.promptUserAuthenticationExpiredTimeout);
          AuthCoreServer.LOGGER.warn(
              false, "{} was kicked as a ghost client (no activity for {}s)", p.username, cfg.ghostKickAfterSec);
          continue;
        }
      }

      // ---- companion re-challenge ---------------------------------------------
      if (p.claimsCompanion && cfg.challengeTimeoutSec > 0) {
        long sinceChallenge = p.pendingNonce != null ? now - p.challengeIssuedAtMs : 0;
        if (p.pendingNonce != null && sinceChallenge > cfg.challengeTimeoutSec * 1000L) {
          p.pendingNonce = null;
          p.addSignal(p.attestationOk ? Signal.ATTESTATION_LOST : Signal.COMPANION_SPOOF);
          if (p.attestationOk) {
            // downgrade: force re-login for a companion that stopped answering
            AuthCoreServer.LOGGER.toKick(false, player.connection, AuthCoreServer.messages.promptUserAuthenticationExpiredTimeout);
            p.attestationOk = false;
            continue;
          }
        }
        if (cfg.reChallengeIntervalSec > 0
            && p.pendingNonce == null
            && (p.lastActivityMs == p.joinMs || now - p.challengeIssuedAtMs > cfg.reChallengeIntervalSec * 1000L)) {
          issueChallenge(player);
        }
      }

      // ---- decision matrix -----------------------------------------------------
      if (inLobby) {
        if (p.risk >= cfg.riskKickThreshold) {
          SecurityLog.log(
              "RISK_KICK",
              p.username + " kicked at risk " + p.risk + " (signals: " + p.signals + ")");
          net.ded3ec.network.Webhook.sendEmbed(
              "AuthCore - Risk Kick",
              "**" + p.username + "** kicked at risk **" + p.risk + "** - signals: `" + p.signals + "`",
              0xE74C3C);
          AuthCoreServer.LOGGER.toKick(false, player.connection, AuthCoreServer.messages.promptUserAuthenticationExpiredTimeout);
          continue;
        }
        if (p.risk >= cfg.riskAlertThreshold && !p.notified) {
          p.notified = true;
          SecurityLog.log(
              "RISK_ALERT", p.username + " reached risk " + p.risk + " (signals: " + p.signals + ")");
        }
      }
    }
  }

  // ------------------------------------------------------------------ identity

  /** ASCII-normalizes a name (NFKD + confusable map) for impersonation checks. */
  public static String normalizeName(String name) {
    String normalized = Normalizer.normalize(name, Normalizer.Form.NFKD);
    StringBuilder sb = new StringBuilder(normalized.length());
    for (int i = 0; i < normalized.length(); i++) {
      char c = normalized.charAt(i);
      if (Character.getType(c) == Character.NON_SPACING_MARK) continue;
      Character mapped = CONFUSABLES.get(c);
      sb.append(mapped != null ? mapped : c);
    }
    return sb.toString().toLowerCase(Locale.ROOT);
  }

  /** Signals a confusable-name flag when the (normalized) name is close to a registered one. */
  public static void checkConfusableName(String username) {
    Config.Session.ClientGuardConfig cfg = config();
    if (cfg == null || !cfg.detectConfusableNames) return;
    String normalized = normalizeName(username);
    if (normalized.equalsIgnoreCase(username)) return; // nothing confusable

    for (String registered : net.ded3ec.util.Database.getRegisteredNames()) {
      if (registered == null || registered.equalsIgnoreCase(username)) continue;
      if (levenshtein(normalized, normalizeName(registered)) <= 1) {
        SecurityLog.log(
            "NAME_CONFUSABLE",
            username + " looks like registered account " + registered + " (impersonation risk)");
        net.ded3ec.network.Webhook.sendEmbed(
            "AuthCore - Confusable Name",
            "**" + username + "** is confusable with registered account **" + registered + "**.",
            0xE74C3C);
        return;
      }
    }
  }

  private static int levenshtein(String a, String b) {
    int[] prev = new int[b.length() + 1];
    int[] curr = new int[b.length() + 1];
    for (int j = 0; j <= b.length(); j++) prev[j] = j;
    for (int i = 1; i <= a.length(); i++) {
      curr[0] = i;
      for (int j = 1; j <= b.length(); j++) {
        int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
      }
      int[] tmp = prev;
      prev = curr;
      curr = tmp;
    }
    return prev[b.length()];
  }

  // ------------------------------------------------------------------ helpers

  private static Config.Session.ClientGuardConfig config() {
    if (AuthCoreServer.config == null) return null;
    return AuthCoreServer.config.session.clientGuard;
  }

  private static boolean constantTimeEquals(String a, String b) {
    if (a == null || b == null || a.length() != b.length()) return false;
    int diff = 0;
    for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
    return diff == 0;
  }

  /** Common Cyrillic/Greek/Latin confusables. */
  private static final Map<Character, Character> CONFUSABLES =
      Map.ofEntries(
          Map.entry('а', 'a'), Map.entry('е', 'e'), Map.entry('о', 'o'), Map.entry('р', 'p'),
          Map.entry('с', 'c'), Map.entry('у', 'y'), Map.entry('х', 'x'), Map.entry('і', 'i'),
          Map.entry('ѕ', 's'), Map.entry('ј', 'j'), Map.entry('в', 'b'),
          Map.entry('к', 'k'), Map.entry('м', 'm'), Map.entry('н', 'h'), Map.entry('т', 't'),
          Map.entry('Α', 'A'), Map.entry('Ε', 'E'), Map.entry('Ο', 'O'), Map.entry('Ι', 'I'),
          Map.entry('ν', 'v'), Map.entry('ω', 'w'));
}
