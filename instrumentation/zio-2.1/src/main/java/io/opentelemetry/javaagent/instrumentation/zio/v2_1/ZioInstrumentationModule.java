package io.opentelemetry.javaagent.instrumentation.zio.v2_1;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;

import com.google.auto.service.AutoService;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.internal.ExperimentalInstrumentationModule;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import java.util.Arrays;
import java.util.List;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumentationModule.class)
public class ZioInstrumentationModule extends InstrumentationModule
    implements ExperimentalInstrumentationModule {

  public ZioInstrumentationModule() {
    super("zio", "zio-2.1");
  }

  @Override
  public List<TypeInstrumentation> typeInstrumentations() {
    return Arrays.asList(
        new ZioRuntimeInstrumentation(),
        new ZioFiberInstrumentation(),
        new ZioExecutorInstrumentation());
  }

  @Override
  public List<String> getAdditionalHelperClassNames() {
    return Arrays.asList(
        "io.opentelemetry.javaagent.instrumentation.zio.common.v2_1.ZioContextSingleton",
        "io.opentelemetry.javaagent.instrumentation.zio.common.v2_1.ZioContextSingleton$FiberRefThreadLocal",
        "io.opentelemetry.javaagent.instrumentation.zio.v2_1.ZioRuntimeInstrumentation$RuntimeAdvice",
        "io.opentelemetry.javaagent.instrumentation.zio.v2_1.ZioFiberInstrumentation$ConstructorAdvice",
        "io.opentelemetry.javaagent.instrumentation.zio.v2_1.ZioExecutorInstrumentation$ExecuteAdvice",
        "io.opentelemetry.javaagent.instrumentation.zio.v2_1.ContextPropagatingRunnable",
        "io.opentelemetry.javaagent.instrumentation.zio.v2_1.ZioRuntimeInstrumentation$IsUnderFiberContextSupplier");
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return hasClassesNamed("zio.ZIO").and(hasClassesNamed("zio.internal.FiberRuntime"));
  }

  @Override
  public boolean defaultEnabled(ConfigProperties config) {
    return super.defaultEnabled(config) && config.getBoolean("zio.trackFiberContext", true);
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
