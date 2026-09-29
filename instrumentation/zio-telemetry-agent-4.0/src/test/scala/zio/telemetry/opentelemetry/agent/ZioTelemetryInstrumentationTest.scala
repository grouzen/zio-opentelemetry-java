package zio.telemetry.opentelemetry.agent

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.{Span, SpanContext}
import org.junit.jupiter.api.Assertions.{assertEquals, assertNotEquals, assertTrue}
import org.junit.jupiter.api.Test
import zio._
import zio.telemetry.opentelemetry.{OpenTelemetry => ZioOpenTelemetry}
import zio.telemetry.opentelemetry.core.{OpenTelemetry => CoreOpenTelemetry}
import zio.telemetry.opentelemetry.core.trace.Tracer

import java.util.concurrent.Executors
import scala.concurrent.{ExecutionContext, Future}

class ZioTelemetryInstrumentationTest {

  @Test
  def respectOuterSpanWithAutoinstrumented(): Unit =
    withOuterSpan("outer") { outer =>
      val observed =
        runWithTelemetry { (openTelemetry, _) =>
          openTelemetry.autoinstrumented {
            ZIO.succeed(currentSpanContext)
          }
        }

      assertEquals(outer.getSpanContext, observed)
    }

  @Test
  def respectOuterSpanAndPropagateToLiftedFuture(): Unit =
    withOuterSpan("outer") { outer =>
      val observed =
        runWithTelemetry { (openTelemetry, tracer) =>
          openTelemetry.autoinstrumented {
            tracer.unmanagedScopeFuture(ec => Future(currentSpanContext)(ec))
          }
        }

      assertEquals(outer.getSpanContext, observed)
    }

  @Test
  def traceIsPropagatedToChildFiber(): Unit = {
    val observed =
      runWithTelemetry { (openTelemetry, tracer) =>
        openTelemetry.autoinstrumented {
          tracer.span("parent") { _ =>
            for {
              parent <- currentScalaSpanContext(openTelemetry)
              fiber <- currentScalaSpanContext(openTelemetry).fork
              child <- fiber.join
            } yield SpanObservation(parent, child)
          }
        }
      }

    assertEquals(observed.parent, observed.child)
  }

  @Test
  def traceIsPropagatedToChildFiberOnExternalExecutor(): Unit = {
    val executor = Executors.newSingleThreadExecutor()
    try {
      val observed =
        runWithTelemetry { (openTelemetry, tracer) =>
          openTelemetry.autoinstrumented {
            tracer.span("parent") { _ =>
              for {
                parent <- currentScalaSpanContext(openTelemetry)
                child <- tracer.unmanagedScopeFuture { _ =>
                  Future(currentSpanContext)(ExecutionContext.fromExecutor(executor))
                }
              } yield SpanObservation(parent, child)
            }
          }
        }

      assertEquals(observed.parent, observed.child)
    } finally executor.shutdown()
  }

  @Test
  def traceIsPreservedWhenFiberIsInterrupted(): Unit = {
    val observed =
      runWithTelemetry { (openTelemetry, tracer) =>
        openTelemetry.autoinstrumented {
          tracer.span("parent") { _ =>
            for {
              parent <- ZIO.succeed(currentSpanContext)
              started <- Promise.make[Nothing, Unit]
              fiber <- (started.succeed(()) *> ZIO.never).fork
              _ <- started.await
              _ <- fiber.interrupt
              afterInterrupt <- ZIO.succeed(currentSpanContext)
            } yield SpanObservation(parent, afterInterrupt)
          }
        }
      }

    assertEquals(observed.parent, observed.child)
  }

  @Test
  def synchronizedFibersDoNotInterfereWithEachOthersTraces(): Unit = {
    val first = runIsolatedChild("first")
    val second = runIsolatedChild("second")

    assertEquals(first.parent, first.child)
    assertEquals(second.parent, second.child)
    assertNotEquals(first.parent.getTraceId, second.parent.getTraceId)
  }

  @Test
  def concurrentFibersDoNotInterfereWithEachOthersTraces(): Unit = {
    val results =
      runWithTelemetry { (openTelemetry, tracer) =>
        openTelemetry.autoinstrumented {
          ZIO.collectAllPar(
            List(
              observeInsideNewSpan(openTelemetry, tracer, "first"),
              observeInsideNewSpan(openTelemetry, tracer, "second")
            )
          )
        }
      }

    results.foreach { result =>
      assertEquals(result.parent, result.child)
    }
    assertNotEquals(results.head.parent.getTraceId, results(1).parent.getTraceId)
  }

