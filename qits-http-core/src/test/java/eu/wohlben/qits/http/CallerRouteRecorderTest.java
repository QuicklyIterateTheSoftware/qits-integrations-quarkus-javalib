package eu.wohlben.qits.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.http.impl.HttpServerRequestInternal;
import io.vertx.ext.web.Router;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The recorder on a real Vert.x router and HTTP server, the way a service's plain routes see it.
 * Quarkus is absent, so its two arrangements are staged by a route ahead of the recorder: either a
 * span made current around the rest of routing (the {@link Span#current()} fallback) or an
 * OpenTelemetry context put on the request's own context under Quarkus' key (what its tracer does).
 * An application route registered after the recorder answers, which proves {@code next()}.
 */
class CallerRouteRecorderTest {

  private static final AttributeKey<String> CALLER = AttributeKey.stringKey(CallerHeader.ATTRIBUTE);
  private static final int STAGE_ORDER = Integer.MIN_VALUE;

  private static Vertx vertx;
  private static HttpClient client;

  private InMemorySpanExporter exporter;
  private SdkTracerProvider provider;
  private HttpServer server;
  private Router router;

  @BeforeAll
  static void startVertx() {
    vertx = Vertx.vertx();
    client = vertx.createHttpClient();
  }

  @AfterAll
  static void stopVertx() throws Exception {
    vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  @BeforeEach
  void setUp() {
    exporter = InMemorySpanExporter.create();
    provider =
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    router = Router.router(vertx);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (server != null) {
      server.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
    provider.close();
  }

  @Test
  void theCallerIsRecordedOnTheCurrentSpan() throws Exception {
    Span span = provider.get("test").spanBuilder("GET /git").startSpan();
    stage(ctx -> {
      try (Scope ignored = span.makeCurrent()) {
        ctx.next();
      }
    });

    assertEquals("served", get("  qits-ci "));
    span.end();

    assertEquals("qits-ci", only().getAttributes().get(CALLER));
  }

  @Test
  void theRequestsOwnContextWinsOverTheCurrentSpan() throws Exception {
    Span own = provider.get("test").spanBuilder("own").startSpan();
    Span other = provider.get("test").spanBuilder("other").startSpan();
    stage(ctx -> {
      ((HttpServerRequestInternal) ctx.request())
          .context()
          .putLocal(
              CallerRouteRecorder.OTEL_CONTEXT_KEY,
              io.opentelemetry.context.Context.root().with(own));
      try (Scope ignored = other.makeCurrent()) {
        ctx.next();
      }
    });

    assertEquals("served", get("qits-artifacts"));
    own.end();
    other.end();

    List<SpanData> ended = exporter.getFinishedSpanItems();
    assertEquals("qits-artifacts", byName(ended, "own").getAttributes().get(CALLER));
    assertNull(byName(ended, "other").getAttributes().get(CALLER));
  }

  @Test
  void anAbsentCallerRecordsNothingAndRoutingGoesOn() throws Exception {
    Span span = provider.get("test").spanBuilder("GET /git").startSpan();
    stage(ctx -> {
      try (Scope ignored = span.makeCurrent()) {
        ctx.next();
      }
    });

    assertEquals("served", get(null));
    span.end();

    assertNull(only().getAttributes().get(CALLER));
  }

  @Test
  void withoutAnySpanTheRequestStillAnswers() throws Exception {
    start();
    assertEquals("served", get("qits-ci"));
  }

  /** A staging route ahead of the recorder, then the recorder and an application route. */
  private void stage(Consumer<io.vertx.ext.web.RoutingContext> staging) throws Exception {
    router.route().order(STAGE_ORDER).handler(staging::accept);
    start();
  }

  private void start() throws Exception {
    // The application route first, as a service's own observer might be: order, not registration
    // sequence, has to put the recorder in front of it.
    router.get("/git/repo/blob/main/README.md").handler(ctx -> ctx.response().end("served"));
    new CallerRouteRecorder().register(router);
    server =
        vertx
            .createHttpServer()
            .requestHandler(router)
            .listen(0)
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
  }

  private String get(String caller) throws Exception {
    RequestOptions options =
        new RequestOptions()
            .setMethod(HttpMethod.GET)
            .setHost("localhost")
            .setPort(server.actualPort())
            .setURI("/git/repo/blob/main/README.md");
    if (caller != null) {
      options.putHeader(CallerHeader.NAME, caller);
    }
    return client
        .request(options)
        .compose(request -> request.send())
        .compose(HttpClientResponse::body)
        .toCompletionStage()
        .toCompletableFuture()
        .get(10, TimeUnit.SECONDS)
        .toString();
  }

  private SpanData only() {
    List<SpanData> ended = exporter.getFinishedSpanItems();
    assertEquals(1, ended.size());
    return ended.getFirst();
  }

  private static SpanData byName(List<SpanData> spans, String name) {
    return spans.stream().filter(s -> s.getName().equals(name)).findFirst().orElseThrow();
  }
}
