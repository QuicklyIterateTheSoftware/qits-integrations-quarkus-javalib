package eu.wohlben.qits.offline;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** Writes the stored answer an {@link IdempotentReplay} carries, unchanged. */
@Provider
public class IdempotentReplayMapper implements ExceptionMapper<IdempotentReplay> {

  @Override
  public Response toResponse(IdempotentReplay replay) {
    return replay.response();
  }
}
