package io.opentelemetry.javaagent.instrumentation.ziotelemetry.v4_0;

import static net.bytebuddy.matcher.ElementMatchers.named;

import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import io.opentelemetry.javaagent.instrumentation.zio.common.v2_1.ZioContextSingleton;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
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

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object result) {
      // Keep zio-telemetry on its local FiberRef storage until the shared bridge is safe to enable.
    }

    public static Object wrapFiberRefBridge(Object fiberRef) {
      try {
        Class<?> bridgeModuleClass =
            Class.forName(
                "io.opentelemetry.javaagent.instrumentation.ziotelemetry.v4_0.ContextFiberRefBridge$");
        Object bridgeModule = bridgeModuleClass.getField("MODULE$").get(null);
        return bridgeModuleClass.getMethod("wrap", Object.class).invoke(bridgeModule, fiberRef);
      } catch (Exception e) {
        throw new IllegalStateException("Failed to wrap ZIO agent FiberRef for zio-telemetry", e);
      }
    }
  }
}
