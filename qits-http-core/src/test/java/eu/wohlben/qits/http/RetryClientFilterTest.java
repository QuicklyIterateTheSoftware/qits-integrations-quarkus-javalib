package eu.wohlben.qits.http;

import static eu.wohlben.qits.http.ScriptedServer.respond;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Context;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jboss.resteasy.reactive.common.core.BlockingOperationSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The REST-client half on the wire: the JAX-RS client the Quarkus REST client is built on,
 * standalone, with {@link RetryClientFilter} registered as Quarkus registers every
 * {@code @Provider}, against the scripted loopback server.
 */
class RetryClientFilterTest {

  private final FakeTiming timing = new FakeTiming();
  private final List<String> warnings = Collections.synchronizedList(new ArrayList<>());
  private ScriptedServer server;
  private Client client;

  /**
   * What Quarkus installs at boot: blocking is disallowed on a Vert.x event loop, so response
   * filters are handed to a worker. Standalone the client allows blocking everywhere and would run
   * them on the event loop, which is not the arrangement this filter lives in.
   */
  @BeforeAll
  static void likeQuarkus() {
    BlockingOperationSupport.setIoThreadDetector(() -> !Context.isOnEventLoopThread());
  }

  @BeforeEach
  void start() throws IOException {
    server = new ScriptedServer();
    client =
        ClientBuilder.newBuilder()
            .register(
                new RetryClientFilter(
                    RetryPolicy.standard()
                        .withLoopbackRetries()
                        .withTiming(timing)
                        .withWarnings(warnings::add)))
            .build();
  }

  @AfterEach
  void stop() throws IOException {
    client.close();
    server.close();
  }

