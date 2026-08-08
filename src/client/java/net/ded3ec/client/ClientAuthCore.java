package net.ded3ec.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;

/**
 * Client companion for AuthCore (client-included build, Minecraft 1.19.4+).
 *
 * <p>Shows a custom username/password login screen before connecting to servers that run
 * AuthCore, then automatically executes {@code /login} (or {@code /register}) after joining.
 *
 * <p>Configuration lives in {@code config/authcore-client.json}:
 *
 * <pre>{@code
 * {
 *   "enabled": true,
 *   "auto-login": true,
 *   "servers": ["*"],        // hostnames to intercept ("*" = all)
 *   "theme": {
 *     "title-color": 5636095,
 *     "label-color": 16777215,
 *     "subtitle-color": 11184810
 *   }
 * }
 * }</pre>
 */
public final class ClientAuthCore implements ClientModInitializer {

  /** Credentials captured by the login screen, waiting to be sent after joining. */
  public static volatile PendingLogin pending;

  /** The last server the player attempted to join (used by /authclient). */
  public static volatile ServerContext lastServer;

  private static volatile boolean enabled = true;
  private static volatile boolean autoLogin = true;
  private static volatile JsonArray servers = new JsonArray();

  /** Theme colors applied by the login screen. */
  public static int themeTitleColor = 0x55FFFF;
  public static int themeSubtitleColor = 0xAAAAAA;
  public static int themeLabelColor = 0xFFFFFF;

  @Override
  public void onInitializeClient() {
    loadConfig();
    registerAuthClientCommand();
  }

  private void registerAuthClientCommand() {
    try {
      net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register(
          (dispatcher, registryAccess) -> {
            var command =
                net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("authclient")
                    .executes(
                        context -> {
                          ServerContext ctx = lastServer;
                          if (ctx == null) {
                            context.getSource()
                                .sendFeedback(
                                    net.minecraft.text.Text.literal(
                                        "AuthCore: no server to log in to yet. Join a server first."));
                            return 0;
                          }
                          MinecraftClient.getInstance().setScreen(
                              new LoginScreen(
                                  null, ctx.address, ctx.info, ctx.quickPlay, ctx.cookieStorage));
                          return 1;
                        });
            dispatcher.register(command);
          });
    } catch (Exception err) {
      // client command API not available on this version
    }
  }

  private static void loadConfig() {
    try {
      Path path = FabricLoader.getInstance().getConfigDir().resolve("authcore-client.json");
      if (Files.exists(path)) {
        JsonObject json = new Gson().fromJson(Files.readString(path), JsonObject.class);
        if (json == null) return;
        if (json.has("enabled")) enabled = json.get("enabled").getAsBoolean();
        if (json.has("auto-login")) autoLogin = json.get("auto-login").getAsBoolean();
        if (json.has("servers") && json.get("servers").isJsonArray())
          servers = json.getAsJsonArray("servers");
        if (json.has("theme")) {
          JsonObject theme = json.getAsJsonObject("theme");
          if (theme.has("title-color")) themeTitleColor = theme.get("title-color").getAsInt();
          if (theme.has("label-color")) themeLabelColor = theme.get("label-color").getAsInt();
          if (theme.has("subtitle-color")) themeSubtitleColor = theme.get("subtitle-color").getAsInt();
        }
      }
    } catch (Exception err) {
      // fall back to defaults
    }
  }

  /** Whether the client companion is enabled and should intercept the given server host. */
  public static boolean shouldIntercept(String host) {
    if (!enabled || host == null) return false;
    for (var entry : servers) {
      String rule = entry.getAsString();
      if ("*".equals(rule) || host.equalsIgnoreCase(rule) || host.startsWith(rule + ":"))
        return true;
    }
    return false;
  }

  /** Called from the client mixin after the player joins the world. */
  public static void onJoined(ClientPlayNetworkHandler handler) {
    PendingLogin login = pending;
    if (login == null || !autoLogin) return;
    pending = null;

    String command = "/login " + login.password + (login.registerMode ? " " + login.password : "");
    new Thread(
            () -> {
              try {
                Thread.sleep(2000);
              } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
              }
              MinecraftClient.getInstance().execute(
                  () -> {
                    try {
                      // Primary path: auto-login command
                      handler.sendChatMessage(command);
                    } catch (Exception fallbackErr) {
                      // Fallback: the client cannot send the command (e.g. signed-chat
                      // restrictions) - tell the player to type it manually
                      MinecraftClient.getInstance().player.sendMessage(
                          net.minecraft.text.Text.literal(
                              "[AuthCore] Auto-login unavailable - please type: " + command),
                          false);
                    }
                  });
            },
            "AuthCore-AutoLogin")
        .start();
  }

  /** Credentials entered in the login screen. */
  public static final class PendingLogin {
    public final String username;
    public final String password;
    public final boolean registerMode;

    public PendingLogin(String username, String password, boolean registerMode) {
      this.username = username;
      this.password = password;
      this.registerMode = registerMode;
    }
  }

  /** Server connection context captured by the mixin (used by /authclient). */
  public static final class ServerContext {
    public final net.minecraft.client.network.ServerAddress address;
    public final net.minecraft.client.network.ServerInfo info;
    public final boolean quickPlay;
    public final net.minecraft.client.network.CookieStorage cookieStorage;

    public ServerContext(
        net.minecraft.client.network.ServerAddress address,
        net.minecraft.client.network.ServerInfo info,
        boolean quickPlay,
        net.minecraft.client.network.CookieStorage cookieStorage) {
      this.address = address;
      this.info = info;
      this.quickPlay = quickPlay;
      this.cookieStorage = cookieStorage;
    }
  }
}
