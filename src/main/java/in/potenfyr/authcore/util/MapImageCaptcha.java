package in.potenfyr.authcore.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Map-captcha challenges: a random code rendered as a map image (see {@link MapImageContent#text})
 * plus the server-side verification state.
 *
 * <p>Designed so the map feature cannot be abused as an oracle or a memory/log leak:
 * <ul>
 *   <li>Codes come from {@link SecureRandom} over an unambiguous alphabet (no {@code I}, {@code O},
 *       {@code 0}, {@code 1}).
 *   <li>Only a SHA-256 <b>digest</b> of the code is kept in memory - a heap dump, a log line or an
 *       exception never contains the code itself, and {@link #show} never logs it.
 *   <li>Verification is single-use, expires after {@link #TTL_MS}, allows only
 *       {@link #MAX_ATTEMPTS} guesses per challenge and compares digests in constant time
 *       ({@link MessageDigest#isEqual}).
 *   <li>State is bounded ({@link #MAX_CHALLENGES}); expired entries are pruned on every issue so a
 *       hostile or buggy caller cannot grow the map without limit.
 *   <li>Input is normalised (trim, upper-case, whitespace removed) and rejected unless it is exactly
 *       the code length over the allowed alphabet - no unbounded or exotic input reaches the
 *       comparison.
 * </ul>
 */
public final class MapImageCaptcha {

  /** Code length. */
  public static final int CODE_LENGTH = 6;
  /** Guesses allowed per challenge before it is burned. */
  public static final int MAX_ATTEMPTS = 3;
  /** Challenge lifetime in milliseconds. */
  public static final long TTL_MS = 120_000L;
  /** Upper bound on tracked challenges. */
  public static final int MAX_CHALLENGES = 4096;

  private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
  private static final String ALPHABET_STRING = new String(ALPHABET);
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Map<UUID, Challenge> CHALLENGES = new ConcurrentHashMap<>();

  private record Challenge(byte[] digest, long issuedAt, int attempts) {}

  private MapImageCaptcha() {}

  /** Generates a fresh code. Never logged by this class. */
  public static String newCode() {
    char[] code = new char[CODE_LENGTH];
    for (int i = 0; i < code.length; i++) code[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
    return new String(code);
  }

  /** The 128x128 image for a code, ready for {@link MapImageService#show}. */
  public static int[] image(String code) {
    return MapImageContent.text(code);
  }

  /** The characters a valid code is made of. */
  public static String alphabet() {
    return ALPHABET_STRING;
  }

  /** Starts (or replaces) the challenge for a player. Returns false for unusable codes. */
  static boolean track(UUID playerId, String code) {
    if (playerId == null) return false;
    String normalised = normalise(code);
    if (normalised == null) return false;
    long now = System.currentTimeMillis();
    prune(now);
    CHALLENGES.put(playerId, new Challenge(digest(normalised), now, 0));
    return true;
  }

  /** Drops a player's pending challenge (map taken back, logout, ...). */
  static void discard(UUID playerId) {
    if (playerId != null) CHALLENGES.remove(playerId);
  }

  /** Whether the player currently has a pending, unexpired challenge. */
  public static boolean pending(UUID playerId) {
    return playerId != null && CHALLENGES.containsKey(playerId);
  }

  /** Number of tracked challenges (diagnostics / self-test). */
  public static int pendingCount() {
    return CHALLENGES.size();
  }

  /** Verifies a player's answer against their pending challenge. */
  public static boolean verify(UUID playerId, String input) {
    return verify(playerId, input, System.currentTimeMillis());
  }

  /** Verifies with an explicit clock (self-test). */
  static boolean verify(UUID playerId, String input, long now) {
    if (playerId == null) return false;
    Challenge challenge = CHALLENGES.get(playerId);
    if (challenge == null) return false;
    if (now - challenge.issuedAt() > TTL_MS || now < challenge.issuedAt()) {
      CHALLENGES.remove(playerId);
      return false;
    }
    String normalised = normalise(input);
    if (normalised == null) {
      // Invalid input counts as a failed guess: it must not be a free retry oracle.
      return registerFailure(playerId, challenge);
    }
    boolean match = MessageDigest.isEqual(digest(normalised), challenge.digest());
    if (match) {
      CHALLENGES.remove(playerId); // single use
      return true;
    }
    return registerFailure(playerId, challenge);
  }

  /** Stores a challenge for the self-test with a synthetic issue time. */
  static void trackForTest(UUID playerId, String code, long issuedAt) {
    String normalised = normalise(code);
    if (playerId == null || normalised == null) return;
    CHALLENGES.put(playerId, new Challenge(digest(normalised), issuedAt, 0));
  }

  /** Clears all state (self-test / reload). */
  static void resetForTest() {
    CHALLENGES.clear();
  }

  // ------------------------------------------------------------------ internals

  private static boolean registerFailure(UUID playerId, Challenge challenge) {
    int attempts = challenge.attempts() + 1;
    if (attempts >= MAX_ATTEMPTS) {
      CHALLENGES.remove(playerId);
    } else {
      CHALLENGES.put(playerId, new Challenge(challenge.digest(), challenge.issuedAt(), attempts));
    }
    return false;
  }

  private static void prune(long now) {
    Iterator<Map.Entry<UUID, Challenge>> iterator = CHALLENGES.entrySet().iterator();
    while (iterator.hasNext()) {
      Challenge challenge = iterator.next().getValue();
      if (now - challenge.issuedAt() > TTL_MS || now < challenge.issuedAt()) iterator.remove();
    }
    if (CHALLENGES.size() < MAX_CHALLENGES) return;
    // Still full: drop arbitrary entries (the oldest by issue time first, then anything).
    long cutoff = now - TTL_MS / 2;
    iterator = CHALLENGES.entrySet().iterator();
    while (iterator.hasNext() && CHALLENGES.size() >= MAX_CHALLENGES) {
      if (iterator.next().getValue().issuedAt() <= cutoff) iterator.remove();
    }
    while (CHALLENGES.size() >= MAX_CHALLENGES && !CHALLENGES.isEmpty()) {
      CHALLENGES.remove(CHALLENGES.keySet().iterator().next());
    }
  }

  private static String normalise(String input) {
    if (input == null) return null;
    StringBuilder builder = new StringBuilder(input.length());
    for (int i = 0; i < input.length(); i++) {
      char character = input.charAt(i);
      if (Character.isWhitespace(character) || character == '-' || character == '_') continue;
      builder.append(Character.toUpperCase(character));
    }
    String normalised = builder.toString();
    if (normalised.length() != CODE_LENGTH) return null;
    for (int i = 0; i < normalised.length(); i++) {
      if (ALPHABET_STRING.indexOf(normalised.charAt(i)) < 0) return null;
    }
    return normalised;
  }

  private static byte[] digest(String code) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(code.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException impossible) {
      // SHA-256 is mandatory in every Java platform implementation.
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }
}
