package in.potenfyr.authcore.util;

import in.potenfyr.authcore.AuthCoreServer;
import in.potenfyr.authcore.events.EntityEvents;
import in.potenfyr.authcore.models.Config;
import in.potenfyr.authcore.models.Lobby;
import in.potenfyr.authcore.models.User;
import in.potenfyr.authcore.network.McApiManager;
import in.potenfyr.authcore.proxy.ProxyAuthGate;
import in.potenfyr.authcore.proxy.ProxyConfig;
import in.potenfyr.authcore.proxy.SessionCache;
import in.potenfyr.authcore.security.Encrypter;
import in.potenfyr.authcore.security.LobbyPacketPolicy;
import in.potenfyr.authcore.security.RateLimiter;
import in.potenfyr.authcore.security.Security;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * Built-in simulation/self-test suite, exposed through the highly restricted {@code /authcore
 * simulate} command and asserted by the Docker host harness.
 *
 * <p>Everything runs INSIDE the live server JVM on the real runtime: the actual mappings, the
 * configured hashing algorithm, the live database dialect and the pure landing/support geometry
 * driven by dummy block columns. That catches runtime linkage/mapping problems that a plain
 * compile check cannot, without needing a network client.
 *
 * <p>Side effects are bounded: temporary password-history rows are removed again, and the
 * rate-limiter check only adds short-lived self-test keys (the cache is LRU-bounded).
 */
public final class SelfTest {

  /** One simulation result. */
  public record Check(String name, boolean ok, String detail) {}

  /** Runs every simulation. Must be called on the server thread. */
  public static List<Check> run(MinecraftServer server) {
    List<Check> checks = new ArrayList<>();
    checks.add(checkHashRoundtrip());
    checks.add(checkLegacyMd5());
    checks.add(checkPasswordPolicy());
    checks.add(checkPasswordHistoryDatabase());
    checks.add(checkRateLimiterEviction());
    checks.add(checkGroundResolverDummy());
    checks.add(checkSupportGeometryDummy());
    checks.add(checkRestoreHealthClamp());
    checks.add(checkLandingClearance());
    checks.add(checkConfigLoaded());
    checks.add(checkSessionFlowSimulation(server));
    checks.add(checkProxyGateSimulation());
    checks.add(checkSrgRuntimeSimulation());
    checks.add(checkBoundedCachesSimulation());
    checks.add(checkMapRenderSimulation(server));
    checks.add(checkMapLifecycleSimulation(server));
    checks.add(checkMapContentSimulation());
    checks.add(checkSanitizerSimulation());
    checks.add(checkLobbyPacketPolicySimulation());
    checks.add(checkConcurrencySimulation());
    return checks;
  }

  /** Real hash/verify round-trip with the configured algorithm. */
  private static Check checkHashRoundtrip() {
    try {
      String algorithm = AuthCoreServer.config.passwordRules.passwordHashAlgorithm;
      String password = "AuthCore-SelfTest-9x!";
      String hash = Encrypter.hash(algorithm, password);
      boolean ok =
          hash != null
              && Encrypter.verify(password, hash, algorithm)
              && !Encrypter.verify(password + "x", hash, algorithm);
      return new Check("password-hash-roundtrip", ok, String.valueOf(algorithm));
    } catch (Throwable err) {
      return new Check("password-hash-roundtrip", false, describe(err));
    }
  }

  /** Legacy MD5 verification against the known "abc" digest. */
  private static Check checkLegacyMd5() {
    try {
      String md5 = "900150983cd24fb0d6963f7d28e17f72";
      boolean ok = Encrypter.verify("abc", md5, "md5") && !Encrypter.verify("abd", md5, "md5");
      return new Check("legacy-md5-digest", ok, md5);
    } catch (Throwable err) {
      return new Check("legacy-md5-digest", false, describe(err));
    }
  }

  /** Password-policy decision function against the live configuration. */
  private static Check checkPasswordPolicy() {
    try {
      Security.Password.PolicyViolation blank = Security.Password.validate(null);
      Security.Password.PolicyViolation empty = Security.Password.validate("");
      Security.Password.PolicyViolation weak = Security.Password.validate("abc");
      Config.PasswordRules rules = AuthCoreServer.config.passwordRules;
      StringBuilder valid = new StringBuilder();
      valid.append("A".repeat(Math.max(0, rules.upperCase.min)));
      valid.append("a".repeat(Math.max(0, rules.lowerCase.min)));
      valid.append("1".repeat(Math.max(0, rules.digits.min)));
      while (valid.length() < rules.length.min) valid.append('a');
      Security.Password.PolicyViolation strong = Security.Password.validate(valid.toString());
      boolean ok =
          blank == Security.Password.PolicyViolation.BLANK
              && empty == Security.Password.PolicyViolation.BLANK
              && weak != Security.Password.PolicyViolation.NONE
              && strong == Security.Password.PolicyViolation.NONE
              && !Security.Password.violationText(weak).isEmpty();
      return new Check(
          "password-policy", ok, "weak=" + weak + " strong=" + strong);
    } catch (Throwable err) {
      return new Check("password-policy", false, describe(err));
    }
  }

  /** Password-history table round-trip (insert, trim, newest-first, cleanup) on the live DB. */
  private static Check checkPasswordHistoryDatabase() {
    try {
      String algorithm = AuthCoreServer.config.passwordRules.passwordHashAlgorithm;
      boolean ok = User.selfTestPasswordHistory(algorithm);
      return new Check("password-history-db", ok, "live-db-roundtrip=" + ok);
    } catch (Throwable err) {
      return new Check("password-history-db", false, describe(err));
    }
  }

  /** The rate limiter must evict old windows instead of globally failing closed. */
  private static Check checkRateLimiterEviction() {
    try {
      String prefix = "authcore-selftest-" + UUID.randomUUID();
      for (int i = 0; i < 10050; i++) RateLimiter.tryAcquire(prefix + i, 1, 60_000L);
      boolean admitted = RateLimiter.tryAcquire(prefix + "-fresh", 1, 60_000L);
      return new Check("rate-limiter-eviction", admitted, "fresh-key-admitted=" + admitted);
    } catch (Throwable err) {
      return new Check("rate-limiter-eviction", false, describe(err));
    }
  }

  /** Pure ground resolver driven by dummy block columns (the issue #11 code path). */
  private static Check checkGroundResolverDummy() {
    try {
      Map<Integer, Lobby.Snapshot.GroundType> column = new HashMap<>();
      column.put(63, Lobby.Snapshot.GroundType.LANDING);
      Lobby.Snapshot.BlockColumn landing = y -> column.getOrDefault(y, Lobby.Snapshot.GroundType.AIR);
      boolean findsGround = Integer.valueOf(63).equals(Lobby.Snapshot.findGroundBelow(64, 0, landing));
      boolean allAir = Lobby.Snapshot.findGroundBelow(64, 0, y -> Lobby.Snapshot.GroundType.AIR) == null;
      Map<Integer, Lobby.Snapshot.GroundType> passable = new HashMap<>();
      passable.put(63, Lobby.Snapshot.GroundType.PASSABLE);
      passable.put(62, Lobby.Snapshot.GroundType.LANDING);
      boolean skipsPassable =
          Integer.valueOf(62)
              .equals(
                  Lobby.Snapshot.findGroundBelow(
                      64, 0, y -> passable.getOrDefault(y, Lobby.Snapshot.GroundType.AIR)));
      boolean fluidStops =
          Lobby.Snapshot.findGroundBelow(
                  64, 0, y -> y == 63 ? Lobby.Snapshot.GroundType.FLUID : Lobby.Snapshot.GroundType.AIR)
              == null;
      boolean blockedStops =
          Lobby.Snapshot.findGroundBelow(
                  64, 0, y -> y == 63 ? Lobby.Snapshot.GroundType.BLOCKED : Lobby.Snapshot.GroundType.AIR)
              == null;
      boolean boundsSafe = Lobby.Snapshot.findGroundBelow(64, 70, landing) == null;
      boolean ok =
          findsGround && allAir && skipsPassable && fluidStops && blockedStops && boundsSafe;
      return new Check(
          "ground-resolver-dummy",
          ok,
          "landing=" + findsGround + " air=" + allAir + " passable=" + skipsPassable
              + " fluid=" + fluidStops + " blocked=" + blockedStops + " bounds=" + boundsSafe);
    } catch (Throwable err) {
      return new Check("ground-resolver-dummy", false, describe(err));
    }
  }

