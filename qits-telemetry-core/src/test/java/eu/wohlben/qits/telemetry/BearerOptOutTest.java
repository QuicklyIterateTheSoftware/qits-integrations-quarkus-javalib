package eu.wohlben.qits.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Tracer;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * {@code qits.telemetry.bearer.enabled=false}: the shape qits-observability (self-export on
 * localhost) and qits-edge (E1, internal) adopt. Every signal still goes to the configured
 * endpoint, none carries an Authorization header, and the oidc client is never asked for a token —
 * the stub's token endpoint stays at zero mints even though the client is configured and enabled.
 */
@QuarkusTest
@TestProfile(BearerOptOutTest.OptOut.class)
@WithTestResource(StubPlatform.class)
class BearerOptOutTest {

  public static class OptOut implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("qits.telemetry.bearer.enabled", "false");
    }
  }

  private static final Logger LOG = Logger.getLogger(BearerOptOutTest.class);
  private static final Duration WITHIN = Duration.ofSeconds(15);

  @Inject Tracer tracer;
  @Inject Meter meter;

  @Test
  void everySignalKeepsTheStaticEndpointWithoutABearer() {
    tracer.spanBuilder("opt-out-probe").startSpan().end();
    LOG.info("opt-out probe log line");
    meter.counterBuilder("telemetry.core.optout").build().add(1);

    for (String signal : new String[] {"traces", "logs", "metrics"}) {
      StubPlatform.Export export =
          StubPlatform.await(StubPlatform.signal(signal), WITHIN)
              .orElseThrow(() -> new AssertionError("no " + signal + " export in " + WITHIN));
      assertEquals(null, export.authorization(), signal + " carried a bearer");
    }
    assertTrue(
        StubPlatform.EXPORTS.stream().allMatch(e -> e.authorization() == null),
        StubPlatform.EXPORTS.toString());
    assertEquals(0, StubPlatform.MINTED.get(), "the oidc client was asked for a token");
  }
}
