package in.potenfyr.authcore.util;

import java.nio.charset.StandardCharsets;

/**
 * Minimal, dependency-free QR Code encoder (byte mode, versions 1-10, all four error-correction
 * levels).
 *
 * <p>Exists so AuthCore can put a genuinely scannable QR code (Discord invite, {@code otpauth://}
 * 2FA enrolment, documentation link) on an in-game map without bundling a third-party library. It
 * implements ISO/IEC 18004: data encoding, Reed-Solomon error correction, function patterns,
 * format/version information, all eight data masks and the standard penalty-based mask selection.
 *
 * <p>Scope is deliberately small and side-effect free: no I/O, no reflection, no threads, no
 * allocation driven by anything but the (length-bounded) input string. The largest supported symbol
 * (version 10 = 57x57 modules) still fits a 128x128 map with 2-pixel modules and a 4-module quiet
 * zone, so the encoder is capped there.
 *
 * <p>Correctness is covered by the host suite (matrices checked against an independent reference
 * implementation) and by the in-mod {@code /authcore simulate} pipeline check (QR -> map colours ->
 * sampled modules -> decoded text).
 */
public final class QrCode {

  /** Error-correction level: recovers ~7% of codewords. */
  public static final int ECC_LOW = 0;
  /** Error-correction level: recovers ~15% of codewords. */
  public static final int ECC_MEDIUM = 1;
  /** Error-correction level: recovers ~25% of codewords. */
  public static final int ECC_QUARTILE = 2;
  /** Error-correction level: recovers ~30% of codewords. */
  public static final int ECC_HIGH = 3;

  /** Largest symbol this encoder produces (57x57 modules). */
  public static final int MAX_VERSION = 10;

  /** Hard input cap: keeps every encode allocation bounded (version 10-LOW holds 271 bytes). */
  public static final int MAX_BYTES = 271;

  // ECC codewords per block, indexed [eccLevel][version]; index 0 is unused.
  private static final int[][] ECC_CODEWORDS_PER_BLOCK = {
    {0, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18},
    {0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26},
    {0, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24},
    {0, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28},
  };

  // Number of error-correction blocks, indexed [eccLevel][version]; index 0 is unused.
  private static final int[][] NUM_ERROR_CORRECTION_BLOCKS = {
    {0, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4},
    {0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5},
    {0, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8},
    {0, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8},
  };

  // Total codewords (data + ECC) per version; index 0 is unused.
  private static final int[] TOTAL_CODEWORDS = {0, 26, 44, 70, 100, 134, 172, 196, 242, 292, 346};

  private QrCode() {}

  /** Encodes {@code text} (UTF-8, byte mode) with automatic version and mask selection. */
  public static boolean[][] encode(String text, int eccLevel) {
    return encode(text, eccLevel, 0, -1);
  }

  /**
   * Encodes {@code text}, optionally pinning the version ({@code 0} = smallest that fits) and the
   * mask ({@code -1} = pick the lowest-penalty mask). Returns {@code modules[y][x]}, where
   * {@code true} is a dark module, without the quiet zone.
   */
  public static boolean[][] encode(String text, int eccLevel, int version, int mask) {
    if (text == null || text.isEmpty()) throw new IllegalArgumentException("empty QR payload");
    checkEcc(eccLevel);
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("QR payload too long");
    // Non-ASCII payloads need an ECI designator (ECI 26 = UTF-8), otherwise scanners decode the
    // raw bytes as ISO-8859-1 and the text comes out as mojibake.
    boolean eci = needsEci(bytes);
    int versionToUse = version == 0 ? pickVersion(bytes.length, eccLevel, eci) : version;
    if (versionToUse < 1 || versionToUse > MAX_VERSION) {
      throw new IllegalArgumentException("unsupported QR version " + versionToUse);
    }
    if (byteModeBits(bytes.length, versionToUse, eci) > dataCodewords(versionToUse, eccLevel) * 8) {
      throw new IllegalArgumentException("QR payload does not fit the pinned version");
    }
    if (mask < -1 || mask > 7) throw new IllegalArgumentException("bad QR mask " + mask);

    byte[] data = buildDataCodewords(bytes, versionToUse, eccLevel, eci);
    byte[] allCodewords = addErrorCorrectionAndInterleave(data, versionToUse, eccLevel);

    boolean[][] modules = new boolean[17 + 4 * versionToUse][17 + 4 * versionToUse];
    boolean[][] isFunction = new boolean[modules.length][modules.length];
    drawFunctionPatterns(modules, isFunction, versionToUse, eccLevel);
    drawCodewords(modules, isFunction, allCodewords);

    int bestMask = mask;
    if (mask == -1) {
      int minPenalty = Integer.MAX_VALUE;
      for (int candidate = 0; candidate < 8; candidate++) {
        applyMask(modules, isFunction, candidate);
        drawFormatBits(modules, isFunction, versionToUse, eccLevel, candidate);
        int penalty = penaltyScore(modules);
        if (penalty < minPenalty) {
          minPenalty = penalty;
          bestMask = candidate;
        }
        applyMask(modules, isFunction, candidate); // XOR is its own inverse
      }
    }
    applyMask(modules, isFunction, bestMask);
    drawFormatBits(modules, isFunction, versionToUse, eccLevel, bestMask);
    return modules;
  }

