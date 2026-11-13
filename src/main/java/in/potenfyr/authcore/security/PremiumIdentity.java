package in.potenfyr.authcore.security;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.mojang.authlib.GameProfile;
import in.potenfyr.authcore.AuthCoreServer;
import in.potenfyr.authcore.compat.Compat;
import in.potenfyr.authcore.models.User;
import net.minecraft.server.MinecraftServer;

/**
 * The identity a Mojang-verified premium player joins an OFFLINE-mode server with.
 *
 * <p>Vanilla gives every player of an offline-mode server the offline uuid and a profile without
 * skin data. After AuthCore verified the session itself (see {@code ServerPremiumVerificationMixin})
 * the join can use a better profile:
 *
 * <ul>
 *   <li>{@code premium-skins} (default on): the offline uuid is kept and the verified profile's
 *       signed skin is attached - nothing stored under the uuid changes.
 *   <li>{@code premium-real-uuid} (default off): the verified Mojang profile itself is used (real
 *       uuid, like an online-mode server). The player's vanilla data is moved from the offline
 *       uuid to the real uuid once, with a {@code .authcore-bak} copy of every moved file.
 * </ul>
 *
 * <p>The login is resumed on the SERVER thread, from the login listener's tick: vanilla handles
 * the hello again (creating its offline profile) and the profile is replaced before vanilla reads
 * it later in the same tick - no window in which the join could proceed with the wrong identity.
 */
public final class PremiumIdentity {

  private PremiumIdentity() {}

  /** What the login tick has to do for one verified login. */
  public static final class Plan {
    public final Object hello;
    public final String username;
    public final UUID offlineUuid;
    public final GameProfile verified;
    public final boolean wantRealUuid;
    public final boolean wantSkin;

    Plan(
        Object hello,
        String username,
        UUID offlineUuid,
        GameProfile verified,
        boolean wantRealUuid,
        boolean wantSkin) {
      this.hello = hello;
      this.username = username;
      this.offlineUuid = offlineUuid;
      this.verified = verified;
      this.wantRealUuid = wantRealUuid;
      this.wantSkin = wantSkin;
    }
  }

  /** Verified logins waiting for the next login tick: login listener -> plan. */
  private static final Map<Object, Plan> RESUME = new ConcurrentHashMap<>();

  private static final int MAX_TRACKED = 4096;

  // ------------------------------------------------------------------ decision

  /**
   * Whether a verified session joins with the real uuid.
   *
   * <p>Only when the AuthCore account of that name cannot belong to somebody else: there is no
   * account yet, it is an online-mode (premium) account, it has no password (nobody ever proved
   * ownership with one - the verified owner of the name claims it), or it already lives under this
   * real uuid. An existing PASSWORD account keeps the offline uuid: it may be a different person
   * who registered the name first, and handing its data to the premium owner of the name would let
   * any premium account take a same-named offline player's inventory just by connecting. Its
   * owner opts in with {@code /account set-mode online} (ownership proven by the password).
   */
  public static boolean useRealUuid(
      boolean enabled,
      boolean accountExists,
      boolean accountPremium,
      boolean accountHasPassword,
      boolean accountUuidIsVerifiedUuid) {
    if (!enabled) return false;
    if (!accountExists) return true;
    return accountPremium || !accountHasPassword || accountUuidIsVerifiedUuid;
  }

  /**
   * Whether an account is stored under a real (Mojang) uuid. Mojang uuids are version 4, the
   * offline uuids vanilla derives from a name are version 3 - independent of the name's case.
   */
  public static boolean isRealUuid(UUID uuid) {
    return uuid != null && uuid.version() == 4;
  }

