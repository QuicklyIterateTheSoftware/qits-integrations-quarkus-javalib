package eu.wohlben.qits.archrules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;

/**
 * The rule against its own fixtures: the decided shapes pass, the undecided ones are named with
 * the repair, and qits-http-core's own package is forgiven. Programmatic {@code evaluate}, as in
 * {@link CausationRowRulesTest}, because half the assertions are that the rule fails.
 */
class HttpClientRulesTest {

  private static final JavaClasses FIXTURES =
      new ClassFileImporter()
          .importPackages(
              "eu.wohlben.qits.archrules.fixtures.httpclients", HttpClientRules.HTTP_PACKAGE);

  private static final String REPORT = report(FIXTURES);

  @Test
  void aClassThatBuildsARawClientAndDecidedNothingViolates() {
    assertTrue(REPORT.contains("fixtures.httpclients.Forgotten neither"), REPORT);
  }

  @Test
  void lambdasAndNestedClassesCountAgainstTheTopLevelClass() {
    assertTrue(REPORT.contains("ForgottenInALambda neither"), REPORT);
    assertTrue(REPORT.contains("ForgottenInALambda$Holder"), REPORT);
  }

  @Test
  void theViolationNamesTheRepair() {
    assertTrue(REPORT.contains("QitsHttpClients.wrap(...)"), REPORT);
    assertTrue(REPORT.contains("@RawHttpClient(reason"), REPORT);
  }

  @Test
  void wrappingAnywhereInTheNestSatisfiesIt() {
    assertFalse(REPORT.contains("Wrapped"), REPORT);
  }

  /** Doubles as the proof that the CLASS-retention annotation is read from bytecode. */
  @Test
  void aDeclaredRawClientPassesNestedClassesIncluded() {
    assertFalse(REPORT.contains("DeclaredRaw"), REPORT);
  }

  @Test
  void aClassThatBuildsNothingAndTheWrappingPackageArePassed() {
    assertFalse(REPORT.contains("NoClientAtAll"), REPORT);
    assertFalse(REPORT.contains("Method <eu.wohlben.qits.http."), REPORT);
  }

  @Test
  void aPackageWithNoClientsPasses() {
    JavaClasses clean =
        new ClassFileImporter().importPackages("eu.wohlben.qits.archrules.fixtures.good");
    assertFalse(HttpClientRules.rawHttpClientsAreWrappedOrDeclared.evaluate(clean).hasViolation());
  }

  private static String report(JavaClasses classes) {
    EvaluationResult result = HttpClientRules.rawHttpClientsAreWrappedOrDeclared.evaluate(classes);
    return String.join("\n", result.getFailureReport().getDetails());
  }
}
