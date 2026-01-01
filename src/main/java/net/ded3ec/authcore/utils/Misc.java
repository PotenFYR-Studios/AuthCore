package net.ded3ec.authcore.utils;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.password4j.*;
import com.password4j.types.Hmac;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.loader.api.FabricLoader;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.jspecify.annotations.Nullable;

/**
 * Misc Utils ( consist of Minecraft Utils, Time Converter & Time Manager ) This class provides
 * various utility functions for Minecraft-related operations, including API calls for premium
 * accounts, GeoIP lookups, Bedrock player detection, time conversions, scheduling tasks, TPS
 * measurement, and password hashing/verification.
 */
public class Misc {

  /**
   * Http based client to fetch data from URL. Used for making HTTP requests to external APIs like
   * Minecraft services and GeoIP.
   */
  private static final OkHttpClient httpClient = new OkHttpClient();

  /**
   * Gson client driver for converting raw JSON to JsonObject based. Handles JSON parsing for API
   * responses.
   */
  private static final Gson gson = new Gson();

  /**
   * Fetch premium username with the help of uuid. Queries the Minecraft services API to get the
   * username associated with a UUID.
   *
   * @param uuid the player's UUID
   * @return the premium username or null if not found
   */
  public static String getPremiumUsername(UUID uuid) {
    JsonObject ctx =
        checkMinecraftAPI(
            "https://api.minecraftservices.com/minecraft/profile/lookup/" + uuid.toString());

    if (ctx == null) return null;
    else return ctx.get("name").getAsString();
  }

  /**
   * Fetch premium uuid with the help of username. Queries the Mojang API to get the UUID associated
   * with a username. The UUID from the API is in compact form and needs to be formatted with
   * hyphens.
   *
   * @param username the player's username
   * @return the premium UUID or null if not found
   */
  public static @Nullable UUID getPreimumUuid(String username) {
    JsonObject ctx =
        checkMinecraftAPI("https://api.mojang.com/users/profiles/minecraft/" + username);

    if (ctx == null) return null;

    // Format the compact UUID string into standard UUID format with hyphens
    String uuid =
        ctx.get("id")
            .getAsString()
            .replaceFirst(
                "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)",
                "$1-$2-$3-$4-$5");

    return UUID.fromString(uuid);
  }

  /**
   * Checking Minecraft API url. Validates the URL and makes a GET request to the Minecraft API,
   * parsing the JSON response.
   *
   * @param url the API URL to check
   * @return the JSON response as JsonObject or null if failed
   */
  private static @Nullable JsonObject checkMinecraftAPI(String url) {
    if (!url.isEmpty() && !url.startsWith("https://")) return null;

    Request request =
        new Request.Builder().url(url).get().header("User-Agent", "AuthMod/1.0").build();

    try (Response response = httpClient.newCall(request).execute()) {
      if (!response.isSuccessful())
        return Logger.debug(
            null, "Facing error while sending GET Minecraft API request {}: ", response.code());
      else return gson.fromJson(response.body().string(), JsonObject.class);

    } catch (IOException err) {
      return Logger.error(null, "Facing error while fetching data via Minecraft API: ", err);
    }
  }

  /**
   * Fetch GeoIp data using IPv4 Address. Makes a request to the GeoIP API to get location data for
   * the given IP address.
   *
   * @param ipAddress the IPv4 address to lookup
   * @return the GeoIP data as JsonObject
   */
  public static JsonObject geoIp(String ipAddress) {

    String url = "https://apip.cc/api-json/" + ipAddress;

    Request request = new Request.Builder().url(url).get().build();

    try (Response response = httpClient.newCall(request).execute()) {
      if (!response.isSuccessful())
        return Logger.debug(
            null, "Facing error while sending GET GeoIP request {}: ", response.code());
      else return gson.fromJson(response.body().string(), JsonObject.class);

    } catch (IOException err) {
      return Logger.error(null, "Facing error while fetching GeoIP data: ", err);
    }
  }

