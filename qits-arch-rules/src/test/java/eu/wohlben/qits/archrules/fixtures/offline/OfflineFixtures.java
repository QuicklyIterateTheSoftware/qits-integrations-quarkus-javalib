package eu.wohlben.qits.archrules.fixtures.offline;

import eu.wohlben.qits.offline.ChangesPage;
import eu.wohlben.qits.offline.OfflineCapable;
import eu.wohlben.qits.offline.OfflineRecord;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.extensions.Extension;
import org.eclipse.microprofile.openapi.annotations.extensions.Extensions;
import org.jboss.resteasy.reactive.RestResponse;

/**
 * The offline rules' fixtures: {@link GoodResource} follows every convention, {@link BadResource}
 * breaks each one once per method, {@link PlainResource} breaks them all without opting in.
 * Nothing here runs — the rules read bytecode only.
 */
public final class OfflineFixtures {

  private OfflineFixtures() {}

  public record ThingDto(String id, long version, String name) implements OfflineRecord {}

  public record ThingInput(String name) {}

  /** Carries no version: not something a client can keep offline. */
  public record PlainDto(String name) {}

  /** {@code @OfflineCapable} on the class: every operation is judged, and every one passes. */
  @Path("/things")
  @OfflineCapable
  public static class GoodResource {

    @GET
    @Path("{id}")
    @Extension(name = "x-qits-offline", value = "true")
    public ThingDto get(@PathParam("id") String id) {
      return null;
    }

    @GET
    @Extension(name = "x-qits-offline", value = "true")
    public ChangesPage<ThingDto> changes(@QueryParam("since") String since) {
      return null;
    }

    @GET
    @Path("later/{id}")
    @Extension(name = "x-qits-offline", value = "true")
    public Uni<RestResponse<ThingDto>> later(@PathParam("id") String id) {
      return null;
    }

    @GET
    @Path("changes-later")
    @Extension(name = "x-qits-offline", value = "true")
    public Uni<RestResponse<ChangesPage<ThingDto>>> changesLater(
        @QueryParam("since") String since) {
      return null;
    }

    /** Names no type: cannot be checked, so passes. */
    @GET
    @Path("raw/{id}")
    @Extension(name = "x-qits-offline", value = "true")
    public Response raw(@PathParam("id") String id) {
      return null;
    }

    /** A create takes no If-Match: Idempotency-Key covers it. */
    @POST
    @Extension(name = "x-qits-offline", value = "true")
    public ThingDto create(ThingInput input) {
      return null;
    }

    @PUT
    @Path("{id}")
    @Extension(name = "x-qits-offline", value = "true")
    public ThingDto update(
        @PathParam("id") String id, @HeaderParam("If-Match") String ifMatch, ThingInput input) {
      return null;
    }

    /** A header name is case-insensitive. */
    @PATCH
    @Path("{id}")
    @Extension(name = "x-qits-offline", value = "true")
    public ThingDto patch(
        @PathParam("id") String id, @HeaderParam("if-match") String ifMatch, ThingInput input) {
      return null;
    }

    /** The mark inside an explicit container, beside another extension. */
    @DELETE
    @Path("{id}")
    @Extensions({
      @Extension(name = "x-other", value = "1"),
      @Extension(name = "x-qits-offline", value = "true")
    })
    public void delete(@PathParam("id") String id, @HeaderParam("If-Match") String ifMatch) {}
  }

  /** {@code @OfflineCapable} on single methods: each breaks one convention. */
  @Path("/bad")
  public static class BadResource {

    @PUT
    @Path("{id}")
    @OfflineCapable
    @Extension(name = "x-qits-offline", value = "true")
    public void update(@PathParam("id") String id, ThingInput input) {}

    @DELETE
    @Path("{id}")
    @OfflineCapable
    @Extension(name = "x-qits-offline", value = "true")
    public void delete(@HeaderParam("Idempotency-Key") String key) {}

    @GET
    @OfflineCapable
    @Extension(name = "x-qits-offline", value = "true")
    public List<ThingDto> list() {
      return List.of();
    }

    @GET
    @Path("array")
    @OfflineCapable
    @Extension(name = "x-qits-offline", value = "true")
    public Uni<RestResponse<ThingDto[]>> array() {
      return null;
    }

    @GET
    @Path("stream")
    @OfflineCapable
    @Extension(name = "x-qits-offline", value = "true")
    public Multi<ThingDto> stream() {
      return null;
    }

    @GET
    @Path("changes")
    @OfflineCapable
    @Extension(name = "x-qits-offline", value = "true")
    public ChangesPage<ThingDto> changes(@QueryParam("cursor") String cursor) {
      return null;
    }

    @GET
    @Path("plain")
    @OfflineCapable
    @Extension(name = "x-qits-offline", value = "true")
    public PlainDto plain() {
      return null;
    }

    @GET
    @Path("plain-later")
    @OfflineCapable
    @Extension(name = "x-qits-offline", value = "true")
    public Uni<RestResponse<PlainDto>> plainLater() {
      return null;
    }

    @POST
    @OfflineCapable
    public ThingDto unmarked(ThingInput input) {
      return null;
    }

    @GET
    @Path("other-mark")
    @OfflineCapable
    @Extension(name = "x-other", value = "true")
    public ThingDto otherMark() {
      return null;
    }

    /** Not opted in, though its class's neighbours are: never judged. */
    @GET
    @Path("not-offline")
    public List<PlainDto> notOffline() {
      return List.of();
    }
  }

  /** No {@code @OfflineCapable} anywhere: breaks every convention, judged by none. */
  @Path("/plain")
  public static class PlainResource {

    @GET
    public List<PlainDto> list() {
      return List.of();
    }

    @PUT
    public void put(ThingInput input) {}
  }
}
