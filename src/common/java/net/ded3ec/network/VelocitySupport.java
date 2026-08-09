package net.ded3ec.network;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Velocity MODERN forwarding support for the Fabric server (not a proxy plugin).
 *
 * <p>With {@code player-info-forwarding-mode = "modern"} in {@code velocity.toml}, the proxy
 * sends a login-phase plugin message ({@code velocity:player_info}) containing the real client
 * identity (UUID, username, skin properties) signed with the shared forwarding secret. This
 * class verifies the HMAC-SHA256 and parses the payload so the backend can use the real
 * identity instead of the offline UUID.
 *
 * <p>Format (all big-endian): {@code hmac(32 bytes) | version(int) | uuid(16) | username(utf) |
 * propertyCount(int) | name(utf) value(utf) [hasSignature(bool) signature(utf)] * n}.
 *
 * <p>NOTE: Velocity modern forwards IDENTITY only - not the client IP (use legacy mode if
 * IP-based features such as GeoIP are required).
 */
public final class VelocitySupport {

  /** The login-phase plugin message channel used by Velocity modern forwarding. */
  public static final String PLAYER_INFO_CHANNEL = "velocity:player_info";

  private VelocitySupport() {}

  /** Parsed identity from a Velocity modern forwarding payload. */
  public static final class PlayerInfo {
    public final UUID uuid;
    public final String username;

    PlayerInfo(UUID uuid, String username) {
      this.uuid = uuid;
      this.username = username;
    }
  }

  /**
   * Verifies and parses a {@code velocity:player_info} payload.
   *
   * @param payload the raw payload bytes (first 32 bytes are the HMAC)
   * @param secret the shared forwarding secret (the value of {@code forwarding-secret} in
   *     {@code velocity.toml})
   * @return the forwarded identity, or {@code null} when the HMAC is invalid or the payload is
   *     malformed
   */
  public static PlayerInfo parsePlayerInfo(byte[] payload, String secret) {
    if (payload == null || secret == null || secret.isBlank()) return null;
    byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);

    // HMAC-SHA256 verification (first 32 bytes)
    if (!net.ded3ec.network.ProxySupport.verifyVelocityHmac(payload, secretBytes)) return null;

    try {
      java.io.DataInputStream in =
          new java.io.DataInputStream(new java.io.ByteArrayInputStream(payload, 32, payload.length - 32));

      int version = in.readInt();
      if (version != 1) return null;

      long most = in.readLong();
      long least = in.readLong();
      UUID uuid = new UUID(most, least);

      String username = in.readUTF();

      // Skin/texture properties (parsed and discarded - identity is what we need)
      int propertyCount = in.readInt();
      if (propertyCount < 0 || propertyCount > 32) return null;
      for (int i = 0; i < propertyCount; i++) {
        in.readUTF(); // name
        in.readUTF(); // value
        if (in.readBoolean()) in.readUTF(); // signature
      }

      return new PlayerInfo(uuid, username);
    } catch (java.io.IOException err) {
      return null;
    }
  }
}