  @Test
  def sequentialFibersDoNotInterfereWithEachOthersTraces(): Unit = {
    val results =
      runWithTelemetry { (openTelemetry, tracer) =>
        openTelemetry.autoinstrumented {
          for {
            first <- observeInsideNewSpan(openTelemetry, tracer, "first")
            second <- observeInsideNewSpan(openTelemetry, tracer, "second")
          } yield List(first, second)
        }
      }

    results.foreach { result =>
      assertEquals(result.parent, result.child)
    }
    assertNotEquals(results.head.parent.getTraceId, results(1).parent.getTraceId)
  }

  @Test
  def raceContextIsCorrect(): Unit =
    withOuterSpan("outer") { outer =>
      val observed =
        runWithTelemetry { (openTelemetry, _) =>
          openTelemetry.autoinstrumented {
            ZIO
              .succeed(currentSpanContext)
              .race(ZIO.sleep(Duration.fromMillis(250)) *> ZIO.succeed(currentSpanContext))
          }
        }

      assertEquals(outer.getSpanContext, observed)
    }

  @Test
  def timeoutContextIsCorrect(): Unit =
    withOuterSpan("outer") { outer =>
      val observed =
        runWithTelemetry { (openTelemetry, _) =>
          openTelemetry.autoinstrumented {
            for {
              value <- ZIO.succeed(currentSpanContext).timeout(Duration.fromSeconds(1))
              afterTimeout <- ZIO.succeed(currentSpanContext)
            } yield value.getOrElse(afterTimeout)
          }
        }

      assertEquals(outer.getSpanContext, observed)
    }

  @Test
  def bidirectionalContextFlow(): Unit =
    withOuterSpan("outer") { outer =>
      val observed =
        runWithTelemetry { (openTelemetry, tracer) =>
          openTelemetry.autoinstrumented {
            for {
              before <- openTelemetry.unsafe.getCurrentContext.map(ctx => Span.fromContext(ctx).getSpanContext)
              during <- tracer.span("zio-child") { _ =>
                for {
                  fiberCurrent <- openTelemetry.unsafe.getCurrentContext.map(ctx => Span.fromContext(ctx).getSpanContext)
                  javaCurrent <- ZIO.succeed(currentSpanContext)
                } yield SpanObservation(fiberCurrent, javaCurrent)
              }
              after <- tracer.unmanagedScopeTotal(currentSpanContext)
            } yield BidirectionalObservation(before, during.parent, during.child, after)
          }
        }

      assertEquals(outer.getSpanContext, observed.before)
      assertEquals(outer.getSpanContext.getTraceId, observed.fiberCurrent.getTraceId)
      assertNotEquals(outer.getSpanContext.getSpanId, observed.fiberCurrent.getSpanId)
      assertEquals(observed.fiberCurrent, observed.javaCurrent)
      assertEquals(outer.getSpanContext, observed.after)
    }

  @Test
  def manualSpanIsVisibleToJavaWithoutExplicitBoundary(): Unit = {
    val observed =
      runWithTelemetry { (openTelemetry, tracer) =>
        tracer.span("zio-parent") { _ =>
          for {
            scalaCurrent <- currentScalaSpanContext(openTelemetry)
            javaCurrent <- ZIO.succeed(currentSpanContext)
          } yield SpanObservation(scalaCurrent, javaCurrent)
        }
      }

    assertTrue(observed.parent.isValid)
    assertEquals(observed.parent, observed.child)
  }

  @Test
  def javaScopeIsVisibleToZioWithoutExplicitBoundary(): Unit = {
    val observed =
      runWithTelemetry { (openTelemetry, _) =>
        ZIO.acquireReleaseWith(
          ZIO.attempt {
            val span = GlobalOpenTelemetry.getTracer("zio-telemetry-test").spanBuilder("java-parent").startSpan()
            (span, span.makeCurrent())
          }
        ) { case (span, scope) =>
          ZIO.succeed {
            scope.close()
            span.end()
          }
        } { case (span, _) =>
          currentScalaSpanContext(openTelemetry).map(ctx => SpanObservation(span.getSpanContext, ctx))
        }
      }

    assertTrue(observed.parent.isValid)
    assertEquals(observed.parent, observed.child)
  }

  @Test
  def autoinstrumentedCompatibility(): Unit =
    withOuterSpan("outer") { outer =>
      val observed =
        runWithTelemetry { (openTelemetry, tracer) =>
          openTelemetry.autoinstrumented {
            tracer.span("zio-child") { _ =>
              tracer.unmanagedScopeTotal(currentSpanContext)
            }
          }
        }

      assertEquals(outer.getSpanContext.getTraceId, observed.getTraceId)
      assertNotEquals(outer.getSpanContext.getSpanId, observed.getSpanId)
    }

