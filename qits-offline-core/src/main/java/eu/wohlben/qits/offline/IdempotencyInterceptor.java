package eu.wohlben.qits.offline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Set;
import javax.sql.DataSource;

/**
 * Makes a replayed offline write safe: a {@code POST}, {@code PUT}, {@code PATCH} or {@code DELETE}
 * of an {@link OfflineCapable} operation that carries {@code Idempotency-Key} runs at most once
 * per (caller, key), and every repeat answers what the first run answered, plus {@code
 * Idempotency-Replayed: true}.
 *
 * <p><b>One transaction for the key and the write.</b> The interceptor joins the caller's JTA
 * transaction or starts one, and claims the key with {@code INSERT … ON CONFLICT DO NOTHING
 * RETURNING} into {@code qits_idempotency} before the method runs. Postgres makes a concurrent
 * duplicate wait on that insert until the first transaction ends, so two parallel requests with
 * one key cannot both run the method: the second sees the stored answer once the first commits, or
 * claims the key itself if the first rolled back.
 *
 * <ul>
 *   <li>claimed: the method runs; a 2xx answer is stored (status, the JSON body written by the CDI
 *       {@code ObjectMapper}, {@code ETag} and {@code Location}) and commits with the write.
 *   <li>already claimed with the same request fingerprint: the stored answer is replayed and the
 *       method does not run. With a different fingerprint: 422 {@link
 *       Problems#IDEMPOTENCY_KEY_REUSED}.
 *   <li>the method throws, or answers a non-2xx {@code Response}: the transaction rolls back and
 *       the key is free again. A 412 is therefore never replayed, and the client may retry the
 *       same key with the record's new version.
 * </ul>
 *
 * The fingerprint is SHA-256 over the method, the raw path and query, and the JSON of the entity
 * parameter (the one with no JAX-RS annotation). Without the header the write runs as it always
 * did; a header that is not 1-255 printable ASCII characters is 400 {@link
 * Problems#IDEMPOTENCY_KEY_INVALID}.
 *
 * <p>A replay is answered as a {@code Response} when the method declares one, and otherwise by
 * throwing {@link IdempotentReplay}, which {@link IdempotentReplayMapper} writes: a CDI interceptor
 * cannot return a {@code Response} from a method declared to return a DTO.
 *
 * <p>Ordered at {@code PLATFORM_BEFORE + 100}, ahead of {@code @Transactional} (+200), so a
 * {@code @Transactional} resource method joins the transaction this interceptor opened. Each claim
 * first purges at most {@value OfflineConfig#PURGE_BATCH} keys older than {@code
 * qits.offline.idempotency.retention} (default {@code P7D}).
 */
@OfflineCapable
@Interceptor
@Priority(Interceptor.Priority.PLATFORM_BEFORE + 100)
public class IdempotencyInterceptor {

  /** The request header naming the key. */
  public static final String KEY_HEADER = "Idempotency-Key";

  /** The response header a replayed answer carries, with the value {@code true}. */
  public static final String REPLAYED_HEADER = "Idempotency-Replayed";

  /** The caller of a request with no authenticated identity. */
  public static final String ANONYMOUS = "anonymous";

