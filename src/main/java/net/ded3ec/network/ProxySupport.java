package net.ded3ec.network;

/**
 * Parses proxy IP forwarding payloads.
 *
 * <p>BungeeCord (and Velocity in legacy mode) deliver the real client IP inside the handshake
 * address field using NUL separators: {@code <real-ip>\u0000<uuid>\u0000<properties>}. AuthCore
 * rewrites the connection address to the real IP so GeoIP, sessions, rate limits and login
 * intelligence all work behind a proxy network.
 */
public final class ProxySupport {

  private ProxySupport() {}

  /**
   * Extracts the forwarded real client IP from a handshake address string.
   *
   * @param handshakeAddress the raw {@code address()} value of the handshake packet
   * @return the real client IP, or {@code null} when no forwarding payload is present
   */
  public static String parseForwardedIp(String handshakeAddress) {
    if (handshakeAddress == null || handshakeAddress.isBlank()) return null;

    // BungeeCord / Velocity-legacy format: ip\0uuid\0properties
    int nul = handshakeAddress.indexOf('\u0000');
    if (nul > 0) {
      String ip = handshakeAddress.substring(0, nul);
      return isValidIp(ip) ? ip : null;
    }

    // Some setups forward a bare IP without NUL separators
    String trimmed = handshakeAddress.trim();
    return isValidIp(trimmed) ? trimmed : null;
  }

  /** Basic sanity check that a string looks like an IPv4/IPv6 address. */
  public static boolean isValidIp(String ip) {
    if (ip == null || ip.isBlank()) return false;

    if (ip.contains(":")) {
      // IPv6 (may include a zone index)
      String core = ip.contains("%") ? ip.substring(0, ip.indexOf('%')) : ip;
      return core.matches("^[0-9a-fA-F:]+$") && core.contains(":");
    }

    String[] parts = ip.split("\\.");
    if (parts.length != 4) return false;
    for (String part : parts) {
      if (part.isEmpty() || part.length() > 3) return false;
      if (!part.chars().allMatch(Character::isDigit)) return false;
      int value = Integer.parseInt(part);
      if (value < 0 || value > 255) return false;
    }
    return true;
  }

  /**
   * Detects which forwarding protocol a handshake address uses.
   *
   * @return {@code "bungeecord"} for the BungeeCord / Velocity-legacy NUL-separated format,
   *     {@code "bare"} for a plain forwarded IP, {@code "none"} when no forwarding is present
   */
  public static String detectProtocol(String handshakeAddress) {
    if (handshakeAddress == null || handshakeAddress.isBlank()) return "none";
    if (handshakeAddress.indexOf('\u0000') > 0) return "bungeecord";
    return isValidIp(handshakeAddress.trim()) ? "bare" : "none";
  }

  /**
   * Verifies a Velocity modern-forwarding payload (HMAC-SHA256 of the data with the shared
   * forwarding secret). Useful for proxy-integration code and future login-phase support.
   *
   * @param payload the raw payload bytes (first 32 bytes are the HMAC, the rest is data)
   * @param secret the shared forwarding secret bytes (UTF-8 of the configured secret)
   * @return {@code true} when the HMAC is valid
   */
  public static boolean verifyVelocityHmac(byte[] payload, byte[] secret) {
    if (payload == null || secret == null || payload.length <= 32) return false;
    try {
      javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
      mac.init(new javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256"));
      byte[] expected = new byte[32];
      System.arraycopy(payload, 0, expected, 0, 32);
      byte[] actual = mac.doFinal(java.util.Arrays.copyOfRange(payload, 32, payload.length));
      return java.security.MessageDigest.isEqual(expected, actual);
    } catch (Exception err) {
      return false;
    }
  }
}
