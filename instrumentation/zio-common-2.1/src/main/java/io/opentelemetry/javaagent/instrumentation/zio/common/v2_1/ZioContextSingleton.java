package io.opentelemetry.javaagent.instrumentation.zio.common.v2_1;

import java.lang.reflect.Method;

public final class ZioContextSingleton {

  private static final Object agentRootContext;

  public static final Object contextFiberRef;

  @SuppressWarnings("rawtypes")
  public static final ThreadLocal contextThreadLocal;

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
      agentRootContext =
          toAgentContext(invokeStaticZeroArg("io.opentelemetry.context.Context", "root"));
      contextFiberRef =
          fiberRefUnsafeClass
              .getMethod(
                  "make",
                  Object.class,
                  Class.forName("scala.Function1"),
                  Class.forName("scala.Function2"),
                  unsafeClass)
              .invoke(fiberRefUnsafe, agentRootContext, fork, join, unsafe);

      contextThreadLocal = createFiberRefThreadLocal(contextFiberRef);
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static Object invokeZeroArg(Object target, String methodName) throws Exception {
    Method method = target.getClass().getMethod(methodName);
    return method.invoke(target);
  }

  private static Object invokeStaticZeroArg(String className, String methodName) throws Exception {
    Method method = Class.forName(className).getMethod(methodName);
    return method.invoke(null);
  }

  private static ThreadLocal<Object> createFiberRefThreadLocal(Object fiberRef) throws Exception {
    Class<?> fiberCompanionClass = Class.forName("zio.Fiber$");
    Object fiberCompanion = fiberCompanionClass.getField("MODULE$").get(null);
    @SuppressWarnings("unchecked")
    ThreadLocal<Object> currentFiber =
        (ThreadLocal<Object>) fiberCompanionClass.getMethod("_currentFiber").invoke(fiberCompanion);

    return new ThreadLocal<Object>() {
      @Override
      public Object get() {
        Object fiber = currentFiber.get();
        if (fiber == null) {
          return super.get();
        }
        try {
          Object context =
              fiber.getClass()
                  .getMethod("getFiberRef", Class.forName("zio.FiberRef"))
                  .invoke(fiber, fiberRef);
          return toAgentContext(context);
        } catch (Exception e) {
          throw new IllegalStateException("Failed to read ZIO FiberRef context", e);
        }
      }

      @Override
      public void set(Object value) {
        Object fiber = currentFiber.get();
        try {
          Object agentContext = toAgentContext(value);
          if (fiber == null) {
            super.set(agentContext);
            return;
          }
          fiber
              .getClass()
              .getMethod("setFiberRef", Class.forName("zio.FiberRef"), Object.class)
              .invoke(fiber, fiberRef, agentContext);
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
          fiber
              .getClass()
              .getMethod("deleteFiberRef", Class.forName("zio.FiberRef"))
              .invoke(fiber, fiberRef);
        } catch (Exception e) {
          throw new IllegalStateException("Failed to remove ZIO FiberRef context", e);
        }
      }

      @Override
      protected Object initialValue() {
        return agentRootContext;
      }
    };
  }

  public static Object toAgentContext(Object value) throws Exception {
    if (value == null) {
      return agentRootContext;
    }
    try {
      Method toAgentContext = value.getClass().getDeclaredMethod("toAgentContext");
      toAgentContext.setAccessible(true);
      return toAgentContext.invoke(value);
    } catch (NoSuchMethodException ignored) {
      if (value.getClass().getName().startsWith("io.opentelemetry.context.")) {
        return invokeAgentContextStorage("getAgentContext", value);
      }
      return value;
    }
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
            // The helper is injected into the application loader after agent packaging.
          }
        }
      }
      throw new NoSuchMethodException(methodName + "(" + argument.getClass().getName() + ")");
    } catch (Exception e) {
      throw new IllegalStateException("Failed to bridge OpenTelemetry context", e);
    }
  }

  private ZioContextSingleton() {}
}
