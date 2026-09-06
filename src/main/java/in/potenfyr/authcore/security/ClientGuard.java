package in.potenfyr.authcore.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import in.potenfyr.authcore.AuthCoreServer;
import in.potenfyr.authcore.models.Config;
import in.potenfyr.authcore.models.Messages;
import in.potenfyr.authcore.models.User;
import net.minecraft.server.level.ServerPlayer;


/**
 * ClientGuard, the anti-bypass detection engine.
 *
 * <p>Builds a per-player behavioral profile from packet-level and gameplay signals and turns
 * them into a weighted risk score with a decision matrix (alert / 2FA-required / kick).
 * The companion mod (client side of the same jar) is always optional: every check is
 * risk-based, and vanilla clients keep the normal chat-login flow, only accruing small risk
 * penalties where a signal genuinely suggests automation.
 *
 * <p>Signals tracked: brand anomaly, missing client settings, ghost behaviour, packet/click/
 * chat/payload floods, tab-completion probing, companion attestation failures, confusable
 * names, concurrent logins and session-token mismatches.
 */
public final class ClientGuard {

  private ClientGuard() {}

  /**
   * Companion attestation key (loaded from disk per-server, not hardcoded).
   * See {@link in.potenfyr.authcore.security.AttestationKeyManager}.
   */
  public static final String ATTESTATION_KEY =
      "attestation-key-managed-by-AttestationKeyManager";

  /** Companion protocol version - bump when the payloads change. */
  public static final int PROTOCOL_VERSION = 1;

  /** Payload markers exchanged on the interop channel. */
  public static final String MSG_HELLO = "HELLO";
  public static final String MSG_CHALLENGE = "CHALLENGE";
  public static final String MSG_CHALLENGE_RESP = "CHALLENGE_RESP";
  public static final String MSG_SESSION_TOKEN = "SESSION_TOKEN";
  public static final String MSG_SESSION_TOKEN_ECHO = "SESSION_TOKEN_ECHO";
  /** Server -> client: the player is locked in the auth lobby - the companion opens its
   *  in-game GUI (login/register) automatically. */
  public static final String MSG_IN_LOBBY = "IN_LOBBY";
  /** Server -> client: the player authenticated - the companion closes its in-game GUI. */
  public static final String MSG_OUT_OF_LOBBY = "OUT_OF_LOBBY";

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
    CONCURRENT_FARM(25, "multiple different accounts from the same IP in a short window"),
    CONCURRENT_LOGIN(25, "same account connected from a different connection"),
    SESSION_TOKEN_MISSING(10, "companion claimed session resume without a token"),
    VANILLA_RESUME(10, "session resumed by IP only (no companion token)"),
    OVERSIZED_PAYLOAD(15, "custom payload exceeded the lobby size limit"),
    LOOK_PATTERN(20, "look-change pattern is too regular (bot-like camera movement)");

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

    /** Timestamp of the last view-rotation (look) packet - bots never rotate. */
    public volatile long lastLookChangeMs = 0L;

    /** Previous look rotation for delta computation (sentinel -9999 = unset). */
    public volatile float lastPitch = -9999f;
    public volatile float lastYaw = -9999f;

    /** Timestamp of last look-pattern check (rate-limits the analysis). */
    public volatile long lastLookCheckMs = 0L;

    public volatile int chatCount = 0;
    public volatile int commandCount = 0;

    // rate counters (per-second windows)
    final RateCounter moves = new RateCounter(1000);
    final RateCounter clicks = new RateCounter(1000);
    final RateCounter payloads = new RateCounter(1000);
    final RateCounter chats = new RateCounter(1000);

    // timing-regularity detection: timestamped click events only (bounded).
    final java.util.List<Long> clickTimestamps = new java.util.ArrayList<>();
    static final int MAX_TIMESTAMP_SAMPLES = 40;

    /** Look-change delta tracking for bot-pattern detection (bounded). */
    final java.util.List<LookDelta> lookDeltas = new java.util.ArrayList<>();
    static final int MAX_LOOK_DELTAS = 30;

    /** Records a timestamped click for timing-regularity analysis. */
    void recordClickTimestamp() {
      long now = System.currentTimeMillis();
      synchronized (clickTimestamps) {
        clickTimestamps.add(now);
        if (clickTimestamps.size() > MAX_TIMESTAMP_SAMPLES) clickTimestamps.remove(0);
      }
    }

    /** Records a look-change delta for bot-pattern analysis. */
    void recordLookDelta(float dPitch, float dYaw) {
      long now = System.currentTimeMillis();
      synchronized (lookDeltas) {
        lookDeltas.add(new LookDelta(now, dPitch, dYaw));
        if (lookDeltas.size() > MAX_LOOK_DELTAS) lookDeltas.remove(0);
      }
    }

