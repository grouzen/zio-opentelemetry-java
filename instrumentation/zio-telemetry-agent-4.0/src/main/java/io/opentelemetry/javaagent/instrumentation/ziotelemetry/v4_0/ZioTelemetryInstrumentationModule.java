package io.opentelemetry.javaagent.instrumentation.ziotelemetry.v4_0;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;

import com.google.auto.service.AutoService;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.internal.ExperimentalInstrumentationModule;
import java.util.Collections;
import java.util.List;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumentationModule.class)
public class ZioTelemetryInstrumentationModule extends InstrumentationModule
    implements ExperimentalInstrumentationModule {

  public ZioTelemetryInstrumentationModule() {
    super("zio-telemetry-agent", "zio-telemetry-agent-4.0");
  }

  @Override
  public List<TypeInstrumentation> typeInstrumentations() {
    return Collections.singletonList(new ZioAgentContextInstrumentation());
  }

  @Override
  public List<String> getAdditionalHelperClassNames() {
    return Collections.singletonList(
        "io.opentelemetry.javaagent.instrumentation.ziotelemetry.v4_0.ZioAgentContextInstrumentation$GetAgentFiberRefAdvice");
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return hasClassesNamed("zio.telemetry.opentelemetry.agent.ZioAgentContext$");
  }

  @Override
  public String getModuleGroup() {
    return "opentelemetry-api-bridge";
  }

  @Override
  public int order() {
    return Integer.MAX_VALUE;
  }
}
