package eu.wohlben.qits.offline;

/** 400 {@link Problems#IDEMPOTENCY_KEY_INVALID}: the key is not 1-255 printable ASCII characters. */
public class IdempotencyKeyInvalidException extends OfflineProblemException {

  public IdempotencyKeyInvalidException(String detail) {
    super(Problems.IDEMPOTENCY_KEY_INVALID, "The idempotency key is invalid", 400, detail);
  }
}
