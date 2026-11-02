package in.potenfyr.authcore.util;

/**
 * Turns content (QR codes, captcha/label text) into the 128x128 RGB images that
 * {@link MapImageRenderer} paints onto a map.
 *
 * <p>All output is deterministic and side-effect free; the only inputs are strings (validated and
 * length-bounded) so there is no file, network or reflection surface here - nothing in this class
 * can execute code or read server state. Callers get an {@link IllegalArgumentException} for
 * unusable input instead of a partially rendered image.
 */
public final class MapImageContent {

  /** Dark module / ink colour. Pure black quantises to the palette's BLACK shade. */
  private static final int INK = 0x000000;
  /** Light module / paper colour. Pure white quantises to the palette's SNOW entry. */
  private static final int PAPER = 0xFFFFFF;
  /** Quiet zone required by the QR specification, in modules. */
  public static final int QR_QUIET_ZONE = 4;
  /** A module must be at least this many map pixels to stay scannable after printing. */
  public static final int MIN_QR_MODULE_PIXELS = 2;

  private static final int TEXT_MARGIN = 6;

  private MapImageContent() {}

  /**
   * Renders {@code text} as a QR code on a 128x128 image (quiet zone included).
   *
   * @throws IllegalArgumentException when the payload cannot be encoded or the resulting symbol
   *     would need modules smaller than {@link #MIN_QR_MODULE_PIXELS} pixels (not scannable)
   */
  public static int[] qr(String text, int eccLevel) {
    boolean[][] modules = QrCode.encode(text, eccLevel);
    int size = modules.length;
    int scale = MapImageRenderer.SIZE / (size + QR_QUIET_ZONE * 2);
    if (scale < MIN_QR_MODULE_PIXELS) {
      throw new IllegalArgumentException(
          "QR version too large for a 128x128 map (needs " + MIN_QR_MODULE_PIXELS + "px modules)");
    }
    int offset = (MapImageRenderer.SIZE - (size + QR_QUIET_ZONE * 2) * scale) / 2
        + QR_QUIET_ZONE * scale;
    int[] image = solid(PAPER);
    for (int y = 0; y < size; y++) {
      for (int x = 0; x < size; x++) {
        if (modules[y][x]) {
          fill(image, offset + x * scale, offset + y * scale, scale, INK);
        }
      }
    }
    return image;
  }

  /**
   * Renders a short label (captcha code, server name, banner) as large glyphs on a 128x128 image.
   *
   * @throws IllegalArgumentException for empty/unsupported/oversized labels
   */
  public static int[] text(String label) {
    if (label == null) throw new IllegalArgumentException("label is null");
    String upper = label.trim().toUpperCase(java.util.Locale.ROOT);
    if (upper.isEmpty()) throw new IllegalArgumentException("label is empty");
    if (upper.length() > 12) throw new IllegalArgumentException("label too long");
    int[] glyphs = new int[upper.length()];
    for (int i = 0; i < upper.length(); i++) {
      glyphs[i] = glyphIndex(upper.charAt(i));
    }
    int[] layout = layout(upper);
    int scale = layout[0];
    int startX = layout[1];
    int startY = layout[2];
    int[] image = solid(INK);
    // Thin frame so the label stays readable on a bright map area.
    rect(image, 1, 1, MapImageRenderer.SIZE - 2, MapImageRenderer.SIZE - 2, PAPER);
    rect(image, 3, 3, MapImageRenderer.SIZE - 6, MapImageRenderer.SIZE - 6, INK);
    for (int g = 0; g < glyphs.length; g++) {
      long glyph = GLYPHS[glyphs[g]];
      int glyphX = startX + g * 6 * scale;
      for (int row = 0; row < 7; row++) {
        // Row 0 of each glyph literal sits in the most significant 5-bit group.
        int bits = (int) ((glyph >>> ((7 - 1 - row) * 5)) & 0x1F);
        for (int col = 0; col < 5; col++) {
          if (((bits >>> (4 - col)) & 1) != 0) {
            fill(image, glyphX + col * scale, startY + row * scale, scale, PAPER);
          }
        }
      }
    }
    return image;
  }

  /**
   * Label layout as {@code {scale, startX, startY}} for an already normalised (trimmed, upper-case)
   * label. Package-private so the self-test verifies the very same geometry.
   */
  static int[] layout(String upper) {
    if (upper == null || upper.isEmpty()) throw new IllegalArgumentException("label is empty");
    int unitsWide = upper.length() * 6 - 1;
    int available = MapImageRenderer.SIZE - TEXT_MARGIN * 2;
    int scale = Math.min(available / unitsWide, available / 7);
    if (scale < 2) throw new IllegalArgumentException("label too long for a 128x128 map");
    int width = unitsWide * scale;
    int height = 7 * scale;
    return new int[] {scale, (MapImageRenderer.SIZE - width) / 2, (MapImageRenderer.SIZE - height) / 2};
  }

