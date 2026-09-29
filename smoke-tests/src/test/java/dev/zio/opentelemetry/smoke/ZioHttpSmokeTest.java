package dev.zio.opentelemetry.smoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.util.JsonFormat;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.trace.v1.Span;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

class ZioHttpSmokeTest {
  private static final String TRACE_ID = "1234567890abcdef1234567890abcdef";
  private static final String PARENT_SPAN_ID = "1234567890abcdef";
  private static final String TRACEPARENT = "00-" + TRACE_ID + "-" + PARENT_SPAN_ID + "-01";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final OkHttpClient CLIENT =
      new OkHttpClient.Builder().readTimeout(Duration.ofSeconds(30)).build();

  @Test
  void manualAndAutomaticSpansShareOneTraceAcrossHttp() throws Exception {
    File agentJar = new File(System.getProperty("zio.smoketest.agent.jar"));
    assertTrue(agentJar.isFile(), "assembled agent JAR must exist");

    try (Network network = Network.newNetwork();
        GenericContainer<?> backend =
            new GenericContainer<>(
                    DockerImageName.parse(
                        "ghcr.io/open-telemetry/opentelemetry-java-instrumentation/smoke-test-fake-backend:20221127.3559314891"))
                .withExposedPorts(8080)
                .withNetwork(network)
                .withNetworkAliases("backend")
                .waitingFor(Wait.forHttp("/health").forPort(8080));
        GenericContainer<?> target =
            new GenericContainer<>(DockerImageName.parse("zio-opentelemetry-smoke:latest"))
                .withExposedPorts(8080)
                .withNetwork(network)
                .withCopyFileToContainer(
                    MountableFile.forHostPath(agentJar.getAbsolutePath()), "/agent.jar")
                .withEnv("JAVA_TOOL_OPTIONS", "-javaagent:/agent.jar -Dzio.trackFiberContext=true")
                .withEnv("OTEL_EXPORTER_OTLP_ENDPOINT", "http://backend:8080")
                .withEnv("OTEL_EXPORTER_OTLP_PROTOCOL", "grpc")
                .withEnv("OTEL_PROPAGATORS", "tracecontext,baggage")
                .withEnv("OTEL_BSP_SCHEDULE_DELAY", "10")
                .waitingFor(
                    Wait.forHttp("/downstream")
                        .forPort(8080)
                        .withStartupTimeout(Duration.ofMinutes(2)))) {
      backend.start();
      target.start();

      Request request =
          new Request.Builder()
              .url("http://" + target.getHost() + ":" + target.getMappedPort(8080) + "/welcome")
              .header("traceparent", TRACEPARENT)
              .build();
      String outboundSpanId;
      try (Response response = CLIENT.newCall(request).execute()) {
        assertEquals(200, response.code());
        assertEquals("welcome", response.body().string());
        assertEquals(TRACE_ID, response.header("x-current-trace-id"));
        String outboundTraceparent = response.header("traceparent");
        assertNotNull(outboundTraceparent, "outgoing HTTP call must inject traceparent");
        assertEquals(TRACE_ID, outboundTraceparent.split("-")[1]);
        outboundSpanId = outboundTraceparent.split("-")[2];
      }

      List<ExportTraceServiceRequest> exports = waitForSpans(backend, outboundSpanId);
      Span handler = findSpan(exports, "request.handler");
      Span manual = findSpan(exports, "zio.manual");
      Span outbound = findSpanById(exports, outboundSpanId);
      assertEquals(TRACE_ID, hex(handler.getTraceId().toByteArray()));
      assertEquals(PARENT_SPAN_ID, hex(handler.getParentSpanId().toByteArray()));
      assertEquals(
          hex(handler.getSpanId().toByteArray()), hex(manual.getParentSpanId().toByteArray()));
      assertEquals(TRACE_ID, hex(manual.getTraceId().toByteArray()));
      assertEquals(
          hex(manual.getSpanId().toByteArray()), hex(outbound.getParentSpanId().toByteArray()));
      assertTrue(
          manual.getAttributesList().stream()
              .anyMatch(
                  kv ->
                      kv.getKey().equals("smoke.value")
                          && kv.getValue().getStringValue().equals("welcome")));
      assertTrue(
          exports.stream()
              .flatMap(export -> export.getResourceSpansList().stream())
              .flatMap(resource -> resource.getResource().getAttributesList().stream())
              .anyMatch(
                  kv ->
                      kv.getKey().equals("telemetry.distro.name")
                          && kv.getValue().getStringValue().equals("zio-opentelemetry-java")));
      assertTrue(
          exports.stream()
              .flatMap(export -> export.getResourceSpansList().stream())
              .flatMap(resource -> resource.getResource().getAttributesList().stream())
              .anyMatch(
                  kv ->
                      kv.getKey().equals("telemetry.distro.version")
                          && !kv.getValue().getStringValue().isEmpty()));
    }
  }

