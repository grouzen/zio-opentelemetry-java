package io.opentelemetry.javaagent.instrumentation.zio.common.v2_1;

import application.io.opentelemetry.context.Context;
import java.lang.reflect.Method;

public final class ZioContextSingleton {

  public static final Object contextFiberRef;

  public static final ThreadLocal<Context> fiberRefThreadLocal;

  public static final ThreadLocal<io.opentelemetry.context.Context> contextThreadLocal;

  static {
    try {
      Class<?> unsafeClass = Class.forName("zio.Unsafe");
      Class<?> unsafeCompanionClass = Class.forName("zio.Unsafe$");
      Class<?> fiberRefUnsafeClass = Class.forName("zio.FiberRef$unsafe$");

      Object unsafeCompanion = unsafeCompanionClass.getField("MODULE$").get(null);
      Object unsafe = invokeZeroArg(unsafeCompanion, "unsafe");

      Object fiberRefUnsafe = fiberRefUnsafeClass.getField("MODULE$").get(null);
      Object fork = fiberRefUnsafeClass.getMethod("make$default$2").invoke(fiberRefUnsafe);
      Object join = fiberRefUnsafeClass.getMethod("make$default$3").invoke(fiberRefUnsafe);
      contextFiberRef =
          fiberRefUnsafeClass
              .getMethod("make", Object.class, Class.forName("scala.Function1"), Class.forName("scala.Function2"), unsafeClass)
              .invoke(fiberRefUnsafe, Context.root(), fork, join, unsafe);

      fiberRefThreadLocal = createFiberRefThreadLocal(contextFiberRef);

      contextThreadLocal =
          new ThreadLocal<io.opentelemetry.context.Context>() {
            @Override
            public io.opentelemetry.context.Context get() {
              Context applicationContext = fiberRefThreadLocal.get();
              return (io.opentelemetry.context.Context)
                  invokeAgentContextStorage("getAgentContext", applicationContext);
            }

            @Override
            public void set(io.opentelemetry.context.Context value) {
              if (value == null) {
                fiberRefThreadLocal.remove();
              } else {
                fiberRefThreadLocal.set(
                    (Context) invokeAgentContextStorage("toApplicationContext", value));
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

  private static Object invokeAgentContextStorage(String methodName, Object argument) {
    try {
      Class<?> storageClass =
          Class.forName(
              "io.opentelemetry.javaagent.instrumentation.opentelemetryapi.context.AgentContextStorage");
      for (Method method : storageClass.getMethods()) {
        if (method.getName().equals(methodName) && method.getParameterCount() == 1) {
          try {
            return method.invoke(null, argument);
          } catch (IllegalArgumentException ignored) {
            // The helper is relocated differently before and after agent packaging.
          }
        }
      }
      throw new NoSuchMethodException(methodName + "(" + argument.getClass().getName() + ")");
    } catch (Exception e) {
      throw new IllegalStateException("Failed to bridge OpenTelemetry context", e);
    }
  }

  private static ThreadLocal<Context> createFiberRefThreadLocal(Object fiberRef) throws Exception {
    Class<?> fiberCompanionClass = Class.forName("zio.Fiber$");
    Object fiberCompanion = fiberCompanionClass.getField("MODULE$").get(null);
    @SuppressWarnings("unchecked")
    ThreadLocal<Object> currentFiber =
        (ThreadLocal<Object>) fiberCompanionClass.getMethod("_currentFiber").invoke(fiberCompanion);

    return new ThreadLocal<Context>() {
      @Override
      public Context get() {
        Object fiber = currentFiber.get();
        if (fiber == null) {
          return super.get();
        }
        try {
          @SuppressWarnings("unchecked")
          Context context =
              (Context) fiber.getClass().getMethod("getFiberRef", Class.forName("zio.FiberRef")).invoke(fiber, fiberRef);
          return context;
        } catch (Exception e) {
          throw new IllegalStateException("Failed to read ZIO FiberRef context", e);
        }
      }

      @Override
      public void set(Context value) {
        Object fiber = currentFiber.get();
        if (fiber == null) {
          super.set(value);
          return;
        }
        try {
          fiber
              .getClass()
              .getMethod("setFiberRef", Class.forName("zio.FiberRef"), Object.class)
              .invoke(fiber, fiberRef, value);
        } catch (Exception e) {
          throw new IllegalStateException("Failed to set ZIO FiberRef context", e);
        }
      }

      @Override
      public void remove() {
        Object fiber = currentFiber.get();
        if (fiber == null) {
          super.remove();
          return;
        }
        try {
          fiber.getClass().getMethod("deleteFiberRef", Class.forName("zio.FiberRef")).invoke(fiber, fiberRef);
        } catch (Exception e) {
          throw new IllegalStateException("Failed to remove ZIO FiberRef context", e);
        }
      }

      @Override
      protected Context initialValue() {
        return Context.root();
      }
    };
  }

  private ZioContextSingleton() {}
}
