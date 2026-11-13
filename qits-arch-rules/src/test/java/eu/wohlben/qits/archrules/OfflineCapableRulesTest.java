package eu.wohlben.qits.archrules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;

/**
 * The offline rules (qits-2148) against their own fixtures, the way {@link
 * DeskAdminParityRulesTest} does: programmatic {@code evaluate}, because half the assertions are
 * that a rule fails. {@code GoodResource} must pass every rule, {@code BadResource} fails each
 * rule on the methods built to break it, and {@code PlainResource}, which never opts in, is never
 * judged.
 */
class OfflineCapableRulesTest {

  private static final JavaClasses FIXTURES =
      new ClassFileImporter().importPackages("eu.wohlben.qits.archrules.fixtures.offline");

  private static final JavaClasses ENTITYLESS =
      new ClassFileImporter().importPackages("eu.wohlben.qits.archrules.fixtures.entityless");

  // --- updatesTakeIfMatch ----------------------------------------------------------------------

  @Test
  void anOfflineUpdateWithoutIfMatchFails() {
    String report = report(OfflineCapableRules.updatesTakeIfMatch());
    assertTrue(
        report.contains(
            "BadResource.update(String, ThingInput) is an offline PUT without an"
                + " @HeaderParam(\"If-Match\") parameter (qits-2148); take"
                + " @HeaderParam(\"If-Match\") String ifMatch and check it with"
                + " RecordVersions.require"),
        report);
    // Another header is not the version.
    assertTrue(
        report.contains("BadResource.delete(String) is an offline DELETE without an"), report);
  }

  @Test
  void updatesTakingIfMatchAndCreatesPass() {
    String report = report(OfflineCapableRules.updatesTakeIfMatch());
    // PUT, PATCH (header in lower case) and DELETE with it; a POST needs none.
    assertFalse(report.contains("GoodResource."), report);
    assertFalse(report.contains("BadResource.unmarked"), report);
    assertFalse(report.contains("PlainResource."), report);
  }

  // --- collectionReadsTakeSince ----------------------------------------------------------------

  @Test
  void anOfflineReadOfACollectionFailsThroughEveryWrapper() {
    String report = report(OfflineCapableRules.collectionReadsTakeSince());
    assertTrue(
        report.contains(
            "BadResource.list() is an offline GET answering a collection,"
                + " java.util.List<eu.wohlben.qits.archrules.fixtures.offline.OfflineFixtures$ThingDto>"
                + " (qits-2148); answer ChangesPage<T> and take @QueryParam(\"since\") String"
                + " since"),
        report);
    // Uni<RestResponse<ThingDto[]>> unwraps to an array; Multi is several.
    assertTrue(report.contains("BadResource.array() is an offline GET answering a collection"), report);
    assertTrue(report.contains("BadResource.stream() is an offline GET answering a collection"), report);
  }

  @Test
  void aChangesPageReadWithoutSinceFails() {
    String report = report(OfflineCapableRules.collectionReadsTakeSince());
    assertTrue(
        report.contains(
            "BadResource.changes(String) answers ChangesPage without an @QueryParam(\"since\")"
                + " parameter (qits-2148)"),
        report);
  }

  @Test
  void singleReadsAndChangesPagesWithSincePass() {
    String report = report(OfflineCapableRules.collectionReadsTakeSince());
    assertFalse(report.contains("GoodResource."), report);
    assertFalse(report.contains("BadResource.notOffline"), report);
    assertFalse(report.contains("PlainResource."), report);
  }

  // --- readsAnswerOfflineRecords ---------------------------------------------------------------

  @Test
  void anOfflineReadOfARecordWithoutAVersionFails() {
    String report = report(OfflineCapableRules.readsAnswerOfflineRecords());
    assertTrue(
        report.contains(
            "BadResource.plain() is an offline GET answering"
                + " eu.wohlben.qits.archrules.fixtures.offline.OfflineFixtures$PlainDto, which does"
                + " not implement eu.wohlben.qits.offline.OfflineRecord (qits-2148)"),
        report);
    assertTrue(
        report.contains(
            "BadResource.plainLater() is an offline GET answering"
                + " eu.wohlben.qits.archrules.fixtures.offline.OfflineFixtures$PlainDto"),
        report);
  }

  @Test
  void offlineRecordsChangesPagesOfThemAndRawResponsesPass() {
    String report = report(OfflineCapableRules.readsAnswerOfflineRecords());
    // get, changes, later, changesLater, and raw (a Response names no type: not checked).
    assertFalse(report.contains("GoodResource."), report);
    assertFalse(report.contains("BadResource.notOffline"), report);
    assertFalse(report.contains("PlainResource."), report);
    // A List<ThingDto> fails collectionReadsTakeSince, not this rule.
    assertFalse(report.contains("BadResource.list()"), report);
  }

  // --- operationsCarryOfflineMark --------------------------------------------------------------

  @Test
  void anOfflineOperationWithoutTheMarkFails() {
    String report = report(OfflineCapableRules.operationsCarryOfflineMark());
    assertTrue(
        report.contains(
            "BadResource.unmarked(ThingInput) is @OfflineCapable without @Extension(name ="
                + " \"x-qits-offline\", value = \"true\") (qits-2148)"),
        report);
    // Another extension is not the mark.
    assertTrue(report.contains("BadResource.otherMark() is @OfflineCapable without"), report);
  }

  @Test
  void theMarkDirectlyOrInsideExtensionsPasses() {
    String report = report(OfflineCapableRules.operationsCarryOfflineMark());
    assertFalse(report.contains("GoodResource."), report);
    assertFalse(report.contains("BadResource.update"), report);
    assertFalse(report.contains("PlainResource."), report);
  }

  // --- all ---------------------------------------------------------------------------------------

  @Test
  void allFoldsTheFourRules() {
    String report = report(OfflineCapableRules.all());
    assertTrue(report.contains("BadResource.update(String, ThingInput) is an offline PUT"), report);
    assertTrue(report.contains("BadResource.list() is an offline GET answering a collection"), report);
    assertTrue(report.contains("BadResource.plain() is an offline GET answering"), report);
    assertTrue(report.contains("BadResource.unmarked(ThingInput) is @OfflineCapable without"), report);
    assertFalse(report.contains("GoodResource."), report);
  }

  @Test
  void aModuleWithNoOfflineOperationPassesAllRules() {
    assertClean(OfflineCapableRules.all(), ENTITYLESS);
  }

  // --- helpers -----------------------------------------------------------------------------------

  private static String report(ArchRule rule) {
    EvaluationResult result = rule.evaluate(FIXTURES);
    return String.join("\n", result.getFailureReport().getDetails());
  }

  private static void assertClean(ArchRule rule, JavaClasses classes) {
    EvaluationResult result = rule.evaluate(classes);
    assertFalse(result.hasViolation(), String.join("\n", result.getFailureReport().getDetails()));
  }
}