  private static List<ExportTraceServiceRequest> waitForSpans(
      GenericContainer<?> backend, String outboundSpanId) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
    List<ExportTraceServiceRequest> exports = new ArrayList<>();
    while (System.nanoTime() < deadline) {
      Request request =
          new Request.Builder()
              .url(
                  "http://" + backend.getHost() + ":" + backend.getMappedPort(8080) + "/get-traces")
              .build();
      try (Response response = CLIENT.newCall(request).execute()) {
        JsonNode root = JSON.readTree(response.body().string());
        exports.clear();
        for (JsonNode item : root) {
          ExportTraceServiceRequest.Builder builder = ExportTraceServiceRequest.newBuilder();
          JsonFormat.parser().merge(JSON.writeValueAsString(item), builder);
          exports.add(builder.build());
        }
      }
      if (hasSpan(exports, "request.handler")
          && hasSpan(exports, "zio.manual")
          && hasSpanId(exports, outboundSpanId)) {
        return exports;
      }
      Thread.sleep(500);
    }
    throw new IOException("Timed out waiting for handler, manual, and outbound HTTP spans");
  }

  private static boolean hasSpan(List<ExportTraceServiceRequest> exports, String name) {
    return exports.stream()
        .flatMap(export -> export.getResourceSpansList().stream())
        .flatMap(resource -> resource.getScopeSpansList().stream())
        .flatMap(scope -> scope.getSpansList().stream())
        .anyMatch(span -> span.getName().equals(name));
  }

  private static boolean hasSpanId(List<ExportTraceServiceRequest> exports, String spanId) {
    return exports.stream()
        .flatMap(export -> export.getResourceSpansList().stream())
        .flatMap(resource -> resource.getScopeSpansList().stream())
        .flatMap(scope -> scope.getSpansList().stream())
        .anyMatch(span -> hex(span.getSpanId().toByteArray()).equals(spanId));
  }

  private static Span findSpanById(List<ExportTraceServiceRequest> exports, String spanId) {
    return exports.stream()
        .flatMap(export -> export.getResourceSpansList().stream())
        .flatMap(resource -> resource.getScopeSpansList().stream())
        .flatMap(scope -> scope.getSpansList().stream())
        .filter(span -> hex(span.getSpanId().toByteArray()).equals(spanId))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing outbound HTTP span: " + spanId));
  }

  private static Span findSpan(List<ExportTraceServiceRequest> exports, String name) {
    return exports.stream()
        .flatMap(export -> export.getResourceSpansList().stream())
        .flatMap(resource -> resource.getScopeSpansList().stream())
        .flatMap(scope -> scope.getSpansList().stream())
        .filter(span -> span.getName().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing span: " + name));
  }

  private static String hex(byte[] bytes) {
    StringBuilder result = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) {
      result.append(String.format("%02x", value & 0xff));
    }
    return result.toString();
  }
}
