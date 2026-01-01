package net.ded3ec.authcore.mixin;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.ded3ec.authcore.AuthCore;
import net.ded3ec.authcore.models.User;
import net.ded3ec.authcore.utils.Misc;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.packet.c2s.login.LoginHelloC2SPacket;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin for {@link ServerLoginNetworkHandler} to handle custom authentication logic during player
 * login. This mixin intercepts the login hello packet to enforce authentication rules, such as
 * offline mode defaults, premium account checks, and Bedrock player restrictions, bypassing vanilla
 * Mojang authentication when necessary.
 */
@Mixin(ServerLoginNetworkHandler.class)
abstract class ServerLoginNetworkHandlerMixin {

  /** Shadows the startVerify method to finalize the login process for offline mode. */
  @Shadow
  abstract void startVerify(GameProfile profile);

  /** Shadows the disconnect method to handle login errors and disconnections. */
  @Shadow
  public abstract void disconnect(Text reason);

  /**
   * Injects into the {@code onHello} method to process the {@link LoginHelloC2SPacket} and perform
   * custom authentication. This method checks for premium accounts, enforces offline mode defaults,
   * restricts Bedrock players if configured, and cancels vanilla authentication to allow custom
   * login handling.
   *
   * @param packet the login hello packet containing the player's username and UUID
   * @param ci the callback info, used to cancel the event and prevent vanilla authentication
   */
  @Inject(method = "onHello", at = @At("HEAD"), cancellable = true)
  private void authCore$onCustomAuthentication(LoginHelloC2SPacket packet, CallbackInfo ci) {
    // Extract data using Record accessors
    String username = packet.name();
    UUID uuid = packet.profileId();

    if (username != null && uuid != null) {

      boolean isPremium = username.equals(Misc.getPremiumUsername(uuid));
      User user = User.getUser(username, uuid);

      if (isPremium && (user == null || user.isPremium)) return;
      else if (!AuthCore.config.session.authentication.offlineModeByDefault) return;
      else if (!AuthCore.config.session.authentication.allowBedrockPlayers
          && Misc.isBedrockPlayer(uuid))
        this.disconnect(
            Text.literal(AuthCore.messages.promptUserBedrockPlayersNotAllowed.logout.text)
                .setStyle(Style.EMPTY.withColor(Formatting.RED)));
    }

    // Stop Vanilla Auth (mojang servers)
    ci.cancel();

    if (username == null) {
      this.disconnect(Text.of("Authentication Failed: AuthCore Rejected You"));
      return;
    }

    if (AuthCore.config.session.authentication.allowBedrockPlayers
        && !FabricLoader.getInstance().isModLoaded("floodgate"))
      this.disconnect(Text.of("Authentication Failed: Floodgate is not Found or installed!"));
    else {

      // Create the GameProfile
      uuid =
          (uuid != null) ? uuid : UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes());

      // Shadowing the 'profile' field so we can set it manually
      GameProfile profile = new GameProfile(uuid, username);

      // Finalize Login (Skip Encryption)
      this.startVerify(profile);
    }
  }
}
