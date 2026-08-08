import java.util.UUID;
import net.ded3ec.security.Encrypter;
import net.ded3ec.security.RateLimiter;
import net.ded3ec.security.Security;
import net.ded3ec.network.ProxySupport;
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
    testCaptcha();
    testEmailRecovery();
    testRateLimiter();
    testProxyParsing();
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

  private static void testCaptcha() {
    System.out.println("== Captcha ==");
    UUID player = UUID.randomUUID();
    String code = Security.CaptchaManager.generate(player, 5, 60000);
    check("generate returns a code", code != null && code.length() >= 4);
    check("verify accepts the correct code (case-insensitive)", Security.CaptchaManager.verify(player, code.toLowerCase()));
    check("verify fails after consumption", !Security.CaptchaManager.verify(player, code));

    UUID player2 = UUID.randomUUID();
    String code2 = Security.CaptchaManager.generate(player2, 5, 60000);
    check("verify rejects a wrong code but keeps it", !Security.CaptchaManager.verify(player2, "WRONG"));
    check("correct code still works after a typo", Security.CaptchaManager.verify(player2, code2));

    UUID expired = UUID.randomUUID();
    Security.CaptchaManager.generate(expired, 5, -1000); // already expired
    check("expired captcha is rejected", !Security.CaptchaManager.verify(expired, "ABCDE"));
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
