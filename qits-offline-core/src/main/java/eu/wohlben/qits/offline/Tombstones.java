package eu.wohlben.qits.offline;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * The deletions a changed-since read reports, in {@code qits_tombstones}. Call both methods inside
 * the transaction that deletes or re-creates the record, on the default {@code DataSource}, so the
 * tombstone and the row it stands for commit or roll back together.
 *
 * <pre>{@code
 * things.delete(id);
 * tombstones.record("things", id, current.version());
 * }</pre>
 *
 * Each {@link #record} first purges at most {@value OfflineConfig#PURGE_BATCH} tombstones older
 * than {@code qits.offline.tombstones.retention} (default {@code P30D}), so retention needs no
 * scheduler. A cursor older than that retention is answered 410 by {@link Changes}.
 */
@ApplicationScoped
public class Tombstones {

  private static final String PURGE =
      "delete from qits_tombstones where (resource, record_id) in ("
          + "select resource, record_id from qits_tombstones"
          + " where deleted_at < now() - ? * interval '1 second'"
          + " limit "
          + OfflineConfig.PURGE_BATCH
          + " for update skip locked)";

  private static final String UPSERT =
      "insert into qits_tombstones (resource, record_id, version) values (?, ?, ?)"
          + " on conflict (resource, record_id) do update set version = excluded.version,"
          + " change_xid = pg_current_xact_id(), deleted_at = now()";

  private static final String CLEAR =
      "delete from qits_tombstones where resource = ? and record_id = ?";

  @Inject Instance<DataSource> dataSource;

  /** Records that {@code resource}'s record {@code id} was deleted at {@code version}. */
  public void record(String resource, Object id, long version) {
    try (Connection connection = dataSource.get().getConnection()) {
      try (PreparedStatement purge = connection.prepareStatement(PURGE)) {
        purge.setLong(1, OfflineConfig.tombstoneRetention().toSeconds());
        purge.executeUpdate();
      }
      try (PreparedStatement upsert = connection.prepareStatement(UPSERT)) {
        upsert.setString(1, resource);
        upsert.setString(2, String.valueOf(id));
        upsert.setLong(3, version);
        upsert.executeUpdate();
      }
    } catch (SQLException e) {
      throw new IllegalStateException("could not record the tombstone of " + resource + " " + id, e);
    }
  }

  /** Forgets the deletion of {@code id}, because the record exists again. Call it on create. */
  public void clear(String resource, Object id) {
    try (Connection connection = dataSource.get().getConnection();
        PreparedStatement clear = connection.prepareStatement(CLEAR)) {
      clear.setString(1, resource);
      clear.setString(2, String.valueOf(id));
      clear.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("could not clear the tombstone of " + resource + " " + id, e);
    }
  }
}
