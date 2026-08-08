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
}