  /** Pure bounding-box support geometry driven by dummy block shapes (anti-float/restore guard). */
  private static Check checkSupportGeometryDummy() {
    try {
      Lobby.Snapshot.BlockSupport ground = (x, y, z) -> y == 63;
      boolean supported = Lobby.Snapshot.hasSupportBelow(0.2, 0.8, 64.0, 0.2, 0.8, ground);
      boolean unsupported = Lobby.Snapshot.hasSupportBelow(0.2, 0.8, 64.0, 0.2, 0.8, (x, y, z) -> false);
      // A block one lower still counts (tolerates the restore teleport's +0.5 offset).
      boolean oneLower = Lobby.Snapshot.hasSupportBelow(0.2, 0.8, 64.0, 0.2, 0.8, (x, y, z) -> y == 62);
      boolean ok = supported && !unsupported && oneLower;
      return new Check(
          "support-geometry-dummy",
          ok,
          "supported=" + supported + " unsupported=" + unsupported + " one-lower=" + oneLower);
    } catch (Throwable err) {
      return new Check("support-geometry-dummy", false, describe(err));
    }
  }

  /**
   * Pure landing clearance rule (issue #11 follow-up): headroom is decided by collision height, not
   * by air. A decorated surface (tall grass, flowers, torches, snow layers, carpets, slabs) must
   * still count as a usable landing - requiring strict air made every such landing "blocked", so a
   * mid-air restore found no ground and the player fell to their death.
   */
  private static Check checkLandingClearance() {
    try {
      boolean noCollision = Lobby.Snapshot.isClearanceHeight(0.0D);
      boolean snowLayer = Lobby.Snapshot.isClearanceHeight(0.125D);
      boolean carpet = Lobby.Snapshot.isClearanceHeight(0.0625D);
      boolean slab = Lobby.Snapshot.isClearanceHeight(0.5D);
      boolean threshold = Lobby.Snapshot.isClearanceHeight(0.6D);
      boolean fullBlock = !Lobby.Snapshot.isClearanceHeight(1.0D);
      boolean tallPartial = !Lobby.Snapshot.isClearanceHeight(1.5D);
      boolean ok =
          noCollision
              && snowLayer
              && carpet
              && slab
              && threshold
              && fullBlock
              && tallPartial;
      return new Check(
          "landing-clearance",
          ok,
          "empty=" + noCollision + " snow=" + snowLayer + " carpet=" + carpet + " slab=" + slab
              + " threshold=" + threshold + " full=" + fullBlock + " tall=" + tallPartial);
    } catch (Throwable err) {
      return new Check("landing-clearance", false, describe(err));
    }
  }

  /**
   * Pure restore-health clamp (issue #11 follow-up): a snapshot captured while the player was dead
   * must never be restored verbatim, and a corrupted value must never grant above max health.
   */
  private static Check checkRestoreHealthClamp() {
    try {
      boolean deadSafe = Lobby.Snapshot.clampRestoredHealth(0.0F, 20.0F) == 20.0F;
      boolean negativeSafe = Lobby.Snapshot.clampRestoredHealth(-4.0F, 20.0F) == 20.0F;
      boolean nanSafe = Lobby.Snapshot.clampRestoredHealth(Float.NaN, 20.0F) == 20.0F;
      boolean normalKept = Lobby.Snapshot.clampRestoredHealth(7.5F, 20.0F) == 7.5F;
      boolean tinyClamped = Lobby.Snapshot.clampRestoredHealth(0.25F, 20.0F) == 1.0F;
      boolean capped = Lobby.Snapshot.clampRestoredHealth(40.0F, 20.0F) == 20.0F;
      boolean modifiedMax = Lobby.Snapshot.clampRestoredHealth(18.0F, 6.0F) == 6.0F;
      boolean fallbackMax = Lobby.Snapshot.clampRestoredHealth(5.0F, 0.0F) == 5.0F;
      boolean ok =
          deadSafe
              && negativeSafe
              && nanSafe
              && normalKept
              && tinyClamped
              && capped
              && modifiedMax
              && fallbackMax;
      return new Check(
          "restore-health-clamp",
          ok,
          "dead=" + deadSafe + " negative=" + negativeSafe + " nan=" + nanSafe
              + " kept=" + normalKept + " tiny=" + tinyClamped + " capped=" + capped
              + " max=" + modifiedMax + " bad-max=" + fallbackMax);
    } catch (Throwable err) {
      return new Check("restore-health-clamp", false, describe(err));
    }
  }

  /** The live configuration must be fully mapped (guards against silent section-load failures). */
  private static Check checkConfigLoaded() {
    try {
      boolean ok =
          AuthCoreServer.config != null
              && AuthCoreServer.config.commands != null
              && AuthCoreServer.config.commands.admin != null
              && AuthCoreServer.config.commands.admin.simulate != null
              && AuthCoreServer.config.passwordRules != null;
      return new Check("config-loaded", ok, ok ? "sections present" : "missing section");
    } catch (Throwable err) {
      return new Check("config-loaded", false, describe(err));
    }
  }

