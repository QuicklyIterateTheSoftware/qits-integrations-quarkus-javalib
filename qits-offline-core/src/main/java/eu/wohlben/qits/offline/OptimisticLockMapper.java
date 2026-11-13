package eu.wohlben.qits.offline;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * JPA's {@code OptimisticLockException} as 412 {@link Problems#STALE_VERSION}, without {@code
 * current}: another write landed between {@link RecordVersions#require}'s check and this
 * transaction's commit, so there is no record to show that is not already stale again. The client
 * re-reads and asks the person again.
 *
 * <p>The type is named by its fully-qualified name and the JPA API is a provided dependency: only a
 * JPA consumer can throw it, and that consumer already holds the API.
 */
@Provider
public class OptimisticLockMapper
    implements ExceptionMapper<jakarta.persistence.OptimisticLockException> {

  @Inject Instance<ObjectMapper> mappers;

  @Override
  public Response toResponse(jakarta.persistence.OptimisticLockException exception) {
    return ProblemResponses.of(
        ProblemResponses.mapper(mappers),
        Problems.STALE_VERSION,
        "The record has changed",
        412,
        "The record changed while this write was being applied. Read it again.",
        null,
        null);
  }
}
