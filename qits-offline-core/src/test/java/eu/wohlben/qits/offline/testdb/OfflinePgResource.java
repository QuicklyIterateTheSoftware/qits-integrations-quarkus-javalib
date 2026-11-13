package eu.wohlben.qits.offline.testdb;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;

/**
 * Stands up the database before Quarkus boots: the module's own {@code schema.sql}, applied as a
 * service's migration would apply it, plus the fixture table, and a datasource pointed at it.
 *
 * <p>The datasource carries {@code validate-on-borrow} as the fleet's does, because {@code Changes}
 * relies on its {@code SET TRANSACTION} being the first statement of the transaction.
 */
public class OfflinePgResource implements QuarkusTestResourceLifecycleManager {

  /** The fixture's table, with {@code change_xid} and its trigger as an adopting service adds them. */
  public static final String TABLE = "fixture_things";

  @Override
  public Map<String, String> start() {
    String url = EmbeddedPg.directUrl();
    try (Connection connection =
            DriverManager.getConnection(url, EmbeddedPg.USER, EmbeddedPg.PASSWORD);
        Statement sql = connection.createStatement()) {
      sql.execute(
          "drop table if exists qits_idempotency, qits_tombstones, " + TABLE + " cascade;"
              + " drop function if exists qits_touch_change_xid() cascade");
      sql.execute(schema());
      sql.execute(
          "create table "
              + TABLE
              + " (id text primary key, name text not null, version bigint not null,"
              + " change_xid xid8 not null default pg_current_xact_id());"
              + " create trigger fixture_things_change_xid before update on "
              + TABLE
              + " for each row execute function qits_touch_change_xid()");
    } catch (Exception e) {
      throw new IllegalStateException("could not prepare the offline test database", e);
    }
    return Map.of(
        "quarkus.datasource.db-kind", "postgresql",
        "quarkus.datasource.username", EmbeddedPg.USER,
        "quarkus.datasource.password", EmbeddedPg.PASSWORD,
        "quarkus.datasource.jdbc.url", url,
        "quarkus.datasource.jdbc.validate-on-borrow", "true");
  }

  @Override
  public void stop() {}

  /** The schema exactly as the module ships it. */
  public static String schema() throws Exception {
    try (InputStream in =
        OfflinePgResource.class.getResourceAsStream("/META-INF/qits-offline/schema.sql")) {
      if (in == null) {
        throw new IllegalStateException("META-INF/qits-offline/schema.sql is not on the classpath");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
