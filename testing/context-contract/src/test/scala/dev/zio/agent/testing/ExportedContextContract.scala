package dev.zio.agent.testing

import io.opentelemetry.api.trace.{Span, SpanId}
import io.opentelemetry.instrumentation.testing.junit.{AgentInstrumentationExtension, InstrumentationExtension}
import org.junit.jupiter.api.Assertions.{assertEquals, assertFalse, assertTrue}
import org.junit.jupiter.api.{Test, Timeout}
import org.junit.jupiter.api.extension.RegisterExtension
import zio._

import java.util.concurrent.Executors
import scala.jdk.CollectionConverters._

/** Runs the same propagation contract with Java scopes and library-managed scopes. */
@Timeout(30)
abstract class ExportedContextContract {
  @RegisterExtension val testing: InstrumentationExtension = AgentInstrumentationExtension.create()

  protected trait Spans {
    def apply[A](name: String)(effect: Task[A]): Task[A]
  }
  protected def withTracing[A](effect: Spans => Task[A]): A

  @Test
  def interruptionEndsAndExportsChildBeforeRestoringParent(): Unit = {
    withTracing { span =>
      span("parent") {
        for {
          parent <- ZIO.succeed(Span.current().getSpanContext)
          started <- Promise.make[Nothing, Unit]
          child <- span("interrupted")(started.succeed(()) *> ZIO.never).fork
          _ <- started.await
          exit <- child.interrupt
          _ <- ZIO.succeed(assertFalse(exit.isSuccess))
          _ <- ZIO.succeed(assertEquals(parent, Span.current().getSpanContext))
          _ <- span("after-interrupt")(ZIO.unit)
        } yield ()
      }
    }
    assertForest(Map("parent" -> None, "interrupted" -> Some("parent"), "after-interrupt" -> Some("parent")))
  }

  @Test
  def synchronizedFibersRestoreContextAcrossAToBToA(): Unit = {
    withTracing { span =>
      for {
        aStarted <- Promise.make[Nothing, Unit]
        bFinished <- Promise.make[Nothing, Unit]
        a <- span("a") {
          aStarted.succeed(()) *> bFinished.await *> ZIO.yieldNow *> span("a-child")(ZIO.unit)
        }.fork
        b <- (aStarted.await *> span("b")(span("b-child")(ZIO.unit))
          .ensuring(bFinished.succeed(()))).fork
        _ <- a.join
        _ <- b.join
        _ <- ZIO.succeed(assertFalse(Span.current().getSpanContext.isValid))
      } yield ()
    }
    assertForest(Map("a" -> None, "a-child" -> Some("a"), "b" -> None, "b-child" -> Some("b")))
  }

  @Test
  def concurrentFibersExportIndependentNestedTreesAfterResuming(): Unit = {
    withTracing { span =>
      for {
        ready <- ZIO.foreach(1 to 3)(_ => Promise.make[Nothing, Unit])
        release <- Promise.make[Nothing, Unit]
        fibers <- ZIO.foreach(ready.zipWithIndex) { case (started, index) =>
          span(s"root-$index") {
            started.succeed(()) *> release.await *> ZIO.yieldNow *> span(s"child-$index")(ZIO.unit)
          }.fork
        }
        _ <- ZIO.foreachDiscard(ready)(_.await)
        _ <- release.succeed(())
        _ <- ZIO.foreachDiscard(fibers)(_.join)
        _ <- ZIO.succeed(assertFalse(Span.current().getSpanContext.isValid))
      } yield ()
    }
    assertForest((0 until 3).flatMap(i => List(s"root-$i" -> None, s"child-$i" -> Some(s"root-$i"))).toMap)
  }

