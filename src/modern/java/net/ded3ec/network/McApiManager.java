package net.ded3ec.network;

import net.ded3ec.util.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.brigadier.context.CommandContext;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.ded3ec.AuthCoreServer;
import net.fabricmc.loader.api.FabricLoader;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * External API utilities for AuthCore: Mojang premium lookups, GeoIP lookups and permission
 * checks.
 *
 * <p>All HTTP traffic uses the JDK's built-in {@link HttpClient} (no third-party networking
 * libraries), with connection/read timeouts, HTTPS-only endpoints and in-memory TTL caches so the
 * Minecraft API and GeoIP services are not hammered on every player join. Private/loopback
 * addresses are never sent to external services.
 */
public final class McApiManager {

  /** JDK HTTP client with sane timeouts (no external networking library required). */
  private static final HttpClient HTTP_CLIENT =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  /** Gson instance for JSON parsing. */
  private static final Gson GSON = new Gson();

  /** Cache TTLs (milliseconds). */
  private static final long PREMIUM_HIT_TTL_MS = 6 * 60 * 60 * 1000L; // 6 hours
  private static final long PREMIUM_MISS_TTL_MS = 10 * 60 * 1000L; // 10 minutes
  private static final long PREMIUM_ERROR_RETRY_TTL_MS = 30_000L; // 30 seconds
  private static final long GEOIP_TTL_MS = 24 * 60 * 60 * 1000L; // 24 hours

  /** Maximum cache size before expired entries are purged (bounds memory under bot floods). */
  private static final int CACHE_MAX_ENTRIES = 10_000;

  /** Window during which the Mojang API is considered healthy after the last success. */
  private static final long API_HEALTH_WINDOW_MS = 10 * 60 * 1000L;

  /** Timestamp of the last successful Mojang/Minecraft API response. */
  private static volatile long lastApiSuccessAtMs = 0L;

  /** Caches for premium lookups (name → uuid and uuid → name). */
  private static final ConcurrentHashMap<String, CacheEntry<UUID>> PREMIUM_UUID_CACHE =
      new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<UUID, CacheEntry<String>> PREMIUM_NAME_CACHE =
      new ConcurrentHashMap<>();

  /** Cache for GeoIP lookups, keyed by IP address. */
  private static final ConcurrentHashMap<String, CacheEntry<JsonObject>> GEOIP_CACHE =
      new ConcurrentHashMap<>();

  private McApiManager() {}

  /**
   * Fetches the premium (Mojang) username for the given UUID, with caching.
   *
   * <p>{@code null} is returned when the UUID is not a paid account OR when the API is
   * temporarily unavailable. Use {@link #isPremiumApiHealthy()} to distinguish a definitive
   * negative from a degraded lookup.
   *
   * @param uuid the player's UUID
   * @return the premium username or {@code null} if the UUID is not a paid account / unavailable
   */
  public static @Nullable String getPremiumUsername(UUID uuid) {
    if (uuid == null) return null;

    CacheEntry<String> cached = PREMIUM_NAME_CACHE.get(uuid);
    if (cached != null && !cached.isExpired()) return cached.value;

    ApiResult result = checkMinecraftAPI("https://api.minecraftservices.com/minecraft/profile/lookup/" + uuid);

    String name = (result.success && result.body != null && result.body.has("name"))
        ? result.body.get("name").getAsString()
        : null;

    // A definitive "no account" is cached long; network failures are only cached briefly so a
    // transient Mojang outage self-heals on the next lookup.
    long ttl = result.success ? (name == null ? PREMIUM_MISS_TTL_MS : PREMIUM_HIT_TTL_MS) : PREMIUM_ERROR_RETRY_TTL_MS;
    PREMIUM_NAME_CACHE.put(uuid, new CacheEntry<>(name, ttl));
    purgeIfLarge(PREMIUM_NAME_CACHE);
    return name;
  }

