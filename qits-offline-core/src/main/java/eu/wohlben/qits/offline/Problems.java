package eu.wohlben.qits.offline;

/**
 * The {@code type} URNs an offline operation refuses with, and the media type it refuses in (RFC
 * 9457). A client switches on these strings, so they are the contract: never reword one.
 */
public final class Problems {

  /** {@code application/problem+json}. */
  public static final String MEDIA_TYPE = "application/problem+json";

  /** 412: {@code If-Match} names an older version. {@code current} is the record now. */
  public static final String STALE_VERSION = "urn:qits:problem:stale-version";

  /** 428: an update or delete without {@code If-Match}, or with {@code *}. */
  public static final String VERSION_REQUIRED = "urn:qits:problem:version-required";

  /** 422: the same key from the same caller with a different request. */
  public static final String IDEMPOTENCY_KEY_REUSED = "urn:qits:problem:idempotency-key-reused";

  /** 400: an {@code Idempotency-Key} that is not 1-255 printable ASCII characters. */
  public static final String IDEMPOTENCY_KEY_INVALID = "urn:qits:problem:idempotency-key-invalid";

  /** 410: {@code since} is older than tombstone retention. The client re-syncs from scratch. */
  public static final String CURSOR_EXPIRED = "urn:qits:problem:cursor-expired";

  /** 400: {@code since} does not decode. */
  public static final String CURSOR_INVALID = "urn:qits:problem:cursor-invalid";

  private Problems() {}
}
