package eu.wohlben.qits.offline;

import eu.wohlben.qits.offline.fixture.ThingResource;
import eu.wohlben.qits.offline.testdb.OfflinePgResource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

/** What the @QuarkusTests read and reset behind the API's back. */
final class OfflineDb {

  private OfflineDb() {}

  static void reset(DataSource dataSource) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement sql = connection.createStatement()) {
      sql.execute(
          "truncate qits_idempotency, qits_tombstones, " + OfflinePgResource.TABLE);
    }
    ThingResource.reset();
  }

  static long count(DataSource dataSource, String where, Object... params) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement query =
            connection.prepareStatement(
                "select count(*) from " + OfflinePgResource.TABLE + " where " + where)) {
      for (int i = 0; i < params.length; i++) {
        query.setObject(i + 1, params[i]);
      }
      try (ResultSet result = query.executeQuery()) {
        result.next();
        return result.getLong(1);
      }
    }
  }
}