  /**
   * Fetches the premium (Mojang) UUID for the given username, with caching. The API returns a
   * compact UUID that is formatted with hyphens.
   *
   * <p>{@code null} is returned when the name is not a paid account OR when the API is
   * temporarily unavailable. Use {@link #isPremiumApiHealthy()} to distinguish a definitive
   * negative from a degraded lookup.
   *
   * @param username the player's username
   * @return the premium UUID or {@code null} if the name is not a paid account / unavailable
   */
  public static @Nullable UUID getPremiumUuid(String username) {
    if (username == null || username.isBlank()) return null;

    CacheEntry<UUID> cached = PREMIUM_UUID_CACHE.get(username);
    if (cached != null && !cached.isExpired()) return cached.value;

    ApiResult result = checkMinecraftAPI("https://api.mojang.com/users/profiles/minecraft/" + username);

    UUID uuid = null;
    if (result.success && result.body != null && result.body.has("id")) {
      String id = result.body.get("id").getAsString();
      try {
        String formatted =
            id.replaceFirst(
                "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)",
                "$1-$2-$3-$4-$5");
        uuid = UUID.fromString(formatted);
      } catch (IllegalArgumentException err) {
        AuthCoreServer.LOGGER.debug(null, "Mojang API returned an invalid UUID for '{}': {}", username, id);
      }
    }

    long ttl = result.success ? (uuid == null ? PREMIUM_MISS_TTL_MS : PREMIUM_HIT_TTL_MS) : PREMIUM_ERROR_RETRY_TTL_MS;
    PREMIUM_UUID_CACHE.put(username, new CacheEntry<>(uuid, ttl));
    purgeIfLarge(PREMIUM_UUID_CACHE);
    return uuid;
  }

  /**
   * Whether the Mojang/Minecraft API has answered successfully recently. When this returns
   * {@code false}, premium lookups may be unreliable and features that hard-block players on
   * premium lookups should degrade gracefully instead of kicking.
   *
   * @return {@code true} if the API was reachable within the health window
   */
  public static boolean isPremiumApiHealthy() {
    return lastApiSuccessAtMs > 0
        && (System.currentTimeMillis() - lastApiSuccessAtMs) < API_HEALTH_WINDOW_MS;
  }

  /** Rate-limited warning shown when the Mojang API is unavailable (at most once per 5 minutes). */
  private static volatile long lastApiWarnAtMs = 0L;

  public static void warnApiUnavailable() {
    long now = System.currentTimeMillis();
    if (now - lastApiWarnAtMs < 5 * 60 * 1000L) return;
    lastApiWarnAtMs = now;
    AuthCoreServer.LOGGER.warn(
        false,
        "Mojang API is currently unreachable - premium (online-mode) detection is degraded. "
            + "Players are NOT blocked; premium auto-login will resume when the API recovers.");
  }

  /**
   * Performs a HTTPS GET request against a Minecraft/Mojang API endpoint and parses the JSON
   * response. Only https:// URLs are ever accepted (no SSRF).
   *
   * @param url the API URL to check
   * @return the parsed result (success flag + JSON body)
   */
  private static ApiResult checkMinecraftAPI(String url) {
    if (!url.startsWith("https://")) return ApiResult.failure();

    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(Duration.ofSeconds(5))
              .header("User-Agent", "AuthCore/1.0 (+https://github.com/DawnOfDedSec/AuthCore)")
              .header("Accept", "application/json")
              .GET()
              .build();

      HttpResponse<String> response =
          HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200)
        return AuthCoreServer.LOGGER.debug(
            null, "Minecraft API request '{}' returned status {}", url, response.statusCode());

