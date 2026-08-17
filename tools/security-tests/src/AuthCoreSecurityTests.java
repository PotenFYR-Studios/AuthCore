import java.util.UUID;
import net.ded3ec.security.Encrypter;
import net.ded3ec.security.RateLimiter;
import net.ded3ec.security.Security;
import net.ded3ec.network.ProxySupport;
import net.ded3ec.network.VelocitySupport;
import net.ded3ec.util.TimeManager;

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
    testVelocityForwarding();
    testDeviceFingerprint();
    testTimeManager();

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
    check("bare private ip accepted", "127.0.0.1".equals(ProxySupport.parseForwardedIp("127.0.0.1")));
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
}
