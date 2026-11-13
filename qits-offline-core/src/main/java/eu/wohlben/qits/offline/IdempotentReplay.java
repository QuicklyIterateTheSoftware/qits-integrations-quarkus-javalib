package eu.wohlben.qits.offline;

import jakarta.ws.rs.core.Response;

/**
 * How {@link IdempotencyInterceptor} answers a replay from a method declared to return a DTO: the
 * stored answer, carried out of the method as an exception and written by {@link
 * IdempotentReplayMapper}. Not a {@code WebApplicationException}, so a service's own mapper for
 * those never rewrites a replay. Not an error either: it carries no stack trace.
 */
public final class IdempotentReplay extends RuntimeException {

  private final transient Response response;

  IdempotentReplay(Response response) {
    super("replayed answer for Idempotency-Key", null, false, false);
    this.response = response;
  }

  /** The stored answer, with {@code Idempotency-Replayed: true}. */
  public Response response() {
    return response;
  }
}
