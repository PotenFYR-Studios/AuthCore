package net.ded3ec.events;

import net.ded3ec.models.Config;
import net.ded3ec.models.Messages;
import net.ded3ec.network.EmailSender;
import net.ded3ec.network.McApiManager;
import net.ded3ec.network.RedisManager;
import net.ded3ec.network.Webhook;
import net.ded3ec.security.RateLimiter;
import net.ded3ec.security.Security;
import net.ded3ec.security.SecurityLog;
import net.ded3ec.util.Logger;
import net.ded3ec.util.TaskScheduler;
import net.ded3ec.util.TpsManager;

import java.util.Objects;
import java.util.UUID;

import net.ded3ec.AuthCoreServer;
import net.ded3ec.models.Lobby;
import net.ded3ec.models.User;
import net.ded3ec.util.TimeManager;
import net.ded3ec.util.*;
import net.ded3ec.security.*;
import net.ded3ec.network.*;

import net.fabricmc.fabric.api.networking.v1.PacketSender;


import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

public class ServerEvents {

  /** Player join handler */
  public static void onPlayerJoin(
      ServerGamePacketListenerImpl connection, PacketSender sender, MinecraftServer server) {

    ServerPlayer player = connection.player;

    // Maintenance mode: block all joins
    if (AuthCoreServer.config.session.maintenance.enabled) {
      connection.disconnect(
          net.ded3ec.compat.Compat.text(AuthCoreServer.config.session.maintenance.kickMessage));
      return;
    }

    UUID uuid = player.getUUID();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    // Duplicate login handling
    if (user != null && user.isActive) {

      if (user.isInLobby.get()
          && AuthCoreServer.config.session.authentication.blockDuplicateRegister) {
        AuthCoreServer.LOGGER.toKick(
            false, connection, AuthCoreServer.messages.promptUserAnotherAccountIsRegistering);
        return;
      }

      if (user.isAuthenticated.get()
          && AuthCoreServer.config.session.authentication.blockDuplicateSession) {
        AuthCoreServer.LOGGER.toKick(
            false, connection, AuthCoreServer.messages.promptUserAnotherAccountSession);
        return;
      }

    } else if (user == null)
      user =
          new User(
              uuid,
              username,
              System.currentTimeMillis(),
              (Objects.equals(AuthCoreServer.config.session.serverMode, "online"))
                  || username.equals(McApiManager.getPremiumUsername(uuid)));

    // Per-IP join rate limit (bot flood protection)
    if (AuthCoreServer.config.session.rateLimit.enabled
        && !McApiManager.isPrivateAddress(player.getIpAddress())
        && !RateLimiter.tryAcquire(
            player.getIpAddress(),
            AuthCoreServer.config.session.rateLimit.maxJoinsPerWindow,
            AuthCoreServer.config.session.rateLimit.windowMs)) {

      String reason = AuthCoreServer.config.session.rateLimit.overLimitMessage;
      net.ded3ec.security.SecurityLog.log("JOIN_RATE_LIMITED", player.getIpAddress() + " | " + username);
      AuthCoreServer.LOGGER.toKick(
          false,
          connection,
          new net.ded3ec.models.Messages.KickTemplate() {
            {
              logout.text = reason;
            }
          });
      return;
    }

    // Remote (Redis) duplicate session check for server networks
    if (user.isRegistered.get() && net.ded3ec.network.RedisManager.hasRemoteSession(uuid)) {
      AuthCoreServer.LOGGER.toKick(
          false, connection, AuthCoreServer.messages.promptUserAnotherAccountSession);
      return;
    }

    // Persistent account lock check
    if (AuthCoreServer.config.session.accountLock.enabled && user.isLocked()) {
      long remainingMs = user.lockUntilMs - System.currentTimeMillis();
      AuthCoreServer.LOGGER.toUser(
          false,
          connection,
          AuthCoreServer.messages.promptUserAccountLocked,
          net.ded3ec.util.TimeManager.toDuration(Math.max(remainingMs, 1000)));
      AuthCoreServer.LOGGER.toKick(
          false, connection, AuthCoreServer.messages.promptUserAuthenticationExpiredTimeout,
          net.ded3ec.util.TimeManager.toDuration(Math.max(remainingMs, 1000)));
      return;
    }

    // Establish connection
    user.connect(connection);

    // Per-IP allow/deny rules (ip-rules.conf)
    if (net.ded3ec.security.IpRules.isDenied(player.getIpAddress())) {
      SecurityLog.log("IP_DENIED", player.getIpAddress() + " | " + username + " blocked by ip-rules.conf");
      AuthCoreServer.LOGGER.toKick(
          false,
          connection,
          AuthCoreServer.messages.promptUserProxyNotAllowed);
      return;
    }

    // Fast-rejoin alert (bot pattern: disconnect + reconnect within milliseconds)
    if (AuthCoreServer.config.session.rateLimit.alertOnFastRejoin
        && !net.ded3ec.security.RateLimiter.tryAcquire(
            "rejoin:" + player.getIpAddress(),
            1,
            AuthCoreServer.config.session.rateLimit.fastRejoinWindowMs)) {
      SecurityLog.log("FAST_REJOIN", player.getIpAddress() + " | " + username + " rejoined too fast");
      Webhook.sendEmbed(
          "Fast Rejoin Detected",
          "**" + username + "** reconnected from `" + player.getIpAddress() + "` within "
              + AuthCoreServer.config.session.rateLimit.fastRejoinWindowMs + " ms (bot pattern).",
          0xF1C40F);
    }

    // Proxy restriction (GeoIP lookups are cached, so this is one request per unique IP per day)
    if (!AuthCoreServer.config.session.authentication.allowProxyUsers) {
      com.google.gson.JsonObject geo = McApiManager.geoIp(player.getIpAddress());
      if (geo != null
          && geo.has("status")
          && "success".equalsIgnoreCase(geo.get("status").getAsString()))
        user.geoIpData = geo;

      if (user.isProxy.get()) {
        AuthCoreServer.LOGGER.toKick(
            false, connection, AuthCoreServer.messages.promptUserProxyNotAllowed);
        return;
      }
    }

    // Same-IP restriction (empty/legacy IPs are never considered a mismatch)
    if (AuthCoreServer.config.session.sessionFromSameIPOnly
        && user.isRegistered.get()
        && org.apache.commons.lang3.StringUtils.isNotBlank(user.ipAddress)
        && !user.ipAddress.equals(player.getIpAddress())) {

      AuthCoreServer.LOGGER.toKick(
          false, connection, AuthCoreServer.messages.promptUserDifferentIpLoginNotAllowed);
      return;
    }

    // Premium-name restriction (username squatting protection).
    // Only enforced when the Mojang API is confirmed healthy - a transient API failure must
    // never block legitimate premium players (the reported "blocks me as not online-mode" bug).
    if (!AuthCoreServer.config.session.authentication.allowOnlineNameByOffline
        && McApiManager.isPremiumApiHealthy()
        && McApiManager.getPremiumUuid(user.username) != null
        && McApiManager.getPremiumUsername(uuid) == null) {

      user.kick(AuthCoreServer.messages.promptUserPremiumNameNotAllowed);
      return;
    }

    if (!McApiManager.isPremiumApiHealthy())
      McApiManager.warnApiUnavailable();

    // Resume active session
    if (user.uuid.equals(uuid)
        && org.apache.commons.lang3.StringUtils.isNotBlank(user.ipAddress)
        && user.ipAddress.equals(player.getIpAddress())
        && user.isActiveSession.get()) {

      AuthCoreServer.LOGGER.debug(
          true, "{} skipped authentication and resumed session!", user.username);

      AuthCoreServer.LOGGER.toUser(
          true, connection, AuthCoreServer.messages.promptUserSessionResumed);

      user.login(player);
      return;
    }

    // Premium auto-login
    if (user.uuid.equals(uuid)
        && AuthCoreServer.config.session.authentication.premiumAutoLogin
        && user.isPremium) {

      AuthCoreServer.LOGGER.debug(true, "{} is premium and skipped authentication!", user.username);

      AuthCoreServer.LOGGER.toUser(
          true, connection, AuthCoreServer.messages.promptUserPremiumAutoLogin);

      if (AuthCoreServer.config.session.authentication.premiumAutoRegister
          && !user.isRegistered.get()) user.register(player, Security.Password.generate(20));
      else if (user.isRegistered.get()) user.login(player);
      else user.lobby.lock();

      return;
    }

    // Premium UUID mismatch
    if (!user.uuid.equals(uuid)
        && user.isPremium
        && AuthCoreServer.config.session.authentication.premiumAutoLogin) {

      user.kick(AuthCoreServer.messages.promptUserPremiumDifferentUUID);
      return;
    }

    // Login intelligence: risk score + new IP/country detection (only for returning users)
    if (user.isRegistered.get()
        && AuthCoreServer.config.session.intelligence.enabled
        && !McApiManager.isPrivateAddress(player.getIpAddress())) {

      com.google.gson.JsonObject geo = user.geoIpData;
      String country = user.country.get();
      if (geo == null && country == null) {
        geo = McApiManager.geoIp(player.getIpAddress());
        if (geo != null
            && geo.has("status")
            && "success".equalsIgnoreCase(geo.get("status").getAsString()))
          user.geoIpData = geo;
        country = user.country.get();
      }

      user.riskScore = Security.RiskScore.compute(user, player.getIpAddress(), country);

      boolean newIp =
          user.lastLoginIp != null && !user.lastLoginIp.isEmpty() && !user.lastLoginIp.equals(player.getIpAddress());
      boolean newCountry =
          country != null
              && user.lastLoginCountry != null
              && !user.lastLoginCountry.isEmpty()
              && !user.lastLoginCountry.equalsIgnoreCase(country);

      if (newIp && AuthCoreServer.config.session.intelligence.alertOnNewIp) {
        SecurityLog.log(
            "NEW_IP",
            username + " logged in from a new IP: " + player.getIpAddress() + " (was " + user.lastLoginIp + ")");
        Webhook.sendEmbed(
            "New IP Login",
            "**" + username + "** logged in from a new IP `" + player.getIpAddress() + "` (was `" + user.lastLoginIp + "`)",
            0xF1C40F);
      }

      if (newCountry && AuthCoreServer.config.session.intelligence.alertOnNewCountry) {
        SecurityLog.log(
            "NEW_COUNTRY",
            username + " logged in from a new country: " + country + " (was " + user.lastLoginCountry + ")");
        Webhook.sendEmbed(
            "New Country Login (possible account sharing)",
            "**" + username + "** logged in from **" + country + "** (was " + user.lastLoginCountry + ")",
            0xE67E22);
      }

      if (newCountry && AuthCoreServer.config.session.intelligence.blockOnNewCountry) {
        SecurityLog.log("BLOCKED_NEW_COUNTRY", username + " blocked from " + country);
        if (AuthCoreServer.config.session.shadowBan.enabled) {
          // Shadow-ban: generic disconnect instead of a security message
          connection.disconnect(
              net.ded3ec.compat.Compat.text(AuthCoreServer.config.session.shadowBan.disconnectReason));
          Webhook.sendEmbed(
              "Shadow-Banned Login",
              "**" + username + "** was silently blocked from **" + country + "**.",
              0xE74C3C);
          return;
        }
        user.kick(AuthCoreServer.messages.promptUserDifferentIpLoginNotAllowed);
        return;
      }

      // Network-based device fingerprint (anti account-sharing / anti account-trading)
      String fingerprint = net.ded3ec.security.Security.DeviceFingerprint.compute(player.getIpAddress(), country);
      if (user.deviceFingerprint != null
          && !user.deviceFingerprint.isEmpty()
          && !user.deviceFingerprint.equals(fingerprint)) {
        user.riskScore = Math.min(100, user.riskScore + 15);
        SecurityLog.log(
            "DEVICE_CHANGED",
            username + " logged in from a different network fingerprint (was "
                + user.deviceFingerprint + ", now " + fingerprint + ")");
        Webhook.sendEmbed(
            "Possible Account Sharing",
            "**" + username + "** logged in from a different network/device fingerprint.",
            0xE74C3C);
      }
      user.deviceFingerprint = fingerprint;

      // Email login alert (SMTP) for new IP / new country logins
      if ((newIp || newCountry) && EmailSender.isEnabled() && user.email != null) {
        EmailSender.sendAsync(
            user.email,
            "AuthCore - New login detected",
            "Hello " + username + ",\n\n"
                + "Your account was just used from:\n"
                + "  IP: " + player.getIpAddress() + "\n"
                + "  Country: " + (country != null ? country : "unknown") + "\n\n"
                + "If this was you, no action is needed. If not, contact the server staff "
                + "immediately - your account may have been compromised.");
        SecurityLog.log("EMAIL_ALERT", "login alert sent to " + user.email + " for " + username);
      }

      // Remember the login origin for the next intelligence pass
      user.lastLoginIp = player.getIpAddress();
      if (country != null) user.lastLoginCountry = country;
      user.update("Login intelligence");
    }

    // Kick cooldown
    if (user.lastKickedMs > 0
        && AuthCoreServer.config.session.cooldownAfterKickMs
            > (System.currentTimeMillis() - user.lastKickedMs)) {

      user.kick(
          AuthCoreServer.messages.promptUserCooldownAfterKickNotExpired,
          TimeManager.toDuration(
              (System.currentTimeMillis() - user.lastKickedMs)
                  - AuthCoreServer.config.session.cooldownAfterKickMs));
      return;
    }

    // Lobby full
    if (AuthCoreServer.config.lobby.maxLobbyUsers > 0
        && AuthCoreServer.config.lobby.maxLobbyUsers <= Lobby.users.size()) {

      user.kick(AuthCoreServer.messages.promptUserMaxLobbyUsersReached);
      return;
    }

    // Default: lock user in lobby
    user.lobby.lock();
  }

