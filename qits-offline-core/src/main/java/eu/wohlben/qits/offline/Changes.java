package eu.wohlben.qits.offline;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * Builds the {@link ChangesPage} of an offline collection read, over any table that carries {@code
 * change_xid xid8} (see {@code META-INF/qits-offline/schema.sql}) and the tombstones of one {@code
 * resource} name:
 *
 * <pre>{@code
 * @GET @OfflineCapable
 * public ChangesPage<Thing> list(@QueryParam("since") String since) {
 *   return changes.page("things", "things", "id", since,
 *       row -> new Thing(row.getString("id"), row.getString("name"), row.getLong("version")));
 * }
 * }</pre>
 *
 * <p>The rows come from {@code select t.* from <from> t}, so the mapper reads the table's own
 * columns, and {@code from} may be a table name or a parenthesized subquery with {@code ?}
 * parameters ({@code "(select * from things where owner = ?)"}, with the owner in {@code
 * params}) for a caller-scoped read. {@code from} and {@code idColumn} are SQL, written by the
 * service, never taken from a request. Tombstones are per {@code resource} only: a scoped read
 * reports every deletion of that resource, which a client applies as a no-op for ids it never held.
 *
 * <p>One page is read in one {@code REPEATABLE READ} transaction of its own (any transaction the
 * caller holds is suspended), so the rows, the tombstones and the snapshot {@code xmin} the next
 * cursor carries agree:
 *
 * <ul>
 *   <li>no {@code since}: every row, ordered {@code (change_xid, id)} — the initial sync. Its first
 *       page carries no tombstones: a client with nothing has nothing to delete.
 *   <li>{@code since}: rows and tombstones with {@code change_xid >= x} (after {@code a}, when the
 *       cursor is mid-paging), merged in {@code (change_xid, id)} order.
 *   <li>a full page answers {@code hasMore} and a cursor that keeps {@code x} and moves {@code a};
 *       the last page answers a cursor at the lowest snapshot {@code xmin} the run of pages saw.
 *   <li>a cursor older than {@code qits.offline.tombstones.retention}: {@link
 *       CursorExpiredException} (410); one that does not decode: {@link CursorInvalidException}
 *       (400).
 * </ul>
 *
 * Ids are compared as text in the {@code "C"} collation, so an id of any type pages stably. A
 * record may arrive twice across reads, which the client's upsert by id and version absorbs.
 */
@ApplicationScoped
public class Changes {

  /** Maps the current row of the caller's table to its record. Do not move the cursor. */
  @FunctionalInterface
  public interface RowMapper<T> {
    T map(ResultSet row) throws SQLException;
  }

  private static final Pattern COLUMN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  @Inject Instance<DataSource> dataSource;

  /** One page of at most {@code qits.offline.changes.max-page-size} (default 500) changes. */
  public <T extends OfflineRecord> ChangesPage<T> page(
      String resource, String from, String idColumn, String since, RowMapper<T> rows) {
    return page(resource, from, idColumn, since, 0, rows);
  }

  /**
   * One page of at most {@code limit} changes, capped at {@code qits.offline.changes.max-page-size};
   * {@code limit <= 0} means the cap. {@code params} bind the {@code ?}s inside {@code from}.
   */
  public <T extends OfflineRecord> ChangesPage<T> page(
      String resource,
      String from,
      String idColumn,
      String since,
      int limit,
      RowMapper<T> rows,
      Object... params) {
    if (!COLUMN.matcher(idColumn).matches()) {
      throw new IllegalArgumentException("idColumn must be a plain column name: " + idColumn);
    }
    ChangeCursor cursor = since == null || since.isBlank() ? null : ChangeCursor.decode(since);
    Instant now = Instant.now();
    if (cursor != null) {
      Duration retention = OfflineConfig.tombstoneRetention();
      if (cursor.issuedAt().isBefore(now.minus(retention))) {
        throw new CursorExpiredException(
            "The cursor is older than the "
                + retention
                + " deletions are kept for. Drop the local copy and read without since.");
      }
    }
    int max = OfflineConfig.maxPageSize();
    int size = limit <= 0 ? max : Math.min(limit, max);
    return QuarkusTransaction.requiringNew()
        .call(() -> read(resource, from, idColumn, cursor, size, rows, params, now));
  }

