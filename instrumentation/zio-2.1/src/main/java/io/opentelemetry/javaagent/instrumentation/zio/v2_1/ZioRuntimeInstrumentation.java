package io.opentelemetry.javaagent.instrumentation.zio.v2_1;

import static net.bytebuddy.matcher.ElementMatchers.named;

import io.opentelemetry.javaagent.bootstrap.zio.v2_1.FiberLocalContextHelper;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import io.opentelemetry.javaagent.instrumentation.zio.common.v2_1.ZioContextSingleton;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
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
    public static void onExit() {
      FiberLocalContextHelper.initialize(
          ZioContextSingleton.contextThreadLocal,
          () -> {
            try {
              Class<?> fiberClass = Class.forName("zio.Fiber");
              Method currentFiber = fiberClass.getMethod("_currentFiber");
              Object threadLocal = currentFiber.invoke(null);
              return threadLocal instanceof ThreadLocal
                  && ((ThreadLocal<?>) threadLocal).get() != null;
            } catch (Throwable ignored) {
              return false;
            }
          });
    }
  }
}
