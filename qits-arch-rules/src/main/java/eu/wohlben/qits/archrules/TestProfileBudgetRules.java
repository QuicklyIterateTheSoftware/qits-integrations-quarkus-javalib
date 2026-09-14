package eu.wohlben.qits.archrules;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The test-profile budget: <b>at most one {@code QuarkusTestProfile} per module</b>, unless the
 * duplicate is marked {@link NecessaryTestProfileDuplication}.
 *
 * <p>This rule exists because a suite of profiles does not fail slowly, it fails as a corpse.
 * qits-workspaces-service's CI gate died four times on 2026-09-14 with {@code Process Exit Code:
 * 137} — the kernel's OOM killer — inside qits-ci's hard {@code QITS_CI_MEMORY_LIMIT=4g} step
 * container. No failing test, no stack trace, nothing in the surefire report: the fork was killed
 * mid-sentence, and the build looked like an infrastructure flake for as long as anybody was willing
 * to rerun it.
 *
 * <p><b>The measured cause: each distinct {@code @TestProfile} is a Quarkus restart, and each
 * restart retains its application's classloader</b> — about 125 MB of metaspace that is never given
 * back for the life of the fork. Fourteen applications in one module's suite came to 1.71 GiB of
 * committed metaspace inside a 3.41 GiB fork. {@code -XX:MaxMetaspaceSize} does not reclaim any of
 * it; it only converts the SIGKILL into an {@code OutOfMemoryError: Metaspace}, which is a nicer
 * message for the same dead build. The only lever that moves the number is <em>fewer
 * applications</em>, and profiles are what mints them.
 *
 * <p><b>Why the rule counts profiles rather than comparing their configuration.</b> The obvious
 * shape — "no two profiles may declare equal config" — would have caught none of the fourteen.
 * Almost every one of them minted a <em>fresh temp directory</em> for a config key, which made its
 * config map unequal to every other <b>by construction</b> while buying nothing: the tests did not
 * care that the directory differed, only that there was one. Profiles that differ are the normal
 * case even when the duplication is pure accident, so difference cannot be the test. The count is,
 * because the count is what the metaspace is proportional to.
 *
 * <p>Enable it in a service with a test-scope dependency on this module and:
 *
 * <pre>{@code
 * @AnalyzeClasses(packages = "eu.wohlben.qits.myservice",
 *     importOptions = ImportOption.OnlyIncludeTests.class)
 * class TestProfileBudgetTest {
 *   @ArchTest static final ArchTests PROFILES = ArchTests.in(TestProfileBudgetRules.class);
 * }
 * }</pre>
 *
 * <p>Same shape as {@link CausationRowRules} — {@code ArchTests.in(…)} over a package — with one
 * difference that has to be its own test class: <b>profiles live in test sources</b>, so this rule
 * must be pointed at them, while the bytecode rules are analyzed with {@code DoNotIncludeTests}. One
 * {@code @AnalyzeClasses} cannot say both.
 *
 * <p><b>"Per module" is whatever the analyzed package holds.</b> ArchUnit has no notion of a Maven
 * module; what it counts is the import. Pointing the test at the service's own root package inside
 * the module that owns the test classes makes the two coincide, which is how every consumer should
 * spell it — and it is the honest boundary anyway, because the surefire fork, and therefore the
 * metaspace, is per module.
 *
 * <p><b>{@code QuarkusTestProfile} is named as a string, never imported</b> — the module's standing
 * trick, and here it earns its keep twice over. A compile dependency on {@code quarkus-junit5} would
 * drag the Quarkus test framework and its version onto the classpath of every consumer of these
 * rules, pinning the fleet's Quarkus version from an arch-rules release; and this repo must build
 * from a bare clone with no platform registry. ArchUnit reads bytecode, and bytecode carries names.
 * The price is the usual one: if Quarkus ever moves the interface, {@link #QUARKUS_TEST_PROFILE}
 * changes here and the fixture mirror in this module's test sources is where the drift surfaces.
 */
public final class TestProfileBudgetRules {

  /** The type Quarkus restarts an application for, matched by name — see the class javadoc. */
  public static final String QUARKUS_TEST_PROFILE = "io.quarkus.test.junit.QuarkusTestProfile";

  /** The written exception, by name for symmetry with the type above. */
  static final String MARKER = NecessaryTestProfileDuplication.class.getName();

  /** Retained metaspace per extra application, measured 2026-09-14. Quoted in the failure. */
  static final int RETAINED_METASPACE_MB = 125;

  /** qits-ci's hard per-step limit; the ceiling the retained metaspace is spent against. */
  static final String STEP_MEMORY_LIMIT = "4g";

  private TestProfileBudgetRules() {}

  /**
   * One unmarked profile per module, or none.
   *
   * <p>{@code allowEmptyShould(true)} for the same reason the causation rules carry it: a module
   * with no profile at all is the best state this rule describes, not a misconfigured rule. Most of
   * the fleet's libraries are in exactly that state.
   */
  @ArchTest
  public static final ArchRule atMostOneTestProfilePerModule =
      classes()
          .that(isAnUnmarkedTestProfile())
          .should(beTheModulesOnlyOne())
          .because(
              "every distinct profile is a separate Quarkus application whose classloader is"
                  + " retained for the life of the surefire fork; fourteen of them came to 1.71 GiB"
                  + " of committed metaspace in a 3.41 GiB fork and the OOM killer ended four"
                  + " consecutive CI gates at exit code 137 (qits-workspaces-service, 2026-09-14)")
          .allowEmptyShould(true);

  /**
   * A class Quarkus would boot an application for: it implements the profile interface, and it is
   * something that can actually be named in a {@code @TestProfile}.
   *
   * <p>Interfaces, abstract classes and anonymous classes are excluded deliberately. None of them
   * can be passed to {@code @TestProfile}, so none of them costs a restart — and a shared abstract
   * base that several profiles extend is the <em>repair</em> this rule asks for, not a violation of
   * it. Counting it would charge a module for factoring its profiles together.
   *
   * <p>The marker is matched through the imported class hierarchy, so inheriting it from a base
   * class works as long as that base is on the import path.
   */
  private static DescribedPredicate<JavaClass> isAnUnmarkedTestProfile() {
    return DescribedPredicate.describe(
        "are instantiable " + QUARKUS_TEST_PROFILE + " implementations not marked @" + MARKER,
        candidate ->
            candidate.isAssignableTo(QUARKUS_TEST_PROFILE)
                && !candidate.isInterface()
                && !candidate.getModifiers().contains(JavaModifier.ABSTRACT)
                && !candidate.isAnonymousClass()
                && !candidate.isAssignableTo(MARKER));
  }

  /**
   * The cardinality check. ArchUnit hands a condition the whole matched set in {@code init} before
   * checking any member of it, which is what lets a per-class condition answer a question about the
   * set — and lets <em>every</em> offender carry the full roster in its message, so one red build
   * shows the whole bill rather than one name at a time.
   */
  private static ArchCondition<JavaClass> beTheModulesOnlyOne() {
    return new ArchCondition<>("be the module's only unmarked " + QUARKUS_TEST_PROFILE) {

      private final List<String> unmarked = new ArrayList<>();

      @Override
      public void init(Collection<JavaClass> allProfiles) {
        unmarked.clear();
        allProfiles.stream().map(JavaClass::getName).sorted().forEach(unmarked::add);
      }

      @Override
      public void check(JavaClass profile, ConditionEvents events) {
        if (unmarked.size() > 1) {
          events.add(SimpleConditionEvent.violated(profile, violation(profile, unmarked)));
        }
      }
    };
  }

  /**
   * The failure a person reads. It names the offender, names every other unmarked profile in the
   * module, prices the duplication, and gives the two repairs — because a message that only says
   * "too many profiles" leaves the reader to rediscover the metaspace arithmetic that made it a rule.
   */
  static String violation(JavaClass profile, List<String> unmarked) {
    return profile.getName()
        + " is one of "
        + unmarked.size()
        + " unmarked "
        + QUARKUS_TEST_PROFILE
        + " implementations in this module ["
        + String.join(", ", unmarked)
        + "]. Each one is a separate Quarkus application: a restart whose classloader is retained"
        + " for the life of the surefire fork, about "
        + RETAINED_METASPACE_MB
        + " MB of metaspace that is never given back, inside a CI step container capped at "
        + STEP_MEMORY_LIMIT
        + " — past a dozen the OOM killer ends the build at exit code 137 with no failing test to"
        + " read. Merge it into the module's other profile, or implement "
        + MARKER
        + " and say in that class's javadoc why the two configurations cannot be one.";
  }
}
