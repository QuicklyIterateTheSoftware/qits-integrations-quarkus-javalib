package eu.wohlben.qits.http;

import io.opentelemetry.api.trace.Span;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * The incoming half: a request carrying {@link CallerHeader#NAME} gets its caller recorded on the
 * server span as {@link CallerHeader#ATTRIBUTE}, so even a span whose parent never arrived says
 * which application sent it.
 *
 * <p>{@link Span#current()} is the SERVER span here because Quarkus' OpenTelemetry opens it before
 * routing and Quarkus REST runs filters on the request's own (duplicated) Vert.x context, where
 * that span is current. Without quarkus-opentelemetry the current span is the invalid one and the
 * write goes nowhere — which is the right outcome, not an error.
 *
 * <p>The value is the caller's claim and is treated as data: trimmed, capped at {@value #MAX_LENGTH}
 * characters so a hostile or broken caller cannot bloat every span, and ignored when blank. An
 * absent header records nothing — an unnamed caller is unknown, not a value.
 */
@Provider
@Priority(Priorities.HEADER_DECORATOR)
public class CallerServerFilter implements ContainerRequestFilter {

  static final int MAX_LENGTH = 128;

  @Override
  public void filter(ContainerRequestContext requestContext) {
    String caller = parse(requestContext.getHeaderString(CallerHeader.NAME));
    if (caller != null) {
      Span.current().setAttribute(CallerHeader.ATTRIBUTE, caller);
    }
  }

  /** The header as a recordable value, or {@code null} for absent or blank. */
  static String parse(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    return trimmed.length() > MAX_LENGTH ? trimmed.substring(0, MAX_LENGTH) : trimmed;
  }
}
