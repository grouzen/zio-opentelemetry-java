package io.opentelemetry.javaagent.instrumentation.zio.v2_1;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

public final class ContextPropagatingRunnable implements Runnable {
  private final Runnable delegate;
  private final Context context;

  public ContextPropagatingRunnable(Runnable delegate, Context context) {
    this.delegate = delegate;
    this.context = context;
  }

  @Override
  public void run() {
    try (Scope ignored = context.makeCurrent()) {
      delegate.run();
    }
  }
}
