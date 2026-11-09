package in.potenfyr.authcore.proxy;

import java.nio.charset.StandardCharsets;

/**
 * Parses AuthCore interop messages received on the proxy.
 *
 * <p>Backend servers running AuthCore broadcast {@code AUTH_CHANGED|<uuid>|<username>|<1|0>}
 * on the {@code authcore:auth} channel (and on {@code bungeecord:main} subchannel
 * {@code AuthCore}). The proxy plugin consumes both and keeps {@link SessionCache} in sync.
 *
 * <p>Only a well-formed message on one of those two channels is accepted: the payload decides
 * whether a player may move through the proxy, so a malformed or foreign message must never
 * touch the cache. {@code AUTH_CHANGED|...|0} clears the session (logout / disconnect).
 */
public final class InteropMessages {

  public static final String CHANNEL = "authcore:auth";
  public static final String BUNGEE_SUBCHANNEL = "AuthCore";
  public static final String BUNGEE_CHANNEL = "bungeecord:main";

  private InteropMessages() {}

  /** Whether {@code channel} is one of the channels this bridge reads. */
  public static boolean isInteropChannel(String channel) {
    return CHANNEL.equals(channel) || BUNGEE_CHANNEL.equals(channel);
  }

  /**
   * Attempts to parse an interop message from raw payload bytes.
   *
   * @param channel the channel the message arrived on
   * @param data the payload bytes
   * @return {@code true} when a valid AUTH_CHANGED message was parsed and cached
   */
  public static boolean handle(String channel, byte[] data) {
    if (!isInteropChannel(channel) || data == null || data.length == 0) return false;

    int offset = 0;
    if (BUNGEE_CHANNEL.equals(channel)) {
      // bungeecord:main payload = "AuthCore\0AUTH_CHANGED|..." - matched at the byte level so
      // no charset decode or trim can eat the separator.
      byte[] sub = BUNGEE_SUBCHANNEL.getBytes(StandardCharsets.US_ASCII);
      if (data.length <= sub.length + 1) return false;
      for (int i = 0; i < sub.length; i++) if (data[i] != sub[i]) return false;
      if (data[sub.length] != 0) return false;
      offset = sub.length + 1;
    }

    return apply(new String(data, offset, data.length - offset, StandardCharsets.US_ASCII));
  }

  private static boolean apply(String line) {
    // NUL padding at the end of a plugin-message buffer must not defeat the match.
    int end = line.length();
    while (end > 0 && line.charAt(end - 1) == '\u0000') end--;
    if (end == 0) return false;
    line = line.substring(0, end);

    if (!line.startsWith("AUTH_CHANGED|")) return false;
    String[] parts = line.split("\\|", -1);
    if (parts.length != 4) return false;

    java.util.UUID uuid;
    try {
      uuid = java.util.UUID.fromString(parts[1]);
    } catch (IllegalArgumentException malformed) {
      return false;
    }

    // The cache is keyed by the canonical uuid string the gate queries
    // (Player#getUniqueId().toString()), so store it in exactly that form.
    SessionCache.update(uuid.toString(), "1".equals(parts[3]));
    return true;
  }
}
