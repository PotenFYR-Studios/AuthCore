package net.ded3ec.mixin;

import com.mojang.authlib.GameProfile;
import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.Lobby;
import net.ded3ec.models.User;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tracks operator removals that happen WHILE the target is locked in the limbo.
 *
 * <p>The limbo itself strips operator status at lock time (lobby.safe-operators), so the
 * unlock-time snapshot restore cannot distinguish "our own temporary strip" from "an admin
 * deliberately deopped this account while it sat in the lobby". Without this tracking, the
 * restore would resurrect op powers over the admin's decision. The marker set is consumed
 * by {@code Lobby.Snapshot.reset}.
 *
 * <p>Method candidates cover both mapping eras ("deop" Mojang names, "removeFromOperators"
 * Yarn); {@code require = 0} keeps every other version booting - a missed injection only
 * degrades to the pre-fix behavior, never breaks startup. Profile accessors are resolved
 * reflectively (getId()/getName() on classic authlib, id()/name() record accessors on 26.x).
 */
/*? if fabric {*/
@net.fabricmc.api.Environment(net.fabricmc.api.EnvType.SERVER)
/*?}*/
@Mixin(PlayerList.class)
@SuppressWarnings({"mapping", "unresolvable-target"})
abstract class PlayerListOpMixin {

  @Inject(
      method = {
        "deop(Lcom/mojang/authlib/GameProfile;)V",
        "removeFromOperators(Lcom/mojang/authlib/GameProfile;)V"
      },
      at = @At("HEAD"),
      require = 0)
  private void authCore$trackDeopWhileInLimbo(GameProfile profile, CallbackInfo ci) {
    if (profile == null) return;

    java.util.UUID uuid = invokeUuid(profile);
    if (uuid == null) return;

    User user = User.getUser(uuid);
    if (user != null
        && user.isInLobby.get()
        && AuthCoreServer.config != null
        && AuthCoreServer.config.lobby.safeOperators) {
      Lobby.ADMIN_DEOPS_DURING_LIMBO.add(uuid);
      String name = invokeString(profile);
      AuthCoreServer.LOGGER.debug(
          false,
          "{} was deopped while locked in the limbo - operator status will not be restored "
              + "on unlock",
          name != null ? name : uuid.toString());
    }
  }

  /** Reads the profile UUID across authlib eras (getId() classic / id() record). */
  private static java.util.UUID invokeUuid(GameProfile profile) {
    for (String m : new String[] {"getId", "id"}) {
      try {
        Object value = profile.getClass().getMethod(m).invoke(profile);
        if (value instanceof java.util.UUID u) return u;
      } catch (ReflectiveOperationException | RuntimeException ignored) {
        // try the next accessor
      }
    }
    return null;
  }

  /** Reads the profile name across authlib eras (getName() classic / name() record). */
  private static String invokeString(GameProfile profile) {
    for (String m : new String[] {"getName", "name"}) {
      try {
        Object value = profile.getClass().getMethod(m).invoke(profile);
        if (value instanceof String s) return s;
      } catch (ReflectiveOperationException | RuntimeException ignored) {
        // try the next accessor
      }
    }
    return null;
  }
}
