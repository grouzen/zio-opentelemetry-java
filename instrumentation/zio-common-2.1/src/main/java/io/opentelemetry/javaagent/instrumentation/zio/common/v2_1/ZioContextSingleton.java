package io.opentelemetry.javaagent.instrumentation.zio.common.v2_1;

import application.io.opentelemetry.context.Context;
import io.opentelemetry.javaagent.instrumentation.opentelemetryapi.context.AgentContextStorage;
import java.lang.reflect.Method;

public final class ZioContextSingleton {

  public static final Object contextFiberRef;

  public static final ThreadLocal<Context> fiberRefThreadLocal;

  public static final ThreadLocal<io.opentelemetry.context.Context> contextThreadLocal;

  static {
    try {
      Class<?> unsafeClass = Class.forName("zio.Unsafe");
      Class<?> unsafeCompanionClass = Class.forName("zio.Unsafe$");
      Class<?> fiberRefClass = Class.forName("zio.FiberRef");

      Object unsafeCompanion = unsafeCompanionClass.getField("MODULE$").get(null);
      Object unsafe = invokeZeroArg(unsafeCompanion, "unsafe");

      contextFiberRef =
          fiberRefClass
              .getMethod("unsafeMake", Object.class, unsafeClass)
              .invoke(null, Context.root(), unsafe);

      @SuppressWarnings("unchecked")
      ThreadLocal<Context> threadLocal =
          (ThreadLocal<Context>) invokeAsThreadLocal(fiberRefClass, contextFiberRef, unsafe);
      fiberRefThreadLocal = threadLocal;

      contextThreadLocal =
          new ThreadLocal<io.opentelemetry.context.Context>() {
            @Override
            public io.opentelemetry.context.Context get() {
              Context applicationContext = fiberRefThreadLocal.get();
              return AgentContextStorage.getAgentContext(applicationContext);
            }

            @Override
            public void set(io.opentelemetry.context.Context value) {
              if (value == null) {
                fiberRefThreadLocal.remove();
              } else {
                fiberRefThreadLocal.set(AgentContextStorage.toApplicationContext(value));
              }
            }

            @Override
            public void remove() {
              fiberRefThreadLocal.remove();
            }
          };
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static Object invokeZeroArg(Object target, String methodName) throws Exception {
    Method method = target.getClass().getMethod(methodName);
    return method.invoke(target);
  }

  private static Object invokeAsThreadLocal(Class<?> fiberRefClass, Object fiberRef, Object unsafe)
      throws Exception {
    Class<?> traceClass = Class.forName("zio.Trace");
    Object trace = defaultTrace(traceClass);
    Method asThreadLocal = null;
    for (Method method : fiberRefClass.getMethods()) {
      if (method.getName().equals("asThreadLocal") && method.getParameterCount() == 2) {
        asThreadLocal = method;
        break;
      }
    }
    if (asThreadLocal == null) {
      throw new NoSuchMethodException("zio.FiberRef.asThreadLocal");
    }
    return asThreadLocal.invoke(fiberRef, trace, unsafe);
  }

  private static Object defaultTrace(Class<?> traceClass) {
    try {
      return traceClass.getField("empty").get(null);
    } catch (Exception ignored) {
      return null;
    }
  }

  private ZioContextSingleton() {}
}