  @Test
  def baggageSurvivesFork(): Unit = {
    val observed =
      runWithTelemetry { (openTelemetry, _) =>
        openTelemetry.autoinstrumented {
          openTelemetry.baggage.set("bag", "value") {
            for {
              parent <- openTelemetry.baggage.get("bag")
              fiber <- openTelemetry.baggage.get("bag").fork
              child <- fiber.join
            } yield (parent, child)
          }
        }
      }

    assertEquals(Some("value"), observed._1)
    assertEquals(Some("value"), observed._2)
  }

  @Test
  def nestedJavaScalaContexts(): Unit = {
    val observed =
      runWithTelemetry { (openTelemetry, tracer) =>
        openTelemetry.autoinstrumented {
          tracer.span("zio-parent") { _ =>
            for {
              before <- currentScalaSpanContext(openTelemetry)
              nested <- tracer.unmanagedScopeTotal {
                val javaSpan = GlobalOpenTelemetry.getTracer("zio-telemetry-test").spanBuilder("java-child").startSpan()
                val scope = javaSpan.makeCurrent()
                try currentSpanContext
                finally {
                  scope.close()
                  javaSpan.end()
                }
              }
              after <- currentScalaSpanContext(openTelemetry)
            } yield NestedObservation(before, nested, after)
          }
        }
      }

    assertEquals(observed.before, observed.after)
    assertEquals(observed.before.getTraceId, observed.nested.getTraceId)
    assertNotEquals(observed.before.getSpanId, observed.nested.getSpanId)
  }

  private def runIsolatedChild(name: String): SpanObservation =
    runWithTelemetry { (openTelemetry, tracer) =>
      openTelemetry.autoinstrumented {
        observeInsideNewSpan(openTelemetry, tracer, name)
      }
    }

  private def observeInsideNewSpan(
    openTelemetry: CoreOpenTelemetry,
    tracer: Tracer,
    name: String
  ): ZIO[Any, Throwable, SpanObservation] =
    tracer.span(name) { _ =>
      for {
        parent <- currentScalaSpanContext(openTelemetry)
        fiber <- currentScalaSpanContext(openTelemetry).fork
        child <- fiber.join
      } yield SpanObservation(parent, child)
    }

  private def currentScalaSpanContext(openTelemetry: CoreOpenTelemetry): ZIO[Any, Nothing, SpanContext] =
    openTelemetry.unsafe.getCurrentContext.map(ctx => Span.fromContext(ctx).getSpanContext)

  @Test
  def agentFiberRefIsDetected(): Unit = {
    val usesAgentRef =
      runWithTelemetry { (openTelemetry, _) =>
        ZIO.succeed {
          ZioAgentContext.isAgentAttached &&
          ZioAgentContext.getAgentFiberRef().exists(_ eq openTelemetry.unsafe.getCtxStorage.ref)
        }
      }

    assertTrue(usesAgentRef)
  }

  @Test
  def fiberRefContextStorageIsActive(): Unit = {
    val validCurrentSpan =
      runWithTelemetry { (openTelemetry, tracer) =>
        openTelemetry.autoinstrumented {
          tracer.span("zio-parent") { _ =>
            currentScalaSpanContext(openTelemetry).map(_.isValid)
          }
        }
      }

    assertTrue(validCurrentSpan)
  }

  private def runWithTelemetry[A](
    effect: (CoreOpenTelemetry, Tracer) => ZIO[Any, Throwable, A]
  ): A =
    runZIO {
      ZIO.scoped {
        for {
          openTelemetry <- ZIO.service[CoreOpenTelemetry]
          tracerEnv <- ZioOpenTelemetry.tracer("zio-telemetry-test").build
          tracer <- ZIO.succeed(tracerEnv.get[Tracer])
          result <- effect(openTelemetry, tracer)
        } yield result
      }.provideLayer(OpenTelemetry.global())
    }

  private def withOuterSpan[A](name: String)(body: Span => A): A = {
    val span = GlobalOpenTelemetry.getTracer("zio-telemetry-test").spanBuilder(name).startSpan()
    val scope = span.makeCurrent()
    try {
      assertTrue(span.getSpanContext.isValid)
      body(span)
    } finally {
      scope.close()
      span.end()
    }
  }

  private def runZIO[A](zio: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(zio).getOrThrowFiberFailure()
    }

  private def currentSpanContext: SpanContext =
    Span.current().getSpanContext

  private final case class SpanObservation(parent: SpanContext, child: SpanContext)
  private final case class BidirectionalObservation(
    before: SpanContext,
    fiberCurrent: SpanContext,
    javaCurrent: SpanContext,
    after: SpanContext
  )
  private final case class NestedObservation(before: SpanContext, nested: SpanContext, after: SpanContext)
}
