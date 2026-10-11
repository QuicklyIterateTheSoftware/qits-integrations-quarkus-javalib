package eu.wohlben.qits.archrules;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The completeness guard for traced service-to-service HTTP: every {@code java.net.http.HttpClient}
 * a service builds either goes through qits-http-core's {@code QitsHttpClients} or is declared raw,
 * in writing, so "forgot to wrap" fails a build instead of leaving one more parentless SERVER span
 * on the receiving side.
 *
 * <p>Quarkus' OpenTelemetry instruments the REST client and never {@code java.net.http}: a raw
 * client sends no {@code traceparent} and no {@code X-Qits-Caller}, which is not an error at
 * runtime — the trace just breaks at that hop, and nothing says so. This rule turns the gap into a
 * failed test and the deliberate exception (an external host, an OTLP exporter) into one
 * reviewable {@code @RawHttpClient(reason = ...)} line.
 *
 * <p>Enable it in a service with a test-scope dependency on this module and:
 *
 * <pre>{@code
 * @AnalyzeClasses(packages = "eu.wohlben.qits.myservice",
 *     importOptions = ImportOption.DoNotIncludeTests.class)
 * class ArchRulesTest {
 *   @ArchTest static final ArchTests HTTP_CLIENTS = ArchTests.in(HttpClientRules.class);
 * }
 * }</pre>
 *
 * <p><b>What it judges is the construction</b> — a call to {@code HttpClient.newBuilder()} or
 * {@code HttpClient.newHttpClient()} — and <b>what satisfies it is the top-level class</b>. A
 * client built in a lambda, an anonymous class or a nested holder is counted against the class
 * that encloses it, and that whole nest passes if any class in it touches {@code QitsHttpClients}
 * or the top-level class carries {@code @RawHttpClient}. Coarse on purpose: the rule asks "did this
 * class decide", not "is this exact expression wrapped", which bytecode cannot answer reliably and
 * a reviewer can. qits-http-core's own package is exempt — it is where the wrapping happens.
 *
 * <p><b>Every judged type is named as a string, never imported</b>, for {@link
 * CausationRowRules}' reasons: no dependency on qits-http-core, a bare clone builds, and the
 * fixture mirror in this module's test sources is where a rename surfaces first.
 */
public final class HttpClientRules {

  static final String HTTP_CLIENT = "java.net.http.HttpClient";
  static final String QITS_HTTP_CLIENTS = "eu.wohlben.qits.http.QitsHttpClients";
  static final String RAW_HTTP_CLIENT = "eu.wohlben.qits.http.RawHttpClient";
  static final String HTTP_PACKAGE = "eu.wohlben.qits.http";

  private static final Set<String> CONSTRUCTIONS = Set.of("newBuilder", "newHttpClient");

  private HttpClientRules() {}

  // allowEmptyShould(true) for CausationRowRules' reason: a service with no HttpClient at all is
  // the best state this rule describes, not a misconfigured rule.

  /** The decision rule: wrap the client, or declare it raw and say why. */
  @ArchTest
  public static final ArchRule rawHttpClientsAreWrappedOrDeclared =
      classes()
          .should(wrapOrDeclareTheirHttpClients())
          .because(
              "a raw java.net.http.HttpClient sends no traceparent and no X-Qits-Caller, and the"
                  + " receiver's span loses its parent with nothing reporting it")
          .allowEmptyShould(true);

  private static ArchCondition<JavaClass> wrapOrDeclareTheirHttpClients() {
    return new ArchCondition<>(
        "build java.net.http.HttpClient only through "
            + QITS_HTTP_CLIENTS
            + " or under @"
            + RAW_HTTP_CLIENT) {

      /** Top-level class name to every class of its nest that touches QitsHttpClients. */
      private final Map<String, Set<String>> wrappingNests = new HashMap<>();

      @Override
      public void init(Collection<JavaClass> allClasses) {
        for (JavaClass type : allClasses) {
          if (touchesQitsHttpClients(type)) {
            wrappingNests
                .computeIfAbsent(topLevel(type).getName(), k -> new HashSet<>())
                .add(type.getName());
          }
        }
      }

      @Override
      public void check(JavaClass type, ConditionEvents events) {
        if (isExempt(type)) {
          return;
        }
        List<JavaMethodCall> constructions =
            type.getMethodCallsFromSelf().stream().filter(HttpClientRules::constructs).toList();
        if (constructions.isEmpty()) {
          return;
        }
        JavaClass owner = topLevel(type);
        if (declaresRaw(type) || wrappingNests.containsKey(owner.getName())) {
          return;
        }
        for (JavaMethodCall call : constructions) {
          events.add(
              SimpleConditionEvent.violated(
                  type,
                  call.getDescription()
                      + ", but "
                      + owner.getName()
                      + " neither uses QitsHttpClients nor declares @RawHttpClient. Pass the"
                      + " client through QitsHttpClients.wrap(...) (inject"
                      + " eu.wohlben.qits.http.QitsHttpClients from qits-http-core), or, for a"
                      + " call that must stay untraced (an external host, an OTLP exporter),"
                      + " annotate "
                      + owner.getSimpleName()
                      + " with @RawHttpClient(reason = \"...\")"));
        }
      }
    };
  }

  private static boolean constructs(JavaMethodCall call) {
    return call.getTargetOwner().getName().equals(HTTP_CLIENT)
        && CONSTRUCTIONS.contains(call.getName())
        && call.getTarget().getRawParameterTypes().isEmpty();
  }

  private static boolean isExempt(JavaClass type) {
    String pkg = type.getPackageName();
    return pkg.equals(HTTP_PACKAGE) || pkg.startsWith(HTTP_PACKAGE + ".");
  }

  /** {@code @RawHttpClient} on the calling class or on any class enclosing it. */
  private static boolean declaresRaw(JavaClass type) {
    for (JavaClass current = type; current != null; ) {
      if (current.isAnnotatedWith(RAW_HTTP_CLIENT)) {
        return true;
      }
      current = current.getEnclosingClass().orElse(null);
    }
    return false;
  }

  private static boolean touchesQitsHttpClients(JavaClass type) {
    return type.getDirectDependenciesFromSelf().stream()
        .anyMatch(dependency -> dependency.getTargetClass().getName().equals(QITS_HTTP_CLIENTS))
        || type.getAccessesFromSelf().stream()
            .map(JavaAccess::getTargetOwner)
            .anyMatch(target -> target.getName().equals(QITS_HTTP_CLIENTS));
  }

  private static JavaClass topLevel(JavaClass type) {
    JavaClass current = type;
    while (current.getEnclosingClass().isPresent()) {
      current = current.getEnclosingClass().get();
    }
    return current;
  }
}
