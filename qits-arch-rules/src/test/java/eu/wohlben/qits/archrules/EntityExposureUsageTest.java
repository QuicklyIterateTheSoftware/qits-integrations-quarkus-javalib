package eu.wohlben.qits.archrules;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/**
 * The snippet from {@link EntityExposure}'s javadoc, run for real by ArchUnit's JUnit 5 engine
 * over the link fixtures: the proof that a service can paste it, that the rules and the {@code
 * @ArchTest} method share one import, and that the checked-in map below is what this module's
 * analysis renders. A change in the analysis shows up here as a diff of that file; {@code
 * -Dgolden.update=true} rewrites it.
 */
@AnalyzeClasses(
    packages = "eu.wohlben.qits.archrules.exposure.fixture.links",
    importOptions = ImportOption.OnlyIncludeTests.class)
class EntityExposureUsageTest {

  static final Set<String> KNOWN_GAPS =
      Set.of(
          "November qits-1945",
          "Oscar qits-1945",
          "Romeo qits-1945",
          "Sierra qits-1945",
          "Tango qits-1945");

  @ArchTest
  static final ArchRule EXPOSED = EntityExposureRules.everyDomainEntityIsExposed(KNOWN_GAPS);

  @ArchTest
  static final ArchRule GAPS = EntityExposureRules.knownGapsAreReal(KNOWN_GAPS);

  @ArchTest static final ArchRule MARKERS = EntityExposureRules.markersAreWellFormed;

  @ArchTest
  static void map(JavaClasses classes) throws IOException {
    EntityExposure.verify(
        classes,
        "qits-fixture",
        KNOWN_GAPS,
        Path.of("src/test/resources/exposure/link-fixtures-entities.json"));
  }
}
