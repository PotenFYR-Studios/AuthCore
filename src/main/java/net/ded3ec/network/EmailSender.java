package net.ded3ec.network;

import net.ded3ec.models.Config;
import net.ded3ec.util.Logger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import net.ded3ec.AuthCoreServer;

/**
 * Minimal dependency-free SMTP client used for login alerts and password recovery emails.
 *
 * <p>Supports implicit TLS (port 465) and STARTTLS (port 587). All operations are safe to call
 * from any thread; network I/O happens on the caller's thread with hard timeouts. No passwords
 * are ever logged.
 */
public final class EmailSender {

  private static final int TIMEOUT_MS = 10_000;

  private EmailSender() {}

  /** Whether SMTP is configured (enabled + host + from). */
  public static boolean isEnabled() {
    if (AuthCoreServer.config == null || !AuthCoreServer.config.session.email.enabled) return false;
    var cfg = AuthCoreServer.config.session.email;
    return cfg.host != null && !cfg.host.isBlank() && cfg.from != null && !cfg.from.isBlank();
  }

  /**
   * Sends an email asynchronously on the shared I/O executor.
   *
   * @param to recipient address
   * @param subject email subject
   * @param body plain-text body
   */
  public static void sendAsync(String to, String subject, String body) {
    AuthCoreServer.IO_EXECUTOR.execute(() -> send(to, subject, body));
  }

  /** Sends an email synchronously. Returns {@code true} on success. */
  public static boolean send(String to, String subject, String body) {
    if (!isEnabled() || to == null || to.isBlank()) return false;

    var cfg = AuthCoreServer.config.session.email;

    try {
      InetSocketAddress address = new InetSocketAddress(cfg.host, cfg.port);

      SSLSocket socket = null;
      BufferedWriter writer;
      BufferedReader reader;

      if (cfg.useSsl) {
        // Implicit TLS (e.g. port 465)
        socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
        socket.connect(address, TIMEOUT_MS);
        socket.setSoTimeout(TIMEOUT_MS);
        writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
      } else {
        // Plain socket + STARTTLS upgrade (e.g. port 587)
        java.net.Socket plain = new java.net.Socket();
        plain.connect(address, TIMEOUT_MS);
        plain.setSoTimeout(TIMEOUT_MS);
        writer = new BufferedWriter(new OutputStreamWriter(plain.getOutputStream(), StandardCharsets.UTF_8));
        reader = new BufferedReader(new InputStreamReader(plain.getInputStream(), StandardCharsets.UTF_8));
        try {
          expect(reader, 220);
          sendLine(writer, "EHLO AuthCore");
          expect(reader, 250);
          sendLine(writer, "STARTTLS");
          expect(reader, 220);
          socket =
              (SSLSocket)
                  ((SSLSocketFactory) SSLSocketFactory.getDefault())
                      .createSocket(plain, cfg.host, cfg.port, true);
          socket.setSoTimeout(TIMEOUT_MS);
          writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
          reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        } finally {
          if (socket == null) plain.close();
        }
      }

      try {
        sendLine(writer, "EHLO AuthCore");
        expect(reader, 250);

        if (cfg.username != null && !cfg.username.isEmpty()) {
          sendLine(writer, "AUTH LOGIN");
          expect(reader, 334);
          sendLine(writer, Base64.getEncoder().encodeToString(cfg.username.getBytes(StandardCharsets.UTF_8)));
          expect(reader, 334);
          sendLine(writer, Base64.getEncoder().encodeToString(cfg.password.getBytes(StandardCharsets.UTF_8)));
          expect(reader, 235);
        }

        sendLine(writer, "MAIL FROM:<" + cfg.from + ">");
        expect(reader, 250);
        sendLine(writer, "RCPT TO:<" + to + ">");
        expect(reader, 250);
        sendLine(writer, "DATA");
        expect(reader, 354);

        writer.write("From: AuthCore <" + cfg.from + ">");
        writer.newLine();
        writer.write("To: <" + to + ">");
        writer.newLine();
        writer.write("Subject: " + subject);
        writer.newLine();
        writer.write("MIME-Version: 1.0");
        writer.newLine();
        writer.write("Content-Type: text/plain; charset=UTF-8");
        writer.newLine();
        writer.newLine();
        writer.write(body);
        writer.newLine();
        writer.write(".");
        writer.newLine();
        writer.flush();
        expect(reader, 250);

        sendLine(writer, "QUIT");
        return true;
      } finally {
        try {
          writer.close();
          reader.close();
        } catch (Exception ignored) {
          // closing best-effort
        }
        if (socket != null && !socket.isClosed()) socket.close();
      }
    } catch (Exception err) {
      AuthCoreServer.LOGGER.debug(null, "Failed to send email to {}:", to, err);
      return false;
    }
  }

  /** Writes a command line and flushes. */
  private static void sendLine(BufferedWriter writer, String line) throws java.io.IOException {
    writer.write(line);
    writer.newLine();
    writer.flush();
  }

  /** Reads server responses until a line starting with the expected code is found. */
  private static void expect(BufferedReader reader, int code) throws java.io.IOException {
    String line;
    while ((line = reader.readLine()) != null) {
      // "250-..." continuation vs "250 ..." final
      if (line.length() >= 3 && line.startsWith(String.valueOf(code))) {
        if (line.length() == 3 || line.charAt(3) == ' ') return;
      }
      // charAt(3) needs length >= 4 - a bare 3-char line ("250") threw
      // StringIndexOutOfBoundsException here and aborted the whole send.
      if (line.length() >= 4 && line.charAt(3) == '-') continue;
    }
    throw new java.io.IOException("SMTP: connection closed before code " + code);
  }
}
