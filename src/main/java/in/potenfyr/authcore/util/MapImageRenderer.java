package in.potenfyr.authcore.util;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Renders 128x128 RGB images onto Minecraft maps.
 *
 * <p>The palette is discovered from the live game classes instead of a hard-coded table, and every
 * accessor is resolved by SHAPE (parameter types + return type) instead of by name - the classes
 * were renamed ({@code MaterialColor} -> {@code MapColor} in 1.19.4) and the colour accessor was
 * renamed ({@code calculateRGBColor} -> {@code calculateARGBColor} in 1.21.2), and even the
 * intermediary names of unchanged methods differ between endpoints. A 32x32x32 nearest-colour
 * lookup table is built once, giving O(1) per pixel.
 */
public final class MapImageRenderer {

  public static final int SIZE = 128;
  public static final int PIXELS = SIZE * SIZE;

  /**
   * True when this build targets the 1.20.5+ map-id API (data components). The legacy builds
   * (1.16-1.18) use the NBT map-id path instead.
   */
  public static final boolean MODERN_MAP_API;

  static {
    /*? if < 1.20.5 {*/
    /*MODERN_MAP_API = false;
    *//*?} else {*/
    MODERN_MAP_API = true;
    /*?}*/
  }

  private static final byte[] PALETTE_BYTES = new byte[256];
  private static final int[] PALETTE_RGB = new int[256];
  private static volatile int paletteSize;
  private static volatile boolean paletteUnavailable;
  private static volatile byte[] lut;

  private MapImageRenderer() {}

  /** Renders an ARGB/RGB 128x128 image into the 16384 map-colour bytes. */
  public static byte[] render(int[] image) {
    if (image == null || image.length < PIXELS) {
      throw new IllegalArgumentException("image must be 128x128");
    }
    byte[] table = lut();
    byte[] out = new byte[PIXELS];
    for (int i = 0; i < PIXELS; i++) {
      int rgb = image[i];
      int r = (rgb >> 16) & 0xFF;
      int g = (rgb >> 8) & 0xFF;
      int b = rgb & 0xFF;
      out[i] = table[((r >> 3) << 10) | ((g >> 3) << 5) | (b >> 3)];
    }
    return out;
  }

  /** Number of palette entries discovered at runtime (diagnostics/self-test). */
  public static int paletteSize() {
    ensurePalette();
    return paletteSize;
  }

  /** RGB of a packed map-colour byte (diagnostics/self-test). */
  public static int paletteRgb(byte packed) {
    ensurePalette();
    return PALETTE_RGB[packed & 0xFF];
  }

  /** Deterministic 128x128 test image (quadrants + border + checker) used by the demo command. */
  public static int[] testPattern() {
    int[] rgb = new int[PIXELS];
    for (int y = 0; y < SIZE; y++) {
      for (int x = 0; x < SIZE; x++) {
        int color;
        if (x < 2 || y < 2 || x >= SIZE - 2 || y >= SIZE - 2) {
          color = 0x000000;
        } else {
          int quadrant = (x < SIZE / 2 ? 0 : 1) + (y < SIZE / 2 ? 0 : 2);
          color =
              switch (quadrant) {
                case 0 -> 0xFF0000;
                case 1 -> 0x00FF00;
                case 2 -> 0x0000FF;
                default -> 0xFFFFFF;
              };
          if (((x / 8) + (y / 8)) % 2 == 0) color = (color & 0xFEFEFE) >> 1;
        }
        rgb[y * SIZE + x] = color;
      }
    }
    return rgb;
  }

