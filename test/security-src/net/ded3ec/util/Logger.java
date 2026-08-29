package net.ded3ec.util;

/**
 * Standalone stub used by the security test harness. It shadows the real Logger class (which
 * requires Minecraft) so pure-logic components can be tested without a server.
 */
public class Logger {

  public Logger(String modId) {}

  public <T> T debug(T value, String message, Object... args) {
    return value;
  }

  public <T> T info(T value, String message, Object... args) {
    return value;
  }

  public <T> T warn(T value, String message, Object... args) {
    return value;
  }

  public <T> T error(T value, String message, Object... args) {
    return value;
  }

  public <T> T toUser(T value, Object ignored, String message, Object... args) {
    return value;
  }
}
