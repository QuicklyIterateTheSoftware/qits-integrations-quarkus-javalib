package eu.wohlben.qits.archrules.exposure.fixture.offline;

import eu.wohlben.qits.offline.ChangesPage;
import eu.wohlben.qits.offline.OfflineCapable;
import eu.wohlben.qits.offline.OfflineRecord;
import io.smallrye.mutiny.Uni;
import jakarta.persistence.Entity;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.openapi.annotations.extensions.Extension;
import org.jboss.resteasy.reactive.RestResponse;

/**
 * The entity map's offline fixtures (qits-2148), kept apart from {@code fixture.links} so that
 * suite's checked-in map stays what it was: one entity, Robin, read offline by a whole class, by a
 * single method, and not offline at all. {@code ChangesPage} is qits-offline-core's (mirrored in
 * this module's test sources) and, as in a service, not part of the import. Nothing here runs.
 */
public final class OfflineMapFixtures {

  private OfflineMapFixtures() {}

  @Entity
  public static class Robin {
    public String id;
    public long version;
  }

  public record RobinDto(String id, long version) implements OfflineRecord {
    public static RobinDto from(Robin robin) {
      return new RobinDto(robin.id, robin.version);
    }
  }

  /** Offline by its class: every row is offline; the ChangesPage reads are collections. */
  @Path("/robins")
  @OfflineCapable
  public static class RobinResource {

    @GET
    @Extension(name = "x-qits-offline", value = "true")
    public ChangesPage<RobinDto> changes(@QueryParam("since") String since) {
      return null;
    }

    @GET
    @Path("later")
    @Extension(name = "x-qits-offline", value = "true")
    public Uni<RestResponse<ChangesPage<RobinDto>>> changesLater(@QueryParam("since") String since) {
      return null;
    }
  }

  /** Offline on one method only: that row is offline, its neighbour is not. */
  @Path("/robins/one")
  public static class RobinOneResource {

    @GET
    public RobinDto get() {
      return null;
    }

    @PUT
    @OfflineCapable
    @Extension(name = "x-qits-offline", value = "true")
    public RobinDto update(RobinDto input) {
      return null;
    }
  }
}
