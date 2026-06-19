package io.opentelemetry.javaagent.instrumentation.zio.v2_1;

import com.google.auto.service.AutoService;
import io.opentelemetry.context.ContextStorage;
import io.opentelemetry.javaagent.tooling.BeforeAgentListener;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;

@AutoService(BeforeAgentListener.class)
public class FiberContextBridgeInstaller implements BeforeAgentListener {
  @Override
  public void beforeAgent(AutoConfiguredOpenTelemetrySdk autoConfiguredOpenTelemetrySdk) {
    ContextStorage.addWrapper(FiberContextBridge::new);
  }
}
