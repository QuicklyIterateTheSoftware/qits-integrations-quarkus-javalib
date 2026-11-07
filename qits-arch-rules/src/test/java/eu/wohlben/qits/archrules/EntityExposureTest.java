package eu.wohlben.qits.archrules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import eu.wohlben.qits.archrules.ExposureMap.DtoLink;
import eu.wohlben.qits.archrules.ExposureMap.EntityNode;
import eu.wohlben.qits.archrules.ExposureMap.LinkKind;
import eu.wohlben.qits.archrules.ExposureMap.Marking;
import eu.wohlben.qits.archrules.ExposureMap.OperationKind;
import eu.wohlben.qits.archrules.ExposureMap.OperationLink;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The analysis, the three rules and the map against their own fixtures: one entity per link kind
 * in {@code fixture.links}, one marker mistake per class in {@code fixture.markers}. Programmatic
 * {@code evaluate}, as {@link CausationRowRulesTest} explains, because half the assertions are
 * that a rule fails; {@link EntityExposureUsageTest} is the consumer's snippet run for real.
 */
class EntityExposureTest {

  private static final String LINKS = "eu.wohlben.qits.archrules.exposure.fixture.links";
  private static final String F = LINKS + ".LinkFixtures$";

  private static final JavaClasses FIXTURES = new ClassFileImporter().importPackages(LINKS);
  private static final JavaClasses MARKERS =
      new ClassFileImporter()
          .importPackages("eu.wohlben.qits.archrules.exposure.fixture.markers");
  private static final JavaClasses ENTITYLESS =
      new ClassFileImporter().importPackages("eu.wohlben.qits.archrules.fixtures.entityless");

  private static final Set<String> GAPS = Set.of("Oscar qits-1945");

  private static final ExposureMap MAP = EntityExposure.analyze(FIXTURES);

  // --- entity → type ---------------------------------------------------------------------------

  @Test
  void aStaticFactoryOnTheDtoIsAFactoryLink() {
    assertDto("Alpha", "AlphaDto", LinkKind.FACTORY, "AlphaDto.from(Alpha)");
    assertOperation("Alpha", "AlphaResource.get", "GET", "/alphas/{id}", "AlphaDto", OperationKind.RETURNS);
    assertEquals("alpha", entity("Alpha").table());
  }

  @Test
  void aDtoConstructorTakingTheEntityIsAFactoryLink() {
    assertDto("Bravo", "BravoDto", LinkKind.FACTORY, "BravoDto.<init>(Bravo)");
    assertOperation("Bravo", "listBravos", "GET", "/bravos", "BravoDto", OperationKind.RETURNS);
    assertNull(entity("Bravo").table());
  }

  @Test
  void anAbstractMapperMethodIsAMapperLinkWithoutAnyImpl() {
    assertDto("Charlie", "CharlieDto", LinkKind.MAPPER, "CharlieMapper.toDto(Charlie)");
    assertOperation(
        "Charlie", "CharlieResource.one", "GET", "/charlies", "CharlieDto", OperationKind.RETURNS);
  }

  @Test
  void aStaticFinderAndANewDtoInOneMethodIsAnAssemblerLink() {
    assertDto("Delta", "DeltaSummary", LinkKind.ASSEMBLER, "DeltaAssembler.summary(UUID)");
    assertDto("Delta", "DeltaSummary", LinkKind.ASSEMBLER, "DeltaAssembler.all()");
    assertOperation(
        "Delta", "DeltaResource.summary", "GET", "/deltas", "DeltaSummary", OperationKind.RETURNS);
  }

  @Test
  void oneCarrierHopIsAViaLink() {
    assertDto("Echo", "EchoLive", LinkKind.FACTORY, "EchoLive.of(Echo)");
    DtoLink via = dto("Echo", "EchoView", LinkKind.VIA);
    assertEquals(F + "EchoLive", via.via());
    assertEquals("EchoView.from(EchoLive)", via.site());
    assertOperation("Echo", "EchoResource.live", "GET", "/echoes", "EchoView", OperationKind.RETURNS);
  }

  @Test
  void exposesOnTheDtoIsAnAnnotationLink() {
    assertDto("Foxtrot", "FoxtrotDto", LinkKind.ANNOTATION, "@Exposes on FoxtrotDto");
    assertOperation(
        "Foxtrot", "FoxtrotResource.get", "GET", "/foxtrots", "FoxtrotDto", OperationKind.RETURNS);
  }

  @Test
  void callsOnAClassImplementingARepositoryOfTheEntityReadTheEntity() {
    assertDto("Kilo", "KiloCount", LinkKind.ASSEMBLER, "KiloService.count()");
    assertOperation("Kilo", "KiloResource.count", "GET", "/kilos", "KiloCount", OperationKind.RETURNS);
  }

  // --- type → operation ------------------------------------------------------------------------

