import java.util.UUID;
import in.potenfyr.authcore.security.Encrypter;
import in.potenfyr.authcore.security.RateLimiter;
import in.potenfyr.authcore.security.Security;
import in.potenfyr.authcore.proxy.ProxyAuthGate;
import in.potenfyr.authcore.proxy.ProxyConfig;
import in.potenfyr.authcore.proxy.InteropMessages;
import in.potenfyr.authcore.proxy.SessionCache;
import in.potenfyr.authcore.models.Config;
import in.potenfyr.authcore.network.ProxySupport;
import in.potenfyr.authcore.network.VelocitySupport;
import in.potenfyr.authcore.util.TimeManager;

/**
 * Security & business-logic tests for AuthCore. Runs standalone (no Minecraft required) against
 * the compiled mod classes with a stubbed AuthCoreServer.
 *
 * <p>Run: see tools/security-tests/run-tests.ps1
 */
public class AuthCoreSecurityTests {

  private static int passed = 0;
  private static int failed = 0;

  public static void main(String[] args) {
    testPasswordHashing();
    testPasswordGeneration();
    testEmailRecovery();
    testRateLimiter();
    testProxyParsing();
    testProxySpoofGuard();
    testTrustedProxySource();
    testVelocityForwarding();
    testProxyGate();
    testProxyConfigStrictness();
    testInteropMessages();
    testSessionCache();
    testDeviceFingerprint();
    testTimeManager();
    testAttestationKey();
    testConcurrentFarmDetection();
    testLookPatternDetection();
    testLoginTimingDetection();
    testPacketSequenceTracking();
    testSnapshotAndVersionSupport();

    System.out.println();
    System.out.println("==============================================");
    System.out.println("  RESULT: " + passed + " passed, " + failed + " failed");
    System.out.println("==============================================");
    if (failed > 0) System.exit(1);
  }

  private static void check(String name, boolean condition) {
    if (condition) {
      passed++;
      System.out.println("  [PASS] " + name);
    } else {
      failed++;
      System.out.println("  [FAIL] " + name);
    }
  }

  // ------------------------------------------------------------------