  /** Player leave handler */
  public static void onPlayerLeave(ServerGamePacketListenerImpl connection, MinecraftServer server) {

    ServerPlayer player = connection.player;

    UUID uuid = player.getUUID();
    String username = player.getName().getString();
    User user = User.getUser(username, uuid);

    if (user != null) {

      if (user.isInLobby.get()) user.lobby.unlock();

      user.isActive = false;

      user.isInCombatPenalty =
          user.lastCombatDetectMs > 0
              && AuthCoreServer.config.session.combatTimeout > 0
              && ((System.currentTimeMillis() - user.lastCombatDetectMs)
                  < AuthCoreServer.config.session.combatTimeout);

      // Combat-log punishment: kill players who disconnect while still in combat
      if (user.isInCombatPenalty && AuthCoreServer.config.session.combatLog.enabled) {
        AuthCoreServer.LOGGER.debug(
            true, "{} attempted to skip death penalty!", user.username);
        SecurityLog.log("COMBAT_LOGOUT", user.username + " disconnected during combat");

        if (AuthCoreServer.config.session.combatLog.killOnDisconnect) {
          try {
            net.ded3ec.compat.Compat.kill(player);
            AuthCoreServer.LOGGER.info(
                true,
                "{} was killed for combat-logging (disconnect during combat).",
                user.username);
            Webhook.send(
                ":skull: **" + user.username + "** was killed for combat-logging.");
          } catch (Exception err) {
            AuthCoreServer.LOGGER.debug(false, "Failed to kill combat-logger:", err);
          }
        }
      }

      user.update("Player Leave Cache");
    }
  }

