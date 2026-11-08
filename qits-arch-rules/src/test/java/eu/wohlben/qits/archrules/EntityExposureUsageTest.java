package eu.wohlben.qits.archrules;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.LocationProvider;
import com.tngtech.archunit.lang.ArchRule;
import eu.wohlben.qits.archrules.exposure.fixture.links.LinkFixtures;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.jar.JarFile;

/**
 * The snippet from {@link EntityExposure}'s javadoc, run for real by ArchUnit's JUnit 5 engine
 * over the link fixtures: the proof that a service can paste it, that the rules and the {@code
 * @ArchTest} method share one import, and that the checked-in map below is what this module's
 * analysis renders. A change in the analysis shows up here as a diff of that file; {@code
 * -Dgolden.update=true} rewrites it.
 *
 * <p>Imports from {@link FixtureJarLocation}'s plain jar, not the fixtures' own package: {@link
 * EntityExposure#isTestOutput} now skips anything imported from a {@code test-classes} directory
 * (qits-1945), which is exactly where this module's own compiled fixtures sit. The fixtures stand
 * in for a service's production classes, so a plain jar — never one named {@code -tests.jar} — is
 * what lets this test still see them.
 */
@AnalyzeClasses(locations = FixtureJarLocation.class)
class EntityExposureUsageTest {

  static final Set<String> KNOWN_GAPS =
      Set.of(
          "November qits-1945",
          "Oscar qits-1945",
          "Romeo qits-1945",
          "Sierra qits-1945",
          "Tango qits-1945",
          "Victor qits-1945");

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

/** The {@code fixture.links} package, repacked as a plain jar — see the class javadoc above. */
final class FixtureJarLocation implements LocationProvider {
  @Override
  public Set<Location> get(Class<?> testClass) {
    Path jar =
        FixtureImport.jarOf(
            "eu.wohlben.qits.archrules.exposure.fixture.links", LinkFixtures.class);
    try (JarFile jarFile = new JarFile(jar.toFile())) {
      return Set.of(Location.of(jarFile));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