  /**
   * Simulates the join -> limbo -> guard state machine with dummy objects: a real {@link User}
   * instance, real entity instances spawned via the console and the live lobby state map.
   * Exercises the same decision functions the packet/movement/attack mixins call, so the flow is
   * covered on every version/loader without a network client.
   */
  private static Check checkSessionFlowSimulation(MinecraftServer server) {
    UUID id = UUID.nameUUIDFromBytes("authcore-selftest-session".getBytes());
    User user = null;
    Config.Lobby lobby = AuthCoreServer.config.lobby;
    // Capture every flag the guard matrix touches so the live config is always restored.
    boolean[] original = {
      lobby.allowAttackingPlayer,
      lobby.allowAttackingHostileMobs,
      lobby.allowAttackingAnimals,
      lobby.allowAttackingFriendlyMobs,
      lobby.allowAttackNeutralMobs,
      lobby.allowAttackMountableEntity,
      lobby.allowAttackEntity,
      lobby.allowPlayerInteractWith,
      lobby.allowHostileMobsInteractWith,
      lobby.allowAnimalInteractWith,
      lobby.allowFriendlyMobsInteractWith,
      lobby.allowNeutralMobsInteractWith,
      lobby.allowMountableInteractWith,
      lobby.allowEntityInteractWith
    };
    try {
      ServerLevel level = server.overworld();
      net.minecraft.commands.CommandSourceStack source = server.createCommandSourceStack();
      net.minecraft.world.phys.Vec3 pos = source.getPosition();
      // Spawn dummy guard targets with vanilla commands (relative to the console's position, so
      // the chunk is always loaded). Referencing the entity classes directly would break on
      // runtime endpoints where those classes moved packages.
      in.potenfyr.authcore.compat.Compat.runConsoleCommand(
          server, "summon minecraft:zombie ~2 ~1 ~ {Tags:[\"authcore_selftest\"]}");
      in.potenfyr.authcore.compat.Compat.runConsoleCommand(
          server, "summon minecraft:cow ~3 ~1 ~ {Tags:[\"authcore_selftest\"]}");
      // Small AABB around the console position (the summon targets are placed relative to it).
      // A huge AABB must never be used: on 1.16-1.18 the entity lookup iterates every chunk
      // position inside the box, which turns a world-sized box into an effective hang.
      net.minecraft.world.phys.AABB area =
          new net.minecraft.world.phys.AABB(
              pos.x - 16, pos.y - 16, pos.z - 16, pos.x + 18, pos.y + 18, pos.z + 18);
      Entity zombie = null;
      Entity cow = null;
      int found = 0;
      for (Entity entity : level.getEntitiesOfClass(Entity.class, area, e -> true)) {
        found++;
        String category = entity.getType().getCategory().getName();
        if (zombie == null && category.equals("monster")) zombie = entity;
        if (cow == null && category.equals("creature")) cow = entity;
      }
      if (zombie == null || cow == null) {
        int all = 0;
        for (Entity ignored : level.getAllEntities()) all++;
        return new Check(
            "session-flow-simulation",
            false,
            "no guard targets (found=" + found + ", all=" + all + ", pos=" + pos + ", zombie="
                + (zombie != null) + ", cow=" + (cow != null) + ")");
      }

      user = new User(id, "AuthCoreSelfTest", System.currentTimeMillis(), false);
      boolean initiallyUnlocked = !user.isInLobby.get();

      // All guard rules allow everything first; each blocked case flips exactly one flag off, so
      // every assertion is attributable to the rule under test.
      lobby.allowAttackingPlayer = true;
      lobby.allowAttackingHostileMobs = true;
      lobby.allowAttackingAnimals = true;
      lobby.allowAttackingFriendlyMobs = true;
      lobby.allowAttackNeutralMobs = true;
      lobby.allowAttackMountableEntity = true;
      lobby.allowAttackEntity = true;
      lobby.allowPlayerInteractWith = true;
      lobby.allowHostileMobsInteractWith = true;
      lobby.allowAnimalInteractWith = true;
      lobby.allowFriendlyMobsInteractWith = true;
      lobby.allowNeutralMobsInteractWith = true;
      lobby.allowMountableInteractWith = true;
      lobby.allowEntityInteractWith = true;

      // Guard decisions with the dummy user (null connection -> no message side effects).
      lobby.allowAttackingHostileMobs = false;
      boolean monsterBlocked = EntityEvents.guardLobbyAttack(user, zombie);
      lobby.allowAttackingHostileMobs = true;
      boolean monsterAllowed = !EntityEvents.guardLobbyAttack(user, zombie);
      lobby.allowAttackingAnimals = false;
      boolean animalBlocked = EntityEvents.guardLobbyAttack(user, cow);
      lobby.allowAttackingAnimals = true;
      boolean animalAllowed = !EntityEvents.guardLobbyAttack(user, cow);
      lobby.allowAnimalInteractWith = false;
      boolean useBlocked = EntityEvents.guardLobbyUse(user, cow);
      lobby.allowAnimalInteractWith = true;
      boolean useAllowed = !EntityEvents.guardLobbyUse(user, cow);

      // Deferred limbo lock: the player entity is not resolvable yet -> must not throw.
      user.lobby.lock();
      boolean deferredSafe = !user.isInLobby.get();

      // Simulated limbo state -> unlock must clean up without a live entity.
      Lobby.users.put(user.username, user.lobby);
      boolean locked = user.isInLobby.get();
      user.lobby.unlock();
      boolean unlocked = !user.isInLobby.get() && !Lobby.users.containsKey(user.username);

      boolean ok =
          initiallyUnlocked
              && monsterBlocked
              && monsterAllowed
              && animalBlocked
              && animalAllowed
              && useBlocked
              && useAllowed
              && deferredSafe
              && locked
              && unlocked;
      return new Check(
          "session-flow-simulation",
          ok,
          "monster=" + monsterBlocked + "/" + monsterAllowed + " animal=" + animalBlocked + "/"
              + animalAllowed + " use=" + useBlocked + "/" + useAllowed + " deferred="
              + deferredSafe + " lock=" + locked + " unlock=" + unlocked);
    } catch (Throwable err) {
      return new Check("session-flow-simulation", false, describe(err));
    } finally {
      // Restore every flag the guard matrix touched (live server config must not stay modified).
      lobby.allowAttackingPlayer = original[0];
      lobby.allowAttackingHostileMobs = original[1];
      lobby.allowAttackingAnimals = original[2];
      lobby.allowAttackingFriendlyMobs = original[3];
      lobby.allowAttackNeutralMobs = original[4];
      lobby.allowAttackMountableEntity = original[5];
      lobby.allowAttackEntity = original[6];
      lobby.allowPlayerInteractWith = original[7];
      lobby.allowHostileMobsInteractWith = original[8];
      lobby.allowAnimalInteractWith = original[9];
      lobby.allowFriendlyMobsInteractWith = original[10];
      lobby.allowNeutralMobsInteractWith = original[11];
      lobby.allowMountableInteractWith = original[12];
      lobby.allowEntityInteractWith = original[13];
      Lobby.users.remove("AuthCoreSelfTest");
      try {
        in.potenfyr.authcore.compat.Compat.runConsoleCommand(
            server, "kill @e[tag=authcore_selftest]");
      } catch (Throwable ignored) {
        // cleanup is best-effort
      }
      if (user != null) {
        try {
          user.delete("self-test cleanup", false);
        } catch (Throwable ignored) {
          User.users.remove(id);
        }
      }
    }
  }

  /**
   * Simulates the proxy-mode gate with dummy configuration objects: the auth-server redirect
   * target matching, the interop session cache trust, and the fail-closed/fail-open behaviour
   * when no session can be confirmed. Runs inside the mod jar (no proxy API classes needed).
   */
  private static Check checkProxyGateSimulation() {
    Path dir = null;
    Path dirOpen = null;
    UUID id = UUID.nameUUIDFromBytes("authcore-selftest-proxy-session".getBytes());
    try {
      dir = Files.createTempDirectory("authcore-selftest-proxy");
      Files.writeString(
          dir.resolve("authcore-proxy.properties"),
          "enabled=true\n"
              + "auth-server=hub\n"
              + "block-unauthenticated=true\n"
              + "fail-closed=true\n"
              + "redis-host=\n"
              + "session-timeout-ms=60000\n",
          StandardCharsets.UTF_8);
      ProxyConfig cfg = ProxyConfig.load(dir);
      boolean parsed =
          cfg.blockUnauthenticated
              && cfg.failClosed
              && "hub".equals(cfg.authServer)
              && cfg.sessionTimeoutMs == 60_000L;
      boolean authServer =
          ProxyAuthGate.isAuthServer("hub", cfg)
              && ProxyAuthGate.isAuthServer("HUB", cfg)
              && !ProxyAuthGate.isAuthServer("game", cfg)
              && !ProxyAuthGate.isAuthServer("", cfg);

      SessionCache.update(id.toString(), true);
      boolean sessionTrusted = ProxyAuthGate.hasValidSession(id, cfg, null);
      SessionCache.update(id.toString(), false);
      boolean denied = !ProxyAuthGate.hasValidSession(id, cfg, null);

      dirOpen = Files.createTempDirectory("authcore-selftest-proxy-open");
      Files.writeString(
          dirOpen.resolve("authcore-proxy.properties"),
          "block-unauthenticated=true\nfail-closed=false\nredis-host=\n",
          StandardCharsets.UTF_8);
      ProxyConfig open = ProxyConfig.load(dirOpen);
      boolean allowed = ProxyAuthGate.hasValidSession(id, open, null);

      boolean ok = parsed && authServer && sessionTrusted && denied && allowed;
      return new Check(
          "proxy-gate-simulation",
          ok,
          "parsed=" + parsed + " auth-server=" + authServer + " session=" + sessionTrusted
              + " fail-closed-deny=" + denied + " fail-open-allow=" + allowed);
    } catch (Throwable err) {
      return new Check("proxy-gate-simulation", false, describe(err));
    } finally {
      SessionCache.update(id.toString(), false);
      try {
        if (dir != null) Files.deleteIfExists(dir.resolve("authcore-proxy.properties"));
        if (dir != null) Files.deleteIfExists(dir);
        if (dirOpen != null) Files.deleteIfExists(dirOpen.resolve("authcore-proxy.properties"));
        if (dirOpen != null) Files.deleteIfExists(dirOpen);
      } catch (Throwable ignored) {
        // temp cleanup is best-effort
      }
    }
  }

