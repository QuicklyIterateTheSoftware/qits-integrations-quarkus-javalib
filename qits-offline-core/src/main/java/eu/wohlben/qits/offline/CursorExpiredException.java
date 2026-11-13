package eu.wohlben.qits.offline;

/**
 * 410 {@link Problems#CURSOR_EXPIRED}: the cursor is older than tombstone retention, so deletions
 * since it may already be forgotten. The client drops its copy and reads from scratch.
 */
public class CursorExpiredException extends OfflineProblemException {

  public CursorExpiredException(String detail) {
    super(Problems.CURSOR_EXPIRED, "The cursor has expired", 410, detail);
  }
}
