package io.opentelemetry.javaagent.instrumentation.zio.v2_1

import io.opentelemetry.context.{Context, ContextKey}
import java.lang.reflect.InvocationTargetException
import org.junit.jupiter.api.Assertions.{assertEquals, assertThrows}
import org.junit.jupiter.api.Test

class ContextLeakDetectionTest {
  @Test
  def agentRejectsNonRootContextAtLeakCheckAndAcceptsRestoration(): Unit = {
    val check = Class.forName(
      "io.opentelemetry.javaagent.shaded.instrumentation.api.internal.ContextPropagationDebug"
    ).getMethod("debugContextLeakIfEnabled")
    val key = ContextKey.named[String]("intentional-leak-probe")
    val scope = Context.root().`with`(key, "probe").makeCurrent()
    try {
      val thrown = assertThrows(classOf[InvocationTargetException], () => { check.invoke(null); () })
      assertEquals(classOf[IllegalStateException], thrown.getCause.getClass)
      assertEquals("Context leak detected", thrown.getCause.getMessage)
    } finally scope.close()
    check.invoke(null)
  }
}
