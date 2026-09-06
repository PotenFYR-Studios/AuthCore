package in.potenfyr.authcore.proxy;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Minimal zero-dependency RESP client for the proxy plugin.
 *
 * <p>Reads only the bounded scalar replies used by AUTH, SELECT, GET, SET and DEL.
 * Malformed replies are transport failures, never missing sessions.
 */
public final class RedisClient implements AutoCloseable {
  private static final int MAX_REPLY_BYTES = 65536;
  private final Socket socket;
  private final BufferedInputStream in;
  private final BufferedOutputStream out;
  private volatile boolean unavailable;

  private RedisClient(Socket socket) throws IOException {
    this.socket = socket;
    this.in = new BufferedInputStream(socket.getInputStream());
    this.out = new BufferedOutputStream(socket.getOutputStream());
  }

  /** Connects and validates optional AUTH/SELECT. Returns null on failure. */
  public static RedisClient connect(String host, int port, String password, int database) {
    Socket socket = new Socket();
    try {
      if (database < 0) throw new IOException("negative database");
      socket.connect(new InetSocketAddress(host, port), 1500);
      socket.setSoTimeout(1500);
      RedisClient client = new RedisClient(socket);
      if (password != null && !password.isBlank()) {
        client.send("AUTH", password);
        client.expectOk();
      }
      if (database > 0) {
        client.send("SELECT", String.valueOf(database));
        client.expectOk();
      }
      return client;
    } catch (IOException | RuntimeException err) {
      try {
        socket.close();
      } catch (IOException ignored) {
        // best-effort cleanup after a failed connect or handshake
      }
      return null;
    }
  }

  /** Returns the stored value or null; isAvailable distinguishes absence from failure. */
  public String get(String key) {
    try {
      send("GET", key);
      // GET must return a bulk string (or bulk nil), not an arbitrary scalar.
      if (in.read() != '$') throw new IOException("invalid GET reply");
      return readBulk();
    } catch (IOException err) {
      fail();
      return null;
    }
  }

  public boolean isAvailable() {
    return !unavailable;
  }

  public void setEx(String key, long ttlSeconds, String value) {
    try {
      if (ttlSeconds <= 0) throw new IOException("invalid TTL");
      send("SET", key, value, "EX", String.valueOf(ttlSeconds));
      expectOk();
    } catch (IOException err) {
      fail();
    }
  }

  public void del(String key) {
    try {
      send("DEL", key);
      if (in.read() != ':' || readNumber() < 0) throw new IOException("invalid DEL reply");
    } catch (IOException err) {
      fail();
    }
  }

  private void fail() {
    unavailable = true;
    close();
  }

  private void send(String... args) throws IOException {
    if (unavailable) throw new IOException("connection unavailable");
    StringBuilder cmd = new StringBuilder("*").append(args.length).append("\r\n");
    for (String arg : args) {
      if (arg == null) throw new IOException("null argument");
      byte[] data = arg.getBytes(StandardCharsets.UTF_8);
      cmd.append('$').append(data.length).append("\r\n").append(arg).append("\r\n");
    }
    out.write(cmd.toString().getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  private void expectOk() throws IOException {
    if (in.read() != '+' || !"OK".equals(readLine())) throw new IOException("expected OK");
  }

  private long readNumber() throws IOException {
    String value = readLine();
    if (!value.matches("-?[0-9]{1,19}")) throw new IOException("invalid RESP number");
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException err) {
      throw new IOException("invalid RESP number", err);
    }
  }

  private String readBulk() throws IOException {
    long length = readNumber();
    if (length == -1) return null;
    if (length < 0 || length > MAX_REPLY_BYTES) throw new IOException("invalid bulk length");
    byte[] data = in.readNBytes((int) length);
    if (data.length != length || in.read() != '\r' || in.read() != '\n')
      throw new IOException("truncated or malformed bulk reply");
    return new String(data, StandardCharsets.UTF_8);
  }

  private String readLine() throws IOException {
    StringBuilder sb = new StringBuilder();
    while (sb.length() < 1024) {
      int b = in.read();
      if (b < 0 || b == '\n') throw new IOException("truncated or malformed RESP line");
      if (b == '\r') {
        if (in.read() != '\n') throw new IOException("invalid RESP line ending");
        return sb.toString();
      }
      sb.append((char) b);
    }
    throw new IOException("RESP line too long");
  }

  @Override
  public void close() {
    unavailable = true;
    try {
      socket.close();
    } catch (IOException ignored) {
      // best-effort
    }
  }
}
