package net.ded3ec.utils;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.*;

import net.ded3ec.AuthCoreServer;
import net.fabricmc.loader.api.FabricLoader;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.network.ServerPlayerEntity;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jspecify.annotations.Nullable;

/**
 * McApiManager Utils ( consist of Minecraft Utils, Time Converter & Time Manager ) This class
 * provides various utility functions for Minecraft-related operations, including API calls for
 * premium accounts, GeoIP lookups, Bedrock player detection, time conversions, scheduling tasks,
 * TPS measurement, and password hashing/verification.
 */
public class McApiManager {

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

  public static final class PermissionUtil {

    private static Boolean lpLoaded = null;
    private static LuckPerms lpApi = null;

    private static boolean isLuckPermsLoaded() {
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
     * Combined permission check: - LuckPerms node - OR custom integer rank - OR vanilla OP level
     */
    public static boolean has(ServerPlayerEntity player, String node, int level) {
      return hasNode(player, node) || hasLevel(player, level);
    }

    // ------------------------------------------------------------
    //  VANILLA OP LEVELS (1.21.11 Yarn)
    // ------------------------------------------------------------

    public static boolean hasLevel(ServerPlayerEntity player, int level) {
      // Vanilla exposes the permission level through the player's interaction manager
      return player.getPermissions().hasPermission(new Permission.Level(PermissionLevel.fromLevel(level)));
    }

    // ------------------------------------------------------------
    //  LUCKPERMS NODE CHECK
    // ------------------------------------------------------------
    public static boolean hasNode(ServerPlayerEntity player, String node) {
      if (!isLuckPermsLoaded()) return false;

      User user = lpApi.getUserManager().getUser(player.getUuid());
      if (user == null) return false;

      QueryOptions query = lpApi.getContextManager().getQueryOptions(user).orElse(null);
      if (query == null) return false;

      return user.getCachedData().getPermissionData(query).checkPermission(node).asBoolean();
    }

    // ------------------------------------------------------------
    //  CUSTOM RANK SYSTEM (ADMIN / OWNER / GM / ETC.)
    // ------------------------------------------------------------
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

    /**
     * Check if player has at least this custom rank. You can map this to LuckPerms groups OR OP
     * levels.
     */
    public static boolean hasRank(ServerPlayerEntity player, Rank rank) {
      // 1) LuckPerms group check
      if (isLuckPermsLoaded()) {
        User user = lpApi.getUserManager().getUser(player.getUuid());
        if (user != null) {
          String group =
              switch (rank) {
                case GAME_MASTER -> "gamemaster";
                case ADMIN -> "admin";
                case OWNER -> "owner";
                default -> "default";
              };

          QueryOptions query = lpApi.getContextManager().getQueryOptions(user).orElse(null);
          if (query != null) {
            boolean inGroup =
                user.getCachedData()
                    .getPermissionData(query)
                    .checkPermission("group." + group)
                    .asBoolean();

            if (inGroup) return true;
          }
        }
      }

      // 2) Fallback to OP-level mapping
      return switch (rank) {
        case PLAYER -> true;
        case GAME_MASTER -> hasLevel(player, 1);
        case ADMIN -> hasLevel(player, 2);
        case OWNER -> hasLevel(player, 4);
      };
    }
  }

  /**
   * Fetch premium uuid with the help of username. Queries the Mojang API to get the UUID associated
   * with a username. The UUID from the API is in compact form and needs to be formatted with
   * hyphens.
   *
   * @param username the player's username
   * @return the premium UUID or null if not found
   */
  public static @Nullable UUID getPremiumUuid(String username) {
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
        return AuthCoreServer.LOGGER.debug(
            null, "Facing error while sending GET Minecraft API request {}: ", response.code());
      else return gson.fromJson(response.body().string(), JsonObject.class);

    } catch (IOException err) {
      return AuthCoreServer.LOGGER.error(
          null, "Facing error while fetching data via Minecraft API: ", err);
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
        return AuthCoreServer.LOGGER.debug(
            null, "Facing error while sending GET GeoIP request {}: ", response.code());
      else return gson.fromJson(response.body().string(), JsonObject.class);

    } catch (IOException err) {
      return AuthCoreServer.LOGGER.error(null, "Facing error while fetching GeoIP data: ", err);
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
}
