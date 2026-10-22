package example

import io.opentelemetry.api.trace.{Span, SpanContext}
import zio._
import zio.http.Client
import zio.telemetry.opentelemetry.{OpenTelemetry => ZioOpenTelemetry}
import zio.telemetry.opentelemetry.core.{OpenTelemetry => CoreOpenTelemetry}
import zio.telemetry.opentelemetry.core.trace.Tracer

object TimeoutContextApp extends ZIOApp {
  override type Environment = CoreOpenTelemetry
  override val environmentTag: EnvironmentTag[Environment] = EnvironmentTag[Environment]
  override val bootstrap: ZLayer[ZIOAppArgs, Throwable, Environment] = ZioOpenTelemetry.agent()

  private final case class SpanIds(traceId: String, spanId: String) {
    def json: String = s"""{"traceId":"$traceId","spanId":"$spanId"}"""
  }

  private object SpanIds {
    def from(context: SpanContext): SpanIds = SpanIds(context.getTraceId, context.getSpanId)
  }

  private final case class Snapshot(java: SpanIds, zio: SpanIds) {
    def json: String = s"""{"java":${java.json},"zio":${zio.json}}"""
  }

  private final case class Result(
    scenario: String,
    timedResultPresent: Boolean,
    before: Snapshot,
    inside: Option[Snapshot],
    after: Snapshot
  ) {
    def json: String =
      s"""{"scenario":"$scenario","timedResultPresent":$timedResultPresent,"before":${before.json},"inside":${inside.map(_.json).getOrElse("null")},"after":${after.json}}"""
  }

  private def snapshot: URIO[CoreOpenTelemetry, Snapshot] =
    for {
      telemetry <- ZIO.service[CoreOpenTelemetry]
      zioContext <- telemetry.unsafe.getCurrentContext
      javaContext <- ZIO.succeed(Span.current().getSpanContext)
    } yield Snapshot(SpanIds.from(javaContext), SpanIds.from(Span.fromContext(zioContext).getSpanContext))

  private def fast: URIO[CoreOpenTelemetry, Result] =
    for {
      before <- snapshot
      inside <- snapshot.timeout(Duration.fromSeconds(5))
      after <- snapshot
    } yield Result("fast", inside.isDefined, before, inside, after)

  private def expired: URIO[CoreOpenTelemetry, Result] =
    for {
      insideRef <- Ref.make[Option[Snapshot]](None)
      before <- snapshot
      timed <- (snapshot.flatMap(value => insideRef.set(Some(value))) *> ZIO.never)
        .timeout(Duration.fromSeconds(1))
      inside <- insideRef.get
      after <- snapshot
    } yield Result("expired", timed.isDefined, before, inside, after)

  override def run =
    (for {
      args <- ZIOAppArgs.getArgs
      scenario <- ZIO.fromOption(args.headOption).orElseFail(new IllegalArgumentException("Missing scenario"))
      _ <- ZIO.service[Client]
      tracer <- ZIO.service[Tracer]
      result <- (scenario match {
        case "fast" => fast
        case "expired" => expired
        case other => ZIO.fail(new IllegalArgumentException(s"Unknown scenario: $other"))
      }) @@ tracer.aspects.span("root_span")
      _ <- ZIO.succeed(println("ZIO_TIMEOUT_RESULT " + result.json))
    } yield ()).provideSome[CoreOpenTelemetry with ZIOAppArgs](
      Client.default,
      ZioOpenTelemetry.tracer("zio-timeout-integration-test")
    )
}
