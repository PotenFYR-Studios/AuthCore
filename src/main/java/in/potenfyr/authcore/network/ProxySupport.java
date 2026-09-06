package in.potenfyr.authcore.network;

import in.potenfyr.authcore.AuthCoreServer;

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
    if (ip == null || ip.isBlank() || ip.length() > 45) return false;

    if (ip.contains(":")) {
      // A literal-only character check prevents DNS resolution. Forwarded addresses must
      // not contain ports, brackets or interface-local zone identifiers.
      if (!ip.matches("[0-9a-fA-F:.]+")) return false;
      try {
        java.net.InetAddress.getByName(ip);
        return true;
      } catch (java.net.UnknownHostException err) {
        return false;
      }
    }

    String[] parts = ip.split("\\.", -1);
    if (parts.length != 4) return false;
    for (String part : parts) {
      if (part.isEmpty() || part.length() > 3) return false;
      if (!part.matches("[0-9]+")) return false;
      int value = Integer.parseInt(part);
      if (value < 0 || value > 255) return false;
    }
    return true;
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
   * strict: only listed addresses (exact IP or IPv4 CIDR range) are honored. An empty
   * list denies forwarding, including during startup and configuration reload.
   */
  public static boolean isTrustedProxySource(String socketIp) {
    var config = AuthCoreServer.config;
    if (config == null || config.session == null || config.session.proxySupport == null
        || !config.session.proxySupport.enabled) return false;
    java.util.List<String> trusted = config.session.proxySupport.trustedProxies;
    if (trusted == null || trusted.isEmpty()) return false;
    if (socketIp == null || socketIp.isBlank()) return false;

    // Normalize: strip a zone index and brackets ("[::1]:25565" style inputs).
    String ip = socketIp.trim();
    if (ip.startsWith("[")) ip = ip.substring(1, ip.indexOf(']') > 0 ? ip.indexOf(']') : ip.length());
    int zone = ip.indexOf('%');
    if (zone > 0) ip = ip.substring(0, zone);
    if (!isValidIp(ip)) return false;

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
      String[] parts = cidr.split("/", -1);
      if (parts.length != 2 || !parts[1].matches("[0-9]{1,2}")) return false;
      int prefix = Integer.parseInt(parts[1]);
      if (prefix > 32) return false;
      long ipBits = ipv4ToLong(ip);
      long netBits = ipv4ToLong(parts[0]);
      if (ipBits < 0 || netBits < 0) return false;
      int shift = 32 - prefix;
      return (ipBits >> shift) == (netBits >> shift);
    } catch (RuntimeException err) {
      return false;
    }
  }

  /** Packs an IPv4 string into 32 bits; {@code -1} when the input is not IPv4. */
  private static long ipv4ToLong(String ip) {
    if (!isValidIp(ip) || ip.contains(":")) return -1;
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
