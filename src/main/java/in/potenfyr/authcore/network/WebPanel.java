package in.potenfyr.authcore.network;

import in.potenfyr.authcore.models.Config;
import in.potenfyr.authcore.models.Lobby;
import in.potenfyr.authcore.models.Messages;
import in.potenfyr.authcore.security.Encrypter;
import in.potenfyr.authcore.security.Security;
import in.potenfyr.authcore.security.SecurityLog;
import in.potenfyr.authcore.util.Database;
import in.potenfyr.authcore.util.HoconConf;
import in.potenfyr.authcore.util.Logger;
import in.potenfyr.authcore.util.TpsManager;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import in.potenfyr.authcore.AuthCoreServer;
import in.potenfyr.authcore.models.User;

/**
 * Lightweight built-in web admin panel.
 *
 * <p>Serves a single-page HTML dashboard over HTTP with token authentication:
 *
 * <ul>
 *   <li>{@code GET /} - dashboard page
 *   <li>{@code GET /api/overview} - server + auth stats
 *   <li>{@code GET /api/players} - account list
 *   <li>{@code GET /api/history?uuid=...} - login history
 *   <li>{@code POST /api/action} - kick / logout / unlock / delete / set-password / reload
 * </ul>
 *
 * <p>Security: bound to 127.0.0.1 by default; every request requires
 * {@code Authorization: Bearer <token>}. Runs on a daemon thread.
 */
public final class WebPanel {

  private static final Gson GSON = new Gson();
  private static HttpServer server;

  private WebPanel() {}