  @Test
  void aResponseOperationWhoseBodyNewsTheDtoExposesItByBody() {
    assertDto("Golf", "GolfDto", LinkKind.ASSEMBLER, "GolfResource.get(UUID)");
    assertOperation("Golf", "GolfResource.get", "GET", "/golfs/{id}", "GolfDto", OperationKind.BODY);
    // Finding the row to map is not handing out the row.
    assertFalse(
        entity("Golf").operations().stream().anyMatch(op -> op.kind() == OperationKind.DIRECT),
        entity("Golf").toString());
  }

  @Test
  void aDtoHeldAsAListFieldIsExposedByTheOperationReturningItsHolder() {
    assertDto("Hotel", "HotelRow", LinkKind.FACTORY, "HotelRow.from(Hotel)");
    assertOperation("Hotel", "HotelResource.page", "GET", "/hotels", "HotelRow", OperationKind.RETURNS);
  }

  @Test
  void anApiResponseSchemaExposesTheDtoSingleOrInsideApiResponses() {
    assertOperation("India", "IndiaResource.get", "GET", "/indias", "IndiaDto", OperationKind.SCHEMA);
    assertOperation(
        "India", "IndiaResource.create", "POST", "/indias", "IndiaDto", OperationKind.SCHEMA);
  }

  @Test
  void anOperationReturningTheEntityExposesItDirectly() {
    assertDto("Papa", "Papa", LinkKind.DIRECT, "PapaResource.list()");
    assertOperation("Papa", "PapaResource.list", "GET", "/papas", "Papa", OperationKind.DIRECT);
  }

  @Test
  void markedAndUnreachedEntitiesAreRecordedAsSuch() {
    assertEquals(Marking.TECHNICAL, entity("Lima").marking());
    assertEquals("n:n join of alphas and bravos", entity("Lima").reason());
    assertEquals(Marking.PROJECTION, entity("Mike").marking());
    assertEquals("qits-projects:Repository", entity("Mike").projectionOf());
    assertFalse(entity("November").exposed());
    assertFalse(entity("Oscar").exposed());
    assertTrue(entity("November").dtos().isEmpty(), entity("November").toString());
  }

  @Test
  void theAnalysisIsCachedPerImportAndIsDeterministic() {
    assertSame(MAP, EntityExposure.analyze(FIXTURES));
    assertEquals(MAP, EntityExposure.analyze(new ClassFileImporter().importPackages(LINKS)));
    // A rule's condition receives a copy of the import, not the JavaClasses: still one analysis.
    assertSame(MAP, EntityExposure.analyzeCached(new ArrayList<>(FIXTURES)));
  }

  // --- the rules ---------------------------------------------------------------------------------

  @Test
  void everyDomainEntityIsExposedFailsTheUnexposedOneOnlyAndNamesTheWaysOut() {
    String report = report(EntityExposureRules.everyDomainEntityIsExposed(GAPS), FIXTURES);
    assertTrue(
        report.contains(
            F + "November is a domain entity no operation exposes (qits-1945): expose it through a"
                + " DTO an operation returns, mark it @Technical"),
        report);
    assertTrue(report.contains("list it as a known gap \"November qits-NNNN\""), report);
    for (String fine :
        Set.of("Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel", "India",
            "Kilo", "Lima", "Mike", "Oscar", "Papa")) {
      assertFalse(report.contains("LinkFixtures$" + fine + " "), fine + ":\n" + report);
    }
  }

  @Test
  void aKnownGapExcusesTheEntityAndIsReal() {
    assertClean(EntityExposureRules.everyDomainEntityIsExposed(Set.of("November qits-1", "Oscar qits-2")), FIXTURES);
    assertClean(EntityExposureRules.knownGapsAreReal(Set.of("November qits-1", "Oscar qits-2")), FIXTURES);
  }

  @Test
  void staleAndMalformedGapsFail() {
    String report =
        report(
            EntityExposureRules.knownGapsAreReal(
                Set.of("Oscar qits-1945", "Alpha qits-1", "Nowhere qits-2", "Lima qits-3", "oscar")),
            FIXTURES);
    assertTrue(report.contains("known gap \"Alpha qits-1\" names an entity an operation now exposes"), report);
    assertTrue(report.contains("known gap \"Nowhere qits-2\" names no imported entity"), report);
    assertTrue(report.contains("known gap \"Lima qits-3\" names an entity marked @Technical or @Projection"), report);
    assertTrue(report.contains("known gap \"oscar\" is malformed"), report);
    assertFalse(report.contains("\"Oscar qits-1945\""), report);
  }

  @Test
  void wellFormedMarkersPassAndEachMistakeFails() {
    assertClean(EntityExposureRules.markersAreWellFormed, FIXTURES);
    String report = report(EntityExposureRules.markersAreWellFormed, MARKERS);
    String m = "eu.wohlben.qits.archrules.exposure.fixture.markers.MarkerFixtures$";
    assertTrue(report.contains(m + "BlankReason carries @Technical with a blank reason"), report);
    assertTrue(report.contains(m + "BadProjection carries @Projection(of = \"Repository\")"), report);
    assertTrue(report.contains(m + "BothMarkers carries both @Technical and @Projection"), report);
    assertTrue(report.contains(m + "TechnicalDto carries @Technical but is no @Entity"), report);
    assertTrue(report.contains(m + "ProjectionDto carries @Projection but is no @Entity"), report);
    assertTrue(report.contains(m + "ExposingEntity is an @Entity carrying @Exposes"), report);
    assertFalse(report.contains("FineTechnical"), report);
    assertFalse(report.contains("FineProjection"), report);
    assertFalse(report.contains("FineDto"), report);
  }

