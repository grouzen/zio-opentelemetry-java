package io.opentelemetry.javaagent.instrumentation.zio.v2_1;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import io.opentelemetry.context.Context;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class ZioExecutorInstrumentation implements TypeInstrumentation {

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("zio.Executor$Interop");
  }

  @Override
  public void transform(TypeTransformer transformer) {
    transformer.applyAdviceToMethod(
        named("execute").and(takesArgument(0, named("java.lang.Runnable"))),
        this.getClass().getName() + "$ExecuteAdvice");
  }

  @SuppressWarnings("unused")
  public static final class ExecuteAdvice {
    private ExecuteAdvice() {}

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(value = 0, readOnly = false) Runnable command) {
      if (command != null) {
        command = new ContextPropagatingRunnable(command, Context.current());
      }
    }
  }
}
