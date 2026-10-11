package eu.wohlben.qits.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The wrapper on the wire: a real JDK server on loopback records the headers each request
 * actually arrived with, and a real SDK records the spans the wrapper ended. No Quarkus — the
 * wrapper is plain {@code java.net.http}, and so is the proof.
 */
class TracingHttpClientTest {

  private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
  private final BlockingQueue<HttpHeaders> received = new LinkedBlockingQueue<>();
  private OpenTelemetrySdk otel;
  private HttpServer server;
  private HttpClient raw;
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
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          received.add(HttpHeaders.of(exchange.getRequestHeaders(), (n, v) -> true));
          int status = exchange.getRequestURI().getPath().startsWith("/boom") ? 503 : 200;
          exchange.sendResponseHeaders(status, -1);
          exchange.close();
        });
    server.start();
    raw = HttpClient.newHttpClient();
    client = QitsHttpClients.wrap(raw, otel, "qits-ci");
  }

  @AfterEach
  void stop() {
    client.close();
    server.stop(0);
    otel.close();
  }

  @Test
  void sendCarriesTheTraceContextOfItsOwnClientSpanAndTheCaller() throws Exception {
    Span parent = otel.getTracer("test").spanBuilder("parent").startSpan();
    try (Scope ignored = parent.makeCurrent()) {
      HttpResponse<Void> response =
          client.send(request("/things?token=secret"), HttpResponse.BodyHandlers.discarding());
      assertEquals(200, response.statusCode());
    } finally {
      parent.end();
    }

    HttpHeaders headers = arrived();
    assertEquals("qits-ci", headers.firstValue(CallerHeader.NAME).orElseThrow());

    SpanData clientSpan = onlyClientSpan();
    assertEquals("GET", clientSpan.getName());
    assertEquals(parent.getSpanContext().getTraceId(), clientSpan.getTraceId());
    assertEquals(parent.getSpanContext().getSpanId(), clientSpan.getParentSpanId());
    String traceparent = headers.firstValue("traceparent").orElseThrow();
    assertEquals(
        "00-" + clientSpan.getTraceId() + "-" + clientSpan.getSpanId() + "-01",
        traceparent,
        "the receiver's parent is the CLIENT span, not the caller's own");

    assertEquals("GET", clientSpan.getAttributes().get(TracingHttpClient.HTTP_REQUEST_METHOD));
    assertEquals(
        "http://127.0.0.1:" + server.getAddress().getPort() + "/things",
        clientSpan.getAttributes().get(TracingHttpClient.URL_FULL),
        "no query string in the trace");
    assertEquals("127.0.0.1", clientSpan.getAttributes().get(TracingHttpClient.SERVER_ADDRESS));
    assertEquals(
        (long) server.getAddress().getPort(),
        clientSpan.getAttributes().get(TracingHttpClient.SERVER_PORT));
    assertEquals(200L, clientSpan.getAttributes().get(TracingHttpClient.HTTP_RESPONSE_STATUS_CODE));
    assertEquals(StatusCode.UNSET, clientSpan.getStatus().getStatusCode());
  }

  @Test
  void withNoCurrentSpanTheClientSpanIsTheRoot() throws Exception {
    client.send(request("/"), HttpResponse.BodyHandlers.discarding());

    SpanData clientSpan = onlyClientSpan();
    assertTrue(!clientSpan.getParentSpanContext().isValid());
    assertTrue(arrived().firstValue("traceparent").orElseThrow().contains(clientSpan.getTraceId()));
  }

  @Test
  void aCallerHeaderTheRequestAlreadyCarriesWins() throws Exception {
    client.send(
        HttpRequest.newBuilder(uri("/")).header("x-qits-caller", "proxied-for").build(),
        HttpResponse.BodyHandlers.discarding());

    assertEquals(List.of("proxied-for"), arrived().allValues(CallerHeader.NAME));
  }

  @Test
  void aProcessWithoutANameStampsNoCallerButStillTraces() throws Exception {
    HttpClient unnamed = QitsHttpClients.wrap(raw, otel, "  ");
    unnamed.send(request("/"), HttpResponse.BodyHandlers.discarding());

    HttpHeaders headers = arrived();
    assertTrue(headers.firstValue(CallerHeader.NAME).isEmpty());
    assertTrue(headers.firstValue("traceparent").isPresent());
  }

  @Test
  void sendAsyncStampsAndEndsTheSpanOnCompletion() throws Exception {
    HttpResponse<Void> response =
        client
            .sendAsync(request("/async"), HttpResponse.BodyHandlers.discarding())
            .get(10, TimeUnit.SECONDS);
    assertEquals(200, response.statusCode());

    HttpHeaders headers = arrived();
    assertEquals("qits-ci", headers.firstValue(CallerHeader.NAME).orElseThrow());
    SpanData clientSpan = onlyClientSpan();
    assertTrue(headers.firstValue("traceparent").orElseThrow().contains(clientSpan.getSpanId()));
    assertEquals(200L, clientSpan.getAttributes().get(TracingHttpClient.HTTP_RESPONSE_STATUS_CODE));
  }

  @Test
  void aServerErrorMarksTheSpan() throws Exception {
    client.send(request("/boom"), HttpResponse.BodyHandlers.discarding());

    SpanData clientSpan = onlyClientSpan();
    assertEquals(StatusCode.ERROR, clientSpan.getStatus().getStatusCode());
    assertEquals(503L, clientSpan.getAttributes().get(TracingHttpClient.HTTP_RESPONSE_STATUS_CODE));
  }

  @Test
  void aFailedExchangeRecordsTheExceptionAndRethrows() throws Exception {
    int closed = server.getAddress().getPort();
    server.stop(0);

    assertThrows(
        IOException.class,
        () ->
            client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + closed + "/")).build(),
                HttpResponse.BodyHandlers.discarding()));

    SpanData clientSpan = onlyClientSpan();
    assertEquals(StatusCode.ERROR, clientSpan.getStatus().getStatusCode());
    assertEquals(1, clientSpan.getEvents().size(), "the exception is recorded as an event");
    server = HttpServer.create(); // so stop() has something to stop
  }

  @Test
  void aNoopOpenTelemetryStillStampsTheCaller() throws Exception {
    HttpClient untraced = QitsHttpClients.wrap(raw, OpenTelemetry.noop(), "qits-ci");
    untraced.send(request("/"), HttpResponse.BodyHandlers.discarding());

    HttpHeaders headers = arrived();
    assertEquals("qits-ci", headers.firstValue(CallerHeader.NAME).orElseThrow());
    assertTrue(headers.firstValue("traceparent").isEmpty());
  }

  /**
   * The handshake is what is stamped; the loopback server answers it with a 200 rather than a 101,
   * so the socket itself never opens — the headers it arrived with are the whole assertion.
   */
  @Test
  void theWebSocketHandshakeCarriesTheCallerAndTheContext() throws Exception {
    Span parent = otel.getTracer("test").spanBuilder("parent").startSpan();
    try (Scope ignored = parent.makeCurrent()) {
      var connecting =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  URI.create("ws://127.0.0.1:" + server.getAddress().getPort() + "/socket"),
                  new java.net.http.WebSocket.Listener() {});
      assertThrows(Exception.class, () -> connecting.get(10, TimeUnit.SECONDS));
    } finally {
      parent.end();
    }

    HttpHeaders headers = arrived();
    assertEquals(List.of("qits-ci"), headers.allValues(CallerHeader.NAME));
    assertTrue(
        headers
            .firstValue("traceparent")
            .orElseThrow()
            .contains(parent.getSpanContext().getTraceId()));
  }

  @Test
  void aWebSocketCallerHeaderTheCallerSetWins() throws Exception {
    var connecting =
        client
            .newWebSocketBuilder()
            .header(CallerHeader.NAME, "proxied-for")
            .buildAsync(
                URI.create("ws://127.0.0.1:" + server.getAddress().getPort() + "/socket"),
                new java.net.http.WebSocket.Listener() {});
    assertThrows(Exception.class, () -> connecting.get(10, TimeUnit.SECONDS));

    assertEquals(List.of("proxied-for"), arrived().allValues(CallerHeader.NAME));
  }

  @Test
  void wrappingIsIdempotentAndTheRestIsTheDelegates() {
    assertSame(client, QitsHttpClients.wrap(client, otel, "other"));
    assertEquals(raw.version(), client.version());
    assertEquals(raw.followRedirects(), client.followRedirects());
    assertSame(raw.sslContext(), client.sslContext());
  }

  @Test
  void theUrlKeepsNoSecrets() {
    assertEquals(
        "https://example.org/a/b",
        TracingHttpClient.redacted(URI.create("https://user:pw@example.org/a/b?token=x#f")));
  }

  private HttpRequest request(String pathAndQuery) {
    return HttpRequest.newBuilder(uri(pathAndQuery)).build();
  }

  private URI uri(String pathAndQuery) {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + pathAndQuery);
  }

  private HttpHeaders arrived() throws InterruptedException {
    HttpHeaders headers = received.poll(10, TimeUnit.SECONDS);
    assertNotNull(headers, "the request never arrived");
    return headers;
  }

  /** The one CLIENT span, waited for: an async exchange may end it a moment after the future. */
  private SpanData onlyClientSpan() throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      List<SpanData> spans =
          exporter.getFinishedSpanItems().stream()
              .filter(s -> s.getKind() == SpanKind.CLIENT)
              .toList();
      if (!spans.isEmpty()) {
        assertEquals(1, spans.size());
        SpanData span = spans.getFirst();
        assertEquals(
            TracingHttpClient.INSTRUMENTATION_SCOPE, span.getInstrumentationScopeInfo().getName());
        return span;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("no CLIENT span ended");
  }
}
