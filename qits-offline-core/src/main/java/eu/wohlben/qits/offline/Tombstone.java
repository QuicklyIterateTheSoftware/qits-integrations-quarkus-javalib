package eu.wohlben.qits.offline;

/** A deleted record, as a changed-since read reports it: the id and the version it was deleted at. */
public record Tombstone(Object id, long version) {}
