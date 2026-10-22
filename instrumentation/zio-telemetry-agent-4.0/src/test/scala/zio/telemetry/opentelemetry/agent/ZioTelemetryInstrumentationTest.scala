package zio.telemetry.opentelemetry.agent

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.{Span, SpanContext}
import io.opentelemetry.context.{Context, ContextKey}
import org.junit.jupiter.api.Assertions.{assertEquals, assertNotEquals, assertTrue}
import org.junit.jupiter.api.Test
import zio._
import zio.telemetry.opentelemetry.{OpenTelemetry => ZioOpenTelemetry}
import zio.telemetry.opentelemetry.core.{OpenTelemetry => CoreOpenTelemetry}
import zio.telemetry.opentelemetry.core.trace.Tracer

import java.util.concurrent.Executors
import scala.concurrent.{ExecutionContext, Future}

class ZioTelemetryInstrumentationTest {
  private val stringKey: ContextKey[String] = ContextKey.named("zio.bridge.string")
  private val numberKey: ContextKey[Integer] = ContextKey.named("zio.bridge.number")

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
              fiber <- tracer.span("interrupted-child") { _ =>
                started.succeed(()) *> ZIO.never
              }.fork
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
    val results = runWithTelemetry { (openTelemetry, tracer) =>
      openTelemetry.autoinstrumented {
        for {
          semaphore <- Semaphore.make(1)
          firstStarted <- Promise.make[Nothing, Unit]
          releaseFirst <- Promise.make[Nothing, Unit]
          firstFiber <- semaphore.withPermit(
            observeInsideNewSpanWithBarrier(openTelemetry, tracer, "first", firstStarted, releaseFirst)
          ).fork
          _ <- firstStarted.await
          secondFiber <- semaphore.withPermit(observeInsideNewSpan(openTelemetry, tracer, "second")).fork
          _ <- releaseFirst.succeed(())
          first <- firstFiber.join
          second <- secondFiber.join
        } yield List(first, second)
      }
    }
    results.foreach(result => assertEquals(result.parent, result.child))
    assertNotEquals(results.head.parent.getTraceId, results(1).parent.getTraceId)
  }

  @Test
  def concurrentFibersDoNotInterfereWithEachOthersTraces(): Unit = {
    val results =
      runWithTelemetry { (openTelemetry, tracer) =>
        openTelemetry.autoinstrumented {
          for {
            firstStarted <- Promise.make[Nothing, Unit]
            secondStarted <- Promise.make[Nothing, Unit]
            release <- Promise.make[Nothing, Unit]
            firstFiber <- observeInsideNewSpanWithBarrier(openTelemetry, tracer, "first", firstStarted, release).fork
            secondFiber <- observeInsideNewSpanWithBarrier(openTelemetry, tracer, "second", secondStarted, release).fork
            _ <- firstStarted.await.zipPar(secondStarted.await)
            _ <- release.succeed(())
            first <- firstFiber.join
            second <- secondFiber.join
          } yield List(first, second)
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
            firstFiber <- observeInsideNewSpan(openTelemetry, tracer, "first").fork
            first <- firstFiber.join
            secondFiber <- observeInsideNewSpan(openTelemetry, tracer, "second").fork
            second <- secondFiber.join
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
              beforeTimeout <- ZIO.succeed(currentSpanContext)
              duringTimeout <- ZIO.succeed(currentSpanContext).timeout(Duration.fromSeconds(1))
              afterTimeout <- ZIO.succeed(currentSpanContext)
            } yield (beforeTimeout, duringTimeout, afterTimeout)
          }
        }

      assertEquals(outer.getSpanContext, observed._1)
      assertEquals(Some(outer.getSpanContext), observed._2)
      assertEquals(outer.getSpanContext, observed._3)
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

  @Test
  def javaContextKeysAreScopedAndRestored(): Unit = {
    assertKeys(None, None)
    withJavaContext(Context.current().`with`(stringKey, "outer")) {
      assertKeys(Some("outer"), None)
      withJavaContext(Context.current().`with`(numberKey, Integer.valueOf(2))) {
        assertKeys(Some("outer"), Some(2))
        withJavaContext(Context.root()) {
          assertKeys(None, None)
        }
        assertKeys(Some("outer"), Some(2))
      }
      assertKeys(Some("outer"), None)
    }
    assertKeys(None, None)
  }

  @Test
  def fiberContextKeysAreScopedAndRestored(): Unit = {
    val observations = runWithTelemetry { (openTelemetry, _) =>
      val storage = openTelemetry.unsafe.getCtxStorage
      storage.locally(Context.current().`with`(stringKey, "outer")) {
        for {
          outer <- observeKeys(openTelemetry)
          nested <- storage.locally(Context.current().`with`(numberKey, Integer.valueOf(2))) {
            for {
              both <- observeKeys(openTelemetry)
              empty <- storage.locally(Context.root())(observeKeys(openTelemetry))
              restored <- observeKeys(openTelemetry)
            } yield List(both, empty, restored)
          }
          after <- observeKeys(openTelemetry)
        } yield outer :: nested ::: List(after)
      }
    }
    assertEquals(List(KeyObservation(Some("outer"), None), KeyObservation(Some("outer"), Some(2)),
      KeyObservation(None, None), KeyObservation(Some("outer"), Some(2)),
      KeyObservation(Some("outer"), None)), observations)
    assertKeys(None, None)
  }

  @Test
  def javaScopeInsideFiberAndFiberScopeInsideJavaPreserveKeys(): Unit = {
    val fromFiber = runWithTelemetry { (openTelemetry, _) =>
      val storage = openTelemetry.unsafe.getCtxStorage
      storage.locally(Context.current().`with`(stringKey, "fiber")) {
        for {
          before <- observeKeys(openTelemetry)
          inside <- ZIO.succeed {
            withJavaContext(Context.current().`with`(numberKey, Integer.valueOf(2))) {
              runZIO(observeKeys(openTelemetry))
            }
          }
          after <- observeKeys(openTelemetry)
        } yield List(before, inside, after)
      }
    }
    assertEquals(List(KeyObservation(Some("fiber"), None), KeyObservation(Some("fiber"), Some(2)),
      KeyObservation(Some("fiber"), None)), fromFiber)

    withJavaContext(Context.current().`with`(stringKey, "java")) {
      val fromJava = runWithTelemetry { (openTelemetry, _) =>
        val storage = openTelemetry.unsafe.getCtxStorage
        for {
          before <- observeKeys(openTelemetry)
          inside <- storage.locally(Context.current().`with`(numberKey, Integer.valueOf(4))) {
            observeKeys(openTelemetry)
          }
          after <- observeKeys(openTelemetry)
        } yield List(before, inside, after)
      }
      assertEquals(List(KeyObservation(Some("java"), None), KeyObservation(Some("java"), Some(4)),
        KeyObservation(Some("java"), None)), fromJava)
      assertKeys(Some("java"), None)
    }
    assertKeys(None, None)
  }

  @Test
  def nestedJavaAndFiberScopesRestoreOverriddenKeys(): Unit = {
    val observed = runWithTelemetry { (openTelemetry, _) =>
      val storage = openTelemetry.unsafe.getCtxStorage
      storage.locally(Context.current().`with`(stringKey, "fiber")) {
        for {
          inside <- ZIO.succeed {
            withJavaContext(Context.current().`with`(numberKey, Integer.valueOf(2))) {
              withJavaContext(Context.current().`with`(stringKey, "java")) {
                runZIO {
                  for {
                    before <- observeKeys(openTelemetry)
                    overrideNumber <- storage.locally(Context.current().`with`(numberKey, Integer.valueOf(4))) {
                      observeKeys(openTelemetry)
                    }
                    empty <- storage.locally(Context.root())(observeKeys(openTelemetry))
                    after <- observeKeys(openTelemetry)
                  } yield List(before, overrideNumber, empty, after)
                }
              }
            }
          }
          afterJava <- observeKeys(openTelemetry)
        } yield inside :+ afterJava
      }
    }
    assertEquals(List(KeyObservation(Some("java"), Some(2)), KeyObservation(Some("java"), Some(4)),
      KeyObservation(None, None), KeyObservation(Some("java"), Some(2)),
      KeyObservation(Some("fiber"), None)), observed)
    assertKeys(None, None)
  }

  private def observeKeys(openTelemetry: CoreOpenTelemetry): ZIO[Any, Nothing, KeyObservation] =
    openTelemetry.unsafe.getCurrentContext.map { fiberContext =>
      val javaContext = Context.current()
      val fiberKeys = keys(fiberContext)
      val javaKeys = keys(javaContext)
      assertEquals(fiberKeys, javaKeys)
      fiberKeys
    }

  private def keys(context: Context): KeyObservation =
    KeyObservation(Option(context.get(stringKey)), Option(context.get(numberKey)).map(_.intValue()))

  private def assertKeys(string: Option[String], number: Option[Int]): Unit =
    assertEquals(KeyObservation(string, number), keys(Context.current()))

  private def withJavaContext[A](context: Context)(body: => A): A = {
    val scope = context.makeCurrent()
    try body
    finally scope.close()
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

  private def observeInsideNewSpanWithBarrier(
    openTelemetry: CoreOpenTelemetry,
    tracer: Tracer,
    name: String,
    started: Promise[Nothing, Unit],
    release: Promise[Nothing, Unit]
  ): ZIO[Any, Throwable, SpanObservation] =
    tracer.span(name) { _ =>
      for {
        before <- currentScalaSpanContext(openTelemetry)
        _ <- started.succeed(())
        _ <- release.await
        _ <- ZIO.yieldNow
        after <- currentScalaSpanContext(openTelemetry)
      } yield SpanObservation(before, after)
    }

  private def currentScalaSpanContext(openTelemetry: CoreOpenTelemetry): ZIO[Any, Nothing, SpanContext] =
    openTelemetry.unsafe.getCurrentContext.map(ctx => Span.fromContext(ctx).getSpanContext)

  @Test
  def agentFiberRefIsDetected(): Unit = {
    val usesAgentRef =
      runWithTelemetry { (openTelemetry, _) =>
        ZIO.succeed {
          ZioAgentContext.isAgentAttached &&
          ZioAgentContext.getAgentFiberRef().exists { agentRef =>
            openTelemetry.unsafe.getCtxStorage.fiberRefOption.exists(_ eq agentRef)
          }
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
  private final case class KeyObservation(string: Option[String], number: Option[Int])
}
