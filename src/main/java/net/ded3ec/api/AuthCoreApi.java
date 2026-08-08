package net.ded3ec.api;

import net.ded3ec.models.Config;
import net.ded3ec.models.Lobby;
import net.ded3ec.models.Messages;
import net.ded3ec.util.Database;

import java.util.UUID;
import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.User;
import net.ded3ec.security.Encrypter;
import net.ded3ec.network.McApiManager;
import net.ded3ec.security.Security;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.Nullable;

/**
 * Public API for other mods and server scripts to integrate with AuthCore.
 *
 * <p>All methods are safe to call from the server thread. Use {@link #isEnabled()} to verify the
 * mod and its configuration are fully initialized.
 *
 * <pre>{@code
 * if (AuthCoreApi.isEnabled() && AuthCoreApi.isRegistered(uuid)) {
 *     AuthCoreApi.login(uuid, "password123");
 * }
 * }</pre>
 */
public final class AuthCoreApi {

  private AuthCoreApi() {}

  /** Whether AuthCore finished initializing (config + database loaded). */
  public static boolean isEnabled() {
    return AuthCoreServer.config != null && AuthCoreServer.messages != null;
  }

  /** Returns the cached {@link User} for a UUID, or {@code null}. */
  public static @Nullable User getUser(UUID uuid) {
    return uuid == null ? null : User.users.get(uuid);
  }

  /** Returns the cached {@link User} for a username (case-insensitive), or {@code null}. */
  public static @Nullable User getUser(String username) {
    return User.getUserByUsername(username);
  }

  /** Whether the account exists in the cache (registered or merely seen). */
  public static boolean exists(UUID uuid) {
    return getUser(uuid) != null;
  }

  /** Whether the account has a password set (is registered). */
  public static boolean isRegistered(UUID uuid) {
    User user = getUser(uuid);
    return user != null && user.isRegistered.get();
  }

  /** Whether the player is currently authenticated on this server. */
  public static boolean isAuthenticated(UUID uuid) {
    User user = getUser(uuid);
    return user != null && user.isAuthenticated.get();
  }

  /** Whether the player is currently restricted in the auth lobby (limbo). */
  public static boolean isInLobby(UUID uuid) {
    User user = getUser(uuid);
    return user != null && user.isInLobby.get();
  }

  /** Whether the account is locked after too many failed logins. */
  public static boolean isLocked(UUID uuid) {
    User user = getUser(uuid);
    return user != null && user.isLocked();
  }

  /** The account's last computed login risk score (0-100). */
  public static int getRiskScore(UUID uuid) {
    User user = getUser(uuid);
    return user == null ? 100 : user.riskScore;
  }

  /** Whether the player's UUID belongs to a premium (online-mode) account. */
  public static boolean isPremium(UUID uuid) {
    User user = getUser(uuid);
    return user != null && user.isPremium;
  }

  /**
   * Verifies a password against the stored hash.
   *
   * @return {@code true} if the password matches
   */
  public static boolean verifyPassword(UUID uuid, String password) {
    User user = getUser(uuid);
    return user != null
        && user.isRegistered.get()
        && Encrypter.verify(password, user.password, user.passwordEncryption);
  }

  /**
   * Registers a new account with the given password. If the account already exists, this is a
   * no-op that returns {@code false}.
   *
   * @return {@code true} if the account was created
   */
  public static boolean register(UUID uuid, String username, String password) {
    if (getUser(uuid) != null) return false;

    User user = new User(uuid, username, System.currentTimeMillis(), false);
    user.register(null, password);
    return user.password != null;
  }

  /**
   * Authenticates the account in memory (without checking the password). Equivalent to a
   * successful login.
   *
   * @return {@code true} if the account was found
   */
  public static boolean login(UUID uuid) {
    User user = getUser(uuid);
    if (user == null) return false;
    user.login(playerOf(user));
    return true;
  }

  /** Ends the active session of the account. */
  public static boolean logout(UUID uuid) {
    User user = getUser(uuid);
    if (user == null) return false;
    user.logout(AuthCoreServer.messages.promptUserSessionExpired);
    return true;
  }

  /** Kicks the player from the server with a custom reason. */
  public static boolean kickPlayer(UUID uuid, String reason) {
    User user = getUser(uuid);
    if (user == null || !user.isActive || user.connection == null) return false;

    user.connection.disconnect(net.ded3ec.compat.Compat.text(reason));
    return true;
  }

  /** Sends a chat message to the player. */
  public static boolean sendMessage(UUID uuid, String message) {
    User user = getUser(uuid);
    if (user == null || user.connection == null || user.connection.player == null) return false;

    net.ded3ec.compat.Compat.sendMessage(user.connection.player, net.ded3ec.compat.Compat.text(message), false);
    return true;
  }

  /** Deletes the account from the database and cache. */
  public static boolean deleteAccount(UUID uuid) {
    User user = getUser(uuid);
    if (user == null) return false;
    user.delete("Deleted via AuthCoreApi", true);
    return true;
  }

  /** Fetches the online player entity for the user, if present. */
  private static @Nullable ServerPlayerEntity playerOf(User user) {
    try {
      return user.player.get();
    } catch (Exception err) {
      return null;
    }
  }
}
