package eu.wohlben.qits.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * The real thing, end to end in one Quarkus app: the SDK picks up these exporters through its
 * {@code cdi} providers, each one POSTs to the configured endpoint's signal path, and the
 * Authorization header is the oidc client's current token — refreshed as the stub's two-second
 * tokens expire.
 */
@QuarkusTest
@WithTestResource(StubPlatform.class)
class TelemetryExportTest {

  private static final Logger LOG = Logger.getLogger(TelemetryExportTest.class);
  private static final Duration WITHIN = Duration.ofSeconds(15);

  @Inject Tracer tracer;
  @Inject Meter meter;
  @Inject BearerHeaders headers;

  @Inject @Any Instance<SpanExporter> spanExporters;
  @Inject @Any Instance<LogRecordExporter> logExporters;
  @Inject @Any Instance<MetricExporter> metricExporters;

  @Test
  void theseBeansAreTheOnlyExportersQuarkusHolds() {
    assertInstanceOf(OtlpHttpSpanExporter.class, only(spanExporters));
    assertInstanceOf(OtlpHttpLogRecordExporter.class, only(logExporters));
    assertInstanceOf(OtlpHttpMetricExporter.class, only(metricExporters));
  }

  @Test
  void spansGoToTheTracesPathWithTheCurrentBearer() {
    tracer.spanBuilder("telemetry-core-probe").startSpan().end();
    assertBearerExport("traces");
  }

  @Test
  void logsGoToTheLogsPathWithTheCurrentBearer() {
    LOG.info("telemetry-core probe log line");
    assertBearerExport("logs");
  }

  @Test
  void metricsGoToTheMetricsPathWithTheCurrentBearer() {
    meter.counterBuilder("telemetry.core.probe").build().add(1);
    assertBearerExport("metrics");
  }

  @Test
  void aRefreshedTokenIsOnTheNextExport() throws InterruptedException {
    String before = headers.get().get(BearerHeaders.AUTHORIZATION);
    assertTrue(before.startsWith("Bearer token-"), before);
    Thread.sleep(Duration.ofSeconds(StubPlatform.EXPIRES_IN + 1).toMillis());
    String after = headers.get().get(BearerHeaders.AUTHORIZATION);
    assertTrue(after.startsWith("Bearer token-"), after);
    assertNotEquals(before, after, "the expired token was refreshed");

    StubPlatform.EXPORTS.clear();
    tracer.spanBuilder("telemetry-core-after-refresh").startSpan().end();
    StubPlatform.Export export =
        StubPlatform.await(StubPlatform.signal("traces"), WITHIN).orElseThrow();
    int minted = Integer.parseInt(after.substring("Bearer token-".length()));
    int sent = Integer.parseInt(export.authorization().substring("Bearer token-".length()));
    assertTrue(sent >= minted, "export carried " + export.authorization() + ", not older than " + after);
  }

  private static void assertBearerExport(String signal) {
    StubPlatform.Export export =
        StubPlatform.await(StubPlatform.signal(signal), WITHIN)
            .orElseThrow(() -> new AssertionError("no " + signal + " export in " + WITHIN));
    assertTrue(
        export.authorization() != null && export.authorization().startsWith("Bearer token-"),
        signal + " export carried " + export.authorization());
    assertEquals("application/x-protobuf", export.contentType());
    assertTrue(export.bytes() > 0, signal + " export had an empty body");
  }

  private static <T> T only(Instance<T> instance) {
    List<T> all = instance.stream().toList();
    assertEquals(1, all.size(), "exactly one bean: " + all);
    return all.get(0);
  }
}
