package eu.wohlben.qits.archrules;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import eu.wohlben.qits.archrules.ExposureMap.EntityNode;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The entity-exposure rules (qits-1945): every domain entity reaches an operation through a DTO,
 * every known gap is real, and every qits-db-core marker is well formed. What "reaches" means —
 * the reads, the builds, the link kinds — is {@link EntityExposure}'s javadoc; these rules only
 * judge the {@link ExposureMap} it produces, and the JSON map a service checks in is rendered from
 * the same analysis, so the build's reason and the reviewer's evidence never disagree.
 *
 * <p>Three rules, two of them taking the service's known gaps — {@code "SimpleName qits-NNNN"}
 * entries naming a domain entity nothing exposes yet and the ticket that will close it:
 *
 * <ul>
 *   <li>{@link #everyDomainEntityIsExposed(Set)} — every {@code @Entity} without {@code
 *       @Technical} or {@code @Projection} reaches an operation, or is a known gap.
 *   <li>{@link #knownGapsAreReal(Set)} — every entry is well formed and names an imported entity
 *       that is unmarked and unexposed. A gap closed, an entity renamed or marked, fails as stale.
 *   <li>{@link #markersAreWellFormed} — {@code @Technical(reason)} not blank, {@code
 *       @Projection(of)} shaped {@code "<application>:<EntitySimpleName>"}, never both, both only
 *       on entities, and {@code @Exposes} never on one.
 * </ul>
 *
 * <p>Enable them, and the map, in a service with a test-scope dependency on this module:
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
 * <p>{@link #all(Set)} folds the three into one {@link CompositeArchRule} for a service that wants
 * a single line; separate fields report each rule on its own test, which reads better in a red
 * build. Every rule is {@code allowEmptyShould(true)}, for {@link CausationRowRules}' reason: a
 * module with no entities, no gaps or no markers is the best state the rules describe.
 *
 * <p>The rules evaluate over <em>every</em> imported class ({@code classes().should(...)}) rather
 * than {@code that().areAnnotatedWith(Entity)}, because whether an entity is exposed depends on
 * the DTOs and resources around it, and a condition sees only the classes its rule selected.
 */
public final class EntityExposureRules {

  /** The shape of a known-gap entry: an entity's simple name, a space, a ticket. */
  public static final String KNOWN_GAP_PATTERN = "^[A-Z][A-Za-z0-9]* [a-z][a-z0-9-]*-[0-9]+$";

  /** The shape of {@code @Projection(of)}: {@code "<application>:<EntitySimpleName>"}. */
  public static final String PROJECTION_OF_PATTERN = "^[a-z0-9-]+:[A-Z][A-Za-z0-9]*$";

  private static final Pattern KNOWN_GAP = Pattern.compile(KNOWN_GAP_PATTERN);
  private static final Pattern PROJECTION_OF = Pattern.compile(PROJECTION_OF_PATTERN);

  private EntityExposureRules() {}

  /**
   * Every domain entity — an {@code @Entity} without {@code @Technical} or {@code @Projection} —
   * reaches at least one operation through a DTO, or its simple name is a known gap.
   *
   * @param knownGaps {@code "SimpleName qits-NNNN"} entries; a malformed one still names its first
   *     word here, and {@link #knownGapsAreReal(Set)} is what fails it
   */
  public static ArchRule everyDomainEntityIsExposed(Set<String> knownGaps) {
    Set<String> gapNames = gapNames(knownGaps);
    return classes()
        .should(beExposedMarkedOrAKnownGap(gapNames))
        .because(
            "a domain entity no operation hands out is either dead or a missing door, and the map"
                + " cannot tell which (qits-1945)")
        .allowEmptyShould(true);
  }

  /**
   * Every known-gap entry is shaped {@code "SimpleName qits-NNNN"} and names an imported entity
   * that is neither marked nor exposed — a gap someone closed, an entity renamed, removed or
   * marked since, fails as stale.
   *
   * @param knownGaps the same set passed to {@link #everyDomainEntityIsExposed(Set)}
   */
  public static ArchRule knownGapsAreReal(Set<String> knownGaps) {
    Set<String> entries = new TreeSet<>(knownGaps);
    return classes()
        .should(nameUnexposedDomainEntities(entries))
        .because(
            "a known gap that no longer names an unexposed domain entity hides nothing and should"
                + " be removed (qits-1945)")
        .allowEmptyShould(true);
  }

  /** The markers say what they mean, on the classes they are meant for. */
  public static final ArchRule markersAreWellFormed =
      classes()
          .should(carryWellFormedMarkers())
          .because(
              "@Technical and @Projection are opt-outs a reviewer reads; one without its reason,"
                  + " on the wrong class or contradicting the other says nothing (qits-1945)")
          .allowEmptyShould(true);

  /** The three rules as one, for a service that wants a single {@code @ArchTest} line. */
  public static ArchRule all(Set<String> knownGaps) {
    return CompositeArchRule.of(everyDomainEntityIsExposed(knownGaps))
        .and(knownGapsAreReal(knownGaps))
        .and(markersAreWellFormed);
  }

  // ---------------------------------------------------------------------------------------------

  private static Set<String> gapNames(Set<String> knownGaps) {
    Set<String> names = new TreeSet<>();
    for (String entry : knownGaps) {
      String trimmed = entry.strip();
      if (!trimmed.isEmpty()) {
        names.add(trimmed.split("\\s+", 2)[0]);
      }
    }
    return names;
  }

  /** The analysis over everything the rule imported. */
  private abstract static class OverTheMap extends ArchCondition<JavaClass> {

    ExposureMap map = new ExposureMap(List.of());

    OverTheMap(String description) {
      super(description);
    }

    @Override
    public void init(Collection<JavaClass> allObjectsToTest) {
      map = EntityExposure.analyzeCached(allObjectsToTest);
    }
  }

  private static ArchCondition<JavaClass> beExposedMarkedOrAKnownGap(Set<String> gapNames) {
    return new OverTheMap(
        "reach an operation through a DTO where they are domain entities, unless marked"
            + " @Technical/@Projection or listed as a known gap") {

      @Override
      public void check(JavaClass clazz, ConditionEvents events) {
        Optional<EntityNode> found = map.entity(clazz.getName());
        if (found.isEmpty()) {
          return;
        }
        EntityNode entity = found.get();
        if (entity.marked()) {
          events.add(SimpleConditionEvent.satisfied(clazz, entity.entity() + " is marked"));
        } else if (entity.exposed()) {
          events.add(SimpleConditionEvent.satisfied(clazz, entity.entity() + " is exposed"));
        } else if (gapNames.contains(entity.simpleName())) {
          events.add(SimpleConditionEvent.satisfied(clazz, entity.entity() + " is a known gap"));
        } else {
          events.add(
              SimpleConditionEvent.violated(
                  clazz,
                  entity.entity()
                      + " is a domain entity no operation exposes (qits-1945): expose it through a"
                      + " DTO an operation returns, mark it @Technical(reason = \"…\") or"
                      + " @Projection(of = \"<application>:"
                      + entity.simpleName()
                      + "\"), or list it as a known gap \""
                      + entity.simpleName()
                      + " qits-NNNN\" with the ticket that will close it"));
        }
      }
    };
  }

  private static ArchCondition<JavaClass> nameUnexposedDomainEntities(Set<String> entries) {
    return new OverTheMap("be named by known gaps only where unmarked and unexposed") {

      @Override
      public void check(JavaClass clazz, ConditionEvents events) {
        // Every entry is independent of the class being visited; finish() compares them all
        // against the map init() built, as DeskAdminParityRules.refusedRoutesExistAndRefuse does.
      }

      @Override
      public void finish(ConditionEvents events) {
        for (String entry : entries) {
          if (!KNOWN_GAP.matcher(entry).matches()) {
            events.add(
                SimpleConditionEvent.violated(
                    entry,
                    "known gap \""
                        + entry
                        + "\" is malformed (qits-1945); write it \"EntitySimpleName qits-NNNN\""));
            continue;
          }
          String name = entry.split(" ", 2)[0];
          List<EntityNode> named = map.entitiesNamed(name);
          if (named.isEmpty()) {
            events.add(
                SimpleConditionEvent.violated(
                    entry,
                    "known gap \""
                        + entry
                        + "\" names no imported entity (qits-1945); fix the spelling or remove"
                        + " it"));
          } else if (named.stream().allMatch(EntityNode::marked)) {
            events.add(
                SimpleConditionEvent.violated(
                    entry,
                    "known gap \""
                        + entry
                        + "\" names an entity marked @Technical or @Projection (qits-1945);"
                        + " remove it"));
          } else if (named.stream().filter(node -> !node.marked()).allMatch(EntityNode::exposed)) {
            events.add(
                SimpleConditionEvent.violated(
                    entry,
                    "known gap \""
                        + entry
                        + "\" names an entity an operation now exposes (qits-1945); remove it"));
          } else {
            events.add(
                SimpleConditionEvent.satisfied(
                    entry, "known gap \"" + entry + "\" names an unexposed domain entity"));
          }
        }
      }
    };
  }

  private static ArchCondition<JavaClass> carryWellFormedMarkers() {
    return new ArchCondition<>("carry well-formed entity-exposure markers") {

      @Override
      public void check(JavaClass clazz, ConditionEvents events) {
        boolean entity = clazz.isAnnotatedWith(EntityExposure.ENTITY);
        boolean technical = clazz.isAnnotatedWith(EntityExposure.TECHNICAL);
        boolean projection = clazz.isAnnotatedWith(EntityExposure.PROJECTION);
        String name = clazz.getName();
        if (technical) {
          String reason = value(clazz, EntityExposure.TECHNICAL, "reason");
          if (reason == null || reason.isBlank()) {
            events.add(
                SimpleConditionEvent.violated(
                    clazz,
                    name + " carries @Technical with a blank reason (qits-1945); say what the rows"
                        + " are for"));
          }
          if (!entity) {
            events.add(
                SimpleConditionEvent.violated(
                    clazz,
                    name + " carries @Technical but is no @Entity (qits-1945); the marker is for"
                        + " entities only"));
          }
        }
        if (projection) {
          String of = value(clazz, EntityExposure.PROJECTION, "of");
          if (of == null || !PROJECTION_OF.matcher(of).matches()) {
            events.add(
                SimpleConditionEvent.violated(
                    clazz,
                    name + " carries @Projection(of = \"" + of + "\"), which is not"
                        + " \"<application>:<EntitySimpleName>\" (qits-1945)"));
          }
          if (!entity) {
            events.add(
                SimpleConditionEvent.violated(
                    clazz,
                    name + " carries @Projection but is no @Entity (qits-1945); the marker is for"
                        + " entities only"));
          }
        }
        if (technical && projection) {
          events.add(
              SimpleConditionEvent.violated(
                  clazz,
                  name + " carries both @Technical and @Projection (qits-1945); keep the one that"
                      + " is true"));
        }
        if (entity && clazz.isAnnotatedWith(EntityExposure.EXPOSES)) {
          events.add(
              SimpleConditionEvent.violated(
                  clazz,
                  name + " is an @Entity carrying @Exposes (qits-1945); @Exposes goes on the DTO"
                      + " that carries the entity"));
        }
      }
    };
  }

  private static String value(JavaClass clazz, String annotation, String property) {
    return clazz
        .tryGetAnnotationOfType(annotation)
        .flatMap(found -> found.get(property))
        .map(String.class::cast)
        .orElse(null);
  }
}
