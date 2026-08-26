package net.ded3ec.network;

import net.ded3ec.AuthCoreServer;

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
   * <p>ONLY the NUL-separated forwarding payload ({@code ip\0uuid\0properties}) is accepted.
   * The old bare-IP fallback was a spoofing hole: the handshake address is CLIENT-CONTROLLED,
   * so a direct (unproxied) modified client could claim any IP and defeat rate limits, GeoIP,
   * login intelligence and IP rules. Real proxies (BungeeCord / Velocity legacy) always send
   * the NUL payload, so a plain hostname/IP handshake is simply ignored here.
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

    // No NUL payload -> not proxied forwarding. Never trust a bare client-supplied address.
    return null;
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

  /**
   * Whether forwarded handshake data from this SOCKET source address may be trusted.
   *
   * <p>Forwarded payloads are client-controlled strings: without a source check, any
   * modified client could claim an arbitrary IP and defeat IP rules, rate limits, GeoIP
   * and login intelligence. A configured {@code trusted-proxies} list makes the rewrite
   * strict: only listed addresses (exact IP or IPv4 CIDR range) are honored. An EMPTY
   * list keeps the legacy permissive behavior (documented as not recommended) so existing
   * proxy setups do not silently break on upgrade.
   */
  public static boolean isTrustedProxySource(String socketIp) {
    if (AuthCoreServer.config == null) return true; // config not loaded yet - legacy path
    java.util.List<String> trusted = AuthCoreServer.config.session.proxySupport.trustedProxies;
    if (trusted == null || trusted.isEmpty()) return true; // legacy behavior + boot warning
    if (socketIp == null || socketIp.isBlank()) return false;

    // Normalize: strip a zone index and brackets ("[::1]:25565" style inputs).
    String ip = socketIp.trim();
    if (ip.startsWith("[")) ip = ip.substring(1, ip.indexOf(']') > 0 ? ip.indexOf(']') : ip.length());
    int zone = ip.indexOf('%');
    if (zone > 0) ip = ip.substring(0, zone);

    for (String entry : trusted) {
      if (entry == null || entry.isBlank()) continue;
      String value = entry.trim();
      int slash = value.indexOf('/');
      if (slash > 0 && isValidIp(value.substring(0, slash))) {
        if (cidrMatches(ip, value)) return true;
      } else if (ip.equals(value)) {
        return true;
      }
    }
    return false;
  }

  /** IPv4 CIDR match ({@code a.b.c.d/n}); falls back to exact string compare otherwise. */
  public static boolean cidrMatches(String ip, String cidr) {
    try {
      String[] parts = cidr.split("/");
      int prefix = Integer.parseInt(parts[1]);
      long ipBits = ipv4ToLong(ip);
      if (ipBits < 0) return false; // IPv6 against an IPv4 CIDR -> exact match only
      long netBits = ipv4ToLong(parts[0]);
      int shift = 32 - Math.min(prefix, 32);
      return (ipBits >> shift) == (netBits >> shift);
    } catch (RuntimeException err) {
      return false;
    }
  }

  /** Packs an IPv4 string into 32 bits; {@code -1} when the input is not IPv4. */
  private static long ipv4ToLong(String ip) {
    if (ip == null || ip.contains(":")) return -1;
    String[] parts = ip.split("\\.");
    if (parts.length != 4) return -1;
    long result = 0;
    for (String part : parts) {
      int value;
      try {
        value = Integer.parseInt(part);
      } catch (NumberFormatException err) {
        return -1;
      }
      if (value < 0 || value > 255) return -1;
      result = (result << 8) | value;
    }
    return result;
  }
}
