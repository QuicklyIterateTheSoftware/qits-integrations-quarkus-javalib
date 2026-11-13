package eu.wohlben.qits.offline;

/** A TEST FIXTURE mirroring qits-offline-core's interface by name; see {@link OfflineCapable}. */
public interface OfflineRecord {

  Object id();

  long version();
}