  /** Module count (edge length) of a version, quiet zone excluded. */
  public static int size(int version) {
    return 17 + 4 * version;
  }

  // ------------------------------------------------------------------ encoding

  private static void checkEcc(int eccLevel) {
    if (eccLevel < ECC_LOW || eccLevel > ECC_HIGH) {
      throw new IllegalArgumentException("bad QR error-correction level " + eccLevel);
    }
  }

  private static int countBits(int version) {
    return version <= 9 ? 8 : 16;
  }

  private static boolean needsEci(byte[] bytes) {
    for (byte value : bytes) {
      if ((value & 0x80) != 0) return true;
    }
    return false;
  }

  private static int byteModeBits(int length, int version, boolean eci) {
    return 4 + countBits(version) + length * 8 + (eci ? 12 : 0);
  }

  private static int pickVersion(int length, int eccLevel, boolean eci) {
    for (int version = 1; version <= MAX_VERSION; version++) {
      if (byteModeBits(length, version, eci) <= dataCodewords(version, eccLevel) * 8) {
        return version;
      }
    }
    throw new IllegalArgumentException("QR payload too long");
  }

  private static int dataCodewords(int version, int eccLevel) {
    return TOTAL_CODEWORDS[version]
        - ECC_CODEWORDS_PER_BLOCK[eccLevel][version]
            * NUM_ERROR_CORRECTION_BLOCKS[eccLevel][version];
  }

  private static byte[] buildDataCodewords(byte[] bytes, int version, int eccLevel, boolean eci) {
    int capacityBits = dataCodewords(version, eccLevel) * 8;
    int[] bits = new int[capacityBits];
    int index = 0;
    if (eci) {
      index = appendBits(bits, index, 0b0111, 4); // ECI mode
      index = appendBits(bits, index, 26, 8); // ECI assignment 26 = UTF-8
    }
    index = appendBits(bits, index, 0b0100, 4); // byte mode
    index = appendBits(bits, index, bytes.length, countBits(version));
    for (byte value : bytes) index = appendBits(bits, index, value & 0xFF, 8);
    // Terminator (up to four zero bits), then zero-pad to a byte boundary.
    index = appendBits(bits, index, 0, Math.min(4, capacityBits - index));
    index = appendBits(bits, index, 0, (8 - index % 8) % 8);
    int capacity = capacityBits / 8;
    byte[] data = new byte[capacity];
    for (int i = 0; i < capacity; i++) {
      int value = 0;
      for (int j = 0; j < 8; j++) value = (value << 1) | bits[i * 8 + j];
      data[i] = (byte) value;
    }
    for (int i = index / 8, pad = 0; i < capacity; i++, pad++) {
      data[i] = (byte) (pad % 2 == 0 ? 0xEC : 0x11);
    }
    return data;
  }

  private static int appendBits(int[] target, int index, int value, int length) {
    if (index + length > target.length) throw new IllegalArgumentException("QR data overflow");
    for (int i = length - 1; i >= 0; i--) target[index++] = (value >>> i) & 1;
    return index;
  }

  // ------------------------------------------------------------------ error correction

