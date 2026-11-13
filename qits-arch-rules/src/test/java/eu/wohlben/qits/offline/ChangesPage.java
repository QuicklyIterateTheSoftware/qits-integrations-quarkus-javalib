package eu.wohlben.qits.offline;

import java.util.List;

/**
 * A TEST FIXTURE mirroring qits-offline-core's record by name and shape, its {@code nextCursor}
 * included; see {@link OfflineCapable}.
 */
public record ChangesPage<T extends OfflineRecord>(
    List<T> items, List<Tombstone> deleted, String nextCursor, boolean hasMore) {}
