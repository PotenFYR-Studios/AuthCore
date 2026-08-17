package net.ded3ec.mixin;


import java.util.UUID;

import net.ded3ec.AuthCoreServer;
import net.ded3ec.network.McApiManager;

/*? if fabric {*/
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
/*?}*/
import net.minecraft.ChatFormatting;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Universal login interception (1.16 - 26.x).
 *
 * <p>Reads the profile from the hello packet via reflection because the packet shape changed
 * across versions ({@code getProfile()} on 1.16, {@code name()}/{@code profileId()} on 1.19.3+).
 * Enforces the bedrock restriction and leaves offline-mode handling to vanilla - on 1.16-1.20.4
 * vanilla offline servers accept the profile without session verification, on 1.21.x the modern
 * offline flow handles the rest.
 */
/*? if fabric {*/
  @Environment(EnvType.SERVER)
  /*?}*/
@Pseudo
@Mixin(ServerLoginPacketListenerImpl.class)
@SuppressWarnings({"mapping", "unresolvable-target"})
abstract class ServerLoginNetworkHandlerMixin {

  @Shadow
  public abstract void disconnect(net.minecraft.network.chat.Component reason);

  @Inject(
      method = "handleHello(Lnet/minecraft/network/protocol/login/ServerboundHelloPacket;)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void authCore$handleHello(ServerboundHelloPacket packet, CallbackInfo ci) {
    String username = readName(packet);
    UUID uuid = readProfileId(packet);

    if (username == null || uuid == null) return;

    // Only intercept clients that use the offline-mode UUID convention
    if (!UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes()).equals(uuid)) return;

    // Detect the real server.properties mode once (the mod always follows the REAL server
    // mode - no config override).
    net.minecraft.server.MinecraftServer loginServer = net.ded3ec.compat.Compat.loginServer(this);
    if (loginServer != null)
      AuthCoreServer.detectServerOnlineMode(
          net.ded3ec.compat.Compat.serverUsesAuthentication(loginServer));

    // Online-mode server: vanilla would reject this offline-UUID client at its own session
    // check ("Failed to verify username!"). Hybrid mode - when offline-mode players are
    // allowed (allow-offline-players, default true), accept them through vanilla's offline
    // accept flow instead (no session check, offline UUID kept); when disallowed, disconnect
    // with a clear message.
    if (AuthCoreServer.isServerOnline()) {
      if (!AuthCoreServer.config.session.authentication.allowOfflinePlayers) {
        this.disconnect(
            net.ded3ec.compat.Compat.text(
                    AuthCoreServer.messages.promptUserOfflinePlayersNotAllowed.logout.text)
                .withStyle(style -> style.withColor(ChatFormatting.RED)));
        ci.cancel();
        return;
      }
      if (net.ded3ec.compat.Compat.loginAcceptOffline(this, username)) {
        AuthCoreServer.LOGGER.debug(
            true,
            "Hybrid mode: offline-mode player \"{}\" accepted on an online-mode server "
                + "(allow-offline-players enabled).",
            username);
        ci.cancel();
      }
      // Fail-safe: when the offline accept flow is unavailable on this version, vanilla
      // handles the connection (the cracked client is rejected by the normal session check).
      return;
    }

    // Bedrock restriction
    if (!AuthCoreServer.config.session.authentication.allowBedrockPlayers
        && McApiManager.isBedrockPlayer(uuid)) {

      this.disconnect(
          net.ded3ec.compat.Compat.text(
                  AuthCoreServer.messages.promptUserBedrockPlayersNotAllowed.logout.text)
              .withStyle(style -> style.withColor(ChatFormatting.RED)));
      ci.cancel();
      return;
    }

    // Floodgate requirement
    if (AuthCoreServer.config.session.authentication.allowBedrockPlayers
        && !net.ded3ec.compat.Compat.isModLoaded("floodgate")) {

      this.disconnect(net.ded3ec.compat.Compat.text("Authentication Failed: Floodgate is not installed!"));
      ci.cancel();
      return;
    }

