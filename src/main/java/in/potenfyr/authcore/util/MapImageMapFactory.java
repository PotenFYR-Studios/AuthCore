package in.potenfyr.authcore.util;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/**
 * Creates a filled-map item whose server-side saved data already carries the rendered colours.
 *
 * <p>This is the piece that makes an image actually render in-game: a {@code filled_map} whose map
 * id has no {@link MapItemSavedData} falls back to the client's default terrain map (the "it just
 * shows my location" symptom). Creating the data first and writing the 16384 colour bytes makes
 * the server send the real image to the client when the map is carried.
 *
 * <p>The data is also replaced with a <em>locked</em> copy: an unlocked map is continuously
 * re-coloured from the world terrain by the vanilla updater as soon as a player carries it, which
 * erases the image. {@code locked()} exists from 1.17 on; 1.16.5 has no such copy method, so the
 * same result is produced by re-loading the map's own NBT with the {@code locked} flag flipped.
 *
 * <p>Kept in a separate class on purpose: the 1.19-1.21 jar is compiled against 1.21.11 (data
 * components), and loading it on 1.19.4 (which has no component API) must be avoided - the service
 * gates on {@link MapImageRenderer#MODERN_MAP_API} + the runtime version before touching this
 * class.
 */
public final class MapImageMapFactory {

  private MapImageMapFactory() {}

  /** Creates a locked, scale-0 map item with the given map colours, or null on failure. */
  public static ItemStack createMap(ServerLevel level, byte[] colors) {
    if (level == null || colors == null || colors.length < MapImageRenderer.PIXELS) return null;
    /*? if < 1.20.5 {*/
    /*ItemStack stack = net.minecraft.world.item.MapItem.create(level, 0, 0, (byte) 0, false, false);
    int id = legacyMapId(stack);
    if (id < 0) return null;
    String mapName = "map_" + id;
    MapItemSavedData data = level.getMapData(mapName);
    if (data == null) return null;
    MapItemSavedData locked = lockLegacy(data, mapName);
    if (locked != null) {
      registerMapData(level, mapName, locked);
      data = locked;
    }
    writeColors(data, colors);
    *//*?} else {*/
    ItemStack stack = net.minecraft.world.item.MapItem.create(level, 0, 0, (byte) 0, false, false);
    net.minecraft.world.level.saveddata.maps.MapId id =
        stack.get(net.minecraft.core.component.DataComponents.MAP_ID);
    if (id == null) return null;
    MapItemSavedData data = level.getMapData(id);
    if (data == null) return null;
    MapItemSavedData locked = data.locked();
    level.setMapData(id, locked);
    writeColors(locked, colors);
    /*?}*/
    return stack;
  }

  /** Writes all 16384 pixels into the saved data, using the accessor each runtime offers. */
  private static void writeColors(MapItemSavedData data, byte[] colors) {
    /*? if < 1.20.5 {*/
    /*// 1.16.5 has no MapItemSavedData.setColor(int,int,byte) - vanilla writes the colours array
    // directly there, so try the accessor first (1.17+) and fall back to the array.
    try {
      for (int i = 0; i < MapImageRenderer.PIXELS; i++) {
        data.setColor(i % MapImageRenderer.SIZE, i / MapImageRenderer.SIZE, colors[i]);
      }
      return;
    } catch (NoSuchMethodError legacyNoSetColor) {
      byte[] target = data.colors;
      if (target != null && target.length >= MapImageRenderer.PIXELS) {
        System.arraycopy(colors, 0, target, 0, MapImageRenderer.PIXELS);
      }
      data.setDirty();
    }
    *//*?} else {*/
    for (int i = 0; i < MapImageRenderer.PIXELS; i++) {
      data.setColor(i % MapImageRenderer.SIZE, i / MapImageRenderer.SIZE, colors[i]);
    }
    /*?}*/
  }

