package io.opentelemetry.javaagent.instrumentation.zio.v2_1

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.{Span, SpanId}
import io.opentelemetry.instrumentation.testing.junit.{AgentInstrumentationExtension, InstrumentationExtension}
import io.opentelemetry.sdk.trace.data.SpanData
import org.junit.jupiter.api.Assertions.{assertEquals, assertTrue}
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import zio._

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.{Duration => ScalaDuration, SECONDS}
import scala.jdk.CollectionConverters._

class ZioExportedSpansTest {
  @RegisterExtension val testing: InstrumentationExtension = AgentInstrumentationExtension.create()

  @Test
  def outerZioAndFutureSpansHaveExactParents(): Unit = {
    withSpan("outer") {
      runZIO {
        scopedSpan("zio") {
          ZIO.fromFuture { implicit ec =>
            Future(withSpan("future")(()))
          }
        }
      }
    }
    val spans = traceSpans(1)
    assertEquals(Set("outer", "zio", "future"), spans.map(_.getName).toSet)
    assertEquals(3, spans.size)
    assertParent(spans, "zio", "outer")
    assertParent(spans, "future", "zio")
  }

  @Test
  def forkedChildAndIndependentFibersExportSeparateTrees(): Unit = {
    runZIO {
      for {
        first <- (scopedSpan("first-root", root = true) {
          scopedSpan("first-child")(ZIO.unit).fork.flatMap(_.join)
        }).fork
        second <- scopedSpan("second-root", root = true)(ZIO.unit).fork
        _ <- first.join
        _ <- second.join
      } yield ()
    }
    val spans = traceSpans(2)
    assertEquals(Set("first-root", "first-child", "second-root"), spans.map(_.getName).toSet)
    assertEquals(3, spans.size)
    assertParent(spans, "first-child", "first-root")
    assertEquals(SpanId.getInvalid, span(spans, "first-root").getParentSpanId)
    assertEquals(SpanId.getInvalid, span(spans, "second-root").getParentSpanId)
    assertTrue(span(spans, "first-root").getTraceId != span(spans, "second-root").getTraceId)
  }

  @Test
  def runtimeToFutureAndCallbackReentryPreserveParents(): Unit = {
    withSpan("outer") {
      val future = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.runToFuture {
          scopedSpan("zio") {
            ZIO.fromFuture { implicit ec =>
              Future {
                runZIO(scopedSpan("reentered-zio")(ZIO.unit))
              }
            }
          }
        }
      }
      Await.result(future, ScalaDuration(10, SECONDS))
    }
    val spans = traceSpans(1)
    assertEquals(Set("outer", "zio", "reentered-zio"), spans.map(_.getName).toSet)
    assertEquals(3, spans.size)
    assertParent(spans, "zio", "outer")
    assertParent(spans, "reentered-zio", "zio")
  }

  private def traceSpans(count: Int): List[SpanData] =
    testing.waitForTraces(count).asScala.toList.flatMap(_.asScala)

  private def assertParent(spans: List[SpanData], child: String, parent: String): Unit = {
    val childSpan = span(spans, child)
    val parentSpan = span(spans, parent)
    assertEquals(parentSpan.getTraceId, childSpan.getTraceId)
    assertEquals(parentSpan.getSpanId, childSpan.getParentSpanId)
  }

  private def span(spans: List[SpanData], name: String): SpanData =
    spans.find(_.getName == name).getOrElse(throw new AssertionError(s"Missing span: $name"))

  private def scopedSpan[A](name: String, root: Boolean = false)(effect: ZIO[Any, Throwable, A]): ZIO[Any, Throwable, A] =
    ZIO.acquireReleaseWith(ZIO.attempt {
      val builder = GlobalOpenTelemetry.getTracer("zio-export-test").spanBuilder(name)
      if (root) builder.setNoParent()
      val span = builder.startSpan()
      (span, span.makeCurrent())
    }) { case (span, scope) =>
      ZIO.succeed { scope.close(); span.end() }
    }(_ => effect)

  private def withSpan[A](name: String)(body: => A): A = {
    val span: Span = GlobalOpenTelemetry.getTracer("zio-export-test").spanBuilder(name).startSpan()
    val scope = span.makeCurrent()
    try body
    finally { scope.close(); span.end() }
  }

  private def runZIO[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe { implicit unsafe => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }
}