  /**
   * Builds the plan for a verified login (called off the server thread - the account lookup may
   * load from the database).
   *
   * @return the plan, or {@code null} when neither option applies (the caller resumes the plain
   *     offline flow)
   */
  public static Plan plan(Object hello, String username, UUID offlineUuid, GameProfile verified) {
    if (hello == null || username == null || offlineUuid == null || verified == null) return null;
    boolean skins = AuthCoreServer.isPremiumSkinsEnabled() && Compat.profileHasTextures(verified);
    boolean realEnabled = AuthCoreServer.isPremiumRealUuidEnabled();
    if (!skins && !realEnabled) return null;

    boolean real = false;
    if (realEnabled) {
      UUID realUuid = Compat.profileId(verified);
      User account = null;
      try {
        account = User.getUserByUsername(username);
      } catch (RuntimeException err) {
        // unknown account state: treated like an existing password account (offline uuid)
        realUuid = null;
      }
      real =
          realUuid != null
              && useRealUuid(
                  true,
                  account != null,
                  account != null && account.isPremium,
                  account != null && account.isRegistered.get(),
                  account != null && realUuid.equals(account.uuid));
    }
    if (!skins && !real) return null;
    return new Plan(hello, username, offlineUuid, verified, real, skins);
  }

  // ------------------------------------------------------------------ resume (login tick)

  /** Queues a verified login for the next tick of its login listener. */
  public static void scheduleResume(Object handler, Plan plan) {
    if (handler == null || plan == null) return;
    if (RESUME.size() > MAX_TRACKED) RESUME.clear();
    RESUME.put(handler, plan);
  }

  /** Takes the queued plan of a login listener (called from its tick), or {@code null}. */
  public static Plan takeResume(Object handler) {
    if (RESUME.isEmpty() || handler == null) return null;
    return RESUME.remove(handler);
  }

  /** Withdraws a queued plan that no tick picked up; {@code true} when it was still queued. */
  public static boolean cancelResume(Object handler, Plan plan) {
    return handler != null && plan != null && RESUME.remove(handler, plan);
  }

  /** Drops any queued plan of a disconnected login listener. */
  public static void forget(Object handler) {
    if (handler != null) RESUME.remove(handler);
  }

  /**
   * Resumes a verified login on the server thread: vanilla handles the hello again, then the
   * profile it created is replaced according to the plan. Any failure leaves the plain offline
   * profile in place - the verification marker of the offline uuid stays valid for it.
   */
  public static void resume(Object handler, Plan plan) {
    if (!Compat.loginReplayHelloNow(handler, plan.hello)) return;

    GameProfile join = null;
    try {
      MinecraftServer server = Compat.loginServer(handler);
      if (plan.wantRealUuid && server != null) join = realProfile(server, plan);

      if (join == null && plan.wantSkin && !Compat.loginValidatesProfileKey())
        join = Compat.profileWithProperties(plan.offlineUuid, plan.username, plan.verified);
    } catch (RuntimeException | LinkageError err) {
      AuthCoreServer.LOGGER.debug(
          false, "Premium identity could not be prepared - joining with the offline profile:", err);
      join = null;
    }

    if (join != null && !Compat.loginSetProfile(handler, join))
      AuthCoreServer.LOGGER.debug(
          false,
          "{} | the verified profile could not be applied on this runtime - joining with the "
              + "offline profile.",
          plan.username);
  }

  /** The verified profile when the real uuid can be used for this join, else {@code null}. */
  private static GameProfile realProfile(MinecraftServer server, Plan plan) {
    UUID realUuid = Compat.profileId(plan.verified);
    if (realUuid == null || realUuid.equals(plan.offlineUuid)) return null;
    GameProfile offline = new GameProfile(plan.offlineUuid, plan.username);

    // A ban of the offline profile must not be escaped by switching to the real uuid: stay on the
    // offline profile, where vanilla rejects the join.
    if (Compat.isProfileBanned(server, offline)) return null;

    // The offline identity is in the world right now: its data would be saved again on logout,
    // after the move. Join as offline (vanilla replaces the duplicate, as always).
    if (Compat.isPlayerOnline(server, plan.offlineUuid)) return null;

    Migration migration = migrateFiles(Compat.playerStorageDirs(server), plan.offlineUuid, realUuid);
    if (migration == Migration.FAILED) {
      AuthCoreServer.LOGGER.warn(
          false,
          "{} | premium-real-uuid: the player data could not be moved to the real uuid {} - "
              + "joining with the offline uuid so nothing is lost.",
          plan.username,
          realUuid);
      return null;
    }
    if (migration == Migration.MIGRATED) {
      AuthCoreServer.LOGGER.info(
          false,
          "{} | premium-real-uuid: player data moved from the offline uuid {} to the real uuid {} "
              + "(backups: *.authcore-bak).",
          plan.username,
          plan.offlineUuid,
          realUuid);
      SecurityLog.log(
          "PREMIUM_UUID_MIGRATED",
          plan.username + " | " + plan.offlineUuid + " -> " + realUuid);
    }
    Compat.carryProfileAccess(server, offline, plan.verified);
    return plan.verified;
  }