  private static byte[] addErrorCorrectionAndInterleave(byte[] data, int version, int eccLevel) {
    int numBlocks = NUM_ERROR_CORRECTION_BLOCKS[eccLevel][version];
    int blockEccLen = ECC_CODEWORDS_PER_BLOCK[eccLevel][version];
    int rawCodewords = TOTAL_CODEWORDS[version];
    int numShortBlocks = numBlocks - rawCodewords % numBlocks;
    int shortBlockLen = rawCodewords / numBlocks;

    byte[][] dataBlocks = new byte[numBlocks][];
    byte[][] eccBlocks = new byte[numBlocks][];
    int[] divisor = reedSolomonDivisor(blockEccLen);
    int offset = 0;
    for (int block = 0; block < numBlocks; block++) {
      int dataLen = shortBlockLen - blockEccLen + (block < numShortBlocks ? 0 : 1);
      byte[] chunk = new byte[dataLen];
      System.arraycopy(data, offset, chunk, 0, dataLen);
      offset += dataLen;
      dataBlocks[block] = chunk;
      eccBlocks[block] = reedSolomonRemainder(chunk, divisor);
    }

    byte[] result = new byte[rawCodewords];
    int out = 0;
    int maxDataLen = shortBlockLen - blockEccLen + 1;
    for (int i = 0; i < maxDataLen; i++) {
      for (int block = 0; block < numBlocks; block++) {
        byte[] chunk = dataBlocks[block];
        if (i < chunk.length) result[out++] = chunk[i];
      }
    }
    for (int i = 0; i < blockEccLen; i++) {
      for (int block = 0; block < numBlocks; block++) result[out++] = eccBlocks[block][i];
    }
    if (out != rawCodewords) throw new IllegalStateException("QR codeword count mismatch");
    return result;
  }

  private static int reedSolomonMultiply(int x, int y) {
    int z = 0;
    for (int i = 7; i >= 0; i--) {
      z = (z << 1) ^ ((z >>> 7) * 0x11D);
      z ^= ((y >>> i) & 1) * x;
    }
    return z;
  }

  private static int[] reedSolomonDivisor(int degree) {
    int[] result = new int[degree];
    result[degree - 1] = 1;
    int root = 1;
    for (int i = 0; i < degree; i++) {
      for (int j = 0; j < result.length; j++) {
        result[j] = reedSolomonMultiply(result[j], root);
        if (j + 1 < result.length) result[j] ^= result[j + 1];
      }
      root = reedSolomonMultiply(root, 0x02);
    }
    return result;
  }

  private static byte[] reedSolomonRemainder(byte[] data, int[] divisor) {
    int[] result = new int[divisor.length];
    for (byte value : data) {
      int factor = (value & 0xFF) ^ result[0];
      System.arraycopy(result, 1, result, 0, result.length - 1);
      result[result.length - 1] = 0;
      for (int i = 0; i < result.length; i++) {
        result[i] ^= reedSolomonMultiply(divisor[i], factor);
      }
    }
    byte[] out = new byte[result.length];
    for (int i = 0; i < result.length; i++) out[i] = (byte) result[i];
    return out;
  }

  // ------------------------------------------------------------------ function patterns

  private static void drawFunctionPatterns(
      boolean[][] modules, boolean[][] isFunction, int version, int eccLevel) {
    int size = modules.length;
    // Timing patterns (overwritten by the finder/alignment patterns where they overlap).
    for (int i = 0; i < size; i++) {
      setFunction(modules, isFunction, i, 6, i % 2 == 0);
      setFunction(modules, isFunction, 6, i, i % 2 == 0);
    }
    // Finder patterns with their separators.
    drawFinder(modules, isFunction, 3, 3);
    drawFinder(modules, isFunction, size - 4, 3);
    drawFinder(modules, isFunction, 3, size - 4);
    // Alignment patterns.
    int[] align = alignmentPatternPositions(version);
    for (int i = 0; i < align.length; i++) {
      for (int j = 0; j < align.length; j++) {
        boolean corner =
            (i == 0 && j == 0)
                || (i == 0 && j == align.length - 1)
                || (i == align.length - 1 && j == 0);
        if (!corner) drawAlignment(modules, isFunction, align[i], align[j]);
      }
    }
    // Reserve the format area, then write the real bits (the mask is filled in later).
    drawFormatBits(modules, isFunction, version, eccLevel, 0);
    drawVersionBits(modules, isFunction, version);
  }

  private static void setFunction(
      boolean[][] modules, boolean[][] isFunction, int x, int y, boolean dark) {
    modules[y][x] = dark;
    isFunction[y][x] = true;
  }