  /** The label characters {@link #text(String)} can render. */
  public static String supportedCharacters() {
    return GLYPH_CHARS;
  }

  // ------------------------------------------------------------------ drawing helpers

  private static int[] solid(int rgb) {
    int[] image = new int[MapImageRenderer.PIXELS];
    java.util.Arrays.fill(image, rgb);
    return image;
  }

  private static void fill(int[] image, int startX, int startY, int scale, int rgb) {
    for (int y = 0; y < scale; y++) {
      int py = startY + y;
      if (py < 0 || py >= MapImageRenderer.SIZE) continue;
      for (int x = 0; x < scale; x++) {
        int px = startX + x;
        if (px >= 0 && px < MapImageRenderer.SIZE) image[py * MapImageRenderer.SIZE + px] = rgb;
      }
    }
  }

  private static void rect(int[] image, int startX, int startY, int width, int height, int rgb) {
    for (int y = 0; y < height; y++) {
      int py = startY + y;
      if (py < 0 || py >= MapImageRenderer.SIZE) continue;
      for (int x = 0; x < width; x++) {
        int px = startX + x;
        if (px >= 0 && px < MapImageRenderer.SIZE) image[py * MapImageRenderer.SIZE + px] = rgb;
      }
    }
  }

  // ------------------------------------------------------------------ 5x7 font

  /** Characters covered by the built-in font, in {@link #GLYPHS} order. */
  private static final String GLYPH_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

  /**
   * 5x7 glyphs, one 35-bit pattern each: bits are stored row-major with the most significant of
   * each 5-bit row leftmost, row 0 in the lowest bits. Index matches {@link #GLYPH_CHARS}.
   */
  private static final long[] GLYPHS = {
    0b01110_10001_10001_11111_10001_10001_10001L, // A
    0b11110_10001_10001_11110_10001_10001_11110L, // B
    0b01110_10001_10000_10000_10000_10001_01110L, // C
    0b11110_10001_10001_10001_10001_10001_11110L, // D
    0b11111_10000_10000_11110_10000_10000_11111L, // E
    0b11111_10000_10000_11110_10000_10000_10000L, // F
    0b01110_10001_10000_10111_10001_10001_01111L, // G
    0b10001_10001_10001_11111_10001_10001_10001L, // H
    0b00111_00010_00010_00010_00010_10010_01100L, // J
    0b10001_10010_10100_11000_10100_10010_10001L, // K
    0b10000_10000_10000_10000_10000_10000_11111L, // L
    0b10001_11011_10101_10101_10001_10001_10001L, // M
    0b10001_11001_10101_10011_10001_10001_10001L, // N
    0b11110_10001_10001_11110_10000_10000_10000L, // P
    0b01110_10001_10001_10001_10101_10010_01101L, // Q
    0b11110_10001_10001_11110_10100_10010_10001L, // R
    0b01111_10000_10000_01110_00001_00001_11110L, // S
    0b11111_00100_00100_00100_00100_00100_00100L, // T
    0b10001_10001_10001_10001_10001_10001_01110L, // U
    0b10001_10001_10001_10001_10001_01010_00100L, // V
    0b10001_10001_10001_10101_10101_11011_10001L, // W
    0b10001_10001_01010_00100_01010_10001_10001L, // X
    0b10001_10001_01010_00100_00100_00100_00100L, // Y
    0b11111_00001_00010_00100_01000_10000_11111L, // Z
    0b01110_10001_00001_00010_00100_01000_11111L, // 2
    0b11111_00010_00100_00010_00001_10001_01110L, // 3
    0b00010_00110_01010_10010_11111_00010_00010L, // 4
    0b11111_10000_11110_00001_00001_10001_01110L, // 5
    0b00110_01000_10000_11110_10001_10001_01110L, // 6
    0b11111_00001_00010_00100_01000_01000_01000L, // 7
    0b01110_10001_10001_01110_10001_10001_01110L, // 8
    0b01110_10001_10001_01111_00001_00010_01100L, // 9
  };

  private static int glyphIndex(char character) {
    int index = GLYPH_CHARS.indexOf(character);
    if (index < 0) throw new IllegalArgumentException("unsupported label character: " + character);
    return index;
  }

  /** Glyph bits for a font character (self-test diagnostics). */
  static long glyphBits(char character) {
    return GLYPHS[glyphIndex(character)];
  }
}
