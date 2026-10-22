package example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TimeoutContextIntegrationTest {
  private static final String RESULT_PREFIX = "ZIO_TIMEOUT_RESULT ";
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void contextSurvivesSuccessfulTimeout() throws Exception {
    assertScenario("fast", true);
  }

  @Test
  void contextSurvivesExpiredTimeout() throws Exception {
    assertScenario("expired", false);
  }

  private static void assertScenario(String scenario, boolean resultPresent) throws Exception {
    JsonNode result = launch(scenario);
    assertEquals(scenario, result.path("scenario").asText());
    assertTrue(result.path("timedResultPresent").isBoolean(), "Missing timeout outcome");
    assertEquals(resultPresent, result.path("timedResultPresent").asBoolean());

    JsonNode before = result.path("before");
    JsonNode inside = result.path("inside");
    JsonNode after = result.path("after");
    assertTrue(inside.isObject(), "Timed effect must execute and capture its context");

    String traceId = before.path("java").path("traceId").asText();
    String spanId = before.path("java").path("spanId").asText();
    assertTrue(traceId.matches("[0-9a-f]{32}"), "Invalid trace ID: " + traceId);
    assertTrue(spanId.matches("[0-9a-f]{16}"), "Invalid span ID: " + spanId);
    assertNotEquals("00000000000000000000000000000000", traceId);
    assertNotEquals("0000000000000000", spanId);

    for (JsonNode snapshot : Arrays.asList(before, inside, after)) {
      for (String context : Arrays.asList("java", "zio")) {
        assertEquals(
            traceId, snapshot.path(context).path("traceId").asText(), scenario + " " + context);
        assertEquals(
            spanId, snapshot.path(context).path("spanId").asText(), scenario + " " + context);
      }
    }
  }

  private static JsonNode launch(String scenario) throws Exception {
    String javaExecutable = System.getProperty("zio.timeout.java.executable");
    String agentJar = System.getProperty("zio.timeout.agent.jar");
    String classpath = System.getProperty("zio.timeout.app.classpath");
    assertNotNull(javaExecutable);
    assertNotNull(agentJar);
    assertNotNull(classpath);

    List<String> command =
        Arrays.asList(
            javaExecutable,
            "-javaagent:" + agentJar,
            "-Dotel.instrumentation.zio-2.0.enabled=false",
            "-Dotel.traces.exporter=none",
            "-Dotel.metrics.exporter=none",
            "-Dotel.logs.exporter=none",
            "-cp",
            classpath,
            "example.TimeoutContextApp",
            scenario);
    Path outputFile = Files.createTempFile("zio-timeout-" + scenario + "-", ".log");
    Process process = null;
    try {
      process =
          new ProcessBuilder(command)
              .redirectErrorStream(true)
              .redirectOutput(outputFile.toFile())
              .start();
      if (!process.waitFor(45, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        fail("Timed out waiting for " + scenario + " child JVM; output:\n" + read(outputFile));
      }
      String output = read(outputFile);
      assertEquals(0, process.exitValue(), "Child JVM failed:\n" + output);
      String resultLine = null;
      for (String line : output.split("\\R")) {
        if (line.startsWith(RESULT_PREFIX)) {
          assertTrue(resultLine == null, "Child JVM emitted multiple results:\n" + output);
          resultLine = line.substring(RESULT_PREFIX.length());
        }
      }
      assertNotNull(resultLine, "Child JVM emitted no result:\n" + output);
      return JSON.readTree(resultLine);
    } finally {
      if (process != null) {
        process.destroyForcibly();
      }
      Files.deleteIfExists(outputFile);
    }
  }

  private static String read(Path outputFile) throws Exception {
    return new String(Files.readAllBytes(outputFile), StandardCharsets.UTF_8);
  }
}
