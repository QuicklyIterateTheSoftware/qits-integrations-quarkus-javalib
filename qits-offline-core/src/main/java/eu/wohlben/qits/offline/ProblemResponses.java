package eu.wohlben.qits.offline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes a problem body. The body is serialized here, to a string, rather than handed to the
 * consumer's JSON provider: a provider registered for {@code application/json} need not take
 * {@code application/problem+json}, and a string entity is written as-is under any media type.
 */
final class ProblemResponses {

  private static final ObjectMapper FALLBACK = new ObjectMapper();

  private ProblemResponses() {}

  /** The CDI {@code ObjectMapper} when the consumer has one, so {@code current} is written the way its own answers are. */
  static ObjectMapper mapper(Instance<ObjectMapper> mappers) {
    return mappers != null && mappers.isResolvable() ? mappers.get() : FALLBACK;
  }

  static Response of(ObjectMapper mapper, OfflineProblemException problem) {
    return of(
        mapper,
        problem.type(),
        problem.title(),
        problem.status(),
        problem.getMessage(),
        problem.current(),
        problem.currentVersion());
  }

  static Response of(
      ObjectMapper mapper,
      String type,
      String title,
      int status,
      String detail,
      Object current,
      Long currentVersion) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", type);
    body.put("title", title);
    body.put("status", status);
    if (detail != null) {
      body.put("detail", detail);
    }
    if (current != null) {
      body.put("current", current);
    }
    if (currentVersion != null) {
      body.put("currentVersion", currentVersion);
    }
    String json;
    try {
      json = mapper.writeValueAsString(body);
    } catch (JsonProcessingException e) {
      // The record would not serialize; the refusal still has to reach the client.
      body.remove("current");
      try {
        json = FALLBACK.writeValueAsString(body);
      } catch (JsonProcessingException impossible) {
        throw new IllegalStateException(impossible);
      }
    }
    Response.ResponseBuilder response =
        Response.status(status).type(Problems.MEDIA_TYPE).entity(json);
    if (currentVersion != null) {
      response.header(HttpHeaders.ETAG, RecordVersions.etag(currentVersion));
    }
    return response.build();
  }
}
