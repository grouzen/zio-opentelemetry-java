# ZIO Opentelemetry Java

An experimental OpenTelemetry Java agent distribution for Java 17 and later,
with ZIO fiber context propagation and zio-telemetry integration. The shared
`FiberRef` stores application contexts; the agent converts contexts at its storage
boundary. See
[the bridge design](application-context-fiberref-bridge-plan.md).

## Build and test

Use Gradle 9.3.1, sbt, Docker, and JDKs 8 and 17. JDK 8 compiles the agent;
JDK 17 runs the build and Scala compiler. The library integration is unpublished,
so first build its reproducible fixture:

```bash
bash scripts/prepare-zio-telemetry.sh
gradle clean build spotlessCheck --no-build-cache --no-daemon
```

The preparation script checks out zio-telemetry commit
`c50f756f6f020eb012633484926a00b284786edf`, applies
[the integration patch](compatibility/zio-telemetry-agent.patch), and publishes
artifacts to the local Maven repository under version `0.0.0-bridge-c50f756`. Its isolated checkout is
under `.gradle/compatibility`; it does not edit another developer checkout.

The suites run with the agent attached and context leak failures enabled. A
deliberate leak probe verifies that the agent actually throws. The same exported
span contract runs with Java scopes and zio-telemetry scopes, covering interruption,
controlled fiber interleaving, concurrency, sequential fibers, a custom ZIO
executor, race cleanup, and actual timeout cancellation. Separate tests cover
context keys, baggage, runtime reentry, and the assembled agent in a ZIO HTTP app.

The `integration-tests:zio-timeout` suite exercises the assembled agent in separate
JVMs using a small `ZIOApp` based on
[dontgitit's issue #1069 reproducer](https://github.com/dontgitit/zio-telemetry-issue-1069). With
`Client.default` present and the upstream ZIO instrumentation disabled, it checks
both a successful `.timeout` and an expired timeout. Each case compares the Java
and zio-telemetry trace and span IDs before, inside, and after the timed effect.
Run it with `gradle :integration-tests:zio-timeout:test` after preparing the pinned
zio-telemetry fixture.

## Compatibility matrix

Java 17 is the minimum supported runtime. CI runs the full build, timeout
integration test, and HTTP smoke test in all eight combinations:

| Dimension | Values |
| --- | --- |
| Instrumentation test JVM and smoke target JVM | JDK 17, 21 |
| Scala compiler | 2.13.18, 3.3.8 |
| ZIO runtime | 2.1.25, 2.1.27 |
| zio-telemetry | Pinned experimental fixture above |

The build rejects runtime selections below Java 17 for every ZIO version.

These are explicit test targets, not a claim to support every version in between.
The matrix tests one patched library revision until the agent integration has a
published artifact; it does not establish compatibility with unmodified releases.
Runtime tests exercise reflective ZIO calls that static Muzzle checks cannot fully
validate. Muzzle checks and MDC log correlation remain follow-ups.

To run one lane locally, install the requested JDK alongside the build JDKs:

```bash
bash scripts/prepare-zio-telemetry.sh 3.3.8
gradle clean build spotlessCheck --no-build-cache --no-daemon \
  -PscalaVersion=3 -PtestJavaVersion=21 -PzioVersion=2.1.27
```

To run all lanes, install JDKs 8/17/21 and use
`bash scripts/test-compatibility.sh`. Logs and JUnit XML are retained per lane in
`.gradle/compatibility/results`. CI uploads each lane's reports on success or
failure. The smoke test checks the target JVM's reported Java version as well as
span parent IDs and propagation.