    /** Computes bot-pattern score from look-change deltas (0 = human, 1 = bot). */
    double lookPatternScore() {
      synchronized (lookDeltas) {
        int n = lookDeltas.size();
        if (n < 5) return 0.0;

        // Compute variance of delta magnitudes
        double sum = 0, sumSq = 0;
        int valid = 0;
        long now = System.currentTimeMillis();
        for (LookDelta ld : lookDeltas) {
          if (now - ld.ts > 30_000L) continue; // stale
          double mag = Math.sqrt(ld.dp * ld.dp + ld.dy * ld.dy);
          if (mag < 0.01f) continue; // no actual movement
          sum += mag;
          sumSq += mag * mag;
          valid++;
        }
        if (valid < 3) return 0.0;
        double mean = sum / valid;
        if (mean < 0.5) return 0.0; // too small to measure
        double variance = (sumSq / valid) - (mean * mean);
        if (variance < 0) variance = 0;
        double stddev = Math.sqrt(variance);
        double cv = stddev / mean;
        // cv near 0 = perfectly regular (bot), cv >= 0.3 = random (human)
        return Math.max(0, Math.min(1, 1.0 - (cv / 0.3)));
      }
    }

    /** Analyzes timing regularity from clicks only - bots have near-zero variance. */
    double timingRegularityScore() {
      synchronized (clickTimestamps) {
        int n = clickTimestamps.size();
        if (n < 4) return 0.0;
        // compute intervals between consecutive timestamps
        double[] intervals = new double[n - 1];
        for (int i = 1; i < n; i++)
          intervals[i - 1] = clickTimestamps.get(i) - clickTimestamps.get(i - 1);
        // filter out long idle gaps (> 3s) - those break bot patterns
        int valid = 0;
        double sum = 0, sumSq = 0;
        for (double d : intervals) {
          if (d <= 3000) {
            valid++;
            sum += d;
            sumSq += d * d;
          }
        }
        if (valid < 3) return 0.0;
        double mean = sum / valid;
        if (mean < 10) return 0.0; // too fast to measure
        double variance = (sumSq / valid) - (mean * mean);
        if (variance < 0) variance = 0;
        double stddev = Math.sqrt(variance);
        // coefficient of variation: 0 = perfectly regular (bot), 1 = very random (human)
        double cv = stddev / mean;
        // normalize: cv=0 -> score 1.0 (bot), cv>=0.5 -> score 0.0 (human)
        return Math.max(0, Math.min(1, 1.0 - (cv / 0.5)));
      }
    }

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
        AuthCoreServer.LOGGER.debug(
            false,
            "{} | ClientGuard signal {} (+{} risk) - {}",
            username,
            signal.name(),
            signal.weight,
            signal.description);
        SecurityLog.log("CLIENT_SIGNAL", username + " | " + signal.name() + " (" + signal.description + ")");
        in.potenfyr.authcore.network.Webhook.sendEmbed(
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

    public void recordActionTimestamp() {
      lastActivityMs = System.currentTimeMillis();
      recordClickTimestamp();
    }
  }

  /** Look-change delta record for bot-pattern analysis. */
  public static final class LookDelta {
    public final long ts;
    public final float dp;
    public final float dy;
    public final float pitch;
    public final float yaw;

    public LookDelta(long ts, float dp, float dy) {
      this.ts = ts;
      this.dp = dp;
      this.dy = dy;
      this.pitch = dp;
      this.yaw = dy;
    }
  }

  private static final Map<net.minecraft.world.entity.player.Player, Profile> PROFILES = new ConcurrentHashMap<>();
  private static final Map<UUID, Profile> PROFILES_BY_UUID = new ConcurrentHashMap<>();

  /** Concurrent-farm detection: IP → recent (username, timestamp) pairs. */
  private static final Map<String, java.util.List<java.util.Map.Entry<String, Long>>> IP_RECENT_USERNAMES =
      new ConcurrentHashMap<>();

  /** Window (ms) for counting distinct usernames per IP. */
  private static final long FARM_WINDOW_MS = 5_000L;

  /** Minimum distinct usernames from the same IP to flag a farm. */
  private static final int FARM_THRESHOLD = 3;

  /** Normalized-name index for O(1) confusable-name lookups. */
  private static final java.util.Map<String, java.util.Set<String>> CONFUSABLE_INDEX = new ConcurrentHashMap<>();
  private static volatile long CONFUSABLE_INDEX_AT = 0L;

