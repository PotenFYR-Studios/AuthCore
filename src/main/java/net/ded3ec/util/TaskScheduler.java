package net.ded3ec.util;

import net.ded3ec.AuthCoreServer;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lightweight tick-based task scheduler that runs callbacks on the server thread. Tasks are
 * scheduled in milliseconds and converted to ticks using the nominal 50 ms per tick ratio, so they
 * fire at the correct wall-clock time regardless of the measured TPS.
 */
public class TaskScheduler {
  private static final TaskScheduler INSTANCE = new TaskScheduler();
  private static final long MILLIS_PER_TICK = 50L;

  private final Map<Integer, ScheduledTask> tasks = new ConcurrentHashMap<>();
  private final AtomicInteger idCounter = new AtomicInteger(0);
  private volatile long tickCounter = 0;

  /**
   * Returns the singleton instance of the TaskScheduler.
   *
   * @return TaskScheduler instance
   */
  public static TaskScheduler getInstance() {
    return INSTANCE;
  }

  /**
   * Advances the tick counter and executes any tasks that are due. This method should be called
   * once per server tick. Tasks are executed safely with exception handling.
   */
  public void onTick() {
    tickCounter++;

    for (Iterator<Map.Entry<Integer, ScheduledTask>> it = tasks.entrySet().iterator();
        it.hasNext(); ) {

      Map.Entry<Integer, ScheduledTask> entry = it.next();
      ScheduledTask task = entry.getValue();

      if (tickCounter >= task.nextExecutionTick) {
        try {
          task.callback.run();
        } catch (Exception err) {
          AuthCoreServer.LOGGER.error(false, "[Scheduler] Task " + task.id + " failed: ", err);
        }

        if (task.repeat) task.nextExecutionTick += task.delayTicks;
        else it.remove();
      }
    }
  }

  /**
   * Schedules a one-time task to run after the specified delay.
   *
   * @param callback the task to execute
   * @param delayMs delay in milliseconds before execution
   * @return unique task ID
   */
  public int setTimeout(Runnable callback, long delayMs) {
    int id = idCounter.incrementAndGet();
    long delayTicks = toTicks(delayMs);

    tasks.put(id, new ScheduledTask(id, delayTicks, false, callback, tickCounter + delayTicks));

    return id;
  }

  /**
   * Schedules a repeating task to run at the specified interval.
   *
   * @param callback the task to execute
   * @param intervalMs interval in milliseconds between executions
   * @return unique task ID
   */
  public int setInterval(Runnable callback, long intervalMs) {
    int id = idCounter.incrementAndGet();
    long delayTicks = toTicks(intervalMs);

    tasks.put(id, new ScheduledTask(id, delayTicks, true, callback, tickCounter + delayTicks));

    return id;
  }

  /**
   * Stops a scheduled task by its ID.
   *
   * @param id the task ID to cancel
   */
  public void stopTask(int id) {
    tasks.remove(id);
  }

  /** Converts milliseconds to ticks (at least 1 tick, capped to avoid overflow). */
  private static long toTicks(long millis) {
    if (millis <= 0) return 1;
    long ticks = Math.round((double) millis / MILLIS_PER_TICK);
    return Math.max(1, Math.min(ticks, Integer.MAX_VALUE / 4L));
  }

  /** Represents a scheduled task with its execution metadata. */
  private static class ScheduledTask {
    final int id;
    final long delayTicks;
    final boolean repeat;
    final Runnable callback;
    long nextExecutionTick;

    /**
     * Constructs a new ScheduledTask.
     *
     * @param id unique task ID
     * @param delayTicks delay in ticks between executions
     * @param repeat true if repeating, false if one-time
     * @param callback the task to execute
     * @param nextExecutionTick tick count when the task should next execute
     */
    ScheduledTask(
        int id, long delayTicks, boolean repeat, Runnable callback, long nextExecutionTick) {
      this.id = id;
      this.delayTicks = delayTicks;
      this.repeat = repeat;
      this.callback = callback;
      this.nextExecutionTick = nextExecutionTick;
    }
  }
}
