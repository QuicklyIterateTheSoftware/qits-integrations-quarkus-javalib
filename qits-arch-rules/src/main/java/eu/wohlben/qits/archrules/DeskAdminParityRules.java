package eu.wohlben.qits.archrules;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Parity between {@code qits:admin-agent} and {@code qits:desk-admin} (qits-1621): the delivery
 * desk reaches every door the admin agent reaches, except the handful a service lists as refused
 * because the door is the desk's own right or a ref write.
 *
 * <p>A <b>route</b> is a method annotated with one of jakarta.ws.rs' {@code GET}, {@code POST},
 * {@code PUT}, {@code PATCH}, {@code DELETE}, {@code HEAD}, or one of WebSockets Next's {@code
 * OnOpen}, {@code OnTextMessage}, {@code OnBinaryMessage}. Its <b>effective roles</b> are its own
 * {@code @jakarta.annotation.security.RolesAllowed} if it carries one, otherwise its declaring
 * class's — a method-level {@code @RolesAllowed} fully replaces the class's, it does not add to
 * it, so a route that overrides down to {@code qits:admin-agent} alone is judged on that override,
 * whatever the class grants.
 *
 * <p>Two rules, each taking the service's own refusal list as {@code Set<String>} entries of the
 * shape {@code "SimpleClassName.methodName"}:
 *
 * <ul>
 *   <li>{@link #everyAdminAgentRouteAdmitsDeskAdmin(Set)} — every route whose effective roles
 *       admit {@code qits:admin-agent} and whose name is not in the refusal list also admits
 *       {@code qits:desk-admin}.
 *   <li>{@link #refusedRoutesExistAndRefuse(Set)} — every entry in the refusal list names a route
 *       that exists and whose effective roles do not admit {@code qits:desk-admin}. This is what
 *       catches a stale entry: a route renamed, removed, or one that has since been given the
 *       role directly and no longer needs refusing.
 * </ul>
 *
 * <p>Enable both in a service with a test-scope dependency on this module:
 *
 * <pre>{@code
 * @AnalyzeClasses(packages = "eu.wohlben.qits.myservice.api",
 *     importOptions = ImportOption.DoNotIncludeTests.class)
 * class DeskAdminParityTest {
 *   private static final Set<String> REFUSED = Set.of("ProjectController.create", ...);
 *
 *   @ArchTest
 *   static final ArchRule PARITY = DeskAdminParityRules.everyAdminAgentRouteAdmitsDeskAdmin(REFUSED);
 *
 *   @ArchTest
 *   static final ArchRule REFUSALS = DeskAdminParityRules.refusedRoutesExistAndRefuse(REFUSED);
 * }
 * }</pre>
 *
 * <p><b>Every judged type is named as a string, never imported</b>, for {@link CausationRowRules}'
 * reasons: this module carries neither a JAX-RS API nor WebSockets Next, so a bare clone builds,
 * and the two frameworks' versions stay uncoupled from this one's. The price is the same as
 * everywhere else here — a rename in either framework silently un-matches the rule — and the
 * fixture mirror in this module's test sources, compiled against the real annotations, is where
 * that would surface first.
 */
public final class DeskAdminParityRules {

  /** The role every route already admits, that {@code qits:desk-admin} is meant to match. */
  public static final String ADMIN_AGENT_ROLE = "qits:admin-agent";

  /** The role this guard is adding parity for. */
  public static final String DESK_ADMIN_ROLE = "qits:desk-admin";

  static final String ROLES_ALLOWED = "jakarta.annotation.security.RolesAllowed";

  private static final Set<String> ROUTE_METHOD_ANNOTATIONS =
      Set.of(
          "jakarta.ws.rs.GET",
          "jakarta.ws.rs.POST",
          "jakarta.ws.rs.PUT",
          "jakarta.ws.rs.PATCH",
          "jakarta.ws.rs.DELETE",
          "jakarta.ws.rs.HEAD",
          "io.quarkus.websockets.next.OnOpen",
          "io.quarkus.websockets.next.OnTextMessage",
          "io.quarkus.websockets.next.OnBinaryMessage");

  private DeskAdminParityRules() {}

  /**
   * Rule 1: a route that admits {@code qits:admin-agent} and is not refused must also admit
   * {@code qits:desk-admin}.
   *
   * @param refused {@code "SimpleClassName.methodName"} entries this service has decided to keep
   *     the desk-admin off, because the door is the desk's own right or a ref write (see the epic
   *     qits-1621 dossier page "Refusals").
   */
  public static ArchRule everyAdminAgentRouteAdmitsDeskAdmin(Set<String> refused) {
    Set<String> refusedCopy = Set.copyOf(refused);
    return classes()
        .should(admitDeskAdminWhereverItAdmitsAdminAgent(refusedCopy))
        .because(
            "the delivery desk (qits:desk-admin) reaches every door the admin agent"
                + " (qits:admin-agent) reaches, except the routes a service lists as refused"
                + " (qits-1621)")
        .allowEmptyShould(true);
  }

  /**
   * Rule 2: every entry in {@code refused} must name a route that exists and that does not itself
   * admit {@code qits:desk-admin} — catching an entry that names nothing (a rename, a removal) or
   * one left behind after the route started admitting the role directly.
   *
   * @param refused the same set passed to {@link #everyAdminAgentRouteAdmitsDeskAdmin(Set)}.
   */
  public static ArchRule refusedRoutesExistAndRefuse(Set<String> refused) {
    Set<String> refusedCopy = Set.copyOf(refused);
    return classes()
        .should(nameExistingRoutesThatDoNotAdmitDeskAdmin(refusedCopy))
        .because(
            "a refusal entry that no longer matches a route, or matches one that now admits"
                + " qits:desk-admin itself, is stale and should be removed (qits-1621)")
        .allowEmptyShould(true);
  }

  private static ArchCondition<JavaClass> admitDeskAdminWhereverItAdmitsAdminAgent(
      Set<String> refused) {
    return new ArchCondition<>(
        "admit qits:desk-admin on every route that admits qits:admin-agent, unless the route is"
            + " refused") {

      @Override
      public void check(JavaClass clazz, ConditionEvents events) {
        for (JavaMethod method : clazz.getMethods()) {
          if (!isRoute(method)) {
            continue;
          }
          Set<String> roles = effectiveRoles(method, clazz);
          if (!roles.contains(ADMIN_AGENT_ROLE)) {
            continue;
          }
          String route = routeName(clazz, method);
          if (refused.contains(route)) {
            continue;
          }
          if (roles.contains(DESK_ADMIN_ROLE)) {
            events.add(SimpleConditionEvent.satisfied(method, route + " admits qits:desk-admin"));
          } else {
            events.add(
                SimpleConditionEvent.violated(
                    method,
                    route
                        + " admits qits:admin-agent but not qits:desk-admin (qits-1621); add the"
                        + " role or list it as refused"));
          }
        }
      }
    };
  }

  private static ArchCondition<JavaClass> nameExistingRoutesThatDoNotAdmitDeskAdmin(
      Set<String> refused) {
    return new ArchCondition<JavaClass>(
        "name an existing route whose effective roles do not admit qits:desk-admin") {

      private final Map<String, Set<String>> rolesByRoute = new HashMap<>();

      @Override
      public void init(Collection<JavaClass> allClasses) {
        for (JavaClass clazz : allClasses) {
          for (JavaMethod method : clazz.getMethods()) {
            if (isRoute(method)) {
              rolesByRoute.put(routeName(clazz, method), effectiveRoles(method, clazz));
            }
          }
        }
      }

      @Override
      public void check(JavaClass clazz, ConditionEvents events) {
        // Every entry in `refused` is independent of which class is currently being visited, so
        // the whole comparison happens once, in finish(), against the index init() built.
      }

      @Override
      public void finish(ConditionEvents events) {
        for (String entry : refused) {
          Set<String> roles = rolesByRoute.get(entry);
          if (roles == null) {
            events.add(
                SimpleConditionEvent.violated(
                    entry,
                    "refused entry \""
                        + entry
                        + "\" does not name an existing route (qits-1621); fix the spelling or"
                        + " remove it"));
          } else if (roles.contains(DESK_ADMIN_ROLE)) {
            events.add(
                SimpleConditionEvent.violated(
                    entry,
                    "refused entry \""
                        + entry
                        + "\" names a route that already admits qits:desk-admin (qits-1621);"
                        + " remove it from the refusal list"));
          } else {
            events.add(
                SimpleConditionEvent.satisfied(
                    entry, "refused entry \"" + entry + "\" names a route that stays refused"));
          }
        }
      }
    };
  }

  private static boolean isRoute(JavaMethod method) {
    return ROUTE_METHOD_ANNOTATIONS.stream().anyMatch(method::isAnnotatedWith);
  }

  private static String routeName(JavaClass clazz, JavaMethod method) {
    return clazz.getSimpleName() + "." + method.getName();
  }

  /** The method's own {@code @RolesAllowed} if present, otherwise the declaring class's. */
  private static Set<String> effectiveRoles(JavaMethod method, JavaClass owner) {
    return method
        .tryGetAnnotationOfType(ROLES_ALLOWED)
        .map(DeskAdminParityRules::rolesFrom)
        .orElseGet(
            () ->
                owner
                    .tryGetAnnotationOfType(ROLES_ALLOWED)
                    .map(DeskAdminParityRules::rolesFrom)
                    .orElse(Set.of()));
  }

  private static Set<String> rolesFrom(JavaAnnotation<?> rolesAllowed) {
    String[] values = (String[]) rolesAllowed.get("value").orElse(new String[0]);
    return Set.copyOf(Arrays.asList(values));
  }
}