  // ------------------------------------------------------------------ lifecycle

  public static Profile onJoin(ServerPlayer player) {
    Profile p = new Profile(player.getUUID(), player.getName().getString());
    PROFILES.put(player, p);
    PROFILES_BY_UUID.put(p.uuid, p);
    AuthCoreServer.LOGGER.debug(
        false, "{} | ClientGuard profile created (join)", p.username);
    return p;
  }

  public static void onLeave(net.minecraft.world.entity.player.Player player) {
    if (player == null) return;
    Profile p = PROFILES.remove(player);
    if (p == null) return;
    AuthCoreServer.LOGGER.debug(
        false, "{} | ClientGuard profile dropped (leave, risk {})", p.username, p.risk);
    PROFILES_BY_UUID.remove(p.uuid);
  }

  // --------------------------------------------------------------- concurrent-farm detection

  /**
   * Called on every join with the connection's real IP. Tracks distinct usernames per IP
   * within a short window and flags potential bot-farm connections when ≥3 different
   * usernames arrive from the same IP within {@value #FARM_WINDOW_MS}ms.
   */
  public static void checkConcurrentFarm(String ipAddress, String username) {
    checkConcurrentFarm(ipAddress, username, null);
  }

  /** Same detection with the joining player's UUID so the risk signal is attached to its profile. */
  public static void checkConcurrentFarm(String ipAddress, String username, UUID uuid) {
    if (ipAddress == null || ipAddress.isBlank() || username == null || username.isBlank())
      return;

    long now = System.currentTimeMillis();
    List<Map.Entry<String, Long>> list =
        IP_RECENT_USERNAMES.computeIfAbsent(ipAddress, k -> new java.util.ArrayList<>());

    synchronized (list) {
      // prune stale entries
      list.removeIf(e -> now - e.getValue() > FARM_WINDOW_MS);
      list.add(new java.util.AbstractMap.SimpleEntry<>(username, now));

      // count distinct usernames in the window
      Set<String> distinct = new java.util.HashSet<>();
      for (Map.Entry<String, Long> e : list) distinct.add(e.getKey());
      if (distinct.size() >= FARM_THRESHOLD) {
        AuthCoreServer.LOGGER.warn(
            false,
            "{} | CONCURRENT_FARM detected: {} distinct usernames from {} in {}ms",
            username,
            distinct.size(),
            ipAddress,
            FARM_WINDOW_MS);
        SecurityLog.log(
            "CONCURRENT_FARM",
            ipAddress
                + " had "
                + distinct.size()
                + " distinct usernames within "
                + FARM_WINDOW_MS
                + "ms");
        in.potenfyr.authcore.network.Webhook.sendEmbed(
            "AuthCore - Concurrent Farm",
            "**"
                + ipAddress
                + "** had **"
                + distinct.size()
                + "** distinct usernames within "
                + FARM_WINDOW_MS
                + "ms (bot-farm signal)",
            0xE74C3C);
        if (uuid != null) {
          Profile profile = profile(uuid);
          if (profile != null) profile.addSignal(Signal.CONCURRENT_FARM);
        }
      }
    }

    // Keep attacker-controlled IP cardinality from becoming an unbounded memory sink.
    if (IP_RECENT_USERNAMES.size() > 4096) {
      long cutoff = now - FARM_WINDOW_MS;
      IP_RECENT_USERNAMES.entrySet().removeIf(
          entry -> {
            List<Map.Entry<String, Long>> entries = entry.getValue();
            synchronized (entries) {
              entries.removeIf(e -> e.getValue() < cutoff);
              return entries.isEmpty();
            }
          });
    }
  }