    AuthCoreServer.LOGGER.debug(
        "Detected offline-mode player \"{}(uuid: {})\" sending hello packet", username, uuid);
  }

  /**
   * Captures the outcome of the server's OWN Mojang session authentication (the profile that
   * passed {@code hasJoinedServer} carries Mojang {@code textures} properties; unverified
   * profiles have none). This is the only premium signal AuthCore uses - no Mojang API calls
   * are made. Only ever fires on online-mode servers (offline servers skip session
   * verification entirely, so no player can be marked premium there).
   *
   * <p>Method names cover 1.16-1.20.1 ({@code verifyLoginAndFinishConnectionSetup}), 1.20.2+
   * ({@code finishLoginAndWaitForClient}) and 26.x (single-arg variant) - whichever exists on
   * the running version matches, the others are skipped (require = 0).
   */
  @Inject(
      method = {
        "verifyLoginAndFinishConnectionSetup(Lcom/mojang/authlib/GameProfile;)V",
        "finishLoginAndWaitForClient(Lcom/mojang/authlib/GameProfile;Ljava/util/concurrent/CompletableFuture;)V",
        "finishLoginAndWaitForClient(Lcom/mojang/authlib/GameProfile;)V"
      },
      at = @At("HEAD"),
      require = 0)
  private void authCore$onMojangVerifiedProfile(com.mojang.authlib.GameProfile profile, CallbackInfo ci) {
    if (profile == null) return;

    // Version-agnostic accessors: getProperties()/getId() on classic authlib, record
    // properties()/id() on 26.x.
    Object props = invoke(profile, "getProperties", "properties");
    if (!(props instanceof java.util.Map<?, ?> map)) return;

    Object textures = map.get("textures");
    boolean hasTextures =
        textures instanceof java.util.Collection<?> collection
            ? !collection.isEmpty()
            : textures != null && !String.valueOf(textures).isEmpty();
    if (!hasTextures) return;

    Object idValue = invoke(profile, "getId", "id");
    if (idValue instanceof java.util.UUID verifiedId)
      AuthCoreServer.markPremiumVerified(verifiedId);
  }

  /** Reads the profile name from the hello packet (version-agnostic). */
  private static String readName(ServerboundHelloPacket packet) {
    Object profile = readProfile(packet);
    if (profile != null) {
      for (String m : new String[] {"name", "getName"}) {
        String value = invokeString(profile, m);
        if (value != null) return value;
      }
    }
    return invokeString(packet, "name");
  }

  /** Reads the profile id from the hello packet (version-agnostic). */
  private static UUID readProfileId(ServerboundHelloPacket packet) {
    Object profile = readProfile(packet);
    if (profile != null) {
      for (String m : new String[] {"id", "getId"}) {
        Object value = invoke(profile, m);
        if (value instanceof UUID u) return u;
      }
    }
    Object value = invoke(packet, "profileId");
    return value instanceof UUID u ? u : null;
  }

  /** Reads the GameProfile from the hello packet (getProfile() on 1.16). */
  private static Object readProfile(ServerboundHelloPacket packet) {
    try {
      return packet.getClass().getMethod("getProfile").invoke(packet);
    } catch (ReflectiveOperationException e) {
      return null;
    }
  }

  private static String invokeString(Object target, String method) {
    Object value = invoke(target, method);
    return value instanceof String s ? s : null;
  }

  private static Object invoke(Object target, String method) {
    try {
      return target.getClass().getMethod(method).invoke(target);
    } catch (ReflectiveOperationException e) {
      return null;
    }
  }

  /** Invokes the first method name that exists on the target (version-agnostic accessors). */
  private static Object invoke(Object target, String... methodNames) {
    for (String name : methodNames) {
      Object value = invoke(target, name);
      if (value != null) return value;
    }
    return null;
  }
}