  private static final Set<String> WRITES =
      Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);

  private static final String PURGE =
      "delete from qits_idempotency where (caller, idem_key) in ("
          + "select caller, idem_key from qits_idempotency"
          + " where created_at < now() - ? * interval '1 second'"
          + " limit "
          + OfflineConfig.PURGE_BATCH
          + " for update skip locked)";

  private static final String CLAIM =
      "insert into qits_idempotency (caller, idem_key, fingerprint) values (?, ?, ?)"
          + " on conflict (caller, idem_key) do nothing returning 1";

  private static final String LOAD =
      "select fingerprint, status, body, etag, location from qits_idempotency"
          + " where caller = ? and idem_key = ?";

  private static final String STORE =
      "update qits_idempotency set status = ?, body = ?, etag = ?, location = ?"
          + " where caller = ? and idem_key = ?";

  @Inject Instance<DataSource> dataSource;
  @Inject Instance<HttpHeaders> headers;
  @Inject Instance<UriInfo> uriInfo;
  @Inject Instance<SecurityIdentity> identity;
  @Inject Instance<ObjectMapper> mappers;

  @AroundInvoke
  Object around(InvocationContext context) throws Exception {
    String method = httpMethod(context.getMethod());
    if (method == null || !WRITES.contains(method) || !headers.isResolvable()) {
      return context.proceed();
    }
    String key = headers.get().getHeaderString(KEY_HEADER);
    if (key == null) {
      return context.proceed();
    }
    if (!validKey(key)) {
      throw new IdempotencyKeyInvalidException(
          "Idempotency-Key must be 1 to 255 printable ASCII characters.");
    }
    ObjectMapper mapper = ProblemResponses.mapper(mappers);
    String caller = caller();
    String fingerprint = fingerprint(method, requestUri(), entity(context), mapper);

    Stored replay;
    try {
      replay =
          QuarkusTransaction.joiningExisting()
              .call(() -> claimAndRun(context, caller, key, fingerprint, mapper));
    } catch (Unwind unwind) {
      if (unwind.checked != null) {
        throw unwind.checked;
      }
      return unwind.result;
    }
    if (replay.ran) {
      return replay.result;
    }
    Response response = replay.toResponse();
    if (context.getMethod().getReturnType().isAssignableFrom(Response.class)) {
      return response;
    }
    throw new IdempotentReplay(response);
  }

  /**
   * Claims the key and runs the method, or loads what the key already answered. Returns the method's
   * own result wrapped, or the stored answer to replay.
   */
  private Stored claimAndRun(
      InvocationContext context, String caller, String key, String fingerprint, ObjectMapper mapper)
      throws SQLException {
    DataSource source = dataSource.get();
    boolean claimed = false;
    // Twice at most: a row that vanishes between a refused claim and its load was purged by
    // another writer, and the key is then free to claim.
    for (int attempt = 0; attempt < 2 && !claimed; attempt++) {
      try (Connection connection = source.getConnection()) {
        purge(connection);
        if (claim(connection, caller, key, fingerprint)) {
          claimed = true;
        } else {
          Stored stored = load(connection, caller, key);
          if (stored != null) {
            if (!stored.fingerprint.equals(fingerprint)) {
              throw new IdempotencyKeyReusedException(
                  "This Idempotency-Key was already used for a different request.");
            }
            return stored;
          }
        }
      }
    }
    if (!claimed) {
      throw new IllegalStateException("could not claim Idempotency-Key " + key);
    }

    Object result;
    try {
      result = context.proceed();
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Exception checked) {
      throw new Unwind(null, checked);
    }

    int status;
    Object entity;
    String etag = null;
    String location = null;
    if (result instanceof Response response) {
      status = response.getStatus();
      entity = response.getEntity();
      etag = response.getHeaderString(HttpHeaders.ETAG);
      location = response.getHeaderString(HttpHeaders.LOCATION);
      URI absolute = response.getLocation();
      if (location == null && absolute != null) {
        location = absolute.toString();
      }
    } else {
      entity = result;
      status = result == null ? 204 : 200;
    }
    if (status < 200 || status >= 300) {
      // Rolls the claim back with whatever the method wrote; the key is free for a retry.
      throw new Unwind(result, null);
    }
    String body = entity == null ? null : json(mapper, entity);
    try (Connection connection = source.getConnection();
        PreparedStatement store = connection.prepareStatement(STORE)) {
      store.setInt(1, status);
      store.setString(2, body);
      store.setString(3, etag);
      store.setString(4, location);
      store.setString(5, caller);
      store.setString(6, key);
      store.executeUpdate();
    }
    return Stored.ran(result);
  }

  private static void purge(Connection connection) throws SQLException {
    try (PreparedStatement purge = connection.prepareStatement(PURGE)) {
      purge.setLong(1, OfflineConfig.idempotencyRetention().toSeconds());
      purge.executeUpdate();
    }
  }

  private static boolean claim(Connection connection, String caller, String key, String fingerprint)
      throws SQLException {
    try (PreparedStatement claim = connection.prepareStatement(CLAIM)) {
      claim.setString(1, caller);
      claim.setString(2, key);
      claim.setString(3, fingerprint);
      try (ResultSet inserted = claim.executeQuery()) {
        return inserted.next();
      }
    }
  }

  private static Stored load(Connection connection, String caller, String key)
      throws SQLException {
    try (PreparedStatement load = connection.prepareStatement(LOAD)) {
      load.setString(1, caller);
      load.setString(2, key);
      try (ResultSet row = load.executeQuery()) {
        if (!row.next()) {
          return null;
        }
        return new Stored(
            row.getString("fingerprint"),
            row.getInt("status"),
            row.getString("body"),
            row.getString("etag"),
            row.getString("location"),
            false,
            null);
      }
    }
  }

  private String caller() {
    if (identity.isResolvable()) {
      SecurityIdentity current = identity.get();
      if (current != null && !current.isAnonymous() && current.getPrincipal() != null) {
        return current.getPrincipal().getName();
      }
    }
    return ANONYMOUS;
  }

  private String requestUri() {
    if (!uriInfo.isResolvable()) {
      return "";
    }
    URI uri = uriInfo.get().getRequestUri();
    return uri.getRawQuery() == null ? uri.getRawPath() : uri.getRawPath() + "?" + uri.getRawQuery();
  }

  /** The JAX-RS method designator on the resource method, or {@code null} for none. */
  static String httpMethod(Method method) {
    for (Annotation annotation : method.getAnnotations()) {
      HttpMethod designator = annotation.annotationType().getAnnotation(HttpMethod.class);
      if (designator != null) {
        return designator.value();
      }
    }
    return null;
  }

  /** Whether the key is 1-255 printable ASCII characters (0x20-0x7E). */
  static boolean validKey(String key) {
    if (key.isEmpty() || key.length() > 255) {
      return false;
    }
    for (int i = 0; i < key.length(); i++) {
      char c = key.charAt(i);
      if (c < 0x20 || c > 0x7E) {
        return false;
      }
    }
    return true;
  }

  /** The entity parameter: the one with no JAX-RS annotation that is not a context type. */
  static Object entity(InvocationContext context) {
    Method method = context.getMethod();
    Annotation[][] annotations = method.getParameterAnnotations();
    Class<?>[] types = method.getParameterTypes();
    Object[] values = context.getParameters();
    for (int i = 0; i < types.length; i++) {
      if (isEntity(annotations[i], types[i])) {
        return values[i];
      }
    }
    return null;
  }

  private static boolean isEntity(Annotation[] annotations, Class<?> type) {
    for (Annotation annotation : annotations) {
      String name = annotation.annotationType().getName();
      if (name.startsWith("jakarta.ws.rs.") || name.startsWith("org.jboss.resteasy.reactive.")) {
        return false;
      }
    }
    String typeName = type.getName();
    // Quarkus REST injects these without @Context; none of them is a body.
    return !typeName.startsWith("jakarta.ws.rs.") && !typeName.startsWith("io.vertx.");
  }

  static String fingerprint(String method, String uri, Object entity, ObjectMapper mapper) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(method.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) '\n');
      digest.update(uri.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) '\n');
      if (entity != null) {
        digest.update(json(mapper, entity).getBytes(StandardCharsets.UTF_8));
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is missing from this JVM", e);
    }
  }

  private static String json(ObjectMapper mapper, Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(
          "could not write " + value.getClass().getName() + " as JSON for Idempotency-Key", e);
    }
  }

  /** A stored answer to replay, or the method's own result after it ran. */
  private record Stored(
      String fingerprint,
      int status,
      String body,
      String etag,
      String location,
      boolean ran,
      Object result) {

    static Stored ran(Object result) {
      return new Stored(null, 0, null, null, null, true, result);
    }

    Response toResponse() {
      Response.ResponseBuilder response = Response.status(status).header(REPLAYED_HEADER, "true");
      if (body != null) {
        response.entity(body).type(MediaType.APPLICATION_JSON_TYPE);
      }
      if (etag != null) {
        response.header(HttpHeaders.ETAG, etag);
      }
      if (location != null) {
        response.header(HttpHeaders.LOCATION, location);
      }
      return response.build();
    }
  }

  /**
   * Leaves the transaction by rolling it back, carrying what the caller is to see: the method's own
   * non-2xx result, or the checked exception it threw.
   */
  private static final class Unwind extends RuntimeException {
    final transient Object result;
    final Exception checked;

    Unwind(Object result, Exception checked) {
      super(null, null, false, false);
      this.result = result;
      this.checked = checked;
    }
  }
}
