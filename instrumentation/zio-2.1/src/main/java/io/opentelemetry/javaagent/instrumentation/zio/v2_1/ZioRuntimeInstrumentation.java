package io.opentelemetry.javaagent.instrumentation.zio.v2_1;

import static net.bytebuddy.matcher.ElementMatchers.named;

import io.opentelemetry.javaagent.bootstrap.zio.v2_1.FiberLocalContextHelper;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import io.opentelemetry.javaagent.instrumentation.zio.common.v2_1.ZioContextSingleton;
import java.lang.reflect.Method;
import java.util.function.Supplier;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;

public class ZioRuntimeInstrumentation implements TypeInstrumentation {

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("zio.Runtime$");
  }

  @Override
  public void transform(TypeTransformer transformer) {
    transformer.applyAdviceToMethod(
        named("default").or(named("apply")), this.getClass().getName() + "$RuntimeAdvice");
  }

  @SuppressWarnings("unused")
  public static final class RuntimeAdvice {
    private RuntimeAdvice() {}

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object runtime) {
      FiberLocalContextHelper.initialize(
          ZioContextSingleton.contextThreadLocal, new IsUnderFiberContextSupplier());
      runtime = enableCurrentFiber(runtime);
    }

    public static Object enableCurrentFiber(Object runtime) {
      if (runtime == null) {
        return null;
      }
      try {
        Class<?> runtimeClass = Class.forName("zio.Runtime");
        Class<?> runtimeCompanionClass = Class.forName("zio.Runtime$");
        Class<?> runtimeFlagsClass = Class.forName("zio.RuntimeFlags");
        Class<?> runtimeFlagClass = Class.forName("zio.RuntimeFlag");
        Class<?> currentFiberClass = Class.forName("zio.RuntimeFlag$CurrentFiber$");

        Method runtimeFlags = runtimeClass.getMethod("runtimeFlags");
        int flags = (Integer) runtimeFlags.invoke(runtime);
        Object currentFiber = currentFiberClass.getField("MODULE$").get(null);
        if ((Boolean) runtimeFlagsClass.getMethod("currentFiber", int.class).invoke(null, flags)) {
          return runtime;
        }

        int enabledFlags =
            (Integer)
                runtimeFlagsClass
                    .getMethod("enable", int.class, runtimeFlagClass)
                    .invoke(null, flags, currentFiber);
        Object runtimeCompanion = runtimeCompanionClass.getField("MODULE$").get(null);
        return runtimeCompanionClass
            .getMethod(
                "apply",
                Class.forName("zio.ZEnvironment"),
                Class.forName("zio.FiberRefs"),
                int.class)
            .invoke(
                runtimeCompanion,
                runtimeClass.getMethod("environment").invoke(runtime),
                runtimeClass.getMethod("fiberRefs").invoke(runtime),
                enabledFlags);
      } catch (Throwable ignored) {
        return runtime;
      }
    }
  }

  public static final class IsUnderFiberContextSupplier implements Supplier<Boolean> {
    @Override
    public Boolean get() {
      try {
        Class<?> fiberCompanionClass = Class.forName("zio.Fiber$");
        Object fiberCompanion = fiberCompanionClass.getField("MODULE$").get(null);
        Method currentFiber = fiberCompanionClass.getMethod("_currentFiber");
        Object threadLocal = currentFiber.invoke(fiberCompanion);
        return threadLocal instanceof ThreadLocal && ((ThreadLocal<?>) threadLocal).get() != null;
      } catch (Throwable ignored) {
        return false;
      }
    }
  }
}