  /** Server tick handler */
  public static void onEndServerTick(MinecraftServer server) {
    TpsManager.onTick();
    TaskScheduler.getInstance().onTick();

    // Limbo re-assert guards (fallbacks for environments where mixins cannot apply):
    // - re-apply the lobby blindness/invisibility effects if a lobby player lost them
    // - teleport lobby players back when movement is disabled and they drifted
    if (AuthCoreServer.config != null && !Lobby.users.isEmpty()) {
      boolean invisible = AuthCoreServer.config.lobby.invisibleUnauthorized;
      boolean blind = AuthCoreServer.config.lobby.applyBlindnessEffect;
      boolean noMove = !AuthCoreServer.config.lobby.allowMovement;

      if (invisible || blind || noMove)
        for (Lobby lobby : new java.util.ArrayList<>(Lobby.users.values())) {
          User u = lobby.user;
          if (u == null || !u.isActive || u.player.get() == null) continue;
          ServerPlayer p = u.player.get();

          if (invisible && !p.isInvisible())
            net.ded3ec.compat.Compat.addStatusEffect(
                p,
                new net.minecraft.world.effect.MobEffectInstance(
                    net.minecraft.world.effect.MobEffects.INVISIBILITY,
                    Integer.MAX_VALUE,
                    1,
                    false,
                    false));
          if (blind && p.getActiveEffects().stream().noneMatch(
              e -> e.getEffect() == net.minecraft.world.effect.MobEffects.BLINDNESS))
            net.ded3ec.compat.Compat.addStatusEffect(
                p,
                new net.minecraft.world.effect.MobEffectInstance(
                    net.minecraft.world.effect.MobEffects.BLINDNESS,
                    Integer.MAX_VALUE,
                    1,
                    false,
                    false));

          if (noMove && lobby.isOutsideOfLobbyPos(p.getX(), p.getZ())) lobby.handleTeleport();
        }
    }
  }
}
