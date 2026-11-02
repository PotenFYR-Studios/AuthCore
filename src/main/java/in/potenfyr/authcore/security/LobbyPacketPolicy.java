package in.potenfyr.authcore.security;

import in.potenfyr.authcore.models.Config;

/**
 * Pure decision logic for the lobby packet gate.
 *
 * <p>The mixin that cancels lobby packets stays a thin shell around this class so the matrix of
 * (packet action x lobby configuration) is testable in the in-mod simulation suite without a client,
 * a live server or a packet implementation.
 *
 * <p>Every action is fail-closed: an unknown/unreadable packet action yields {@link Action#UNKNOWN},
 * which is blocked in the lobby (the player can retry after authenticating). Riding on the
 * configuration flags keeps the documented behaviour: with the default all-false lobby config
 * nothing gets through; an operator who explicitly allows item use/moving/crafting gets exactly
 * those actions back.
 */
public final class LobbyPacketPolicy {

  /** Side effects that the lobby gate can cancel before vanilla applies them. */
  public enum Action {
    /** Using a held item (ender pearl/chorus teleport, consumables, firework boost...). */
    ITEM_USE,
    /** Mutating item data (book editing, anvil rename). */
    ITEM_MUTATION,
    /** Creative pick-block (materialises items out of thin air). */
    PICK_ITEM,
    /** Editing a sign block entity (world modification). */
    SIGN_EDIT,
    /** Respawn request (could relocate the player out of the limbo anchor). */
    RESPAWN,
    /** Movement-like packets (gliding start, spectator teleport). */
    MOVEMENT,
    /** Villager trading (item generation). */
    TRADE,
    /** Enchanting/container button actions (item generation). */
    CONTAINER_ACTION,
    /** A packet whose action could not be classified - always blocked in the lobby. */
    UNKNOWN
  }

  private LobbyPacketPolicy() {}

  /** Whether the given action must be cancelled for a lobby (unauthenticated) player. */
  public static boolean blocked(Action action, Config.Lobby lobby) {
    if (action == null || lobby == null) return false;
    return switch (action) {
      case ITEM_USE -> !lobby.allowItemUse;
      case ITEM_MUTATION -> !lobby.allowItemMoving;
      case PICK_ITEM -> !lobby.allowItemMoving;
      case SIGN_EDIT -> !lobby.allowBlockInteraction;
      case RESPAWN -> true;
      case MOVEMENT -> !lobby.allowMovement;
      case TRADE -> !lobby.allowItemUse && !lobby.allowEntityInteractWith;
      case CONTAINER_ACTION -> !lobby.allowItemMoving && !lobby.allowCrafting;
      case UNKNOWN -> true;
    };
  }

  /**
   * Classifies a {@code ServerboundPlayerCommandPacket.Action} name (read as a string so a future
   * enum constant can never break the mapping): the only movement-ish action is starting to glide.
   * Unknown names are not movement and are left to vanilla (sneak/sprint are legitimate and needed
   * by the human-verification captcha).
   */
  public static Action classifyPlayerCommand(String actionName) {
    if (actionName == null || actionName.isBlank()) return Action.UNKNOWN;
    String upper = actionName.toUpperCase(java.util.Locale.ROOT);
    if (upper.contains("FALL_FLYING") || upper.contains("GLIDE")) return Action.MOVEMENT;
    return null; // not a guarded action - vanilla handles it
  }

  /** Log-safe description of an action (used in violation messages). */
  public static String describe(Action action) {
    return action == null ? "none" : action.name().toLowerCase(java.util.Locale.ROOT);
  }
}
