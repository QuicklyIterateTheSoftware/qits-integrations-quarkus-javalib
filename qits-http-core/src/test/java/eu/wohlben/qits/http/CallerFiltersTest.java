package eu.wohlben.qits.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.smallrye.config.SmallRyeConfigBuilder;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.junit.jupiter.api.Test;

/**
 * Both filters and the name they stamp, without a server: the request contexts are proxies that
 * answer the one or two methods each filter calls, which is all a filter can see anyway. That the
 * SERVER span is current when Quarkus REST runs the filter is Quarkus' arrangement and is not
 * re-proved here.
 */
class CallerFiltersTest {

  private static final AttributeKey<String> CALLER = AttributeKey.stringKey(CallerHeader.ATTRIBUTE);

  @Test
  void theServiceNameWinsOverTheApplicationName() {
    assertEquals(
        "otel-name",
        CallerHeader.value(
                config(
                    Map.of(
                        CallerHeader.SERVICE_NAME, "otel-name",
                        CallerHeader.APPLICATION_NAME, "app-name")))
            .orElseThrow());
  }

  @Test
  void theApplicationNameIsTheFallback() {
    assertEquals(
        "app-name",
        CallerHeader.value(config(Map.of(CallerHeader.APPLICATION_NAME, " app-name ")))
            .orElseThrow());
    assertEquals(
        "app-name",
        CallerHeader.value(
                config(
                    Map.of(
                        CallerHeader.SERVICE_NAME, "  ",
                        CallerHeader.APPLICATION_NAME, "app-name")))
            .orElseThrow());
  }

  @Test
  void noNameIsEmptyNotInvented() {
    assertTrue(CallerHeader.value(config(Map.of())).isEmpty());
  }

  @Test
  void theClientFilterStampsTheNameUnlessTheCallerSetOne() {
    Config config = config(Map.of(CallerHeader.SERVICE_NAME, "qits-ci"));
    ConfigProviderResolver resolver = ConfigProviderResolver.instance();
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    resolver.registerConfig(config, loader);
    try {
      MultivaluedMap<String, Object> plain = new MultivaluedHashMap<>();
      new CallerClientFilter().filter(clientRequest(plain));
      assertEquals(List.of("qits-ci"), plain.get(CallerHeader.NAME));

      MultivaluedMap<String, Object> proxied = new MultivaluedHashMap<>();
      proxied.add(CallerHeader.NAME, "proxied-for");
      new CallerClientFilter().filter(clientRequest(proxied));
      assertEquals(List.of("proxied-for"), proxied.get(CallerHeader.NAME));
    } finally {
      resolver.releaseConfig(config);
    }
  }

  @Test
  void theServerFilterRecordsTheCallerOnTheCurrentSpan() {
    assertEquals("qits-ci", recorded("  qits-ci "));
  }

  @Test
  void anAbsentOrBlankCallerRecordsNothing() {
    assertNull(recorded(null));
    assertNull(recorded("   "));
  }

  @Test
  void anOverlongCallerIsCapped() {
    assertEquals(CallerServerFilter.MAX_LENGTH, recorded("x".repeat(1000)).length());
  }

  /** The attribute the server filter left on a span made current around it, or null. */
  private static String recorded(String header) {
    InMemorySpanExporter exporter = InMemorySpanExporter.create();
    try (SdkTracerProvider provider =
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
      Span server = provider.get("test").spanBuilder("GET /").startSpan();
      try (Scope ignored = server.makeCurrent()) {
        new CallerServerFilter().filter(containerRequest(header));
      } finally {
        server.end();
      }
      SpanData ended = exporter.getFinishedSpanItems().getFirst();
      return ended.getAttributes().get(CALLER);
    }
  }

  private static Config config(Map<String, String> values) {
    return new SmallRyeConfigBuilder().withDefaultValues(values).build();
  }

  private static ClientRequestContext clientRequest(MultivaluedMap<String, Object> headers) {
    return (ClientRequestContext)
        Proxy.newProxyInstance(
            CallerFiltersTest.class.getClassLoader(),
            new Class<?>[] {ClientRequestContext.class},
            (proxy, method, args) -> {
              if (method.getName().equals("getHeaders")) {
                return headers;
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }

  private static ContainerRequestContext containerRequest(String caller) {
    return (ContainerRequestContext)
        Proxy.newProxyInstance(
            CallerFiltersTest.class.getClassLoader(),
            new Class<?>[] {ContainerRequestContext.class},
            (proxy, method, args) -> {
              if (method.getName().equals("getHeaderString")
                  && CallerHeader.NAME.equals(args[0])) {
                return caller;
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }
}
