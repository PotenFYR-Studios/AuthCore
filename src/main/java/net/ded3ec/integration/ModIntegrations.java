package net.ded3ec.integration;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.ded3ec.AuthCoreServer;
import net.ded3ec.compat.Compat;
import net.minecraft.server.level.ServerPlayer;

/**
 * Optional runtime integrations with OTHER server mods (DiscordSRV, InteractiveChat, ...).
 *
 * <p>Every integration is best-effort and reflection-based - exactly like the Floodgate and
 * LuckPerms hooks. When the mod is not installed (or its API changed), the integration is
 * skipped silently and AuthCore keeps working. Nothing here is ever allowed to throw into the
 * gameplay path.
 *
 * <p>Compatibility contract: AuthCore's restrictions (chat, commands, movement, inventory)
 * only ever apply to players inside the auth lobby - for authenticated players AuthCore does
 * not touch chat, packets or gameplay, so other mods are unaffected.
 */
public final class ModIntegrations {

  private ModIntegrations() {}

  // ------------------------------------------------------------------ detection

  /** DiscordSRV account-link API shapes tried in order (best-effort, cached once). */
  private static volatile Object discordSrvAccountLinkManager = null;
  private static volatile boolean discordSrvResolved = false;

  /** Whether the DiscordSRV mod/plugin is loaded on this server. */
  public static boolean isDiscordSrvLoaded() {
    return Compat.isModLoaded("discordsrv");
  }

  /** Whether InteractiveChat (or the rec fork) is loaded on this server. */
  public static boolean isInteractiveChatLoaded() {
    return Compat.isModLoaded("interactivechat") || Compat.isModLoaded("interactivechatrec");
  }

  /** Human-readable status of every optional integration (for /authcore compat). */
  public static String status() {
    StringBuilder sb = new StringBuilder();
    sb.append("DiscordSRV    : ").append(isDiscordSrvLoaded() ? "detected" : "not installed")
        .append(isDiscordSrvLoaded() && resolveAccountLinkManager() != null ? " (link sync active)" : "")
        .append('\n');
    sb.append("InteractiveChat: ").append(isInteractiveChatLoaded() ? "detected (compatible)" : "not installed")
        .append('\n');
    return sb.toString();
  }

  // ------------------------------------------------------------ DiscordSRV sync

  /**
   * Resolves the DiscordSRV {@code AccountLinkManager} reflectively (API shapes changed
   * across DiscordSRV versions). Returns {@code null} when unavailable.
   */
  private static Object resolveAccountLinkManager() {
    if (discordSrvResolved) return discordSrvAccountLinkManager;
    discordSrvResolved = true;

    if (!isDiscordSrvLoaded()) return null;

    try {
      // DiscordSRV 1.26+: github.discordsrv.discordsrv.api.DiscordSRVApi.instance()
      Class<?> apiClass = Class.forName("github.discordsrv.discordsrv.api.DiscordSRVApi");
      Object api = apiClass.getMethod("instance").invoke(null);
      if (api == null) return null;
      for (String m : new String[] {"getAccountLinkManager", "getAccountLinkManager"}) {
        try {
          Object manager = api.getClass().getMethod(m).invoke(api);
          if (manager != null) {
            discordSrvAccountLinkManager = manager;
            AuthCoreServer.LOGGER.info(
                true, "DiscordSRV integration: account-link sync is active.");
            return manager;
          }
        } catch (ReflectiveOperationException ignored) {
          // try the next shape
        }
      }
    } catch (ReflectiveOperationException | RuntimeException ignored) {
      // DiscordSRV API not available on this version - integration stays off
    }
    return null;
  }

  /**
   * Reads the Discord user id DiscordSRV has linked to this player (best-effort; {@code null}
   * when DiscordSRV is absent, the player is not linked or the API is unreachable).
   *
   * @param uuid the player's UUID
   * @return the Discord snowflake as a string, or {@code null}
   */
  public static String linkedDiscordId(UUID uuid) {
    Object manager = resolveAccountLinkManager();
    if (manager == null || uuid == null) return null;
    try {
      for (String m :
          new String[] {"getDiscordIdForPlayer", "getDiscordId", "getDiscordIdForPlayer"}) {
        try {
          Object id = manager.getClass().getMethod(m, UUID.class).invoke(manager, uuid);
          if (id != null) return String.valueOf(id);
        } catch (ReflectiveOperationException ignored) {
          // try the next shape
        }
      }
    } catch (RuntimeException ignored) {
      // DiscordSRV not ready - ignore
    }
    return null;
  }

  /** Per-player cache of imported Discord ids (avoid repeated reflection/DB calls). */
  private static final ConcurrentHashMap<UUID, String> DISCORD_ID_CACHE = new ConcurrentHashMap<>();

  /**
   * Import hook called after a successful authentication: if DiscordSRV has a linked Discord
   * account for this player, it is stored on the AuthCore user so notifications and webhooks
   * can address the Discord identity (idempotent, cached, never blocks).
   *
   * @param player the authenticated player
   * @param user the AuthCore user
   */
  public static void onAuthSuccess(ServerPlayer player, net.ded3ec.models.User user) {
    if (player == null || user == null) return;
    if (!isDiscordSrvLoaded()) return;

    UUID uuid = player.getUUID();
    String cached = DISCORD_ID_CACHE.get(uuid);
    if (cached != null) {
      if (user.discordId == null || user.discordId.isEmpty())
        user.discordId = cached;
      return;
    }

    String discordId = linkedDiscordId(uuid);
    if (discordId == null || discordId.isBlank()) return;

    DISCORD_ID_CACHE.put(uuid, discordId);
    if (user.discordId == null || user.discordId.isEmpty()) {
      user.discordId = discordId;
      user.update("DiscordSRV link imported");
      AuthCoreServer.LOGGER.debug(
          true, "Imported DiscordSRV link for {} ({}).", user.username, discordId);
    }
  }

  /** Clears the per-player Discord-id cache on leave (memory hygiene). */
  public static void onLeave(UUID uuid) {
    if (uuid != null) DISCORD_ID_CACHE.remove(uuid);
  }
}
