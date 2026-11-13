package eu.wohlben.qits.offline.fixture;

import eu.wohlben.qits.offline.OfflineRecord;

/** The fixture's record: what an offline operation answers. */
public record Thing(String id, String name, long version) implements OfflineRecord {}
