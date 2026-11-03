package eu.wohlben.qits.http;

import io.opentelemetry.api.OpenTelemetry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.net.http.HttpClient;

/**
 * The one door a service's raw {@link HttpClient} goes through on its way to being used: {@link
 * #wrap} hands back a client that opens a CLIENT span per request and sends the W3C trace context
 * and {@link CallerHeader#NAME} with it — the two things Quarkus' OpenTelemetry gives the REST
 * client and never gives {@code java.net.http}.
 *
 * <pre>{@code
 * @Inject QitsHttpClients clients;
 *
 * HttpClient client = clients.wrap(HttpClient.newBuilder()
 *     .connectTimeout(Duration.ofSeconds(5))
 *     .build());
 * }</pre>
 *
 * <p>Every wrapped client also retries an edge switchover under {@link RetryPolicy}: connection
 * failures before the request was sent, resets and {@code 502}/{@code 503}/{@code 504} with {@code
 * Retry-After} for idempotent methods, 250 ms doubling within 30 s — and never for a loopback
 * target, which does not cross the edge. The bean takes the budget from
 * {@value RetryPolicy#BUDGET_PROPERTY}.
 *
 * <p>The OpenTelemetry is whatever the application produces: the SDK under quarkus-opentelemetry,
 * Quarkus' own no-op when the SDK is disabled, and {@link OpenTelemetry#noop()} when nothing
 * produces one at all — in which case the wrapper still stamps the caller and simply records
 * nothing. Wrapping never fails a service over tracing.
 *
 * <p>Build one client and keep it, as one would the raw one; {@code wrap} allocates a wrapper, not
 * a connection pool, and wrapping a wrapped client returns it unchanged. qits-arch-rules' {@code
 * HttpClientRules} is what holds a service to this: a class that builds an {@code HttpClient}
 * either reaches this bean or says why it does not with {@link RawHttpClient}.
 */
@ApplicationScoped
public class QitsHttpClients {

  private final OpenTelemetry openTelemetry;
  private final String caller;
  private final RetryPolicy retry;

  /**
   * For the client proxy ArC puts in front of a normal-scoped bean, and for nothing else: the
   * proxy delegates every call to the instance the injecting constructor built.
   */
  QitsHttpClients() {
    this.openTelemetry = null;
    this.caller = null;
    this.retry = null;
  }

  @Inject
  QitsHttpClients(Instance<OpenTelemetry> openTelemetry) {
    this.openTelemetry =
        openTelemetry.isResolvable() ? openTelemetry.get() : OpenTelemetry.noop();
    this.caller = CallerHeader.value().orElse(null);
    this.retry = RetryPolicy.fromConfig();
  }

  private QitsHttpClients(OpenTelemetry openTelemetry, String caller, RetryPolicy retry) {
    this.openTelemetry = openTelemetry;
    this.caller = caller;
    this.retry = retry;
  }

  /**
   * A bean built by hand, for a test or any other code that constructs its HTTP-calling class
   * itself and has to hand it the door CDI would have injected. {@code of(OpenTelemetry.noop(),
   * null)} is the inert one: no span, no header, the raw client's behaviour exactly.
   */
  public static QitsHttpClients of(OpenTelemetry openTelemetry, String caller) {
    return of(openTelemetry, caller, RetryPolicy.standard());
  }

  /**
   * A hand-built bean with its own retry policy — {@link RetryPolicy#none()} for no retries at
   * all, {@code standard().withLoopbackRetries()} for a test whose stand-in edge is local.
   */
  public static QitsHttpClients of(OpenTelemetry openTelemetry, String caller, RetryPolicy retry) {
    return new QitsHttpClients(openTelemetry, caller, retry);
  }

  /**
   * The traced, caller-stamped, switchover-patient view of {@code raw}, sharing its pool, executor
   * and lifecycle.
   */
  public HttpClient wrap(HttpClient raw) {
    return wrap(raw, openTelemetry, caller, retry);
  }

  /**
   * The same wrapper without CDI, for code that has its own {@link OpenTelemetry} and name — a
   * test, or a class built before the container is. A {@code null} caller stamps no header.
   */
  public static HttpClient wrap(HttpClient raw, OpenTelemetry openTelemetry, String caller) {
    return wrap(raw, openTelemetry, caller, RetryPolicy.standard());
  }

  /** The same, with an explicit {@link RetryPolicy}. */
  public static HttpClient wrap(
      HttpClient raw, OpenTelemetry openTelemetry, String caller, RetryPolicy retry) {
    if (raw instanceof TracingHttpClient) {
      return raw;
    }
    return new TracingHttpClient(raw, openTelemetry, caller, retry);
  }
}