  private <T extends OfflineRecord> ChangesPage<T> read(
      String resource,
      String from,
      String idColumn,
      ChangeCursor cursor,
      int size,
      RowMapper<T> mapper,
      Object[] params,
      Instant now) {
    try (Connection connection = dataSource.get().getConnection()) {
      try (Statement isolation = connection.createStatement()) {
        // The first statement of the transaction, as postgres requires; the snapshot is taken by
        // the next one and every read below shares it.
        isolation.execute("set transaction isolation level repeatable read");
      }
      long xmin;
      try (Statement snapshot = connection.createStatement();
          ResultSet result =
              snapshot.executeQuery("select pg_snapshot_xmin(pg_current_snapshot())::text")) {
        result.next();
        xmin = Long.parseLong(result.getString(1));
      }

      List<Entry<T>> entries = new ArrayList<>();
      readRows(connection, from, idColumn, cursor, size, mapper, params, entries);
      if (cursor != null) {
        readTombstones(connection, resource, cursor, size, entries);
      }
      entries.sort(ORDER);

      boolean hasMore = entries.size() > size;
      List<Entry<T>> taken = hasMore ? entries.subList(0, size) : entries;
      List<T> items = new ArrayList<>();
      List<Tombstone> deleted = new ArrayList<>();
      for (Entry<T> entry : taken) {
        if (entry.item() != null) {
          items.add(entry.item());
        } else {
          deleted.add(entry.tombstone());
        }
      }

      long runXmin = cursor != null && cursor.m() != null ? Math.min(cursor.m(), xmin) : xmin;
      // When the snapshot behind the next x was taken. A run of pages that started from a cursor
      // keeps that cursor's time: older than strictly needed, which only ever expires it sooner.
      long issued = cursor != null ? cursor.t() : now.getEpochSecond();
      ChangeCursor next;
      if (hasMore) {
        Entry<T> last = taken.get(taken.size() - 1);
        next =
            new ChangeCursor(
                cursor == null ? 0 : cursor.x(),
                new ChangeCursor.After(last.xid(), last.id()),
                runXmin,
                issued);
      } else {
        long nextIssued = cursor != null && cursor.a() != null ? cursor.t() : now.getEpochSecond();
        next = new ChangeCursor(runXmin, null, null, nextIssued);
      }
      return new ChangesPage<>(items, deleted, next.encode(), hasMore);
    } catch (SQLException e) {
      throw new IllegalStateException("could not read the changes of " + resource, e);
    }
  }

  private static <T extends OfflineRecord> void readRows(
      Connection connection,
      String from,
      String idColumn,
      ChangeCursor cursor,
      int size,
      RowMapper<T> mapper,
      Object[] params,
      List<Entry<T>> entries)
      throws SQLException {
    String id = "(t." + idColumn + ")::text collate \"C\"";
    StringBuilder sql =
        new StringBuilder("select t.*, t.change_xid::text as qits_change_xid, ")
            .append(id)
            .append(" as qits_record_id from ")
            .append(from)
            .append(" t");
    sql.append(condition(cursor, "t.change_xid", id));
    sql.append(" order by t.change_xid, ").append(id).append(" limit ").append(size + 1);
    try (PreparedStatement query = connection.prepareStatement(sql.toString())) {
      int index = 1;
      for (Object param : params) {
        query.setObject(index++, param);
      }
      bindCondition(query, index, cursor);
      try (ResultSet row = query.executeQuery()) {
        while (row.next()) {
          entries.add(
              new Entry<>(
                  Long.parseLong(row.getString("qits_change_xid")),
                  row.getString("qits_record_id"),
                  mapper.map(row),
                  null));
        }
      }
    }
  }

  private static <T> void readTombstones(
      Connection connection, String resource, ChangeCursor cursor, int size, List<Entry<T>> entries)
      throws SQLException {
    String id = "record_id collate \"C\"";
    String sql =
        "select record_id, version, change_xid::text as qits_change_xid from qits_tombstones"
            + condition(cursor, "change_xid", id)
            + " and resource = ? order by change_xid, "
            + id
            + " limit "
            + (size + 1);
    try (PreparedStatement query = connection.prepareStatement(sql)) {
      int index = bindCondition(query, 1, cursor);
      query.setString(index, resource);
      try (ResultSet row = query.executeQuery()) {
        while (row.next()) {
          String recordId = row.getString("record_id");
          entries.add(
              new Entry<>(
                  Long.parseLong(row.getString("qits_change_xid")),
                  recordId,
                  null,
                  new Tombstone(recordId, row.getLong("version"))));
        }
      }
    }
  }

  private static String condition(ChangeCursor cursor, String xid, String id) {
    if (cursor == null) {
      return " where true";
    }
    if (cursor.a() == null) {
      return " where " + xid + " >= ?::text::xid8";
    }
    return " where (" + xid + ", " + id + ") > (?::text::xid8, ?)";
  }

  private static int bindCondition(PreparedStatement query, int index, ChangeCursor cursor)
      throws SQLException {
    if (cursor == null) {
      return index;
    }
    if (cursor.a() == null) {
      query.setString(index++, Long.toString(cursor.x()));
      return index;
    }
    query.setString(index++, Long.toString(cursor.a().xid()));
    query.setString(index++, cursor.a().id());
    return index;
  }

  /** {@code (change_xid, id)} in postgres' {@code "C"} order — unsigned UTF-8 bytes — rows first on a tie. */
  private static final Comparator<Entry<?>> ORDER =
      Comparator.<Entry<?>>comparingLong(Entry::xid)
          .thenComparing(
              entry -> entry.id().getBytes(StandardCharsets.UTF_8), Arrays::compareUnsigned)
          .thenComparing(entry -> entry.item() == null);

  private record Entry<T>(long xid, String id, T item, Tombstone tombstone) {}
}