  /**
   * Simulates the Forge 1.16-1.18 (SRG) runtime shape: there the Mojang mapping names are not
   * reachable through reflection, so the permission resolver must fall back to allowing the
   * console (otherwise admins cannot run any admin command). Dummy command sources reproduce the
   * three shapes the resolver handles.
   */
  private static Check checkSrgRuntimeSimulation() {
    try {
      String node = "authcore.admin.simulate";
      boolean srgConsoleAllowed =
          McApiManager.PermissionUtil.hasForSource(new Object(), node, 4);
      boolean nullConsoleAllowed =
          McApiManager.PermissionUtil.hasForSource(new NullPlayerSource(), node, 4);
      boolean throwingConsoleAllowed =
          McApiManager.PermissionUtil.hasForSource(new ThrowingPlayerSource(), node, 4);
      boolean ok = srgConsoleAllowed && nullConsoleAllowed && throwingConsoleAllowed;
      return new Check(
          "srg-runtime-simulation",
          ok,
          "no-accessor=" + srgConsoleAllowed + " null-player=" + nullConsoleAllowed
              + " throwing-accessor=" + throwingConsoleAllowed);
    } catch (Throwable err) {
      return new Check("srg-runtime-simulation", false, describe(err));
    }
  }

  /** Dummy command source whose player accessor exists and reports "no player" (console shape). */
  public static final class NullPlayerSource {
    public Object getPlayer() {
      return null;
    }
  }

  /** Dummy command source whose accessor throws like vanilla's accessor does for the console. */
  public static final class ThrowingPlayerSource {
    public Object getPlayerOrException() {
      throw new IllegalStateException("no player attached");
    }
  }

  /**
   * Bounded caches and fast paths added for high-player-count servers: the per-packet reflection
   * cache (hit + miss semantics) and the proxy session-cache pruning. Both must stay bounded.
   */
  private static Check checkBoundedCachesSimulation() {
    try {
      java.lang.reflect.Method length =
          in.potenfyr.authcore.util.ReflectCache.method(String.class, "length");
      boolean cacheHit =
          length == in.potenfyr.authcore.util.ReflectCache.method(String.class, "length");
      boolean invoked = Integer.valueOf(3).equals(length.invoke("abc"));
      boolean missThrows;
      try {
        in.potenfyr.authcore.util.ReflectCache.method(String.class, "authCoreNoSuchMethod");
        missThrows = false;
      } catch (NoSuchMethodException expected) {
        missThrows = true;
      }
      UUID id = UUID.randomUUID();
      SessionCache.update(id.toString(), true);
      boolean trusted = SessionCache.isAuthenticated(id.toString(), 60_000L);
      Thread.sleep(5L);
      SessionCache.prune(1L);
      boolean pruned = !SessionCache.isAuthenticated(id.toString(), 60_000L);
      boolean ok = cacheHit && invoked && missThrows && trusted && pruned;
      return new Check(
          "bounded-cache-simulation",
          ok,
          "reflect-hit=" + cacheHit + " invoke=" + invoked + " miss-throws=" + missThrows
              + " session-trusted=" + trusted + " session-pruned=" + pruned);
    } catch (Throwable err) {
      return new Check("bounded-cache-simulation", false, describe(err));
    }
  }
  /**
   * Simulates the map-image pipeline: renders a deterministic test image, creates the REAL
   * server-side map data, reads the colours back and verifies they match exactly. A map item
   * without saved map data renders the client's default terrain map instead of the image - this
   * is the exact failure mode the check guards against.
   */
  private static Check checkMapRenderSimulation(MinecraftServer server) {
    try {
      int[] image = MapImageRenderer.testPattern();
      byte[] colors = MapImageRenderer.render(image);
      boolean rendered =
          colors.length == MapImageRenderer.PIXELS && MapImageRenderer.paletteSize() > 200;
      // Accuracy on colours that exist in the vanilla palette: the red quadrant must render
      // reddish, white whitish and the black border dark - asserted per channel so the check is
      // independent of the exact palette entry chosen on each version.
      int red = MapImageRenderer.paletteRgb(colors[40 * MapImageRenderer.SIZE + 32]);
      int white = MapImageRenderer.paletteRgb(colors[88 * MapImageRenderer.SIZE + 96]);
      int black = MapImageRenderer.paletteRgb(colors[0]);
      boolean accuracy =
          ((red >> 16) & 0xFF) > 150
              && ((red >> 8) & 0xFF) < 90
              && (red & 0xFF) < 90
              && ((white >> 16) & 0xFF) > 200
              && ((white >> 8) & 0xFF) > 200
              && (white & 0xFF) > 200
              && ((black >> 16) & 0xFF) < 70
              && ((black >> 8) & 0xFF) < 70
              && (black & 0xFF) < 70;
      // Edge case: the service must no-op safely without a player (console/self-test callers).
      boolean edgeNoPlayer = !MapImageService.show(null, image) && !MapImageService.clear(null);
      if (!MapImageService.isSupported()) {
        return new Check(
            "map-render-simulation",
            rendered && accuracy && edgeNoPlayer,
            "renderer-ok (server map-data API unavailable on this version - feature disabled)");
      }
      ServerLevel level = server.overworld();
      ItemStack stack = MapImageMapFactory.createMap(level, colors);
      int mapId = stack == null ? -1 : MapImageMapFactory.mapId(stack);
      byte[] readBack = stack == null ? null : MapImageMapFactory.readBack(level, stack);
      // readBack only succeeds when the item's map id resolves to the saved data we wrote, so the
      // exact round-trip is the proof that the map renders our image instead of the default map.
      boolean roundTrip = readBack != null && java.util.Arrays.equals(colors, readBack);
      // Locked maps are never re-coloured from the world terrain: without it the vanilla updater
      // erases the image the moment a player carries the map.
      boolean locked = stack != null && MapImageMapFactory.isLocked(level, stack);
      boolean ok = rendered && accuracy && edgeNoPlayer && roundTrip && locked;
      return new Check(
          "map-render-simulation",
          ok,
          "palette=" + MapImageRenderer.paletteSize() + " rendered=" + rendered
              + " accuracy=" + accuracy + " map-data-roundtrip=" + roundTrip + " map-id=" + mapId
              + " locked=" + locked + " edge-no-player=" + edgeNoPlayer);
    } catch (Throwable err) {
      return new Check("map-render-simulation", false, describe(err));
    }
  }

