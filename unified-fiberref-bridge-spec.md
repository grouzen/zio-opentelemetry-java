# Unified FiberRef Bridge Spec

> Historical design record. The current otel4s-style application-context
> approach is described in [application-context-fiberref-bridge-plan.md](application-context-fiberref-bridge-plan.md).
> The agent-only slot rule below applied to the previous bridge design.

## Goal

Define a safe design for sharing OpenTelemetry context across:

- the javaagent's ZIO instrumentation
- `zio-telemetry`
- Java `Context.current()` / `Scope`
- Scala `Future` and ZIO fiber boundaries

without introducing classloader corruption, invalid `FiberRef` state, or runtime `ClassCastException`s.

## Non-Goals

- Preserve the current experimental bridge shape.
- Reuse `FiberRef.Proxy` with a cast raw delegate.
- Store both agent and application context representations in the same slot.
- Optimize for minimal code over correctness.

## Problem Statement

There are two incompatible context representations:

1. Agent context
   - shaded / agent-side OpenTelemetry context objects
   - canonical for javaagent instrumentation internals

2. Application context
   - application `io.opentelemetry.context.Context`
   - canonical for `zio-telemetry` and application code

They may represent the same trace state but are not type-compatible and may be loaded by different classloaders.

Previous attempts failed because:

- a single `FiberRef` stored values that were not semantically uniform
- wrapper objects leaked into slots expected to contain shaded agent contexts
- inherited `FiberRef.Proxy` operations touched the raw delegate with the wrong type assumptions
- Scala-generated anonymous helper classes were not injected, causing runtime linkage failures

## Core Safety Rule

Exactly one representation may live in a given `FiberRef` slot.

Corollaries:

- The shared core ZIO `FiberRef` must remain agent-only if it is canonical for the javaagent.
- Application `Context` objects must never be written directly into that shared slot.
- `AgentContextWrapper` instances must never be written into that shared slot.
- Any adapter exposed as `FiberRef[application Context]` must fully bridge every operation and must not rely on raw delegate typing.

## Design Options

### Option A: Agent-First Unified Slot

Canonical storage:

- one shared `FiberRef`
- stores agent/shaded context only

Access paths:

- javaagent reads/writes directly
- `zio-telemetry` sees a facade `FiberRef[application Context]`
- Java `Context.current()` is bridged via existing agent/application context storage helpers

Pros:

- true single source of truth
- direct interoperability with javaagent fiber propagation

Cons:

- highest implementation risk
- every facade operation must be correct
- more helper injection and classloader sensitivity

### Option B: Library-First Dual Slot With Synchronization

Canonical storage:

- `zio-telemetry` keeps its own local `FiberRef[application Context]`
- agent keeps its own shared agent `FiberRef`

Synchronization:

- explicit copy/sync at entry/exit boundaries
- explicit sync for unmanaged Java scopes and future boundaries

Pros:

- much lower risk
- avoids fake `FiberRef` facade
- easier to reason about classloader boundaries

Cons:

- not a true unified slot
- requires carefully defined sync points

## Recommendation

If true single-slot unification is not strictly required, use Option B.

If a single slot is required, implement Option A only with a full facade design described below. Do not expose the raw shared `FiberRef` directly.

## Option A Required Invariants

### Storage Invariants

- Shared core `FiberRef` stores agent/shaded context objects only.
- Null application values map to agent root context before storage.
- Reading the shared slot through the facade always yields an application `Context`.
- No wrapper instance is ever stored in the shared slot.

### Classloader Invariants

- All code executed from injected instrumentation helpers must reference only:
  - bootstrap-visible classes
  - application-visible classes
  - explicitly injected named helper classes
- No Scala anonymous class may be required at runtime unless it is also injected explicitly.

### Semantic Invariants

- `get` from facade returns the application view of the current shared agent context.
- `set` writes the agent representation of the supplied application context.
- `locally` restores exactly the prior shared slot contents.
- `fork` / `join` semantics match the canonical shared slot semantics.
- `diff` / `patch` must not assume application values are stored in the raw delegate.

## Option A Facade Requirements

The facade must be a real `FiberRef[application Context]` implementation.

It must not be implemented as:

- `new FiberRef.Proxy[Context](rawDelegate.asInstanceOf[FiberRef[Context]])`
- a cast raw delegate exposed directly

### Required Operations

The facade must explicitly define:

- `initial`
- `diff`
- `combine`
- `patch`
- `fork`
- `join`
- `get`
- `getWith`
- `set`
- `modify`
- `locally`
- `locallyWith`

