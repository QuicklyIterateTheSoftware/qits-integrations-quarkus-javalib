package eu.wohlben.qits.telemetry;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * The two platform services an export touches, on one local port: the idp's token endpoint
 * (client-credentials, a fresh {@code token-<n>} on every call, expiring in {@link #EXPIRES_IN}
 * seconds) and qits-observability's OTLP/HTTP ingest, which records every POST with the
 * Authorization header it carried and answers 200.
 */
public class StubPlatform implements QuarkusTestResourceLifecycleManager {

  /** Short, so a test can watch the oidc client refresh within seconds. */
  static final int EXPIRES_IN = 2;

  static final AtomicInteger MINTED = new AtomicInteger();
  static final List<Export> EXPORTS = new CopyOnWriteArrayList<>();

  private HttpServer server;

  /** One captured OTLP POST. {@code authorization} is null when the request carried none. */
  record Export(String path, String authorization, String contentType, int bytes) {}

  @Override
  public Map<String, String> start() {
    MINTED.set(0);
    EXPORTS.clear();
    try {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    server.createContext("/idp/token", StubPlatform::token);
    server.createContext("/observability/api/otel/v1/", StubPlatform::ingest);
    server.start();
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    return Map.ofEntries(
        Map.entry("qits.telemetry.endpoint", base + "/observability/api/otel"),
        Map.entry("quarkus.oidc-client.qits.auth-server-url", base + "/idp"),
        Map.entry("quarkus.oidc-client.qits.discovery-enabled", "false"),
        Map.entry("quarkus.oidc-client.qits.token-path", "token"),
        Map.entry("quarkus.oidc-client.qits.client-id", "test-telemetry"),
        Map.entry("quarkus.oidc-client.qits.credentials.secret", "secret"),
        Map.entry("quarkus.oidc-client.qits.grant.type", "client"),
        Map.entry("quarkus.oidc-client.qits.early-tokens-acquisition", "false"),
        Map.entry("quarkus.oidc-client.qits.client-enabled", "true"));
  }

  @Override
  public void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  private static void token(HttpExchange exchange) throws IOException {
    try (InputStream body = exchange.getRequestBody()) {
      body.readAllBytes();
    }
    String json =
        "{\"access_token\":\"token-"
            + MINTED.incrementAndGet()
            + "\",\"token_type\":\"Bearer\",\"expires_in\":"
            + EXPIRES_IN
            + "}";
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private static void ingest(HttpExchange exchange) throws IOException {
    int bytes;
    try (InputStream body = exchange.getRequestBody()) {
      bytes = body.readAllBytes().length;
    }
    EXPORTS.add(
        new Export(
            exchange.getRequestURI().getPath(),
            exchange.getRequestHeaders().getFirst("Authorization"),
            exchange.getRequestHeaders().getFirst("Content-Type"),
            bytes));
    exchange.getResponseHeaders().add("Content-Type", "application/x-protobuf");
    exchange.sendResponseHeaders(200, -1);
    exchange.close();
  }

  /** The first export matching {@code filter} within {@code within}, polling. */
  static Optional<Export> await(Predicate<Export> filter, Duration within) {
    long deadline = System.nanoTime() + within.toNanos();
    while (System.nanoTime() < deadline) {
      Optional<Export> hit = EXPORTS.stream().filter(filter).findFirst();
      if (hit.isPresent()) {
        return hit;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return Optional.empty();
      }
    }
    return Optional.empty();
  }

  static Predicate<Export> signal(String signal) {
    return export -> export.path().equals("/observability/api/otel/v1/" + signal);
  }
}
