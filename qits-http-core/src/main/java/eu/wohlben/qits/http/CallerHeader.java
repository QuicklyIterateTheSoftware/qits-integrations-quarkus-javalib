package eu.wohlben.qits.http;

import java.util.Optional;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * The HTTP header that names the calling application on a service-to-service request, and the span
 * attribute the receiver records it as — the wire and trace spellings of one concept.
 *
 * <p><b>The value is the caller's deployed identity</b>, the very string its own spans carry as
 * {@code service.name}: {@code quarkus.otel.service.name}, falling back to {@code
 * quarkus.application.name} — the same fallback Quarkus' OpenTelemetry applies. So the receiving
 * span's {@link #ATTRIBUTE} and the calling span's resource line up character for character, and a
 * trace that lost its parent still says whose request it was.
 *
 * <p><b>The name is inside the edge's reserved namespace on purpose.</b> qits-platform-edge strips
 * every client-supplied {@code X-Qits-*} header before it proxies, so an outside caller cannot
 * pose as a service in the trace. Service-to-service traffic does not pass the edge and carries
 * the header untouched.
 *
 * <p><b>It is diagnostic, not authentication.</b> Nothing may authorize on it: a caller is free to
 * state any name, and the receiver records what it was told. The authenticated principal stays
 * {@code X-Qits-User} and the bearer behind it; this header only makes the trace legible.
 */
public final class CallerHeader {

  public static final String NAME = "X-Qits-Caller";

  /**
   * The span attribute {@link CallerServerFilter} and {@link CallerRouteRecorder} record an inbound
   * caller as.
   */
  public static final String ATTRIBUTE = "qits.caller";

  static final String SERVICE_NAME = "quarkus.otel.service.name";
  static final String APPLICATION_NAME = "quarkus.application.name";

  private CallerHeader() {}

  /**
   * This process's name as a caller, or empty where neither property says anything readable — in
   * which case nothing is stamped, because an invented name is worse than none.
   */
  public static Optional<String> value() {
    return value(ConfigProvider.getConfig());
  }

  static Optional<String> value(Config config) {
    return nonBlank(config.getOptionalValue(SERVICE_NAME, String.class))
        .or(() -> nonBlank(config.getOptionalValue(APPLICATION_NAME, String.class)));
  }

  private static Optional<String> nonBlank(Optional<String> value) {
    return value.map(String::trim).filter(v -> !v.isEmpty());
  }
}