  /** Finds the nested Brightness enum constants without referencing the nested class. */
  private static Object[] brightnessValuesOf(Class<?> colorClass) {
    for (Class<?> nested : colorClass.getDeclaredClasses()) {
      if (nested.isEnum() && nested.getSimpleName().contains("Brightness")) {
        Object[] values = nested.getEnumConstants();
        if (values != null && values.length > 0) return values;
      }
    }
    for (Method method : colorClass.getMethods()) {
      for (Class<?> parameter : method.getParameterTypes()) {
        if (parameter.isEnum()) {
          Object[] values = parameter.getEnumConstants();
          if (values != null && values.length > 0) return values;
        }
      }
    }
    return new Object[0];
  }
  private static synchronized void ensurePalette() {
    if (paletteSize > 0 || paletteUnavailable) return;
    /*? if < 1.19.4 {*/
    /*Class<?> colorClass = net.minecraft.world.level.material.MaterialColor.class;
    *//*?} else {*/
    Class<?> colorClass = net.minecraft.world.level.material.MapColor.class;
    /*?}*/
    // Strategy 1 (1.18+): enumerate byId(int) and multiply by the nested Brightness enum.
    int count = paletteFromColors(colorClass);
    // Strategy 2 (1.16.5/1.17.1): those versions have NO Brightness enum at all, so the palette
    // is rebuilt from the static COLORS array and the vanilla shade multipliers.
    if (count == 0) count = paletteFromColorArray(colorClass);
    if (count == 0) {
      paletteUnavailable = true;
      return;
    }
    paletteSize = count;
  }

  /** Strategy 1: {@code byId(int)} + nested {@code Brightness} constants (1.18+). */
  private static int paletteFromColors(Class<?> colorClass) {
    Object[] brightnessValues = brightnessValuesOf(colorClass);
    if (brightnessValues.length == 0) return 0;
    int count = 0;
    for (int id = 1; id < 256 && count + brightnessValues.length <= 256; id++) {
      Object color;
      try {
        color = colorById(colorClass, id);
      } catch (RuntimeException | Error outOfRange) {
        break; // byId throws once the id exceeds the palette (it does not return null)
      }
      if (color == null) break;
      for (Object brightness : brightnessValues) {
        byte packed = packedIdOf(color, brightness);
        PALETTE_BYTES[count++] = packed;
        PALETTE_RGB[packed & 0xFF] = paletteRgbOf(color, brightness);
      }
    }
    return count;
  }

  /**
   * Strategy 2: rebuild the palette from the static {@code COLORS} array (index == base colour id)
   * and the four vanilla shade multipliers.
   *
   * <p>1.16.5/1.17.1 predate the {@code Brightness} enum; the shaded RGB is computed with the
   * multipliers below, which are the exact ones used by 1.17.1's {@code MapColor.getRenderColor(int)}
   * bytecode (shade 0 = 180, 1 = 220, 2 = 255, 3 = 135) and have not changed since 1.7. The colour
   * RGB is read from the int field of each entry - the {@code id} int field is told apart by its
   * tiny range (0-63) versus the 24-bit RGB values.
   */
  private static final int[] SHADE_MULTIPLIERS = {180, 220, 255, 135};

  private static int paletteFromColorArray(Class<?> colorClass) {
    Object[] colors = colorArrayOf(colorClass);
    if (colors == null || colors.length < 2) return 0;
    java.lang.reflect.Field rgbField = rgbFieldOf(colorClass, colors);
    if (rgbField == null) return 0;
    int count = 0;
    for (int id = 1; id < colors.length; id++) {
      Object color = colors[id];
      if (color == null) continue;
      int rgb;
      try {
        rgb = ((Integer) rgbField.get(color)) & 0xFFFFFF;
      } catch (ReflectiveOperationException | RuntimeException unreadable) {
        break;
      }
      for (int shade = 0; shade < SHADE_MULTIPLIERS.length; shade++) {
        int packed = (id << 2) | shade;
        if (packed > 255 || count >= 256) break;
        int multiplier = SHADE_MULTIPLIERS[shade];
        int r = ((rgb >> 16) & 0xFF) * multiplier / 255;
        int g = ((rgb >> 8) & 0xFF) * multiplier / 255;
        int b = (rgb & 0xFF) * multiplier / 255;
        PALETTE_BYTES[count++] = (byte) packed;
        PALETTE_RGB[packed] = (r << 16) | (g << 8) | b;
      }
    }
    return count;
  }

  /** The static {@code MapColor[] COLORS} table, located by its array component type. */
  private static Object[] colorArrayOf(Class<?> colorClass) {
    for (java.lang.reflect.Field field : colorClass.getDeclaredFields()) {
      if (Modifier.isStatic(field.getModifiers())
          && field.getType().isArray()
          && field.getType().getComponentType() == colorClass) {
        try {
          field.setAccessible(true);
          Object value = field.get(null);
          return value instanceof Object[] array ? array : null;
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
          return null;
        }
      }
    }
    return null;
  }