  /**
   * Check if a player is a Bedrock player using Floodgate API. Requires the Floodgate mod to be
   * loaded; uses reflection to access the API.
   *
   * @param uuid the player's UUID
   * @return true if the player is Bedrock, false otherwise
   */
  public static boolean isBedrockPlayer(UUID uuid) {
    if (!FabricLoader.getInstance().isModLoaded("floodgate")) return false;

    try {
      Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
      Object api = apiClass.getMethod("getInstance").invoke(null);

      if (api == null) return false;
      Object player = apiClass.getMethod("getPlayer", UUID.class).invoke(api, uuid);

      return player != null;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Time Converter for raw milliseconds to human-readable! Provides utilities to convert
   * milliseconds into readable duration or date strings.
   */
  public static class TimeConverter {

    /**
     * Converts ms to a Duration (e.g., "1 hour 15 minutes"). Uses Apache Commons
     * DurationFormatUtils to format the duration in words.
     *
     * @param millis the milliseconds to convert
     * @return the formatted duration string
     */
    public static String toDuration(long millis) {
      // "true, true" suppresses leading zeros and uses word representation
      return DurationFormatUtils.formatDurationWords(millis, true, true);
    }

    /**
     * Converts ms to a Timestamp/Date (e.g., "15 Dec 2025, 10:30 AM"). Formats the milliseconds
     * into a human-readable date and time string.
     *
     * @param millis the milliseconds to convert
     * @return the formatted date string
     */
    public static String toHumanDate(long millis) {
      DateTimeFormatter formatter =
          DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a").withZone(ZoneId.systemDefault());
      return formatter.format(Instant.ofEpochMilli(millis));
    }
  }

  /**
   * TPS meter for calculating tps within minecraft! Measures the server's ticks per second by
   * sampling tick times.
   */
  public static class TpsMeter {

    /** Sample size for Calculate the average tps. Number of tick samples to keep for averaging. */
    private static final int sampleSize = 1000;

    /** Tick array cached. Stores the timestamps of recent ticks. */
    private static final long[] tickTimes = new long[sampleSize];

    /** Tick count. Current index in the tickTimes array. */
    private static int tickIndex = 0;

    /** Check if filled. Indicates if the tickTimes array has been fully populated. */
    private static boolean filled = false;

    /**
     * Tick event handler by End Server Tick event in the Minecraft Server. Called on each server
     * tick to record the timestamp.
     */
    public static void onTick() {
      long now = System.nanoTime();
      tickTimes[tickIndex] = now;
      tickIndex = (tickIndex + 1) % sampleSize;

      if (tickIndex == 0) filled = true;
    }

    /**
     * Fetch TPS value with calculation. Calculates TPS by measuring the time elapsed over the
     * sample size. If not enough samples, returns 20.0 (default TPS).
     *
     * @return the calculated TPS
     */
    public static double get() {
      if (!filled) return 20.0;

      int lastIndex = (tickIndex + sampleSize - 1) % sampleSize;
      long first = tickTimes[tickIndex];
      long last = tickTimes[lastIndex];
      double elapsedSec = (last - first) / 1_000_000_000.0;

      return sampleSize / elapsedSec;
    }
  }

  /**
   * Hashing Algorithm with multiple algorithm and verify it with password! Provides password
   * hashing and verification using various algorithms like Argon2, Bcrypt, etc. Uses a fixed salt
   * for consistency.
   */
  public static class HashManager {

    /**
     * Salt value for hashing algorithm. Generated salt used across all hashing operations for
     * security.
     */
    private static final String salt = Arrays.toString(SaltGenerator.generate(32));

    /**
     * Argon2 based instance to hash and verify. Configured with specific parameters for Argon2
     * hashing.
     */
    private static final Argon2Function argon2 =
        Argon2Function.getInstance(65536, 3, 1, 16, com.password4j.types.Argon2.ID);

    /** Bcrypt based instance to hash and verify. Uses Bcrypt with cost factor 12. */
    private static final BcryptFunction bcrypt =
        BcryptFunction.getInstance(com.password4j.types.Bcrypt.B, 12);

    /** Scrypt based instance to hash and verify. Configured with N=16384, r=8, p=1, dkLen=32. */
    private static final ScryptFunction scrypt = ScryptFunction.getInstance(16384, 8, 1, 32);

    /**
     * PBKDF based instance to hash and verify. Uses PBKDF2 with SHA-256, 10000 iterations, 32-byte
     * key.
     */
    private static final PBKDF2Function pbkdf2 = PBKDF2Function.getInstance(Hmac.SHA256, 10000, 32);

    /** SHA256 based instance to hash and verify. Uses SHA-256 message digest. */
    private static final MessageDigestFunction sha256 =
        MessageDigestFunction.getInstance("SHA-256");

    /** SHA512 based instance to hash and verify. Uses SHA-512 message digest. */
    private static final MessageDigestFunction sha512 =
        MessageDigestFunction.getInstance("SHA-512");

    /**
     * MD5 based instance to hash and verify. Uses MD5 message digest (not recommended for
     * security).
     */
    private static final MessageDigestFunction md5 = MessageDigestFunction.getInstance("MD5");

    /**
     * Hashing the plain text as per HashManager algorithm. Selects the hashing algorithm based on
     * the provided string and hashes the password.
     *
     * @param algorithm the hashing algorithm name (e.g., "argon2", "bcrypt")
     * @param password the plain text password to hash
     * @return the hashed password string or null if invalid
     */
    public static String hash(String algorithm, String password) {
      if (algorithm == null) return null;
      else if (password == null) return null;

      return switch (algorithm.toLowerCase()) {
        case "argon2" -> argon2.hash(password, HashManager.salt).getResult();
        case "bcrypt" -> bcrypt.hash(password, HashManager.salt).getResult();
        case "pbkdf2" -> pbkdf2.hash(password, HashManager.salt).getResult();
        case "sha-256" -> sha256.hash(password, HashManager.salt).getResult();
        case "sha-512" -> sha512.hash(password, HashManager.salt).getResult();
        case "md5" -> md5.hash(password, HashManager.salt).getResult();
        case "scrypt" -> scrypt.hash(password, HashManager.salt).getResult();
        default -> null;
      };
    }

    /**
     * Verify the plain text with stored hash as per HashManager algorithm. Checks if the plain
     * password matches the stored hash using the specified algorithm.
     *
     * @param password the plain text password
     * @param storedHash the stored hashed password
     * @param algorithm the hashing algorithm name
     * @return true if the password matches, false otherwise
     */
    public static boolean verify(String password, String storedHash, String algorithm) {
      if (algorithm == null) return false;
      else if (password == null) return false;

      return switch (algorithm.toLowerCase()) {
        case "argon2" -> argon2.check(password, storedHash);
        case "bcrypt" -> bcrypt.check(password, storedHash);
        case "pbkdf2" -> pbkdf2.check(password, storedHash);
        case "sha-256" -> sha256.check(password, storedHash);
        case "sha-512" -> sha512.check(password, storedHash);
        case "md5" -> md5.check(password, storedHash);
        case "scrypt" -> scrypt.check(password, storedHash);
        default -> false;
      };
    }
  }

  /**
   * TaskScheduler provides a JavaScript-like scheduling system for server ticks. It supports
   * setTimeout and setInterval functionality, driven by a universal tick cycle. Tasks are executed
   * based on custom tick timing derived from the server TPS.
   */
  public static class TaskScheduler {
    private static final TaskScheduler INSTANCE = new TaskScheduler();
    private final Map<Integer, ScheduledTask> tasks = new ConcurrentHashMap<>();
    private final AtomicInteger idCounter = new AtomicInteger(0);
    private long tickCounter = 0;

    /**
     * Returns the singleton instance of the TaskScheduler.
     *
     * @return TaskScheduler instance
     */
    public static TaskScheduler getInstance() {
      return INSTANCE;
    }

    /**
     * Advances the tick counter and executes any tasks that are due. This method should be called
     * once per server tick. Tasks are executed safely with exception handling.
     */
    public void onTick() {
      tickCounter++;

      for (Iterator<Map.Entry<Integer, ScheduledTask>> it = tasks.entrySet().iterator();
          it.hasNext(); ) {

        Map.Entry<Integer, ScheduledTask> entry = it.next();
        ScheduledTask task = entry.getValue();

        if (tickCounter >= task.nextExecutionTick) {

          try {
            task.callback.run();
          } catch (Exception err) {
            Logger.error(false, "[Scheduler] Task " + task.id + " failed: ", err);
          }

          if (task.repeat) task.nextExecutionTick += task.delayTicks;
          else it.remove();
        }
      }
    }

    /**
     * Schedules a one-time task to run after the specified delay.
     *
     * @param callback the task to execute
     * @param delayMs delay in milliseconds before execution
     * @return unique task ID
     */
    public int setTimeout(Runnable callback, long delayMs) {
      int id = idCounter.incrementAndGet();
      long delayTicks = Math.floorDiv(delayMs, 1000) * ((long) TpsMeter.get());

      tasks.put(id, new ScheduledTask(id, delayTicks, false, callback, tickCounter + delayTicks));

      return id;
    }

    /**
     * Schedules a repeating task to run at the specified interval.
     *
     * @param callback the task to execute
     * @param intervalMs interval in milliseconds between executions
     * @return unique task ID
     */
    public int setInterval(Runnable callback, long intervalMs) {
      int id = idCounter.incrementAndGet();
      long delayTicks = Math.floorDiv(intervalMs, 1000) * ((long) TpsMeter.get());

      tasks.put(id, new ScheduledTask(id, delayTicks, true, callback, tickCounter + delayTicks));

      return id;
    }

    /**
     * Stops a scheduled task by its ID.
     *
     * @param id the task ID to cancel
     */
    public void stopTask(int id) {
      tasks.remove(id);
    }

    /** Represents a scheduled task with its execution metadata. */
    private static class ScheduledTask {
      int id;
      long delayTicks;
      boolean repeat;
      Runnable callback;
      long nextExecutionTick;

      /**
       * Constructs a new ScheduledTask.
       *
       * @param id unique task ID
       * @param delayTicks delay in ticks between executions
       * @param repeat true if repeating, false if one-time
       * @param callback the task to execute
       * @param nextExecutionTick tick count when the task should next execute
       */
      ScheduledTask(
          int id, long delayTicks, boolean repeat, Runnable callback, long nextExecutionTick) {
        this.id = id;
        this.delayTicks = delayTicks;
        this.repeat = repeat;
        this.callback = callback;
        this.nextExecutionTick = nextExecutionTick;
      }
    }
  }
}