  /*? if < 1.20.5 {*/
  /*
  // Returns a locked copy of the map data, or null when the runtime cannot produce one.
  //
  // 1.17+ exposes MapItemSavedData.locked(), a copy whose locked flag is set. 1.16.5 has neither
  // that method nor a static NBT loader (and "locked" is a plain mutable field there), so the copy
  // is rebuilt through the public serialisation: save the map NBT, flip "locked", construct a data
  // instance and load the tag into it. Reflection is required because the 1.16.5
  // constructor/loader signatures are not part of the 1.18 compile target.
  private static MapItemSavedData lockLegacy(MapItemSavedData data, String mapName) {
    try {
      return data.locked();
    } catch (NoSuchMethodError noLockedCopy) {
      // 1.16.5: fall through to the NBT round-trip
    }
    try {
      java.lang.reflect.Constructor<?> constructor = null;
      for (java.lang.reflect.Constructor<?> candidate : data.getClass().getConstructors()) {
        if (candidate.getParameterCount() == 1
            && candidate.getParameterTypes()[0] == String.class) {
          constructor = candidate;
          break;
        }
      }
      java.lang.reflect.Method load = null;
      for (java.lang.reflect.Method method : data.getClass().getMethods()) {
        if (method.getParameterCount() == 1
            && method.getParameterTypes()[0] == net.minecraft.nbt.CompoundTag.class
            && method.getReturnType() == void.class) {
          load = method;
          break;
        }
      }
      if (constructor == null || load == null) return null;
      net.minecraft.nbt.CompoundTag tag = saveNbt(data);
      if (tag == null) return null;
      tag.putBoolean("locked", true);
      MapItemSavedData copy = (MapItemSavedData) constructor.newInstance(mapName);
      load.invoke(copy, tag);
      return copy;
    } catch (ReflectiveOperationException | RuntimeException | LinkageError failed) {
      return null;
    }
  }

  // Serialises the map data through its own save(CompoundTag) (shape-resolved).
  private static net.minecraft.nbt.CompoundTag saveNbt(MapItemSavedData data) {
    for (java.lang.reflect.Method method : data.getClass().getMethods()) {
      if (method.getParameterCount() == 1
          && method.getParameterTypes()[0] == net.minecraft.nbt.CompoundTag.class
          && method.getReturnType() == net.minecraft.nbt.CompoundTag.class) {
        try {
          return (net.minecraft.nbt.CompoundTag)
              method.invoke(data, new net.minecraft.nbt.CompoundTag());
        } catch (ReflectiveOperationException | RuntimeException failed) {
          return null;
        }
      }
    }
    return null;
  }

  // Registers the (locked) map data under its map name.
  //
  // 1.17+ has setMapData(String, MapItemSavedData); 1.16.5 only has
  // setMapData(MapItemSavedData), which takes the name from the data instance itself - so the
  // two-arg call is attempted first and the one-arg overload is invoked reflectively.
  private static void registerMapData(ServerLevel level, String mapName, MapItemSavedData data) {
    try {
      level.setMapData(mapName, data);
      return;
    } catch (NoSuchMethodError oneArgOnly) {
      // 1.16.5: fall through to the one-argument overload
    }
    for (java.lang.reflect.Method method : level.getClass().getMethods()) {
      if (method.getParameterCount() == 1
          && method.getParameterTypes()[0] == MapItemSavedData.class) {
        try {
          method.invoke(level, data);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
          // best effort: the map keeps the unlocked data (still renders, may be re-coloured)
        }
        return;
      }
    }
  }

  // Legacy (1.16-1.20.4) map id lookup through the "map" NBT tag.
  //
  // Deliberately not MapItem.getMapId(ItemStack): its return type changed from int
  // (1.16.5/1.17.1) to Integer (1.18+), and the 1.16-1.18 jar is a single build compiled against
  // 1.18.2, so linking against the 1.18 signature crashed the older runtimes with
  // NoSuchMethodError.
  private static int legacyMapId(ItemStack stack) {
    if (stack == null) return -1;
    net.minecraft.nbt.CompoundTag tag = stack.getTag();
    if (tag == null || !tag.contains("map")) return -1;
    return tag.getInt("map");
  }
  *//*?}*/

  /** The numeric map id of a filled map item, or -1. */
  public static int mapId(ItemStack stack) {
    if (stack == null) return -1;
    /*? if < 1.20.5 {*/
    /*return legacyMapId(stack);
    *//*?} else {*/
    net.minecraft.world.level.saveddata.maps.MapId id =
        stack.get(net.minecraft.core.component.DataComponents.MAP_ID);
    return id == null ? -1 : id.id();
    /*?}*/
  }

  /** The saved data behind a map item, or null when it does not exist. */
  private static MapItemSavedData dataOf(ServerLevel level, ItemStack stack) {
    int id = mapId(stack);
    if (level == null || id < 0) return null;
    /*? if < 1.20.5 {*/
    /*return level.getMapData("map_" + id);
    *//*?} else {*/
    return level.getMapData(new net.minecraft.world.level.saveddata.maps.MapId(id));
    /*?}*/
  }

  /** Reads the map colours back from the saved data (self-test verification). */
  public static byte[] readBack(ServerLevel level, ItemStack stack) {
    MapItemSavedData data = dataOf(level, stack);
    if (data == null || data.colors == null) return null;
    return data.colors.clone();
  }

  /** Whether the map is locked (no terrain updates) - the self-test asserts this is true. */
  public static boolean isLocked(ServerLevel level, ItemStack stack) {
    MapItemSavedData data = dataOf(level, stack);
    return data != null && data.locked;
  }
}