  /**
   * Simulates the whole give/take lifecycle with a dummy inventory (no client needed): give,
   * replace, move to another slot, restore, death (map dropped -&gt; no duplicate), crash recovery
   * from the persisted ids, offhand removal, a completely full inventory, the per-player rate limit
   * and the config kill-switch. Everything runs against the REAL map factory and the REAL service.
   */
  private static Check checkMapLifecycleSimulation(MinecraftServer server) {
    if (!MapImageService.isSupported()) {
      return new Check("map-lifecycle-simulation", true, "unsupported-version");
    }
    if (!MapImageService.isEnabled()) {
      return new Check("map-lifecycle-simulation", true, "disabled-in-config");
    }
    int idsBefore = MapImageService.pendingIdCount();
    ServerLevel level = server.overworld();
    int[] image = MapImageRenderer.testPattern();
    Config.Lobby.MapImage config = AuthCoreServer.config.lobby.mapImage;
    boolean configuredEnabled = config.enabled;
    int configuredRate = config.maxPerMinute;
    try {
      config.enabled = true;
      config.maxPerMinute = Math.max(4, configuredRate);

      // --- give + restore: the map lands in the selected slot and the item comes back ----------
      ItemStack[] items = emptyInventory();
      items[0] = new ItemStack(net.minecraft.world.item.Items.DIRT, 5);
      UUID giveId = UUID.randomUUID();
      MapImageService.Slots giveSlots = dummySlots(items, 0);
      boolean given = MapImageService.showInto(giveId, giveSlots, level, image);
      ItemStack givenMap = items[0];
      int mapId = MapImageMapFactory.mapId(givenMap);
      byte[] expected = MapImageRenderer.render(image);
      byte[] readBack = given ? MapImageMapFactory.readBack(level, givenMap) : null;
      boolean renders =
          readBack != null
              && java.util.Arrays.equals(expected, readBack)
              && MapImageMapFactory.isLocked(level, givenMap);
      boolean cleared = MapImageService.clearInto(giveId, giveSlots);
      boolean restored =
          cleared
              && items[0].getItem() == net.minecraft.world.item.Items.DIRT
              && items[0].getCount() == 5
              && mapId >= 0
              && tempMapCount(items) == 0;

      // --- replace: a second show without an explicit clear still leaves exactly one map --------
      ItemStack[] twice = emptyInventory();
      twice[0] = new ItemStack(net.minecraft.world.item.Items.DIRT, 3);
      MapImageService.Slots twiceSlots = dummySlots(twice, 0);
      UUID replaceId = UUID.randomUUID();
      MapImageService.showInto(replaceId, twiceSlots, level, image);
      MapImageService.showInto(replaceId, twiceSlots, level, image);
      boolean replaced = tempMapCount(twice) == 1 && !twice[0].isEmpty();
      MapImageService.clearInto(replaceId, twiceSlots);

      // --- moved slot + full inventory: the saved item is restored into the slot the map left ---
      ItemStack[] full = emptyInventory();
      for (int i = 0; i < full.length - 1; i++) {
        full[i] = new ItemStack(net.minecraft.world.item.Items.STONE, 1);
      }
      full[0] = new ItemStack(net.minecraft.world.item.Items.DIRT, 7);
      MapImageService.Slots fullSlots = dummySlots(full, 0);
      UUID movedId = UUID.randomUUID();
      MapImageService.showInto(movedId, fullSlots, level, image);
      // simulate the player swapping the map with the stone in slot 20
      ItemStack movedMap = full[0];
      full[0] = full[20];
      full[20] = movedMap;
      boolean movedCleared = MapImageService.clearInto(movedId, fullSlots);
      boolean movedRestored =
          movedCleared
              && full[20].getItem() == net.minecraft.world.item.Items.DIRT
              && full[20].getCount() == 7
              && full[0].getItem() == net.minecraft.world.item.Items.STONE
              && tempMapCount(full) == 0;

      // --- death/drop: the map and the saved item are gone -> clear must not duplicate them -----
      ItemStack[] dead = emptyInventory();
      dead[0] = new ItemStack(net.minecraft.world.item.Items.DIRT, 9);
      MapImageService.Slots deadSlots = dummySlots(dead, 0);
      UUID deadId = UUID.randomUUID();
      MapImageService.showInto(deadId, deadSlots, level, image);
      java.util.Arrays.fill(dead, ItemStack.EMPTY); // death: vanilla dropped everything
      boolean deathCleared = MapImageService.clearInto(deadId, deadSlots);
      boolean deathSafe = deathCleared && tempMapCount(dead) == 0 && isEmptyStack(dead[0]);

      // --- offhand: a map moved to the offhand must be removed as well ---------------------------
      ItemStack[] offhandItems = emptyInventory();
      offhandItems[0] = new ItemStack(net.minecraft.world.item.Items.DIRT, 2);
      MapImageService.Slots offhandSlots = dummySlots(offhandItems, 0);
      UUID offhandId = UUID.randomUUID();
      MapImageService.showInto(offhandId, offhandSlots, level, image);
      offhandItems[OFFHAND_SLOT] = offhandItems[0]; // player moved the map to the offhand
      offhandItems[0] = ItemStack.EMPTY;
      boolean offhandCleared = MapImageService.clearInto(offhandId, offhandSlots);
      boolean offhandRemoved =
          offhandCleared
              && isEmptyStack(offhandItems[OFFHAND_SLOT])
              && offhandItems[0].getItem() == net.minecraft.world.item.Items.DIRT
              && tempMapCount(offhandItems) == 0;

      // --- crash recovery: a server restart keeps the persisted id, the next join cleans up -------
      ItemStack[] crashed = emptyInventory();
      crashed[0] = new ItemStack(net.minecraft.world.item.Items.DIRT, 4);
      MapImageService.Slots crashedSlots = dummySlots(crashed, 0);
      UUID crashedId = UUID.randomUUID();
      MapImageService.showInto(crashedId, crashedSlots, level, image);
      ItemStack carriedThroughCrash = crashed[0]; // survives in the player's saved data
      boolean idPersisted = MapImageService.pendingIdCount() > 0;
      MapImageService.resetState(); // restart: displays and caches gone, ids file kept
      crashed[0] = carriedThroughCrash;
      MapImageService.recoverInto(crashedId, crashedSlots);
      boolean recovered =
          idPersisted
              && tempMapCount(crashed) == 0
              && MapImageService.pendingIdCount() == 0
              && isEmptyStack(crashed[0]);

      // --- stale in-memory display (leave handler never ran) is taken back, item restored --------
      ItemStack[] stale = emptyInventory();
      stale[0] = new ItemStack(net.minecraft.world.item.Items.DIRT, 6);
      MapImageService.Slots staleSlots = dummySlots(stale, 0);
      UUID staleId = UUID.randomUUID();
      MapImageService.showInto(staleId, staleSlots, level, image);
      MapImageService.recoverInto(staleId, staleSlots);
      boolean staleRecovered =
          tempMapCount(stale) == 0
              && stale[0].getItem() == net.minecraft.world.item.Items.DIRT
              && stale[0].getCount() == 6;

      // --- rate limit: the per-player budget is enforced ----------------------------------------
      ItemStack[] limited = emptyInventory();
      MapImageService.Slots limitedSlots = dummySlots(limited, 0);
      UUID limitedId = UUID.randomUUID();
      int budget = Math.max(1, config.maxPerMinute);
      boolean allAllowed = true;
      for (int i = 0; i < budget; i++) {
        allAllowed &= MapImageService.showInto(limitedId, limitedSlots, level, image);
      }
      boolean limitedRejected = !MapImageService.showInto(limitedId, limitedSlots, level, image);
      MapImageService.clearInto(limitedId, limitedSlots);
      boolean rateLimited = allAllowed && limitedRejected;

      // --- kill-switch: with the config disabled nothing is given --------------------------------
      config.enabled = false;
      ItemStack[] disabled = emptyInventory();
      boolean disabledRejected =
          !MapImageService.showInto(
              UUID.randomUUID(), dummySlots(disabled, 0), level, image);
      config.enabled = true;
      boolean killSwitch = disabledRejected && tempMapCount(disabled) == 0;

      boolean ok =
          given
              && renders
              && restored
              && replaced
              && movedRestored
              && deathSafe
              && offhandRemoved
              && recovered
              && staleRecovered
              && rateLimited
              && killSwitch
              && MapImageService.pendingIdCount() == idsBefore;
      return new Check(
          "map-lifecycle-simulation",
          ok,
          "give=" + given + " render=" + renders + " restore=" + restored + " replace=" + replaced
              + " moved-slot=" + movedRestored + " death-no-dupe=" + deathSafe + " offhand="
              + offhandRemoved + " crash-recovery=" + recovered + " stale-display="
              + staleRecovered + " rate-limit=" + rateLimited + " kill-switch=" + killSwitch);
    } catch (Throwable err) {
      return new Check("map-lifecycle-simulation", false, describe(err));
    } finally {
      config.enabled = configuredEnabled;
      config.maxPerMinute = configuredRate;
    }
  }

  /** Virtual offhand slot of the simulated inventory (36 real slots + 1 offhand). */
  private static final int OFFHAND_SLOT = 36;

  /** A dummy inventory view: 36 plain slots plus a virtual offhand slot. */
  private static MapImageService.Slots dummySlots(ItemStack[] items, int selected) {
    return new MapImageService.Slots() {
      @Override
      public int size() {
        return items.length;
      }

      @Override
      public ItemStack get(int slot) {
        return slot >= 0 && slot < items.length ? items[slot] : ItemStack.EMPTY;
      }

      @Override
      public void set(int slot, ItemStack stack) {
        if (slot >= 0 && slot < items.length) items[slot] = stack;
      }

      @Override
      public int selectedSlot() {
        return selected;
      }
    };
  }

  private static ItemStack[] emptyInventory() {
    ItemStack[] items = new ItemStack[OFFHAND_SLOT + 1];
    java.util.Arrays.fill(items, ItemStack.EMPTY);
    return items;
  }

