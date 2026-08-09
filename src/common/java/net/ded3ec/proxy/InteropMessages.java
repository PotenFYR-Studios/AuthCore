package net.ded3ec.proxy;

import java.nio.charset.StandardCharsets;

/**
 * Parses AuthCore interop messages received on the proxy.
 *
 * <p>Backend servers running AuthCore broadcast {@code AUTH_CHANGED|<uuid>|<username>|<1|0>}
 * on the {@code authcore:auth} channel (and on {@code bungeecord:main} subchannel
 * {@code AuthCore}). The proxy plugin consumes both and keeps {@link SessionCache} in sync.
 */
public final class InteropMessages {

  public static final String CHANNEL = "authcore:auth";
  public static final String BUNGEE_SUBCHANNEL = "AuthCore";
  public static final String BUNGEE_CHANNEL = "bungeecord:main";

  private InteropMessages() {}

  /**
   * Attempts to parse an interop message from raw payload bytes.
   *
   * @param channel the channel the message arrived on
   * @param data the payload bytes
   * @return {@code true} when a valid AUTH_CHANGED message was parsed and cached
   */
  public static boolean handle(String channel, byte[] data) {
    if (data == null) return false;
    String text = new String(data, StandardCharsets.US_ASCII).trim();

    // bungeecord:main payload = "AuthCore\0AUTH_CHANGED|..."
    if (BUNGEE_CHANNEL.equals(channel)) {
      int nul = text.indexOf('\u0000');
      if (nul < 0) return false;
      String sub = text.substring(0, nul);
      if (!BUNGEE_SUBCHANNEL.equals(sub)) return false;
      text = text.substring(nul + 1);
    } else if (!CHANNEL.equals(channel)) {
      return false;
    }

    return apply(text);
  }

  private static boolean apply(String line) {
    if (!line.startsWith("AUTH_CHANGED|")) return false;
    String[] parts = line.split("\\|");
    if (parts.length < 4) return false;
    String uuid = parts[1];
    boolean authenticated = "1".equals(parts[3]);
    SessionCache.update(uuid, authenticated);
    return true;
  }
}
