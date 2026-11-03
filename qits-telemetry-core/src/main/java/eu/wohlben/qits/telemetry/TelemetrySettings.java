package eu.wohlben.qits.telemetry;

import java.time.Duration;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Everything the three exporters are built from, read once when the SDK asks for them.
 *
 * <p><b>Read lazily, from {@link ConfigProvider}, never injected.</b> Quarkus validates every
 * {@code @ConfigProperty} injection point at boot, and the endpoint's default expands {@code
 * ${QITS_DOMAIN}}, which carries no default anywhere on the estate on purpose. An injected field
 * would refuse to boot every adopting service's {@code %test} profile, where the OpenTelemetry SDK
 * is disabled and the exporters are never built. Read here, the endpoint is resolved exactly when
 * an exporter is, and a process that really exports with no domain fails at that moment, naming
 * the key.
 *
 * @param endpoint the OTLP/HTTP base, without the {@code /v1/<signal>} suffix
 * @param bearer whether the exporters attach the named oidc client's token at all
 * @param client the {@code quarkus.oidc-client.<name>} that mints it
 * @param tokenTimeout how long one export may wait for a token that is not cached yet
 * @param timeout the export request's own timeout
 * @param compression {@code none} or {@code gzip}
 */
public record TelemetrySettings(
    String endpoint,
    boolean bearer,
    String client,
    Duration tokenTimeout,
    Duration timeout,
    String compression) {

  public static final String PREFIX = "qits.telemetry.";
  public static final String ENDPOINT = PREFIX + "endpoint";
  public static final String BEARER_ENABLED = PREFIX + "bearer.enabled";
  public static final String BEARER_CLIENT = PREFIX + "bearer.client";
  public static final String BEARER_TIMEOUT = PREFIX + "bearer.timeout";
  public static final String TIMEOUT = PREFIX + "timeout";
  public static final String COMPRESSION = PREFIX + "compression";

  /** The current values, from the running application's config. */
  public static TelemetrySettings read() {
    return from(ConfigProvider.getConfig());
  }

  static TelemetrySettings from(Config config) {
    return new TelemetrySettings(
        config.getValue(ENDPOINT, String.class),
        config.getOptionalValue(BEARER_ENABLED, Boolean.class).orElse(true),
        config.getOptionalValue(BEARER_CLIENT, String.class).orElse("qits"),
        config.getOptionalValue(BEARER_TIMEOUT, Duration.class).orElse(Duration.ofSeconds(5)),
        config.getOptionalValue(TIMEOUT, Duration.class).orElse(Duration.ofSeconds(10)),
        config.getOptionalValue(COMPRESSION, String.class).orElse("none"));
  }

  /**
   * The full URL for one signal. The upstream builders take the WHOLE path, unlike Quarkus' own
   * exporter, which appends {@code /v1/<signal>} to a base itself — so the base is configured the
   * way every service already spells {@code quarkus.otel.exporter.otlp.endpoint}, and the suffix is
   * added here.
   *
   * @param signal {@code traces}, {@code logs} or {@code metrics}
   */
  public String signalUrl(String signal) {
    String base = endpoint.strip();
    while (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    return base + "/v1/" + signal;
  }
}
