package io.opentelemetry.javaagent.instrumentation.zio.v2_1

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.{Span, SpanContext, Tracer}
import org.junit.jupiter.api.Assertions.{assertEquals, assertNotEquals, assertTrue}
import org.junit.jupiter.api.{AfterEach, BeforeEach, Test}
import zio._

import java.util.concurrent.Executors
import scala.concurrent.{ExecutionContext, Future}

class ZioInstrumentationTest {
  private var tracer: Tracer = _

  @BeforeEach
  def setUp(): Unit = {
    tracer = GlobalOpenTelemetry.getTracer("zio-instrumentation-test")
  }

  @AfterEach
  def tearDown(): Unit = {
    tracer = null
  }

  @Test
  def respectOuterSpanWithUnsafeRunSync(): Unit =
    withOuterSpan("outer") { outer =>
      val observed = runZIO(ZIO.succeed(currentSpanContext))
      assertEquals(outer.getSpanContext, observed)
    }

  @Test
  def respectOuterSpanAndPropagateToLiftedFuture(): Unit =
    withOuterSpan("outer") { outer =>
      val observed =
        runZIO(
          ZIO.fromFuture { implicit ec =>
            Future(currentSpanContext)
          }
        )
      assertEquals(outer.getSpanContext, observed)
    }

  @Test
  def traceIsPropagatedToChildFiber(): Unit =
    withOuterSpan("outer") { outer =>
      val observed =
        runZIO {
          for {
            fiber <- ZIO.succeed(currentSpanContext).fork
            child <- fiber.join
          } yield child
        }
      assertEquals(outer.getSpanContext, observed)
    }

  @Test
  def traceIsPropagatedToChildFiberOnExternalExecutor(): Unit =
    withOuterSpan("outer") { outer =>
      val executor = Executors.newSingleThreadExecutor()
      try {
        val observed =
          runZIO {
            for {
              fiber <- ZIO
                .fromFuture(_ => Future(currentSpanContext)(ExecutionContext.fromExecutor(executor)))
                .fork
              child <- fiber.join
            } yield child
          }
        assertEquals(outer.getSpanContext, observed)
      } finally executor.shutdown()
    }

  @Test
  def traceIsPreservedWhenFiberIsInterrupted(): Unit =
    withOuterSpan("outer") { outer =>
      val observed =
        runZIO {
          for {
            started <- Promise.make[Nothing, Unit]
            fiber <- scopedSpan("interrupted-child") {
              started.succeed(()) *> ZIO.never
            }.fork
            _ <- started.await
            _ <- fiber.interrupt
            afterInterrupt <- ZIO.succeed(currentSpanContext)
          } yield afterInterrupt
        }
      assertEquals(outer.getSpanContext, observed)
    }

  @Test
  def synchronizedFibersDoNotInterfereWithEachOthersTraces(): Unit = {
    val results = runZIO {
      for {
        semaphore <- Semaphore.make(1)
        firstStarted <- Promise.make[Nothing, Unit]
        releaseFirst <- Promise.make[Nothing, Unit]
        firstFiber <- semaphore.withPermit(
          observeInsideNewSpanWithBarrier("first", firstStarted, releaseFirst)
        ).fork
        _ <- firstStarted.await
        secondFiber <- semaphore.withPermit(observeInsideNewSpan("second")).fork
        _ <- releaseFirst.succeed(())
        first <- firstFiber.join
        second <- secondFiber.join
      } yield List(first, second)
    }

    results.foreach(result => assertEquals(result.parent, result.child))
    assertNotEquals(results.head.parent.getTraceId, results(1).parent.getTraceId)
  }

  @Test
  def concurrentFibersDoNotInterfereWithEachOthersTraces(): Unit = {
    val results =
      runZIO {
        for {
          firstStarted <- Promise.make[Nothing, Unit]
          secondStarted <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          firstFiber <- observeInsideNewSpanWithBarrier("first", firstStarted, release).fork
          secondFiber <- observeInsideNewSpanWithBarrier("second", secondStarted, release).fork
          _ <- firstStarted.await.zipPar(secondStarted.await)
          _ <- release.succeed(())
          first <- firstFiber.join
          second <- secondFiber.join
        } yield List(first, second)
      }

    results.foreach { result =>
      assertEquals(result.parent, result.child)
    }
    assertNotEquals(results.head.parent.getTraceId, results(1).parent.getTraceId)
  }

  @Test
  def sequentialFibersDoNotInterfereWithEachOthersTraces(): Unit = {
    val results =
      runZIO {
        for {
          firstFiber <- observeInsideNewSpan("first").fork
          first <- firstFiber.join
          secondFiber <- observeInsideNewSpan("second").fork
          second <- secondFiber.join
        } yield List(first, second)
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
        runZIO {
          ZIO
            .succeed(currentSpanContext)
            .race(ZIO.sleep(Duration.fromMillis(250)) *> ZIO.succeed(currentSpanContext))
        }
      assertEquals(outer.getSpanContext, observed)
    }

  @Test
  def timeoutContextIsCorrect(): Unit =
    withOuterSpan("outer") { outer =>
      val observed =
        runZIO {
          for {
            value <- ZIO.succeed(currentSpanContext).timeout(Duration.fromSeconds(1))
            afterTimeout <- ZIO.succeed(currentSpanContext)
          } yield value.getOrElse(afterTimeout)
        }
      assertEquals(outer.getSpanContext, observed)
    }

  private def observeInsideNewSpan(name: String): ZIO[Any, Throwable, SpanObservation] =
    scopedSpan(name) {
      for {
        parent <- ZIO.succeed(currentSpanContext)
        fiber <- ZIO.succeed(currentSpanContext).fork
        child <- fiber.join
      } yield SpanObservation(parent, child)
    }

  private def observeInsideNewSpanWithBarrier(
    name: String,
    started: Promise[Nothing, Unit],
    release: Promise[Nothing, Unit]
  ): ZIO[Any, Throwable, SpanObservation] =
    scopedSpan(name) {
      for {
        before <- ZIO.succeed(currentSpanContext)
        _ <- started.succeed(())
        _ <- release.await
        _ <- ZIO.yieldNow
        after <- ZIO.succeed(currentSpanContext)
      } yield SpanObservation(before, after)
    }

  private def scopedSpan[A](name: String)(effect: ZIO[Any, Throwable, A]): ZIO[Any, Throwable, A] =
    ZIO.acquireReleaseWith(
      ZIO.attempt {
        val span = tracer.spanBuilder(name).startSpan()
        val scope = span.makeCurrent()
        (span, scope)
      }
    ) { case (span, scope) =>
      ZIO.succeed {
        scope.close()
        span.end()
      }
    }(_ => effect)

  private def withOuterSpan[A](name: String)(body: Span => A): A = {
    val span = tracer.spanBuilder(name).startSpan()
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
}
