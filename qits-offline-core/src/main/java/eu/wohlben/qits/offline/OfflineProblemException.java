package eu.wohlben.qits.offline;

/**
 * A refusal an offline operation answers as {@code application/problem+json}: its {@link
 * #type()} is one of {@link Problems}, its message is the problem's {@code detail}. {@link
 * OfflineProblemMapper} writes every subclass, so a resource just throws.
 */
public abstract class OfflineProblemException extends RuntimeException {

  private final String type;
  private final String title;
  private final int status;

  protected OfflineProblemException(String type, String title, int status, String detail) {
    super(detail);
    this.type = type;
    this.title = title;
    this.status = status;
  }

  /** The URN, one of {@link Problems}. */
  public String type() {
    return type;
  }

  /** A short, fixed summary of the type. */
  public String title() {
    return title;
  }

  /** The HTTP status. */
  public int status() {
    return status;
  }

  /** The record as it is now, for the problem's {@code current}; {@code null} for none. */
  public Object current() {
    return null;
  }

  /** The record's version now, for {@code currentVersion} and the {@code ETag}; {@code null} for none. */
  public Long currentVersion() {
    return null;
  }
}
