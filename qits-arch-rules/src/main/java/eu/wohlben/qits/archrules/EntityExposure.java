package eu.wohlben.qits.archrules;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaGenericArrayType;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.domain.JavaWildcardType;
import com.tngtech.archunit.core.domain.Source;
import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget;
import eu.wohlben.qits.archrules.ExposureMap.DtoLink;
import eu.wohlben.qits.archrules.ExposureMap.EntityNode;
import eu.wohlben.qits.archrules.ExposureMap.LinkKind;
import eu.wohlben.qits.archrules.ExposureMap.Marking;
import eu.wohlben.qits.archrules.ExposureMap.OperationKind;
import eu.wohlben.qits.archrules.ExposureMap.OperationLink;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Which entities a service hands out, and how — one analysis of the bytecode, rendered two ways
 * (qits-1945): the rules in {@link EntityExposureRules} judge it, and {@link #verify} keeps it as
 * a checked-in JSON map a reviewer reads in a diff.
 *
 * <p><b>The model.</b> Every {@code jakarta.persistence.Entity} is a <em>domain entity</em> by
 * default and must reach at least one JAX-RS operation through a DTO, unless qits-db-core's
 * {@code @Technical} (plumbing) or {@code @Projection} (another service's entity) says otherwise,
 * or the service lists it as a known gap with the ticket that will close it. A domain entity
 * nobody can read is either dead or a missing door, and until now nothing said which.
 *
 * <p><b>The nodes.</b> Entities are the imported classes annotated {@code @Entity}. Operations are
 * the imported methods annotated with one of jakarta.ws.rs' {@code GET}, {@code POST}, {@code
 * PUT}, {@code PATCH}, {@code DELETE}, {@code HEAD} — except those of an outbound REST client,
 * an interface annotated {@code org.eclipse.microprofile.rest.client.inject.RegisterRestClient},
 * whose verbs are another service's doors. Every other imported class is a candidate type — a DTO,
 * or a carrier between an entity and a DTO — except classes that declare operations, throwables,
 * annotations, a {@code @RegisterRestClient} interface and every type nested in one (at any depth
 * of the outer-class chain: the client's request and response records describe a call this service
 * makes), and primary-key classes: a class named by an entity's {@code jakarta.persistence.IdClass},
 * and an {@code @Embeddable} that is the type of an {@code @EmbeddedId} field or getter (any other
 * {@code @Embeddable} stays a candidate). A key is built from every row and read by every lookup,
 * so as a carrier it would link anything to anything. Nor is an enum class ({@code
 * JavaClass.isEnum()}): a value like a status or a kind, never a carrier of entity data, so it can
 * be neither an entity→type link target, a via carrier, nor an operation row's {@code dto} — only
 * the enum class itself is excluded, never a type merely nested in one. <em>Imported</em> means in
 * the {@link JavaClasses} handed in: nothing from the JDK or a framework is ever a node. Nor is a
 * class from test output — one under a {@code test-classes} directory (Maven; Gradle and IntelliJ
 * have their own layouts) or inside a sibling's {@code -tests.jar} test-jar — ever a node of any
 * kind, entity included: {@link #isTestOutput} skips it regardless of the {@code ImportOption} the
 * caller's {@code @AnalyzeClasses} passes, because {@code ImportOption.DoNotIncludeTests} only
 * recognizes its own build tool's test-output layout, never a dependency's test-jar that `verify`
 * (unlike `test`) puts on the classpath (qits-1945).
 *
 * <p><b>Reads and builds.</b> A code unit <em>reads</em> a type when it takes it as a parameter
 * (raw, or as the argument of a {@code Collection}, {@code List}, {@code Set}, {@code Optional},
 * {@code Stream}, {@code Iterable} or a {@code Map}'s value), accesses one of its fields, calls one
 * of its methods (which covers Panache's static finders, {@code Foo.listAll()}), calls a method
 * returning it (raw or so wrapped), or calls any method on a class whose superclass or interfaces
 * are parameterized with it — {@code class FooRepository implements PanacheRepository<Foo>} reads
 * {@code Foo} on every call, because the inherited finders' return types erase to {@code Object} in
 * the bytecode. Method references ({@code Foo::name}) count as calls. A code unit <em>builds</em> a
 * type when it calls one of its constructors (or references one, {@code FooDto::new}), or returns
 * it, raw or wrapped as above or in {@code Uni}, {@code Multi}, {@code CompletionStage}.
 *
 * <p><b>Entity → type links</b>, each recorded with the code unit it was seen in:
 *
 * <ul>
 *   <li><b>factory</b> — a static method or a constructor declared on the type reads the entity:
 *       {@code static FooDto from(Foo f)}, {@code FooDto(Foo f)}.
 *   <li><b>mapper</b> — any other code unit with an entity parameter builds the type: a MapStruct
 *       interface's abstract {@code FooDto toDto(Foo f)} is enough, no generated {@code Impl} is
 *       needed.
 *   <li><b>assembler</b> — a code unit reads the entity without taking it as a parameter and
 *       builds the type: a service method that finds the row and news the DTO.
 *   <li><b>annotation</b> — qits-db-core's {@code @Exposes} on the type names the entity, for the
 *       paths bytecode cannot show.
 *   <li><b>via</b> — the entity links to a carrier by any kind above and the carrier links to the
 *       type the same way, with the carrier in the entity's place. One hop, never more: a chain
 *       longer than that is a design a reviewer should see in the map, not one the rule assumes.
 * </ul>
 *
 * <p><b>Type → operation links.</b> An operation hands out a type when its return type, unwrapped
 * through {@code List}/{@code Set}/{@code Collection}/{@code Optional}/{@code Uni}/{@code
 * Multi}/{@code CompletionStage}/{@code RestResponse}, is the type (<b>returns</b>); when that
 * unwraps to nothing imported — a {@code Response}, a raw {@code RestResponse}, {@code void} — and
 * its own body news the type or calls a method returning it, one level deep (<b>body</b>; an
 * entity counts here only when the body news it, since finding a row to map is what every body
 * does); when {@code @APIResponse(content = @Content(schema = @Schema(implementation = T.class)))}
 * names it, directly or inside {@code @APIResponses} (<b>schema</b>). From each of those roots the
 * types the root holds as instance fields — record components, unwrapped through collections,
 * {@code Optional} and map values — count too, transitively, so a page DTO carrying {@code
 * List<RowDto>} hands out {@code RowDto}. An entity reached this way is handed out
 * <b>direct</b>ly.
 *
 * <p><b>What the map keeps.</b> The verdict is taken over every link above; the map records only
 * the path. An entity's {@code dtos} are the types an operation link names, each with exactly one
 * link — the most direct, by kind {@code direct > annotation > factory > mapper > assembler > via},
 * ties broken by {@code site} — plus, where that link is {@code via}, the carrier's own best link,
 * because the carrier lies on the path. A link to a type no operation hands out is dropped: in a
 * real service those outnumber the reaching ones many times over and bury them in the diff. Its
 * {@code operations} are one row per (operationId, method, path): the {@code dto} is the best one
 * reaching the entity through that operation, by kind {@code direct > returns > schema > body},
 * ties broken by {@code dto} FQN lexicographically, and {@code kind} is that row's kind. An
 * operation reaching the entity through several DTOs therefore still gets one row, not one per
 * DTO. An unexposed entity therefore records {@code dtos = []}: what it links to reaches nothing,
 * which is the finding, and the rest is noise a reviewer cannot act on.
 *
 * <p><b>Known limits.</b> The rule errs lenient, never strict, where bytecode cannot tell:
 *
 * <ul>
 *   <li>A <b>body</b> link counts anything an operation's body builds or gets back from a call, so
 *       an authentication lookup — resolving the caller's session or user row into some imported
 *       type — counts as handing that type out. It only makes the gate lenient: an entity can pass
 *       on a lookup nobody reads, never fail on one.
 *   <li>Only JAX-RS doors are seen. Vert.x {@code Router} routes, {@code @Route} methods, WebSocket
 *       endpoints and any other non-JAX-RS door are invisible, so an entity handed out only there
 *       reads as unexposed; mark it, or list it as a known gap.
 * </ul>
 *
 * <p><b>The map.</b> {@link #verify} renders the analysis as JSON and compares it with a file the
 * service keeps in its repository, failing with a diff when they disagree; {@code
 * -Dgolden.update=true} (or {@code QITS_GOLDEN_UPDATE=true}) rewrites the file instead. A service
 * pastes:
 *
 * <pre>{@code
 * @AnalyzeClasses(packages = "eu.wohlben.qits.myservice",
 *     importOptions = ImportOption.DoNotIncludeTests.class)
 * class EntityExposureTest {
 *   static final Set<String> KNOWN_GAPS = Set.of();
 *
 *   @ArchTest
 *   static final ArchRule EXPOSED = EntityExposureRules.everyDomainEntityIsExposed(KNOWN_GAPS);
 *
 *   @ArchTest
 *   static final ArchRule GAPS = EntityExposureRules.knownGapsAreReal(KNOWN_GAPS);
 *
 *   @ArchTest
 *   static final ArchRule MARKERS = EntityExposureRules.markersAreWellFormed;
 *
 *   @ArchTest
 *   static void map(JavaClasses classes) throws IOException {
 *     EntityExposure.verify(classes, "qits-myservice", KNOWN_GAPS, Path.of("../docs/entities.json"));
 *   }
 * }
 * }</pre>
 *
 * <p>The path is relative to surefire's working directory, the module. {@link #verify} does only
 * the map; the three rules are separate {@code @ArchTest}s so each fails on its own line. The
 * analysis is cached per import, so the rules and the map do not each repeat the work.
 *
 * <p><b>Every judged type is named as a string, never imported</b>, for {@link CausationRowRules}'
 * reasons: this module carries no JPA, JAX-RS, MicroProfile OpenAPI, Mutiny or RESTEasy Reactive,
 * and no qits-db-core either. A rename over there silently un-matches here; the fixture mirror in
 * this module's test sources, compiled against the real annotations where this module can reach
 * them, is where that surfaces first.
 */
public final class EntityExposure {

  /** The JSON map's format version; a change in its shape changes this. */
  public static final int FORMAT_VERSION = 1;

  static final String ENTITY = "jakarta.persistence.Entity";
  static final String TABLE = "jakarta.persistence.Table";
  static final String ID_CLASS = "jakarta.persistence.IdClass";
  static final String EMBEDDED_ID = "jakarta.persistence.EmbeddedId";
  static final String EMBEDDABLE = "jakarta.persistence.Embeddable";
  static final String REGISTER_REST_CLIENT =
      "org.eclipse.microprofile.rest.client.inject.RegisterRestClient";
  static final String TECHNICAL = "eu.wohlben.qits.db.exposure.Technical";
  static final String PROJECTION = "eu.wohlben.qits.db.exposure.Projection";
  static final String EXPOSES = "eu.wohlben.qits.db.exposure.Exposes";
  static final String PATH = "jakarta.ws.rs.Path";
  static final String OPERATION = "org.eclipse.microprofile.openapi.annotations.Operation";
  static final String API_RESPONSE =
      "org.eclipse.microprofile.openapi.annotations.responses.APIResponse";
  static final String API_RESPONSES =
      "org.eclipse.microprofile.openapi.annotations.responses.APIResponses";

  /** The HTTP-method annotations, to the verb each one is. */
  static final Map<String, String> HTTP_METHODS;

  static {
    Map<String, String> verbs = new LinkedHashMap<>();
    for (String verb : List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")) {
      verbs.put("jakarta.ws.rs." + verb, verb);
    }
    HTTP_METHODS = Collections.unmodifiableMap(verbs);
  }

  private static final String MAP = "java.util.Map";

  /** Containers whose type arguments are what a parameter, a field or a built value holds. */
  static final Set<String> CONTAINERS =
      Set.of(
          "java.util.Collection",
          "java.util.List",
          "java.util.Set",
          "java.util.SortedSet",
          "java.util.NavigableSet",
          "java.util.Optional",
          "java.util.stream.Stream",
          "java.lang.Iterable",
          MAP,
          "java.util.SortedMap",
          "java.util.NavigableMap");

  /** {@link #CONTAINERS}, plus the asynchronous and response wrappers an operation returns. */
  static final Set<String> RESPONSE_WRAPPERS;

  static {
    Set<String> wrappers = new HashSet<>(CONTAINERS);
    wrappers.addAll(
        Set.of(
            "io.smallrye.mutiny.Uni",
            "io.smallrye.mutiny.Multi",
            "java.util.concurrent.CompletionStage",
            "java.util.concurrent.CompletableFuture",
            "org.jboss.resteasy.reactive.RestResponse"));
    RESPONSE_WRAPPERS = Set.copyOf(wrappers);
  }

  private static final Set<String> MAP_LIKE =
      Set.of(MAP, "java.util.SortedMap", "java.util.NavigableMap");

  /**
   * One analysis per import. ArchUnit hands a rule's condition a fresh copy of the imported classes,
   * never the {@link JavaClasses} the {@code @ArchTest} method receives, so the key cannot be the
   * collection. It is the import's first class by name — a {@link JavaClass} belongs to exactly one
   * import and compares by identity — held weakly, so a dropped import drops its entry; the class
   * count guards against a different selection out of the same import.
   */
  private static final Map<JavaClass, Cached> CACHE =
      Collections.synchronizedMap(new WeakHashMap<>());

  private record Cached(int size, ExposureMap map) {}

  private static final Pattern GAP = Pattern.compile(EntityExposureRules.KNOWN_GAP_PATTERN);

  /** The most direct link first: direct, annotation, factory, mapper, assembler, via; then site. */
  static final Comparator<DtoLink> LINK_PRECEDENCE =
      Comparator.comparingInt(
              (DtoLink link) ->
                  List.of(
                          LinkKind.DIRECT,
                          LinkKind.ANNOTATION,
                          LinkKind.FACTORY,
                          LinkKind.MAPPER,
                          LinkKind.ASSEMBLER,
                          LinkKind.VIA)
                      .indexOf(link.kind()))
          .thenComparing(DtoLink::site)
          .thenComparing(link -> link.via() == null ? "" : link.via());

  /**
   * The strongest operation kind first: direct, returns, schema, body; ties broken by dto FQN
   * lexicographically, so one (operationId, method, path) row picks its dto deterministically.
   */
  static final Comparator<OperationLink> OPERATION_PRECEDENCE =
      Comparator.<OperationLink>comparingInt(
              link ->
                  List.of(
                          OperationKind.DIRECT,
                          OperationKind.RETURNS,
                          OperationKind.SCHEMA,
                          OperationKind.BODY)
                      .indexOf(link.kind()))
          .thenComparing(OperationLink::dto);

  private EntityExposure() {}

  /**
   * The analysis, cached per import: the rules and the map evaluated over the same import share one
   * result.
   */
  public static ExposureMap analyze(JavaClasses classes) {
    return analyzeCached(classes);
  }

  /** The cached analysis of whatever collection of one import ArchUnit hands over. */
  static ExposureMap analyzeCached(Iterable<JavaClass> classes) {
    JavaClass first = null;
    int size = 0;
    for (JavaClass clazz : classes) {
      size++;
      if (first == null || clazz.getName().compareTo(first.getName()) < 0) {
        first = clazz;
      }
    }
    if (first == null) {
      return new ExposureMap(List.of());
    }
    Cached cached = CACHE.get(first);
    if (cached != null && cached.size() == size) {
      return cached.map();
    }
    ExposureMap computed = new Analysis(classes).run();
    CACHE.put(first, new Cached(size, computed));
    return computed;
  }

  /**
   * Renders the analysis to JSON and compares it with {@code mapFile}, or rewrites the file when
   * {@code -Dgolden.update=true} or {@code QITS_GOLDEN_UPDATE=true}.
   *
   * @param classes the service's import, as the {@code @ArchTest} method receives it
   * @param application the deployed application name, e.g. {@code "qits-projects"}
   * @param knownGaps the same {@code "SimpleName qits-NNNN"} set the rules take; recorded per entity
   * @param mapFile the checked-in map, e.g. {@code Path.of("../docs/entities.json")}
   * @throws AssertionError when the file is missing or differs, with a diff and the update hint
   */
  public static void verify(
      JavaClasses classes, String application, Set<String> knownGaps, Path mapFile)
      throws IOException {
    verify(classes, application, knownGaps, mapFile, updateRequested());
  }

  /** The same, with the update decision made by the caller; package-private for the self-test. */
  static void verify(
      JavaClasses classes, String application, Set<String> knownGaps, Path mapFile, boolean update)
      throws IOException {
    String rendered = render(analyze(classes), application, knownGaps);
    if (update) {
      Path parent = mapFile.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(mapFile, rendered, StandardCharsets.UTF_8);
      return;
    }
    String hint =
        "Rerun with -Dgolden.update=true (or QITS_GOLDEN_UPDATE=true) to write it, and commit the"
            + " result.";
    if (!Files.isRegularFile(mapFile)) {
      throw new AssertionError(
          "The entity exposure map "
              + mapFile
              + " does not exist (qits-1945). "
              + hint);
    }
    String existing = Files.readString(mapFile, StandardCharsets.UTF_8).replace("\r\n", "\n");
    if (!existing.equals(rendered)) {
      throw new AssertionError(
          "The entity exposure map "
              + mapFile
              + " is stale (qits-1945): the bytecode no longer exposes what it records. "
              + hint
              + "\n"
              + diff(existing, rendered));
    }
  }

  /** Whether the run asked for golden files to be rewritten. */
  static boolean updateRequested() {
    return "true".equalsIgnoreCase(System.getProperty("golden.update"))
        || "true".equalsIgnoreCase(System.getenv("QITS_GOLDEN_UPDATE"));
  }

  /**
   * The map as the JSON {@link #verify} writes: two-space indent, LF, a trailing newline, keys in a
   * fixed order, every list sorted — the same bytecode always renders the same bytes.
   */
  public static String render(ExposureMap map, String application, Set<String> knownGaps) {
    Map<String, String> gapTickets = new TreeMap<>();
    for (String entry : knownGaps) {
      if (GAP.matcher(entry).matches()) {
        String[] parts = entry.split(" ", 2);
        gapTickets.putIfAbsent(parts[0], parts[1]);
      }
    }
    JsonNodeFactory json = JsonNodeFactory.instance;
    ObjectNode root = json.objectNode();
    root.put("formatVersion", FORMAT_VERSION);
    root.put("application", application);
    ArrayNode entities = root.putArray("entities");
    for (EntityNode node : map.entities()) {
      ObjectNode entity = entities.addObject();
      entity.put("entity", node.entity());
      entity.put("table", node.table());
      entity.put("marking", node.marking().wire());
      entity.put("reason", node.reason());
      entity.put("projectionOf", node.projectionOf());
      entity.put("knownGap", gapTickets.get(node.simpleName()));
      ArrayNode dtos = entity.putArray("dtos");
      for (DtoLink link : node.dtos()) {
        ObjectNode dto = dtos.addObject();
        dto.put("type", link.type());
        dto.put("kind", link.kind().wire());
        dto.put("via", link.via());
        dto.put("site", link.site());
      }
      ArrayNode operations = entity.putArray("operations");
      for (OperationLink link : node.operations()) {
        ObjectNode operation = operations.addObject();
        operation.put("operationId", link.operationId());
        operation.put("method", link.method());
        operation.put("path", link.path());
        operation.put("dto", link.dto());
        operation.put("kind", link.kind().wire());
      }
    }
    DefaultIndenter lf = new DefaultIndenter("  ", "\n");
    DefaultPrettyPrinter printer =
        new DefaultPrettyPrinter().withObjectIndenter(lf).withArrayIndenter(lf);
    try {
      return new ObjectMapper().writer(printer).writeValueAsString(root) + "\n";
    } catch (IOException impossible) {
      throw new UncheckedIOException(impossible);
    }
  }

  /** The first differing lines, with a little context: enough to see what moved. */
  static String diff(String expected, String actual) {
    List<String> was = List.of(expected.split("\n", -1));
    List<String> now = List.of(actual.split("\n", -1));
    int first = 0;
    while (first < was.size() && first < now.size() && was.get(first).equals(now.get(first))) {
      first++;
    }
    int lastWas = was.size() - 1;
    int lastNow = now.size() - 1;
    while (lastWas >= first && lastNow >= first && was.get(lastWas).equals(now.get(lastNow))) {
      lastWas--;
      lastNow--;
    }
    StringBuilder out = new StringBuilder("--- recorded\n+++ analyzed\n");
    out.append("@@ line ").append(first + 1).append(" @@\n");
    int context = Math.max(0, first - 2);
    for (int i = context; i < first; i++) {
      out.append("  ").append(was.get(i)).append('\n');
    }
    int limit = 40;
    for (int i = first; i <= lastWas && limit > 0; i++, limit--) {
      out.append("- ").append(was.get(i)).append('\n');
    }
    for (int i = first; i <= lastNow && limit > 0; i++, limit--) {
      out.append("+ ").append(now.get(i)).append('\n');
    }
    if (limit == 0) {
      out.append("  … (diff truncated)\n");
    }
    return out.toString();
  }

  // ---------------------------------------------------------------------------------------------

  /** One run of the analysis over one import. */
  private static final class Analysis {

    private final Map<String, JavaClass> imported = new TreeMap<>();
    private final Set<String> entities = new LinkedHashSet<>();
    private final Set<String> operationClasses = new HashSet<>();
    private final Set<String> candidates = new HashSet<>();
    private final List<JavaMethod> operations = new ArrayList<>();
    private final Map<String, Set<String>> supertypeArguments = new HashMap<>();

    /** Source type (entity or candidate) to the links leaving it. */
    private final Map<String, Set<DtoLink>> links = new HashMap<>();

    /** Operation to the types it hands out, each with the ways it does. */
    private final Map<JavaMethod, Map<String, Set<OperationKind>>> exposed =
        new LinkedHashMap<>();

    Analysis(Iterable<JavaClass> classes) {
      for (JavaClass clazz : classes) {
        if (isTestOutput(clazz)) {
          // Never a node of any kind — see the class javadoc and isTestOutput (qits-1945).
          continue;
        }
        imported.put(clazz.getName(), clazz);
      }
    }

    ExposureMap run() {
      for (JavaClass clazz : imported.values()) {
        if (clazz.isAnnotatedWith(ENTITY)) {
          entities.add(clazz.getName());
        }
        if (insideRestClient(clazz)) {
          // An outbound call's @GET is somebody else's door, not one of this service's.
          continue;
        }
        for (JavaMethod method : clazz.getMethods()) {
          if (verb(method).isPresent()) {
            operations.add(method);
            operationClasses.add(clazz.getName());
          }
        }
      }
      Set<String> keys = keyClasses();
      for (JavaClass clazz : imported.values()) {
        String name = clazz.getName();
        // An enum is a value (a status, a kind), never a carrier of entity data; only the enum
        // class itself is excluded here, not a type merely nested in one.
        if (!entities.contains(name)
            && !operationClasses.contains(name)
            && !clazz.isAnnotation()
            && !clazz.isAssignableTo(Throwable.class)
            && !insideRestClient(clazz)
            && !keys.contains(name)
            && !clazz.isEnum()) {
          candidates.add(name);
        }
      }
      collectCodeLinks();
      collectAnnotationLinks();
      for (JavaMethod operation : operations) {
        exposed.put(operation, handedOut(operation));
      }
      List<EntityNode> nodes = new ArrayList<>();
      for (String entity : entities) {
        nodes.add(node(imported.get(entity)));
      }
      return new ExposureMap(nodes);
    }

    // --- what is never a candidate -------------------------------------------------------------

    /**
     * An {@code @RegisterRestClient} interface, or a type nested in one at any depth: the shape of
     * a call this service makes, never a door it offers or a DTO it hands out.
     */
    private boolean insideRestClient(JavaClass clazz) {
      Set<String> seen = new HashSet<>();
      Optional<JavaClass> current = Optional.of(clazz);
      while (current.isPresent() && seen.add(current.get().getName())) {
        JavaClass found = imported.getOrDefault(current.get().getName(), current.get());
        if (found.isInterface() && found.isAnnotatedWith(REGISTER_REST_CLIENT)) {
          return true;
        }
        current = found.getEnclosingClass();
      }
      return false;
    }

    /**
     * The primary-key classes: every {@code @IdClass} value on an entity, and every {@code
     * @Embeddable} that is the type of an {@code @EmbeddedId} field or getter. A key is built from
     * every row of its entity and read by every lookup, so as a carrier it would link anything to
     * anything. Other embeddables stay candidates: an embedded value is data like any field.
     */
    private Set<String> keyClasses() {
      Set<String> keys = new HashSet<>();
      for (JavaClass clazz : imported.values()) {
        if (clazz.isAnnotatedWith(ENTITY)) {
          clazz
              .tryGetAnnotationOfType(ID_CLASS)
              .flatMap(idClass -> idClass.get("value"))
              .filter(JavaClass.class::isInstance)
              .map(type -> ((JavaClass) type).getName())
              .ifPresent(keys::add);
        }
        List<JavaClass> embeddedIds = new ArrayList<>();
        for (JavaField field : clazz.getFields()) {
          if (field.isAnnotatedWith(EMBEDDED_ID)) {
            embeddedIds.add(field.getRawType());
          }
        }
        for (JavaMethod method : clazz.getMethods()) {
          if (method.isAnnotatedWith(EMBEDDED_ID)) {
            embeddedIds.add(method.getRawReturnType());
          }
        }
        for (JavaClass type : embeddedIds) {
          JavaClass found = imported.get(type.getName());
          if (found != null && found.isAnnotatedWith(EMBEDDABLE)) {
            keys.add(found.getName());
          }
        }
      }
      return keys;
    }

    // --- entity/carrier → type -----------------------------------------------------------------

    private void collectCodeLinks() {
      for (JavaClass owner : imported.values()) {
        for (JavaCodeUnit unit : owner.getCodeUnits()) {
          if (unit.getName().equals("<clinit>")) {
            continue;
          }
          Set<String> built = builds(unit);
          if (unit.isConstructor()) {
            built.add(owner.getName());
          }
          built.retainAll(candidates);
          if (built.isEmpty()) {
            continue;
          }
          Set<String> parameters = importedNames(unit.getParameterTypes(), CONTAINERS);
          Set<String> read = reads(unit);
          String site = site(unit);
          boolean factoryShaped =
              unit.isConstructor() || unit.getModifiers().contains(JavaModifier.STATIC);
          for (String type : built) {
            for (String source : read) {
              if (source.equals(type)) {
                continue;
              }
              LinkKind kind;
              if (factoryShaped && owner.getName().equals(type)) {
                kind = LinkKind.FACTORY;
              } else if (parameters.contains(source)) {
                kind = LinkKind.MAPPER;
              } else {
                kind = LinkKind.ASSEMBLER;
              }
              link(source, new DtoLink(type, kind, null, site));
            }
          }
        }
      }
    }

    private void collectAnnotationLinks() {
      for (String type : candidates) {
        JavaClass clazz = imported.get(type);
        Optional<JavaAnnotation<JavaClass>> exposes = clazz.tryGetAnnotationOfType(EXPOSES);
        if (exposes.isEmpty()) {
          continue;
        }
        Object value = exposes.get().get("value").orElse(new Object[0]);
        if (!(value instanceof Object[] named)) {
          continue;
        }
        for (Object source : named) {
          if (source instanceof JavaClass sourceClass
              && imported.containsKey(sourceClass.getName())
              && !sourceClass.getName().equals(type)) {
            link(
                sourceClass.getName(),
                new DtoLink(type, LinkKind.ANNOTATION, null, "@Exposes on " + simpleName(clazz)));
          }
        }
      }
    }

    private void link(String source, DtoLink link) {
      if (entities.contains(source) || candidates.contains(source)) {
        links.computeIfAbsent(source, s -> new LinkedHashSet<>()).add(link);
      }
    }

    /** The imported entities and candidates this code unit reads. */
    private Set<String> reads(JavaCodeUnit unit) {
      Set<String> read = new HashSet<>(importedNames(unit.getParameterTypes(), CONTAINERS));
      for (JavaFieldAccess access : unit.getFieldAccesses()) {
        read.add(access.getTargetOwner().getName());
      }
      List<JavaCodeUnitAccess<?>> calls = new ArrayList<>(unit.getMethodCallsFromSelf());
      calls.addAll(unit.getMethodReferencesFromSelf());
      for (JavaCodeUnitAccess<?> call : calls) {
        CodeUnitAccessTarget target = call.getTarget();
        JavaClass owner = target.getOwner();
        read.add(owner.getName());
        read.addAll(importedNames(List.of(target.getReturnType()), CONTAINERS));
        read.addAll(supertypeArguments(owner));
      }
      read.retainAll(imported.keySet());
      read.removeIf(name -> !entities.contains(name) && !candidates.contains(name));
      return read;
    }

    /** The imported types this code unit builds: constructors it calls, the type it returns. */
    private Set<String> builds(JavaCodeUnit unit) {
      Set<String> built = new HashSet<>();
      for (JavaCodeUnitAccess<?> construction : constructions(unit)) {
        built.add(construction.getTargetOwner().getName());
      }
      if (unit instanceof JavaMethod method) {
        built.addAll(importedNames(List.of(method.getReturnType()), RESPONSE_WRAPPERS));
      }
      built.retainAll(imported.keySet());
      return built;
    }

    private static List<JavaCodeUnitAccess<?>> constructions(JavaCodeUnit unit) {
      List<JavaCodeUnitAccess<?>> constructions =
          new ArrayList<>(unit.getConstructorCallsFromSelf());
      constructions.addAll(unit.getConstructorReferencesFromSelf());
      return constructions;
    }

    /**
     * The imported type arguments of {@code owner}'s generic supertypes, up its imported
     * superclass chain: {@code FooRepository implements PanacheRepository<Foo>} gives {@code Foo}.
     */
    private Set<String> supertypeArguments(JavaClass owner) {
      return supertypeArguments.computeIfAbsent(
          owner.getName(),
          name -> {
            Set<String> arguments = new HashSet<>();
            Set<String> seen = new HashSet<>();
            Deque<JavaClass> queue = new ArrayDeque<>(List.of(owner));
            while (!queue.isEmpty()) {
              JavaClass clazz = queue.pop();
              if (!seen.add(clazz.getName())) {
                continue;
              }
              List<JavaType> supertypes = new ArrayList<>(clazz.getInterfaces());
              clazz.getSuperclass().ifPresent(supertypes::add);
              for (JavaType supertype : supertypes) {
                if (supertype instanceof JavaParameterizedType parameterized) {
                  arguments.addAll(
                      importedNames(parameterized.getActualTypeArguments(), CONTAINERS));
                }
                JavaClass raw = supertype.toErasure();
                if (imported.containsKey(raw.getName())) {
                  queue.add(raw);
                }
              }
            }
            return Set.copyOf(arguments);
          });
    }

    // --- type → operation ----------------------------------------------------------------------

    private Map<String, Set<OperationKind>> handedOut(JavaMethod operation) {
      Map<String, Set<OperationKind>> types = new TreeMap<>();
      Set<String> returned = importedNames(List.of(operation.getReturnType()), RESPONSE_WRAPPERS);
      if (!returned.isEmpty()) {
        reach(returned, OperationKind.RETURNS, types);
      } else {
        Set<String> bodyRoots = new HashSet<>();
        for (JavaCodeUnitAccess<?> construction : constructions(operation)) {
          bodyRoots.add(construction.getTargetOwner().getName());
        }
        List<JavaCodeUnitAccess<?>> calls = new ArrayList<>(operation.getMethodCallsFromSelf());
        calls.addAll(operation.getMethodReferencesFromSelf());
        for (JavaCodeUnitAccess<?> call : calls) {
          for (String type :
              importedNames(List.of(call.getTarget().getReturnType()), RESPONSE_WRAPPERS)) {
            // A body finds the row it maps; only a body that news an entity hands it out.
            if (!entities.contains(type)) {
              bodyRoots.add(type);
            }
          }
        }
        bodyRoots.retainAll(imported.keySet());
        bodyRoots.removeAll(operationClasses);
        reach(bodyRoots, OperationKind.BODY, types);
      }
      reach(schemaImplementations(operation), OperationKind.SCHEMA, types);
      return types;
    }

    /** Adds the roots and every imported type they hold as instance fields, transitively. */
    private void reach(Set<String> roots, OperationKind kind, Map<String, Set<OperationKind>> out) {
      Set<String> seen = new HashSet<>();
      Deque<String> queue = new ArrayDeque<>(new TreeSet<>(roots));
      while (!queue.isEmpty()) {
        String type = queue.pop();
        if (!seen.add(type)) {
          continue;
        }
        out.computeIfAbsent(type, t -> new LinkedHashSet<>()).add(kind);
        JavaClass clazz = imported.get(type);
        if (clazz == null) {
          continue;
        }
        for (JavaField field : clazz.getAllFields()) {
          if (field.getModifiers().contains(JavaModifier.STATIC)) {
            continue;
          }
          queue.addAll(importedNames(List.of(field.getType()), CONTAINERS));
        }
      }
    }

    /** {@code @APIResponse(content = @Content(schema = @Schema(implementation = T.class)))}. */
    private Set<String> schemaImplementations(JavaMethod operation) {
      List<JavaAnnotation<?>> responses = new ArrayList<>();
      operation.tryGetAnnotationOfType(API_RESPONSE).ifPresent(responses::add);
      operation
          .tryGetAnnotationOfType(API_RESPONSES)
          .flatMap(wrapper -> wrapper.get("value"))
          .ifPresent(value -> responses.addAll(annotations(value)));
      Set<String> types = new HashSet<>();
      for (JavaAnnotation<?> response : responses) {
        for (JavaAnnotation<?> content : annotations(response.get("content").orElse(null))) {
          for (JavaAnnotation<?> schema : annotations(content.get("schema").orElse(null))) {
            schema
                .get("implementation")
                .filter(JavaClass.class::isInstance)
                .map(type -> ((JavaClass) type).getName())
                .filter(imported::containsKey)
                .ifPresent(types::add);
          }
        }
      }
      return types;
    }

    private static List<JavaAnnotation<?>> annotations(Object value) {
      List<JavaAnnotation<?>> out = new ArrayList<>();
      if (value instanceof JavaAnnotation<?> single) {
        out.add(single);
      } else if (value instanceof Object[] array) {
        for (Object element : array) {
          if (element instanceof JavaAnnotation<?> annotation) {
            out.add(annotation);
          }
        }
      }
      return out;
    }

    // --- the entity node -----------------------------------------------------------------------

    private EntityNode node(JavaClass entity) {
      String name = entity.getName();
      // Every link, as the verdict sees it: the first hop, and one carrier hop beyond it.
      Map<String, List<DtoLink>> byType = new HashMap<>();
      for (DtoLink first : links.getOrDefault(name, Set.of())) {
        byType.computeIfAbsent(first.type(), t -> new ArrayList<>()).add(first);
        if (!candidates.contains(first.type())) {
          continue;
        }
        for (DtoLink second : links.getOrDefault(first.type(), Set.of())) {
          if (!second.type().equals(first.type())) {
            byType
                .computeIfAbsent(second.type(), t -> new ArrayList<>())
                .add(new DtoLink(second.type(), LinkKind.VIA, first.type(), second.site()));
          }
        }
      }
      // One row per (operationId, method, path): the best dto by kind (direct > returns > schema
      // > body), ties broken by dto FQN lexicographically — see OPERATION_PRECEDENCE. Collapsing
      // rows must not shrink what reaches an operation at all, so every dto that reaches one —
      // not only the one its row keeps — is tracked separately in `reaching`, for `dtos` below.
      Map<List<String>, OperationLink> operationLinks = new HashMap<>();
      Set<String> reaching = new TreeSet<>();
      for (Map.Entry<JavaMethod, Map<String, Set<OperationKind>>> entry : exposed.entrySet()) {
        JavaMethod operation = entry.getKey();
        String operationId = operationId(operation);
        String verb = verb(operation).orElseThrow();
        String path = path(operation);
        for (Map.Entry<String, Set<OperationKind>> type : entry.getValue().entrySet()) {
          String dto = type.getKey();
          Set<OperationKind> kinds;
          if (dto.equals(name)) {
            byType
                .computeIfAbsent(name, t -> new ArrayList<>())
                .add(new DtoLink(name, LinkKind.DIRECT, null, site(operation)));
            kinds = Set.of(OperationKind.DIRECT);
          } else if (byType.containsKey(dto)) {
            kinds = type.getValue();
          } else {
            continue;
          }
          reaching.add(dto);
          for (OperationKind kind : kinds) {
            operationLinks.merge(
                List.of(operationId, verb, path),
                new OperationLink(operationId, verb, path, dto, kind),
                (was, now) -> OPERATION_PRECEDENCE.compare(was, now) <= 0 ? was : now);
          }
        }
      }
      // The map keeps what reaches an operation: per type, its most direct link; a via link's
      // carrier is on the path too, so it keeps its own best link beside it.
      Set<DtoLink> dtos = new LinkedHashSet<>();
      for (String type : reaching) {
        DtoLink best = best(byType.get(type));
        dtos.add(best);
        if (best.kind() == LinkKind.VIA) {
          dtos.add(best(byType.get(best.via())));
        }
      }
      String table =
          entity
              .tryGetAnnotationOfType(TABLE)
              .flatMap(annotation -> annotation.get("name"))
              .map(String.class::cast)
              .filter(value -> !value.isEmpty())
              .orElse(null);
      String reason = stringValue(entity, TECHNICAL, "reason");
      String projectionOf = stringValue(entity, PROJECTION, "of");
      Marking marking =
          entity.isAnnotatedWith(TECHNICAL)
              ? Marking.TECHNICAL
              : entity.isAnnotatedWith(PROJECTION) ? Marking.PROJECTION : Marking.DOMAIN;
      return new EntityNode(
          name,
          simpleName(entity),
          table,
          marking,
          reason,
          projectionOf,
          List.copyOf(dtos),
          List.copyOf(operationLinks.values()));
    }

    private static DtoLink best(List<DtoLink> candidates) {
      return candidates.stream().min(LINK_PRECEDENCE).orElseThrow();
    }

    // --- helpers -------------------------------------------------------------------------------

    /** The imported classes these types hold, unwrapped through {@code wrappers}. */
    private Set<String> importedNames(List<JavaType> types, Set<String> wrappers) {
      Set<String> out = new HashSet<>();
      for (JavaType type : types) {
        unwrap(type, wrappers, out, 0);
      }
      out.retainAll(imported.keySet());
      return out;
    }

    private static void unwrap(JavaType type, Set<String> wrappers, Set<String> out, int depth) {
      if (depth > 8) {
        return;
      }
      if (type instanceof JavaParameterizedType parameterized) {
        String raw = parameterized.toErasure().getName();
        if (!wrappers.contains(raw)) {
          out.add(raw);
          return;
        }
        List<JavaType> arguments = parameterized.getActualTypeArguments();
        if (MAP_LIKE.contains(raw) && !arguments.isEmpty()) {
          arguments = List.of(arguments.get(arguments.size() - 1));
        }
        for (JavaType argument : arguments) {
          unwrap(argument, wrappers, out, depth + 1);
        }
      } else if (type instanceof JavaWildcardType wildcard) {
        for (JavaType bound : wildcard.getUpperBounds()) {
          unwrap(bound, wrappers, out, depth + 1);
        }
      } else if (type instanceof JavaGenericArrayType array) {
        unwrap(array.getComponentType(), wrappers, out, depth + 1);
      } else if (type instanceof JavaClass clazz) {
        if (clazz.isArray()) {
          unwrap(clazz.getBaseComponentType(), wrappers, out, depth + 1);
        } else if (!wrappers.contains(clazz.getName())) {
          out.add(clazz.getName());
        }
      }
      // A type variable says nothing the bytecode can name; its bound is usually Object.
    }
  }

  /**
   * Whether {@code clazz} was imported from test output: a {@code test-classes} directory (Maven;
   * {@code /build/classes/java/test/} or {@code /build/classes/kotlin/test/} for Gradle, mirroring
   * {@code ImportOption.DoNotIncludeTests}), or a jar whose file name ends {@code -tests.jar} — the
   * test-jar a sibling module publishes for its own tests, which {@code verify}/{@code package} (but
   * not {@code test}) puts on a consuming module's classpath. Such a class never takes part in the
   * analysis — not as an entity, an operation, a candidate DTO or carrier, or a {@code via} hop —
   * whatever {@code ImportOption} the caller's own {@code @AnalyzeClasses} passes (qits-1945), so the
   * gate and the map stay the same under {@code mvn test} and {@code mvn verify}. A class with no
   * source at all (e.g. one ArchUnit synthesized) is kept.
   */
  static boolean isTestOutput(JavaClass clazz) {
    return clazz.getSource().map(EntityExposure::isTestOutput).orElse(false);
  }

  private static boolean isTestOutput(Source source) {
    String uri = source.getUri().toString();
    if (uri.contains("/test-classes/")
        || uri.contains("/build/classes/java/test/")
        || uri.contains("/build/classes/kotlin/test/")) {
      return true;
    }
    // A jar entry's URI looks like jar:file:/path/to/name-tests.jar!/pkg/Clazz.class; take the
    // file name of the part before the first '!' and check its name, not merely "contains".
    int bang = uri.indexOf('!');
    String jarPart = bang >= 0 ? uri.substring(0, bang) : uri;
    String fileName = jarPart.substring(jarPart.lastIndexOf('/') + 1);
    return fileName.endsWith("-tests.jar");
  }

  static Optional<String> verb(JavaMethod method) {
    for (Map.Entry<String, String> verb : HTTP_METHODS.entrySet()) {
      if (method.isAnnotatedWith(verb.getKey())) {
        return Optional.of(verb.getValue());
      }
    }
    return Optional.empty();
  }

  static String operationId(JavaMethod operation) {
    return operation
        .tryGetAnnotationOfType(OPERATION)
        .flatMap(annotation -> annotation.get("operationId"))
        .map(String.class::cast)
        .filter(id -> !id.isBlank())
        .orElse(simpleName(operation.getOwner()) + "." + operation.getName());
  }

  /** The class {@code @Path} and the method {@code @Path}, joined with single slashes. */
  static String path(JavaMethod operation) {
    List<String> parts = new ArrayList<>();
    for (Optional<? extends JavaAnnotation<?>> annotation :
        List.of(
            operation.getOwner().tryGetAnnotationOfType(PATH),
            operation.tryGetAnnotationOfType(PATH))) {
      annotation
          .flatMap(path -> path.get("value"))
          .map(String.class::cast)
          .map(value -> value.replaceAll("^/+|/+$", ""))
          .filter(value -> !value.isEmpty())
          .ifPresent(parts::add);
    }
    return "/" + String.join("/", parts);
  }

  /** {@code "Owner.method(ParamSimpleNames)"}, or {@code "Owner.<init>(...)"}. */
  static String site(JavaCodeUnit unit) {
    String parameters =
        unit.getRawParameterTypes().stream()
            .map(EntityExposure::simpleName)
            .collect(Collectors.joining(", "));
    return simpleName(unit.getOwner()) + "." + unit.getName() + "(" + parameters + ")";
  }

  /** The simple name, or the binary-name tail where Java has none (an anonymous class). */
  static String simpleName(JavaClass clazz) {
    String simple = clazz.getSimpleName();
    if (!simple.isEmpty()) {
      return simple;
    }
    String name = clazz.getName();
    return name.substring(name.lastIndexOf('.') + 1);
  }

  private static String stringValue(JavaClass clazz, String annotation, String property) {
    return clazz
        .tryGetAnnotationOfType(annotation)
        .flatMap(found -> found.get(property))
        .map(String.class::cast)
        .orElse(null);
  }
}
