package eu.wohlben.qits.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TelemetrySettingsTest {

  private static TelemetrySettings at(String endpoint) {
    return new TelemetrySettings(
        endpoint, true, "qits", Duration.ofSeconds(5), Duration.ofSeconds(10), "none");
  }

  @Test
  void appendsTheSignalPathTheReceiverServes() {
    TelemetrySettings settings = at("https://observability.qits.example.org/observability/api/otel");
    assertEquals(
        "https://observability.qits.example.org/observability/api/otel/v1/traces",
        settings.signalUrl("traces"));
    assertEquals(
        "https://observability.qits.example.org/observability/api/otel/v1/logs",
        settings.signalUrl("logs"));
    assertEquals(
        "https://observability.qits.example.org/observability/api/otel/v1/metrics",
        settings.signalUrl("metrics"));
  }

  @Test
  void aTrailingSlashOnTheBaseIsNotDoubled() {
    assertEquals(
        "http://localhost:8080/observability/api/otel/v1/logs",
        at("http://localhost:8080/observability/api/otel/ ").signalUrl("logs"));
  }
}