  /** Number of temporary map items still in the simulated inventory. */
  private static int tempMapCount(ItemStack[] items) {
    int count = 0;
    for (ItemStack stack : items) {
      if (stack != null && !stack.isEmpty() && MapImageMapFactory.mapId(stack) >= 0) count++;
    }
    return count;
  }

  private static boolean isEmptyStack(ItemStack stack) {
    return stack == null || stack.isEmpty();
  }

  /**
   * Simulates the map CONTENT pipeline: QR codes and captcha/label text are rendered to a
   * 128x128 image, quantised to the map palette exactly like a real display, and sampled back
   * module by module. Because the QR matrix is the same one the host suite verifies against an
   * independent decoder, an exact round-trip here proves the map still carries a scannable symbol.
   * Also exercises the captcha state machine (single use, attempts, expiry) and the input bounds of
   * everything that takes untrusted-length input.
   */
  private static Check checkMapContentSimulation() {
    int checks = 0;
    int failed = 0;
    StringBuilder detail = new StringBuilder();
    try {
      // --- 1. QR -> map pixels -> modules round-trip -------------------------------------------
      String[] payloads = {
        "https://discord.gg/authcore",
        "otpauth://totp/AuthCore:Steve?secret=JBSWY3DPEHPK3PXP&issuer=AuthCore",
        "x".repeat(90),
      };
      int qrModules = 0;
      int qrMismatch = 0;
      for (String payload : payloads) {
        boolean[][] modules = QrCode.encode(payload, QrCode.ECC_MEDIUM);
        int[] image = MapImageContent.qr(payload, QrCode.ECC_MEDIUM);
        byte[] colours = MapImageRenderer.render(image);
        int scale = MapImageRenderer.SIZE / (modules.length + MapImageContent.QR_QUIET_ZONE * 2);
        int offset = (MapImageRenderer.SIZE - (modules.length + MapImageContent.QR_QUIET_ZONE * 2)
                * scale) / 2 + MapImageContent.QR_QUIET_ZONE * scale;
        if (scale < MapImageContent.MIN_QR_MODULE_PIXELS) {
          failed++;
          detail.append("qr-scale-too-small;");
        }
        for (int y = 0; y < modules.length; y++) {
          for (int x = 0; x < modules.length; x++) {
            int rgb = MapImageRenderer.paletteRgb(
                colours[(offset + y * scale + scale / 2) * MapImageRenderer.SIZE
                    + offset + x * scale + scale / 2]);
            boolean dark = luminance(rgb) < 110;
            qrModules++;
            if (dark != modules[y][x]) qrMismatch++;
          }
        }
      }
      // Quiet zone must be light on every pixel outside the symbol area.
      boolean quietZone = true;
      String quietPayload = "https://example.com";
      boolean[][] quietModules = QrCode.encode(quietPayload, QrCode.ECC_MEDIUM);
      byte[] quietColours = MapImageRenderer.render(
          MapImageContent.qr(quietPayload, QrCode.ECC_MEDIUM));
      int qSize = quietModules.length;
      int qScale = MapImageRenderer.SIZE
          / (qSize + MapImageContent.QR_QUIET_ZONE * 2);
      int qOffset = (MapImageRenderer.SIZE
          - (qSize + MapImageContent.QR_QUIET_ZONE * 2) * qScale) / 2;
      int symbolStart = qOffset + MapImageContent.QR_QUIET_ZONE * qScale;
      int symbolEnd = symbolStart + qSize * qScale;
      for (int y = 0; y < MapImageRenderer.SIZE; y++) {
        for (int x = 0; x < MapImageRenderer.SIZE; x++) {
          boolean insideSymbol = x >= symbolStart && x < symbolEnd && y >= symbolStart
              && y < symbolEnd;
          if (!insideSymbol) {
            quietZone &= isLight(quietColours[y * MapImageRenderer.SIZE + x]);
          }
        }
      }
      checks++;
      if (qrMismatch != 0 || !quietZone) failed++;
      detail.append("qr-modules=").append(qrModules).append(" qr-mismatch=").append(qrMismatch)
          .append(" quiet-zone=").append(quietZone).append(' ');

      // --- 2. captcha/label text -> map pixels -> glyph cells -----------------------------------
      String[] labels = {"ABC234", MapImageCaptcha.newCode(), "K9XZ7Q"};
      int textCells = 0;
      int textMismatch = 0;
      for (String label : labels) {
        int[] image = MapImageContent.text(label);
        byte[] colours = MapImageRenderer.render(image);
        String upper = label.trim().toUpperCase(java.util.Locale.ROOT);
        int[] layout = MapImageContent.layout(upper);
        int scale = layout[0];
        int startX = layout[1];
        int startY = layout[2];
        for (int g = 0; g < upper.length(); g++) {
          long glyph = MapImageContent.glyphBits(upper.charAt(g));
          for (int row = 0; row < 7; row++) {
            int bits = (int) ((glyph >>> ((7 - 1 - row) * 5)) & 0x1F);
            for (int col = 0; col < 5; col++) {
              boolean ink = ((bits >>> (4 - col)) & 1) != 0;
              int px = startX + g * 6 * scale + col * scale + scale / 2;
              int py = startY + row * scale + scale / 2;
              int rgb = MapImageRenderer.paletteRgb(
                  colours[py * MapImageRenderer.SIZE + px]);
              textCells++;
              if ((luminance(rgb) >= 110) != ink) textMismatch++;
            }
          }
        }
      }
      checks++;
      if (textMismatch != 0) failed++;
      detail.append("text-cells=").append(textCells).append(" text-mismatch=").append(textMismatch)
          .append(' ');

      // --- 3. captcha state machine (single use, attempts, expiry, normalisation) ---------------
      UUID captchaId = UUID.randomUUID();
      String code = MapImageCaptcha.newCode();
      MapImageCaptcha.trackForTest(captchaId, code, 1_000L);
      boolean wrong = !MapImageCaptcha.verify(captchaId, "AAAAAA", 1_100L);
      boolean wrongAgain = !MapImageCaptcha.verify(captchaId, "BBBBBB", 1_200L);
      boolean correct = MapImageCaptcha.verify(captchaId, " " + code.toLowerCase() + " ", 1_300L);
      boolean singleUse = !MapImageCaptcha.verify(captchaId, code, 1_400L);
      UUID attemptsId = UUID.randomUUID();
      MapImageCaptcha.trackForTest(attemptsId, code, 1_000L);
      MapImageCaptcha.verify(attemptsId, "AAAAAA", 1_100L);
      MapImageCaptcha.verify(attemptsId, "BBBBBB", 1_200L);
      boolean burned = !MapImageCaptcha.verify(attemptsId, "CCCCCC", 1_300L)
          && !MapImageCaptcha.verify(attemptsId, code, 1_400L);
      UUID expiredId = UUID.randomUUID();
      MapImageCaptcha.trackForTest(expiredId, code, 1_000L);
      boolean expired = !MapImageCaptcha.verify(
          expiredId, code, 1_000L + MapImageCaptcha.TTL_MS + 1);
      boolean noChallenge = !MapImageCaptcha.verify(UUID.randomUUID(), code, 1_500L);
      boolean rejectedInput = !MapImageCaptcha.verify(attemptsId, null, 1_000L)
          && !MapImageCaptcha.verify(attemptsId, "TOO-LONG-CODE-123", 1_000L);
      MapImageCaptcha.discard(captchaId);
      boolean discarded = !MapImageCaptcha.verify(captchaId, code, 2_000L);
      boolean stateClean = MapImageCaptcha.pendingCount() == 0;
      checks++;
      if (!(wrong && wrongAgain && correct && singleUse && burned && expired && noChallenge
          && rejectedInput && discarded && stateClean)) {
        failed++;
      }
      detail.append("captcha=").append(wrong && correct && singleUse && burned && expired && stateClean)
          .append(' ');

      // --- 4. input bounds / hostile input (no crash, no unbounded allocation) ------------------
      boolean rejects = false;
      try {
        QrCode.encode("", QrCode.ECC_MEDIUM);
      } catch (IllegalArgumentException expected) {
        rejects = true;
      }
      rejects &= throwsIae(() -> QrCode.encode(null, QrCode.ECC_MEDIUM));
      rejects &= throwsIae(() -> QrCode.encode("x".repeat(QrCode.MAX_BYTES + 1), QrCode.ECC_LOW));
      rejects &= throwsIae(() -> QrCode.encode("hello", 4));
      rejects &= throwsIae(() -> QrCode.encode("hello", QrCode.ECC_LOW, 11, 0));
      rejects &= throwsIae(() -> QrCode.encode("hello", QrCode.ECC_LOW, 1, 8));
      rejects &= throwsIae(() -> MapImageContent.text(null));
      rejects &= throwsIae(() -> MapImageContent.text(""));
      rejects &= throwsIae(() -> MapImageContent.text("A".repeat(13)));
      rejects &= throwsIae(() -> MapImageContent.text("A$B"));
      rejects &= throwsIae(() -> MapImageContent.qr("x".repeat(200), QrCode.ECC_MEDIUM));
      rejects &= throwsIae(() -> MapImageRenderer.render(null));
      rejects &= throwsIae(() -> MapImageRenderer.render(new int[5]));
      // A code never contains the ambiguous glyphs and is always in the documented alphabet.
      String alphabet = MapImageCaptcha.alphabet();
      String sample = MapImageCaptcha.newCode();
      boolean codeShape = sample.length() == MapImageCaptcha.CODE_LENGTH;
      for (int i = 0; i < sample.length(); i++) {
        codeShape &= alphabet.indexOf(sample.charAt(i)) >= 0;
      }
      codeShape &= "IO01".chars().noneMatch(c -> alphabet.indexOf(c) >= 0);
      checks++;
      if (!rejects || !codeShape) failed++;
      detail.append("bounds=").append(rejects).append(" code-shape=").append(codeShape).append(' ');

      // --- 5. QR goldens: exact matrices validated by two independent decoders -------------------
      // (zxing-cpp decoded all four payloads correctly, including the ECI/UTF-8 and the pinned
      // mask case - see test/README.md). A mismatch here means the encoder changed and must be
      // re-validated externally, not just accepted.
      String[][] goldens = {
        {"https://discord.gg/authcore", "1", "0", "-1",
            "68c7151aeb3904d042c06d27c00d829b87021a2f7b6fb3ca1e3c8ca85aca7541"},
        {"otpauth://totp/AuthCore:Steve?secret=JBSWY3DPEHPK3PXP&issuer=AuthCore", "1", "0", "-1",
            "39b823739b2281ee02ff7e37038799f1297d22e05dfb6edf7d85fde83589b928"},
        {"HELLO", "3", "2", "5",
            "55cb7ff538d3394fb0ed5b4e4e23bddd1fde21fb0512a58a52f02474056b8a31"},
        {"\u00dcn\u00efcode-\u65e5\u672c\u8a9e", "0", "0", "-1",
            "ccab2015928a19fcdcfa462bf46f324c564334284e002f970be65b34e4059e84"},
      };
      int goldenOk = 0;
      for (String[] golden : goldens) {
        boolean[][] modules = QrCode.encode(
            golden[0], Integer.parseInt(golden[1]), Integer.parseInt(golden[2]),
            Integer.parseInt(golden[3]));
        StringBuilder joined = new StringBuilder();
        for (boolean[] row : modules) {
          for (boolean value : row) joined.append(value ? '1' : '0');
          joined.append('/');
        }
        joined.setLength(joined.length() - 1);
        if (sha256Hex(joined.toString()).equals(golden[4])) goldenOk++;
      }
      checks++;
      if (goldenOk != goldens.length) failed++;
      detail.append("qr-goldens=").append(goldenOk).append('/').append(goldens.length).append(' ');

      return new Check(
          "map-content-simulation", failed == 0, detail.toString().trim());
    } catch (Throwable err) {
      return new Check("map-content-simulation", false, describe(err));
    }
  }

