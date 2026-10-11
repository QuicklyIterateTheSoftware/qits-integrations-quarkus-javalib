package eu.wohlben.qits.http;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * The REST-client half of the outgoing side: every REST-client request this process sends names
 * it, as the {@link CallerHeader#NAME} header. Trace context needs nothing here — Quarkus'
 * OpenTelemetry already instruments the REST client — so this is the caller header alone, which
 * nothing else sends. Raw {@code java.net.http.HttpClient} traffic gets both from {@link
 * QitsHttpClients}.
 *
 * <p>{@code @Provider} is the whole registration, as for qits-environment-core's {@code
 * EnvironmentClientFilter}, whose shape this is: Quarkus applies an annotated client filter to
 * every REST client in the application, and a consumer without the REST client never instantiates
 * it. <b>A header the caller set itself wins</b>, and a process with no name to give stamps none.
 */
@Provider
@Priority(Priorities.HEADER_DECORATOR)
public class CallerClientFilter implements ClientRequestFilter {

  @Override
  public void filter(ClientRequestContext requestContext) {
    if (requestContext.getHeaders().containsKey(CallerHeader.NAME)) {
      return;
    }
    CallerHeader.value()
        .ifPresent(caller -> requestContext.getHeaders().add(CallerHeader.NAME, caller));
  }
}