  // ------------------------------------------------------------------ player data move

  public enum Migration {
    /** Nothing to move (new player, or the real uuid already has data). */
    NOT_NEEDED,
    MIGRATED,
    /** The move could not be completed - nothing was changed, the real uuid must not be used. */
    FAILED
  }

  /**
   * Moves a player's vanilla files from one uuid to another: {@code playerdata/<uuid>.dat},
   * {@code advancements/<uuid>.json} and {@code stats/<uuid>.json}. A file is only moved when the
   * target does not exist, and the source is renamed to {@code <name>.authcore-bak} - it is never
   * left in place, so the old identity cannot be used to duplicate the inventory.
   *
   * @param dirs {@code [playerdata, advancements, stats]}
   */
  public static Migration migrateFiles(Path[] dirs, UUID from, UUID to) {
    if (dirs == null || dirs.length < 3 || dirs[0] == null || from == null || to == null)
      return Migration.FAILED;
    if (from.equals(to)) return Migration.NOT_NEEDED;

    Path source = dirs[0].resolve(from + ".dat");
    Path target = dirs[0].resolve(to + ".dat");
    if (Files.exists(target)) return Migration.NOT_NEEDED;

    boolean moved = false;
    if (Files.isRegularFile(source)) {
      Path temp = dirs[0].resolve(to + ".dat.authcore-tmp");
      try {
        byte[] nbt = gunzip(Files.readAllBytes(source));
        // The entity uuid is stored INSIDE the file and vanilla applies it when the data is
        // loaded - a plain rename would give the player the offline uuid back.
        patchUuid(nbt, from, to);
        Files.write(temp, gzip(nbt));
        Files.move(temp, target);
      } catch (IOException | RuntimeException err) {
        deleteQuietly(temp);
        return Migration.FAILED;
      }
      try {
        Files.move(
            source, dirs[0].resolve(from + ".dat.authcore-bak"), StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException | RuntimeException err) {
        // Both identities would own the same inventory - undo.
        deleteQuietly(target);
        return Migration.FAILED;
      }
      moved = true;
    }

    for (int i = 1; i <= 2; i++) {
      if (dirs[i] == null) continue;
      Path json = dirs[i].resolve(from + ".json");
      Path jsonTarget = dirs[i].resolve(to + ".json");
      try {
        if (!Files.isRegularFile(json) || Files.exists(jsonTarget)) continue;
        Files.copy(json, jsonTarget);
        Files.move(
            json, dirs[i].resolve(from + ".json.authcore-bak"), StandardCopyOption.REPLACE_EXISTING);
        moved = true;
      } catch (IOException | RuntimeException err) {
        // advancements / statistics are best-effort - the inventory is what must not be lost
      }
    }
    return moved ? Migration.MIGRATED : Migration.NOT_NEEDED;
  }

  /**
   * Rewrites every {@code UUID} int-array tag that holds {@code from} to {@code to}, in place (the
   * tag keeps its size, so the NBT structure is untouched). Player data stores the uuid in this
   * form since 1.16.
   *
   * @return the number of tags rewritten
   */
  public static int patchUuid(byte[] nbt, UUID from, UUID to) {
    if (nbt == null || from == null || to == null) return 0;
    // TAG_Int_Array (11), name length 4, "UUID", array length 4
    byte[] head = {0x0B, 0x00, 0x04, 'U', 'U', 'I', 'D', 0x00, 0x00, 0x00, 0x04};
    byte[] oldBytes = uuidBytes(from);
    byte[] newBytes = uuidBytes(to);
    int count = 0;
    outer:
    for (int i = 0; i + head.length + 16 <= nbt.length; i++) {
      for (int j = 0; j < head.length; j++) if (nbt[i + j] != head[j]) continue outer;
      for (int j = 0; j < 16; j++) if (nbt[i + head.length + j] != oldBytes[j]) continue outer;
      System.arraycopy(newBytes, 0, nbt, i + head.length, 16);
      count++;
      i += head.length + 15;
    }
    return count;
  }

  private static byte[] uuidBytes(UUID uuid) {
    return ByteBuffer.allocate(16)
        .putLong(uuid.getMostSignificantBits())
        .putLong(uuid.getLeastSignificantBits())
        .array();
  }

  private static byte[] gunzip(byte[] data) throws IOException {
    try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
      return in.readAllBytes();
    }
  }

