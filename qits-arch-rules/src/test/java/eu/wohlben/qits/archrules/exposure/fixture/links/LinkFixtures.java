package eu.wohlben.qits.archrules.exposure.fixture.links;

import eu.wohlben.qits.db.exposure.Exposes;
import eu.wohlben.qits.db.exposure.Projection;
import eu.wohlben.qits.db.exposure.Technical;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * One entity per way of reaching an operation, each with the smallest code that shows it, plus
 * the entities that reach none. The letters are the entities; each section names the link kind it
 * proves. Nothing here runs — the analysis reads bytecode only.
 */
public final class LinkFixtures {

  private LinkFixtures() {}

  // --- factory: a static method on the DTO reads the entity ----------------------------------

  @Entity
  @Table(name = "alpha")
  public static class Alpha {
    public String name;

    /** A Panache-shaped static finder: calling it reads Alpha. */
    public static Alpha findById(UUID id) {
      return null;
    }
  }

  public record AlphaDto(String name) {
    public static AlphaDto from(Alpha alpha) {
      return new AlphaDto(alpha.name);
    }
  }

  @Path("/alphas/")
  public static class AlphaResource {
    @GET
    @Path("/{id}")
    public AlphaDto get(UUID id) {
      return AlphaDto.from(Alpha.findById(id));
    }
  }

  // --- factory: a constructor of the DTO takes the entity --------------------------------------

  @Entity
  public static class Bravo {
    public String name;
  }

  public record BravoDto(String name) {
    public BravoDto(Bravo bravo) {
      this(bravo.name);
    }
  }

  @Path("bravos")
  public static class BravoResource {
    @GET
    @Operation(operationId = "listBravos")
    public List<BravoDto> list() {
      return List.of();
    }
  }

  // --- mapper: a MapStruct-shaped interface, abstract, no generated Impl ------------------------

  @Entity
  public static class Charlie {
    public String name;
  }

  public record CharlieDto(String name) {}

  public interface CharlieMapper {
    CharlieDto toDto(Charlie charlie);
  }

  @Path("/charlies")
  public static class CharlieResource {
    @GET
    public CompletionStage<CharlieDto> one() {
      return CompletableFuture.completedFuture(null);
    }
  }

  // --- assembler: finds the row through a static finder and news the DTO ------------------------

  @Entity
  public static class Delta {
    public String name;

    public static Delta findById(UUID id) {
      return null;
    }

    public static List<Delta> listAll() {
      return List.of();
    }
  }

  public record DeltaSummary(String name) {}

  public static class DeltaAssembler {
    public DeltaSummary summary(UUID id) {
      Delta delta = Delta.findById(id);
      return new DeltaSummary(delta.name);
    }

    /** The same inside a lambda: ArchUnit attributes the lambda's accesses to this method. */
    public List<DeltaSummary> all() {
      return Delta.listAll().stream().map(delta -> new DeltaSummary(delta.name)).toList();
    }
  }

  @Path("/deltas")
  public static class DeltaResource {
    @GET
    public Optional<DeltaSummary> summary() {
      return Optional.empty();
    }
  }

  // --- via: Echo → EchoLive (a carrier no operation returns) → EchoView -----------------------

  @Entity
  public static class Echo {
    public String name;
  }

  public record EchoLive(String name, int watchers) {
    public static EchoLive of(Echo echo) {
      return new EchoLive(echo.name, 0);
    }
  }

  public record EchoView(String label) {
    public static EchoView from(EchoLive live) {
      return new EchoView(live.name() + " (" + live.watchers() + ")");
    }
  }

  @Path("/echoes")
  public static class EchoResource {
    @GET
    public EchoView live() {
      return null;
    }
  }

  // --- annotation: @Exposes, where no code path shows the link ----------------------------------

  @Entity
  public static class Foxtrot {
    public String name;
  }

  @Exposes(Foxtrot.class)
  public record FoxtrotDto(String name) {}

  @Path("/foxtrots")
  public static class FoxtrotResource {
    @GET
    public FoxtrotDto get() {
      return new FoxtrotDto("by hand");
    }
  }