  private static void drawFinder(
      boolean[][] modules, boolean[][] isFunction, int centerX, int centerY) {
    for (int dy = -4; dy <= 4; dy++) {
      for (int dx = -4; dx <= 4; dx++) {
        int x = centerX + dx;
        int y = centerY + dy;
        if (x < 0 || y < 0 || x >= modules.length || y >= modules.length) continue;
        int distance = Math.max(Math.abs(dx), Math.abs(dy));
        setFunction(modules, isFunction, x, y, distance != 2 && distance != 4);
      }
    }
  }

  private static void drawAlignment(
      boolean[][] modules, boolean[][] isFunction, int centerX, int centerY) {
    for (int dy = -2; dy <= 2; dy++) {
      for (int dx = -2; dx <= 2; dx++) {
        int distance = Math.max(Math.abs(dx), Math.abs(dy));
        setFunction(modules, isFunction, centerX + dx, centerY + dy, distance != 1);
      }
    }
  }

  /** Alignment pattern centre positions (ISO/IEC 18004 annex E). */
  static int[] alignmentPatternPositions(int version) {
    if (version == 1) return new int[0];
    int numAlign = version / 7 + 2;
    int step = version == 32 ? 26 : (version * 4 + numAlign * 2 + 1) / (numAlign * 2 - 2) * 2;
    int[] result = new int[numAlign];
    result[0] = 6;
    for (int i = result.length - 1, pos = size(version) - 7; i >= 1; i--, pos -= step) {
      result[i] = pos;
    }
    return result;
  }

  private static void drawFormatBits(
      boolean[][] modules, boolean[][] isFunction, int version, int eccLevel, int mask) {
    int data = (eccFormatBits(eccLevel) << 3) | mask;
    int remainder = data;
    for (int i = 0; i < 10; i++) remainder = (remainder << 1) ^ ((remainder >>> 9) * 0x537);
    int bits = ((data << 10) | remainder) ^ 0x5412;
    int size = modules.length;
    // First copy, around the top-left finder.
    for (int i = 0; i <= 5; i++) setFunction(modules, isFunction, 8, i, getBit(bits, i));
    setFunction(modules, isFunction, 8, 7, getBit(bits, 6));
    setFunction(modules, isFunction, 8, 8, getBit(bits, 7));
    setFunction(modules, isFunction, 7, 8, getBit(bits, 8));
    for (int i = 9; i < 15; i++) setFunction(modules, isFunction, 14 - i, 8, getBit(bits, i));
    // Second copy, split between the top-right and bottom-left finders.
    for (int i = 0; i < 8; i++) setFunction(modules, isFunction, size - 1 - i, 8, getBit(bits, i));
    for (int i = 8; i < 15; i++) setFunction(modules, isFunction, 8, size - 15 + i, getBit(bits, i));
    setFunction(modules, isFunction, 8, size - 8, true); // dark module
  }

  private static int eccFormatBits(int eccLevel) {
    return switch (eccLevel) {
      case ECC_LOW -> 1;
      case ECC_MEDIUM -> 0;
      case ECC_QUARTILE -> 3;
      default -> 2;
    };
  }

  private static boolean getBit(int value, int index) {
    return ((value >>> index) & 1) != 0;
  }

  private static void drawVersionBits(boolean[][] modules, boolean[][] isFunction, int version) {
    if (version < 7) return;
    int remainder = version;
    for (int i = 0; i < 12; i++) remainder = (remainder << 1) ^ ((remainder >>> 11) * 0x1F25);
    int bits = (version << 12) | remainder;
    int size = modules.length;
    for (int i = 0; i < 18; i++) {
      boolean bit = getBit(bits, i);
      int a = size - 11 + i % 3;
      int b = i / 3;
      setFunction(modules, isFunction, a, b, bit);
      setFunction(modules, isFunction, b, a, bit);
    }
  }

  // ------------------------------------------------------------------ data placement / masking

