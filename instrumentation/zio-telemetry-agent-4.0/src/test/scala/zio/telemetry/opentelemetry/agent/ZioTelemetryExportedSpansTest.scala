package zio.telemetry.opentelemetry.agent

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Span
import dev.zio.agent.testing.ExportedContextContract
import io.opentelemetry.sdk.trace.data.SpanData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import zio._
import zio.telemetry.opentelemetry.{OpenTelemetry => ZioOpenTelemetry}
import zio.telemetry.opentelemetry.core.{OpenTelemetry => CoreOpenTelemetry}
import zio.telemetry.opentelemetry.core.trace.Tracer

import scala.jdk.CollectionConverters._
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.{Duration => ScalaDuration, SECONDS}

class ZioTelemetryExportedSpansTest extends ExportedContextContract {
  override protected def withTracing[A](effect: Spans => Task[A]): A =
    runWithTelemetry { (_, tracer) =>
      effect(new Spans {
        override def apply[B](name: String)(body: Task[B]): Task[B] = tracer.span(name)(_ => body)
      })
    }

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

  @Test
  def librarySpanPropagatesToLiftedFutureWithoutAnExplicitBoundary(): Unit = {
    withSpan("outer") {
      runWithTelemetry { (_, tracer) =>
        tracer.span("zio") { _ =>
          ZIO.fromFuture { implicit ec => Future(withSpan("future")(())) }
        }
      }
    }
    assertForest(Map("outer" -> None, "zio" -> Some("outer"), "future" -> Some("zio")))
  }

  @Test
  def libraryRuntimeToFutureAndCallbackReentryPreserveParents(): Unit = {
    withSpan("outer") {
      runWithTelemetry { (_, tracer) =>
        tracer.span("zio") { _ =>
          ZIO.fromFuture { implicit ec =>
            Future {
              withSpan("callback") {
                val future = Unsafe.unsafe { implicit unsafe =>
                  Runtime.default.unsafe.runToFuture(tracer.span("reentered-zio")(_ => ZIO.unit))
                }
                Await.result(future, ScalaDuration(10, SECONDS))
              }
            }
          }
        }
      }
    }
    assertForest(Map("outer" -> None, "zio" -> Some("outer"),
      "callback" -> Some("zio"), "reentered-zio" -> Some("callback")))
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