  /** Starts the panel when enabled and a token is configured. */
  public static synchronized void start() {
    if (server != null) return;

    var cfg = AuthCoreServer.config.session.webPanel;
    if (!cfg.enabled) return;

    final String[] tokenHolder = {cfg.token == null ? "" : cfg.token.trim()};
    // Optional token file (wins over the inline token)
    if (cfg.tokenFile != null && !cfg.tokenFile.isBlank()) {
      try {
        java.nio.file.Path tokenPath = AuthCoreServer.configPath.resolve(cfg.tokenFile);
        if (java.nio.file.Files.exists(tokenPath)) {
          String fileToken = java.nio.file.Files.readString(tokenPath).trim();
          if (!fileToken.isEmpty()) tokenHolder[0] = fileToken;
        }
      } catch (Exception ignored) {
        // token file unreadable - keep the inline token
      }
    }
    if (tokenHolder[0].isEmpty()) {
      AuthCoreServer.LOGGER.warn(
          false,
          "Web panel is enabled but no token is set - panel will NOT start! "
              + "Set session.web-panel.token in settings.conf.");
      return;
    }

    try {
      if (cfg.httpsEnabled) {
        javax.net.ssl.SSLContext ssl = createSslContext();
        server = com.sun.net.httpserver.HttpsServer.create(
            new InetSocketAddress(cfg.host, cfg.httpsPort), 0);
        ((com.sun.net.httpserver.HttpsServer) server).setHttpsConfigurator(
            new com.sun.net.httpserver.HttpsConfigurator(ssl));
        AuthCoreServer.LOGGER.info(
            true, "Web panel started at https://{}:{}/ (self-signed certificate)", cfg.host, cfg.httpsPort);
      } else {
        server = HttpServer.create(new InetSocketAddress(cfg.host, cfg.port), 0);
        AuthCoreServer.LOGGER.info(
            true, "Web panel started at http://{}:{}/ (token required)", cfg.host, cfg.port);
      }

      // CORS preflight handler: allows cross-origin requests from the configured origin
      // (useful when the panel is accessed through a reverse proxy or a different port).
      server.createContext("/", exchange -> {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
          setCorsHeaders(exchange);
          exchange.getResponseHeaders().set("Allow", "GET, POST, OPTIONS");
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
          return;
        }
        handle(exchange, tokenHolder[0]);
      });
      // Bounded executor: an admin panel must never spawn unbounded threads under load.
      // CallerRuns back-pressures onto the HttpServer dispatcher when saturated.
      server.setExecutor(
          new java.util.concurrent.ThreadPoolExecutor(
              2,
              8,
              60L,
              java.util.concurrent.TimeUnit.SECONDS,
              new java.util.concurrent.LinkedBlockingQueue<>(64),
              runnable -> {
                Thread thread = new Thread(runnable, "AuthCore-WebPanel");
                thread.setDaemon(true);
                return thread;
              },
              new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy()));
      server.start();
    } catch (Exception err) {
      AuthCoreServer.LOGGER.error(
          false,
          "["
              + in.potenfyr.authcore.util.ErrorCodes.code(
                  in.potenfyr.authcore.util.ErrorCodes.Module.WEB_PANEL, in.potenfyr.authcore.util.ErrorCodes.Kind.INIT, 1)
              + "] Failed to start the web panel on {}:{}:",
          cfg.host,
          cfg.httpsEnabled ? cfg.httpsPort : cfg.port,
          err);
      server = null;
    }
  }

  /** Builds the SSL context from a custom keystore or an auto-generated self-signed one. */
  private static javax.net.ssl.SSLContext createSslContext() throws Exception {
    var cfg = AuthCoreServer.config.session.webPanel;

    java.security.KeyStore ks;
    String password;

    if (cfg.httpsKeystore != null && !cfg.httpsKeystore.isBlank()) {
      java.nio.file.Path path = java.nio.file.Path.of(cfg.httpsKeystore);
      password = cfg.httpsKeystorePassword == null ? "" : cfg.httpsKeystorePassword;
      ks = java.security.KeyStore.getInstance(path.toString().endsWith(".jks") ? "JKS" : "PKCS12");
      try (java.io.InputStream in = java.nio.file.Files.newInputStream(path)) {
        ks.load(in, password.toCharArray());
      }
      AuthCoreServer.LOGGER.info(true, "Web panel using custom keystore: {}", path);
    } else {
      // Auto-generate a self-signed certificate on first start
      java.nio.file.Path keystorePath = AuthCoreServer.configPath.resolve("panel-keystore.p12");
      password = "authcore";

      if (!java.nio.file.Files.exists(keystorePath)) {
        generateSelfSignedKeystore(keystorePath, password);
      }

      ks = java.security.KeyStore.getInstance("PKCS12");
      try (java.io.InputStream in = java.nio.file.Files.newInputStream(keystorePath)) {
        ks.load(in, password.toCharArray());
      }
    }

    javax.net.ssl.KeyManagerFactory kmf =
        javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
    kmf.init(ks, password.toCharArray());

    javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
    ctx.init(kmf.getKeyManagers(), null, null);
    return ctx;
  }

  /** Generates a self-signed RSA certificate (PKCS12) with BouncyCastle. */
  private static void generateSelfSignedKeystore(java.nio.file.Path target, String password)
      throws Exception {
    org.bouncycastle.asn1.x500.X500Name issuer =
        new org.bouncycastle.asn1.x500.X500Name("CN=AuthCore Web Panel, O=AuthCore");
    java.security.KeyPairGenerator keyGen = java.security.KeyPairGenerator.getInstance("RSA");
    keyGen.initialize(2048);
    java.security.KeyPair pair = keyGen.generateKeyPair();

    org.bouncycastle.cert.X509v3CertificateBuilder builder =
        new org.bouncycastle.cert.X509v3CertificateBuilder(
            issuer,
            java.math.BigInteger.valueOf(System.currentTimeMillis()),
            new java.util.Date(System.currentTimeMillis() - 86400000L),
            new java.util.Date(System.currentTimeMillis() + 10L * 365 * 86400000L),
            issuer,
            org.bouncycastle.asn1.x509.SubjectPublicKeyInfo.getInstance(pair.getPublic().getEncoded()));

    org.bouncycastle.cert.jcajce.JcaX509CertificateConverter converter =
        new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter();
    java.security.cert.X509Certificate cert = converter.getCertificate(builder.build(
        new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA")
            .build(pair.getPrivate())));

    java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
    ks.load(null, null);
    ks.setKeyEntry(
        "authcore",
        pair.getPrivate(),
        password.toCharArray(),
        new java.security.cert.Certificate[] {cert});

    java.nio.file.Files.createDirectories(AuthCoreServer.configPath);
    try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(target)) {
      ks.store(out, password.toCharArray());
    }

    AuthCoreServer.LOGGER.warn(
        false,
        "Generated a self-signed certificate for the web panel: {} - add an exception in your browser!",
        target);
    AuthCoreServer.LOGGER.info(
        true, "Certificate fingerprint (SHA-256): {}", fingerprint(cert));
  }

  /** Formats a certificate SHA-256 fingerprint for console display. */
  private static String fingerprint(java.security.cert.X509Certificate cert) throws Exception {
    byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < digest.length; i++) {
      if (i > 0) sb.append(':');
      sb.append(String.format("%02X", digest[i]));
    }
    return sb.toString();
  }

  /** Stops the panel (used on reload). */
  public static synchronized void stop() {
    if (server != null) {
      server.stop(0);
      server = null;
    }
  }

  private static void handle(HttpExchange exchange, String token) {
    try {
      String provided = extractToken(exchange);

      // Brute-force guard: after repeated failed token attempts from one source IP, the
      // panel rejects further requests from that IP for a lockout window.
      if (isLockedOut(exchange)) {
        respond(exchange, 429, error("Too many failed attempts - try again later"));
        return;
      }

      // Constant-time comparisons (no timing side-channel on the token)
      boolean fullAccess = constantTimeEquals(token, provided);
      // Read-only token (view data, no actions)
      String readOnly = AuthCoreServer.config.session.webPanel.readonlyToken;
      boolean readOnlyAccess =
          readOnly != null && !readOnly.isBlank() && constantTimeEquals(readOnly, provided);

      if (!fullAccess && !readOnlyAccess) {
        recordFailedAttempt(exchange);
        respond(exchange, 401, error("Unauthorized - send 'Authorization: Bearer <token>'"));
        return;
      }
      recordSuccess(exchange);

      String path = exchange.getRequestURI().getPath();
      String method = exchange.getRequestMethod();

      // Read-only tokens may not run actions
      boolean isAction = "POST".equals(method) && "/api/action".equals(path);
      if (readOnlyAccess && isAction) {
        respond(exchange, 403, error("Read-only token cannot run actions"));
        return;
      }

      if ("GET".equals(method) && "/".equals(path)) {
        respondHtml(exchange, PAGE);
        return;
      }
      if ("GET".equals(method) && "/api/overview".equals(path)) {
        respond(exchange, 200, overview());
        return;
      }
      if ("GET".equals(method) && "/api/players".equals(path)) {
        respond(exchange, 200, players());
        return;
      }
      if ("GET".equals(method) && "/api/history".equals(path)) {
        String uuid = param(exchange, "uuid");
        respond(exchange, 200, history(uuid));
        return;
      }
      if ("POST".equals(method) && "/api/action".equals(path)) {
        // Bounded read: an unbounded readAllBytes() let any authenticated caller (or a
        // brute-forcing client) stream gigabytes into memory. Action bodies are tiny.
        byte[] raw = exchange.getRequestBody().readNBytes(65_537);
        if (raw.length > 65_536) {
          respond(exchange, 413, error("Request body too large"));
          return;
        }
        String body = new String(raw, StandardCharsets.UTF_8);
        respond(exchange, 200, action(body));
        return;
      }

      respond(exchange, 404, error("Not found"));
    } catch (Exception err) {
      // Never leak internal details to the client
      AuthCoreServer.LOGGER.debug(null, "Web panel request failed:", err);
      try {
        setCorsHeaders(exchange);
        respond(exchange, 500, error("Internal server error"));
      } catch (Exception ignored) {
        // response already failed
      }
    } finally {
      exchange.close();
    }
  }

  // ------------------------------------------------------------------
  // Constant-time token comparison + per-IP brute-force lockout
  // ------------------------------------------------------------------

  /** Constant-time comparison of two strings (timing-attack safe). */
  private static boolean constantTimeEquals(String a, String b) {
    if (a == null || b == null) return false;
    return java.security.MessageDigest.isEqual(
        a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }

  private static final int MAX_ATTEMPTS = 5;
  private static final long LOCKOUT_MS = 60_000L;
  private static final int MAX_TRACKED_IPS = 2_048;
  /** Per-IP brute-force tracking: {firstAttemptMs, failureCount, lockoutUntilMs}. */
  private static final java.util.Map<String, long[]> FAILED_ATTEMPTS = new java.util.concurrent.ConcurrentHashMap<>();

  private static boolean isLockedOut(HttpExchange exchange) {
    String ip = clientIp(exchange);
    long[] entry = FAILED_ATTEMPTS.get(ip);
    if (entry == null) return false;
    long now = System.currentTimeMillis();
    // Reset the counter once the lockout window has passed
    if (entry[2] != 0L && now > entry[2]) {
      FAILED_ATTEMPTS.remove(ip);
      return false;
    }
    return entry[1] >= MAX_ATTEMPTS;
  }

  private static void recordFailedAttempt(HttpExchange exchange) {
    String ip = clientIp(exchange);
    long now = System.currentTimeMillis();
    AuthCoreServer.LOGGER.debug(
        false, "Web panel | failed token attempt from {} (brute-force lockout tracking)", ip);

    // Bounded memory: if an attacker cycles through many IPs, forget stale entries
    // instead of letting the map grow forever.
    if (FAILED_ATTEMPTS.size() >= MAX_TRACKED_IPS) {
      FAILED_ATTEMPTS.entrySet().removeIf(e -> now - e.getValue()[0] > LOCKOUT_MS);
      if (FAILED_ATTEMPTS.size() >= MAX_TRACKED_IPS) FAILED_ATTEMPTS.clear();
    }

    FAILED_ATTEMPTS.compute(
        ip,
        (k, entry) -> {
          if (entry == null || entry[2] != 0L || now - entry[0] > LOCKOUT_MS)
            return new long[] {now, 1, 0L};
          long[] next = {entry[0], entry[1] + 1, 0L};
          if (next[1] >= MAX_ATTEMPTS) {
            next[2] = now + LOCKOUT_MS;
            AuthCoreServer.LOGGER.debug(
                false,
                "Web panel | {} locked out for {}ms after {} failed attempts",
                ip,
                LOCKOUT_MS,
                MAX_ATTEMPTS);
          }
          return next;
        });
  }

  private static void recordSuccess(HttpExchange exchange) {
    FAILED_ATTEMPTS.remove(clientIp(exchange));
  }

  private static String clientIp(HttpExchange exchange) {
    try {
      var addr = exchange.getRemoteAddress();
      return addr == null || addr.getAddress() == null ? "unknown" : addr.getAddress().getHostAddress();
    } catch (Exception err) {
      return "unknown";
    }
  }

  /** Extracts the bearer token from the Authorization header. */
  private static String extractToken(HttpExchange exchange) {
    try {
      String header = exchange.getRequestHeaders().getFirst("Authorization");
      if (header == null) return "";
      String trimmed = header.trim();
      return trimmed.startsWith("Bearer ") ? trimmed.substring(7).trim() : trimmed;
    } catch (Exception err) {
      return "";
    }
  }

  private static JsonObject overview() {
    JsonObject json = new JsonObject();

    // Online/lobby/locked come from the live cache; registered/premium counts are
    // database-backed so the panel stays fast on servers with 100k+ registered users.
    long online = User.users.values().stream().filter(u -> u.isActive).count();
    long lobby = User.users.values().stream().filter(u -> u.isInLobby.get()).count();
    long locked = User.users.values().stream().filter(User::isLocked).count();
    long registered = in.potenfyr.authcore.models.User.countRegistered();
    long premium = in.potenfyr.authcore.models.User.countByMode("online-mode");

    json.addProperty("version", AuthCoreServer.MOD_VERSION);
    json.addProperty("registered", registered);
    json.addProperty("online", online);
    json.addProperty("inLobby", lobby);
    json.addProperty("locked", locked);
    json.addProperty("premium", premium);
    json.addProperty("tps", Math.round(TpsManager.get() * 10.0) / 10.0);
    json.addProperty("database", Database.dialect.name());
    json.addProperty("redis", RedisManager.isEnabled());
    return json;
  }

  private static JsonObject players() {
    JsonArray list = new JsonArray();

    // Memory-friendly: bounded DB query instead of iterating the in-memory cache
    for (in.potenfyr.authcore.models.User user : in.potenfyr.authcore.models.User.fetchPlayersPublic(500, null)) {
      JsonObject o = new JsonObject();
      o.addProperty("username", user.username);
      o.addProperty("nickname", user.nickname);
      o.addProperty("uuid", user.uuid.toString());
      o.addProperty("premium", user.isPremium);
      o.addProperty("registered", user.isRegistered.get());
      o.addProperty("online", user.isActive);
      o.addProperty("inLobby", user.isInLobby.get());
      o.addProperty("locked", user.isLocked());
      o.addProperty("risk", user.riskScore);
      o.addProperty("ip", user.ipAddress);
      o.addProperty("country", user.country.get());
      list.add(o);
    }

    JsonObject json = new JsonObject();
    json.add("players", list);
    json.addProperty("count", list.size());
    return json;
  }

  private static JsonObject history(String uuidParam) {
    JsonArray list = new JsonArray();

    if (uuidParam != null) {
      try {
        UUID uuid = UUID.fromString(uuidParam);
        for (String line : User.fetchLoginHistory(uuid, 20)) list.add(line);
      } catch (IllegalArgumentException ignored) {
        // invalid uuid -> empty list
      }
    }

    JsonObject json = new JsonObject();
    json.add("history", list);
    return json;
  }

  private static JsonObject action(String body) {
    JsonObject request;
    try {
      request = GSON.fromJson(body, JsonObject.class);
    } catch (Exception err) {
      return error("Invalid JSON body");
    }
    if (request == null || !request.has("action")) return error("Missing 'action'");

    String action = request.get("action").getAsString();
    String uuidStr = request.has("uuid") ? request.get("uuid").getAsString() : null;

    if ("reload".equals(action)) {
      HoconConf.initialize();
      stop();
      start();
      return ok("Configuration reloaded");
    }

    if (uuidStr == null) return error("Missing 'uuid'");

    UUID uuid;
    try {
      uuid = UUID.fromString(uuidStr);
    } catch (IllegalArgumentException err) {
      return error("Invalid UUID");
    }

    User user = User.users.get(uuid);
    if (user == null) return error("User not found");

    switch (action) {
      case "kick" -> {
        if (user.isActive) {
          user.kick(AuthCoreServer.messages.promptUserKickedByAdmin);
          SecurityLog.log("WEB_KICK", user.username + " kicked from the web panel");
          return ok("Kicked " + user.username);
        }
        return error("User is not online");
      }
      case "logout" -> {
        user.logout(AuthCoreServer.messages.promptUserLogoutComplete);
        SecurityLog.log("WEB_LOGOUT", user.username + " logged out from the web panel");
        return ok("Logged out " + user.username);
      }
      case "unlock" -> {
        user.unlock();
        SecurityLog.log("WEB_UNLOCK", user.username + " unlocked from the web panel");
        return ok("Unlocked " + user.username);
      }
      case "delete" -> {
        user.kick(AuthCoreServer.messages.promptUserDataDeleted, "Web Panel");
        user.delete("Deleted from the web panel", true);
        SecurityLog.log("WEB_DELETE", user.username + " deleted from the web panel");
        return ok("Deleted " + user.username);
      }
      case "set-password" -> {
        if (!request.has("value")) return error("Missing 'value'");
        String password = request.get("value").getAsString();
        if (password == null || password.isBlank()) return error("Password cannot be empty");
        user.passwordEncryption = AuthCoreServer.config.passwordRules.passwordHashAlgorithm;
        user.password = Encrypter.hash(user.passwordEncryption, password);
        if (user.password == null) {
          // Never leave the account unregistered because hashing failed
          user.passwordEncryption = null;
          return error("Password hashing failed - password not changed");
        }
        user.update("Password set from the web panel");
        SecurityLog.log("WEB_PASSWORD", user.username + " password reset from the web panel");
        return ok("Password updated for " + user.username);
      }
      case "link" -> {
        // Discord account linking (used by Discord bots). value = discordId or a 6-char link code
        String discordValue = request.has("value") ? request.get("value").getAsString() : null;
        if (discordValue == null || discordValue.isBlank()) return error("Missing 'value' (discordId)");

        if (discordValue.length() == 6 && discordValue.matches("[A-Z2-9]+")) {
          String resolved = RedisManager.consumeDiscordLinkCode(discordValue);
          if (resolved == null) return error("Link code expired or invalid");
          in.potenfyr.authcore.models.User byName = in.potenfyr.authcore.models.User.getUserByUsername(resolved);
          if (byName == null) return error("Player not found for link code");
          byName.discordId = discordValue;
          byName.update("Discord linked via code");
          RedisManager.publishDiscordLink(discordValue, byName.username);
          SecurityLog.log("DISCORD_LINK", byName.username + " linked Discord " + discordValue);
          Webhook.send(":white_check_mark: **" + byName.username + "** linked their Discord account.");
          return ok("Linked " + byName.username);
        }

        user.discordId = discordValue;
        user.update("Discord linked");
        RedisManager.publishDiscordLink(discordValue, user.username);
        SecurityLog.log("DISCORD_LINK", user.username + " linked Discord " + discordValue);
        Webhook.send(":white_check_mark: **" + user.username + "** linked their Discord account.");
        return ok("Linked " + user.username);
      }
      default -> {
        return error("Unknown action: " + action);
      }
    }
  }

  private static JsonObject ok(String message) {
    JsonObject json = new JsonObject();
    json.addProperty("success", true);
    json.addProperty("message", message);
    return json;
  }

  private static JsonObject error(String message) {
    JsonObject json = new JsonObject();
    json.addProperty("success", false);
    json.addProperty("error", message);
    return json;
  }

  private static String param(HttpExchange exchange, String name) {
    String query = exchange.getRequestURI().getQuery();
    if (query == null) return null;
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0 && pair.substring(0, eq).equals(name)) {
        try {
          return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
        } catch (Exception err) {
          return null;
        }
      }
    }
    return null;
  }

  /** Sets CORS headers on the response (used for preflight + actual requests). */
  private static void setCorsHeaders(HttpExchange exchange) {
    var h = exchange.getResponseHeaders();
    h.set("Access-Control-Allow-Origin", "*");
    h.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    h.set("Access-Control-Allow-Headers", "Authorization, Content-Type");
    h.set("Access-Control-Max-Age", "86400");
  }

  private static void respond(HttpExchange exchange, int code, JsonObject json) throws java.io.IOException {
    byte[] bytes = GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
    setCorsHeaders(exchange);
    securityHeaders(exchange, false);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(code, bytes.length);
    exchange.getResponseBody().write(bytes);
  }

  private static void respondHtml(HttpExchange exchange, String html) throws java.io.IOException {
    byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
    setCorsHeaders(exchange);
    securityHeaders(exchange, true);
    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
  }

  /**
   * Baseline browser hardening on every response: the panel holds a powerful bearer token,
   * so clickjacking / MIME-sniffing / referrer leakage are all closed by default. The HTML
   * page additionally gets a strict CSP (inline script/style only, no external origins).
   */
  private static void securityHeaders(HttpExchange exchange, boolean html) {
    var h = exchange.getResponseHeaders();
    h.set("X-Content-Type-Options", "nosniff");
    h.set("X-Frame-Options", "DENY");
    h.set("Referrer-Policy", "no-referrer");
    if (html)
      h.set(
          "Content-Security-Policy",
          "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; img-src 'self' data:");
  }

  /** Single-file dashboard page (dark, no external resources). */
  private static final String PAGE =
      """
      <!DOCTYPE html>
      <html lang="en">
      <head>
      <meta charset="utf-8">
      <meta name="viewport" content="width=device-width, initial-scale=1">
      <title>AuthCore Admin Portal</title>
      <style>
        :root {
          --bg: #080a10;
          --surface: rgba(16, 22, 34, 0.75);
          --surface-hover: rgba(26, 35, 53, 0.85);
          --card: #101622;
          --border: rgba(255, 255, 255, 0.08);
          --border-glow: rgba(56, 189, 248, 0.28);
          --text: #f1f5f9;
          --muted: #94a3b8;
          --primary: #38bdf8;
          --primary-glow: rgba(56, 189, 248, 0.35);
          --accent: #6366f1;
          --green: #10b981;
          --green-glow: rgba(16, 185, 129, 0.25);
          --red: #f43f5e;
          --red-glow: rgba(244, 63, 94, 0.25);
          --yellow: #f59e0b;
          --yellow-glow: rgba(245, 158, 11, 0.25);
          --radius: 12px;
          --radius-sm: 8px;
          --transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
        }
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
          background: radial-gradient(circle at 10% 10%, rgba(56, 189, 248, 0.07) 0%, transparent 45%),
                      radial-gradient(circle at 90% 90%, rgba(99, 102, 241, 0.06) 0%, transparent 50%),
                      var(--bg);
          color: var(--text);
          font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", sans-serif;
          min-height: 100vh;
          padding: 24px;
          line-height: 1.5;
        }
        .container { max-width: 1280px; margin: 0 auto; }
        /* Login Card */
        #login {
          max-width: 420px;
          margin: 12vh auto;
          background: var(--surface);
          border: 1px solid var(--border);
          border-radius: 16px;
          padding: 36px 30px;
          box-shadow: 0 20px 40px rgba(0, 0, 0, 0.5), inset 0 1px 0 rgba(255, 255, 255, 0.06);
          backdrop-filter: blur(20px);
          text-align: center;
          animation: fadeIn 0.4s ease;
        }
        .shield-icon {
          width: 52px; height: 52px; margin: 0 auto 16px;
          background: linear-gradient(135deg, var(--primary), var(--accent));
          border-radius: 14px;
          display: flex; align-items: center; justify-content: center;
          box-shadow: 0 8px 24px var(--primary-glow);
        }
        .shield-icon svg { width: 28px; height: 28px; fill: #fff; }
        h1 {
          font-size: 24px; font-weight: 700;
          background: linear-gradient(135deg, #fff, #94a3b8);
          -webkit-background-clip: text;
          -webkit-text-fill-color: transparent;
          margin-bottom: 6px;
        }
        .sub { color: var(--muted); font-size: 13.5px; margin-bottom: 24px; }
        .input-group { position: relative; margin-bottom: 18px; }
        input {
          width: 100%;
          padding: 12px 14px;
          border-radius: var(--radius-sm);
          border: 1px solid var(--border);
          background: rgba(8, 10, 16, 0.7);
          color: var(--text);
          font-size: 14px;
          outline: none;
          transition: var(--transition);
        }
        input:focus {
          border-color: var(--primary);
          box-shadow: 0 0 0 3px var(--primary-glow);
          background: rgba(12, 16, 26, 0.9);
        }
        button {
          padding: 10px 18px;
          border: 0;
          border-radius: var(--radius-sm);
          background: linear-gradient(135deg, var(--primary), var(--accent));
          color: #fff;
          cursor: pointer;
          font-weight: 600;
          font-size: 13px;
          display: inline-flex; align-items: center; justify-content: center; gap: 6px;
          transition: var(--transition);
          box-shadow: 0 4px 14px rgba(56, 189, 248, 0.25);
        }
        button:hover {
          transform: translateY(-1px);
          box-shadow: 0 6px 20px rgba(56, 189, 248, 0.4);
          filter: brightness(1.08);
        }
        button:active { transform: translateY(0); }
        .btn-block { width: 100%; padding: 12px; font-size: 14.5px; }
        .btn-outline {
          background: transparent;
          border: 1px solid var(--border);
          color: var(--muted);
          box-shadow: none;
        }
        .btn-outline:hover {
          background: var(--surface-hover);
          color: var(--text);
          border-color: rgba(255, 255, 255, 0.15);
          box-shadow: none;
        }
        .danger {
          background: linear-gradient(135deg, #f43f5e, #e11d48) !important;
          box-shadow: 0 4px 12px var(--red-glow) !important;
        }
        .danger:hover {
          box-shadow: 0 6px 18px var(--red-glow) !important;
        }
        /* Dashboard */
        #dashboard { display: none; animation: fadeIn 0.4s ease; }
        .top-bar {
          display: flex; justify-content: space-between; align-items: center;
          padding: 16px 20px; margin-bottom: 24px;
          background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius);
          box-shadow: 0 8px 32px rgba(0, 0, 0, 0.35);
          backdrop-filter: blur(16px);
        }
        .brand-meta { display: flex; align-items: center; gap: 14px; }
        .badge-live {
          display: inline-flex; align-items: center; gap: 6px;
          padding: 4px 10px; border-radius: 999px;
          background: rgba(16, 185, 129, 0.12);
          border: 1px solid rgba(16, 185, 129, 0.25);
          color: var(--green); font-size: 12px; font-weight: 600;
        }
        .dot { width: 7px; height: 7px; border-radius: 50%; background: var(--green); box-shadow: 0 0 8px var(--green); }
        .stats {
          display: grid;
          grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
          gap: 14px;
          margin-bottom: 24px;
        }
        .stat-card {
          background: var(--surface);
          border: 1px solid var(--border);
          border-radius: var(--radius);
          padding: 18px 20px;
          backdrop-filter: blur(16px);
          transition: var(--transition);
          position: relative; overflow: hidden;
        }
        .stat-card:hover {
          transform: translateY(-2px);
          border-color: var(--border-glow);
          box-shadow: 0 10px 24px rgba(0, 0, 0, 0.3);
        }
        .stat-card b {
          display: block; font-size: 28px; font-weight: 700;
          color: #fff; margin-bottom: 2px;
        }
        .stat-card span {
          color: var(--muted); font-size: 12.5px; font-weight: 500;
          text-transform: uppercase; letter-spacing: 0.05em;
        }
        /* Search and Table */
        .table-controls {
          display: flex; gap: 12px; margin-bottom: 16px; align-items: center;
        }
        .table-wrap {
          background: var(--surface);
          border: 1px solid var(--border);
          border-radius: var(--radius);
          overflow: hidden;
          box-shadow: 0 8px 32px rgba(0, 0, 0, 0.35);
          backdrop-filter: blur(16px);
        }
        table { width: 100%; border-collapse: collapse; text-align: left; }
        th {
          background: rgba(8, 10, 16, 0.6);
          color: var(--muted);
          font-size: 11.5px;
          font-weight: 600;
          text-transform: uppercase;
          letter-spacing: 0.06em;
          padding: 14px 18px;
          border-bottom: 1px solid var(--border);
        }
        td {
          padding: 14px 18px;
          font-size: 13.5px;
          border-bottom: 1px solid var(--border);
          transition: background 0.15s ease;
        }
        tr:last-child td { border-bottom: none; }
        tr:hover td { background: rgba(255, 255, 255, 0.025); }
        .badge {
          display: inline-flex; align-items: center;
          padding: 3px 10px; border-radius: 999px;
          font-size: 11px; font-weight: 700; letter-spacing: 0.02em;
        }
        .ok { background: rgba(16, 185, 129, 0.12); color: var(--green); border: 1px solid rgba(16, 185, 129, 0.25); }
        .warn { background: rgba(245, 158, 11, 0.12); color: var(--yellow); border: 1px solid rgba(245, 158, 11, 0.25); }
        .no { background: rgba(244, 63, 94, 0.12); color: var(--red); border: 1px solid rgba(244, 63, 94, 0.25); }
        .row-actions { display: flex; gap: 6px; align-items: center; }
        .row-actions button { padding: 5px 11px; font-size: 11.5px; border-radius: 6px; }
        .muted { color: var(--muted); font-size: 12.5px; }
        /* Toast Alert */
        #toast {
          position: fixed; bottom: 24px; right: 24px;
          background: rgba(16, 22, 34, 0.95);
          border: 1px solid var(--border-glow);
          color: #fff;
          border-radius: var(--radius-sm);
          padding: 12px 18px;
          box-shadow: 0 10px 30px rgba(0, 0, 0, 0.5);
          backdrop-filter: blur(14px);
          font-size: 13px; font-weight: 500;
          display: none; z-index: 1000;
          animation: slideIn 0.3s ease;
        }
        @keyframes fadeIn { from { opacity: 0; transform: translateY(8px); } to { opacity: 1; transform: translateY(0); } }
        @keyframes slideIn { from { opacity: 0; transform: translateX(20px); } to { opacity: 1; transform: translateX(0); } }
      </style>
      </head>
      <body>
      <div class="container">
        <div id="login">
          <div class="shield-icon">
            <svg viewBox="0 0 24 24"><path d="M12 2L4 5v6.09c0 5.05 3.41 9.76 8 10.91 4.59-1.15 8-5.86 8-10.91V5l-8-3zm1 14h-2v-2h2v2zm0-4h-2V7h2v5z"/></svg>
          </div>
          <h1>AuthCore Fortress</h1>
          <div class="sub">Administrative Access & Security Monitor</div>
          <div class="input-group">
            <input id="token" type="password" placeholder="Enter Bearer Token..." autocomplete="off">
          </div>
          <button class="btn-block" onclick="connect()">Connect to Dashboard</button>
        </div>

        <div id="dashboard">
          <div class="top-bar">
            <div class="brand-meta">
              <div>
                <h1>AuthCore Fortress</h1>
                <div class="sub" id="sub" style="margin-bottom:0;">Connecting...</div>
              </div>
            </div>
            <div style="display:flex; gap:10px; align-items:center;">
              <span class="badge-live"><span class="dot"></span> Live Engine</span>
              <button class="btn-outline" onclick="disconnect()">Disconnect</button>
            </div>
          </div>

          <div class="stats" id="stats"></div>

          <div class="table-controls">
            <input id="search" placeholder="Filter players by username...">
            <button class="btn-outline" style="white-space:nowrap;" onclick="refresh()">Refresh</button>
          </div>

          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Player</th>
                  <th>Mode</th>
                  <th>Session Status</th>
                  <th>Risk Score</th>
                  <th>IP Address</th>
                  <th>Geo Country</th>
                  <th>Administrative Actions</th>
                </tr>
              </thead>
              <tbody id="rows"></tbody>
            </table>
          </div>
        </div>
      </div>
      <div id="toast"></div>

      <script>
      let TOKEN = '';
      const $ = id => document.getElementById(id);
      const toast = msg => {
        const t = $('toast');
        t.textContent = msg;
        t.style.display = 'block';
        clearTimeout(t.timer);
        t.timer = setTimeout(() => { t.style.display = 'none'; }, 3200);
      };
      const api = async (path, opts) => {
        const res = await fetch(path, Object.assign({ headers: { 'Authorization': 'Bearer ' + TOKEN } }, opts || {}));
        if (res.status === 401) { toast('Invalid or expired token'); throw new Error('unauthorized'); }
        return res.json();
      };
      async function connect() {
        TOKEN = $('token').value.trim();
        if (!TOKEN) { toast('Please enter a valid token'); return; }
        try {
          await api('/api/overview');
          $('login').style.display = 'none';
          $('dashboard').style.display = 'block';
          refresh();
        } catch (e) { /* toast shown */ }
      }
      function disconnect() {
        TOKEN = '';
        $('token').value = '';
        $('dashboard').style.display = 'none';
        $('login').style.display = 'block';
      }
      async function refresh() {
        if (!TOKEN) return;
        try {
          const o = await api('/api/overview');
          $('sub').textContent = 'Version: ' + o.version + ' | TPS: ' + o.tps + ' | Storage: ' + o.database + ' | Redis Bus: ' + (o.redis ? 'Connected' : 'Disabled');
          $('stats').innerHTML =
            stat('Registered', o.registered) + stat('Online', o.online) +
            stat('In Limbo', o.inLobby) + stat('Locked', o.locked) +
            stat('Premium Paid', o.premium);
          const p = await api('/api/players');
          render(p.players);
        } catch (e) {}
      }
      const stat = (label, value) => '<div class="stat-card"><b>' + esc(value) + '</b><span>' + label + '</span></div>';
      const esc = v => String(v ?? '').replace(/[&<>"'`]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;','`':'&#96;'}[c]));
      function render(players) {
        const q = $('search').value.toLowerCase();
        $('rows').innerHTML = players.filter(x => !q || x.username.toLowerCase().includes(q)).map(p => {
          const mode = p.premium ? '<span class="badge ok">online-mode</span>' : '<span class="badge warn">offline-mode</span>';
          const status = p.online ? '<span class="badge ok">online</span>' : (p.inLobby ? '<span class="badge warn">lobby</span>' : '<span class="badge no">offline</span>');
          const locked = p.locked ? ' <span class="badge no">locked</span>' : '';
          const risk = p.risk >= 60 ? '<span class="badge no">' + esc(p.risk) + '</span>' : (p.risk >= 30 ? '<span class="badge warn">' + esc(p.risk) + '</span>' : '<span class="badge ok">' + esc(p.risk) + '</span>');
          return '<tr><td><b style="color:#fff;">' + esc(p.username) + '</b>' + locked + '</td><td>' + mode + '</td><td>' + status + '</td>' +
            '<td>' + risk + '</td><td class="muted">' + esc(p.ip || '-') + '</td><td class="muted">' + esc(p.country || '-') + '</td>' +
            '<td class="row-actions">' +
            (p.online ? '<button class="btn-outline" onclick="act(\'' + p.uuid + '\',\'kick\')">Kick</button><button class="btn-outline" onclick="act(\'' + p.uuid + '\',\'logout\')">Logout</button>' : '') +
            (p.locked ? '<button class="btn-outline" style="border-color:var(--green);color:var(--green);" onclick="act(\'' + p.uuid + '\',\'unlock\')">Unlock</button>' : '') +
            '<button class="danger" onclick="act(\'' + p.uuid + '\',\'delete\')">Delete</button>' +
            '</td></tr>';
        }).join('');
      }
      async function act(uuid, action) {
        if (action === 'delete' && !confirm('Permanently delete account and all credentials?')) return;
        const r = await api('/api/action', { method: 'POST', body: JSON.stringify({ action, uuid }) });
        toast(r.message || r.error || 'Operation completed');
        refresh();
      }
      $('search').addEventListener('input', () => { api('/api/players').then(p => render(p.players)); });
      $('token').addEventListener('keyup', e => { if (e.key === 'Enter') connect(); });
      setInterval(refresh, 5000);
      </script>
      </body>
      </html>
      """;
}
