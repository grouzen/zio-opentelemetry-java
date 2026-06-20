package io.opentelemetry.javaagent.instrumentation.zio.v2_1;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.named;

import io.opentelemetry.context.Context;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import io.opentelemetry.javaagent.instrumentation.zio.common.v2_1.ZioContextSingleton;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class ZioFiberInstrumentation implements TypeInstrumentation {

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("zio.internal.FiberRuntime");
  }

  @Override
  public void transform(TypeTransformer transformer) {
    transformer.applyAdviceToMethod(
        isConstructor(), this.getClass().getName() + "$ConstructorAdvice");
  }

  @SuppressWarnings("unused")
  public static final class ConstructorAdvice {
    private ConstructorAdvice() {}

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.This Object self) {
      try {
        Object currentContext = ZioContextSingleton.toAgentContext(Context.current());
        Method setFiberRef =
            self.getClass().getMethod("setFiberRef", Class.forName("zio.FiberRef"), Object.class);
        setFiberRef.invoke(self, ZioContextSingleton.contextFiberRef, currentContext);
      } catch (Throwable ignored) {
        // ZIO internals differ across patch releases. Later validation can retarget
        // FiberRefs.forkAs.
      }
    }
  }
}
