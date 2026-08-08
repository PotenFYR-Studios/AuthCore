package net.ded3ec.util;

import org.apache.commons.lang3.time.DurationFormatUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Time Converter for raw milliseconds to human-readable! Provides utilities to convert milliseconds
 * into readable duration or date strings.
 */
public class TimeManager {

  /**
   * Converts ms to a Duration (e.g., "1 hour 15 minutes"). Uses Apache Commons DurationFormatUtils
   * to format the duration in words.
   *
   * @param millis the milliseconds to convert
   * @return the formatted duration string
   */
  public static String toDuration(long millis) {
    // "true, true" suppresses leading zeros and uses word representation
    return DurationFormatUtils.formatDurationWords(millis, true, true);
  }

  /**
   * Converts ms to a Timestamp/Date (e.g., "15 Dec 2025, 10:30 AM"). Formats the milliseconds into
   * a human-readable date and time string.
   *
   * @param millis the milliseconds to convert
   * @return the formatted date string
   */
  public static String toHumanDate(long millis) {
    DateTimeFormatter formatter =
        DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a").withZone(ZoneId.systemDefault());
    return formatter.format(Instant.ofEpochMilli(millis));
  }
}
