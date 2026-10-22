# zio-opentelemetry-javaagent — Initial Plan (Superseded)

> **This plan has been split into two separate documents:**
>
> 1. **[Plan A: zio-telemetry Modifications](plan-zio-telemetry.md)** — Changes to the existing `zio/zio-telemetry` repo (new agent module, Logger updates, layer constructors)
> 2. **[Plan B: zio-opentelemetry-java](plan-zio-opentelemetry-java.md)** — New Gradle-based Java agent distribution repo (bootstrap, instrumentation, assembly, tests)
>
> **Execution dependency**: Plan A Step 1 must complete before Plan B Step 5 (zio-telemetry integration module). All other steps can proceed in parallel.
>
> This file is kept for historical reference. The split plans are the source of truth.

---

An experimental OpenTelemetry Java agent distribution with ZIO fiber-aware context propagation. This project mirrors the architecture of `otel4s-opentelemetry-java` but targets ZIO instead of Cats Effect.

## Goal

Solve **zio-telemetry issue #1069** — context loss across ZIO fiber boundaries when using the OpenTelemetry Java agent.

## Architecture Decisions

| Decision | Choice |
|----------|--------|
| Agent distribution | Custom Gradle distribution (fork-style, like otel4s-opentelemetry-java) |
| Context storage | `ThreadLocalBridge` pattern (supervisor + FiberRef + ThreadLocal sync) |
| Integration point | Separate agent module in zio-telemetry (`modules/opentelemetry/agent/`) |
| Fork instrumentation | Internal fiber creation (`FiberRuntime` constructor) |

## High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                       ZIO Application                               │
│                                                                     │
│  ┌──────────────────────────┐   ┌──────────────────────────────┐    │
│  │  zio-telemetry (Scala)   │   │  Java OTel Auto-instrumented │    │
│  │                          │   │  libraries (HTTP, gRPC, etc) │    │
│  │  tracer.span("child")    │   │  httpClient.send(req)        │    │
│  │       │                  │   │       │                      │    │
│  │       v                  │   │       v                      │    │
│  │  FiberRef.get            │   │  Context.current()           │    │
│  │  FiberRef.locally(ctx)   │   │  context.makeCurrent()       │    │
│  └──────┬───────────────────┘   └──────┬───────────────────────┘    │
│         │                              │                            │
│         │    ┌─────────────────────┐    │                            │
│         └───>│  FiberContextBridge │<───┘                            │
│              │  (ContextStorage    │                                 │
│              │   wrapper)          │                                 │
│              └─────────┬───────────┘                                 │
│                        │                                             │
│         ┌──────────────┼──────────────┐                              │
│         v                             v                              │
│  ┌──────────────────┐   ┌──────────────────────┐                    │
│  │  In fiber:        │   │  Not in fiber:       │                    │
│  │  FiberLocal       │   │  Agent's default     │                    │
│  │  ContextHelper    │   │  ThreadLocal storage │                    │
│  │       │           │   └──────────────────────┘                    │
│  │       v           │                                               │
│  │  Bridging         │                                               │
│  │  ThreadLocal      │                                               │
│  │  (FiberRef-       │                                               │
│  │   backed view)    │                                               │
│  └──────────────────┘                                               │
└─────────────────────────────────────────────────────────────────────┘

ThreadLocalBridge Supervisor (installed by agent module):
  onSuspend(fiber): ctx.makeCurrent(Context.root())  -- clean up thread
  onResume(fiber):  ctx.makeCurrent(fiber.getFiberRef(contextRef))  -- restore
