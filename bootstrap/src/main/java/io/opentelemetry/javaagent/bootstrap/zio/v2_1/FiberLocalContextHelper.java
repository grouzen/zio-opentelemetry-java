package io.opentelemetry.javaagent.bootstrap.zio.v2_1;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Logger;

public final class FiberLocalContextHelper {

  private static final Logger logger = Logger.getLogger(FiberLocalContextHelper.class.getName());

  private static final AtomicReference<ThreadLocal<Context>> fiberContextThreadLocal =
      new AtomicReference<>();

  private static final AtomicReference<Supplier<Boolean>> isUnderFiberContextSupplier =
      new AtomicReference<>(() -> false);

  public static void initialize(
      ThreadLocal<Context> fiberThreadLocal, Supplier<Boolean> isUnderFiberContext) {
    if (fiberContextThreadLocal.compareAndSet(null, fiberThreadLocal)) {
      isUnderFiberContextSupplier.set(isUnderFiberContext);
      logger.fine("ZIO OpenTelemetry fiber context bridge initialized");
    } else if (!fiberContextThreadLocal.get().equals(fiberThreadLocal)) {
      logger.warning(
          "ZIO OpenTelemetry fiber context bridge already has a ThreadLocal; skipping replacement.");
    }
  }

  public static Boolean isUnderFiberContext() {
    return isUnderFiberContextSupplier.get().get();
  }

  public static Context current() {
    ThreadLocal<Context> local = fiberContextThreadLocal.get();
    return local != null ? local.get() : null;
  }

  public static Scope attach(Context toAttach) {
    ThreadLocal<Context> local = fiberContextThreadLocal.get();
    if (toAttach == null || local == null) {
      return Scope.noop();
    }
    Context beforeAttach = current();
    if (toAttach == beforeAttach) {
      return Scope.noop();
    }
    local.set(toAttach);
    return new ScopeImpl(beforeAttach, toAttach);
  }

  private static class ScopeImpl implements Scope {
    private final Context beforeAttach;
    private final Context toAttach;
    private boolean closed;

    ScopeImpl(Context beforeAttach, Context toAttach) {
      this.beforeAttach = beforeAttach;
      this.toAttach = toAttach;
    }

    @Override
    public void close() {
      if (!closed && current() == toAttach) {
        closed = true;
        fiberContextThreadLocal.get().set(beforeAttach);
      } else {
        logger.fine(
            "Ignoring stale ZIO OpenTelemetry fiber scope close because it is not current.");
      }
    }
  }

  private FiberLocalContextHelper() {}
}