  private static byte[] gzip(byte[] data) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 2));
    try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
      gz.write(data);
    }
    return out.toByteArray();
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException | RuntimeException ignored) {
      // nothing else to do
    }
  }

  // ------------------------------------------------------------------ 1.16 - 1.19.0 (no hello uuid)

  /** Stored account state of a name, for clients whose hello carries no account uuid. */
  public enum Stored {
    /** No account, or an offline-mode account: never challenged. */
    NOT_PREMIUM,
    /** Online-mode account that also has a password. */
    PREMIUM_WITH_PASSWORD,
    /** Online-mode account without a password (auto-login only). */
    PREMIUM_PASSWORDLESS
  }

  /** Looks the name's account up (may load from the database - never call on a packet thread). */
  public static Stored stored(String username) {
    try {
      User account = User.getUserByUsername(username);
      if (account == null || !(account.isPremium || account.premiumDowngradePending))
        return Stored.NOT_PREMIUM;
      return account.isRegistered.get() ? Stored.PREMIUM_WITH_PASSWORD : Stored.PREMIUM_PASSWORDLESS;
    } catch (RuntimeException err) {
      return Stored.NOT_PREMIUM;
    }
  }

  /** Consecutive challenges of a name that the client did not complete: lower name -> count. */
  private static final Map<String, Integer> UNANSWERED = new ConcurrentHashMap<>();

  private static final int MAX_UNANSWERED = 2;

  /**
   * Whether a client WITHOUT a claimed uuid (1.16 - 1.19.0) is challenged. Such a client cannot be
   * told apart from an offline one before the challenge, and an offline client disconnects when it
   * is challenged - so only names whose stored account is online-mode are challenged.
   *
   * <p>An account WITH a password gets one unchallenged attempt after two unanswered challenges in
   * a row, so its owner can still log in with the password from an offline client (which then
   * turns the account back into an offline-mode account). A passwordless account is always
   * challenged: an unverified session can do nothing with it anyway.
   */
  public static boolean challengeWithoutUuid(String username, Stored stored) {
    if (username == null || stored == null || stored == Stored.NOT_PREMIUM) return false;
    if (stored == Stored.PREMIUM_PASSWORDLESS) return true;
    String key = username.toLowerCase(Locale.ROOT);
    Integer misses = UNANSWERED.get(key);
    if (misses != null && misses >= MAX_UNANSWERED) {
      UNANSWERED.remove(key);
      return false;
    }
    return true;
  }

  /** A challenge of this name was sent (counted as unanswered until it completes). */
  public static void challengeSent(String username) {
    if (username == null) return;
    if (UNANSWERED.size() > MAX_TRACKED) UNANSWERED.clear();
    UNANSWERED.merge(username.toLowerCase(Locale.ROOT), 1, Integer::sum);
  }

  /** The client answered the challenge of this name (whatever the verification result). */
  public static void challengeAnswered(String username) {
    if (username != null) UNANSWERED.remove(username.toLowerCase(Locale.ROOT));
  }
}