  private static void drawCodewords(boolean[][] modules, boolean[][] isFunction, byte[] codewords) {
    int size = modules.length;
    int index = 0;
    int totalBits = codewords.length * 8;
    for (int right = size - 1; right >= 1; right -= 2) {
      if (right == 6) right = 5; // skip the vertical timing column
      for (int vertical = 0; vertical < size; vertical++) {
        for (int j = 0; j < 2; j++) {
          int x = right - j;
          boolean upward = ((right + 1) & 2) == 0;
          int y = upward ? size - 1 - vertical : vertical;
          if (!isFunction[y][x] && index < totalBits) {
            modules[y][x] = getBit(codewords[index >>> 3], 7 - (index & 7));
            index++;
          }
        }
      }
    }
    if (index != totalBits) throw new IllegalStateException("QR data placement mismatch");
  }

  private static void applyMask(boolean[][] modules, boolean[][] isFunction, int mask) {
    int size = modules.length;
    for (int y = 0; y < size; y++) {
      for (int x = 0; x < size; x++) {
        if (isFunction[y][x]) continue;
        boolean invert =
            switch (mask) {
              case 0 -> (x + y) % 2 == 0;
              case 1 -> y % 2 == 0;
              case 2 -> x % 3 == 0;
              case 3 -> (x + y) % 3 == 0;
              case 4 -> (x / 3 + y / 2) % 2 == 0;
              case 5 -> x * y % 2 + x * y % 3 == 0;
              case 6 -> (x * y % 2 + x * y % 3) % 2 == 0;
              default -> ((x + y) % 2 + x * y % 3) % 2 == 0;
            };
        if (invert) modules[y][x] = !modules[y][x];
      }
    }
  }

  /** Penalty score (ISO/IEC 18004 rules N1-N4) used for automatic mask selection. */
  private static int penaltyScore(boolean[][] modules) {
    int size = modules.length;
    int penalty = 0;
    // Rule 1: runs of five or more same-coloured modules, in rows and in columns.
    for (int y = 0; y < size; y++) {
      int run = 1;
      for (int x = 1; x < size; x++) {
        if (modules[y][x] == modules[y][x - 1]) {
          run++;
        } else {
          if (run >= 5) penalty += 3 + (run - 5);
          run = 1;
        }
      }
      if (run >= 5) penalty += 3 + (run - 5);
    }
    for (int x = 0; x < size; x++) {
      int run = 1;
      for (int y = 1; y < size; y++) {
        if (modules[y][x] == modules[y - 1][x]) {
          run++;
        } else {
          if (run >= 5) penalty += 3 + (run - 5);
          run = 1;
        }
      }
      if (run >= 5) penalty += 3 + (run - 5);
    }
    // Rule 2: 2x2 blocks of the same colour.
    for (int y = 0; y < size - 1; y++) {
      for (int x = 0; x < size - 1; x++) {
        boolean value = modules[y][x];
        if (value == modules[y][x + 1]
            && value == modules[y + 1][x]
            && value == modules[y + 1][x + 1]) {
          penalty += 3;
        }
      }
    }
    // Rule 3: finder-like 1:1:3:1:1 patterns with four light modules on either side.
    int[] forward = {1, 0, 1, 1, 1, 0, 1, 0, 0, 0, 0};
    int[] reverse = {0, 0, 0, 0, 1, 0, 1, 1, 1, 0, 1};
    for (int y = 0; y < size; y++) {
      for (int x = 0; x + forward.length <= size; x++) {
        if (matchesRow(modules, x, y, forward) || matchesRow(modules, x, y, reverse)) penalty += 40;
      }
    }
    for (int x = 0; x < size; x++) {
      for (int y = 0; y + forward.length <= size; y++) {
        if (matchesColumn(modules, x, y, forward) || matchesColumn(modules, x, y, reverse)) {
          penalty += 40;
        }
      }
    }
    // Rule 4: balance of dark modules (10 points per 5% deviation from 50%).
    int dark = 0;
    for (boolean[] row : modules) {
      for (boolean value : row) {
        if (value) dark++;
      }
    }
    int total = size * size;
    penalty += Math.abs(dark * 100 / total - 50) / 5 * 10;
    return penalty;
  }

  private static boolean matchesRow(boolean[][] modules, int startX, int y, int[] pattern) {
    for (int i = 0; i < pattern.length; i++) {
      if (modules[y][startX + i] != (pattern[i] != 0)) return false;
    }
    return true;
  }

  private static boolean matchesColumn(boolean[][] modules, int x, int startY, int[] pattern) {
    for (int i = 0; i < pattern.length; i++) {
      if (modules[startY + i][x] != (pattern[i] != 0)) return false;
    }
    return true;
  }
}
