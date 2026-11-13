package eu.wohlben.qits.offline;

/** 400 {@link Problems#CURSOR_INVALID}: {@code since} is not a cursor this server issued. */
public class CursorInvalidException extends OfflineProblemException {

  public CursorInvalidException(String detail) {
    super(Problems.CURSOR_INVALID, "The cursor is invalid", 400, detail);
  }
}
