package example

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import java.net.{HttpURLConnection, URL}
import scala.jdk.CollectionConverters._
import zio._
import zio.http._
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.agent.{OpenTelemetry => AgentOpenTelemetry}
import zio.telemetry.opentelemetry.core.trace.Tracer

object Server extends ZIOAppDefault {
  private val getter = new TextMapGetter[Headers] {
    override def keys(carrier: Headers): java.lang.Iterable[String] =
      carrier.headers.map(_.headerName).asJava

    override def get(carrier: Headers, key: String): String =
      carrier.get(key).orNull
  }

  private def downstreamCall: Task[String] =
    ZIO.attemptBlocking {
      val connection = new URL("http://localhost:8080/downstream").openConnection().asInstanceOf[HttpURLConnection]
      try {
        val stream = connection.getInputStream
        try scala.io.Source.fromInputStream(stream).mkString
        finally stream.close()
      } finally connection.disconnect()
    }

  private def routes(tracer: Tracer): Routes[Any, Nothing] =
    Routes(
      Method.GET / "welcome" -> handler { (request: Request) =>
        val telemetry = GlobalOpenTelemetry.get()
        val parent = telemetry.getPropagators.getTextMapPropagator.extract(Context.root(), request.headers, getter)
        ZIO.acquireReleaseWith(
          ZIO.attempt {
            val span = telemetry.getTracer("zio-http-smoke").spanBuilder("request.handler").setParent(parent).startSpan()
            (span, span.makeCurrent())
          }
        ) { case (span, scope) =>
          ZIO.succeed {
            scope.close()
            span.end()
          }
        } { _ =>
          tracer.span("zio.manual") { span =>
            for {
              _ <- span.setAttribute("smoke.value", "welcome")
              echoedTraceparent <- downstreamCall
              current <- ZIO.succeed(Span.current().getSpanContext)
            } yield Response.text("welcome").addHeader(Header.Custom("traceparent", echoedTraceparent))
              .addHeader(Header.Custom("x-current-trace-id", current.getTraceId))
          }
        }.orDie
      },
      Method.GET / "downstream" -> handler { (request: Request) =>
        ZIO.succeed(Response.text(request.headers.get("traceparent").getOrElse("missing")))
      }
    )

  override def run: Task[Unit] =
    (for {
      tracer <- ZIO.service[Tracer]
      _ <- ZIO.logInfo("Started ZIO HTTP smoke server")
      _ <- zio.http.Server.serve(routes(tracer)).provide(zio.http.Server.defaultWithPort(8080))
    } yield ()).provide(
      AgentOpenTelemetry.global(),
      OpenTelemetry.tracer("zio-http-smoke")
    )
}