  // --- body: a Response-returning operation whose body news the DTO -----------------------------

  @Entity
  public static class Golf {
    public String name;

    public static Golf findById(UUID id) {
      return null;
    }
  }

  public record GolfDto(String name) {}

  @Path("/golfs")
  public static class GolfResource {
    @GET
    @Path("{id}")
    public Response get(UUID id) {
      Golf golf = Golf.findById(id);
      return Response.ok(new GolfDto(golf.name)).build();
    }
  }

  // --- returns, nested: the row DTO rides inside a page as a List field -------------------------

  @Entity
  public static class Hotel {
    public String name;
  }

  public record HotelRow(String name) {
    public static HotelRow from(Hotel hotel) {
      return new HotelRow(hotel.name);
    }
  }

  public record HotelPage(List<HotelRow> rows, int total) {}

  @Path("/hotels")
  public static class HotelResource {
    @GET
    public HotelPage page() {
      return null;
    }
  }

  // --- schema: @APIResponse names the DTO, the body hands out a string --------------------------

  @Entity
  public static class India {
    public String name;
  }

  public record IndiaDto(String name) {
    public static IndiaDto from(India india) {
      return new IndiaDto(india.name);
    }
  }

  @Path("/indias")
  public static class IndiaResource {
    @GET
    @APIResponse(
        responseCode = "200",
        content = @Content(schema = @Schema(implementation = IndiaDto.class)))
    public Response get() {
      return Response.ok(rendered()).build();
    }

    @POST
    @APIResponses({
      @APIResponse(
          responseCode = "201",
          content = @Content(schema = @Schema(implementation = IndiaDto.class)))
    })
    public Response create() {
      return Response.status(201).entity(rendered()).build();
    }

    private static String rendered() {
      return "{}";
    }
  }

  // --- assembler through a repository: calls on a PanacheRepository<Kilo> read Kilo -------------

  @Entity
  public static class Kilo {
    public String name;
  }

  /** The shape of Panache's repository: the inherited finders' return types erase to Object. */
  public interface RepositoryLike<T> {
    default List<T> listAll() {
      return List.of();
    }
  }

  public static class KiloRepository implements RepositoryLike<Kilo> {}

  public record KiloCount(int count) {}

  public static class KiloService {
    KiloRepository repository;

    public KiloCount count() {
      return new KiloCount(repository.listAll().size());
    }
  }

  @Path("/kilos")
  public static class KiloResource {
    @GET
    public KiloCount count() {
      return null;
    }
  }

  // --- the exemptions ---------------------------------------------------------------------------

  @Entity
  @Technical(reason = "n:n join of alphas and bravos")
  public static class Lima {}

  @Entity
  @Projection(of = "qits-projects:Repository")
  public static class Mike {}

  // --- the failures, and the gap that excuses one -----------------------------------------------

  /** Reaches nothing, is listed nowhere: everyDomainEntityIsExposed fails it. */
  @Entity
  public static class November {
    public String name;
  }

  /** Reaches nothing, listed as "Oscar qits-1945": a genuine known gap. */
  @Entity
  public static class Oscar {
    public String name;
  }

  // --- direct: an operation returns the entity itself -------------------------------------------

  @Entity
  public static class Papa {
    public String name;
  }

  @Path("/papas")
  public static class PapaResource {
    @GET
    public List<Papa> list() {
      return List.of();
    }
  }

  // --- pruning: one link per reaching type, nothing for a type no operation hands out ----------

  /** Three links to QuebecDto, one to QuebecStats; the map keeps only QuebecDto's factory. */
  @Entity
  public static class Quebec {
    public String name;

    public static Quebec findById(UUID id) {
      return null;
    }
  }

  public record QuebecDto(String name) {
    public static QuebecDto from(Quebec quebec) {
      return new QuebecDto(quebec.name);
    }
  }

  public interface QuebecMapper {
    QuebecDto toDto(Quebec quebec);
  }

  /** Built from Quebec, handed out by nothing: an assembler link the map drops. */
  public record QuebecStats(int length) {}