```

## Data Flow: End-to-End Context Propagation

```
1. JVM Startup
   └─> FiberContextBridgeInstaller.beforeAgent()
       └─> ContextStorage.addWrapper(FiberContextBridge::new)
           [Agent's ContextStorage is now wrapped with FiberContextBridge]

2. Application starts ZIO Runtime
   └─> ZioRuntimeInstrumentation (intercepts Runtime.Unsafe initialization)
       └─> FiberLocalContextHelper.initialize(
               ZioContextSingleton.contextThreadLocal,
               () -> Fiber._currentFiber.get() != null)
       └─> Installs ThreadLocalBridge supervisor on the Runtime
           [Bootstrap classloader now holds reference to ZIO's bridged ThreadLocal]

3. Application uses zio-telemetry agent module
   └─> ZioAgentContextInstrumentation (intercepts ZioAgentContext$.getAgentFiberRef)
       └─> Returns Some(ZioContextSingleton.contextFiberRef)
           [zio-telemetry discovers the agent's FiberRef and uses it as ContextStorage]

4. Fiber fork (any fork path: fork/forkDaemon/race/timeout)
   └─> ZioFiberInstrumentation (intercepts FiberRuntime constructor)
       └─> Captures Context.current() from parent
           Sets child fiber's FiberRef via fiber.setFiberRef(contextRef, capturedContext)
           [Child fiber starts with parent's context in its FiberRef]

5. Inside a fiber: Context.current() / context.makeCurrent()
   └─> FiberContextBridge.current() / attach()
       └─> isUnderFiberContext() == true
           └─> FiberLocalContextHelper.current() / attach()
               └─> ZioContextSingleton.contextThreadLocal.get() / set()
                   └─> Checks Fiber._currentFiber.get()
                       └─> fiber != null: delegates to fiber.getFiberRef(contextRef)
                       └─> fiber == null: delegates to standard ThreadLocal
                           [Context is stored per-fiber via FiberRef, not per-thread]
```

---

## Phase 1: zio-telemetry Agent Module

**Location**: `modules/opentelemetry/agent/` in the zio-telemetry repo
**Published as**: `dev.zio:zio-opentelemetry-agent_2.13:4.x.x`

### 1.1 New SBT Module Definition

**File**: `build.sbt` (add to existing build)

```scala
lazy val opentelemetryAgent = project
  .in(file("modules/opentelemetry/agent"))
  .settings(
    name := "zio-opentelemetry-agent",
    // ... standard settings
  )
  .dependsOn(opentelemetryCore)
```

**File**: `project/Dependencies.scala` (add agent dependencies)

```scala
lazy val opentelemetryAgent = Seq(
  "dev.zio" %% "zio" % zioVersion,
  "io.opentelemetry" % "opentelemetry-api" % openTelemetryVersion,
  "io.opentelemetry" % "opentelemetry-sdk" % openTelemetryVersion % Test,
  "io.opentelemetry" % "opentelemetry-sdk-testing" % openTelemetryVersion % Test
)
```

### 1.2 `FiberRefContextStorage` — New ContextStorage Implementation

**File**: `modules/opentelemetry/agent/src/main/scala/zio/telemetry/opentelemetry/agent/FiberRefContextStorage.scala`

A `ContextStorage` that uses `FiberRef[Context]` for fiber-scoped storage AND keeps the Java ThreadLocal in sync via the ThreadLocalBridge pattern.

```scala
final class FiberRefContextStorage(
  private[opentelemetry] val ref: FiberRef[Context]
) extends ContextStorage {

  override def get(implicit trace: Trace): UIO[Context] =
    ref.get

  override def locally[R, E, A](ctx: Context)(zio: => ZIO[R, E, A])(implicit
    trace: Trace
  ): ZIO[R, E, A] =
    ref.locally(ctx)(zio)

  override def locallyScoped(ctx: Context)(implicit trace: Trace): ZIO[Scope, Nothing, Unit] =
    ref.locallyScoped(ctx)
}
```

Key difference from existing `ZIOFiberRef`: This class is the target of the Java agent's bytecode instrumentation. The agent replaces the FiberRef with its own singleton instance that is connected to the ThreadLocalBridge.

### 1.3 `ZioAgentContext` — Agent-Facing API (Instrumentation Hook)

**File**: `modules/opentelemetry/agent/src/main/scala/zio/telemetry/opentelemetry/agent/ZioAgentContext.scala`

This is the equivalent of otel4s's `IOLocalContextStorage$.getAgentLocalContext()`. The Java agent instruments this object to inject its singleton `FiberRef[Context]`.

```scala
object ZioAgentContext {

  /**
   * Returns the agent-provided FiberRef[Context], if the agent is attached.
   * This method is instrumented by the zio-opentelemetry-javaagent at runtime.
   * Without the agent, it returns None.
   */
  private[agent] def getAgentFiberRef(): Option[FiberRef[Context]] = None

  /**
   * Checks whether the OpenTelemetry Java agent with ZIO support is attached.
   */
  def isAgentAttached: Boolean = getAgentFiberRef().isDefined
}
```

### 1.4 `OpenTelemetry` Layer Constructors

**File**: `modules/opentelemetry/agent/src/main/scala/zio/telemetry/opentelemetry/agent/OpenTelemetry.scala`

Provides layer constructors for agent-aware integration.

```scala
object OpenTelemetry {

  /**
   * Creates an OpenTelemetry instance backed by the Java agent's FiberRef context storage.
   * Requires the zio-opentelemetry-javaagent to be attached.
   *
   * Falls back to JavaOtelThreadLocal if the agent is not detected (with a warning).
   */
  def global(logAnnotated: Boolean = false)(implicit trace: Trace): TaskLayer[core.OpenTelemetry] =
    ZLayer.scoped {
      for {
        underlying <- ZIO.attempt(GlobalOpenTelemetry.get())
        propagator  = ContextPropagator.fromJava(underlying.getPropagators)
        storage    <- createStorage
      } yield core.OpenTelemetry.make(storage, underlying, propagator, logAnnotated)
    }

  private def createStorage(implicit trace: Trace): URIO[Scope, ContextStorage] =
    ZioAgentContext.getAgentFiberRef() match {
      case Some(fiberRef) =>
        ZIO.logInfo("zio-opentelemetry-agent: agent-provided FiberRef detected") *>
          ZIO.succeed(new FiberRefContextStorage(fiberRef))
      case None =>
        ZIO.logWarning("zio-opentelemetry-agent: agent not detected, falling back to JavaOtelThreadLocal") *>
          ZIO.succeed(ContextStorage.JavaOtelThreadLocal)
    }

  /**
   * Installs the ThreadLocalBridge supervisor for OTel context synchronization.
   * Must be added to the ZIO Runtime for proper suspend/resume handling.
   */
  def installContextBridge: ZLayer[Any, Nothing, Unit] =
    ZLayer.scoped {
      for {
        fiberRef <- FiberRef.make[Context](Context.root())
        _        <- installSupervisor(fiberRef)
      } yield ()
    }

  private def installSupervisor(fiberRef: FiberRef[Context]): ZIO[Scope, Nothing, Unit] = {
    val link: Context => Unit = ctx => { ctx.makeCurrent(); () }
    val supervisor = new Supervisor[Unit] {
      override def value(implicit trace: Trace): UIO[Unit] = ZIO.unit
      override def onStart[R, E, A](...): UIO[Unit] = ZIO.unit
      override def onEnd[R, E, A](...): UIO[Unit] = ZIO.unit
      override def onSuspend[E, A](fiber: Fiber.Runtime[E, A])(implicit unsafe: Unsafe): Unit =
        link(Context.root())
      override def onResume[E, A](fiber: Fiber.Runtime[E, A])(implicit unsafe: Unsafe): Unit = {
        val ctx = fiber.asInstanceOf[FiberRuntime[E, A]].getFiberRef(fiberRef)
        link(ctx)
      }
    }
    Supervisor.addSupervisor(supervisor)
  }
}
```

### 1.5 Update Existing Logger/LogFormats Pattern Match

**Files**:
- `modules/opentelemetry/core/src/main/scala/zio/telemetry/opentelemetry/core/logs/Logger.scala`
- `modules/opentelemetry/zio-logging/src/main/scala/zio/telemetry/opentelemetry/zio/logging/LogFormats.scala`

Both files currently pattern-match on `ContextStorage`:

```scala
ctxStorage match {
  case cs: ContextStorage.ZIOFiberRef             => fiberRefs.get(cs.ref)
  case _: ContextStorage.JavaOtelThreadLocal.type => Context.current()
}
```

Add a case for the new `FiberRefContextStorage`:

```scala
ctxStorage match {
  case cs: ContextStorage.ZIOFiberRef      => fiberRefs.get(cs.ref)
  case cs: FiberRefContextStorage          => fiberRefs.get(cs.ref)
  case _: ContextStorage.JavaOtelThreadLocal.type => Context.current()
}
```

---

## Phase 2: Java Agent Distribution

**Location**: New Gradle project (e.g., `zio-opentelemetry-java/`)

### 2.1 Project Structure

```
zio-opentelemetry-java/
├── agent/                                      # Final agent JAR assembly
│   └── build.gradle
├── bootstrap/                                  # Bootstrap classloader classes
│   ├── build.gradle
│   └── src/main/java/
│       └── io/opentelemetry/javaagent/bootstrap/zio/v2_1/
│           └── FiberLocalContextHelper.java
├── custom/                                     # Distribution resource provider
│   ├── build.gradle
│   └── src/main/java/
│       └── .../ZioDistributionResourceProvider.java
├── instrumentation/
│   ├── zio-common-2.1/                        # Shared: ZioContextSingleton
│   │   ├── build.gradle
│   │   └── src/main/java/
│   │       └── .../ZioContextSingleton.java
│   ├── zio-2.1/                               # Core ZIO instrumentation
│   │   ├── build.gradle
│   │   ├── src/main/java/
│   │   │   └── .../
│   │   │       ├── ZioInstrumentationModule.java
│   │   │       ├── ZioRuntimeInstrumentation.java
│   │   │       ├── FiberContextBridge.java
│   │   │       ├── FiberContextBridgeInstaller.java
│   │   │       └── ZioFiberInstrumentation.java
│   │   └── src/test/scala/
│   │       └── .../ZioInstrumentationTest.scala
│   └── zio-telemetry-agent-4.0/               # zio-telemetry agent module integration
│       ├── build.gradle
│       ├── src/main/java/
│       │   └── .../
│       │       ├── ZioTelemetryInstrumentationModule.java
│       │       └── ZioAgentContextInstrumentation.java
│       └── src/test/scala/
│           └── .../ZioTelemetryInstrumentationTest.scala
├── opentelemetry-api-shaded-for-instrumenting/ # Shaded OTel API (application.* namespace)
│   └── build.gradle
├── testing/
│   └── agent-for-testing/                     # Test agent JAR assembly
│       └── build.gradle
├── smoke-tests/                               # Docker-based smoke tests
│   └── build.gradle
├── smoke-tests-images/
│   └── zio-http/                              # ZIO HTTP sample app
│       └── build.gradle
├── gradle/
│   ├── instrumentation.gradle                 # Shared instrumentation build logic
│   └── shadow.gradle                          # Package relocation rules
├── build.gradle                               # Root build config
├── settings.gradle                            # Subproject declarations
└── gradle.properties
```

### 2.2 Bootstrap: `FiberLocalContextHelper`

**File**: `bootstrap/src/main/java/io/opentelemetry/javaagent/bootstrap/zio/v2_1/FiberLocalContextHelper.java`

Identical structure to the otel4s version (`otel4s-opentelemetry-java/bootstrap/.../FiberLocalContextHelper.java`). Lives in the bootstrap classloader.

```java
package io.opentelemetry.javaagent.bootstrap.zio.v2_1;

public final class FiberLocalContextHelper {

  private static final AtomicReference<ThreadLocal<Context>> fiberContextThreadLocal = new AtomicReference<>();
  private static final AtomicReference<Supplier<Boolean>> isUnderFiberContextSupplier =
      new AtomicReference<>(() -> false);

  public static void initialize(ThreadLocal<Context> fiberThreadLocal, Supplier<Boolean> isUnderFiberContext) {
    if (fiberContextThreadLocal.compareAndSet(null, fiberThreadLocal)) {
      isUnderFiberContextSupplier.set(isUnderFiberContext);
    }
  }

  public static Boolean isUnderFiberContext() { return isUnderFiberContextSupplier.get().get(); }
  public static Context current() { ... }
  public static Scope attach(Context toAttach) { ... }

  // ScopeImpl inner class (same as otel4s version)
}
```

**Dependencies**: Only `io.opentelemetry:opentelemetry-api` (compileOnly) — needs `io.opentelemetry.context.Context`.

### 2.3 Common: `ZioContextSingleton`

**File**: `instrumentation/zio-common-2.1/src/main/java/.../ZioContextSingleton.java`

Creates the singleton `FiberRef[Context]` and its ThreadLocal view. This is the equivalent of `IoLocalContextSingleton`.

```java
package io.opentelemetry.javaagent.instrumentation.zio.common.v2_1;

public class ZioContextSingleton {

  // The singleton FiberRef[io.opentelemetry.context.Context] (application/unshaded)
  // Created via: Unsafe.unsafe(u => FiberRef.unsafeMake(Context.root()))
  public static final Object contextFiberRef;

  // ThreadLocal view backed by the FiberRef
  // Created via: fiberRef.asThreadLocal (with enableCurrentFiber check)
  public static final ThreadLocal<application.io.opentelemetry.context.Context> fiberRefThreadLocal;

  // Bridging ThreadLocal that converts between agent (shaded) and application (unshaded) Context
  public static final ThreadLocal<io.opentelemetry.context.Context> contextThreadLocal;

  static {
    Unsafe unsafe = Unsafe.unsafe(identity());
    contextFiberRef = FiberRef.unsafeMake(Context.root(), unsafe);
    fiberRefThreadLocal = ((FiberRef<Context>) contextFiberRef).asThreadLocal(Trace.empty, unsafe);
    contextThreadLocal = new ThreadLocal<>() {
      @Override
      public io.opentelemetry.context.Context get() {
        application.io.opentelemetry.context.Context appCtx = fiberRefThreadLocal.get();
        return AgentContextStorage.getAgentContext(appCtx);
      }
      @Override
      public void set(io.opentelemetry.context.Context value) {
        application.io.opentelemetry.context.Context appCtx = AgentContextStorage.toApplicationContext(value);
        fiberRefThreadLocal.set(appCtx);
      }
    };
  }
}
```

**Dependencies**: Shaded OTel API module, `dev.zio:zio_2.13:2.1.25`.

### 2.4 Core ZIO Instrumentation Module

#### 2.4.1 `FiberContextBridge`

**File**: `instrumentation/zio-2.1/src/main/java/.../FiberContextBridge.java`

Wraps the agent's `ContextStorage`, routing to ZIO's FiberRef when inside a fiber.

```java
public class FiberContextBridge implements ContextStorage {
  private final ContextStorage agentContextStorage;

  public FiberContextBridge(ContextStorage delegate) {
    this.agentContextStorage = delegate;
  }

  @Override
  public Scope attach(Context toAttach) {
    if (FiberLocalContextHelper.isUnderFiberContext())
      return FiberLocalContextHelper.attach(toAttach);
    else
      return agentContextStorage.attach(toAttach);
  }

  @Override
  public Context current() {
    if (FiberLocalContextHelper.isUnderFiberContext())
      return FiberLocalContextHelper.current();
    else
      return agentContextStorage.current();
  }
}
```

#### 2.4.2 `FiberContextBridgeInstaller`

**File**: `instrumentation/zio-2.1/src/main/java/.../FiberContextBridgeInstaller.java`

SPI hook that installs the bridge before the agent initializes.

```java
@AutoService(BeforeAgentListener.class)
public class FiberContextBridgeInstaller implements BeforeAgentListener {
  @Override
  public void beforeAgent() {
    ContextStorage.addWrapper(FiberContextBridge::new);
  }
}
```

#### 2.4.3 `ZioRuntimeInstrumentation`

**File**: `instrumentation/zio-2.1/src/main/java/.../ZioRuntimeInstrumentation.java`

Instruments ZIO Runtime initialization to register the bridging ThreadLocal with the bootstrap helper. Targets `zio.Runtime$Unsafe` (or equivalent initialization point).

```java
public class ZioRuntimeInstrumentation implements TypeInstrumentation {
  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("zio.Runtime");
  }

  @Override
  public void transform(TypeTransformer transformer) {
    transformer.applyAdviceToMethod(
      named("unsafe").or(named("apply")),
      this.getClass().getName() + "$RuntimeAdvice");
  }

  public static final class RuntimeAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit() {
      FiberLocalContextHelper.initialize(
        ZioContextSingleton.contextThreadLocal,
        () -> Fiber._currentFiber.get() != null
      );
    }
  }
}
```

Note: The exact class/method to instrument needs refinement. Candidates:
- `zio.Runtime.Unsafe.apply()` — the unsafe runtime accessor
- `zio.Runtime.default` — the default runtime lazy val
- `zio.internal.FiberRuntime` constructor — early enough to catch all fibers

#### 2.4.4 `ZioFiberInstrumentation` — Fork Point Interception

**File**: `instrumentation/zio-2.1/src/main/java/.../ZioFiberInstrumentation.java`

Instruments `FiberRuntime` constructor to propagate context from parent to child fiber. This catches ALL fork paths (fork, forkDaemon, race, timeout, etc.) since they all create a `FiberRuntime`.

```java
public class ZioFiberInstrumentation implements TypeInstrumentation {
  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("zio.internal.FiberRuntime");
  }

  @Override
  public void transform(TypeTransformer transformer) {
    transformer.applyAdviceToMethod(
      isConstructor(),
      this.getClass().getName() + "$ConstructorAdvice");
  }

  public static final class ConstructorAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.This Object self) {
      // self is a FiberRuntime instance
      // Capture current context from parent (via FiberContextBridge)
      application.io.opentelemetry.context.Context current = Context.current();
      // Set child fiber's FiberRef to parent's context
      ((zio.Fiber.Runtime<?, ?>) self).setFiberRef(
        ZioContextSingleton.contextFiberRef, current);
    }
  }
}
```

**Important considerations**:
- `FiberRuntime.setFiberRef` is `private[zio]` — accessible via reflection or by matching ZIO's package access
- `Context.current()` on the advice line is the application (unshaded) Context, imported from the shaded-for-instrumenting module
- `@Advice.OnMethodExit` is used (not OnMethodEnter) because we need the `self` reference to the constructed FiberRuntime

**Alternative approach** (if `setFiberRef` is not accessible): Instrument `FiberRefs.forkAs` to patch the child's FiberRefs with the current context.

#### 2.4.5 `ZioInstrumentationModule`

**File**: `instrumentation/zio-2.1/src/main/java/.../ZioInstrumentationModule.java`

```java
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
      new ZioFiberInstrumentation()
    );
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return hasClassesNamed("zio.ZIO")
      .and(hasClassesNamed("zio.internal.FiberRuntime"));
  }

  @Override
  public boolean defaultEnabled(ConfigProperties config) {
    return super.defaultEnabled(config)
      && config.getBoolean("zio.trackFiberContext", true);  // enabled by default
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
```

### 2.5 zio-telemetry Agent Integration

#### 2.5.1 `ZioAgentContextInstrumentation`

**File**: `instrumentation/zio-telemetry-agent-4.0/src/main/java/.../ZioAgentContextInstrumentation.java`

Instruments `ZioAgentContext$.getAgentFiberRef()` to return the agent's singleton FiberRef. This is the equivalent of `IoLocalContextStorageInstrumentation`.

```java
public class ZioAgentContextInstrumentation implements TypeInstrumentation {
  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("zio.telemetry.opentelemetry.agent.ZioAgentContext$");
  }

  @Override
  public void transform(TypeTransformer transformer) {
    transformer.applyAdviceToMethod(
      named("getAgentFiberRef"),
      this.getClass().getName() + "$GetAgentFiberRefAdvice");
  }

  public static final class GetAgentFiberRefAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.Return(readOnly = false)
        scala.Option<zio.FiberRef<application.io.opentelemetry.context.Context>> result) {
      result = scala.Option.apply(ZioContextSingleton.contextFiberRef);
    }
  }
}
```

#### 2.5.2 `ZioTelemetryInstrumentationModule`

```java
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
```

### 2.6 Package Relocation (Shadow/Shading)

**File**: `gradle/shadow.gradle`

Same relocation strategy as otel4s-opentelemetry-java:
- Agent's copy of `io.opentelemetry.api`, `io.opentelemetry.context` → shaded to `io.opentelemetry.javaagent.shaded.*`
- `application.io.opentelemetry` → reverse-shaded back to `io.opentelemetry` in final JAR

This allows instrumentation code to reference the application's (unshaded) OpenTelemetry API classes.

### 2.7 Agent Assembly

**File**: `agent/build.gradle`

3-step process (same as otel4s):
1. **Relocate** javaagent libs (shadow JAR with package relocation)
2. **Isolate** relocated libs into `inst/` directory with `.classdata` extension
3. **Merge** with bootstrap libs and upstream agent JAR → `zio-opentelemetry-javaagent.jar`

---

## Phase 3: Testing

### 3.1 Unit Tests — Core ZIO Instrumentation

**File**: `instrumentation/zio-2.1/src/test/scala/.../ZioInstrumentationTest.scala`

Tests using raw OTel Java API (`Span`, `tracer.spanBuilder`) with the agent attached.

Test scenarios:
1. **Context propagation across `fork`**: Parent span visible in child fiber
2. **Context propagation across `forkDaemon`**: Daemon fibers inherit context
3. **Context propagation across `race`**: Race winner's context is correct
4. **Context propagation across `timeout`**: Timeout fiber inherits context
5. **Nested fibers**: Deep fiber nesting preserves context chain
6. **Fiber interruption**: Context survives fiber interruption
7. **Concurrent fibers**: Parallel fibers don't interfere with each other's traces
8. **Sequential fibers**: Sequential forks maintain correct parent-child relationships
9. **Non-fiber context**: Context outside fibers (plain threads) still works
10. **Dispatcher interop**: Context works with `ZIO.fromFuture` / `ZIO.fromCompletableFuture`

### 3.2 Unit Tests — zio-telemetry Agent Integration

**File**: `instrumentation/zio-telemetry-agent-4.0/src/test/scala/.../ZioTelemetryInstrumentationTest.scala`

Tests using zio-telemetry's `Tracer` API with the agent attached.

Test scenarios (same as above, plus):
11. **otel4s-equivalent span tests**: All scenarios from `CatsEffectInstrumentationTest` ported to ZIO
12. **Context propagation tests**: Bidirectional context flow between Java `Context` and ZIO FiberRef
13. **`autoinstrumented` compatibility**: Verify the method still works (backward compat)
14. **Baggage propagation**: Baggage survives fiber boundaries
15. **Metrics context**: Metrics record with correct context in fibers

### 3.3 Smoke Tests (Docker-based)

**File**: `smoke-tests/src/test/java/.../ZioHttpSmokeTest.java`

Uses Testcontainers:
- ZIO HTTP server with agent attached
- Fake OTel backend (OTLP collector)
- Verifies: traceparent propagation, span creation, span attributes, distribution resource attributes

**Sample app**: `smoke-tests-images/zio-http/` — a ZIO HTTP service with:
- Manual spans via `zio-telemetry`
- Auto-instrumented HTTP client calls
- Log correlation via Logback MDC

**Version matrix** (`smoke-test-versions.gradle`):
- zio-telemetry baseline: `4.0.0-RC`
- ZIO baseline: `2.1.25`

---

## Phase 4: Build Configuration

### 4.1 Root `build.gradle`

```groovy
group = 'dev.zio'
version = '2.28.1'  // match upstream OTel agent version