  /**
   * Resolves the connection IP through the version-stable player API. The proxy-forwarding
   * handshake updates the underlying connection address first, so this returns the forwarded
   * client address for a trusted proxy and the socket address otherwise.
   */
  public static String resolveRealIp(ServerPlayer player) {
    if (player == null) return null;
    try {
      return player.getIpAddress();
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  public static Profile profile(UUID uuid) {
    return PROFILES_BY_UUID.get(uuid);
  }

  public static Profile profile(net.minecraft.world.entity.player.Player player) {
    return player == null ? null : PROFILES.get(player);
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

  /** Records a view-rotation (look) packet - genuine players rotate constantly. */
  public static void recordLook(ServerPlayer player) {
    Profile p = profile(player);
    if (p == null) return;
    p.lastLookChangeMs = System.currentTimeMillis();
    p.touch();
  }

  /** Records a look packet with rotation deltas for bot-pattern analysis. */
  public static void recordLook(ServerPlayer player, float xRot, float yRot) {
    Profile p = profile(player);
    if (p == null) return;
    p.lastLookChangeMs = System.currentTimeMillis();
    // Compute delta from previous look (best-effort; stores prev in profile)
    if (p.lastPitch != -9999f && p.lastYaw != -9999f) {
      float dp = Math.abs(xRot - p.lastPitch);
      float dy = Math.abs(yRot - p.lastYaw);
      // Normalize yaw delta for wraparound (yaw wraps at 360)
      if (dy > 180f) dy = 360f - dy;
      p.recordLookDelta(dp, dy);
    }
    p.lastPitch = xRot;
    p.lastYaw = yRot;
    p.touch();
  }

  /** Whether the player rotated their view within the given window (ms). */
  public static boolean hasLookedRecently(ServerPlayer player, long windowMs) {
    Profile p = profile(player);
    return p != null
        && p.lastLookChangeMs > 0
        && (System.currentTimeMillis() - p.lastLookChangeMs) < windowMs;
  }

  public static void recordClick(net.minecraft.world.entity.player.Player player) {
    Profile p = profile(player);
    if (p == null) return;
    p.touch();
    p.recordActionTimestamp();
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
    p.recordActionTimestamp();
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
    in.potenfyr.authcore.network.AuthInterop.sendCompanion(
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

  /** Clears all pending companion challenges (called when the attestation key rotates). */
  public static void invalidateAllPendingChallenges() {
    int cleared = 0;
    for (Profile p : PROFILES.values()) {
      if (p.pendingNonce != null) {
        p.pendingNonce = null;
        p.attestationOk = false;
        cleared++;
      }
    }
    if (cleared > 0)
      AuthCoreServer.LOGGER.info(
          true, "Invalidated {} pending companion challenges (key rotation).", cleared);
  }

  /** HMAC-SHA256 over the payload using the per-server attestation key. */
  public static String hmac(String payload) {
    try {
      String key = in.potenfyr.authcore.security.AttestationKeyManager.get();
      if (key == null || key.isBlank()) {
        AuthCoreServer.LOGGER.warn(false, "HMAC called before attestation key was loaded");
        return "";
      }
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
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
    if (p == null || player == null || player.connection == null) return;
    User user = User.getUser(p.username, p.uuid);
    if (user == null || user.connection != player.connection) return;

    // Network-wide SSO: a remote server holds an active login for this player. Trust it
    // when the companion presents the matching ticket token (or when trustVanilla is set).
    if (AuthCoreServer.config.session.sso.enabled && user.isRegistered.get()) {
      boolean remoteTrusted = false;
      if (p.sessionTokenClaim != null && in.potenfyr.authcore.network.RedisManager.verifySso(p.uuid, p.sessionTokenClaim)) {
        remoteTrusted = true;
      } else if (AuthCoreServer.config.session.sso.trustVanilla
          && in.potenfyr.authcore.network.RedisManager.hasSso(p.uuid)) {
        remoteTrusted = true;
      }
      if (remoteTrusted && !user.isAuthenticated.get()) {
        AuthCoreServer.LOGGER.info(
            true, "{} authenticated via network SSO (Redis)", p.username);
        in.potenfyr.authcore.security.SecurityLog.log("SSO_LOGIN", p.username + " | trusted from the network");
        in.potenfyr.authcore.network.RedisManager.publishEvent(
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
        // Auth intelligence: session-token replay from a different IP.
        in.potenfyr.authcore.security.AuthIntelligence.recordSessionClaim(user, player.getIpAddress());
        AuthCoreServer.LOGGER.debug(
            true, "{} resumed session with a valid companion session token", p.username);
      } else {
        p.addSignal(Signal.SESSION_TOKEN_MISSING);
        // Only revoke the session when the player is STILL unauthenticated in the lobby. An
        // already-authenticated player (premium auto-login, manual login this join) must never
        // be kicked over a stale/absent companion token (e.g. the payload channel is disabled
        // or the client never received the rotated token) - the session was not resumed via
        // the token claim, so there is nothing to revoke.
        if (user.isInLobby.get() && user.isActiveSession.get()) {
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
        int ghostWarnSec = Math.max(0, cfg.ghostKickAfterSec - 5);
        if (!p.ghostArmed && idle > ghostWarnSec * 1000L) {
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

      // ---- look-pattern bot detection ------------------------------------------
      // Periodically check look-change regularity (bots have machine-precision camera movement).
      if (cfg.lookPatternCheckIntervalSec > 0 && inLobby) {
        long checkIntervalMs = cfg.lookPatternCheckIntervalSec * 1000L;
        if (now - p.lastActivityMs < 5000L // only check recently active players
            && p.lookDeltas.size() >= 5
            && (p.lastLookCheckMs == 0L || now - p.lastLookCheckMs > checkIntervalMs)) {
          p.lastLookCheckMs = now;
          double score = p.lookPatternScore();
          int lookScore = (int) Math.round(score * 20);
          if (lookScore >= 10) {
            p.addSignal(Signal.LOOK_PATTERN);
            AuthCoreServer.LOGGER.debug(
                false,
                "{} | look-pattern score {}/20 (cv-based) - bot signal added",
                p.username,
                lookScore);
          }
        }
      }

      // ---- decision matrix -----------------------------------------------------
      if (inLobby) {
        if (p.risk >= cfg.riskKickThreshold) {
          AuthCoreServer.LOGGER.debug(
              false,
              "{} | risk {} >= kick threshold {} - kicking from lobby",
              p.username,
              p.risk,
              cfg.riskKickThreshold);
          SecurityLog.log(
              "RISK_KICK",
              p.username + " kicked at risk " + p.risk + " (signals: " + p.signals + ")");
          in.potenfyr.authcore.network.Webhook.sendEmbed(
              "AuthCore - Risk Kick",
              "**" + p.username + "** kicked at risk **" + p.risk + "** - signals: `" + p.signals + "`",
              0xE74C3C);
          AuthCoreServer.LOGGER.toKick(false, player.connection, AuthCoreServer.messages.promptUserAuthenticationExpiredTimeout);
          continue;
        }
        if (p.risk >= cfg.riskAlertThreshold && !p.notified) {
          p.notified = true;
          AuthCoreServer.LOGGER.debug(
              false,
              "{} | risk {} >= alert threshold {} - alert emitted",
              p.username,
              p.risk,
              cfg.riskAlertThreshold);
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

    // Build a normalized-name index from the cached DB list (5-min TTL).
    // With 100k+ users the index makes this O(L) per join instead of O(N*L).
    java.util.Set<String> registeredNormalized = CONFUSABLE_INDEX.computeIfAbsent(
        "index", k -> java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>()));
    if (registeredNormalized.isEmpty() || CONFUSABLE_INDEX_AT + 5 * 60_000L < System.currentTimeMillis()) {
      long now = System.currentTimeMillis();
      java.util.Set<String> fresh = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
      for (String registered : in.potenfyr.authcore.util.Database.getRegisteredNames()) {
        if (registered == null) continue;
        fresh.add(normalizeName(registered));
      }
      registeredNormalized.clear();
      registeredNormalized.addAll(fresh);
      CONFUSABLE_INDEX_AT = now;
    }

    // A normalized exact match catches Unicode substitutions (for example Cyrillic "е"
    // in place of ASCII "e"). Then generate all strings within edit distance 1 to catch
    // omitted/changed characters. Both paths are O(L), not O(N * L).
    if (registeredNormalized.contains(normalized)) {
      reportConfusableName(username);
      return;
    }
    for (String candidate : neighborsWithinDistance1(normalized)) {
      if (registeredNormalized.contains(candidate)) {
        reportConfusableName(username);
        return;
      }
    }
  }

  private static void reportConfusableName(String username) {
    SecurityLog.log(
        "NAME_CONFUSABLE",
        username + " looks like a registered confusable name (impersonation risk)");
    in.potenfyr.authcore.network.Webhook.sendEmbed(
        "AuthCore - Confusable Name",
        "**" + username + "** is confusable with a registered account.",
        0xE74C3C);
  }

  /** All strings within Levenshtein distance 1 of s (deletions + substitutions + insertions). */
  private static java.util.List<String> neighborsWithinDistance1(String s) {
    java.util.List<String> out = new java.util.ArrayList<>();
    if (s.isEmpty()) return out;
    int n = s.length();
    // deletions
    for (int i = 0; i < n; i++)
      out.add(s.substring(0, i) + s.substring(i + 1));
    // substitutions
    for (int i = 0; i < n; i++) {
      char c = s.charAt(i);
      for (char r = 'a'; r <= 'z'; r++) {
        if (r != c) out.add(s.substring(0, i) + r + s.substring(i + 1));
      }
      for (char r = '0'; r <= '9'; r++) {
        if (r != c) out.add(s.substring(0, i) + r + s.substring(i + 1));
      }
    }
    // insertions
    for (int i = 0; i <= n; i++) {
      for (char r = 'a'; r <= 'z'; r++)
        out.add(s.substring(0, i) + r + s.substring(i));
      for (char r = '0'; r <= '9'; r++)
        out.add(s.substring(0, i) + r + s.substring(i));
    }
    return out;
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