  public static class QuebecService {
    public QuebecStats stats(UUID id) {
      return new QuebecStats(Quebec.findById(id).name.length());
    }
  }

  @Path("/quebecs")
  public static class QuebecResource {
    /** Returns QuebecDto and names it as its schema too: one operation row, kind returns. */
    @GET
    @APIResponse(
        responseCode = "200",
        content = @Content(schema = @Schema(implementation = QuebecDto.class)))
    public QuebecDto get(UUID id) {
      return QuebecDto.from(Quebec.findById(id));
    }
  }

  // --- excluded: an outbound REST client and what is nested in it ------------------------------

  /** Reachable only through RomeoClient.RomeoState: unexposed. */
  @Entity
  public static class Romeo {
    public String name;
  }

  @RegisterRestClient(configKey = "peer")
  @Path("/peer/romeos")
  public interface RomeoClient {
    /** Another service's door: not an operation of this one. */
    @GET
    RomeoState state();

    /** The response of a call this service makes: never a carrier, never a DTO. */
    record RomeoState(String name) {
      public static RomeoState of(Romeo romeo) {
        return new RomeoState(romeo.name);
      }
    }
  }

  public record RomeoView(String label) {
    public static RomeoView from(RomeoClient.RomeoState state) {
      return new RomeoView(state.name());
    }
  }

  @Path("/romeos")
  public static class RomeoResource {
    @GET
    public RomeoView get() {
      return null;
    }

    @GET
    @Path("raw")
    public RomeoClient.RomeoState raw() {
      return null;
    }
  }

  // --- excluded: primary-key classes -------------------------------------------------------------

  /** Reachable only through its @IdClass key: unexposed. */
  @Entity
  @IdClass(SierraId.class)
  public static class Sierra {
    @Id public String left;
    @Id public String right;
  }

  public static class SierraId {
    public String left;
    public String right;

    public SierraId() {}

    public SierraId(Sierra sierra) {
      this.left = sierra.left;
      this.right = sierra.right;
    }
  }

  public record SierraView(String key) {
    public static SierraView from(SierraId id) {
      return new SierraView(id.left + "/" + id.right);
    }
  }

  @Path("/sierras")
  public static class SierraResource {
    @GET
    public SierraView get() {
      return null;
    }
  }

  /** Reachable only through its @EmbeddedId key: unexposed. */
  @Entity
  public static class Tango {
    @EmbeddedId public TangoKey id;
  }

  @Embeddable
  public static class TangoKey {
    public String left;
    public String right;

    public static TangoKey of(Tango tango) {
      return tango.id;
    }
  }

  public record TangoView(String key) {
    public static TangoView from(TangoKey key) {
      return new TangoView(key.left + "/" + key.right);
    }
  }

  @Path("/tangos")
  public static class TangoResource {
    @GET
    public TangoView get() {
      return null;
    }
  }

  // --- kept: an @Embeddable that is no key is a carrier like any other ---------------------------

  @Entity
  public static class Uniform {
    @Id public UUID id;
    @Embedded public UniformAddress address;
  }

  @Embeddable
  public static class UniformAddress {
    public String city;

    public static UniformAddress of(Uniform uniform) {
      return uniform.address;
    }
  }

  public record UniformView(String city) {
    public static UniformView from(UniformAddress address) {
      return new UniformView(address.city);
    }
  }

  @Path("/uniforms")
  public static class UniformResource {
    @GET
    public UniformView get() {
      return null;
    }
  }

  // --- excluded: an enum is a value, never a carrier --------------------------------------------

  /** Reachable only through an enum a method returns: unexposed. */
  @Entity
  public static class Victor {
    public String name;

    public static VictorStatus statusOf(Victor victor) {
      return VictorStatus.ACTIVE;
    }
  }

  public enum VictorStatus {
    ACTIVE,
    RETIRED
  }

  /** What an operation actually hands out; the enum rides along as a field, never as a carrier. */
  public record VictorView(VictorStatus status) {}

  @Path("/victors")
  public static class VictorResource {
    @GET
    public VictorView get() {
      return null;
    }
  }
}
