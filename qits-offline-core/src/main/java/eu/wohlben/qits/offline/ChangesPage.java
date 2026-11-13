package eu.wohlben.qits.offline;

import java.util.List;

/**
 * The answer of an offline collection read: the records changed since the cursor, the ones deleted
 * since it, the cursor to read on from, and whether this page was cut short.
 *
 * <p>A client upserts {@code items} by id and version, deletes {@code deleted} by id, stores {@code
 * nextCursor}, and reads again at once while {@code hasMore}. A record may arrive twice across
 * reads; the version makes that harmless. Build one with {@link Changes}.
 */
public record ChangesPage<T extends OfflineRecord>(
    List<T> items, List<Tombstone> deleted, String nextCursor, boolean hasMore) {

  public ChangesPage {
    items = List.copyOf(items);
    deleted = List.copyOf(deleted);
  }
}
