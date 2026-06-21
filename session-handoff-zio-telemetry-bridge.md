# Session Handoff: ZIO Telemetry Bridge / Step 7.2

## Purpose

This file is a handoff for the next session so we do not repeat the same failed bridge experiments and debugging loops.

Repo root:

- `/home/lamdness/.local/share/flowmux/worktrees/zio-opentelemetry-java/init-zio-opentelemetry-java`

## High-Level Outcome

Two different tracks were in play in this session:

1. Step `7.2` integration tests for `zio-telemetry-agent-4.0`
2. Experimental unified shared `FiberRef` bridge between:
   - core javaagent ZIO context storage
   - `zio-telemetry`

Final state at the end of the session:

- `:instrumentation:zio-2.1:test` passes
- `:instrumentation:zio-telemetry-agent-4.0:test` passes
- the shared bridge for `zio-telemetry` is **disabled again**
- `zio-telemetry` is currently using its safe local `FiberRef` storage path
- a design spec for a future safe unified bridge exists in:
  - [unified-fiberref-bridge-spec.md](/home/lamdness/.local/share/flowmux/worktrees/zio-opentelemetry-java/init-zio-opentelemetry-java/unified-fiberref-bridge-spec.md)

The important point: the tests were made green by falling back to the supported local `FiberRef` path, not by making the shared bridge production-safe.

## What Was Successfully Completed

### 1. Step 7.2 test setup

Added test/build wiring for:

- `instrumentation/zio-telemetry-agent-4.0/build.gradle`

This includes:

- Scala plugin
- zio / junit / OpenTelemetry test dependencies
- local Ivy dependencies for `zio-opentelemetry-*`
- test javaagent configuration
- Java 17 launcher

### 2. Integration test suite

Created:

- `instrumentation/zio-telemetry-agent-4.0/src/test/scala/zio/telemetry/opentelemetry/agent/ZioTelemetryInstrumentationTest.scala`

This suite now passes against the supported fallback path.

### 3. Core ZIO javaagent fixes from earlier work remain good

Still good and should not be regressed:

- `instrumentation/zio-common-2.1/.../ZioContextSingleton.java`
- `instrumentation/zio-2.1/.../ZioFiberInstrumentation.java`
- `instrumentation/zio-2.1/.../ZioExecutorInstrumentation.java`
- `instrumentation/zio-2.1/.../ContextPropagatingRunnable.java`
- `instrumentation/zio-2.1/.../ZioInstrumentationModule.java`

These core fixes are real and passing. Do not casually rewrite them while working on `zio-telemetry`.

## Current Safe State

### zio-telemetry agent hook

File:

- `instrumentation/zio-telemetry-agent-4.0/src/main/java/io/opentelemetry/javaagent/instrumentation/ziotelemetry/v4_0/ZioAgentContextInstrumentation.java`

Current behavior:

- instrumentation still transforms `ZioAgentContext$`
- but the advice no longer forces `Some(bridgeFiberRef)`
- it effectively leaves the return value untouched
- this means `ZioAgentContext.getAgentFiberRef()` remains `None`
- `OpenTelemetry.global()` falls back to `ContextStorage.root`

This fallback is intentional at the end of the session because the bridge was not safe.

### zio-telemetry behavior

Current supported behavior is:

- local `FiberRef[application Context]`
- no shared-slot unification with the core javaagent ZIO `FiberRef`

## The Main Bridge Failures We Hit

These are the mistakes to avoid repeating.

### 1. Directly exposing the shared core `FiberRef` as `FiberRef[Context]` was wrong

Bad idea:

- cast the raw shared agent `FiberRef` to `FiberRef[application Context]`
- wrap it with `FiberRef.Proxy`

Why it failed:

- the raw slot stores agent/shaded context objects
- `zio-telemetry` expects application `Context`
- inherited `FiberRef.Proxy` methods still exercised raw delegate semantics with the wrong type assumptions

Observable failure:

- `AgentContextWrapper cannot be cast to shaded Context`

### 2. Storing application contexts directly in the shared slot broke core ZIO

We tried a “direct application context in shared FiberRef” experiment and reverted it.

Why it was bad:

- core ZIO javaagent bridge expects agent-side/shaded context in the shared slot
- putting application `Context` in that slot broke core reads immediately

Observable failure:

- `Failed to read ZIO FiberRef context`
- suppressed `ClassCastException` from shaded context assumptions

Do not retry this unless redesigning from first principles.

### 3. Treating any `io.opentelemetry.javaagent.*` object as already-agent-context was wrong

A major bug in the Scala bridge:

- `AgentContextWrapper` is an application-visible wrapper object
- it is **not** the raw shaded context that the shared slot expects