ext {
  otelSdkVersion = '1.62.0'
  otelAgentVersion = '2.28.1'
  zioVersion = '2.1.25'
}

allprojects {
  java { toolchain { languageVersion = JavaLanguageVersion.of(8) } }
}
```

### 4.2 `settings.gradle`

```groovy
rootProject.name = 'zio-opentelemetry-java'
include ':opentelemetry-api-shaded-for-instrumenting'
include ':agent'
include ':bootstrap'
include ':custom'
include ':instrumentation:zio-common-2.1'
include ':instrumentation:zio-2.1'
include ':instrumentation:zio-telemetry-agent-4.0'
include ':smoke-tests-images:zio-http'
include ':smoke-tests'
include ':testing:agent-for-testing'
```

### 4.3 `gradle/instrumentation.gradle`

Shared build logic for instrumentation modules:
- Applies `muzzle-generation` and `muzzle-check` plugins for version compatibility validation
- Configures test JVM with `-javaagent:agent-for-testing.jar`
- Enables context leak detection: `otel.javaagent.testing.context-leak-detection=true`
- Sets `zio.trackFiberContext=true` system property for tests

---

## Challenges & Risk Mitigation

| Challenge | Risk | Mitigation |
|-----------|------|------------|
| `FiberRuntime` constructor signature may change across ZIO versions | High | Use `muzzle-check` plugin for version validation. Pin to ZIO 2.1.x initially. Add multiple version matchers. |
| `setFiberRef` is `private[zio]` — may not be accessible from agent | Medium | Test accessibility. Fallback: instrument `FiberRefs.forkAs` instead, or use reflection. |
| `Runtime.enableCurrentFiber` must be enabled for `_currentFiber` to work | High | Agent auto-enables it via `ZioRuntimeInstrumentation` advice. Document the requirement. |
| ThreadLocalBridge supervisor adds overhead to every fiber transition | Low | Measure performance impact. The supervisor only does ThreadLocal reads/writes (fast). |
| ZIO has many internal fork points beyond `FiberRuntime` constructor | Medium | The constructor approach catches ALL forks since every fiber becomes a `FiberRuntime`. Verify with exhaustive tests. |
| Fiber interruption may leave context in inconsistent state | Medium | `ZIO.acquireReleaseWith` in zio-telemetry handles span lifecycle. The supervisor resets context on suspend. |
| Agent classloader cannot reference ZIO types | Low (known pattern) | Use only Java types in bootstrap. ZIO types only in instrumentation classloader. Same pattern as otel4s. |
| Scala 2.12 vs 2.13 vs 3 binary compatibility | Medium | Build instrumentation against Scala 2.13 ZIO. ZIO is binary compatible across Scala versions at the bytecode level for the instrumented methods. |

---

## Implementation Order

1. **Phase 1.1-1.3**: Create zio-telemetry agent module structure, `FiberRefContextStorage`, `ZioAgentContext`
2. **Phase 2.1-2.2**: Set up Gradle project, build config, bootstrap `FiberLocalContextHelper`
3. **Phase 2.3**: Implement `ZioContextSingleton`
4. **Phase 2.4.1-2.4.2**: Implement `FiberContextBridge` + `FiberContextBridgeInstaller`
5. **Phase 2.4.3**: Implement `ZioRuntimeInstrumentation` (register bridge)
6. **Phase 2.4.4**: Implement `ZioFiberInstrumentation` (fork interception)
7. **Phase 2.4.5**: Wire up `ZioInstrumentationModule`
8. **Phase 3.1**: Write and validate core ZIO instrumentation tests
9. **Phase 2.5**: Implement zio-telemetry agent integration (`ZioAgentContextInstrumentation`)
10. **Phase 1.4-1.5**: Update zio-telemetry Logger/LogFormats, add layer constructors
11. **Phase 3.2**: Write and validate integration tests
12. **Phase 2.6-2.7**: Set up shading, agent assembly
13. **Phase 3.3**: Smoke tests
14. **Phase 4**: Polish build config, CI setup

---

## Key Files to Reference

| What | Source |
|------|--------|
| Bootstrap helper pattern | `otel4s-opentelemetry-java/bootstrap/.../FiberLocalContextHelper.java` |
| Context singleton pattern | `otel4s-opentelemetry-java/instrumentation/cats-effect-common-3.6/.../IoLocalContextSingleton.java` |
| FiberContextBridge | `otel4s-opentelemetry-java/instrumentation/cats-effect-3.6/.../FiberContextBridge.java` |
| Fork instrumentation | `otel4s-opentelemetry-java/instrumentation/cats-effect-3.6/.../IoFiberInstrumentation.java` |
| Module registration | `otel4s-opentelemetry-java/instrumentation/cats-effect-3.6/.../CatsEffectInstrumentationModule.java` |
| otel4s integration | `otel4s-opentelemetry-java/instrumentation/otel4s-0.13/.../IoLocalContextStorageInstrumentation.java` |
| Existing ContextStorage | `zio-telemetry/modules/opentelemetry/core/.../ContextStorage.scala` |
| Existing agent integration | `zio-telemetry/modules/opentelemetry/main/.../OpenTelemetry.scala` (`OpenTelemetry.global()`) |
| ZIO FiberRef.asThreadLocal | ZIO source: `zio/FiberRef.scala` lines 276-305 |
| ZIO ThreadLocalBridge | ZIO source: `zio/ThreadLocalBridge.scala` |
| ZIO _currentFiber | ZIO source: `zio/Fiber.scala` lines 1055-1063 |
| Test patterns | `otel4s-opentelemetry-java/instrumentation/cats-effect-3.6/src/test/.../CatsEffectInstrumentationTest.scala` |

## Reference Material

### otel4s (Cats Effect) vs ZIO Mapping

| otel4s (Cats Effect) | ZIO Equivalent | Status |
|---|---|---|
| `IOLocal.unsafeThreadLocal()` | `FiberRef.asThreadLocal` | ✅ Available (since ZIO 2.x) |
| `IORuntime.isUnderFiberContext()` | `Fiber._currentFiber.get() != null` | ✅ Available |
| `ioLocal.scope(io, context)` | `fiberRef.locally(context)(zio)` | ✅ Available |
| Agent instrumentation on `IOFiber` | Agent instrumentation on `FiberRuntime` | ⚠️ Needs implementation |

### Context Storage Comparison

```
=== ZIOFiberRef (existing) ===
Used with: OpenTelemetry.custom(...)  [manual mode]

