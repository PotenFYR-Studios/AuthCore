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
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * External API utilities for AuthCore: GeoIP lookups and permission checks.
 *
 * <p>Premium (online-mode) detection deliberately makes NO Mojang API calls - it is derived
 * exclusively from the server's own Mojang session authentication during login (see the
 * {@code ServerLoginNetworkHandlerMixin} premium-verification hook). All HTTP traffic uses
 * the JDK's built-in {@link HttpClient} (no third-party networking libraries), with
 * connection/read timeouts, HTTPS-only endpoints and in-memory TTL caches. Private/loopback
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

  /**
   * Ordered GeoIP providers (URL templates with {@code %s} for the IP). Each is tried in turn
   * until one returns a successful result - a flaky primary provider must not permanently null
   * the country/organization for an IP.
   */
  private static final String[] GEOIP_PROVIDERS = {
    "https://apip.cc/api-json/%s",
    "https://ipwho.is/%s"
  };

  /** ipwho.is responses use a different field shape and are normalized to the apip.cc shape. */
  private static final String IPWHOIS_TEMPLATE = "https://ipwho.is/%s";

  /** Cache TTLs (milliseconds). */
  private static final long GEOIP_TTL_MS = 24 * 60 * 60 * 1000L; // 24 hours (success)
  private static final long GEOIP_FAILURE_TTL_MS = 5 * 60 * 1000L; // 5 minutes (failure)

  /** Maximum cache size before expired entries are purged (bounds memory under bot floods). */
  private static final int CACHE_MAX_ENTRIES = 10_000;

  /** Cache for GeoIP lookups, keyed by IP address. */
  private static final ConcurrentHashMap<String, CacheEntry<JsonObject>> GEOIP_CACHE =
      new ConcurrentHashMap<>();

  private McApiManager() {}

  /**
   * Fetches GeoIP data for the given IP address, with caching. Private/loopback addresses
   * (localhost, LAN ranges) are never sent to the external API and always return {@code null}.
   *
   * <p>This method performs a SYNCHRONOUS HTTP call (up to {@code GEOIP_REQUEST_TIMEOUT_MS}).
   * NEVER call it on the server thread - use {@link #geoIpCached} for join-path decisions or
   * run it on the IO executor (see {@code User.connect}).
   *
   * @param ipAddress the IPv4/IPv6 address to look up
   * @return the GeoIP data or {@code null} if unavailable or private
   */
  public static @Nullable JsonObject geoIp(String ipAddress) {
    if (ipAddress == null || ipAddress.isBlank() || isPrivateAddress(ipAddress)) return null;

    CacheEntry<JsonObject> cached = GEOIP_CACHE.get(ipAddress);
    if (cached != null && !cached.isExpired()) return cached.value;

    JsonObject json = null;
    for (String template : GEOIP_PROVIDERS) {
      try {
        HttpRequest request =
            HttpRequest.newBuilder(URI.create(template.formatted(ipAddress)))
                .timeout(Duration.ofSeconds(5))
                .header("User-Agent", "AuthCore/1.0")
                .GET()
                .build();

        HttpResponse<String> response =
            HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) continue;

        JsonObject parsed = GSON.fromJson(response.body(), JsonObject.class);
        if (parsed == null) continue;
        if (template.equals(IPWHOIS_TEMPLATE)) parsed = normalizeIpWhoIs(parsed);
        if (parsed != null
            && parsed.has("status")
            && "success".equalsIgnoreCase(parsed.get("status").getAsString())) {
          json = parsed;
          break;
        }
      } catch (IOException | InterruptedException err) {
        if (err instanceof InterruptedException) Thread.currentThread().interrupt();
        // Single-line debug message - never a full stack trace at INFO. A GeoIP timeout is
        // expected on flaky networks and must not alarm admins or spam the console.
        AuthCoreServer.LOGGER.debug(
            null, "GeoIP lookup failed for {} ({}): {}", ipAddress, template, err.toString());
      }
    }

    // Cache successes for a day, failures (null) for only 5 minutes: a transient network
    // timeout must not permanently null the country/organization for that IP.
    GEOIP_CACHE.put(ipAddress, new CacheEntry<>(json, json != null ? GEOIP_TTL_MS : GEOIP_FAILURE_TTL_MS));
    purgeIfLarge(GEOIP_CACHE);
    return json;
  }

  /**
   * Normalizes an ipwho.is response into the apip.cc shape used by AuthCore
   * ({@code status}/{@code CountryName}/{@code continentCode}/{@code org}), so all consumers
   * work identically regardless of which provider resolved the IP.
   *
   * @param json the raw ipwho.is response
   * @return the normalized response, or {@code null} if ipwho.is reported a failure
   */
  private static @Nullable JsonObject normalizeIpWhoIs(JsonObject json) {
    if (json == null || !json.has("success") || !json.get("success").getAsBoolean()) return null;

    JsonObject out = new JsonObject();
    out.addProperty("status", "success");
    if (json.has("country") && !json.get("country").isJsonNull())
      out.addProperty("CountryName", json.get("country").getAsString());
    if (json.has("continent_code") && !json.get("continent_code").isJsonNull())
      out.addProperty("continentCode", json.get("continent_code").getAsString());
    if (json.get("connection") instanceof JsonObject conn
        && conn.has("org")
        && !conn.get("org").isJsonNull()) out.addProperty("org", conn.get("org").getAsString());
    return out;
  }

  /**
   * Cache-only GeoIP lookup: returns the cached result if present, otherwise {@code null}.
   * NEVER performs an HTTP call, so it is safe on the server thread. The async lookup in
   * {@code User.connect} populates the cache shortly after join.
   *
   * @param ipAddress the IPv4/IPv6 address to look up
   * @return the cached GeoIP data, or {@code null} when not cached yet
   */
  public static @Nullable JsonObject geoIpCached(String ipAddress) {
    if (ipAddress == null || ipAddress.isBlank()) return null;
    CacheEntry<JsonObject> cached = GEOIP_CACHE.get(ipAddress);
    if (cached == null) return null;
    if (cached.isExpired()) {
      GEOIP_CACHE.remove(ipAddress);
      return null;
    }
    return cached.value;
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
    if (!net.ded3ec.compat.Compat.isModLoaded("floodgate")) return false;

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

      lpLoaded = net.ded3ec.compat.Compat.isModLoaded("luckperms");

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
     * <p>Multi-candidate reflection: the method name differs per mapping era
     * ({@code getPlayer} / {@code getPlayerOrException} on unobfuscated versions, the
     * intermediary ids on 1.16-1.21). A direct call compiled for one era cannot compile for
     * the others, so every candidate is tried.
     */
    public static ServerPlayer resolvePlayer(Object ctx) {
      try {
        Object source = ctx;
        if (ctx instanceof CommandContext<?> context) source = context.getSource();
        if (source == null) return null;

        for (String name :
            new String[] {"getPlayer", "getPlayerOrException", "method_44023", "method_9207"}) {
          try {
            Object player = source.getClass().getMethod(name).invoke(source);
            if (player instanceof ServerPlayer serverPlayer) return serverPlayer;
          } catch (ReflectiveOperationException | RuntimeException ignored) {
            // try the next name
          }
        }
        return null;
      } catch (RuntimeException err) {
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
     * <p>Multi-candidate reflection: the API differs per mapping era ({@code
     * hasPermissionLevel(int)} on 1.16-1.18, the {@code net.minecraft.command.permission}
     * API on 1.20.5+, the {@code net.minecraft.server.permissions} set on 1.21.11+), and a
     * direct call compiled for one era cannot compile for the others. Level 0 means "all
     * players" - the default for every user command.
     */
    public static boolean hasLevel(ServerPlayer player, int level) {
      if (player == null) return false;
      // OP level 0 means "all players" (see the command permission config docs)
      if (level <= 0) return true;

      // 1.21.11+/26.x: net.minecraft.server.permissions.PermissionSet
      try {
        Class<?> levelClass = Class.forName("net.minecraft.server.permissions.PermissionLevel");
        Object byId = levelClass.getDeclaredField("BY_ID").get(null);
        if (byId instanceof java.util.function.IntFunction<?> fn) {
          Object permissionLevel = fn.apply(level);
          Class<?> hasCommandLevel =
              Class.forName("net.minecraft.server.permissions.Permission$HasCommandLevel");
          Object permission = hasCommandLevel.getConstructor(levelClass).newInstance(permissionLevel);
          Object permissions = player.getClass().getMethod("permissions").invoke(player);
          Class<?> permissionClass = Class.forName("net.minecraft.server.permissions.Permission");
          return (Boolean)
              permissions
                  .getClass()
                  .getMethod("hasPermission", new Class<?>[] {permissionClass})
                  .invoke(permissions, permission);
        }
      } catch (ReflectiveOperationException | RuntimeException ignored) {
        // fall through to the next API shape
      }

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
