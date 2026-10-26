package eu.wohlben.qits.archrules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The rule against its own fixtures, the way {@link HttpClientRulesTest} does: programmatic
 * {@code evaluate}, because half the assertions are that a rule fails.
 */
class DeskAdminParityRulesTest {

  private static final JavaClasses FIXTURES =
      new ClassFileImporter().importPackages("eu.wohlben.qits.archrules.fixtures.deskadminparity");

  /**
   * {@code RefusedRoute.ownRightsOnly} is a genuine refusal; {@code AlreadyAdmitsDeskAdmin.wideOpen}
   * is stale because the route now admits the role itself; {@code Nowhere.goneDoor} is stale
   * because no such route exists at all.
   */
  private static final Set<String> REFUSED =
      Set.of(
          "RefusedRoute.ownRightsOnly",
          "AlreadyAdmitsDeskAdmin.wideOpen",
          "Nowhere.goneDoor");

  private static final String PARITY_REPORT = report(DeskAdminParityRules.everyAdminAgentRouteAdmitsDeskAdmin(REFUSED));
  private static final String REFUSALS_REPORT = report(DeskAdminParityRules.refusedRoutesExistAndRefuse(REFUSED));

  @Test
  void aRouteThatAdmitsBothRolesPasses() {
    assertFalse(PARITY_REPORT.contains("Passing."), PARITY_REPORT);
  }

  @Test
  void aRouteThatAdmitsOnlyAdminAgentAndIsNotRefusedFails() {
    assertTrue(
        PARITY_REPORT.contains(
            "Failing.leaksAdminOnly admits qits:admin-agent but not qits:desk-admin (qits-1621);"
                + " add the role or list it as refused"),
        PARITY_REPORT);
  }

  @Test
  void aRefusedRouteIsSkippedByTheParityRule() {
    assertFalse(PARITY_REPORT.contains("RefusedRoute."), PARITY_REPORT);
  }

  @Test
  void classLevelRolesAreTheFallbackAndAreFullyOverriddenByMethodLevelOnes() {
    assertFalse(PARITY_REPORT.contains("ClassLevelRoles.fallsBackToTheClass"), PARITY_REPORT);
    assertTrue(
        PARITY_REPORT.contains("ClassLevelRoles.overridesTheClass admits qits:admin-agent but not"),
        PARITY_REPORT);
  }

  @Test
  void webSocketClassLevelRolesAreAlsoAFallbackAndAnOverride() {
    assertFalse(PARITY_REPORT.contains("WebSocketClassLevelRoles.onOpen"), PARITY_REPORT);
    assertTrue(
        PARITY_REPORT.contains(
            "WebSocketClassLevelRoles.onMessage admits qits:admin-agent but not"),
        PARITY_REPORT);
  }

  @Test
  void aGenuineRefusalIsAcceptedByTheRefusalRule() {
    assertFalse(REFUSALS_REPORT.contains("\"RefusedRoute.ownRightsOnly\""), REFUSALS_REPORT);
  }

  @Test
  void aRefusalNamingNoRouteAtAllIsStale() {
    assertTrue(
        REFUSALS_REPORT.contains(
            "refused entry \"Nowhere.goneDoor\" does not name an existing route (qits-1621)"),
        REFUSALS_REPORT);
  }

  @Test
  void aRefusalNamingARouteThatNowAdmitsDeskAdminIsStale() {
    assertTrue(
        REFUSALS_REPORT.contains(
            "refused entry \"AlreadyAdmitsDeskAdmin.wideOpen\" names a route that already admits"
                + " qits:desk-admin (qits-1621)"),
        REFUSALS_REPORT);
  }

  private static String report(ArchRule rule) {
    EvaluationResult result = rule.evaluate(FIXTURES);
    return String.join("\n", result.getFailureReport().getDetails());
  }
}
