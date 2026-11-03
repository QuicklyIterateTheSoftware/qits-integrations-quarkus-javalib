package eu.wohlben.qits.telemetry;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * The header supplier all three exporters share: {@code Authorization: Bearer <current token>},
 * asked afresh for every export request, so a refreshed token is on the very next POST.
 *
 * <p><b>No token, no header — and one WARN, not one per batch.</b> The export still goes out: the
 * edge refuses it, and that 401 is visible where a dropped batch is not. The WARN is said when the
 * token goes missing and not again until one has come back (which is said at INFO), so an idp
 * outage is one line at its start rather than three per export interval. One instance is shared by
 * the span, log and metric exporters, so the three signals say it once between them.
 *
 * <p>The WARN itself becomes a log record the log exporter ships — once, which is why it cannot
 * feed back on itself.
 */
@Singleton
public class BearerHeaders implements Supplier<Map<String, String>> {

  private static final Logger LOG = Logger.getLogger(BearerHeaders.class);

  public static final String AUTHORIZATION = "Authorization";

  private final BearerSource source;

  /** True while the last answer carried no token; the WARN is said on its rising edge only. */
  private final AtomicBoolean missing = new AtomicBoolean(false);

  @Inject
  public BearerHeaders(OidcBearerSource source) {
    this((BearerSource) source);
  }

  public BearerHeaders(BearerSource source) {
    this.source = source;
  }

  @Override
  public Map<String, String> get() {
    BearerSource.Lookup lookup;
    try {
      lookup = source.lookup();
    } catch (RuntimeException e) {
      lookup = BearerSource.Lookup.none(e.toString());
    }
    if (lookup.present()) {
      if (missing.compareAndSet(true, false)) {
        LOG.info("Telemetry export has a bearer again");
      }
      return Map.of(AUTHORIZATION, "Bearer " + lookup.token());
    }
    if (missing.compareAndSet(false, true)) {
      LOG.warnf(
          "Exporting telemetry WITHOUT a bearer, so the edge will refuse it: %s. Said once; the"
              + " next line is the INFO when a token is back.",
          lookup.reason());
    }
    return Map.of();
  }
}
