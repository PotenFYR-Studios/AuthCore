package net.ded3ec.proxy;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Minimal zero-dependency RESP client for the proxy plugin.
 *
 * <p>The proxy plugin classpath contains ONLY the plugin jar (nested shaded libraries are a
 * Fabric concept the proxies do not load), so this speaks just enough Redis RESP to validate
 * sessions: connect, AUTH, SELECT, GET, DEL, SET with expiry. Everything is fail-open: when
 * Redis is unreachable the proxy plugin logs once and allows connections.
 */
public final class RedisClient implements AutoCloseable {

  private final Socket socket;
  private final BufferedInputStream in;
  private final BufferedOutputStream out;

  private RedisClient(Socket socket) throws IOException {
    this.socket = socket;
    this.in = new BufferedInputStream(socket.getInputStream());
    this.out = new BufferedOutputStream(socket.getOutputStream());
  }

  /** Connects (with optional AUTH/SELECT). Returns null when Redis is unreachable. */
  public static RedisClient connect(String host, int port, String password, int database) {
    try {
      Socket socket = new Socket();
      socket.connect(new InetSocketAddress(host, port), 1500);
      socket.setSoTimeout(1500);
      RedisClient client = new RedisClient(socket);
      if (password != null && !password.isBlank()) {
        client.send("AUTH", password);
        client.readReply(); // ignore result - fail-open
      }
      if (database > 0) {
        client.send("SELECT", String.valueOf(database));
        client.readReply(); // ignore result - fail-open
      }
      return client;
    } catch (IOException err) {
      return null;
    }
  }

  /** Returns the stored value or null when absent/unreachable. */
  public String get(String key) {
    try {
      send("GET", key);
      Object reply = readReply();
      return reply == null ? null : reply.toString();
    } catch (IOException err) {
      return null;
    }
  }

  /** Stores a value with a TTL (used to seed sessions from interop messages). */
  public void setEx(String key, long ttlSeconds, String value) {
    try {
      send("SET", key, value, "EX", String.valueOf(ttlSeconds));
      readReply();
    } catch (IOException ignored) {
      // fail-open
    }
  }

  public void del(String key) {
    try {
      send("DEL", key);
      readReply();
    } catch (IOException ignored) {
      // fail-open
    }
  }

  private void send(String... args) throws IOException {
    StringBuilder cmd = new StringBuilder("*").append(args.length).append("\r\n");
    for (String arg : args) {
      byte[] data = arg.getBytes(StandardCharsets.UTF_8);
      cmd.append('$').append(data.length).append("\r\n").append(arg).append("\r\n");
    }
    out.write(cmd.toString().getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  /** Reads one RESP reply; null for bulk-nil / nil, String for bulk/simple, Long for ints. */
  private Object readReply() throws IOException {
    int type = in.read();
    if (type < 0) throw new IOException("connection closed");
    return switch (type) {
      case '+' -> readLine();
      case '-' -> throw new IOException("redis error: " + readLine());
      case ':' -> Long.parseLong(readLine());
      case '$' -> {
        int len = Integer.parseInt(readLine());
        if (len < 0) yield null;
        byte[] data = new byte[len + 2];
        readFully(data);
        yield new String(data, 0, len, StandardCharsets.UTF_8);
      }
      case '*' -> {
        int count = Integer.parseInt(readLine());
        for (int i = 0; i < count; i++) readReply();
        yield null;
      }
      default -> null;
    };
  }

  private String readLine() throws IOException {
    StringBuilder sb = new StringBuilder();
    int b;
    while ((b = in.read()) != -1 && b != '\r') {
      if (b == '\n') break;
      sb.append((char) b);
    }
    return sb.toString();
  }

  private void readFully(byte[] data) throws IOException {
    int off = 0;
    while (off < data.length) {
      int n = in.read(data, off, data.length - off);
      if (n < 0) throw new IOException("connection closed");
      off += n;
    }
  }

  @Override
  public void close() {
    try {
      socket.close();
    } catch (IOException ignored) {
      // best-effort
    }
  }
}
