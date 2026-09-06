package in.potenfyr.authcore.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import in.potenfyr.authcore.AuthCoreServer;

/**
 * Per-server companion attestation key manager.
 *
 * <p>Generates a random 32-byte key on first boot, persists it to the config directory,
 * and loads it on subsequent starts. This replaces the hardcoded public constant so each
 * server has a unique key that cannot be extracted from the jar.
 *
 * <p>Call {@link #rotate()} to invalidate all pending challenges (e.g. on config reload
 * or admin request).
 */
public final class AttestationKeyManager {

  private static volatile String currentKey;
  private static volatile Path keyFilePath;
  private static final SecureRandom RNG = new SecureRandom();

  private AttestationKeyManager() {}

  /**
   * Loads the key from disk, generating a new one if the file does not exist yet.
   * Must be called after {@link AuthCoreServer#configPath} is set.
   */
  public static synchronized void ensureLoaded() {
    if (currentKey != null) return;
    if (AuthCoreServer.configPath == null) return;

    keyFilePath = AuthCoreServer.configPath.resolve("attestation.key");
    try {
      if (Files.exists(keyFilePath)) {
        currentKey = Files.readString(keyFilePath, StandardCharsets.UTF_8).trim();
        if (currentKey.isBlank() || currentKey.length() < 32)
          throw new IOException("key too short: " + currentKey.length() + " chars");
      } else {
        currentKey = generateAndSave();
      }
    } catch (IOException err) {
      AuthCoreServer.LOGGER.warn(false, "Attestation key load failed, generating fresh key:", err);
      currentKey = generateAndSave();
    }
  }

  /**
   * Rotates the key: generates a new random key, persists it, and invalidates all pending
   * companion challenges so clients must re-attest with the new key.
   */
  public static synchronized void rotate() {
    if (AuthCoreServer.configPath == null) return;
    keyFilePath = AuthCoreServer.configPath.resolve("attestation.key");
    currentKey = generateAndSave();
    // Invalidate pending challenges - old HMACs cannot be verified with the new key.
    in.potenfyr.authcore.security.ClientGuard.invalidateAllPendingChallenges();
    AuthCoreServer.LOGGER.info(true, "Attestation key rotated - all pending challenges invalidated.");
  }

  /** Returns the current key, or {@code null} if not yet loaded. */
  public static String get() {
    return currentKey;
  }

  // ------------------------------------------------------------------ internals

  private static String generateAndSave() {
    byte[] raw = new byte[32];
    RNG.nextBytes(raw);
    String hex = bytesToHex(raw);
    try {
      Files.createDirectories(keyFilePath.getParent());
      Files.writeString(keyFilePath, hex, StandardCharsets.UTF_8);
    } catch (IOException err) {
      AuthCoreServer.LOGGER.warn(false, "Failed to persist attestation key:", err);
    }
    return hex;
  }

  private static String bytesToHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes)
      sb.append(String.format("%02x", b & 0xFF));
    return sb.toString();
  }
}
