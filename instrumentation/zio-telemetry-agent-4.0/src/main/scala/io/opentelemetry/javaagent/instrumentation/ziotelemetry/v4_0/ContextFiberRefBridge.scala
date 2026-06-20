package io.opentelemetry.javaagent.instrumentation.ziotelemetry.v4_0

import io.opentelemetry.context.Context
import zio.{Differ, FiberRef, Trace, UIO, Unsafe, ZIO}

final class ContextFiberRefBridge private (
  rawDelegate: FiberRef[AnyRef] { type Patch = Any }
) extends FiberRef.Proxy[Context](ContextFiberRefBridge.delegate) {

  override def initial: Context =
    ContextFiberRefBridge.toApplicationContext(rawDelegate.initial)

  override def diff(oldValue: Context, newValue: Context): Patch =
    rawDelegate.diff(
      ContextFiberRefBridge.toAgentContext(oldValue),
      ContextFiberRefBridge.toAgentContext(newValue)
    ).asInstanceOf[Patch]

  override def combine(first: Patch, second: Patch): Patch =
    rawDelegate.combine(first.asInstanceOf[Any], second.asInstanceOf[Any]).asInstanceOf[Patch]

  override def patch(patch: Patch)(oldValue: Context): Context =
    ContextFiberRefBridge.toApplicationContext(
      rawDelegate.patch(patch.asInstanceOf[Any])(ContextFiberRefBridge.toAgentContext(oldValue))
    )

  override def fork: Patch =
    rawDelegate.fork.asInstanceOf[Patch]

  override def join(oldValue: Context, newValue: Context): Context =
    ContextFiberRefBridge.toApplicationContext(
      rawDelegate.join(
        ContextFiberRefBridge.toAgentContext(oldValue),
        ContextFiberRefBridge.toAgentContext(newValue)
      )
    )

  override def get(implicit trace: Trace): UIO[Context] =
    rawDelegate.get.map(ContextFiberRefBridge.toApplicationContext)

  override def getWith[R, E, B](f: Context => ZIO[R, E, B])(implicit trace: Trace): ZIO[R, E, B] =
    get.flatMap(f)

  override def locally[R, E, B](newValue: Context)(zio: ZIO[R, E, B])(implicit trace: Trace): ZIO[R, E, B] =
    ZIO.acquireReleaseWith(getAndSet(newValue))(set)(_ => zio)

  override def set(value: Context)(implicit trace: Trace): UIO[Unit] =
    rawDelegate.set(ContextFiberRefBridge.toAgentContext(value))

  override def modify[B](f: Context => (B, Context))(implicit trace: Trace): UIO[B] =
    rawDelegate.modify { rawValue =>
      val (result, updated) = f(ContextFiberRefBridge.toApplicationContext(rawValue))
      (result, ContextFiberRefBridge.toAgentContext(updated))
    }
}

object ContextFiberRefBridge {
  @volatile private var cached: FiberRef[Context] = _

  private val delegate: FiberRef.WithPatch[Context, Any] =
    Unsafe.unsafe { implicit unsafe =>
      FiberRef.unsafe.makePatch[Context, Any](
        Context.root(),
        ContextPatchDiffer,
        Context.root()
      )
    }

  private object ContextPatchDiffer extends Differ[Context, Any] {
    override def combine(first: Any, second: Any): Any = second

    override def diff(oldValue: Context, newValue: Context): Any = newValue

    override def empty: Any = Context.root()

    override def patch(patch: Any)(oldValue: Context): Context = patch.asInstanceOf[Context]
  }

  def wrap(rawFiberRef: AnyRef): FiberRef[Context] =
    if (cached != null) cached
    else synchronized {
      if (cached == null) {
        cached =
          new ContextFiberRefBridge(rawFiberRef.asInstanceOf[FiberRef[AnyRef] { type Patch = Any }])
      }
      cached
    }

  private[ziotelemetry] def toAgentContext(value: Context): AnyRef =
    if (value == null) {
      invokeStorage("getAgentContext", Context.root()).asInstanceOf[AnyRef]
    } else {
      try {
        val toAgentContext = value.getClass.getDeclaredMethod("toAgentContext")
        toAgentContext.setAccessible(true)
        toAgentContext.invoke(value).asInstanceOf[AnyRef]
      } catch {
        case _: NoSuchMethodException =>
          invokeStorage("getAgentContext", value).asInstanceOf[AnyRef]
      }
    }

  private[ziotelemetry] def toApplicationContext(value: AnyRef): Context =
    value match {
      case null =>
        Context.root()
      case ctx: Context =>
        ctx
      case other =>
        invokeStorage("toApplicationContext", other).asInstanceOf[Context]
    }

  private def invokeStorage(methodName: String, argument: AnyRef): AnyRef = {
    val storageClass = Class.forName(
      "io.opentelemetry.javaagent.instrumentation.opentelemetryapi.context.AgentContextStorage"
    )

    storageClass.getMethods.iterator
      .filter(method => method.getName == methodName && method.getParameterCount == 1)
      .flatMap { method =>
        try Some(method.invoke(null, argument).asInstanceOf[AnyRef])
        catch {
          case _: IllegalArgumentException => None
        }
      }
      .toSeq
      .headOption
      .getOrElse(throw new NoSuchMethodException(s"$methodName(${argument.getClass.getName})"))
  }
}
