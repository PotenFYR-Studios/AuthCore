package in.potenfyr.authcore.util;

import in.potenfyr.authcore.AuthCoreServer;
import in.potenfyr.authcore.models.Config;
import in.potenfyr.authcore.security.RateLimiter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Gives map images to players and reliably takes them back.
 *
 * <p>Lifecycle guarantees (all of them are simulated by {@code /authcore simulate} with a dummy
 * inventory, so they are verified on every Minecraft version/loader):
 * <ul>
 *   <li>{@link #show} replaces any previous display, stores the player's selected-slot item and
 *       persists the temporary map id so a server crash can be recovered.
 *   <li>{@link #clear} removes the map from <b>any</b> inventory slot (offhand included), and
 *       restores the saved item into the slot the map just left - a full inventory can therefore
 *       never swallow it. The item is only restored when the map was still present, so a death or
 *       respawn in between can never duplicate it.
 *   <li>{@link #onPlayerLeave} clears the display before the player data is saved, so a normal
 *       disconnect never leaves the temporary map behind.
 *   <li>{@link #onPlayerJoin} removes leftover temporary maps after a hard crash / mid-display
 *       disconnect (ids are persisted to {@code config/authcore/map-images.txt}).
 * </ul>
 *
 * <p>On the 1.19-1.21 jar the data-component map API only exists from 1.20.5; older endpoints of
 * that range (1.19.4) report {@link #isSupported()} false and the feature stays disabled instead of
 * crashing.
 */
public final class MapImageService {

  private record Display(int mapId, ItemStack previousItem, int slot) {}

  /**
   * Minimal slot view so the give/take lifecycle can be simulated without a live client.
   *
   * <p>Production uses the real {@link net.minecraft.world.entity.player.Inventory} plus a virtual
   * offhand slot ({@code size() - 1}); the self-test uses a plain array.
   */
  interface Slots {
    int size();

    ItemStack get(int slot);

    void set(int slot, ItemStack stack);

    /** The hotbar slot the map is displayed in. */
    int selectedSlot();
  }

  private static final Map<UUID, Display> DISPLAYS = new ConcurrentHashMap<>();
  private static final Set<Integer> TEMP_IDS = ConcurrentHashMap.newKeySet();
  private static final int MAX_TEMP_IDS = 4096;
  private static volatile boolean idsLoaded;
  private static volatile boolean unsupportedWarned;

  private MapImageService() {}

  /** Whether the runtime supports the server-side map-data API for this build. */
  public static boolean isSupported() {
    if (!MapImageRenderer.MODERN_MAP_API) return true; // 1.16-1.18 NBT path
    // MapItem.create/MapId only share one signature from 1.21.2 onward; older endpoints of the
    // 1.19-1.21 range keep the feature disabled gracefully instead of crashing.
    return versionAtLeast("1.21.2");
  }

  /** Whether the feature is enabled in the config and supported on this runtime. */
  public static boolean isEnabled() {
    Config config = AuthCoreServer.config;
    return config != null
        && config.lobby != null
        && config.lobby.mapImage != null
        && config.lobby.mapImage.enabled
        && isSupported();
  }

  /**
   * Shows a 128x128 RGB image on a map in the player's selected slot.
   *
   * @return true when the map was created and given to the player
   */
  public static boolean show(ServerPlayer player, int[] image) {
    if (player == null) return false;
    try {
      return showInto(
          player.getUUID(),
          slotsOf(player),
          in.potenfyr.authcore.compat.Compat.playerLevel(player),
          image);
    } catch (Throwable err) {
      AuthCoreServer.LOGGER.debug(false, "Failed to show a map image:", err);
      return false;
    }
  }

  /** Takes the map image back and restores the player's previous selected-slot item. */
  public static boolean clear(ServerPlayer player) {
    if (player == null) return false;
    try {
      return clearInto(player.getUUID(), slotsOf(player));
    } catch (Throwable err) {
      AuthCoreServer.LOGGER.debug(false, "Failed to clear a map image:", err);
      return false;
    }
  }

  /**
   * Shows a scannable QR code for {@code text} on a map.
   *
   * <p>The payload is treated as sensitive: it is never written to the log, only the failure reason
   * is. Length/size limits come from {@link QrCode} / {@link MapImageContent} and are enforced
   * before anything is allocated for the map.
   */
  public static boolean showQr(ServerPlayer player, String text) {
    if (player == null || text == null) return false;
    // Cheap gates first: never spend CPU encoding a code that could not be displayed anyway.
    if (!isEnabled()) return false;
    try {
      return show(player, MapImageContent.qr(text, QrCode.ECC_MEDIUM));
    } catch (IllegalArgumentException rejected) {
      // Never log the payload itself - it may be a 2FA enrolment secret.
      AuthCoreServer.LOGGER.warn(false, "Map QR code rejected: " + rejected.getMessage());
      return false;
    }
  }

  /**
   * Shows a fresh map captcha (a random code rendered as glyphs) and tracks its challenge.
   *
   * <p>The code is never logged or stored in clear text; verification goes through
   * {@link #verifyCaptcha}.
   */
  public static boolean showCaptcha(ServerPlayer player) {
    if (player == null) return false;
    if (!isEnabled()) return false;
    String code = MapImageCaptcha.newCode();
    try {
      if (!show(player, MapImageCaptcha.image(code))) return false;
    } catch (IllegalArgumentException rejected) {
      return false;
    }
    MapImageCaptcha.track(player.getUUID(), code);
    return true;
  }

  /** Verifies a player's answer to their pending map captcha (single-use, bounded attempts). */
  public static boolean verifyCaptcha(ServerPlayer player, String answer) {
    return player != null && MapImageCaptcha.verify(player.getUUID(), answer);
  }

  /** Disconnect hook: clear before the player data is saved. */
  public static void onPlayerLeave(ServerPlayer player) {
    if (player == null) return;
    clear(player);
  }

  /** Join hook: remove temporary maps left behind by a crash / mid-display disconnect. */
  public static void onPlayerJoin(ServerPlayer player) {
    if (player == null || !isEnabled()) return;
    try {
      recoverInto(player.getUUID(), slotsOf(player));
    } catch (Throwable err) {
      AuthCoreServer.LOGGER.debug(false, "Failed to recover leftover map images:", err);
    }
  }

  /** Number of persisted temporary map ids (self-test diagnostics). */
  public static int pendingIdCount() {
    return TEMP_IDS.size();
  }

  // ------------------------------------------------------------------ lifecycle core

  /** Give-lifecycle core (player-independent, simulated by the self-test). */
  static boolean showInto(UUID playerId, Slots slots, ServerLevel level, int[] image) {
    if (playerId == null || slots == null || level == null || image == null) return false;
    if (!isSupported()) {
      if (!unsupportedWarned) {
        unsupportedWarned = true;
        AuthCoreServer.LOGGER.warn(
            false,
            "Map images are unavailable on this Minecraft version (the 1.19-1.21 jar needs "
                + "1.20.5+ for the data-component map API) - the feature stays disabled.");
      }
      return false;
    }
    if (!isEnabled()) return false;
    Config.Lobby.MapImage config = AuthCoreServer.config.lobby.mapImage;
    if (!RateLimiter.tryAcquire(
        "mapimg:" + playerId, Math.max(1, config.maxPerMinute), 60_000L)) {
      return false;
    }
    try {
      // Replacing a previous display must first give back what it replaced.
      clearInto(playerId, slots);
      byte[] colors = MapImageRenderer.render(image);
      ItemStack stack = MapImageMapFactory.createMap(level, colors);
      if (stack == null) return false;
      int mapId = MapImageMapFactory.mapId(stack);
      if (mapId < 0) return false;
      int slot = clamp(slots.selectedSlot(), slots.size());
      ItemStack previous = slots.get(slot);
      slots.set(slot, stack);
      DISPLAYS.put(playerId, new Display(mapId, previous, slot));
      rememberId(mapId);
      return true;
    } catch (Throwable err) {
      AuthCoreServer.LOGGER.debug(false, "Failed to show a map image:", err);
      return false;
    }
  }

  /** Take-back core: removes the map from every slot (offhand included) and restores the item. */
  static boolean clearInto(UUID playerId, Slots slots) {
    if (playerId == null || slots == null) return false;
    Display display = DISPLAYS.remove(playerId);
    if (display == null) return false;
    try {
      int size = Math.max(0, slots.size());
      int firstFreed = -1;
      boolean mapStillPresent = false;
      for (int i = 0; i < size; i++) {
        ItemStack stack = slots.get(i);
        if (stack == null || stack.isEmpty()) continue;
        if (MapImageMapFactory.mapId(stack) == display.mapId) {
          slots.set(i, ItemStack.EMPTY);
          if (firstFreed < 0) firstFreed = i;
          mapStillPresent = true;
        }
      }
      // Restore only when the map was still carried: after a death/respawn the old item was already
      // dropped by vanilla, and restoring it here would duplicate it. The target slot is the one the
      // map just left whenever the original slot is taken, so the restore always fits.
      if (mapStillPresent) {
        int target = display.slot;
        boolean slotFree =
            target >= 0 && target < size && isEmpty(slots.get(target));
        if (!slotFree) target = firstFreed;
        if (target >= 0) slots.set(target, display.previousItem);
      }
      forgetId(display.mapId);
      // A pending captcha must die with its map: an answer typed after the image is gone (or shown
      // to another player later) must never verify.
      MapImageCaptcha.discard(playerId);
      return true;
    } catch (Throwable err) {
      AuthCoreServer.LOGGER.debug(false, "Failed to clear a map image:", err);
      return false;
    }
  }

  /** Crash-recovery core: removes leftover temporary maps from the given inventory view. */
  static void recoverInto(UUID playerId, Slots slots) {
    if (playerId == null || slots == null) return;
    // A display still held in memory means the leave handler never ran (it always takes the map
    // back): recover through the normal path so the replaced item is restored instead of dropped.
    if (DISPLAYS.containsKey(playerId)) {
      clearInto(playerId, slots);
      return;
    }
    loadIds();
    if (TEMP_IDS.isEmpty()) return;
    try {
      int size = Math.max(0, slots.size());
      boolean changed = false;
      for (int i = 0; i < size; i++) {
        ItemStack stack = slots.get(i);
        if (stack == null || stack.isEmpty()) continue;
        int mapId = MapImageMapFactory.mapId(stack);
        if (mapId >= 0 && TEMP_IDS.remove(mapId)) {
          slots.set(i, ItemStack.EMPTY);
          changed = true;
        }
      }
      if (changed) saveIds();
    } catch (Throwable err) {
      AuthCoreServer.LOGGER.debug(false, "Failed to recover leftover map images:", err);
    }
  }

  /**
   * Self-test hook: drops every piece of in-memory state (displays and pending ids) exactly like a
   * server restart does - the persisted id file is left untouched, so the next join recovers it.
   */
  static void resetState() {
    DISPLAYS.clear();
    TEMP_IDS.clear();
    idsLoaded = false;
  }

  // ------------------------------------------------------------------ internals

  /** Real player view: the inventory slots plus one virtual offhand slot. */
  private static Slots slotsOf(ServerPlayer player) {
    net.minecraft.world.entity.player.Inventory inventory = player.getInventory();
    int inventorySize = inventory.getContainerSize();
    return new Slots() {
      @Override
      public int size() {
        return inventorySize + 1;
      }

      @Override
      public ItemStack get(int slot) {
        if (slot == inventorySize) return player.getOffhandItem();
        return slot >= 0 && slot < inventorySize ? inventory.getItem(slot) : ItemStack.EMPTY;
      }

      @Override
      public void set(int slot, ItemStack stack) {
        if (slot == inventorySize) {
          player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, stack);
        } else if (slot >= 0 && slot < inventorySize) {
          inventory.setItem(slot, stack);
        }
      }

      @Override
      public int selectedSlot() {
        return MapImageService.selectedSlot(inventory);
      }
    };
  }

  private static boolean isEmpty(ItemStack stack) {
    return stack == null || stack.isEmpty();
  }

  private static int clamp(int value, int size) {
    if (size <= 0) return 0;
    return Math.max(0, Math.min(size - 1, value));
  }

  private static void rememberId(int mapId) {
    if (TEMP_IDS.size() >= MAX_TEMP_IDS) {
      // Bound the persisted recovery list: ids whose player crashed and never came back must not
      // grow the file (and the in-memory set) forever.
      int drop = TEMP_IDS.size() - MAX_TEMP_IDS / 2;
      for (int id : TEMP_IDS) {
        if (drop-- <= 0) break;
        TEMP_IDS.remove(id);
      }
    }
    TEMP_IDS.add(mapId);
    saveIds();
  }

  private static void forgetId(int mapId) {
    if (TEMP_IDS.remove(mapId)) saveIds();
  }

  private static int selectedSlot(net.minecraft.world.entity.player.Inventory inventory) {
    /*? if < 1.21.5 {*/
    /*return inventory.selected;
    *//*?} else {*/
    return inventory.getSelectedSlot();
    /*?}*/
  }

  private static Path idsFile() {
    return AuthCoreServer.configPath.resolve("map-images.txt");
  }

  private static synchronized void saveIds() {
    // Merge anything persisted by an earlier server run before rewriting the file: a display that
    // happens before the first join must not truncate the crash-recovery ids.
    loadIds();
    try {
      StringBuilder builder = new StringBuilder();
      for (int id : TEMP_IDS) builder.append(id).append('\n');
      Files.writeString(idsFile(), builder.toString(), StandardCharsets.UTF_8);
    } catch (Throwable err) {
      AuthCoreServer.LOGGER.debug(false, "Failed to persist temporary map ids:", err);
    }
  }

  private static synchronized void loadIds() {
    if (idsLoaded) return;
    idsLoaded = true;
    try {
      Path file = idsFile();
      if (!Files.exists(file)) return;
      for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        try {
          TEMP_IDS.add(Integer.parseInt(line.trim()));
        } catch (NumberFormatException ignored) {
          // skip malformed line
        }
      }
    } catch (Throwable err) {
      AuthCoreServer.LOGGER.debug(false, "Failed to read temporary map ids:", err);
    }
  }

  private static boolean versionAtLeast(String minimum) {
    try {
      return compareVersions(
              in.potenfyr.authcore.compat.Compat.getGameVersion(), minimum)
          >= 0;
    } catch (Throwable err) {
      return false;
    }
  }

  private static int compareVersions(String current, String minimum) {
    if (current == null) return -1;
    String[] a = current.split("\\.");
    String[] b = minimum.split("\\.");
    for (int i = 0; i < 3; i++) {
      int va = i < a.length ? leadingInt(a[i]) : 0;
      int vb = i < b.length ? leadingInt(b[i]) : 0;
      if (va != vb) return Integer.compare(va, vb);
    }
    return 0;
  }

  private static int leadingInt(String value) {
    int end = 0;
    while (end < value.length() && Character.isDigit(value.charAt(end))) end++;
    try {
      return end == 0 ? 0 : Integer.parseInt(value.substring(0, end));
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }
}