### Implementation Constraints

- If the facade subclasses a ZIO helper, that helper must not touch the raw delegate with application typing assumptions.
- Prefer a self-contained implementation over a `Proxy`.
- If a dummy delegate is needed for inheritance shape only, it must use named helper classes only.

## Conversion Rules

### Application -> Agent

Input:

- application `Context`
- or application-side wrapper exposing agent context via a dedicated bridge method

Rules:

- if null, convert to agent root context
- if the value exposes an agent-context extraction method, use it
- otherwise use `AgentContextStorage.getAgentContext(applicationContext)`

Must never return:

- application `Context`
- `AgentContextWrapper`

### Agent -> Application

Input:

- shaded agent context from shared slot

Rules:

- if null, return application root context
- if already application context, pass through
- otherwise use `AgentContextStorage.toApplicationContext(agentContext)`

Must never write the converted application value back into the shared slot unless converting through the application-to-agent rule again.

## Helper Injection Rules

All runtime-required Scala helper artifacts must be named and listed.

Allowed:

- top-level classes
- companion objects
- named nested classes/objects

Disallowed:

- relying on anonymous Scala classes being auto-available
- relying on lambda-generated classes being injected

For any helper referenced from instrumentation advice or injected facades:

- include the class/object name in `getAdditionalHelperClassNames()`
- include named nested helper objects if they are instantiated at runtime

## Failure Modes To Prevent

### Type Corruption

Symptom:

- `AgentContextWrapper cannot be cast to shaded Context`

Prevention:

- never store wrappers in the shared slot
- agent-only slot invariant

### Proxy Leakage

Symptom:

- `FiberRef.Proxy` inherited methods using raw delegate with wrong generic assumptions

Prevention:

- avoid `Proxy` over the raw delegate
- implement all relevant methods explicitly

### Helper Linkage Failure

Symptom:

- `NoClassDefFoundError` for Scala anonymous helper classes

Prevention:

- no anonymous helper classes in injected code paths
- explicit helper registration

### Silent Semantic Drift

Symptom:

- tests pass for some paths but context differs across `fork`, `join`, unmanaged scopes, or futures

Prevention:

- enforce a comprehensive operation-level test matrix

## Test Matrix

### Core Propagation

- parent span visible in child fiber
- child span isolated from sibling fiber
- sequential child spans get distinct trace/span expectations as appropriate
- interrupted fiber restores prior parent context
- race preserves outer context
- timeout preserves outer context

### Java Interop

- outer Java span visible inside `autoinstrumented`
- `Future` body sees expected current span
- unmanaged Java scope from ZIO code updates Java current context correctly
- nested Java span inside ZIO preserves trace linkage and restores prior context after scope close

### Bidirectional Consistency

- ZIO current context and Java current context agree where contract requires them to agree
- restoration after `locally` matches previous context in both views

### Baggage

- baggage survives `fork`
- baggage survives `Future` handoff when expected

### Detection / Wiring

- if unified-slot mode is enabled, `OpenTelemetry.global()` uses the bridge-backed storage path
- if unified-slot mode is disabled, fallback storage is used and remains correct

### Negative Tests

- shared slot never returns wrapper instances when read through raw agent path
- facade never writes application `Context` directly into raw shared slot

## Rollout Plan

### Phase 1

- Keep current safe fallback behavior:
  - `zio-telemetry` uses local `FiberRef`
  - javaagent core ZIO bridge remains enabled for agent internals
- Land tests that validate current supported behavior

### Phase 2

- Prototype a real facade behind a feature flag
- Add targeted tests for each `FiberRef` operation
- Verify helper injection closure

### Phase 3

- Enable unified slot only after:
  - no classloader failures
  - no wrapper leakage
  - full test matrix passes

## Acceptance Criteria

The unified bridge is safe only when all of the following are true:

- no runtime `ClassCastException` across the full test matrix
- no helper linkage failures
- raw shared `FiberRef` stores only agent/shaded context values
- facade operations preserve ZIO semantics across `fork`, `join`, `locally`, and restoration
- Java and ZIO propagation agree at documented boundaries
- core ZIO instrumentation tests remain green
- zio-telemetry integration tests remain green with unified-slot mode enabled

## Current Status

Current repo state should be treated as:

- core ZIO bridge: supported
- zio-telemetry unified shared-slot bridge: not safe
- zio-telemetry local `FiberRef` fallback: supported

Any future work should start from this spec instead of reviving the prior `Proxy`-based adapter shape.
