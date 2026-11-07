package in.potenfyr.authcore.util;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded cache for reflection lookups used on hot paths (packet handlers, per-packet guards).
 *
 * <p>{@code Class#getMethod} walks the class hierarchy and allocates on every call - at packet
 * rate that is measurable CPU and garbage. The lookup result is keyed by
 * {@code class + name + arity} and cached forever: the key space is bounded by the packet/handler
 * classes of the runtime, so this cache can never grow with player count.
 */
public final class ReflectCache {

  private static final ConcurrentHashMap<String, Optional<Method>> METHODS =
      new ConcurrentHashMap<>();

  /**
   * Returns the public method, cached. Throws {@link NoSuchMethodException} when absent so callers
   * keep their existing reflective fallbacks.
   */
  public static Method method(Class<?> type, String name, Class<?>... parameterTypes)
      throws NoSuchMethodException {
    if (type == null || name == null) throw new NoSuchMethodException("null type/name");
    // The key must include the parameter TYPES: with only the arity, method(X, "f", int.class) and
    // method(X, "f", String.class) shared one cache slot and the second caller got the wrong method.
    StringBuilder keyBuilder = new StringBuilder(64).append(type.getName()).append('#').append(name);
    for (Class<?> p : parameterTypes) keyBuilder.append('/').append(p == null ? "?" : p.getName());
    String key = keyBuilder.toString();
    Optional<Method> cached = METHODS.get(key);
    if (cached == null) {
      Method found = null;
      try {
        found = type.getMethod(name, parameterTypes);
      } catch (NoSuchMethodException absent) {
        // cached as absent below
      }
      cached = Optional.ofNullable(found);
      METHODS.put(key, cached);
    }
    if (cached.isEmpty()) throw MISSING;
    return cached.get();
  }

  /** Invokes a cached no-arg method. */
  public static Object invoke(Object target, String name) throws ReflectiveOperationException {
    if (target == null) throw new NoSuchMethodException("null target");
    return method(target.getClass(), name).invoke(target);
  }

  /** Number of cached lookups (diagnostics/self-test). */
  /**
   * Shared, stackless "absent" signal. Negative lookups are cached, but every miss still built a
   * fresh NoSuchMethodException (stack walk + allocation) - on the per-packet guard paths that is
   * one exception per packet for every name a runtime does not have.
   */
  private static final NoSuchMethodException MISSING =
      new NoSuchMethodException("absent (cached)") {
        @Override
        public synchronized Throwable fillInStackTrace() {
          return this;
        }
      };

  public static int size() {
    return METHODS.size();
  }

  private ReflectCache() {}
}
