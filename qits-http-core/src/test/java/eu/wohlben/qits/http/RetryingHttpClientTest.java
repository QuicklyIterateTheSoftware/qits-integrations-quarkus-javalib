package eu.wohlben.qits.http;

import static eu.wohlben.qits.http.ScriptedServer.hangUp;
import static eu.wohlben.qits.http.ScriptedServer.reset;
import static eu.wohlben.qits.http.ScriptedServer.respond;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The retry policy on the wire, through the client {@link QitsHttpClients#wrap} builds: a scripted
 * loopback server refuses, resets, hangs up on a TLS handshake or answers a switchover status, and
 * virtual time runs the thirty-second budget instantly. The scripted server stands in for the edge
 * on loopback, so the policy here opts into loopback retries; the default exemption has its own
 * tests at the end. One test class per transport; the REST
 * client's half is {@code RetryClientFilterTest}.
 */
class RetryingHttpClientTest {

  private final FakeTiming timing = new FakeTiming();
  private final List<String> warnings = Collections.synchronizedList(new ArrayList<>());
  private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
  private OpenTelemetrySdk otel;
  private ScriptedServer server;
  private HttpClient client;

  @BeforeEach
  void start() throws IOException {
    otel =
        OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                    .build())
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build();
    server = new ScriptedServer();
    client =
        QitsHttpClients.wrap(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
            otel,
            "qits-ci",
            RetryPolicy.standard()
                .withLoopbackRetries()
                .withTiming(timing)
                .withWarnings(warnings::add));
  }

  @AfterEach
  void stop() throws IOException {
    client.close();
    server.close();
    otel.close();
  }

  // --- connection-class failures

  @Test
  void aRefusedConnectionIsRetriedUntilThePeerIsBack() throws Exception {
    int port = closedPort();
    // The peer comes back during the second pause, on the very port that refused.
    List<ScriptedServer> revived = new ArrayList<>();
    timing.onPause(
        () -> {
          if (timing.pauses().size() == 2 && revived.isEmpty()) {
            revived.add(listenOn(port));
          }
        });
    try {
      HttpResponse<String> response =
          client.send(get("http://127.0.0.1:" + port + "/"), HttpResponse.BodyHandlers.ofString());

      assertEquals(200, response.statusCode());
      assertEquals(List.of(Duration.ofMillis(250), Duration.ofMillis(500)), timing.pauses());
      assertEquals(1, warnings.size(), warnings.toString());
      assertTrue(warnings.get(0).contains("GET 127.0.0.1:" + port), warnings.get(0));
      assertTrue(warnings.get(0).contains("got through after 3 attempts"), warnings.get(0));
    } finally {
      for (ScriptedServer s : revived) {
        s.close();
      }
    }
  }

  @Test
  void aConnectionResetBeforeAnyResponseByteIsRetriedForAGet() throws Exception {
    server.then(reset(), reset());

    HttpResponse<String> response =
        client.send(get(server.url("/")), HttpResponse.BodyHandlers.ofString());

    assertEquals(200, response.statusCode());
    assertEquals(3, server.connections());
    assertEquals(1, warnings.size(), warnings.toString());
  }

  @Test
  void aTlsHandshakeFailureIsRetriedEvenForAPost() throws Exception {
    server.otherwise(hangUp());

    var thrown =
        assertThrows(
            IOException.class,
            () ->
                client.send(
                    HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + server.port() + "/"))
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                    HttpResponse.BodyHandlers.ofString()));

    assertTrue(hasCause(thrown, SSLHandshakeException.class), thrown.toString());
    assertTrue(server.connections() > 1, "a handshake failure happens before the request is sent");
    assertEquals(Duration.ofSeconds(30), timing.total());
    assertEquals(1, warnings.size(), warnings.toString());
    assertTrue(warnings.get(0).contains("POST"), warnings.get(0));
    assertTrue(warnings.get(0).contains("gave up"), warnings.get(0));
  }

  @Test
  void anUnresolvableHostIsNotASwitchover() {
    assertEquals(
        RetryPolicy.Failure.OTHER,
        RetryPolicy.classify(
            new ConnectException("x") {
              {
                initCause(new java.nio.channels.UnresolvedAddressException());
              }
            }));
    assertEquals(
        RetryPolicy.Failure.OTHER, RetryPolicy.classify(new java.net.UnknownHostException("nope")));
    assertEquals(
        RetryPolicy.Failure.OTHER,
        RetryPolicy.classify(new java.net.http.HttpTimeoutException("request timed out")));
    assertEquals(
        RetryPolicy.Failure.BEFORE_SEND,
        RetryPolicy.classify(new java.net.http.HttpConnectTimeoutException("connect timed out")));
  }

  // --- switchover statuses

  @Test
  void a502A503AndA504WithRetryAfterAreRetried() throws Exception {
    server.then(
        respond(502, "Retry-After", "1"),
        respond(503, "Retry-After", "1"),
        respond(504, "Retry-After", "1"));

    HttpResponse<String> response =
        client.send(get(server.url("/")), HttpResponse.BodyHandlers.ofString());

    assertEquals(200, response.statusCode());
    assertEquals("status 200", response.body());
    assertEquals(4, server.connections());
    // Retry-After: 1 raises the 250 ms and 500 ms pauses to a second; the third is a second anyway.
    assertEquals(
        List.of(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)),
        timing.pauses());
    assertEquals(1, warnings.size(), warnings.toString());
    assertTrue(warnings.get(0).contains("4 attempts"), warnings.get(0));
  }

  @Test
  void a503WithoutRetryAfterIsAnAnswerNotASwitchover() throws Exception {
    server.otherwise(respond(503));

    HttpResponse<String> response =
        client.send(get(server.url("/")), HttpResponse.BodyHandlers.ofString());

    assertEquals(503, response.statusCode());
    assertEquals(1, server.connections());
    assertTrue(timing.pauses().isEmpty());
    assertTrue(warnings.isEmpty(), "a call that needed no retry says nothing");
  }

  @Test
  void a500IsNeverRetriedEvenWithRetryAfter() throws Exception {
    server.otherwise(respond(500, "Retry-After", "1"));

    HttpResponse<String> response =
        client.send(get(server.url("/")), HttpResponse.BodyHandlers.ofString());

    assertEquals(500, response.statusCode());
    assertEquals(1, server.connections());
    assertTrue(warnings.isEmpty());
  }

  @Test
  void aRetryAfterBeyondTheBudgetEndsTheRetriesAtOnce() throws Exception {
    server.otherwise(respond(503, "Retry-After", "60"));

    assertEquals(
        503, client.send(get(server.url("/")), HttpResponse.BodyHandlers.discarding()).statusCode());
    assertEquals(1, server.connections());
  }

  // --- the method rule

  @Test
  void putDeleteAndHeadAreRetriedLikeGet() throws Exception {
    for (String method : List.of("PUT", "DELETE", "HEAD")) {
      server.then(respond(503, "Retry-After", "0"));
      int before = server.connections();
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(server.url("/")))
              .method(
                  method,
                  method.equals("PUT")
                      ? HttpRequest.BodyPublishers.ofString("{}")
                      : HttpRequest.BodyPublishers.noBody())
              .build();
      assertEquals(
          200, client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode(), method);
      assertEquals(2, server.connections() - before, method);
    }
  }

  @Test
  void postAndPatchAreNeverRetriedOnceAResponseArrived() throws Exception {
    for (String method : List.of("POST", "PATCH")) {
      server.then(respond(503, "Retry-After", "1"));
      int before = server.connections();
      HttpResponse<Void> response =
          client.send(
              HttpRequest.newBuilder(URI.create(server.url("/")))
                  .method(method, HttpRequest.BodyPublishers.ofString("{}"))
                  .build(),
              HttpResponse.BodyHandlers.discarding());
      assertEquals(503, response.statusCode(), method);
      assertEquals(1, server.connections() - before, method);
    }
    assertTrue(warnings.isEmpty());
  }

  @Test
  void postAndPatchAreNeverRetriedAfterAMidRequestReset() throws Exception {
    for (String method : List.of("POST", "PATCH")) {
      server.then(reset());
      int before = server.connections();
      assertThrows(
          IOException.class,
          () ->
              client.send(
                  HttpRequest.newBuilder(URI.create(server.url("/")))
                      .method(method, HttpRequest.BodyPublishers.ofString("{}"))
                      .build(),
                  HttpResponse.BodyHandlers.discarding()),
          method);
      assertEquals(1, server.connections() - before, method);
    }
  }

  @Test
  void aPostIsRetriedWhenTheConnectionWasRefused() throws Exception {
    int port = closedPort();

    assertThrows(
        ConnectException.class,
        () ->
            client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build(),
                HttpResponse.BodyHandlers.discarding()));

    assertTrue(timing.pauses().size() > 1, timing.pauses().toString());
  }

  // --- the budget

  @Test
  void theThirtySecondBudgetGivesUpWithTheLastResponse() throws Exception {
    server.otherwise(respond(503, "Retry-After", "1"));

    HttpResponse<String> response =
        client.send(get(server.url("/")), HttpResponse.BodyHandlers.ofString());

    assertEquals(503, response.statusCode());
    assertEquals("status 503", response.body(), "the last response is returned whole");
    // 1, 1, 1, 2, 4, 8 — then 16 would overrun, and is cut to the 13 s left.
    assertEquals(
        List.of(1L, 1L, 1L, 2L, 4L, 8L, 13L),
        timing.pauses().stream().map(Duration::toSeconds).toList());
    assertEquals(Duration.ofSeconds(30), timing.total());
    assertEquals(8, server.connections());
    assertEquals(1, warnings.size(), "one warning for the whole call, not one per attempt");
    assertTrue(warnings.get(0).contains("gave up after 8 attempts"), warnings.get(0));
    assertTrue(warnings.get(0).contains("HTTP 503"), warnings.get(0));
  }

  @Test
  void theBudgetGivesUpWithTheLastError() throws Exception {
    int port = closedPort();

    assertThrows(
        ConnectException.class,
        () -> client.send(get("http://127.0.0.1:" + port + "/"), HttpResponse.BodyHandlers.ofString()));

    assertEquals(
        List.of(250L, 500L, 1000L, 2000L, 4000L, 8000L, 14_250L),
        timing.pauses().stream().map(Duration::toMillis).toList());
    assertEquals(Duration.ofSeconds(30), timing.total());
    assertEquals(1, warnings.size(), warnings.toString());
    assertTrue(warnings.get(0).contains("gave up after 8 attempts"), warnings.get(0));
    assertTrue(warnings.get(0).contains("ConnectException"), warnings.get(0));
  }

  @Test
  void aZeroBudgetIsOneAttempt() throws Exception {
    HttpClient once =
        QitsHttpClients.wrap(
            HttpClient.newHttpClient(), otel, null, RetryPolicy.none().withTiming(timing));
    server.otherwise(respond(503, "Retry-After", "1"));

    assertEquals(503, once.send(get(server.url("/")), HttpResponse.BodyHandlers.discarding()).statusCode());
    assertEquals(1, server.connections());
  }

  // --- what cannot be replayed

  @Test
  void aBodyThatCannotBeReplayedIsSentOnce() throws Exception {
    server.otherwise(respond(503, "Retry-After", "1"));

    HttpResponse<Void> response =
        client.send(
            HttpRequest.newBuilder(URI.create(server.url("/")))
                .PUT(
                    HttpRequest.BodyPublishers.ofInputStream(
                        () -> new ByteArrayInputStream("{}".getBytes())))
                .build(),
            HttpResponse.BodyHandlers.discarding());

    assertEquals(503, response.statusCode());
    assertEquals(1, server.connections());
  }

  @Test
  void anEventStreamIsLeftToItsSubscriber() throws Exception {
    server.otherwise(respond(503, "Retry-After", "1"));

    HttpResponse<Void> response =
        client.send(
            HttpRequest.newBuilder(URI.create(server.url("/events")))
                .header("Accept", "text/event-stream")
                .build(),
            HttpResponse.BodyHandlers.discarding());

    assertEquals(503, response.statusCode());
    assertEquals(1, server.connections());
  }

  @Test
  void aStreamedBodyIsClosedBeforeTheRetry() throws Exception {
    server.then(respond(503, "Retry-After", "0"));

    HttpResponse<java.io.InputStream> response =
        client.send(get(server.url("/")), HttpResponse.BodyHandlers.ofInputStream());

    try (var body = response.body()) {
      assertEquals(200, response.statusCode());
      assertEquals("status 200", new String(body.readAllBytes()));
    }
  }

  // --- async, and the trace

  @Test
  void sendAsyncRetriesWithoutBlocking() throws Exception {
    server.then(respond(503, "Retry-After", "1"));

    HttpResponse<String> response =
        client
            .sendAsync(get(server.url("/")), HttpResponse.BodyHandlers.ofString())
            .get(10, TimeUnit.SECONDS);

    assertEquals(200, response.statusCode());
    assertEquals(2, server.connections());
    assertEquals(1, warnings.size(), warnings.toString());
  }

  @Test
  void sendAsyncGivesUpWithTheLastError() throws Exception {
    int port = closedPort();

    var thrown =
        assertThrows(
            ExecutionException.class,
            () ->
                client
                    .sendAsync(get("http://127.0.0.1:" + port + "/"), HttpResponse.BodyHandlers.ofString())
                    .get(10, TimeUnit.SECONDS));

    assertInstanceOf(ConnectException.class, thrown.getCause());
    assertEquals(Duration.ofSeconds(30), timing.total());
    assertEquals(1, warnings.size());
  }

  @Test
  void everyAttemptIsItsOwnClientSpanAndAResendSaysSo() throws Exception {
    server.then(respond(503, "Retry-After", "1"));

    client.send(get(server.url("/")), HttpResponse.BodyHandlers.discarding());

    List<SpanData> spans = exporter.getFinishedSpanItems();
    assertEquals(2, spans.size());
    assertEquals(null, spans.get(0).getAttributes().get(TracingHttpClient.HTTP_REQUEST_RESEND_COUNT));
    assertEquals(503L, spans.get(0).getAttributes().get(TracingHttpClient.HTTP_RESPONSE_STATUS_CODE));
    assertEquals(1L, spans.get(1).getAttributes().get(TracingHttpClient.HTTP_REQUEST_RESEND_COUNT));
    assertEquals(200L, spans.get(1).getAttributes().get(TracingHttpClient.HTTP_RESPONSE_STATUS_CODE));
  }

  // --- the loopback exemption

  @Test
  void byDefaultALoopbackTargetGetsOneAttempt() throws Exception {
    HttpClient standard =
        QitsHttpClients.wrap(
            HttpClient.newHttpClient(),
            otel,
            null,
            RetryPolicy.standard().withTiming(timing).withWarnings(warnings::add));
    server.otherwise(respond(503, "Retry-After", "1"));

    assertEquals(
        503, standard.send(get(server.url("/")), HttpResponse.BodyHandlers.discarding()).statusCode());
    assertEquals(
        503,
        standard
            .send(
                get("http://localhost:" + server.port() + "/"),
                HttpResponse.BodyHandlers.discarding())
            .statusCode());
    assertEquals(2, server.connections(), "one attempt each, by address and by name");

    int port = closedPort();
    assertThrows(
        ConnectException.class,
        () -> standard.send(get("http://127.0.0.1:" + port + "/"), HttpResponse.BodyHandlers.discarding()));
    assertThrows(
        ExecutionException.class,
        () ->
            standard
                .sendAsync(get("http://127.0.0.1:" + port + "/"), HttpResponse.BodyHandlers.discarding())
                .get(10, TimeUnit.SECONDS));
    assertTrue(timing.pauses().isEmpty(), "a closed local port fails at once");
    assertTrue(warnings.isEmpty());
  }

  @Test
  void loopbackIsReadOffTheUriWithoutResolvingAnything() {
    for (String url :
        List.of(
            "http://localhost:8080/x",
            "http://LOCALHOST/",
            "http://localhost./",
            "http://qits-ci.localhost/",
            "http://127.0.0.1:1/",
            "http://127.255.3.4/",
            "http://[::1]:8080/",
            "http://[0:0:0:0:0:0:0:1]/")) {
      assertTrue(RetryPolicy.loopback(URI.create(url)), url);
    }
    for (String url :
        List.of(
            "https://qits-ci.qits.example.org/",
            "http://qits-ci:8080/",
            "http://10.0.0.1/",
            "http://128.0.0.1/",
            "http://localhost.example.org/",
            "http://[::2]/",
            "http://[fe80::1]/")) {
      assertTrue(!RetryPolicy.loopback(URI.create(url)), url);
    }
    assertTrue(!RetryPolicy.standard().retries(URI.create("http://localhost:8080/")));
    assertTrue(RetryPolicy.standard().retries(URI.create("https://qits-ci.qits.example.org/")));
    assertTrue(
        RetryPolicy.standard().withLoopbackRetries().retries(URI.create("http://127.0.0.1/")));
    assertTrue(
        !RetryPolicy.none().withLoopbackRetries().retries(URI.create("https://qits-ci.qits.example.org/")));
  }

  // --- configuration

  @Test
  void theBudgetPropertyReadsBothSpellings() {
    assertEquals(Duration.ofSeconds(10), RetryPolicy.parseBudget("PT10S"));
    assertEquals(Duration.ofSeconds(10), RetryPolicy.parseBudget("10s"));
    assertEquals(Duration.ofSeconds(10), RetryPolicy.parseBudget("10"));
    assertEquals(Duration.ofMillis(500), RetryPolicy.parseBudget("500ms"));
    assertEquals(Duration.ZERO, RetryPolicy.parseBudget("0"));
    assertThrows(IllegalArgumentException.class, () -> RetryPolicy.parseBudget("soon"));
  }

  private static HttpRequest get(String url) {
    return HttpRequest.newBuilder(URI.create(url)).build();
  }

  private static int closedPort() throws IOException {
    try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return s.getLocalPort();
    }
  }

  private static ScriptedServer listenOn(int port) {
    try {
      return ScriptedServer.on(port);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static boolean hasCause(Throwable thrown, Class<? extends Throwable> type) {
    for (Throwable t = thrown; t != null; t = t.getCause()) {
      if (type.isInstance(t)) {
        return true;
      }
    }
    return false;
  }
}