Bad heuristic:

- “if class name starts with `io.opentelemetry.javaagent.` then store it directly”

Why it failed:

- wrapper leaked into the raw shared `FiberRef`
- later raw reads assumed shaded `Context`

Observable failure:

- `AgentContextWrapper cannot be cast to io.opentelemetry.javaagent.shaded.io.opentelemetry.context.Context`

### 4. Anonymous Scala helper classes were not injected

We had a `NoClassDefFoundError` on:

- `ContextFiberRefBridge$$anon$1`

Cause:

- Scala anonymous helper generated from inline `new Differ[...] { ... }`
- helper injection only covered named classes/objects

Lesson:

- injected Scala helpers must use named top-level or named nested classes/objects only

### 5. Fixing helper injection alone did not make the bridge safe

Even after fixing anonymous helper injection issues, the bridge still failed semantically and at runtime on other paths.

Do not mistake “helper injection fixed” for “bridge safe”.

### 6. Dynamic ByteBuddy return typing was required, but that was only a small wiring fix

Earlier failure:

- `Cannot assign class java.lang.Object to class scala.Option`

Fix:

- `@Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC)`

This fix is valid, but it only solved ByteBuddy return assignment. It did not solve the underlying bridge correctness problem.

### 7. Private helper visibility caused `IllegalAccessError`

Earlier failure:

- advice/helper call to `wrapFiberRefBridge` failed due to visibility

Fix:

- make helper method `public static`

Again: valid local fix, but not the actual bridge solution.

## Files Touched During the Bridge Experiments

These files were part of the unsafe bridge work:

- `instrumentation/zio-telemetry-agent-4.0/src/main/java/io/opentelemetry/javaagent/instrumentation/ziotelemetry/v4_0/ZioAgentContextInstrumentation.java`
- `instrumentation/zio-telemetry-agent-4.0/src/main/java/io/opentelemetry/javaagent/instrumentation/ziotelemetry/v4_0/ZioTelemetryInstrumentationModule.java`
- `instrumentation/zio-telemetry-agent-4.0/src/main/scala/io/opentelemetry/javaagent/instrumentation/ziotelemetry/v4_0/ContextFiberRefBridge.scala`

Important:

- `ContextFiberRefBridge.scala` may still exist in the tree, but it is not currently part of a safe enabled design
- do not assume it is correct because it compiles

## Recommended Starting Point For the Next Session

Start from the design spec:

- [unified-fiberref-bridge-spec.md](/home/lamdness/.local/share/flowmux/worktrees/zio-opentelemetry-java/init-zio-opentelemetry-java/unified-fiberref-bridge-spec.md)

The next session should decide first:

### Option A: true unified shared slot

If pursuing this:

- do **not** reuse the old `Proxy`-based bridge
- do **not** expose the raw shared core `FiberRef` directly to `zio-telemetry`
- implement a real facade `FiberRef[application Context]`
- ensure all helper classes are named and injected
- keep the shared slot agent-only

### Option B: keep dual storage and sync at explicit boundaries

If pursuing this:

- do not force `ZioAgentContext.getAgentFiberRef()` to return `Some(...)`
- keep `zio-telemetry` local `FiberRef`
- define explicit sync points instead of shared-slot reuse

This is lower risk and likely the better engineering choice unless true single-slot unification is a hard requirement.

## Strong Recommendations

### Do not repeat these

- Do not store application `Context` in the shared core ZIO `FiberRef`.
- Do not store `AgentContextWrapper` in the shared core ZIO `FiberRef`.
- Do not cast the shared agent `FiberRef` to `FiberRef[application Context]`.
- Do not rely on `FiberRef.Proxy` against the raw delegate.
- Do not use anonymous Scala helper classes in injected helper code.
- Do not treat class-name-prefix checks as a trustworthy context representation test.

### Do preserve these

- Keep core ZIO agent tests green while iterating:
  - `gradle :instrumentation:zio-2.1:test`
- Use the zio-telemetry integration suite to validate behavior:
  - `gradle :instrumentation:zio-telemetry-agent-4.0:test`

## Validation Commands Used In This Session

- `gradle :instrumentation:zio-telemetry-agent-4.0:test`
- `gradle :instrumentation:zio-2.1:test`
- `git diff --check`

At the end of the session:

- both Gradle test tasks passed
- `git diff --check` passed

## Summary For The Next Session

Current status is intentionally conservative:

- step 7.2 tests are implemented and green
- the shared zio-telemetry bridge is not considered safe
- fallback local `FiberRef` behavior is the supported state

If resuming bridge work, treat this as a fresh design/implementation task, not a matter of reviving the previous adapter.
