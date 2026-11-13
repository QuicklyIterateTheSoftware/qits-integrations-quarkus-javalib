package eu.wohlben.qits.offline;

/**
 * 422 {@link Problems#IDEMPOTENCY_KEY_REUSED}: the caller already used this key for a different
 * request. A client bug, never a retry: a retry repeats the request it keys.
 */
public class IdempotencyKeyReusedException extends OfflineProblemException {

  public IdempotencyKeyReusedException(String detail) {
    super(Problems.IDEMPOTENCY_KEY_REUSED, "The idempotency key was used for another request", 422,
        detail);
  }
}
