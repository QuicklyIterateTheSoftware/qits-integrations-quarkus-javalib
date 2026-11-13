package eu.wohlben.qits.offline.fixture;

import eu.wohlben.qits.offline.Changes;
import eu.wohlben.qits.offline.ChangesPage;
import eu.wohlben.qits.offline.OfflineCapable;
import eu.wohlben.qits.offline.RecordVersions;
import eu.wohlben.qits.offline.Tombstones;
import eu.wohlben.qits.offline.testdb.OfflinePgResource;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;

/**
 * An offline-capable resource the way an adopting service writes one, over one tiny table with
 * plain JDBC. The static knobs let a test make the next create fail or dawdle, and count how often
 * each write method actually ran.
 */
@Path("/things")
@OfflineCapable
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ThingResource {

  public static final String RESOURCE = "things";

  public static final AtomicInteger CREATES = new AtomicInteger();
  public static final AtomicInteger UPDATES = new AtomicInteger();

  /** {@code "throw"} or {@code "response"}: how the next create fails, after its insert. */
  public static final AtomicReference<String> FAIL_NEXT_CREATE = new AtomicReference<>();

  /** How long each create waits after its insert, so a parallel duplicate overlaps it. */
  public static volatile long createDelayMillis;

  @Inject DataSource dataSource;
  @Inject Tombstones tombstones;
  @Inject Changes changes;

  public static void reset() {
    CREATES.set(0);
    UPDATES.set(0);
    FAIL_NEXT_CREATE.set(null);
    createDelayMillis = 0;
  }

  @POST
  public Response create(ThingInput input) throws Exception {
    CREATES.incrementAndGet();
    Thing thing = new Thing(input.id(), input.name(), 1);
    execute(
        "insert into " + OfflinePgResource.TABLE + " (id, name, version) values (?, ?, 1)",
        thing.id(),
        thing.name());
    tombstones.clear(RESOURCE, thing.id());
    if (createDelayMillis > 0) {
      Thread.sleep(createDelayMillis);
    }
    String failure = FAIL_NEXT_CREATE.getAndSet(null);
    if ("throw".equals(failure)) {
      throw new IllegalStateException("the fixture was told to fail");
    }
    if ("response".equals(failure)) {
      return Response.status(409).entity(thing).build();
    }
    return Response.created(URI.create("/things/" + thing.id()))
        .tag(String.valueOf(thing.version()))
        .entity(thing)
        .build();
  }

  @PUT
  @Path("{id}")
  @Transactional
  public Thing update(
      @PathParam("id") String id, @HeaderParam("If-Match") String ifMatch, ThingInput input) {
    UPDATES.incrementAndGet();
    Thing current = load(id);
    RecordVersions.require(ifMatch, current.version(), () -> current);
    Thing next = new Thing(id, input.name(), current.version() + 1);
    execute(
        "update " + OfflinePgResource.TABLE + " set name = ?, version = ? where id = ?",
        next.name(),
        next.version(),
        id);
    return next;
  }

  @DELETE
  @Path("{id}")
  @Transactional
  public void delete(@PathParam("id") String id, @HeaderParam("If-Match") String ifMatch) {
    Thing current = load(id);
    RecordVersions.require(ifMatch, current.version(), () -> current);
    execute("delete from " + OfflinePgResource.TABLE + " where id = ?", id);
    tombstones.record(RESOURCE, id, current.version());
  }

  @GET
  @Path("{id}")
  public Thing get(@PathParam("id") String id) {
    return load(id);
  }

  @GET
  public ChangesPage<Thing> list(
      @QueryParam("since") String since, @QueryParam("limit") @DefaultValue("0") int limit) {
    return changes.page(
        RESOURCE,
        OfflinePgResource.TABLE,
        "id",
        since,
        limit,
        row -> new Thing(row.getString("id"), row.getString("name"), row.getLong("version")));
  }

  @PUT
  @Path("race/{id}")
  public Thing race(@PathParam("id") String id) {
    throw new jakarta.persistence.OptimisticLockException("another write committed first");
  }

  private Thing load(String id) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement query =
            connection.prepareStatement(
                "select id, name, version from " + OfflinePgResource.TABLE + " where id = ?")) {
      query.setString(1, id);
      try (ResultSet row = query.executeQuery()) {
        if (!row.next()) {
          throw new NotFoundException();
        }
        return new Thing(row.getString(1), row.getString(2), row.getLong(3));
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private void execute(String sql, Object... params) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        statement.setObject(i + 1, params[i]);
      }
      statement.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
