package eu.wohlben.qits.offline;

import java.time.Duration;
import org.eclipse.microprofile.config.ConfigProvider;

/** The module's three keys, read when used so a test profile can set them like any other. */
final class OfflineConfig {

  static final String IDEMPOTENCY_RETENTION = "qits.offline.idempotency.retention";
  static final String TOMBSTONE_RETENTION = "qits.offline.tombstones.retention";
  static final String MAX_PAGE_SIZE = "qits.offline.changes.max-page-size";

  /** How many expired rows one write purges, so retention needs no scheduler and no write waits long. */
  static final int PURGE_BATCH = 100;

  private OfflineConfig() {}

  static Duration idempotencyRetention() {
    return duration(IDEMPOTENCY_RETENTION, Duration.ofDays(7));
  }

  static Duration tombstoneRetention() {
    return duration(TOMBSTONE_RETENTION, Duration.ofDays(30));
  }

  static int maxPageSize() {
    return ConfigProvider.getConfig().getOptionalValue(MAX_PAGE_SIZE, Integer.class).orElse(500);
  }

  private static Duration duration(String key, Duration fallback) {
    return ConfigProvider.getConfig()
        .getOptionalValue(key, String.class)
        .map(String::trim)
        .map(value -> Duration.parse(value.toUpperCase(java.util.Locale.ROOT)))
        .orElse(fallback);
  }
}
