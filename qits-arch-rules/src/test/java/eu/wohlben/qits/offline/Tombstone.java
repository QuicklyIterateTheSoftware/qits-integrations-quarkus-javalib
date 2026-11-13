package eu.wohlben.qits.offline;

/** A TEST FIXTURE mirroring qits-offline-core's record by name; see {@link OfflineCapable}. */
public record Tombstone(Object id, long version) {}