      lastApiSuccessAtMs = System.currentTimeMillis();
      return ApiResult.success(GSON.fromJson(response.body(), JsonObject.class));
    } catch (IOException | InterruptedException err) {
      if (err instanceof InterruptedException) Thread.currentThread().interrupt();
      return AuthCoreServer.LOGGER.debug(null, "Error while fetching '{}':", url, err);
    } catch (IllegalArgumentException err) {
      return AuthCoreServer.LOGGER.debug(null, "Rejected invalid API URL '{}'", url);
    }
  }

  /** Minimal result wrapper distinguishing a successful API response from a failure. */
  private static final class ApiResult {
    final boolean success;
    final JsonObject body;

    private ApiResult(boolean success, JsonObject body) {
      this.success = success;
      this.body = body;
    }

    static ApiResult success(JsonObject body) {
      return new ApiResult(true, body);
    }

    static ApiResult failure() {
      return new ApiResult(false, null);
    }
  }

  /**
   * Fetches GeoIP data for the given IP address, with caching. Private/loopback addresses
   * (localhost, LAN ranges) are never sent to the external API and always return {@code null}.
   *
   * @param ipAddress the IPv4/IPv6 address to look up
   * @return the GeoIP data or {@code null} if unavailable or private
   */
  public static @Nullable JsonObject geoIp(String ipAddress) {
    if (ipAddress == null || ipAddress.isBlank() || isPrivateAddress(ipAddress)) return null;

    CacheEntry<JsonObject> cached = GEOIP_CACHE.get(ipAddress);
    if (cached != null && !cached.isExpired()) return cached.value;

    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create("https://apip.cc/api-json/" + ipAddress))
              .timeout(Duration.ofSeconds(5))
              .header("User-Agent", "AuthCore/1.0")
              .GET()
              .build();

      HttpResponse<String> response =
          HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

      JsonObject json = null;
      if (response.statusCode() == 200) json = GSON.fromJson(response.body(), JsonObject.class);

      GEOIP_CACHE.put(ipAddress, new CacheEntry<>(json, GEOIP_TTL_MS));
      purgeIfLarge(GEOIP_CACHE);
      return json;
    } catch (IOException | InterruptedException err) {
      if (err instanceof InterruptedException) Thread.currentThread().interrupt();
      return AuthCoreServer.LOGGER.debug(null, "Error while fetching GeoIP data for {}:", ipAddress, err);
    }
  }

  /**
   * Checks whether an address is a loopback or private (LAN) address. Local and private networks
   * are never exposed to external GeoIP lookups (privacy + performance).
   *
   * @param address the IP address string
   * @return {@code true} if the address is loopback/private
   */
  public static boolean isPrivateAddress(String address) {
    if (address == null || address.isBlank()) return true;
    try {
      InetAddress inet = InetAddress.getByName(address);
      return inet.isLoopbackAddress()
          || inet.isSiteLocalAddress()
          || inet.isAnyLocalAddress()
          || inet.isLinkLocalAddress();
    } catch (Exception err) {
      return true;
    }
  }

  /**
   * Checks whether a player is a Bedrock player using the Floodgate API (via reflection so
   * Floodgate remains optional).
   *
   * @param uuid the player's UUID
   * @return {@code true} if the player is a Bedrock player
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

  /** Simple TTL cache entry (immutable value + expiry timestamp). */
  private static final class CacheEntry<T> {
    final T value;
    final long expiresAtMs;

    CacheEntry(T value, long ttlMs) {
      this.value = value;
      this.expiresAtMs = System.currentTimeMillis() + ttlMs;
    }

    boolean isExpired() {
      return System.currentTimeMillis() > expiresAtMs;
    }
  }

  /**
   * Purges expired entries from a cache when it grows large. Prevents unbounded memory growth
   * under bot floods while staying cheap (only runs when the cache exceeds the threshold).
   */
  private static void purgeIfLarge(Map<?, ? extends CacheEntry<?>> cache) {
    if (cache.size() <= CACHE_MAX_ENTRIES) return;
    cache.values().removeIf(entry -> entry != null && entry.isExpired());
    if (cache.size() > CACHE_MAX_ENTRIES) cache.clear();
  }

  /** Combined permission checks: LuckPerms node OR vanilla OP level. */
  public static final class PermissionUtil {

    private static Boolean lpLoaded = null;
    private static LuckPerms lpApi = null;

    private static final java.lang.reflect.Method PERMISSION_LEVEL_FROM_LEVEL =
        resolveMethod("net.minecraft.command.permission.PermissionLevel", "fromLevel", int.class);
    private static final java.lang.reflect.Constructor<?> PERMISSION_LEVEL_CTOR =
        resolveConstructor(
            "net.minecraft.command.permission.Permission$Level",
            "net.minecraft.command.permission.PermissionLevel");
    private static final java.lang.reflect.Method PERMISSION_PREDICATE_HAS =
        resolveMethod(
            "net.minecraft.command.permission.PermissionPredicate",
            "hasPermission",
            resolveClass("net.minecraft.command.permission.Permission"));

    private static Class<?> resolveClass(String className) {
      try {
        return Class.forName(className);
      } catch (ClassNotFoundException err) {
        return null;
      }
    }

    private static java.lang.reflect.Method resolveMethod(
        String className, String name, Class<?>... params) {
      try {
        return Class.forName(className).getMethod(name, params);
      } catch (ReflectiveOperationException err) {
        return null;
      }
    }

    private static java.lang.reflect.Constructor<?> resolveConstructor(
        String className, String... paramClasses) {
      try {
        Class<?>[] params = new Class<?>[paramClasses.length];
        for (int i = 0; i < paramClasses.length; i++) params[i] = resolveClass(paramClasses[i]);
        return Class.forName(className).getConstructor(params);
      } catch (ReflectiveOperationException err) {
        return null;
      }
    }

    private PermissionUtil() {}

    private static synchronized boolean isLuckPermsLoaded() {
      if (lpLoaded != null) return lpLoaded;

      lpLoaded = FabricLoader.getInstance().isModLoaded("luckperms");

      if (lpLoaded) {
        try {
          lpApi = LuckPermsProvider.get();
        } catch (IllegalStateException e) {
          lpLoaded = false;
        }
      }
      return lpLoaded;
    }

    /**
     * Resolves the executing player from a command {@code requires}/{@code executes} context.
     *
     * <p>Version-agnostic: brigadier changed the {@code requires} parameter type between MC
     * versions (1.20.4 and earlier pass a {@code CommandContext}, 1.20.5+ pass the source
     * directly), and {@code ServerCommandSource#getPlayer()} only throws (console) on older
     * versions. Reflection sidesteps both differences so this source compiles unchanged for
     * Minecraft 1.16.0 through 1.21.x.
     *
     * @param ctx the requires/executes lambda argument (any version)
     * @return the executing player, or {@code null} for console/non-player sources
     */
    public static ServerPlayer resolvePlayer(Object ctx) {
      try {
        Object source = ctx;
        if (ctx instanceof CommandContext<?> context) source = context.getSource();
        if (source == null) return null;

        Object player = source.getClass().getMethod("getPlayer").invoke(source);
        return (player instanceof ServerPlayer serverPlayer) ? serverPlayer : null;
      } catch (ReflectiveOperationException err) {
        return null;
      }
    }

    /**
     * Combined permission check: LuckPerms node OR vanilla OP level. A {@code null} player (e.g.
     * the console) is always allowed so that server owners can manage AuthCore from the console.
     */
    public static boolean hasForSource(Object ctx, String node, int level) {
      ServerPlayer player = resolvePlayer(ctx);
      if (player == null) return true;
      return has(player, node, level);
    }

    /** Combined permission check for a player. */
    public static boolean has(ServerPlayer player, String node, int level) {
      if (player == null) return false;
      return hasNode(player, node) || hasLevel(player, level);
    }

    /**
     * Vanilla OP permission level check.
     *
     * <p>Uses reflection so the same binary/source works across Minecraft versions: 1.20.5+ uses
     * the new {@code Permission.PermissionLevel} API, older versions (1.16-1.20.4) use {@code
     * ServerPlayerEntity#hasPermissionLevel(int)}. Reflection handles are resolved lazily and
     * cached.
     */
    public static boolean hasLevel(ServerPlayer player, int level) {
      if (player == null) return false;

      // 1.20.5+ permission API (via cached reflection handles)
      if (PERMISSION_LEVEL_FROM_LEVEL != null
          && PERMISSION_LEVEL_CTOR != null
          && PERMISSION_PREDICATE_HAS != null) {
        try {
          Object permissions = player.getClass().getMethod("getPermissions").invoke(player);
          if (permissions != null) {
            Object permissionLevel = PERMISSION_LEVEL_FROM_LEVEL.invoke(null, level);
            Object permission = PERMISSION_LEVEL_CTOR.newInstance(permissionLevel);
            return (Boolean) PERMISSION_PREDICATE_HAS.invoke(permissions, permission);
          }
        } catch (ReflectiveOperationException err) {
          // fall through to the legacy check
        }
      }

      // Legacy (pre-1.20.5) permission level check
      try {
        return (Boolean)
            ServerPlayer.class.getMethod("hasPermissionLevel", int.class).invoke(player, level);
      } catch (ReflectiveOperationException err) {
        return false;
      }
    }

    /** LuckPerms permission node check. */
    public static boolean hasNode(ServerPlayer player, String node) {
      if (player == null || !isLuckPermsLoaded() || node == null || node.isBlank()) return false;

      User user = lpApi.getUserManager().getUser(player.getUUID());
      if (user == null) return false;

      QueryOptions query = lpApi.getContextManager().getQueryOptions(user).orElse(null);
      if (query == null) return false;

      return user.getCachedData().getPermissionData(query).checkPermission(node).asBoolean();
    }

    /** Custom rank system mapped to LuckPerms groups or OP levels. */
    public enum Rank {
      PLAYER(0),
      GAME_MASTER(1),
      ADMIN(2),
      OWNER(3);

      public final int level;

      Rank(int level) {
        this.level = level;
      }
    }

    /** Checks if a player has at least the given custom rank (LuckPerms group or OP level). */
    public static boolean hasRank(ServerPlayer player, Rank rank) {
      if (player == null) return false;

      if (isLuckPermsLoaded()) {
        User user = lpApi.getUserManager().getUser(player.getUUID());
        if (user != null) {
          String group =
              switch (rank) {
                case GAME_MASTER -> "gamemaster";
                case ADMIN -> "admin";
                case OWNER -> "owner";
                default -> "default";
              };

          QueryOptions query = lpApi.getContextManager().getQueryOptions(user).orElse(null);
          if (query != null
              && user.getCachedData()
                  .getPermissionData(query)
                  .checkPermission("group." + group)
                  .asBoolean()) return true;
        }
      }

      return switch (rank) {
        case PLAYER -> true;
        case GAME_MASTER -> hasLevel(player, 1);
        case ADMIN -> hasLevel(player, 2);
        case OWNER -> hasLevel(player, 4);
      };
    }
  }
}