  /** Lower-case SHA-256 of a string (QR regression goldens). */
  private static String sha256Hex(String value) {
    try {
      byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.US_ASCII));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte b : digest) hex.append(String.format("%02x", b));
      return hex.toString();
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  /** Runs a snippet that must throw IllegalArgumentException. */
  private static boolean throwsIae(Runnable snippet) {
    try {
      snippet.run();
      return false;
    } catch (IllegalArgumentException expected) {
      return true;
    }
  }

  /** Perceptual luminance of a packed RGB value (0-255). */
  private static int luminance(int rgb) {
    int r = (rgb >> 16) & 0xFF;
    int g = (rgb >> 8) & 0xFF;
    int b = rgb & 0xFF;
    return (r * 3 + g * 6 + b) / 10;
  }

  private static boolean isLight(byte colour) {
    return luminance(MapImageRenderer.paletteRgb(colour)) > 150;
  }

  /**
   * OWASP-style input hardening: log injection (CR/LF/ANSI/Unicode separators), username validation,
   * Discord snowflake validation, path traversal and file-name sanitisation. Every case is a hostile
   * input a hacked client, a config file or a DB row could produce.
   */
  private static Check checkSanitizerSimulation() {
    try {
      // --- log injection ----------------------------------------------------------------
      String hostile =
          "Steve\n2026-01-01 | FAKE_EVENT | <- forged line\r\n\u001b[31mred\u001b[0m\u2028end";
      String sanitized = Sanitizer.logDetail(hostile);
      boolean noControl = true;
      for (int i = 0; i < sanitized.length(); i++) noControl &= !Character.isISOControl(sanitized.charAt(i));
      boolean logInjection =
          noControl
              && !sanitized.contains("\n")
              && !sanitized.contains("\r")
              && !sanitized.contains("\u2028")
              && !sanitized.contains("\u001b")
              && sanitized.contains("Steve")
              && sanitized.contains("forged line");
      boolean bounded = Sanitizer.logDetail("x".repeat(100_000)).length() == Sanitizer.MAX_LOG_DETAIL;
      boolean eventSafe = Sanitizer.logEvent("premium verified!").equals("PREMIUM_VERIFIED_");
      boolean nullSafe = Sanitizer.logDetail(null).isEmpty() && Sanitizer.logToken(null).isEmpty();

      // --- username validation -----------------------------------------------------------
      boolean usernames =
          Sanitizer.isSafeUsername("Steve")
              && Sanitizer.isSafeUsername("a_b.c-d")
              && Sanitizer.isSafeUsername("Player123")
              && !Sanitizer.isSafeUsername(null)
              && !Sanitizer.isSafeUsername("")
              && !Sanitizer.isSafeUsername("Steve\nAdmin")
              && !Sanitizer.isSafeUsername("Steve\u0000")
              && !Sanitizer.isSafeUsername("Ѕteve") // Cyrillic S lookalike
              && !Sanitizer.isSafeUsername("a".repeat(17))
              && !Sanitizer.isSafeUsername("two words");

      // --- Discord id --------------------------------------------------------------------
      boolean discord =
          Sanitizer.isSafeDiscordId("123456789012345678")
              && !Sanitizer.isSafeDiscordId("12345")
              && !Sanitizer.isSafeDiscordId("12345678901234567a")
              && !Sanitizer.isSafeDiscordId("../../etc/passwd");

      // --- path traversal / file names ---------------------------------------------------
      Path base = Path.of(System.getProperty("java.io.tmpdir", ".")).toAbsolutePath().normalize();
      boolean paths =
          Sanitizer.resolveUnder(base, "security.log") != null
              && Sanitizer.resolveUnder(base, "../escape.log") == null
              && Sanitizer.resolveUnder(base, "a/../../escape.log") == null
              && Sanitizer.resolveUnder(base, "/etc/passwd") == null
              && Sanitizer.resolveUnder(base, "") == null
              && Sanitizer.safeFileName("../../evil.log").indexOf('/') < 0
              && Sanitizer.safeFileName("../../evil.log").indexOf('\\') < 0
              && !Sanitizer.safeFileName("../../evil.log").contains("..");

      boolean ok = logInjection && bounded && eventSafe && nullSafe && usernames && discord && paths;
      return new Check(
          "sanitizer-simulation",
          ok,
          "log-injection=" + logInjection + " bounded=" + bounded + " usernames=" + usernames
              + " discord=" + discord + " paths=" + paths);
    } catch (Throwable err) {
      return new Check("sanitizer-simulation", false, describe(err));
    }
  }

  /**
   * The lobby packet gate decision matrix: for every guarded packet action and every relevant
   * configuration flag the decision must match the documented behaviour (fail closed by default,
   * exactly the allowed actions when an operator enables them). Also covers malformed/unknown packet
   * actions (must be classified safely) - the mixin itself is a thin shell around this logic.
   */
  private static Check checkLobbyPacketPolicySimulation() {
    Config.Lobby lobby = new Config.Lobby();
    try {
      // Default configuration: every guarded action is blocked.
      boolean defaultsBlocked =
          LobbyPacketPolicy.blocked(LobbyPacketPolicy.Action.ITEM_USE, lobby)
              && LobbyPacketPolicy.blocked(LobbyPacketPolicy.Action.ITEM_MUTATION, lobby)
              && LobbyPacketPolicy.blocked(LobbyPacketPolicy.Action.PICK_ITEM, lobby)
              && LobbyPacketPolicy.blocked(LobbyPacketPolicy.Action.SIGN_EDIT, lobby)
              && LobbyPacketPolicy.blocked(LobbyPacketPolicy.Action.RESPAWN, lobby)
              && LobbyPacketPolicy.blocked(LobbyPacketPolicy.Action.MOVEMENT, lobby)
              && LobbyPacketPolicy.blocked(LobbyPacketPolicy.Action.CONTAINER_ACTION, lobby)
              && LobbyPacketPolicy.blocked(LobbyPacketPolicy.Action.UNKNOWN, lobby);

      // Respawn/unknown stay blocked even with everything allowed (no legitimate lobby need).
      lobby.allowItemUse = true;
      lobby.allowItemMoving = true;
      lobby.allowBlockInteraction = true;
      lobby.allowMovement = true;
      lobby.allowCrafting = true;
      lobby.allowEntityInteractWith = true;
      boolean allowAll =
          !LobbyPacketPolicy.blocked(LobbyPacketPolicy.Action.ITEM_USE, lobby)
              && !LobbyPacketPolicy.blocked(LobbyPacketPolicy.Action.ITEM_MUTATION, lobby)
              && !LobbyPacketPolicy.blocked(LobbyPacketPolicy.Action.PICK_ITEM, lobby)
              && !LobbyPacketPolicy.blocked(LobbyPacketPolicy.Action.SIGN_EDIT, lobby)
              && !LobbyPacketPolicy.blocked(LobbyPacketPolicy.Action.MOVEMENT, lobby)
              && !LobbyPacketPolicy.blocked(LobbyPacketPolicy.Action.CONTAINER_ACTION, lobby)
              && LobbyPacketPolicy.blocked(LobbyPacketPolicy.Action.RESPAWN, lobby)
              && LobbyPacketPolicy.blocked(LobbyPacketPolicy.Action.UNKNOWN, lobby);

      // Player-command classification: only gliding is movement, sneak/sprint must pass.
      boolean classification =
          LobbyPacketPolicy.classifyPlayerCommand("START_FALL_FLYING")
                  == LobbyPacketPolicy.Action.MOVEMENT
              && LobbyPacketPolicy.classifyPlayerCommand("STOP_FALL_FLYING")
                  == LobbyPacketPolicy.Action.MOVEMENT
              && LobbyPacketPolicy.classifyPlayerCommand("START_SNEAKING") == null
              && LobbyPacketPolicy.classifyPlayerCommand("STOP_SNEAKING") == null
              && LobbyPacketPolicy.classifyPlayerCommand("START_SPRINTING") == null
              && LobbyPacketPolicy.classifyPlayerCommand(null)
                  == LobbyPacketPolicy.Action.UNKNOWN
              && LobbyPacketPolicy.classifyPlayerCommand("") == LobbyPacketPolicy.Action.UNKNOWN;

      boolean ok = defaultsBlocked && allowAll && classification;
      return new Check(
          "lobby-packet-policy-simulation",
          ok,
          "defaults=" + defaultsBlocked + " allow-all=" + allowAll + " classify=" + classification);
    } catch (Throwable err) {
      return new Check("lobby-packet-policy-simulation", false, describe(err));
    }
  }

  /**
   * Concurrency smoke test for the shared caches/limiters that are touched from the server thread,
   * the IO pool and the web panel: parallel updates must neither throw nor lose/corrupt state, the
   * one-time TOTP replay guard must accept a code exactly once under contention, and every cache must
   * stay bounded.
   */
  private static Check checkConcurrencySimulation() {
    try {
      final int threads = 8;
      final int iterations = 250;
      java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger();
      java.util.concurrent.atomic.AtomicInteger successes = new java.util.concurrent.atomic.AtomicInteger();
      java.util.concurrent.ConcurrentLinkedQueue<UUID> captchaIds = new java.util.concurrent.ConcurrentLinkedQueue<>();
      java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
      java.util.List<Thread> workers = new java.util.ArrayList<>();
      for (int t = 0; t < threads; t++) {
        final int worker = t;
        Thread thread =
            new Thread(
                () -> {
                  try {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                      String key = "concurrency:" + (i % 64);
                      in.potenfyr.authcore.security.RateLimiter.tryAcquire(key, 1_000, 60_000L);
                      SessionCache.update("uuid-" + worker + "-" + i, true);
                      SessionCache.isAuthenticated("uuid-" + worker + "-" + i, 60_000L);
                      String code = MapImageCaptcha.newCode();
                      UUID id = UUID.randomUUID();
                      captchaIds.add(id);
                      MapImageCaptcha.trackForTest(id, code, 1_000L);
                      if (MapImageCaptcha.verify(id, code, 1_100L)) successes.incrementAndGet();
                      Sanitizer.logDetail("user-" + worker + "\n" + i);
                      if (in.potenfyr.authcore.security.RateLimiter.trackedKeys() <= 0) {
                        failures.incrementAndGet();
                      }
                    }
                  } catch (Throwable err) {
                    failures.incrementAndGet();
                  }
                },
                "AuthCore-SelfTest-Concurrency-" + t);
        thread.setDaemon(true);
        workers.add(thread);
        thread.start();
      }
      start.countDown();
      for (Thread thread : workers) thread.join(15_000L);
      boolean allJoined = workers.stream().noneMatch(Thread::isAlive);
      boolean noFailures = failures.get() == 0;
      boolean allVerified = successes.get() == threads * iterations;
      boolean captchaBounded = MapImageCaptcha.pendingCount() <= MapImageCaptcha.MAX_CHALLENGES;
      boolean rateLimiterBounded =
          in.potenfyr.authcore.security.RateLimiter.trackedKeys() <= 10_000;
      boolean sessionsBounded = SessionCache.size() < 100_000;
      MapImageCaptcha.resetForTest();
      boolean ok =
          allJoined && noFailures && allVerified && captchaBounded && rateLimiterBounded
              && sessionsBounded;
      return new Check(
          "concurrency-simulation",
          ok,
          "threads=" + threads + " verified=" + successes.get() + " failures=" + failures.get()
              + " captcha-bounded=" + captchaBounded + " rate-bounded=" + rateLimiterBounded
              + " sessions-bounded=" + sessionsBounded);
    } catch (Throwable err) {
      return new Check("concurrency-simulation", false, describe(err));
    }
  }

  /** Squared RGB distance helper for the palette-accuracy assertion. */
  private static long distance(int a, int b) {
    int dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
    int dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
    int db = (a & 0xFF) - (b & 0xFF);
    return (long) dr * dr + (long) dg * dg + (long) db * db;
  }
  /** Compact failure detail: exception type + message (e.g. the missing class of a NCDFE). */
  private static String describe(Throwable err) {
    String message = err.getMessage();
    return err.getClass().getSimpleName()
        + (message == null || message.isEmpty() ? "" : ": " + message);
  }

  private SelfTest() {}
}
