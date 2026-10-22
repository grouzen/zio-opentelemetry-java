package zio.telemetry.opentelemetry.agent

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.instrumentation.testing.junit.{AgentInstrumentationExtension, InstrumentationExtension}
import io.opentelemetry.sdk.trace.data.SpanData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import zio._
import zio.telemetry.opentelemetry.{OpenTelemetry => ZioOpenTelemetry}
import zio.telemetry.opentelemetry.core.{OpenTelemetry => CoreOpenTelemetry}
import zio.telemetry.opentelemetry.core.trace.Tracer

import scala.jdk.CollectionConverters._

class ZioTelemetryExportedSpansTest {
  @RegisterExtension val testing: InstrumentationExtension = AgentInstrumentationExtension.create()

  @Test
  def javaZioAndForkedJavaSpansExportOneTree(): Unit = {
    withSpan("outer") {
      runWithTelemetry { (_, tracer) =>
        tracer.span("zio") { _ =>
          for {
            child <- javaSpan("java-child").fork
            _ <- child.join
          } yield ()
        }
      }
    }
    val spans = testing.waitForTraces(1).asScala.toList.flatMap(_.asScala)
    assertEquals(Set("outer", "zio", "java-child"), spans.map(_.getName).toSet)
    assertEquals(3, spans.size)
    assertParent(spans, "zio", "outer")
    assertParent(spans, "java-child", "zio")
  }

  private def javaSpan(name: String): ZIO[Any, Throwable, Unit] =
    ZIO.acquireReleaseWith(ZIO.attempt {
      val span = GlobalOpenTelemetry.getTracer("zio-telemetry-export-test").spanBuilder(name).startSpan()
      (span, span.makeCurrent())
    }) { case (span, scope) =>
      ZIO.succeed { scope.close(); span.end() }
    }(_ => ZIO.unit)

  private def assertParent(spans: List[SpanData], child: String, parent: String): Unit = {
    val childSpan = spans.find(_.getName == child).get
    val parentSpan = spans.find(_.getName == parent).get
    assertEquals(parentSpan.getTraceId, childSpan.getTraceId)
    assertEquals(parentSpan.getSpanId, childSpan.getParentSpanId)
  }

  private def runWithTelemetry[A](effect: (CoreOpenTelemetry, Tracer) => ZIO[Any, Throwable, A]): A =
    runZIO {
      ZIO.scoped {
        for {
          openTelemetry <- ZIO.service[CoreOpenTelemetry]
          tracerEnv <- ZioOpenTelemetry.tracer("zio-telemetry-export-test").build
          tracer <- ZIO.succeed(tracerEnv.get[Tracer])
          result <- effect(openTelemetry, tracer)
        } yield result
      }.provideLayer(OpenTelemetry.global())
    }

  private def withSpan[A](name: String)(body: => A): A = {
    val span: Span = GlobalOpenTelemetry.getTracer("zio-telemetry-export-test").spanBuilder(name).startSpan()
    val scope = span.makeCurrent()
    try body
    finally { scope.close(); span.end() }
  }

  private def runZIO[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe { implicit unsafe => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }
}
