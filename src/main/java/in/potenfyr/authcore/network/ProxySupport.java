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
      } else if (sameAddress(ip, value)) {
        return true;
      }
    }
    return false;
  }

  /**
   * CIDR match for IPv4 ({@code a.b.c.d/n}, n &le; 32) and IPv6 ({@code fe80::/10}, n &le; 128).
   *
   * <p>Both sides are parsed as address literals only (validated by {@link #isValidIp} first, so
   * no DNS lookup can ever happen). An IPv4-mapped IPv6 address ({@code ::ffff:1.2.3.4}) is
   * matched as its IPv4 form, so a dual-stack listener reporting mapped addresses still hits
   * IPv4 rules. Address families never cross-match otherwise.
   */
  public static boolean cidrMatches(String ip, String cidr) {
    try {
      if (ip == null || cidr == null) return false;
      String[] parts = cidr.trim().split("/", -1);
      if (parts.length != 2 || !parts[1].matches("[0-9]{1,3}")) return false;
      int prefix = Integer.parseInt(parts[1]);
      byte[] ipBytes = addressBytes(ip.trim());
      byte[] netBytes = addressBytes(parts[0]);
      if (ipBytes == null || netBytes == null || ipBytes.length != netBytes.length) return false;
      if (prefix > ipBytes.length * 8) return false;
      int fullBytes = prefix / 8;
      for (int i = 0; i < fullBytes; i++) if (ipBytes[i] != netBytes[i]) return false;
      int rem = prefix % 8;
      if (rem == 0) return true;
      int mask = (0xFF << (8 - rem)) & 0xFF;
      return (ipBytes[fullBytes] & mask) == (netBytes[fullBytes] & mask);
    } catch (RuntimeException err) {
      return false;
    }
  }

  /**
   * Compares two IP literals by value, so {@code ::1}, {@code 0:0:0:0:0:0:0:1} and
   * {@code ::ffff:127.0.0.1} vs {@code 127.0.0.1} all compare as the same address. Falls back to
   * a plain string compare when either side is not a valid literal.
   */
  public static boolean sameAddress(String a, String b) {
    if (a == null || b == null) return false;
    if (a.equals(b)) return true;
    byte[] x = addressBytes(a.trim());
    byte[] y = addressBytes(b.trim());
    return x != null && y != null && java.util.Arrays.equals(x, y);
  }

  /**
   * Raw address bytes of an IPv4/IPv6 literal (4 or 16 bytes), with IPv4-mapped IPv6 collapsed
   * to 4 bytes; {@code null} for anything that is not a literal. Never resolves hostnames.
   */
  private static byte[] addressBytes(String ip) {
    if (ip == null) return null;
    if (ip.startsWith("[") && ip.endsWith("]")) ip = ip.substring(1, ip.length() - 1);
    if (!isValidIp(ip)) return null;
    try {
      byte[] bytes = java.net.InetAddress.getByName(ip).getAddress();
      if (bytes.length == 16) {
        boolean mapped = true;
        for (int i = 0; i < 10 && mapped; i++) if (bytes[i] != 0) mapped = false;
        if (mapped && (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF)
          return java.util.Arrays.copyOfRange(bytes, 12, 16);
      }
      return bytes;
    } catch (java.net.UnknownHostException | RuntimeException err) {
      return null;
    }
  }
}