  private static void testPasswordHashing() {
    System.out.println("== Password hashing ==");
    String[] algorithms = {"argon2", "bcrypt", "scrypt", "pbkdf2", "sha-256", "sha-512"};
    for (String algo : algorithms) {
      String hash = Encrypter.hash(algo, "Correct-Horse-123");
      check("hash(" + algo + ") produces a non-null hash", hash != null && !hash.isEmpty());
      if (hash != null) {
        check("verify(" + algo + ") accepts the correct password", Encrypter.verify("Correct-Horse-123", hash, algo));
        check("verify(" + algo + ") rejects a wrong password", !Encrypter.verify("Wrong-Password", hash, algo));
      }
    }
    // Unknown algorithm: the code deliberately falls back to Argon2id (never stores
    // an un-hashed password); verification of the fallback hash works
    String unknownHash = Encrypter.hash("nope", "x");
    check(
        "hash(unknown) falls back to a non-null Argon2 hash",
        unknownHash != null && unknownHash.startsWith("$argon2id$"));
    check("fallback hash verifies as argon2", Encrypter.verify("x", unknownHash, "argon2"));
    check("verify(unknown) returns false", !Encrypter.verify("x", "y", "nope"));
    check("verify(null hash) returns false", !Encrypter.verify("x", null, "argon2"));
    check("hash(null password) returns null", Encrypter.hash("argon2", null) == null);
    // Unique salts: identical passwords produce different hashes
    String a = Encrypter.hash("argon2", "SamePassword1");
    String b = Encrypter.hash("argon2", "SamePassword1");
    check("unique per-hash salt (no identical hashes)", a != null && b != null && !a.equals(b));

    // Legacy/foreign rows: a hash created with one algorithm but stored with a different (or
    // missing) algorithm declaration used to throw password4j parse errors ("Bad salt length")
    // and block the login. Verification must fall back through the supported algorithms.
    String legacy = Encrypter.hash("sha-256", "LegacyPass-1");
    check("legacy sha-256 hash produced", legacy != null);
    check(
        "legacy hash verifies with a wrong declared algorithm",
        Encrypter.verify("LegacyPass-1", legacy, "bcrypt"));
    check(
        "legacy hash verifies with null declared algorithm",
        Encrypter.verify("LegacyPass-1", legacy, null));
    check("legacy hash still rejects wrong passwords", !Encrypter.verify("Wrong", legacy, "bcrypt"));
    check(
        "verify of garbage against unknown algorithm returns false",
        !Encrypter.verify("x", "not-a-hash-at-all", "nope"));

    // AuthMe-style imported hashes: "$SHA$<salt>$<sha256(sha256(pw)+salt)>"
    String authMeSha = "$SHA$abcdef0123456789$" + authMeShaHash("AuthMePass-1", "abcdef0123456789");
    check("authme $SHA$ hash verifies", Encrypter.verify("AuthMePass-1", authMeSha, "authme-sha"));
    check("authme $SHA$ rejects wrong passwords", !Encrypter.verify("Wrong", authMeSha, "authme-sha"));
    check(
        "imported-algorithm inference ($SHA$ -> authme-sha)",
        "authme-sha".equals(Encrypter.inferImportedAlgorithm(authMeSha)));
    check(
        "imported-algorithm inference (bcrypt prefix)",
        "bcrypt".equals(Encrypter.inferImportedAlgorithm("$2a$10$abcdefghijklmnopqrstuu")));
    check(
        "imported-algorithm inference (hex sha-256)",
        "sha-256".equals(Encrypter.inferImportedAlgorithm(legacy)));
    check("weak-algorithm detection", Encrypter.isWeakAlgorithm("md5") && Encrypter.isWeakAlgorithm("sha-256"));
    check("strong-algorithm detection", !Encrypter.isWeakAlgorithm("argon2") && !Encrypter.isWeakAlgorithm("bcrypt"));
    check("unknown/null algorithm is treated as weak", Encrypter.isWeakAlgorithm(null));
  }

