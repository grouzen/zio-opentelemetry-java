# ZIO Opentelemetry Java

An experimental OpenTelemetry Java agent distribution with ZIO fiber context
propagation and zio-telemetry integration. The shared `FiberRef` stores application
contexts; the agent converts contexts at its storage boundary. See
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

## Compatibility matrix

CI runs the full build and HTTP smoke test in all ten runnable combinations:

| Dimension | Values |
| --- | --- |
| Instrumentation test JVM and smoke target JVM | JDK 11, 17, 21 |
| Scala compiler | 2.13.18, 3.3.8 |
| ZIO runtime | 2.1.25, 2.1.27 |
| zio-telemetry | Pinned experimental fixture above |

ZIO 2.1.27 is excluded on JDK 11: its published Scala 2.13 and Scala 3 jars
contain `MutableQueueFieldsPadding` compiled for Java 17 (class version 61).
The build rejects that combination immediately. JDK 11 is covered with ZIO 2.1.25.

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

To run all lanes, install JDKs 8/11/17/21 and use
`bash scripts/test-compatibility.sh`. Logs and JUnit XML are retained per lane in
`.gradle/compatibility/results`. CI uploads each lane's reports on success or
failure. The smoke test checks the target JVM's reported Java version as well as
span parent IDs and propagation.
