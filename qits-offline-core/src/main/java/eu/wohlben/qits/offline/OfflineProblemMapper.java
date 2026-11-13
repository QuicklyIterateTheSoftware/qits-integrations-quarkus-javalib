package eu.wohlben.qits.offline;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Answers every {@link OfflineProblemException} as {@code application/problem+json}: {@code type},
 * {@code title}, {@code status}, {@code detail}, and {@code current} plus {@code currentVersion}
 * (and the matching {@code ETag}) where the exception carries them.
 */
@Provider
public class OfflineProblemMapper implements ExceptionMapper<OfflineProblemException> {

  @Inject Instance<ObjectMapper> mappers;

  @Override
  public Response toResponse(OfflineProblemException problem) {
    return ProblemResponses.of(ProblemResponses.mapper(mappers), problem);
  }
}
