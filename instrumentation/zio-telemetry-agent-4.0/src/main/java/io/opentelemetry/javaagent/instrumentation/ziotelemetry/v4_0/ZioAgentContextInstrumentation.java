package io.opentelemetry.javaagent.instrumentation.ziotelemetry.v4_0;

import static net.bytebuddy.matcher.ElementMatchers.named;

import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import io.opentelemetry.javaagent.instrumentation.zio.common.v2_1.ZioContextSingleton;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class ZioAgentContextInstrumentation implements TypeInstrumentation {

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("zio.telemetry.opentelemetry.agent.ZioAgentContext$");
  }

  @Override
  public void transform(TypeTransformer transformer) {
    transformer.applyAdviceToMethod(
        named("getAgentFiberRef"), this.getClass().getName() + "$GetAgentFiberRefAdvice");
  }

  @SuppressWarnings("unused")
  public static final class GetAgentFiberRefAdvice {
    private GetAgentFiberRefAdvice() {}

    @Advice.OnMethodExit
    public static void onExit(@Advice.Return(readOnly = false) scala.Option<?> result) {
      result = scala.Option.apply(ZioContextSingleton.contextFiberRef);
    }
  }
}
