package io.opentelemetry.javaagent.instrumentation.zio.v2_1;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextStorage;
import io.opentelemetry.context.Scope;
import io.opentelemetry.javaagent.bootstrap.zio.v2_1.FiberLocalContextHelper;

public class FiberContextBridge implements ContextStorage {

  private final ContextStorage agentContextStorage;

  public FiberContextBridge(ContextStorage delegate) {
    this.agentContextStorage = delegate;
  }

  @Override
  public Scope attach(Context toAttach) {
    if (FiberLocalContextHelper.isUnderFiberContext()) {
      return FiberLocalContextHelper.attach(toAttach);
    }
    return agentContextStorage.attach(toAttach);
  }

  @Override
  public Context current() {
    if (FiberLocalContextHelper.isUnderFiberContext()) {
      return FiberLocalContextHelper.current();
    }
    return agentContextStorage.current();
  }
}
