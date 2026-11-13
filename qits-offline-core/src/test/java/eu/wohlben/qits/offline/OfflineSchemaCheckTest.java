package eu.wohlben.qits.offline;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.offline.fixture.PlainResource;
import eu.wohlben.qits.offline.fixture.ThingResource;
import eu.wohlben.qits.offline.testdb.EmbeddedPg;
import eu.wohlben.qits.offline.testdb.OfflinePgResource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * The boot check's logic, without booting: which beans switch it on, and that a database without
 * the schema is refused with every missing object named. A {@code @QuarkusTest} that fails boot
 * on purpose would fail the suite with it; the boot path itself is the observer calling these two.
 */
class OfflineSchemaCheckTest {

  @OfflineCapable
  static class MarkedClass {}

  static class MarkedMethod {
    @OfflineCapable
    void write() {}
  }

  @Test
  void anOfflineCapableClassOrMethodSwitchesTheCheckOn() {
    assertTrue(OfflineSchemaCheck.anyOfflineCapable(List.of(PlainResource.class, MarkedClass.class)));
    assertTrue(OfflineSchemaCheck.anyOfflineCapable(List.of(MarkedMethod.class)));
    assertTrue(OfflineSchemaCheck.anyOfflineCapable(List.of(ThingResource.class)));
    assertFalse(OfflineSchemaCheck.anyOfflineCapable(List.of(PlainResource.class, String.class)));
  }

  @Test
  @EnabledIf(
      value = "eu.wohlben.qits.offline.testdb.EmbeddedPg#available",
      disabledReason = "zonky's initdb refuses to run as root")
  void aDatabaseWithoutTheSchemaFailsNamingEveryMissingObject() throws Exception {
    try (Connection connection = fresh("qits_offline_schema_check")) {
      assertEquals(
          List.of(
              "table qits_idempotency", "table qits_tombstones", "function qits_touch_change_xid()"),
          OfflineSchemaCheck.missing(connection));
      IllegalStateException refused =
          assertThrows(IllegalStateException.class, () -> OfflineSchemaCheck.require(connection));
      assertTrue(refused.getMessage().contains("table qits_idempotency"), refused.getMessage());
      assertTrue(refused.getMessage().contains("META-INF/qits-offline/schema.sql"));

      try (Statement sql = connection.createStatement()) {
        sql.execute(OfflinePgResource.schema());
      }
      assertEquals(List.of(), OfflineSchemaCheck.missing(connection));
      assertDoesNotThrow(() -> OfflineSchemaCheck.require(connection));
    }
  }

  private static Connection fresh(String database) throws Exception {
    String url = EmbeddedPg.url(database);
    Connection connection = DriverManager.getConnection(url, EmbeddedPg.USER, EmbeddedPg.PASSWORD);
    try (Statement sql = connection.createStatement()) {
      sql.execute(
          "drop table if exists qits_idempotency, qits_tombstones cascade;"
              + " drop function if exists qits_touch_change_xid() cascade");
    }
    return connection;
  }
}
