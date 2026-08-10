package net.ded3ec.client;

/*? if fabric {*/

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
/*? if < 1.19.4 {*/
/*import net.minecraft.network.chat.TextComponent;
*//*?}*/

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
      /*? if < 1.21.6 {*/
      /*client.setScreen((Screen) ctor.newInstance(parent, address, info, quickPlay, cookieStorage));
      *//*?} else {*/
      client.setScreenAndShow((Screen) ctor.newInstance(parent, address, info, quickPlay, cookieStorage));
      /*?}*/
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
    // Companion attestation handshake: announce the companion and echo the last
    // session token so the server can resume the session (best-effort).
    companionHandshake(handler);

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
                      /*? if < 1.19.4 {*/
                      /*// player.chat(String) exists on every supported version (1.16 - 26.x)
                      Minecraft.getInstance().player.chat(command);
                      *//*?} else {*/
                      // sendChatMessage(String) exists on every supported version (1.16 - 26.x)
                      handler.sendChat(command);
                      /*?}*/
                    } catch (Throwable fallbackErr) {
                      // The client cannot send the command (e.g. signed-chat restrictions) -
                      // tell the player to type it manually
                      Minecraft.getInstance()
                          .player
                          /*? if < 1.19.4 {*/
                          /*.displayClientMessage(
                              new TextComponent(
                                  "[AuthCore] Auto-login unavailable - please type: " + command),
                              false)
                          *//*?} else if < 26 {*/
                          .displayClientMessage(
                              Component.literal(
                                  "[AuthCore] Auto-login unavailable - please type: " + command),
                              false)
                          /*?} else {*/
                          /*.sendSystemMessage(
                              Component.literal(
                                  "[AuthCore] Auto-login unavailable - please type: " + command))
                          *//*?}*/;
                    }
                  });
            },
            "AuthCore-AutoLogin")
        .start();
  }

  /** Credentials entered in the login screen. */
  // ------------------------------------------------------------------ companion
  // The client side of the SAME jar can attest itself to the server (HELLO +
  // challenge-response) and echo session tokens for resume. All best-effort:
  // vanilla clients (or clients that cannot send the payload shape of the running
  // version) simply fall back to the normal chat login.

  /** The local player UUID across every version (User accessors were renamed). */
  private static String currentPlayerId() {
    try {
      Object user = net.minecraft.client.Minecraft.getInstance().getUser();
      for (String m : new String[] {"getProfileId", "getUuid"}) {
        try {
          Object v = user.getClass().getMethod(m).invoke(user);
          if (v != null) return v.toString();
        } catch (ReflectiveOperationException ignored) {
          // try the next name
        }
      }
      Object profile = user.getClass().getMethod("getGameProfile").invoke(user);
      Object id = profile.getClass().getMethod("getId").invoke(profile);
      return id != null ? id.toString() : "";
    } catch (ReflectiveOperationException e) {
      return "";
    }
  }


  /** Last session token received from the server (resume evidence). */
  private static volatile String lastSessionToken = null;

  /** Sends a companion message to the server over the interop channel. */
  private static void sendCompanion(ClientPacketListener handler, String line) {
    try {
      byte[] data = line.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      Class<?> idClass = Class.forName("net.minecraft.resources.ResourceLocation");
      Object id;
      try {
        id = idClass.getMethod("tryParse", String.class).invoke(null, "authcore:auth");
      } catch (ReflectiveOperationException e) {
        id = idClass.getConstructor(String.class).newInstance("authcore:auth");
      }
      Object buf =
          Class.forName("net.minecraft.network.FriendlyByteBuf")
              .getConstructor(io.netty.buffer.ByteBuf.class)
              .newInstance(io.netty.buffer.Unpooled.buffer(data.length));
      buf.getClass().getMethod("writeBytes", byte[].class).invoke(buf, (Object) data);

      Object packet = null;
      try {
        packet =
            Class.forName("net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket")
                .getConstructor(idClass, Class.forName("net.minecraft.network.FriendlyByteBuf"))
                .newInstance(id, buf);
      } catch (ReflectiveOperationException e) {
        // 1.20.5+ shape: constructor takes a CustomPacketPayload - try the payload record
        Class<?> payloadClass = Class.forName("net.minecraft.network.protocol.common.custom.DiscardedPayload");
        Object payload = payloadClass.getConstructor(idClass, Class.forName("net.minecraft.network.FriendlyByteBuf"))
            .newInstance(id, buf);
        packet =
            Class.forName("net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket")
                .getConstructor(payloadClass)
                .newInstance(payload);
      }
      if (packet != null) handler.getClass().getMethod("send", net.minecraft.network.protocol.Packet.class).invoke(handler, packet);
    } catch (ReflectiveOperationException | RuntimeException ignored) {
      // companion messaging is best-effort - the chat login flow still works
    }
  }

  /** Handshake on join: announce the companion and echo the stored session token. */
  private static void companionHandshake(ClientPacketListener handler) {
    try {
      StringBuilder sb =
          new StringBuilder(net.ded3ec.security.ClientGuard.MSG_HELLO)
              .append("|")
              .append(net.ded3ec.security.ClientGuard.PROTOCOL_VERSION);
      String token = lastSessionToken;
      if (token != null)
        sb.append("|")
            .append(net.ded3ec.security.ClientGuard.MSG_SESSION_TOKEN_ECHO)
            .append("|")
            .append(token);
      sendCompanion(handler, sb.toString());
    } catch (RuntimeException ignored) {
      // best-effort
    }
  }

  /** Handles server -> client companion payloads (challenge + session token). */
  public static void onServerPayload(ClientPacketListener handler, Object packet) {
    try {
      String channel = null;
      for (String m : new String[] {"getName", "getIdentifier"}) {
        try {
          Object v = packet.getClass().getMethod(m).invoke(packet);
          if (v != null) {
            channel = v.toString();
            break;
          }
        } catch (ReflectiveOperationException ignored) {
          // try the next name
        }
      }
      if (!"authcore:auth".equals(channel)) return;
      String line = net.ded3ec.compat.Compat.readCustomPayloadData(packet);
      if (line == null || line.isEmpty()) return;
      String[] parts = line.split("\\|", -1);

      switch (parts[0]) {
        case net.ded3ec.security.ClientGuard.MSG_CHALLENGE:
          // CHALLENGE|<proto>|<nonce> -> answer CHALLENGE_RESP|<proto>|<mac>
          if (parts.length >= 3) {
            String mac =
                net.ded3ec.security.ClientGuard.hmac(parts[2] + ":" + currentPlayerId());
            sendCompanion(
                handler,
                net.ded3ec.security.ClientGuard.MSG_CHALLENGE_RESP
                    + "|"
                    + net.ded3ec.security.ClientGuard.PROTOCOL_VERSION
                    + "|"
                    + mac);
          }
          break;

        case net.ded3ec.security.ClientGuard.MSG_SESSION_TOKEN:
          if (parts.length >= 2) lastSessionToken = parts[1];
          break;

        default:
          break;
      }
    } catch (RuntimeException ignored) {
      // best-effort
    }
  }
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
/*?}*/
