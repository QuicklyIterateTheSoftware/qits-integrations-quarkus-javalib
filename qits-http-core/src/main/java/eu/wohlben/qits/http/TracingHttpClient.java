package eu.wohlben.qits.http;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * An {@link HttpClient} that sends every request as a traced, named service-to-service call: a
 * CLIENT span per exchange, the W3C trace context of that span in the request headers, and {@link
 * CallerHeader#NAME} unless the request already says who is calling. Everything else — pool,
 * executor, TLS, redirects, lifecycle — is the wrapped client's, delegated method for method.
 * Obtained through {@link QitsHttpClients#wrap}.
 *
 * <p>The span follows the OpenTelemetry HTTP client conventions as far as {@code java.net.http}
 * can see: named for the method, carrying {@code http.request.method}, {@code url.full} (no query,
 * no userinfo — query strings carry tokens on this platform often enough to keep them out of
 * traces), {@code server.address}, {@code server.port} and {@code http.response.status_code}. It is
 * an error on an exception or a 5xx; a 4xx is an answer the caller asked for and usually handles.
 * The span is current while the request is handed over, so anything the delegate instruments
 * nests under it, and it ends when the exchange does — on return for {@link #send}, on completion
 * for {@link #sendAsync}.
 *
 * <p>Every exchange rides out an edge switchover under {@link RetryPolicy}: each attempt is its own
 * CLIENT span, a resend carrying {@code http.request.resend_count}, so a retried call reads in the
 * trace as what it was.
 */
public final class TracingHttpClient extends HttpClient {

  static final String INSTRUMENTATION_SCOPE = "eu.wohlben.qits.http";

  static final AttributeKey<String> HTTP_REQUEST_METHOD =
      AttributeKey.stringKey("http.request.method");
  static final AttributeKey<Long> HTTP_RESPONSE_STATUS_CODE =
      AttributeKey.longKey("http.response.status_code");
  static final AttributeKey<String> URL_FULL = AttributeKey.stringKey("url.full");
  static final AttributeKey<String> SERVER_ADDRESS = AttributeKey.stringKey("server.address");
  static final AttributeKey<Long> SERVER_PORT = AttributeKey.longKey("server.port");
  static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");
  static final AttributeKey<Long> HTTP_REQUEST_RESEND_COUNT =
      AttributeKey.longKey("http.request.resend_count");

  private static final Set<String> REPLAYABLE_PUBLISHERS =
      Set.of(
          "jdk.internal.net.http.RequestPublishers$EmptyPublisher",
          "jdk.internal.net.http.RequestPublishers$ByteArrayPublisher",
          "jdk.internal.net.http.RequestPublishers$StringPublisher",
          "jdk.internal.net.http.RequestPublishers$FilePublisher");

  /** java.net.http's view of a response, for the retry loop. */
  static final RetryPolicy.Responses<HttpResponse<?>> RESPONSES =
      new RetryPolicy.Responses<>() {
        @Override
        public int status(HttpResponse<?> response) {
          return response.statusCode();
        }

        @Override
        public Optional<String> header(HttpResponse<?> response, String name) {
          return response.headers().firstValue(name);
        }

        /**
         * A streamed body is closed or cancelled so its connection goes back to the pool; a
         * buffered one was read already and is simply dropped.
         */
        @Override
        public void discard(HttpResponse<?> response) {
          Object body = response.body();
          try {
            if (body instanceof AutoCloseable closeable) {
              closeable.close();
            } else if (body instanceof Flow.Publisher<?> publisher) {
              publisher.subscribe(new CancellingSubscriber());
            }
          } catch (Exception ignored) {
            // A body we are walking away from.
          }
        }
      };

  private static final TextMapSetter<HttpRequest.Builder> SETTER =
      (builder, key, value) -> {
        if (builder != null) {
          builder.setHeader(key, value);
        }
      };

  private final HttpClient delegate;
  private final OpenTelemetry openTelemetry;
  private final Tracer tracer;
  private final String caller;
  private final RetryPolicy retry;

  TracingHttpClient(
      HttpClient delegate, OpenTelemetry openTelemetry, String caller, RetryPolicy retry) {
    this.delegate = delegate;
    this.retry = retry;
    this.openTelemetry = openTelemetry;
    this.tracer = openTelemetry.getTracer(INSTRUMENTATION_SCOPE);
    this.caller = caller == null || caller.isBlank() ? null : caller.trim();
  }

  @Override
  public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
      throws IOException, InterruptedException {
    return retry.<HttpResponse<T>, IOException>execute(
        request.method(),
        request.uri(),
        replayable(request),
        attempt -> sendOnce(request, handler, attempt),
        RESPONSES);
  }

  private <T> HttpResponse<T> sendOnce(
      HttpRequest request, HttpResponse.BodyHandler<T> handler, int attempt)
      throws IOException, InterruptedException {
    Span span = start(request, attempt);
    HttpResponse<T> response;
    try (Scope ignored = span.makeCurrent()) {
      response = delegate.send(stamp(request, Context.current()), handler);
    } catch (IOException | InterruptedException | RuntimeException | Error e) {
      fail(span, e);
      throw e;
    }
    finish(span, response);
    return response;
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request, HttpResponse.BodyHandler<T> handler) {
    return sendAsync(request, handler, null);
  }

  /**
   * Retried as {@link #send} is, without blocking: each attempt is a fresh exchange under the
   * caller's trace context, the pauses are timer-driven, and cancelling the returned future cancels
   * the exchange in flight and the retries after it.
   */
  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request,
      HttpResponse.BodyHandler<T> handler,
      HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
    Context parent = Context.current();
    return retry.executeAsync(
        request.method(),
        request.uri(),
        replayable(request),
        attempt -> {
          try (Scope ignored = parent.makeCurrent()) {
            return sendAsyncOnce(request, handler, pushPromiseHandler, attempt);
          }
        },
        RESPONSES);
  }

  private <T> CompletableFuture<HttpResponse<T>> sendAsyncOnce(
      HttpRequest request,
      HttpResponse.BodyHandler<T> handler,
      HttpResponse.PushPromiseHandler<T> pushPromiseHandler,
      int attempt) {
    Span span = start(request, attempt);
    CompletableFuture<HttpResponse<T>> future;
    try (Scope ignored = span.makeCurrent()) {
      HttpRequest stamped = stamp(request, Context.current());
      future =
          pushPromiseHandler == null
              ? delegate.sendAsync(stamped, handler)
              : delegate.sendAsync(stamped, handler, pushPromiseHandler);
    } catch (RuntimeException | Error e) {
      fail(span, e);
      throw e;
    }
    // A side branch, not the returned stage: the retry loop keeps the delegate's own future, so
    // cancelling reaches the exchange exactly as it would unwrapped.
    future.whenComplete(
        (response, failure) -> {
          if (failure != null) {
            fail(span, failure instanceof CompletionException c && c.getCause() != null
                ? c.getCause()
                : failure);
          } else {
            finish(span, response);
          }
        });
    return future;
  }

  /**
   * Whether {@code request} may be sent a second time. Not when it opens an event stream (SSE
   * reconnection is its subscriber's business, with its own Last-Event-ID), and not when its body
   * cannot be re-read: only the JDK's own empty, byte-array, string and file publishers are known
   * to replay, so an input-stream, iterable or {@code fromPublisher} body — or any publisher of
   * the caller's own — makes the first attempt the only one.
   */
  static boolean replayable(HttpRequest request) {
    boolean eventStream =
        request.headers().allValues("Accept").stream()
            .anyMatch(v -> v.toLowerCase(Locale.ROOT).contains("text/event-stream"));
    if (eventStream) {
      return false;
    }
    return request
        .bodyPublisher()
        .map(publisher -> REPLAYABLE_PUBLISHERS.contains(publisher.getClass().getName()))
        .orElse(true);
  }

  /**
   * The delegate's builder, made to send the caller header and the current trace context when it
   * connects — the WebSocket handshake is one HTTP request, and it should be as legible as any.
   * No span: a socket lives far longer than a request, and a span that long says nothing useful.
   */
  @Override
  public WebSocket.Builder newWebSocketBuilder() {
    return new StampingWebSocketBuilder(delegate.newWebSocketBuilder());
  }

  private Span start(HttpRequest request, int attempt) {
    String method = request.method();
    URI uri = request.uri();
    var builder =
        tracer
            .spanBuilder(method == null || method.isBlank() ? "HTTP" : method)
            .setSpanKind(SpanKind.CLIENT)
            .setParent(Context.current())
            .setAttribute(HTTP_REQUEST_METHOD, method)
            .setAttribute(URL_FULL, redacted(uri));
    if (uri.getHost() != null) {
      builder.setAttribute(SERVER_ADDRESS, uri.getHost());
    }
    long port = port(uri);
    if (port > 0) {
      builder.setAttribute(SERVER_PORT, port);
    }
    if (attempt > 1) {
      builder.setAttribute(HTTP_REQUEST_RESEND_COUNT, (long) attempt - 1);
    }
    return builder.startSpan();
  }

  /**
   * The request as it leaves: a copy carrying the trace context of {@code context} and the caller
   * header. A copy because {@link HttpRequest} is immutable; the filter admits every header,
   * since the original passed the JDK's own checks when it was built.
   */
  private HttpRequest stamp(HttpRequest request, Context context) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(request, (name, value) -> true);
    propagator().inject(context, builder, SETTER);
    if (caller != null && request.headers().firstValue(CallerHeader.NAME).isEmpty()) {
      builder.setHeader(CallerHeader.NAME, caller);
    }
    return builder.build();
  }

  private TextMapPropagator propagator() {
    return openTelemetry.getPropagators().getTextMapPropagator();
  }

  private static void finish(Span span, HttpResponse<?> response) {
    int status = response.statusCode();
    span.setAttribute(HTTP_RESPONSE_STATUS_CODE, (long) status);
    if (status >= 500) {
      span.setAttribute(ERROR_TYPE, Integer.toString(status));
      span.setStatus(StatusCode.ERROR);
    }
    span.end();
  }

  private static void fail(Span span, Throwable failure) {
    span.recordException(failure);
    span.setAttribute(ERROR_TYPE, failure.getClass().getName());
    span.setStatus(StatusCode.ERROR);
    span.end();
  }

  /** Scheme, host, port and path: the URL minus the parts that can hold a secret. */
  static String redacted(URI uri) {
    StringBuilder url = new StringBuilder();
    if (uri.getScheme() != null) {
      url.append(uri.getScheme()).append("://");
    }
    if (uri.getHost() != null) {
      url.append(uri.getHost());
    }
    if (uri.getPort() >= 0) {
      url.append(':').append(uri.getPort());
    }
    if (uri.getRawPath() != null) {
      url.append(uri.getRawPath());
    }
    return url.toString();
  }

  private static long port(URI uri) {
    if (uri.getPort() >= 0) {
      return uri.getPort();
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    return switch (scheme) {
      case "http", "ws" -> 80;
      case "https", "wss" -> 443;
      default -> -1;
    };
  }

  /**
   * The WebSocket builder with the stamping deferred to {@link #buildAsync}, because only then is
   * it known whether the caller set the header itself — the builder has no getter to ask.
   */
  private final class StampingWebSocketBuilder implements WebSocket.Builder {

    private final WebSocket.Builder builder;
    private final Set<String> named = new HashSet<>();

    StampingWebSocketBuilder(WebSocket.Builder builder) {
      this.builder = builder;
    }

    @Override
    public WebSocket.Builder header(String name, String value) {
      builder.header(name, value);
      named.add(name.toLowerCase(Locale.ROOT));
      return this;
    }

    @Override
    public WebSocket.Builder connectTimeout(Duration timeout) {
      builder.connectTimeout(timeout);
      return this;
    }

    @Override
    public WebSocket.Builder subprotocols(String mostPreferred, String... lesserPreferred) {
      builder.subprotocols(mostPreferred, lesserPreferred);
      return this;
    }

    @Override
    public CompletableFuture<WebSocket> buildAsync(URI uri, WebSocket.Listener listener) {
      // Through header(...), so a second buildAsync on the same builder adds nothing twice.
      propagator()
          .inject(
              Context.current(),
              this,
              (self, key, value) -> {
                if (self != null && !named.contains(key.toLowerCase(Locale.ROOT))) {
                  self.header(key, value);
                }
              });
      if (caller != null && !named.contains(CallerHeader.NAME.toLowerCase(Locale.ROOT))) {
        header(CallerHeader.NAME, caller);
      }
      return builder.buildAsync(uri, listener);
    }
  }

  private static final class CancellingSubscriber implements Flow.Subscriber<Object> {
    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      subscription.cancel();
    }

    @Override
    public void onNext(Object item) {}

    @Override
    public void onError(Throwable throwable) {}

    @Override
    public void onComplete() {}
  }

  // Everything below is the wrapped client's, unchanged.

  @Override
  public Optional<CookieHandler> cookieHandler() {
    return delegate.cookieHandler();
  }

  @Override
  public Optional<Duration> connectTimeout() {
    return delegate.connectTimeout();
  }

  @Override
  public Redirect followRedirects() {
    return delegate.followRedirects();
  }

  @Override
  public Optional<ProxySelector> proxy() {
    return delegate.proxy();
  }

  @Override
  public SSLContext sslContext() {
    return delegate.sslContext();
  }

  @Override
  public SSLParameters sslParameters() {
    return delegate.sslParameters();
  }

  @Override
  public Optional<Authenticator> authenticator() {
    return delegate.authenticator();
  }

  @Override
  public Version version() {
    return delegate.version();
  }

  @Override
  public Optional<Executor> executor() {
    return delegate.executor();
  }

  @Override
  public void shutdown() {
    delegate.shutdown();
  }

  @Override
  public boolean awaitTermination(Duration duration) throws InterruptedException {
    return delegate.awaitTermination(duration);
  }

  @Override
  public boolean isTerminated() {
    return delegate.isTerminated();
  }

  @Override
  public void shutdownNow() {
    delegate.shutdownNow();
  }

  @Override
  public void close() {
    delegate.close();
  }

  @Override
  public String toString() {
    return "TracingHttpClient[" + delegate + "]";
  }
}