User code:
  tracer.span("child") { ... }
       |
       v
  FiberRef.get --> Context (ZIO-native, fiber-scoped)
       |
       v
  FiberRef.locally(newCtx)(zio)  --> context propagates on fork ✅

Java agent auto-instrumentation:
  httpClient.send(request)
       |
       v
  Context.current() --> reads Java ThreadLocal --> NOT the FiberRef! ❌
  Agent creates span --> stores in ThreadLocal --> NOT visible to ZIO! ❌
  Logback MDC --> reads ThreadLocal --> trace_id NOT available! ❌

VERDICT: Great for pure ZIO apps. Broken when Java agent is attached.


=== JavaOtelThreadLocal (existing) ===
Used with: OpenTelemetry.global()  [agent mode]

User code:
  tracer.span("child") { ... }
       |
       v
  Context.current() --> reads Java ThreadLocal
       |
       v
  ctx.makeCurrent() --> sets Java ThreadLocal
       |
       v
  On fiber fork --> ThreadLocal NOT propagated to child fiber! ❌ (#1069)

Java agent auto-instrumentation:
  httpClient.send(request)
       |
       v
  Context.current() --> reads ThreadLocal --> sees ZIO's span ✅

VERDICT: Java agent works, but fiber fork loses context.


=== FiberRefThreadLocalBridge (new, proposed) ===
Used with: OpenTelemetry.global() + agent JAR  [agent mode, fixed]

User code:
  tracer.span("child") { ... }
       |
       v
  FiberRef.get --> Context (ZIO-native, fiber-scoped)
       |
       v
  FiberRef.locally(newCtx)(zio) --> context propagates on fork ✅
       |
       v
  ThreadLocalBridge syncs real ThreadLocal from FiberRef ✅
       |
       v
  Java agent's FiberContextBridge reads from bridged ThreadLocal ✅

Java agent auto-instrumentation:
  httpClient.send(request)
       |
       v
  Context.current()
       |
       v
  FiberContextBridge.current()
       |
       v
  isUnderFiberContext() == true (we're in a ZIO fiber)
       |
       v
  FiberLocalContextHelper.current()
       |
       v
  Bridged ThreadLocal.get()
       |
       v
  Fiber._currentFiber.get() != null --> delegates to FiberRef ✅
       |
       v
  Returns the correct fiber-scoped Context ✅

Logback MDC:
  MDC.get("trace_id")
       |
       v
  Reads ThreadLocal (set by ThreadLocalBridge.onResume) ✅
```

### ZIO Primitives Reference

#### `FiberRef.asThreadLocal`

```scala
def asThreadLocal(implicit trace: Trace, unsafe: Unsafe): UIO[ThreadLocal[A]] =
  ZIO.succeed {
    new ThreadLocal[A] {
      override def get(): A = {
        val fiber = Fiber._currentFiber.get()
        if (fiber eq null) super.get()
        else fiber.getFiberRef(self)
      }
      override def set(a: A): Unit = {
        val fiber    = Fiber._currentFiber.get()
        val fiberRef = self.asInstanceOf[FiberRef[Any]]
        if (fiber eq null) super.set(a)
        else fiber.setFiberRef(fiberRef, a)
      }
      override def remove(): Unit = {
        val fiber    = Fiber._currentFiber.get()
        val fiberRef = self
        if (fiber eq null) super.remove()
        else fiber.deleteFiberRef(fiberRef)
      }
      override def initialValue(): A = initial
    }
  }
```

Requires `Runtime.enableCurrentFiber` to populate `Fiber._currentFiber`.

#### `ThreadLocalBridge` Supervisor Hooks

```scala
override def onSuspend[E, A1](fiber: Fiber.Runtime[E, A1])(implicit unsafe: Unsafe): Unit =
  foreachTrackedRef { (fiberRef, link) =>
    link(fiberRef.initial)  // Reset ThreadLocal to initial when fiber suspends
  }

override def onResume[E, A1](fiber: Fiber.Runtime[E, A1])(implicit unsafe: Unsafe): Unit =
  foreachTrackedRef { (fiberRef, link) =>
    val value = fiber.asInstanceOf[FiberRuntime[E, A1]].getFiberRef(fiberRef)
    link(value)  // Set ThreadLocal to the resuming fiber's value
  }
```

Hooks fire in `FiberRuntime.evaluateEffect`:
- `onResume` at the START of `evaluateEffect`
- `onSuspend` in the `finally` block of `evaluateEffect`

#### `Fiber._currentFiber`

```scala
private[zio] val _currentFiber: ThreadLocal[Fiber.Runtime[_, _]] =
  new ThreadLocal[Fiber.Runtime[_, _]]()
```

Set by `FiberRuntime.start()` when `RuntimeFlags.currentFiber` is enabled.

#### FiberRef Fork Semantics

```scala
def forkAs(childId: FiberId.Runtime): FiberRefs =
  fiberRefLocals.transform { (fiberRef, entry) =>
    if (fiberRef.hasIdentityFork) entry  // child inherits parent value as-is
    else {
      val oldValue = stack.head.value
      val newValue = fiberRef.patch(fiberRef.fork)(oldValue)  // apply fork patch
      ...
    }
  }
```

Default `fork: A => A = identity[A]` — child fiber inherits parent's current FiberRef value unchanged.
