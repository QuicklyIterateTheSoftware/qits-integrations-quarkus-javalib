package eu.wohlben.qits.telemetry;

import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporterBuilder;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporterBuilder;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporterBuilder;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

/**
 * The three OTLP/HTTP exporters, as CDI beans, each built from {@link TelemetrySettings} with the
 * shared {@link BearerHeaders} supplier.
 *
 * <p><b>How Quarkus (3.34.6) takes them</b>, one signal at a time — measured from its bytecode, not
 * recalled:
 *
 * <ul>
 *   <li><b>Traces.</b> With {@code quarkus.otel.traces.exporter=cdi} (the default), the SDK's
 *       autoconfigure asks Quarkus' {@code SpanExporterCDIProvider}, which composes every {@code
 *       SpanExporter} bean, and wraps the result in a {@code BatchSpanProcessor} tuned by {@code
 *       quarkus.otel.bsp.*}. Quarkus' own OTLP span processor ({@code LateBoundSpanProcessor}) is
 *       still created, sees a {@code SpanExporter} bean exists, and stands down as the removable
 *       no-op — so a span is exported once, here.
 *   <li><b>Logs.</b> With {@code quarkus.otel.logs.exporter=cdi} (the default), {@code
 *       OtlpExporterProcessor} creates its own {@code LogRecordExporter} only when bean discovery
 *       found none; this producer is one, so Quarkus' is never made, and {@code
 *       LogsExporterCDIProvider} answers this bean.
 *   <li><b>Metrics.</b> Exactly the logs' rule, through {@code MetricsExporterCDIProvider}, with
 *       {@code quarkus.otel.metrics.exporter=cdi} (the default) and {@code
 *       quarkus.otel.metrics.enabled=true}.
 * </ul>
 *
 * <p>So adopting needs no exporter key at all; the estate spells the three {@code cdi} values out
 * anyway, because a changed default must be a diff and not a silence. {@code
 * quarkus.otel.exporter.otlp.*} no longer reaches any exporter once this jar is on the classpath.
 *
 * <p><b>Built when the SDK asks, never at boot.</b> The producers run when Quarkus' OpenTelemetry
 * initialises, which a {@code quarkus.otel.sdk.disabled=true} profile (every service's {@code
 * %dev} and {@code %test}) never does — so those profiles need neither the endpoint's {@code
 * QITS_DOMAIN} nor an oidc client.
 *
 * <p><b>Opt-out</b> ({@code qits.telemetry.bearer.enabled=false}): no header supplier at all and
 * the oidc client is never touched, for qits-observability exporting to itself on localhost and
 * qits-edge staying internal (E1). They set {@code qits.telemetry.endpoint} to the address they
 * export to today.
 *
 * <p><b>{@code @Unremovable}</b> because the only consumers are Quarkus' CDI exporter providers,
 * which look the beans up programmatically ({@code CDI.current().select}); ArC cannot see that, and
 * would otherwise be free to drop a bean nothing injects.
 */
@Singleton
public class TelemetryExporters {

  private static final Logger LOG = Logger.getLogger(TelemetryExporters.class);

  @Inject BearerHeaders bearer;

  @Produces
  @Singleton
  @Unremovable
  public SpanExporter spanExporter() {
    TelemetrySettings settings = settings("traces");
    OtlpHttpSpanExporterBuilder builder =
        OtlpHttpSpanExporter.builder()
            .setComponentLoader(JdkSenderLoader.INSTANCE)
            .setEndpoint(settings.signalUrl("traces"))
            .setTimeout(settings.timeout())
            .setCompression(settings.compression());
    if (settings.bearer()) {
      builder.setHeaders(bearer);
    }
    return builder.build();
  }

  @Produces
  @Singleton
  @Unremovable
  public LogRecordExporter logRecordExporter() {
    TelemetrySettings settings = settings("logs");
    OtlpHttpLogRecordExporterBuilder builder =
        OtlpHttpLogRecordExporter.builder()
            .setComponentLoader(JdkSenderLoader.INSTANCE)
            .setEndpoint(settings.signalUrl("logs"))
            .setTimeout(settings.timeout())
            .setCompression(settings.compression());
    if (settings.bearer()) {
      builder.setHeaders(bearer);
    }
    return builder.build();
  }

  @Produces
  @Singleton
  @Unremovable
  public MetricExporter metricExporter() {
    TelemetrySettings settings = settings("metrics");
    OtlpHttpMetricExporterBuilder builder =
        OtlpHttpMetricExporter.builder()
            .setComponentLoader(JdkSenderLoader.INSTANCE)
            .setEndpoint(settings.signalUrl("metrics"))
            .setTimeout(settings.timeout())
            .setCompression(settings.compression());
    if (settings.bearer()) {
      builder.setHeaders(bearer);
    }
    return builder.build();
  }

  private static TelemetrySettings settings(String signal) {
    TelemetrySettings settings = TelemetrySettings.read();
    LOG.debugf(
        "OTLP %s exporter -> %s (%s)",
        signal,
        settings.signalUrl(signal),
        settings.bearer() ? "bearer from oidc client " + settings.client() : "no bearer");
    return settings;
  }
}