  @Test
  void aModuleWithNothingToJudgePassesAllRules() {
    assertClean(EntityExposureRules.all(Set.of()), ENTITYLESS);
  }

  @Test
  void allFoldsTheThreeRules() {
    String report = report(EntityExposureRules.all(Set.of("Nowhere qits-2")), FIXTURES);
    assertTrue(report.contains("November is a domain entity no operation exposes"), report);
    assertTrue(report.contains("\"Nowhere qits-2\" names no imported entity"), report);
  }

  // --- the map -----------------------------------------------------------------------------------

  @Test
  void theMapIsWrittenComparedAndRefusedWhenStale(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("docs/entities.json");

    AssertionError missing =
        assertThrows(
            AssertionError.class,
            () -> EntityExposure.verify(FIXTURES, "qits-fixture", GAPS, file, false));
    assertTrue(missing.getMessage().contains("does not exist"), missing.getMessage());
    assertTrue(missing.getMessage().contains("-Dgolden.update=true"), missing.getMessage());

    EntityExposure.verify(FIXTURES, "qits-fixture", GAPS, file, true);
    String written = Files.readString(file, StandardCharsets.UTF_8);
    assertTrue(written.startsWith("{\n  \"formatVersion\" : 1,\n  \"application\" : \"qits-fixture\","), written);
    assertTrue(written.endsWith("}\n"), written);
    assertFalse(written.contains("\r"), "LF only");
    assertTrue(written.contains("\"knownGap\" : \"qits-1945\""), written);
    assertTrue(written.contains("\"kind\" : \"via\""), written);

    EntityExposure.verify(FIXTURES, "qits-fixture", GAPS, file, false);

    Files.writeString(file, written.replace("\"listBravos\"", "\"listBravo\""), StandardCharsets.UTF_8);
    AssertionError stale =
        assertThrows(
            AssertionError.class,
            () -> EntityExposure.verify(FIXTURES, "qits-fixture", GAPS, file, false));
    assertTrue(stale.getMessage().contains("is stale"), stale.getMessage());
    assertTrue(stale.getMessage().contains("-Dgolden.update=true"), stale.getMessage());
    assertTrue(stale.getMessage().contains("- " ), stale.getMessage());
    assertTrue(stale.getMessage().contains("\"listBravo\""), stale.getMessage());
    assertTrue(stale.getMessage().contains("\"listBravos\""), stale.getMessage());
  }

  @Test
  void goldenUpdateTrueRewritesTheMapThroughThePublicDoor(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("entities.json");
    Files.writeString(file, "stale\n", StandardCharsets.UTF_8);
    String previous = System.getProperty("golden.update");
    System.setProperty("golden.update", "true");
    try {
      EntityExposure.verify(FIXTURES, "qits-fixture", GAPS, file);
    } finally {
      if (previous == null) {
        System.clearProperty("golden.update");
      } else {
        System.setProperty("golden.update", previous);
      }
    }
    assertEquals(
        EntityExposure.render(MAP, "qits-fixture", GAPS),
        Files.readString(file, StandardCharsets.UTF_8));
  }

  // --- helpers -----------------------------------------------------------------------------------

  private static EntityNode entity(String simple) {
    return MAP.entity(F + simple).orElseThrow(() -> new AssertionError("no entity " + simple));
  }

  private static DtoLink dto(String entity, String type, LinkKind kind) {
    return entity(entity).dtos().stream()
        .filter(link -> link.type().equals(F + type) && link.kind() == kind)
        .findFirst()
        .orElseThrow(() -> new AssertionError(entity + " has no " + kind + " " + type + ": " + entity(entity)));
  }

  private static void assertDto(String entity, String type, LinkKind kind, String site) {
    assertTrue(
        entity(entity).dtos().contains(new DtoLink(F + type, kind, null, site)),
        entity(entity).toString());
  }

  private static void assertOperation(
      String entity, String operationId, String method, String path, String dto, OperationKind kind) {
    assertTrue(
        entity(entity).operations().contains(new OperationLink(operationId, method, path, F + dto, kind)),
        entity(entity).toString());
  }

  private static String report(ArchRule rule, JavaClasses classes) {
    EvaluationResult result = rule.evaluate(classes);
    return String.join("\n", result.getFailureReport().getDetails());
  }

  private static void assertClean(ArchRule rule, JavaClasses classes) {
    EvaluationResult result = rule.evaluate(classes);
    assertFalse(result.hasViolation(), String.join("\n", result.getFailureReport().getDetails()));
  }
}
