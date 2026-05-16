package net.ded3ec.utils;

public class TpsManager {

  /** Sample size for Calculate the average tps. Number of tick samples to keep for averaging. */
  private static final int sampleSize = 1000;

  /** Tick array cached. Stores the timestamps of recent ticks. */
  private static final long[] tickTimes = new long[sampleSize];

  /** Tick count. Current index in the tickTimes array. */
  private static int tickIndex = 0;

  /** Check if filled. Indicates if the tickTimes array has been fully populated. */
  private static boolean filled = false;

  /**
   * Tick event handler by End Server Tick event in the Minecraft Server. Called on each server tick
   * to record the timestamp.
   */
  public static void onTick() {
    long now = System.nanoTime();
    tickTimes[tickIndex] = now;
    tickIndex = (tickIndex + 1) % sampleSize;

    if (tickIndex == 0) filled = true;
  }

  /**
   * Fetch TPS value with calculation. Calculates TPS by measuring the time elapsed over the sample
   * size. If not enough samples, returns 20.0 (default TPS).
   *
   * @return the calculated TPS
   */
  public static double get() {
    if (!filled) return 20.0;

    int lastIndex = (tickIndex + sampleSize - 1) % sampleSize;
    long first = tickTimes[tickIndex];
    long last = tickTimes[lastIndex];
    double elapsedSec = (last - first) / 1_000_000_000.0;

    return sampleSize / elapsedSec;
  }
}
