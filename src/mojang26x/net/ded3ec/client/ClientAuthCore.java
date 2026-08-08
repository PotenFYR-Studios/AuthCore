package net.ded3ec.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;

/**
 * Client companion for AuthCore - bundled in the SAME universal jar (environment "*").
 *
 * <p>Shows a custom username/password login screen before connecting to servers that run
 * AuthCore, then automatically executes {@code /login} (or {@code /register}) after joining.
 *
 * <p>Version-safety: this class only references classes that exist on every supported
 * Minecraft version (1.16.x - 26.x). Version-specific types (CookieStorage etc.) are only
 * touched via reflection, so loading the class on an old client is always safe - the
 * companion simply deactivates itself where the screen APIs do not exist (1.20.1 and older),
 * and auto-login keeps working everywhere via the version-stable {@code sendChatMessage}.
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
    // The login screen APIs (CookieStorage etc.) only exist on 1.20.2+. On older clients the
    // companion stays inert (no screen, no command) but the jar itself loads without issues.
    if (!clientSupported()) return;
    loadConfig();
    registerAuthClientCommand();
  }

  /** Whether this client build has the APIs needed by the companion (1.20.2+). */
  public static boolean clientSupported() {
    try {
      Class.forName("net.minecraft.client.multiplayer.TransferState");
      return true;
    } catch (Throwable notPresent) {
      return false;
    }
  }

  private void registerAuthClientCommand() {
    try {
      // net.fabricmc.fabric.api.client.command.v2 - loaded reflectively so the class body
      // never resolves fabric-api classes on old clients (see class javadoc)
      Class<?> callback =
          Class.forName("net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback");
      Class<?> command = Class.forName("com.mojang.brigadier.Command");
      Class<?> manager = Class.forName("net.fabricmc.fabric.api.client.command.v2.ClientCommandManager");

      Object event = callback.getField("EVENT").get(null);

      Object node = buildAuthClientCommandNode(command, manager);
      if (node != null) {
        // Register via a proxy for the callback interface; the interface is resolved at
        // runtime from the fabric-api present on this client version.
        Object registration =
            java.lang.reflect.Proxy.newProxyInstance(
                callback.getClassLoader(),
                new Class<?>[] {callback},
                (proxy, method, args) -> {
                  if (method.getName().equals("register")) {
                    Object dispatcherArg = args[0];
                    Class<?> commandNode = Class.forName("com.mojang.brigadier.tree.CommandNode");
                    dispatcherArg
                        .getClass()
                        .getMethod("register", commandNode)
                        .invoke(dispatcherArg, node);
                  }
                  return null;
                });
        event.getClass().getMethod("register", callback).invoke(event, registration);
      }
    } catch (Throwable err) {
      // client command API not available on this version
    }
  }

  /** Builds the /authclient command node reflectively; returns null when unsupported. */
  private static Object buildAuthClientCommandNode(Class<?> command, Class<?> manager) {
    try {
      Object run =
          java.lang.reflect.Proxy.newProxyInstance(
              command.getClassLoader(),
              new Class<?>[] {command},
              (proxy, method, args) -> {
                if (method.getName().equals("run")) {
                  ServerContext ctx = lastServer;
                  if (ctx == null) return 0;
                  openLoginScreen(null, Minecraft.getInstance(), ctx);
                  return 1;
                }
                return null;
              });
      Object literal = manager.getMethod("literal", String.class).invoke(null, "authclient");
      return literal.getClass().getMethod("executes", command).invoke(literal, run);
    } catch (Throwable ignored) {
      return null; // command API shape differs - skip the command
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

  /**
   * Opens the AuthCore login screen for the server the player is about to join. Called from
   * {@code ConnectScreenMixin}; only ever invoked on clients where the screen APIs exist.
   */
  public static void openLoginScreen(
      Screen parent, Minecraft client, Object address, Object info,
      boolean quickPlay, Object cookieStorage) {
    try {
      Class<?> ls = Class.forName("net.ded3ec.client.LoginScreen");
      java.lang.reflect.Constructor<?> ctor =
          ls.getConstructor(
              Screen.class,
              Class.forName("net.minecraft.client.multiplayer.resolver.ServerAddress"),
              Class.forName("net.minecraft.client.multiplayer.ServerData"),
              boolean.class,
              Class.forName("net.minecraft.client.multiplayer.TransferState"));
      client.setScreenAndShow((Screen) ctor.newInstance(parent, address, info, quickPlay, cookieStorage));
    } catch (Throwable err) {
      // screen API unavailable - connect normally without the login screen
    }
  }

  /** Convenience overload used by /authclient (no parent screen). */
  public static void openLoginScreen(Screen parent, Minecraft client, ServerContext ctx) {
    openLoginScreen(
        parent, client, ctx.address, ctx.info, ctx.quickPlay, ctx.cookieStorage);
  }

  /** Called from the client mixin after the player joins the world. */
  public static void onJoined(ClientPacketListener handler) {
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
              Minecraft.getInstance().execute(
                  () -> {
                    try {
                      // sendChatMessage(String) exists on every supported version (1.16 - 26.x)
                      handler.sendChat(command);
                    } catch (Throwable fallbackErr) {
                      // The client cannot send the command (e.g. signed-chat restrictions) -
                      // tell the player to type it manually
                      Minecraft.getInstance()
                          .player
                          .sendSystemMessage(
                              Component.literal(
                                  "[AuthCore] Auto-login unavailable - please type: " + command));
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

  /**
   * Server connection context captured by the mixin (used by /authclient). Fields are typed as
   * {@link Object} so the class loads on every Minecraft version - the mixin only ever fills
   * them on clients where the concrete types exist.
   */
  public static final class ServerContext {
    public final Object address;
    public final Object info;
    public final boolean quickPlay;
    public final Object cookieStorage;

    public ServerContext(
        Object address, Object info, boolean quickPlay, Object cookieStorage) {
      this.address = address;
      this.info = info;
      this.quickPlay = quickPlay;
      this.cookieStorage = cookieStorage;
    }
  }
}
