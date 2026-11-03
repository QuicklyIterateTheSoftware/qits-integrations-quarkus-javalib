package eu.wohlben.qits.http;

import jakarta.annotation.Priority;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.client.ClientResponseFilter;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import jakarta.ws.rs.ext.Provider;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Flow;

/**
 * {@link RetryPolicy} for the REST client: a {@code 502}, {@code 503} or {@code 504} carrying
 * {@code Retry-After} on a {@code GET}, {@code HEAD}, {@code PUT} or {@code DELETE} is sent again
 * until it answers otherwise or the budget is spent, and the caller sees only the final answer.
 *
 * <p>{@code @Provider} is the whole registration, as for {@link CallerClientFilter}: Quarkus applies
 * an annotated client filter to every REST client in the application, declarative and
 * programmatic, and a consumer without the REST client never instantiates it.
 *
 * <p><b>Why a response filter that resends, and why only the status half.</b> The Quarkus REST
 * client has no public retry hook: its handler chain is fixed, and a filter can neither repeat the
 * exchange it belongs to nor recover one that failed — a transport failure runs the response
 * filters with the throwable already recorded in the client's internal request context, and the
 * call fails whatever a filter does. MicroProfile Fault Tolerance's {@code @Retry} is per interface,
 * and anything that rewrites every interface is a Quarkus extension, which this module is not. So
 * the switchover ANSWER — the edge's {@code 503 Retry-After: 1}, the only class a filter can see —
 * is retried here, by sending the same request again through the same configured {@link
 * jakarta.ws.rs.client.Client} (its TLS, timeouts and providers), and the final status, headers
 * and body replace the first ones in the response context. A connection-class failure of a REST
 * client is <b>not</b> retried; a call site that needs that wraps the call in {@link
 * RetryPolicy#execute} itself, or uses a {@link QitsHttpClients}-wrapped client, which retries both.
 *
 * <p><b>What is never resent.</b> Anything {@link RetryPolicy} would not retry — a loopback target
 * included, since a call to the process's own host never crosses the edge; a request opening
 * an event stream; a body that cannot be written twice (a stream, a reader, a publisher, a
 * multipart form); and any call whose response filters run on an I/O thread, where waiting would
 * block the event loop — in Quarkus they run on a worker unless blocking is disallowed there.
 *
 * <p>The resend carries every header of the first request and passes through the request filters
 * again; the client filters on the estate only add a header that is absent, so nothing doubles.
 * The resend is marked with {@link #RESENT} so this filter does not retry inside a retry.
 */
@Provider
@Priority(RetryClientFilter.PRIORITY)
public class RetryClientFilter implements ClientResponseFilter {

  /**
   * Response filters run in DESCENDING priority, so this one sees the response before any
   * application filter ({@code Priorities.USER} is 5000) and they all see only the final answer.
   */
  static final int PRIORITY = 10_000;

  /** The request property a resend carries. */
  static final String RESENT = "eu.wohlben.qits.http.RetryClientFilter.resent";

  /** Headers that describe one wire transfer, not the request, and are recomputed on a resend. */
  private static final Set<String> HOP_HEADERS =
      Set.of("content-length", "host", "transfer-encoding", "connection", "expect");

  private static final Set<String> UNREPLAYABLE_ENTITY_NAMES =
      Set.of(
          "io.smallrye.mutiny.Multi",
          "org.reactivestreams.Publisher",
          "io.vertx.core.streams.ReadStream",
          "io.vertx.mutiny.core.streams.ReadStream");

  private volatile RetryPolicy policy;

  public RetryClientFilter() {}

  RetryClientFilter(RetryPolicy policy) {
    this.policy = policy;
  }

  private RetryPolicy policy() {
    RetryPolicy p = policy;
    if (p == null) {
      p = RetryPolicy.fromConfig();
      policy = p;
    }
    return p;
  }