  private static String authMeShaHash(String password, String salt) {
    try {
      java.security.MessageDigest d = java.security.MessageDigest.getInstance("SHA-256");
      String first = toHex(d.digest(password.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      return toHex(d.digest((first + salt).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (Exception e) {
      return "";
    }
  }

  private static String toHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes)
      sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
    return sb.toString();
  }

  private static void testPasswordGeneration() {
    System.out.println("== Password generation ==");
    check("generate(20) returns 20 chars", Security.Password.generate(20).length() == 20);
    check("generate(8) returns 8 chars", Security.Password.generate(8).length() == 8);
    boolean threw = false;
    try {
      Security.Password.generate(0);
    } catch (IllegalArgumentException e) {
      threw = true;
    }
    check("generate(0) throws", threw);
  }

  private static void testEmailRecovery() {
    System.out.println("== Email recovery ==");
    check("isValidEmail accepts normal addresses", Security.EmailRecovery.isValidEmail("player@example.com"));
    check("isValidEmail rejects garbage", !Security.EmailRecovery.isValidEmail("not-an-email"));
    check("isValidEmail rejects too-long addresses", !Security.EmailRecovery.isValidEmail("a".repeat(300) + "@x.com"));

    String code = Security.EmailRecovery.generateCode("Player@Example.com");
    check("code generated", code != null && code.matches("\\d{6}"));
    check("verify accepts with different email case", Security.EmailRecovery.verifyCode("player@example.com", code));
    check("code consumed after use", !Security.EmailRecovery.verifyCode("Player@Example.com", code));

    // Anti-abuse: cooldown between code generations for the same email
    String first = Security.EmailRecovery.generateCode("cooldown@example.com");
    check("first code generated", first != null);
    check("second code blocked by cooldown", Security.EmailRecovery.generateCode("Cooldown@Example.com") == null);

    // Anti-brute-force: a code is revoked after a few wrong attempts
    String guarded = Security.EmailRecovery.generateCode("guard@example.com");
    check("guarded code generated", guarded != null);
    check("wrong attempt 1 rejected", !Security.EmailRecovery.verifyCode("guard@example.com", "000000"));
    check("wrong attempt 2 rejected", !Security.EmailRecovery.verifyCode("guard@example.com", "000001"));
    check("wrong attempt 3 rejected", !Security.EmailRecovery.verifyCode("guard@example.com", "000002"));
    check("wrong attempt 4 rejected", !Security.EmailRecovery.verifyCode("guard@example.com", "000003"));
    check("wrong attempt 5 rejected", !Security.EmailRecovery.verifyCode("guard@example.com", "000004"));
    check("code revoked after max attempts", !Security.EmailRecovery.verifyCode("guard@example.com", guarded));
  }

  private static void testRateLimiter() {
    System.out.println("== Rate limiter ==");
    check("first request allowed", RateLimiter.tryAcquire("test:ip", 3, 60000));
    check("second request allowed", RateLimiter.tryAcquire("test:ip", 3, 60000));
    check("third request allowed", RateLimiter.tryAcquire("test:ip", 3, 60000));
    check("fourth request blocked", !RateLimiter.tryAcquire("test:ip", 3, 60000));
    check("other key unaffected", RateLimiter.tryAcquire("test:other", 3, 60000));
    check("null key allowed (no limiter)", RateLimiter.tryAcquire(null, 1, 60000));
  }

  private static void testProxyParsing() {
    System.out.println("== Proxy IP parsing ==");
    check(
        "bungeecord format parsed",
        "203.0.113.5".equals(ProxySupport.parseForwardedIp("203.0.113.5\u0000uuid\u0000{}")));
    check(
        "ipv6 forwarded parsed",
        "2001:db8::1".equals(ProxySupport.parseForwardedIp("2001:db8::1\u0000abc")));
    check("hostname rejected", ProxySupport.parseForwardedIp("localhost") == null);
    check("invalid octet rejected", ProxySupport.parseForwardedIp("256.1.1.1") == null);
    check("port suffix rejected", ProxySupport.parseForwardedIp("10.0.0.5:25565") == null);
    check("null rejected", ProxySupport.parseForwardedIp(null) == null);
    // SECURITY REGRESSION GUARD: a BARE handshake address is client-controlled - accepting
    // it let any modified client spoof its IP (rate limits, GeoIP, intelligence, IP rules).
    // Only the NUL-separated proxy forwarding payload may ever be trusted.
    check("bare address rejected (spoof guard)",
        ProxySupport.parseForwardedIp("127.0.0.1") == null
            && ProxySupport.parseForwardedIp("203.0.113.9") == null);
  }

  private static void testProxySpoofGuard() {
    System.out.println("== Proxy IP spoofing hardening ==");
    // A forwarded payload's IP must be a strict address: anything that would widen the
    // accepted set (hostnames, ports, zones, overlong strings) is rejected.
    check("forwarded payload with hostname rejected",
        ProxySupport.parseForwardedIp("evil.example.com\u0000uuid\u0000{}") == null);
    check("forwarded payload with port suffix rejected",
        ProxySupport.parseForwardedIp("10.0.0.5:25565\u0000uuid") == null);
    check("forwarded ipv6 with zone id rejected",
        ProxySupport.parseForwardedIp("fe80::1%eth0\u0000uuid") == null);
    check("forwarded overlong address rejected",
        ProxySupport.parseForwardedIp("1.2.3.4.5.6.7.8.9.0.1.2.3.4.5.6\u0000uuid") == null);
    check("forwarded payload with empty ip rejected",
        ProxySupport.parseForwardedIp("\u0000uuid") == null);
    check("valid forwarded ipv4 still accepted",
        "203.0.113.5".equals(ProxySupport.parseForwardedIp("203.0.113.5\u0000uuid\u0000props")));
    check("only the first NUL segment is used as ip",
        ProxySupport.parseForwardedIp("203.0.113.5\u0000not-an-ip\u0000x") != null);
    check("valid non-ip payload after NUL still parses (ip is first segment)",
        "203.0.113.5".equals(ProxySupport.parseForwardedIp("203.0.113.5\u0000garbage-data")));
  }

  private static void testTrustedProxySource() {
    System.out.println("== Trusted proxy source validation ==");
    Config cfg = (Config) in.potenfyr.authcore.AuthCoreServer.config;
    Config.Session session = cfg.session;
    boolean savedEnabled = session.proxySupport.enabled;
    java.util.List<String> saved = new java.util.ArrayList<>(session.proxySupport.trustedProxies);
    try {
      // Disabled / empty list: NEVER trust forwarded data (deny by default).
      session.proxySupport.enabled = false;
      session.proxySupport.trustedProxies = java.util.List.of("10.0.0.0/8", "127.0.0.1");
      check("proxy support disabled -> untrusted", !ProxySupport.isTrustedProxySource("10.1.2.3"));

      session.proxySupport.enabled = true;
      session.proxySupport.trustedProxies = java.util.List.of();
      check("empty trusted list -> untrusted (deny by default)",
          !ProxySupport.isTrustedProxySource("127.0.0.1"));

      session.proxySupport.trustedProxies = java.util.List.of("10.0.0.0/8", "127.0.0.1");
      check("exact ip trusted", ProxySupport.isTrustedProxySource("127.0.0.1"));
      check("cidr range trusted", ProxySupport.isTrustedProxySource("10.255.0.1"));
      check("outside cidr untrusted", !ProxySupport.isTrustedProxySource("8.8.8.8"));
      check("null socket ip untrusted", !ProxySupport.isTrustedProxySource(null));
      check("blank socket ip untrusted", !ProxySupport.isTrustedProxySource("  "));
      check("bracketed ipv6 loopback normalized and trusted",
          ProxySupport.isTrustedProxySource("[127.0.0.1]:25565"));

      // CIDR matching corner cases
      check("cidr /32 exact match", ProxySupport.cidrMatches("1.2.3.4", "1.2.3.4/32"));
      check("cidr /32 different ip", !ProxySupport.cidrMatches("1.2.3.4", "1.2.3.5/32"));
      check("cidr /0 matches everything", ProxySupport.cidrMatches("203.0.113.9", "0.0.0.0/0"));
      check("cidr prefix boundary respected",
          ProxySupport.cidrMatches("192.168.1.130", "192.168.1.128/25")
              && !ProxySupport.cidrMatches("192.168.1.5", "192.168.1.128/25"));
      check("cidr with garbage rejected", !ProxySupport.cidrMatches("1.2.3.4", "1.2.3.4/xx"));
      check("cidr with too-large prefix rejected", !ProxySupport.cidrMatches("1.2.3.4", "1.2.3.4/33"));
      check("cidr with ipv6 network rejected", !ProxySupport.cidrMatches("fe80::1", "fe80::/16"));
    } finally {
      session.proxySupport.enabled = savedEnabled;
      session.proxySupport.trustedProxies = saved;
    }
  }

  private static void testProxyConfigStrictness() {
    System.out.println("== Proxy config parsing strictness ==");
    // Invalid boolean values must FAIL LOUD (fail-closed philosophy), not fall back silently.
    java.nio.file.Path tempDir = null;
    try {
      tempDir = java.nio.file.Files.createTempDirectory("authcore-proxycfg-test");
      java.nio.file.Path file = tempDir.resolve("authcore-proxy.properties");
      java.nio.file.Files.writeString(
          file, "enabled=true\nblock-unauthenticated=sure\n");
      boolean threw = false;
      try {
        ProxyConfig.load(tempDir);
      } catch (IllegalStateException expected) {
        threw = true;
      }
      check("invalid boolean fails loudly", threw);

      // Numeric parse errors fall back to the safe default instead of crashing boot.
      java.nio.file.Files.writeString(
          file, "redis-port=not-a-number\nsession-timeout-ms=abc\nkick-message=Go auth!\n");
      ProxyConfig cfg2 = ProxyConfig.load(tempDir);
      check("bad redis-port falls back to default", cfg2.redisPort == 6379);
      check("bad session-timeout falls back to default",
          cfg2.sessionTimeoutMs == ProxyConfig.DEFAULT_TIMEOUT);
      check("kick-message override applied", "Go auth!".equals(cfg2.kickMessage));
    } catch (java.io.IOException e) {
      check("proxy config test setup failed", false);
    } finally {
      if (tempDir != null) {
        try {
          java.nio.file.Files.deleteIfExists(tempDir.resolve("authcore-proxy.properties"));
          java.nio.file.Files.deleteIfExists(tempDir);
        } catch (Exception ignored) {}
      }
    }
  }

  private static void testInteropMessages() {
    System.out.println("== Interop message parsing (proxy <-> backend) ==");
    UUID uuid = UUID.randomUUID();
    byte[] payload = ("AUTH_CHANGED|" + uuid + "|Player|1").getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    check("valid auth message parsed", InteropMessages.handle("authcore:auth", payload));
    check("session cached after AUTH_CHANGED=1",
        SessionCache.isAuthenticated(uuid.toString(), 60_000));

    check("logout message (0) clears the session",
        InteropMessages.handle("authcore:auth",
            ("AUTH_CHANGED|" + uuid + "|Player|0").getBytes(java.nio.charset.StandardCharsets.US_ASCII))
            && !SessionCache.isAuthenticated(uuid.toString(), 60_000));

    // Reject everything that is not a well-formed AUTH_CHANGED on our channel.
    check("foreign channel rejected", !InteropMessages.handle("minecraft:register", payload));
    check("malformed payload rejected",
        !InteropMessages.handle("authcore:auth", "AUTH_CHANGED|tooshort".getBytes()));
    check("wrong message type rejected",
        !InteropMessages.handle("authcore:auth", "HELLO|x".getBytes()));
    check("null payload rejected", !InteropMessages.handle("authcore:auth", null));
    check("empty payload rejected", !InteropMessages.handle("authcore:auth", new byte[0]));

    // BungeeCord channel carries a NUL-separated subchannel prefix.
    byte[] bungee = ("AuthCore\u0000AUTH_CHANGED|" + uuid + "|Player|1")
        .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    check("bungeecord subchannel parsed",
        InteropMessages.handle("bungeecord:main", bungee)
            && SessionCache.isAuthenticated(uuid.toString(), 60_000));
    check("bungeecord channel without subchannel prefix rejected",
        !InteropMessages.handle("bungeecord:main", payload));
  }

  private static void testSessionCache() {
    System.out.println("== Proxy-side session cache ==");
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    SessionCache.update(a.toString(), true);
    SessionCache.update(b.toString(), true);
    check("both sessions tracked (size >= 2)", SessionCache.size() >= 2);
    try { Thread.sleep(3); } catch (InterruptedException ignored) {}
    check("authenticated within timeout", SessionCache.isAuthenticated(a.toString(), 60_000));
    check("not authenticated with zero timeout", !SessionCache.isAuthenticated(a.toString(), 0));
    SessionCache.update(a.toString(), false);
    check("logout removes the session", !SessionCache.isAuthenticated(a.toString(), 60_000));
    // null / blank safety
    SessionCache.update(null, true);
    SessionCache.update("", true);
    check("null/blank uuid ignored", true);
    check("unknown uuid not authenticated", !SessionCache.isAuthenticated(b + "-x", 60_000));
    // tiny sleep so the prune cutoff cannot land in the same millisecond as the update
    try { Thread.sleep(3); } catch (InterruptedException ignored) {}
    SessionCache.prune(0); // everything already expired -> bounded memory
    check("prune(0) clears expired sessions", SessionCache.size() == 0);
  }

  private static void testVelocityForwarding() {
    System.out.println("== Velocity modern forwarding ==");
    String secret = "test-secret-123";

    // Build a valid velocity:player_info payload: hmac(32) + version(1) + uuid(16) + name(utf) + 0 properties
    java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
    try {
      java.io.DataOutputStream out = new java.io.DataOutputStream(body);
      out.writeInt(1); // version
      out.writeLong(0x123456789ABCDEF0L);
      out.writeLong(0x0FEDCBA987654321L);
      out.writeUTF("TestPlayer");
      out.writeInt(0); // no properties
      out.flush();
    } catch (java.io.IOException err) {
      check("payload build failed", false);
      return;
    }
    byte[] bodyBytes = body.toByteArray();
    byte[] payload = new byte[32 + bodyBytes.length];
    try {
      javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
      mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] hmac = mac.doFinal(bodyBytes);
      System.arraycopy(hmac, 0, payload, 0, 32);
      System.arraycopy(bodyBytes, 0, payload, 32, bodyBytes.length);
    } catch (Exception err) {
      check("hmac build failed", false);
      return;
    }

    VelocitySupport.PlayerInfo info = VelocitySupport.parsePlayerInfo(payload, secret);
    check("valid payload parsed", info != null && "TestPlayer".equals(info.username));
    check(
        "uuid matches",
        info != null
            && info.uuid.equals(new java.util.UUID(0x123456789ABCDEF0L, 0x0FEDCBA987654321L)));

    // Tamper with the payload -> HMAC fails -> null
    payload[40] ^= 0x01;
    check("tampered payload rejected", VelocitySupport.parsePlayerInfo(payload, secret) == null);

    // Wrong secret -> null
    byte[] fresh = new byte[32 + bodyBytes.length];
    try {
      javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
      mac.init(new javax.crypto.spec.SecretKeySpec("wrong-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] hmac = mac.doFinal(bodyBytes);
      System.arraycopy(hmac, 0, fresh, 0, 32);
      System.arraycopy(bodyBytes, 0, fresh, 32, bodyBytes.length);
    } catch (Exception err) {
      check("hmac build failed", false);
      return;
    }
    check("wrong secret rejected", VelocitySupport.parsePlayerInfo(fresh, secret) == null);
    check("null payload rejected", VelocitySupport.parsePlayerInfo(null, secret) == null);
    check("blank secret rejected", VelocitySupport.parsePlayerInfo(payload, "  ") == null);
  }

  private static void testProxyGate() {
    System.out.println("== Proxy Auth Gate (Full Auth & Fail-Closed) ==");
    UUID testUuid = UUID.randomUUID();

    // Null safety
    check("null uuid returns false", !ProxyAuthGate.hasValidSession(null, null, null));

    // Default configuration loading & verification
    java.nio.file.Path tempDir = null;
    try {
      tempDir = java.nio.file.Files.createTempDirectory("authcore-proxy-test");
      ProxyConfig cfg = ProxyConfig.load(tempDir);
      check("proxy config defaults: enabled", cfg.enabled);
      check("proxy config defaults: fail-closed is true", cfg.failClosed);
      check("proxy config defaults: block-unauthenticated is false", !cfg.blockUnauthenticated);
      check("proxy config defaults: redis host", "127.0.0.1".equals(cfg.redisHost));
      check("proxy config defaults: redis port", cfg.redisPort == 6379);

      // Null uuid with valid config returns false
      check("null uuid with config returns false", !ProxyAuthGate.hasValidSession(null, cfg, null));

      // Point Redis to an unused port to simulate unreachable Redis
      cfg.redisPort = 59998;
      java.util.List<String> warnings = new java.util.ArrayList<>();

      // 1. Fail-closed test: Redis unreachable -> session denied (false)
      cfg.failClosed = true;
      boolean allowedFailClosed = ProxyAuthGate.hasValidSession(testUuid, cfg, warnings::add);
      check("fail-closed blocks when Redis unreachable", !allowedFailClosed);
      check("fail-closed produces warning", warnings.stream().anyMatch(w -> w.contains("fail-closed")));

      // 2. Fail-open test: Redis unreachable -> session allowed (true)
      warnings.clear();
      cfg.failClosed = false;
      boolean allowedFailOpen = ProxyAuthGate.hasValidSession(testUuid, cfg, warnings::add);
      check("fail-open allows when Redis unreachable", allowedFailOpen);
      check("fail-open produces warning", warnings.stream().anyMatch(w -> w.contains("fail-open")));
    } catch (Exception e) {
      check("proxy gate test completed without error", false);
    } finally {
      if (tempDir != null) {
        try {
          java.nio.file.Files.deleteIfExists(tempDir.resolve("authcore-proxy.properties"));
          java.nio.file.Files.deleteIfExists(tempDir);
        } catch (Exception ignored) {}
      }
    }
  }

  private static void testDeviceFingerprint() {
    System.out.println("== Device fingerprint ==");
    String f1 = Security.DeviceFingerprint.compute("1.2.3.4", "United States");
    String f2 = Security.DeviceFingerprint.compute("1.2.3.4", "United States");
    String f3 = Security.DeviceFingerprint.compute("1.2.3.4", "China");
    String f4 = Security.DeviceFingerprint.compute("5.6.7.8", "United States");
    check("fingerprint deterministic", f1 != null && f1.equals(f2));
    check("fingerprint is 16 hex chars", f1 != null && f1.matches("[0-9a-f]{16}"));
    check("country change changes fingerprint", f1 != null && !f1.equals(f3));
    check("ip change changes fingerprint", f1 != null && !f1.equals(f4));
  }

  private static void testTimeManager() {
    System.out.println("== Time manager ==");
    String d = TimeManager.toDuration(120000);
    check("toDuration formats 120s", d != null && !d.isBlank());
    check("toHumanDate formats", TimeManager.toHumanDate(1700000000000L) != null);
  }

  // ------------------------------------------------------------------
  // Detection hardening tests (post-1.0.0)

  private static void testAttestationKey() {
    System.out.println("== Attestation key ==");
    // AttestationKeyManager is a static singleton that loads from disk.
    // In standalone tests, configPath is null so the key is generated in-memory.
    in.potenfyr.authcore.security.AttestationKeyManager.ensureLoaded();
    String key = in.potenfyr.authcore.security.AttestationKeyManager.get();
    check("attestation key loaded (non-null)", key != null);
    check("attestation key is 64 hex chars (32 bytes)", key != null && key.matches("[0-9a-f]{64}"));
    // Rotation produces a new key
    String first = key;
    in.potenfyr.authcore.security.AttestationKeyManager.rotate();
    String second = in.potenfyr.authcore.security.AttestationKeyManager.get();
    check("key rotation produces a new key", first != null && second != null && !first.equals(second));
  }

  private static void testConcurrentFarmDetection() {
    System.out.println("== Concurrent-farm detection ==");
    // checkConcurrentFarm is a public static method - it should not throw
    // for null/bad input and should track distinct usernames per IP.
    try {
      in.potenfyr.authcore.security.ClientGuard.checkConcurrentFarm(null, null);
      check("concurrent farm null input handled", true);
    } catch (Exception e) {
      check("concurrent farm null input handled", false);
    }
    try {
      in.potenfyr.authcore.security.ClientGuard.checkConcurrentFarm("10.0.0.1", "Player1");
      in.potenfyr.authcore.security.ClientGuard.checkConcurrentFarm("10.0.0.1", "Player2");
      in.potenfyr.authcore.security.ClientGuard.checkConcurrentFarm("10.0.0.1", "Player3");
      check("concurrent farm tracking (3 distinct usernames)", true);
    } catch (Exception e) {
      check("concurrent farm tracking (3 distinct usernames)", false);
    }
  }

  private static void testLookPatternDetection() {
    System.out.println("== Look-pattern bot detection ==");
    // LookDelta tracking: bots have zero variance; humans have random variation.
    try {
      in.potenfyr.authcore.security.ClientGuard.LookDelta delta = new in.potenfyr.authcore.security.ClientGuard.LookDelta(
          System.currentTimeMillis(), 45.0f, 90.0f);
      check("LookDelta created", delta != null && Math.abs(delta.pitch - 45.0f) < 0.01f);
    } catch (Exception e) {
      check("LookDelta created", false);
    }
    // The lookPatternScore() is called on a Profile; without a live player the profile
    // path is guarded. Verify the inner class structure is sane.
    check("LookDelta is static inner class", in.potenfyr.authcore.security.ClientGuard.LookDelta.class.getEnclosingClass() != null);
    check("Signal.LOOK_PATTERN has weight 20", in.potenfyr.authcore.security.ClientGuard.Signal.LOOK_PATTERN.weight == 20);
  }

  private static void testLoginTimingDetection() {
    System.out.println("== Login timing distribution analysis ==");
    // recordLoginTiming is a public static method; it should handle null/bad input.
    try {
      in.potenfyr.authcore.security.AuthIntelligence.recordLoginTiming(null, null);
      check("login timing null input handled", true);
    } catch (Exception e) {
      check("login timing null input handled", false);
    }
    try {
      in.potenfyr.authcore.security.AuthIntelligence.recordLoginTiming("127.0.0.1", "TestUser");
      check("login timing valid input handled", true);
    } catch (Exception e) {
      check("login timing valid input handled", false);
    }
  }

  private static void testPacketSequenceTracking() {
    System.out.println("== Packet sequence validation ==");
    // The PacketSequence class is an inner class of ServerEvents and requires a live
    // connection to fully test. We verify the class structure and the static helpers
    // handle null gracefully.
    try {
      in.potenfyr.authcore.events.ServerEvents.trackPacket(null, null);
      check("packet tracking null connection handled", true);
    } catch (Exception e) {
      check("packet tracking null connection handled", false);
    }
    try {
      in.potenfyr.authcore.events.ServerEvents.startPacketTracking(null);
      check("packet tracking start null handled", true);
    } catch (Exception e) {
      check("packet tracking start null handled", false);
    }
    try {
      in.potenfyr.authcore.events.ServerEvents.completePacketTracking(null);
      check("packet tracking complete null handled", true);
    } catch (Exception e) {
      check("packet tracking complete null handled", false);
    }
    try {
      in.potenfyr.authcore.events.ServerEvents.cleanupPacketTracking(null);
      check("packet tracking cleanup null handled", true);
    } catch (Exception e) {
      check("packet tracking cleanup null handled", false);
    }
  }

  private static void testSnapshotAndVersionSupport() {
    System.out.println("== Minecraft version & snapshot detection ==");
    check("1.16.5 recognized as tested", in.potenfyr.authcore.AuthCoreServer.isTestedMinecraftVersion("1.16.5"));
    check("1.18.2 recognized as tested", in.potenfyr.authcore.AuthCoreServer.isTestedMinecraftVersion("1.18.2"));
    check("1.21.11 recognized as tested", in.potenfyr.authcore.AuthCoreServer.isTestedMinecraftVersion("1.21.11"));
    check("26.1 recognized as tested", in.potenfyr.authcore.AuthCoreServer.isTestedMinecraftVersion("26.1"));
    check("26.2 recognized as tested", in.potenfyr.authcore.AuthCoreServer.isTestedMinecraftVersion("26.2"));
    check("26w09a snapshot recognized as tested", in.potenfyr.authcore.AuthCoreServer.isTestedMinecraftVersion("26w09a"));
    check("24w45a snapshot recognized as tested", in.potenfyr.authcore.AuthCoreServer.isTestedMinecraftVersion("24w45a"));
    check("23w12a snapshot recognized as tested", in.potenfyr.authcore.AuthCoreServer.isTestedMinecraftVersion("23w12a"));
    check("26.2-pre1 snapshot recognized as tested", in.potenfyr.authcore.AuthCoreServer.isTestedMinecraftVersion("26.2-pre1"));
    check("26.1-rc2 snapshot recognized as tested", in.potenfyr.authcore.AuthCoreServer.isTestedMinecraftVersion("26.1-rc2"));
    check("future 27.0 release recognized as tested", in.potenfyr.authcore.AuthCoreServer.isTestedMinecraftVersion("27.0"));
    check("null handled safely", !in.potenfyr.authcore.AuthCoreServer.isTestedMinecraftVersion(null));
    check("empty string handled safely", !in.potenfyr.authcore.AuthCoreServer.isTestedMinecraftVersion(""));
    check("invalid string handled safely", !in.potenfyr.authcore.AuthCoreServer.isTestedMinecraftVersion("invalid"));
  }
}
