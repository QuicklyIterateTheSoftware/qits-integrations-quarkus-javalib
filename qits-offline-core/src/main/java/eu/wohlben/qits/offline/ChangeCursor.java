package eu.wohlben.qits.offline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * The opaque {@code since} of a changed-since read: base64url (unpadded) of
 *
 * <pre>{@code {"x": <xid8>, "a": [<xid8>, "<id>"] | null, "m": <xid8>?, "t": <epoch seconds>}}</pre>
 *
 * <ul>
 *   <li>{@code x}: read changes whose {@code change_xid} is at or above this — a snapshot's {@code
 *       xmin}, so a transaction still running when the cursor was issued is read next time.
 *   <li>{@code a}: mid-paging only, the last {@code (change_xid, id)} already answered; the next
 *       page starts after it.
 *   <li>{@code m}: mid-paging only, the lowest snapshot {@code xmin} this run of pages has seen.
 *       The last page answers {@code x = min(m, its own xmin)}: a write that committed between two
 *       pages with an xid below {@code a} is otherwise skipped by both.
 *   <li>{@code t}: when the snapshot behind {@code x} was taken. Older than tombstone retention, the
 *       cursor is expired.
 * </ul>
 *
 * A client never looks inside: the shape is the server's to change.
 */
public record ChangeCursor(long x, After a, Long m, long t) {

  /** The position inside one {@code change_xid}: {@code (xid, id)}, the id as text. */
  public record After(long xid, String id) {}

  private static final ObjectMapper JSON = new ObjectMapper();

  /** The cursor as the client carries it. */
  public String encode() {
    ObjectNode node = JSON.createObjectNode();
    node.put("x", x);
    if (a == null) {
      node.putNull("a");
    } else {
      node.putArray("a").add(a.xid()).add(a.id());
    }
    if (m != null) {
      node.put("m", m);
    }
    node.put("t", t);
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(node.toString().getBytes(StandardCharsets.UTF_8));
  }

  /** When the snapshot behind {@code x} was taken. */
  public Instant issuedAt() {
    return Instant.ofEpochSecond(t);
  }

  /**
   * Reads a cursor this server issued.
   *
   * @throws CursorInvalidException for anything that is not one
   */
  public static ChangeCursor decode(String since) {
    if (since == null || since.isBlank() || since.length() > 1024) {
      throw invalid();
    }
    try {
      JsonNode node = JSON.readTree(Base64.getUrlDecoder().decode(since.trim()));
      if (node == null
          || !node.isObject()
          || !node.path("x").canConvertToLong()
          || !node.path("x").isIntegralNumber()
          || !node.path("t").isIntegralNumber()) {
        throw invalid();
      }
      After after = null;
      JsonNode a = node.path("a");
      if (a.isArray()) {
        ArrayNode pair = (ArrayNode) a;
        if (pair.size() != 2 || !pair.get(0).isIntegralNumber() || !pair.get(1).isTextual()) {
          throw invalid();
        }
        after = new After(pair.get(0).asLong(), pair.get(1).asText());
      } else if (!a.isMissingNode() && !a.isNull()) {
        throw invalid();
      }
      Long m = null;
      JsonNode mNode = node.path("m");
      if (mNode.isIntegralNumber()) {
        m = mNode.asLong();
      } else if (!mNode.isMissingNode() && !mNode.isNull()) {
        throw invalid();
      }
      long x = node.get("x").asLong();
      if (x < 0 || (m != null && m < 0) || (after != null && after.xid() < x)) {
        throw invalid();
      }
      return new ChangeCursor(x, after, m, node.get("t").asLong());
    } catch (CursorInvalidException e) {
      throw e;
    } catch (Exception e) {
      throw invalid();
    }
  }

  private static CursorInvalidException invalid() {
    return new CursorInvalidException(
        "since is not a cursor this server issued. Read without since to start over.");
  }
}