  @Override
  public void filter(ClientRequestContext request, ClientResponseContext response) {
    if (request.getProperty(RESENT) != null) {
      return;
    }
    String retryAfter;
    int status;
    try {
      // On a transport failure Quarkus still runs the response filters, over a response context
      // with no headers behind it; that call is the failure's, and must stay untouched.
      status = response.getStatus();
      retryAfter = response.getHeaderString(RetryPolicy.RETRY_AFTER);
    } catch (RuntimeException noResponse) {
      return;
    }
    if (!RetryPolicy.switchoverStatus(status, retryAfter != null)
        || !RetryPolicy.idempotent(request.getMethod())
        || eventStream(request)
        || !replayable(request.getEntity())
        || onEventLoop()) {
      return;
    }
    RetryPolicy retry = policy();
    if (!retry.retries(request.getUri())) {
      return;
    }

    Answer first = Answer.of(response);
    Answer last;
    try {
      last =
          retry.<Answer, RuntimeException>resume(
              request.getMethod(),
              request.getUri(),
              first,
              attempt -> resend(request, attempt),
              ANSWERS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new ProcessingException("interrupted while retrying " + request.getUri(), interrupted);
    }
    if (last != first) {
      last.into(response);
    }
  }

  private static Answer resend(ClientRequestContext request, int attempt) {
    Invocation.Builder builder =
        request.getClient().target(request.getUri()).request().property(RESENT, attempt);
    MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
    request
        .getStringHeaders()
        .forEach(
            (name, values) -> {
              if (!HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                headers.put(name, new ArrayList<>(values));
              }
            });
    builder.headers(headers);
    Object entity = request.getEntity();
    Response answer;
    if (entity == null) {
      answer = builder.method(request.getMethod());
    } else {
      MediaType type =
          request.getMediaType() == null
              ? MediaType.APPLICATION_OCTET_STREAM_TYPE
              : request.getMediaType();
      answer =
          builder.method(
              request.getMethod(),
              Entity.entity(generic(entity, request.getEntityType()), type,
                  request.getEntityAnnotations()));
    }
    return Answer.of(answer);
  }

  private static Object generic(Object entity, Type type) {
    if (entity instanceof GenericEntity<?> || type == null || type == entity.getClass()) {
      return entity;
    }
    return new GenericEntity<>(entity, type);
  }

  private static boolean eventStream(ClientRequestContext request) {
    List<String> accept = request.getStringHeaders().get("Accept");
    if (accept != null
        && accept.stream()
            .anyMatch(v -> v.toLowerCase(Locale.ROOT).contains("text/event-stream"))) {
      return true;
    }
    return request.getAcceptableMediaTypes().stream()
        .anyMatch(t -> "text".equals(t.getType()) && "event-stream".equals(t.getSubtype()));
  }

  /** Whether {@code entity} can be written a second time. */
  static boolean replayable(Object entity) {
    if (entity == null) {
      return true;
    }
    Object value = entity instanceof GenericEntity<?> g ? g.getEntity() : entity;
    if (value instanceof InputStream
        || value instanceof Reader
        || value instanceof StreamingOutput
        || value instanceof Flow.Publisher<?>) {
      return false;
    }
    for (Class<?> c = value.getClass(); c != null; c = c.getSuperclass()) {
      if (c.getName().contains("Multipart")) {
        return false;
      }
      for (Class<?> i : c.getInterfaces()) {
        if (UNREPLAYABLE_ENTITY_NAMES.contains(i.getName())) {
          return false;
        }
      }
    }
    return true;
  }

  /**
   * True on a Vert.x event-loop thread, where the resend would block; false wherever Vert.x is
   * absent from the classpath (it never is in a Quarkus REST client).
   */
  private static boolean onEventLoop() {
    try {
      return io.vertx.core.Context.isOnEventLoopThread();
    } catch (LinkageError noVertx) {
      return false;
    }
  }

  /** One answer, either the response context's own or a resend's. */
  private record Answer(
      int status, MultivaluedMap<String, String> headers, Response response) {

    static Answer of(ClientResponseContext context) {
      return new Answer(context.getStatus(), context.getHeaders(), null);
    }

    static Answer of(Response response) {
      return new Answer(response.getStatus(), response.getStringHeaders(), response);
    }

    /** Puts a resend's answer where the caller reads the first one. */
    void into(ClientResponseContext context) {
      context.setStatus(status);
      MultivaluedMap<String, String> target = context.getHeaders();
      MultivaluedMap<String, String> copy = new MultivaluedHashMap<>();
      headers.forEach((name, values) -> copy.put(name, new ArrayList<>(values)));
      target.clear();
      target.putAll(copy);
      InputStream old = context.getEntityStream();
      if (old != null) {
        try {
          old.close();
        } catch (IOException ignored) {
          // the first answer's body, which nobody reads
        }
      }
      context.setEntityStream(
          response.hasEntity()
              ? response.readEntity(InputStream.class)
              : new ByteArrayInputStream(new byte[0]));
    }
  }

  private static final RetryPolicy.Responses<Answer> ANSWERS =
      new RetryPolicy.Responses<>() {
        @Override
        public int status(Answer answer) {
          return answer.status();
        }

        @Override
        public Optional<String> header(Answer answer, String name) {
          for (var entry : answer.headers().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)
                && entry.getValue() != null
                && !entry.getValue().isEmpty()) {
              return Optional.ofNullable(entry.getValue().get(0));
            }
          }
          return Optional.empty();
        }

        /** A resend's response is closed; the first answer stays the response context's own. */
        @Override
        public void discard(Answer answer) {
          if (answer.response() != null) {
            answer.response().close();
          }
        }
      };
}
