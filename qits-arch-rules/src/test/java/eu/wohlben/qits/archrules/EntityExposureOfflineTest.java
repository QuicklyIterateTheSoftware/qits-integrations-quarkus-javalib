package eu.wohlben.qits.archrules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import eu.wohlben.qits.archrules.ExposureMap.Cardinality;
import eu.wohlben.qits.archrules.ExposureMap.EntityNode;
import eu.wohlben.qits.archrules.ExposureMap.OperationKind;
import eu.wohlben.qits.archrules.ExposureMap.OperationLink;
import eu.wohlben.qits.archrules.exposure.fixture.offline.OfflineMapFixtures;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

/**
 * The entity map's offline key and {@code ChangesPage} cardinality (qits-2148), over {@code
 * fixture.offline} repacked as a plain jar the way {@link EntityExposureTest} imports its fixtures
 * (see {@link FixtureImport}). That {@code fixture.links}' checked-in map is unchanged is {@link
 * EntityExposureUsageTest}'s job, untouched by this change.
 */
class EntityExposureOfflineTest {

  private static final String PACKAGE = "eu.wohlben.qits.archrules.exposure.fixture.offline";
  private static final String F = PACKAGE + ".OfflineMapFixtures$";

  private static final JavaClasses FIXTURES;

  static {
    try (JarFile jar =
        new JarFile(FixtureImport.jarOf(PACKAGE, OfflineMapFixtures.class).toFile())) {
      FIXTURES = new ClassFileImporter().importJar(jar);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static final ExposureMap MAP = EntityExposure.analyze(FIXTURES);

  @Test
  void anOperationOfAnOfflineClassIsAnOfflineRowAndItsChangesPageIsACollection() {
    assertEquals(
        new OperationLink(
            "RobinResource.changes",
            "GET",
            "/robins",
            F + "RobinDto",
            OperationKind.RETURNS,
            Cardinality.COLLECTION,
            true),
        row("RobinResource.changes"));
  }

  @Test
  void aChangesPageInsideUniAndRestResponseIsACollectionToo() {
    OperationLink row = row("RobinResource.changesLater");
    assertEquals(Cardinality.COLLECTION, row.cardinality());
    assertEquals(OperationKind.RETURNS, row.kind());
    assertTrue(row.offline());
  }

  @Test
  void onlyTheMethodCarryingOfflineCapableIsOfflineInAPlainClass() {
    assertEquals(
        new OperationLink(
            "RobinOneResource.update",
            "PUT",
            "/robins/one",
            F + "RobinDto",
            OperationKind.RETURNS,
            Cardinality.SINGLE,
            true),
        row("RobinOneResource.update"));
    OperationLink plain = row("RobinOneResource.get");
    assertFalse(plain.offline(), plain.toString());
    // The six-argument constructor is a row that is not offline.
    assertEquals(
        new OperationLink(
            "RobinOneResource.get",
            "GET",
            "/robins/one",
            F + "RobinDto",
            OperationKind.RETURNS,
            Cardinality.SINGLE),
        plain);
  }

  @Test
  void theMapWritesOfflineTrueOnOfflineRowsOnlyAndNoKeyOtherwise() {
    String rendered = EntityExposure.render(MAP, "qits-fixture", Set.of());
    assertTrue(rendered.startsWith("{\n  \"formatVersion\" : 2,"), rendered);
    // Three offline rows, each with the key right after its cardinality; the fourth has none.
    assertEquals(4, rendered.split("\"operationId\" :", -1).length - 1, rendered);
    assertEquals(3, rendered.split("\"offline\" : true", -1).length - 1, rendered);
    assertFalse(rendered.contains("\"offline\" : false"), rendered);
    assertTrue(
        rendered.contains(
            "\"operationId\" : \"RobinResource.changes\",\n"
                + "          \"method\" : \"GET\",\n"
                + "          \"path\" : \"/robins\",\n"
                + "          \"dto\" : \"" + F + "RobinDto\",\n"
                + "          \"kind\" : \"returns\",\n"
                + "          \"cardinality\" : \"collection\",\n"
                + "          \"offline\" : true\n"
                + "        }"),
        rendered);
    assertTrue(
        rendered.contains(
            "\"operationId\" : \"RobinOneResource.get\",\n"
                + "          \"method\" : \"GET\",\n"
                + "          \"path\" : \"/robins/one\",\n"
                + "          \"dto\" : \"" + F + "RobinDto\",\n"
                + "          \"kind\" : \"returns\",\n"
                + "          \"cardinality\" : \"single\"\n"
                + "        }"),
        rendered);
  }

  // --- helpers -----------------------------------------------------------------------------------

  private static OperationLink row(String operationId) {
    EntityNode robin =
        MAP.entity(F + "Robin").orElseThrow(() -> new AssertionError("no entity Robin: " + MAP));
    List<OperationLink> rows =
        robin.operations().stream().filter(op -> op.operationId().equals(operationId)).toList();
    assertEquals(1, rows.size(), robin.toString());
    return rows.get(0);
  }
}
