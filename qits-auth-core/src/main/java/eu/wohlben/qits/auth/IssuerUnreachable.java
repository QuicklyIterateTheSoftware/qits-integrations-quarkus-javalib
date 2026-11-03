package eu.wohlben.qits.auth;

import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

/**
 * Answers 503 when a request could not be authenticated because qits-idp was unreachable.
 *
 * <p><b>Why this exists.</b> quarkus-oidc initializes its tenant lazily — on the first request that
 * presents a credential — and if the issuer cannot be reached at that moment it throws {@code
 * OIDCException}. Nothing maps that, so it reaches Quarkus' own error handler and becomes a
 * <b>500</b> with an error id. A 500 tells a caller its request was wrong; the request was fine and
 * the platform was briefly unable to check a token. Callers act on that difference: the announcement
 * paths between qits-ci and qits-platform-deployments are fire-and-forget and swallow failures at
 * debug, so a 500 there is a deployment that silently never happens.
 *
 * <p><b>Only connectivity counts.</b> {@code OIDCException} also covers configuration mistakes — a
 * wrong audience, an unparseable key — and those are genuinely this service's fault and stay 500. So
 * the chain has to show BOTH an OIDC failure AND a connectivity cause before this answers 503.
 * Retrying a misconfiguration would just move the failure to the caller's patience.
 *
 * <p><b>Matched by name, not by type,</b> and that is deliberate: {@code quarkus-oidc} is not a
 * dependency of this library. A service that validates no bearers (it only uses the machine gate
 * switched off) does not carry the extension, and adding it here to reference one exception class would put an issuer client on
 * their classpath to satisfy an import. The name is API — it is what a caller reads in a log line.
 *
 * <p>This handles the window that {@code quarkus.oidc.connection-delay} does not: that key retries
 * the connection while the process is STARTING, so it covers a cold boot racing idp. An idp that
 * goes away later is this class's case.
 */
@ApplicationScoped
public class IssuerUnreachable {

  /**
   * Ahead of Quarkus' own failure handler, which is what turns this into a 500. A failure handler
   * that does not recognise the failure calls {@code next()}, so ordering first costs nothing to
   * anything else — it only guarantees this one is asked.
   */
  static final int ROUTE_ORDER = -1_000;

  /** See the class note: the name rather than the type, so quarkus-oidc stays out of the poms. */
  private static final String OIDC_EXCEPTION = "io.quarkus.oidc.OIDCException";

  /**
   * Short on purpose. The failure this reports is a service coming back, usually in seconds — a
   * cutover window or a restart — and a caller that waits minutes has turned a blip into an outage.
   */
  private static final String RETRY_AFTER_SECONDS = "5";

  void register(@Observes Router router) {
    router.route().order(ROUTE_ORDER).failureHandler(this::handle);
  }

  private void handle(RoutingContext context) {
    if (context.response().ended() || !causedByUnreachableIssuer(context.failure())) {
      context.next();
      return;
    }
    context
        .response()
        .setStatusCode(503)
        .putHeader("Retry-After", RETRY_AFTER_SECONDS)
        .putHeader("Content-Type", "application/json")
        .end("{\"message\":\"the token issuer is unreachable, and this request was not "
            + "authenticated; retry\"}");
  }

  /**
   * How deep the walk goes before giving up. A cause chain can be a CYCLE: {@code initCause} refuses
   * self-causation, but nothing stops A causing B causing A, and this runs on an event-loop thread
   * where a spin is not one slow request but the whole process. Bounded is simpler than a visited
   * set and no real chain comes close — the one this exists for is three deep.
   */
  private static final int MAX_CAUSE_DEPTH = 32;

  /**
   * Both halves, anywhere in the chain: an OIDC failure, and a cause that is the network rather than
   * the configuration.
   */
  static boolean causedByUnreachableIssuer(Throwable failure) {
    boolean oidc = false;
    boolean connectivity = false;
    Throwable cause = failure;
    for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++, cause = cause.getCause()) {
      if (OIDC_EXCEPTION.equals(cause.getClass().getName())) {
        oidc = true;
      }
      if (cause instanceof ConnectException
          || cause instanceof UnknownHostException
          || cause instanceof TimeoutException) {
        connectivity = true;
      }
    }
    return oidc && connectivity;
  }
}
