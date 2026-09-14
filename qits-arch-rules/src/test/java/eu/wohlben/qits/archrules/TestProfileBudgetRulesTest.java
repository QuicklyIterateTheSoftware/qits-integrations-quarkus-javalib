package eu.wohlben.qits.archrules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;

/**
 * The budget rule against its own fixtures: a module within it passes without a word, a module over
 * it fails with the whole bill in every line.
 *
 * <p>Programmatic {@code evaluate} rather than the {@code @ArchTest} runner, for the same reason as
 * {@link CausationRowRulesTest} — half the assertions here are that the rule <em>fails</em>, which
 * the runner would report as a failing test. Consumers use the runner; the snippet is in
 * {@link TestProfileBudgetRules}'s javadoc.
 */
class TestProfileBudgetRulesTest {

  private static final JavaClasses LEAN =
      new ClassFileImporter().importPackages("eu.wohlben.qits.archrules.fixtures.leanprofiles");
  private static final JavaClasses BLOATED =
      new ClassFileImporter().importPackages("eu.wohlben.qits.archrules.fixtures.bloatedprofiles");
  private static final JavaClasses NO_PROFILES =
      new ClassFileImporter().importPackages("eu.wohlben.qits.archrules.fixtures.entityless");

  @Test
  void oneProfilePlusOneMarkedDuplicatePasses() {
    assertClean(TestProfileBudgetRules.atMostOneTestProfilePerModule, LEAN);
  }

  /**
   * A module with no profile at all is the best state this rule describes, not a misconfigured rule
   * — most of the fleet's libraries are in exactly that state, and ArchUnit's fail-on-empty default
   * would turn every one of them red.
   */
  @Test
  void aModuleWithNoProfileAtAllPasses() {
    assertClean(TestProfileBudgetRules.atMostOneTestProfilePerModule, NO_PROFILES);
  }

  @Test
  void twoUnmarkedProfilesFail() {
    EvaluationResult result =
        TestProfileBudgetRules.atMostOneTestProfilePerModule.evaluate(BLOATED);
    assertTrue(result.hasViolation(), "three unmarked profiles in one package must fail");
  }

  /** Every offender is named, so one red build shows the whole bill rather than one name at a time. */
  @Test
  void everyOffendingProfileIsNamed() {
    String report = report(BLOATED);

    assertTrue(report.contains("BloatedProfiles$ServiceProfile"), report);
    assertTrue(report.contains("BloatedProfiles$FreshTempDirProfile"), report);
    assertTrue(report.contains("BloatedProfiles$AnotherTempDirProfile"), report);
  }

  /** The marker is the written exception, so a profile carrying it is neither offender nor roster. */
  @Test
  void aMarkedProfileIsNeitherAnOffenderNorOnTheRoster() {
    assertFalse(report(BLOATED).contains("DeliberateProfile"), report(BLOATED));
  }

  /**
   * A shared abstract base is the repair this rule asks for. Quarkus never boots an application for
   * one, so counting it would charge a module for factoring its profiles together.
   */
  @Test
  void anAbstractBaseIsNotAProfile() {
    assertFalse(report(BLOATED).contains("ProfileBase"), report(BLOATED));
  }

  /** The message has to carry the arithmetic and both repairs, or it is just "too many profiles". */
  @Test
  void theMessageNamesThePriceAndBothRepairs() {
    String report = report(BLOATED);

    assertTrue(report.contains("3 unmarked"), report);
    assertTrue(report.contains(TestProfileBudgetRules.QUARKUS_TEST_PROFILE), report);
    assertTrue(report.contains("separate Quarkus application"), report);
    assertTrue(report.contains(TestProfileBudgetRules.RETAINED_METASPACE_MB + " MB"), report);
    assertTrue(report.contains("capped at " + TestProfileBudgetRules.STEP_MEMORY_LIMIT), report);
    assertTrue(report.contains("exit code 137"), report);
    assertTrue(report.contains("Merge it into the module's other profile"), report);
    assertTrue(report.contains(NecessaryTestProfileDuplication.class.getName()), report);
  }

  private static String report(JavaClasses classes) {
    EvaluationResult result =
        TestProfileBudgetRules.atMostOneTestProfilePerModule.evaluate(classes);
    return String.join("\n", result.getFailureReport().getDetails());
  }

  private static void assertClean(ArchRule rule, JavaClasses classes) {
    EvaluationResult result = rule.evaluate(classes);
    assertFalse(
        result.hasViolation(),
        () -> "expected no violations:\n" + String.join("\n", result.getFailureReport().getDetails()));
  }
}
