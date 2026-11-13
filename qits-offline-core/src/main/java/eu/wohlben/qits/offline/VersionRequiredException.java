package eu.wohlben.qits.offline;

/**
 * 428 {@link Problems#VERSION_REQUIRED}: an update or delete that does not name the version it was
 * made against. {@code If-Match: *} counts as not naming one, because a write against any version
 * is last-write-wins.
 */
public class VersionRequiredException extends OfflineProblemException {

  public VersionRequiredException(String detail) {
    super(Problems.VERSION_REQUIRED, "A record version is required", 428, detail);
  }
}
