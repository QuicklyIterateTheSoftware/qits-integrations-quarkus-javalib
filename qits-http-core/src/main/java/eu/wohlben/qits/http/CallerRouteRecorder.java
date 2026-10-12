package eu.wohlben.qits.http;

import io.opentelemetry.api.trace.Span;
import io.vertx.core.Context;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.impl.HttpServerRequestInternal;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * The incoming half for EVERY request on the Quarkus HTTP router, not only the JAX-RS ones: a
 * request carrying {@link CallerHeader#NAME} gets its caller recorded on its SERVER span as {@link
 * CallerHeader#ATTRIBUTE}, then routing carries on untouched.
 *
 * <p><b>Why a route and not only {@link CallerServerFilter}.</b> Services serve part of their
 * surface as plain Vert.x routes registered off {@code @Observes Router} — qits-githost's smart-HTTP
 * and blob reads, qits-artifacts' maven/OCI/npm/sbom registries. A JAX-RS filter never runs there,
 * so those server spans joined the caller's trace and still did not say who called. One route at
 * {@link #ROUTE_ORDER} sits in front of all of them. The filter stays: it is what covers a consumer
 * that runs JAX-RS without this router (and it writes the same value, so the double set is
 * harmless).
 *
 * <p><b>Which span.</b> Quarkus' OpenTelemetry opens the SERVER span in its Vert.x tracer's {@code
 * receiveRequest}, before the router is asked, and attaches it to the request's own duplicated
 * context ({@code QuarkusContextStorage} keeps it as a context local). {@link Span#current()} reads
 * that same local off whichever context is current — the right one at the start of routing, but
 * not certainly once anything asynchronous ran first (a body handler ahead of this route, a future
 * completed elsewhere; qits-edge's {@code RequestSpans} documents the failure). So the span is read
 * off the request's OWN context first, by the local's key, and {@link Span#current()} is only the
 * fallback for when that cannot be reached: no Quarkus OpenTelemetry, a request that is not
 * Vert.x-internal, or a Quarkus that renamed the key. Without any OpenTelemetry the fallback is the
 * invalid span and the write goes nowhere — the right outcome, not an error.
 *
 * <p>The key is matched by name, not through {@code QuarkusContextStorage}, so quarkus-opentelemetry
 * stays out of this pom, as everywhere in this module.
 *
 * <p>Inert outside an HTTP application: with no {@link Router} bean the observer never fires. It
 * never ends a request and never fails one; it always calls {@code next()}.
 */
@ApplicationScoped
public class CallerRouteRecorder {

  /**
   * Ahead of every application route on the estate — default-ordered {@code @Observes Router}
   * routes count up from 0, the earliest explicit order is 100, Quarkus REST sits at 10 000 — and
   * ahead of Quarkus' upload limit (-2) and compression (0). Quarkus' own {@code Integer.MIN_VALUE}
   * plumbing (access log, headers, CORS, body) still runs first, which costs nothing: the span
   * exists before the router is asked at all, and this reads it off the request's own context.
   */
  static final int ROUTE_ORDER = -10_000;

  /**
   * The context local {@code io.quarkus.opentelemetry.runtime.QuarkusContextStorage} keeps the
   * request's OpenTelemetry context under ({@code QuarkusContextStorage.class.getName() +
   * ".otelContext"}).
   */
  static final String OTEL_CONTEXT_KEY =
      "io.quarkus.opentelemetry.runtime.QuarkusContextStorage.otelContext";

  void register(@Observes Router router) {
    router.route().order(ROUTE_ORDER).handler(this::handle);
  }

  void handle(RoutingContext routingContext) {
    try {
      String caller = CallerServerFilter.parse(routingContext.request().getHeader(CallerHeader.NAME));
      if (caller != null) {
        serverSpan(routingContext.request()).setAttribute(CallerHeader.ATTRIBUTE, caller);
      }
    } catch (RuntimeException | LinkageError bookkeeping) {
      // Diagnostics only: a request must never fail over its trace annotation.
    }
    routingContext.next();
  }

  /** The span attached to the request's own context, else the current one. */
  static Span serverSpan(HttpServerRequest request) {
    if (request instanceof HttpServerRequestInternal internal) {
      Context context = internal.context();
      Object otel = context == null ? null : context.getLocal(OTEL_CONTEXT_KEY);
      if (otel instanceof io.opentelemetry.context.Context attached) {
        Span span = Span.fromContext(attached);
        if (span.getSpanContext().isValid()) {
          return span;
        }
      }
    }
    return Span.current();
  }
}
