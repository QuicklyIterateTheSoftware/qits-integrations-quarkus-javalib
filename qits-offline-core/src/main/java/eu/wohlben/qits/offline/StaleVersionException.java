package eu.wohlben.qits.offline;

/**
 * 412 {@link Problems#STALE_VERSION}: the client's {@code If-Match} names a version the record has
 * moved past. The answer carries the record as it is now, so the client can show the person both
 * and let them resolve it — the server never merges.
 */
public class StaleVersionException extends OfflineProblemException {

  private final transient Object current;
  private final long currentVersion;

  public StaleVersionException(Object current, long currentVersion) {
    super(
        Problems.STALE_VERSION,
        "The record has changed",
        412,
        "The record is at version " + currentVersion + ", not the version If-Match names.");
    this.current = current;
    this.currentVersion = currentVersion;
  }

  @Override
  public Object current() {
    return current;
  }

  @Override
  public Long currentVersion() {
    return currentVersion;
  }
}