  @Test
  def sequentialFibersExportIndependentNestedTrees(): Unit = {
    withTracing { span =>
      ZIO.foreachDiscard(1 to 3) { i =>
        span(s"root-$i")(span(s"child-$i")(ZIO.unit)).fork.flatMap(_.join) *>
          ZIO.succeed(assertFalse(Span.current().getSpanContext.isValid))
      }
    }
    assertForest((1 to 3).flatMap(i => List(s"root-$i" -> None, s"child-$i" -> Some(s"root-$i"))).toMap)
  }

  @Test
  def fiberRunsAndResumesOnAnExternalZioExecutor(): Unit = {
    val executor = Executors.newSingleThreadExecutor((task: Runnable) => new Thread(task, "zio-contract-external"))
    try {
      withTracing { span =>
        span("parent") {
          span("external-child") {
            for {
              before <- ZIO.succeed(Thread.currentThread().getName)
              _ <- ZIO.yieldNow
              after <- ZIO.succeed(Thread.currentThread().getName)
              _ <- ZIO.succeed(assertEquals("zio-contract-external", before))
              _ <- ZIO.succeed(assertEquals("zio-contract-external", after))
              _ <- span("external-grandchild")(ZIO.unit)
            } yield ()
          }.onExecutor(Executor.fromJavaExecutor(executor)).fork.flatMap(_.join)
        }
      }
      assertForest(Map("parent" -> None, "external-child" -> Some("parent"),
        "external-grandchild" -> Some("external-child")))
    } finally executor.shutdownNow()
  }

  @Test
  def raceExportsBothBranchesAndRestoresParentAfterLoserCleanup(): Unit = {
    withTracing { span =>
      span("parent") {
        for {
          loserStarted <- Promise.make[Nothing, Unit]
          loserFinalized <- Promise.make[Nothing, Unit]
          winner <- span("winner")(loserStarted.await.as("winner")).race(
            span("loser")(loserStarted.succeed(()) *> ZIO.never)
              .ensuring(loserFinalized.succeed(()))
          )
          _ <- ZIO.succeed(assertEquals("winner", winner))
          finalized <- loserFinalized.isDone
          _ <- ZIO.succeed(assertTrue(finalized))
          _ <- span("after-race")(ZIO.unit)
        } yield ()
      }
    }
    assertForest(Map("parent" -> None, "winner" -> Some("parent"), "loser" -> Some("parent"),
      "after-race" -> Some("parent")))
  }

  @Test
  def actualTimeoutEndsChildAndRestoresParent(): Unit = {
    withTracing { span =>
      span("parent") {
        for {
          finalized <- Promise.make[Nothing, Unit]
          result <- span("timed-out")(ZIO.never).ensuring(finalized.succeed(()))
            .timeout(Duration.fromMillis(50))
          _ <- ZIO.succeed(assertEquals(None, result))
          isFinalized <- finalized.isDone
          _ <- ZIO.succeed(assertTrue(isFinalized))
          _ <- span("after-timeout")(ZIO.unit)
        } yield ()
      }
    }
    assertForest(Map("parent" -> None, "timed-out" -> Some("parent"), "after-timeout" -> Some("parent")))
  }

  protected def assertForest(expected: Map[String, Option[String]]): Unit = {
    val roots = expected.count(_._2.isEmpty)
    val traces = testing.waitForTraces(roots).asScala.toList
    val spans = traces.flatMap(_.asScala)
    assertEquals(roots, traces.size)
    assertEquals(expected.size, spans.size, s"Exported spans: ${spans.map(_.getName)}")
    assertEquals(expected.keySet, spans.map(_.getName).toSet)
    val byName = spans.map(s => s.getName -> s).toMap
    expected.foreach { case (name, parent) =>
      val child = byName(name)
      parent match {
        case Some(parentName) =>
          assertEquals(byName(parentName).getSpanId, child.getParentSpanId, name)
          assertEquals(byName(parentName).getTraceId, child.getTraceId, name)
        case None => assertEquals(SpanId.getInvalid, child.getParentSpanId, name)
      }
    }
    assertEquals(roots, expected.collect { case (name, None) => byName(name).getTraceId }.toSet.size)
  }
}
