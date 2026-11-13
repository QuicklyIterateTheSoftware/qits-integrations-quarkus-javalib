package eu.wohlben.qits.offline;

/**
 * A DTO an offline operation answers: something a client can store by {@link #id()} and replace
 * only by a higher {@link #version()}.
 *
 * <p>The service backs the version with whatever it likes — JPA {@code @Version} is recommended, a
 * hand-incremented column works too — as long as every write that changes the record raises it. A
 * record type may narrow {@code id()}'s return type ({@code String id()} overrides it).
 */
public interface OfflineRecord {

  /** The record's identity, stable across versions. */
  Object id();

  /** The record's version, raised by every write that changes it. */
  long version();
}