  /** The colour's RGB int field (the sibling int field holds the small base-colour id). */
  private static java.lang.reflect.Field rgbFieldOf(Class<?> colorClass, Object[] colors) {
    java.lang.reflect.Field best = null;
    long bestMax = -1;
    for (java.lang.reflect.Field field : colorClass.getDeclaredFields()) {
      if (Modifier.isStatic(field.getModifiers()) || field.getType() != int.class) continue;
      try {
        field.setAccessible(true);
        long max = 0;
        for (Object color : colors) {
          if (color == null) continue;
          max = Math.max(max, ((Integer) field.get(color)) & 0xFFFFFF);
        }
        if (max > bestMax) {
          bestMax = max;
          best = field;
        }
      } catch (ReflectiveOperationException | RuntimeException unreadable) {
        // try the next candidate field
      }
    }
    // A real RGB component can reach 0xFFFFFF; the id field never exceeds the palette length.
    return bestMax > 0xFFFF ? best : null;
  }

  /** Resolves {@code byId(int)} by shape (static, int parameter, returns the colour class). */
  private static Object colorById(Class<?> colorClass, int id) {
    for (Method method : colorClass.getMethods()) {
      if (Modifier.isStatic(method.getModifiers())
          && method.getParameterCount() == 1
          && method.getParameterTypes()[0] == int.class
          && method.getReturnType() == colorClass) {
        try {
          return method.invoke(null, id);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
          // try the next overload
        }
      }
    }
    throw new IllegalStateException("no byId on " + colorClass.getName());
  }

  /** Resolves {@code getPackedId(Brightness)} by shape (byte return). */
  private static byte packedIdOf(Object color, Object brightness) {
    for (Method method : color.getClass().getMethods()) {
      if (!Modifier.isStatic(method.getModifiers())
          && method.getParameterCount() == 1
          && method.getParameterTypes()[0].isInstance(brightness)
          && method.getReturnType() == byte.class) {
        try {
          return (Byte) method.invoke(color, brightness);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
          // try the next overload
        }
      }
    }
    return 0;
  }

  /**
   * RGB of a map colour/brightness pair. The accessor was renamed ({@code calculateRGBColor} ->
   * {@code calculateARGBColor}) in 1.21.2, so it is resolved by shape (one brightness parameter,
   * int return).
   */
  private static int paletteRgbOf(Object color, Object brightness) {
    for (Method method : color.getClass().getMethods()) {
      if (method.getParameterCount() == 1
          && method.getParameterTypes()[0].isInstance(brightness)
          && method.getReturnType() == int.class) {
        try {
          return ((Integer) method.invoke(color, brightness)) & 0xFFFFFF;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
          // try the next overload
        }
      }
    }
    return 0;
  }

  private static synchronized byte[] lut() {
    if (lut != null) return lut;
    ensurePalette();
    byte[] table = new byte[32 * 32 * 32];
    for (int r = 0; r < 32; r++) {
      for (int g = 0; g < 32; g++) {
        for (int b = 0; b < 32; b++) {
          int tr = (r << 3) | 4;
          int tg = (g << 3) | 4;
          int tb = (b << 3) | 4;
          byte best = 0;
          long bestDistance = Long.MAX_VALUE;
          for (int i = 0; i < paletteSize; i++) {
            byte packed = PALETTE_BYTES[i];
            int rgb = PALETTE_RGB[packed & 0xFF];
            int dr = tr - ((rgb >> 16) & 0xFF);
            int dg = tg - ((rgb >> 8) & 0xFF);
            int db = tb - (rgb & 0xFF);
            // Perceptual weights: green contributes most, red next, blue least.
            long distance = 2L * dr * dr + 4L * dg * dg + 3L * db * db;
            if (distance < bestDistance) {
              bestDistance = distance;
              best = packed;
            }
          }
          table[(r << 10) | (g << 5) | b] = best;
        }
      }
    }
    lut = table;
    return table;
  }
}
