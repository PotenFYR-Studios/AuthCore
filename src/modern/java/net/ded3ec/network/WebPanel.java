package net.ded3ec.network;

import net.ded3ec.models.Config;
import net.ded3ec.models.Lobby;
import net.ded3ec.models.Messages;
import net.ded3ec.security.Encrypter;
import net.ded3ec.security.Security;
import net.ded3ec.security.SecurityLog;
import net.ded3ec.util.Database;
import net.ded3ec.util.HoconConf;
import net.ded3ec.util.Logger;
import net.ded3ec.util.TpsManager;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.User;

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

      server.createContext("/", exchange -> handle(exchange, tokenHolder[0]));
      server.setExecutor(
          java.util.concurrent.Executors.newCachedThreadPool(
              runnable -> {
                Thread thread = new Thread(runnable, "AuthCore-WebPanel");
                thread.setDaemon(true);
                return thread;
              }));
      server.start();
    } catch (Exception err) {
      AuthCoreServer.LOGGER.error(
          false, "Failed to start the web panel on {}:{}:", cfg.host, cfg.httpsEnabled ? cfg.httpsPort : cfg.port, err);
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

      // Full token
      boolean fullAccess = token.equals(provided);
      // Read-only token (view data, no actions)
      String readOnly = AuthCoreServer.config.session.webPanel.readonlyToken;
      boolean readOnlyAccess =
          readOnly != null && !readOnly.isBlank() && readOnly.equals(provided);

      if (!fullAccess && !readOnlyAccess) {
        respond(exchange, 401, error("Unauthorized - send 'Authorization: Bearer <token>'"));
        return;
      }

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
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        respond(exchange, 200, action(body));
        return;
      }

      respond(exchange, 404, error("Not found"));
    } catch (Exception err) {
      try {
        respond(exchange, 500, error("Internal error: " + err.getMessage()));
      } catch (Exception ignored) {
        // response already failed
      }
    } finally {
      exchange.close();
    }
  }

  private static boolean authorized(HttpExchange exchange, String token) {
    try {
      String header = exchange.getRequestHeaders().getFirst("Authorization");
      if (header == null) return false;
      String expected = "Bearer " + token;
      return expected.equals(header.trim()) || token.equals(header.trim());
    } catch (Exception err) {
      return false;
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
    long registered = net.ded3ec.models.User.countRegistered();
    long premium = net.ded3ec.models.User.countByMode("online-mode");

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
    for (net.ded3ec.models.User user : net.ded3ec.models.User.fetchPlayersPublic(500, null)) {
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
        user.logout(AuthCoreServer.messages.promptUserSessionExpired);
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
          net.ded3ec.models.User byName = net.ded3ec.models.User.getUserByUsername(resolved);
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

  private static void respond(HttpExchange exchange, int code, JsonObject json) throws java.io.IOException {
    byte[] bytes = GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(code, bytes.length);
    exchange.getResponseBody().write(bytes);
  }

  private static void respondHtml(HttpExchange exchange, String html) throws java.io.IOException {
    byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
  }

  /** Single-file dashboard page (dark, no external resources). */
  private static final String PAGE =
      """
      <!DOCTYPE html>
      <html lang="en">
      <head>
      <meta charset="utf-8">
      <meta name="viewport" content="width=device-width, initial-scale=1">
      <title>AuthCore Admin</title>
      <style>
        :root { --bg:#0f1117; --card:#171a23; --line:#262b3a; --text:#e6e8ef; --muted:#8b93a7;
                --green:#2ecc71; --red:#e74c3c; --yellow:#f1c40f; --blue:#55a7ff; }
        * { box-sizing:border-box; margin:0; padding:0; }
        body { background:var(--bg); color:var(--text); font-family:'Segoe UI',system-ui,sans-serif; padding:24px; }
        h1 { font-size:22px; margin-bottom:4px; }
        .sub { color:var(--muted); font-size:13px; margin-bottom:20px; }
        #login { max-width:380px; margin:10vh auto; background:var(--card); border:1px solid var(--line); border-radius:10px; padding:24px; }
        #login input { width:100%; padding:10px; border-radius:6px; border:1px solid var(--line); background:var(--bg); color:var(--text); margin:10px 0; }
        #login button, button { padding:10px 16px; border:0; border-radius:6px; background:var(--blue); color:#fff; cursor:pointer; font-weight:600; }
        #dashboard { display:none; }
        .stats { display:grid; grid-template-columns:repeat(auto-fit,minmax(130px,1fr)); gap:12px; margin-bottom:20px; }
        .stat { background:var(--card); border:1px solid var(--line); border-radius:8px; padding:14px; }
        .stat b { display:block; font-size:24px; }
        .stat span { color:var(--muted); font-size:12px; }
        table { width:100%; border-collapse:collapse; background:var(--card); border:1px solid var(--line); border-radius:8px; overflow:hidden; }
        th, td { text-align:left; padding:8px 10px; font-size:13px; border-bottom:1px solid var(--line); }
        th { color:var(--muted); font-weight:600; }
        .badge { display:inline-block; padding:2px 8px; border-radius:10px; font-size:11px; font-weight:700; }
        .ok { background:#1d3a2a; color:var(--green); } .no { background:#3a1d1d; color:var(--red); }
        .warn { background:#3a321d; color:var(--yellow); }
        .row-actions button { margin:0 4px 0 0; padding:4px 10px; font-size:12px; }
        .danger { background:var(--red); }
        .muted { color:var(--muted); font-size:12px; }
        #toast { position:fixed; bottom:20px; right:20px; background:var(--card); border:1px solid var(--line); border-radius:8px; padding:12px 16px; display:none; }
        #search { width:100%; padding:10px; border-radius:6px; border:1px solid var(--line); background:var(--card); color:var(--text); margin-bottom:12px; }
      </style>
      </head>
      <body>
      <div id="login">
        <h1>AuthCore Admin</h1>
        <div class="sub">Enter your panel token</div>
        <input id="token" type="password" placeholder="Token">
        <button onclick="connect()">Connect</button>
      </div>
      <div id="dashboard">
        <h1>AuthCore Admin</h1>
        <div class="sub" id="sub"></div>
        <div class="stats" id="stats"></div>
        <input id="search" placeholder="Search players...">
        <table>
          <thead><tr><th>Player</th><th>Mode</th><th>Status</th><th>Risk</th><th>IP</th><th>Country</th><th>Actions</th></tr></thead>
          <tbody id="rows"></tbody>
        </table>
      </div>
      <div id="toast"></div>
      <script>
      let TOKEN = '';
      const $ = id => document.getElementById(id);
      const toast = msg => { $('toast').textContent = msg; $('toast').style.display = 'block'; setTimeout(() => $('toast').style.display='none', 3000); };
      const api = async (path, opts) => {
        const res = await fetch(path, Object.assign({ headers: { 'Authorization': 'Bearer ' + TOKEN } }, opts || {}));
        if (res.status === 401) { toast('Invalid token'); throw new Error('unauthorized'); }
        return res.json();
      };
      async function connect() {
        TOKEN = $('token').value.trim();
        try {
          await api('/api/overview');
          $('login').style.display = 'none';
          $('dashboard').style.display = 'block';
          refresh();
        } catch (e) { /* toast shown */ }
      }
      async function refresh() {
        const o = await api('/api/overview');
        $('sub').textContent = 'AuthCore ' + o.version + ' | TPS ' + o.tps + ' | DB ' + o.database + ' | Redis ' + (o.redis ? 'on' : 'off');
        $('stats').innerHTML =
          stat('Registered', o.registered) + stat('Online', o.online) +
          stat('In Lobby', o.inLobby) + stat('Locked', o.locked) +
          stat('Premium', o.premium);
        const p = await api('/api/players');
        render(p.players);
      }
      const stat = (label, value) => '<div class="stat"><b>' + value + '</b><span>' + label + '</span></div>';
      function render(players) {
        const q = $('search').value.toLowerCase();
        $('rows').innerHTML = players.filter(x => !q || x.username.toLowerCase().includes(q)).map(p => {
          const mode = p.premium ? '<span class="badge ok">premium</span>' : '<span class="badge warn">offline</span>';
          const status = p.online ? '<span class="badge ok">online</span>' : (p.inLobby ? '<span class="badge warn">lobby</span>' : '<span class="badge no">offline</span>');
          const locked = p.locked ? ' <span class="badge no">locked</span>' : '';
          const risk = p.risk >= 60 ? '<span class="badge no">' + p.risk + '</span>' : '<span class="badge ok">' + p.risk + '</span>';
          return '<tr><td><b>' + p.username + '</b>' + locked + '</td><td>' + mode + '</td><td>' + status + '</td>' +
            '<td>' + risk + '</td><td class="muted">' + (p.ip || '-') + '</td><td class="muted">' + (p.country || '-') + '</td>' +
            '<td class="row-actions">' +
            (p.online ? '<button onclick="act(\'' + p.uuid + '\',\'kick\')">Kick</button><button onclick="act(\'' + p.uuid + '\',\'logout\')">Logout</button>' : '') +
            (p.locked ? '<button onclick="act(\'' + p.uuid + '\',\'unlock\')">Unlock</button>' : '') +
            '<button class="danger" onclick="act(\'' + p.uuid + '\',\'delete\')">Delete</button>' +
            '</td></tr>';
        }).join('');
      }
      async function act(uuid, action) {
        if (action === 'delete' && !confirm('Delete this account permanently?')) return;
        const r = await api('/api/action', { method: 'POST', body: JSON.stringify({ action, uuid }) });
        toast(r.message || r.error || 'Done');
        refresh();
      }
      $('search').addEventListener('input', () => { api('/api/players').then(p => render(p.players)); });
      setInterval(refresh, 5000);
      </script>
      </body>
      </html>
      """;
}
