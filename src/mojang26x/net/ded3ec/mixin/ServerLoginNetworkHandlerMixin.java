package net.ded3ec.mixin;


import java.util.Objects;
import java.util.UUID;

import net.ded3ec.AuthCoreServer;
import net.ded3ec.network.McApiManager;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
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
@Environment(EnvType.SERVER)
@Mixin(ServerLoginPacketListenerImpl.class)
abstract class ServerLoginNetworkHandlerMixin {

  @Shadow
  public abstract void disconnect(net.minecraft.network.chat.Component reason);

  @Inject(method = "handleHello", at = @At("HEAD"), cancellable = true)
  private void authCore$handleHello(ServerboundHelloPacket packet, CallbackInfo ci) {
    String username = readName(packet);
    UUID uuid = readProfileId(packet);

    if (username == null || uuid == null) return;

    // Only intercept clients that use the offline-mode UUID convention
    if (!UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes()).equals(uuid)) return;

    // Offline-mode handling disabled in the config -> let vanilla handle it
    if (!(Objects.equals(AuthCoreServer.config.session.serverMode, "offline"))) return;

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
        && !FabricLoader.getInstance().isModLoaded("floodgate")) {

      this.disconnect(net.ded3ec.compat.Compat.text("Authentication Failed: Floodgate is not installed!"));
      ci.cancel();
      return;
    }

    AuthCoreServer.LOGGER.debug(
        "Detected offline-mode player \"{}(uuid: {})\" sending hello packet", username, uuid);
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
}
