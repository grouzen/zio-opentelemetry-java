# Application-context FiberRef bridge plan

## Decision

Follow the otel4s approach: the one shared ZIO `FiberRef` stores application
`io.opentelemetry.context.Context` values. `zio-telemetry` receives that actual
`FiberRef`, with no `Proxy` or cast to a differently typed slot. The agent's
`ContextStorage` view converts between application and shaded agent contexts at
its `ThreadLocal` boundary.

This replaces the agent-context-canonical design in
`unified-fiberref-bridge-spec.md`. That document remains useful as a record of
the earlier failure modes, but its agent-only slot rule no longer applies.

## Runtime contract

1. `ZioContextSingleton.contextFiberRef` starts with application `Context.root()`.
2. Every write to that `FiberRef`, including fiber-constructor seeding, uses an
   application `Context`. Application-visible wrappers are valid values here.
3. The agent-facing `contextThreadLocal.get()` converts the current application
   value to a shaded agent context. Its `set()` converts a shaded agent context
   to an application context before writing to a running fiber. Outside a
   fiber, it retains the original agent `ThreadLocal` behavior.
4. `ZioAgentContext.getAgentFiberRef()` returns the actual singleton to
   `zio-telemetry`. ZIO's own `FiberRef` operations handle scope, fork, and join.
5. Java and `zio-telemetry` contexts must agree inside manual spans and Java
   scopes, including restoration after those scopes close.

## Implementation sequence

1. Change the singleton's canonical value and move conversions to its
   agent-facing view and fiber-constructor boundary.
2. Return the real `FiberRef` from the zio-telemetry instrumentation hook;
   remove the experimental proxy and its helper registrations.
3. Assert that the library selected the agent-provided reference. Test context
   visibility in both directions without explicit `autoinstrumented` or
   unmanaged-scope synchronization.
4. Run core ZIO tests, zio-telemetry tests, the full Gradle test set, formatting
   checks, and agent assembly. Then add a ZIO HTTP smoke test before treating
   the design as production-ready.

## Current attempt

Steps 1 through 3 are implemented in this worktree. The strengthened tests
cover library-to-Java and Java-to-library visibility without explicit boundary
helpers. The core and zio-telemetry suites pass (10 and 18 tests respectively),
and `gradle test :agent:shadowJar` passes. This is integration-test evidence,
not a complete validation of production packaging or ZIO patch-version
compatibility. The planned ZIO HTTP smoke test is still missing.