  @Test
  void aSwitchoverAnswerIsRetriedAndTheCallerSeesOnlyTheFinalOne() {
    server.then(respond(503, "Retry-After", "1"), respond(502, "Retry-After", "1"));

    try (Response response = client.target(server.url("/things")).request().get()) {
      assertEquals(200, response.getStatus());
      assertEquals("status 200", response.readEntity(String.class));
      assertEquals(MediaType.TEXT_PLAIN_TYPE, response.getMediaType());
    }
    assertEquals(3, server.connections());
    assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(1)), timing.pauses());
    assertEquals(1, warnings.size(), warnings.toString());
    assertTrue(warnings.get(0).contains("GET 127.0.0.1:" + server.port()), warnings.get(0));
    assertTrue(warnings.get(0).contains("got through after 3 attempts"), warnings.get(0));
  }

  @Test
  void a504WithRetryAfterIsRetriedForAPutAndTheBodyIsSentAgain() {
    server.then(respond(504, "Retry-After", "1"));

    try (Response response =
        client
            .target(server.url("/things/1"))
            .request()
            .put(Entity.entity("{\"a\":1}", MediaType.APPLICATION_JSON_TYPE))) {
      assertEquals(200, response.getStatus());
    }
    assertEquals(List.of("PUT", "PUT"), server.methods());
  }

  @Test
  void a503WithoutRetryAfterAndA500AreAnswers() {
    server.then(respond(503), respond(500, "Retry-After", "1"));

    try (Response first = client.target(server.url("/")).request().get()) {
      assertEquals(503, first.getStatus());
    }
    try (Response second = client.target(server.url("/")).request().get()) {
      assertEquals(500, second.getStatus());
    }
    assertEquals(2, server.connections());
    assertTrue(warnings.isEmpty());
  }

  @Test
  void aPostOrPatchWithAResponseIsNeverResent() {
    server.otherwise(respond(503, "Retry-After", "1"));

    for (String method : List.of("POST", "PATCH")) {
      try (Response response =
          client
              .target(server.url("/"))
              .request()
              .method(method, Entity.entity("{}", MediaType.APPLICATION_JSON_TYPE))) {
        assertEquals(503, response.getStatus(), method);
      }
    }
    assertEquals(2, server.connections());
  }

  @Test
  void theBudgetGivesUpWithTheLastResponse() {
    server.otherwise(respond(503, "Retry-After", "1"));

    try (Response response = client.target(server.url("/")).request().get()) {
      assertEquals(503, response.getStatus());
      assertEquals("status 503", response.readEntity(String.class));
    }
    assertTrue(
        timing.total().compareTo(Duration.ofSeconds(30)) <= 0, () -> timing.total().toString());
    assertTrue(server.connections() >= 7, () -> "attempts: " + server.connections());
    assertEquals(1, warnings.size(), warnings.toString());
    assertTrue(warnings.get(0).contains("gave up"), warnings.get(0));
  }

  @Test
  void aStreamBodyIsNotResent() {
    server.otherwise(respond(503, "Retry-After", "1"));

    try (Response response =
        client
            .target(server.url("/"))
            .request()
            .put(
                Entity.entity(
                    new ByteArrayInputStream("{}".getBytes()),
                    MediaType.APPLICATION_OCTET_STREAM_TYPE))) {
      assertEquals(503, response.getStatus());
    }
    assertEquals(1, server.connections());
  }

  @Test
  void anEventStreamIsLeftToItsSubscriber() {
    server.otherwise(respond(503, "Retry-After", "1"));

    try (Response response =
        client.target(server.url("/events")).request(MediaType.SERVER_SENT_EVENTS).get()) {
      assertEquals(503, response.getStatus());
    }
    assertEquals(1, server.connections());
  }

  /**
   * The documented gap: a transport failure of a REST client is not retried, because the Quarkus
   * REST client gives a filter no way to recover one. This pins that honestly rather than letting
   * the README claim otherwise.
   */
  @Test
  void aRefusedRestClientConnectionFailsAtOnce() throws IOException {
    int port;
    try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      port = s.getLocalPort();
    }

    ProcessingException thrown =
        assertThrows(
            ProcessingException.class,
            () -> client.target("http://127.0.0.1:" + port + "/").request().get().close());
    // The filter runs in the failure's chain too, and must leave the refusal as the cause.
    boolean refused = false;
    for (Throwable t = thrown; t != null; t = t.getCause()) {
      assertFalse(t instanceof NullPointerException, thrown::toString);
      refused |= t instanceof java.net.ConnectException;
    }
    assertTrue(refused, thrown::toString);
    assertTrue(timing.pauses().isEmpty());
    assertFalse(warnings.stream().anyMatch(w -> w.contains("attempts")));
  }

  @Test
  void byDefaultALoopbackTargetIsNeverResent() {
    server.otherwise(respond(503, "Retry-After", "1"));
    Client standard =
        ClientBuilder.newBuilder()
            .register(
                new RetryClientFilter(
                    RetryPolicy.standard().withTiming(timing).withWarnings(warnings::add)))
            .build();
    try {
      try (Response response = standard.target(server.url("/")).request().get()) {
        assertEquals(503, response.getStatus());
      }
      try (Response response =
          standard.target("http://localhost:" + server.port() + "/").request().get()) {
        assertEquals(503, response.getStatus());
      }
    } finally {
      standard.close();
    }
    assertEquals(2, server.connections());
    assertTrue(timing.pauses().isEmpty());
    assertTrue(warnings.isEmpty());
  }

  @Test
  void entitiesThatCannotBeWrittenTwiceAreRecognised() {
    assertTrue(RetryClientFilter.replayable(null));
    assertTrue(RetryClientFilter.replayable("{}"));
    assertTrue(RetryClientFilter.replayable(new byte[] {1}));
    assertTrue(RetryClientFilter.replayable(List.of("a")));
    assertFalse(RetryClientFilter.replayable(new ByteArrayInputStream(new byte[0])));
    assertFalse(RetryClientFilter.replayable(new java.io.StringReader("")));
    assertFalse(RetryClientFilter.replayable((jakarta.ws.rs.core.StreamingOutput) out -> {}));
  }
}
