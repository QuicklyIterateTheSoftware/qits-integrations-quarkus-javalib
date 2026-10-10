package eu.wohlben.qits.pact.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reading a provider's index: lookups, the legacy query, the guard, several jars on one classpath. */
class GoldenMastersTest {

  static final GoldenMasters FIXTURE = GoldenMasters.at("fixtures/golden-masters/", "qits-fixture-service", "qits-fixture");

  @Test
  void anOperationIsReadFromTheIndex() {
    var op = FIXTURE.operation("a thing", "getThing");
    assertEquals("GET", op.method());
    assertEquals("/fixture/api/things/{thingId}", op.path());
    assertEquals("/fixture/api/things/00000000-0000-4000-8000-000000000001", op.examplePath());
    assertEquals("/fixture/api/things/${thingId}", op.expressionPath());
    assertEquals(200, op.status());
    assertNull(op.body());
    assertEquals(Map.of(), op.query());
    assertTrue(op.ids().contains("$.id"));
    assertEquals("first", FIXTURE.json("a thing", "getThing").path("name").asText());
  }

  @Test
  void theQueryIsItsOwnObject() {
    var op = FIXTURE.operation("a thing", "listThings");
    assertEquals("/fixture/api/things", op.path());
    assertEquals(Map.of("limit", List.of("20")), op.query());
  }

  @Test
  void aLegacyRecordingHasItsQueryMovedOutOfThePath() {
    var op = FIXTURE.operation("a thing", "searchThings");
    assertEquals("/fixture/api/things/search", op.path());
    assertEquals(List.of("a", "b"), op.query().get("tag"));
    assertEquals(List.of("{thingId}"), op.query().get("owner"));
  }

  @Test
  void bodiesAndResponseHeadersAreRead() {
    var form = FIXTURE.operation("a token request", "token");
    assertEquals("application/x-www-form-urlencoded", form.contentType());
    assertTrue(form.body().isTextual());
    assertTrue(FIXTURE.operation("a thing", "createThing").body().isObject());
    assertEquals("e447df8a0cabaeafedbf4c0235e013bb29697cd9",
        FIXTURE.operation("a thing", "getThingFile").responseHeader("git-commit-sha"));
  }

  @Test
  void anUnrecordedStateFailsNamingWhatItNeeds() {
    assertFalse(FIXTURE.recorded("no such state", "getThing"));
    ProviderStateMissing missing =
        assertThrows(ProviderStateMissing.class, () -> FIXTURE.operation("no such state", "getThing"));
    assertTrue(missing.getMessage().startsWith(
        "needs provider state 'no such state' for getThing in qits-fixture-service"), missing.getMessage());
  }

  @Test
  void aRecordedStateWithoutTheOperationFailsTheSameWay() {
    assertFalse(FIXTURE.recorded("a thing", "renameThing"));
    ProviderStateMissing missing =
        assertThrows(ProviderStateMissing.class, () -> FIXTURE.operation("a thing", "renameThing"));
    assertTrue(missing.getMessage().startsWith(
        "needs provider state 'a thing' for renameThing in qits-fixture-service"), missing.getMessage());
  }

  @Test
  void aProviderWithNoJarOnTheClasspathFailsTheSameWay() {
    GoldenMasters absent = GoldenMasters.of("qits-absent-service", "qits-absent");
    assertFalse(absent.recorded("a state", "getIt"));
    ProviderStateMissing missing = assertThrows(ProviderStateMissing.class, () -> absent.operation("a state", "getIt"));
    assertTrue(missing.getMessage().startsWith("needs provider state 'a state' for getIt in qits-absent-service"),
        missing.getMessage());
  }

  /** Two jars, both at golden-masters/: each provider reads its own index and its own recordings. */
  @Test
  void severalProvidersOnOneClasspathDoNotShadowEachOther(@TempDir Path dir) throws Exception {
    Path one = provider(dir.resolve("one"), "qits-one", "from one");
    Path two = provider(dir.resolve("two"), "qits-two", "from two");
    ClassLoader previous = Thread.currentThread().getContextClassLoader();
    try (URLClassLoader loader = new URLClassLoader(new URL[] {one.toUri().toURL(), two.toUri().toURL()}, null)) {
      Thread.currentThread().setContextClassLoader(loader);
      assertEquals("from one", GoldenMasters.of("qits-one-service", "qits-one").json("s", "getIt").path("v").asText());
      assertEquals("from two", GoldenMasters.of("qits-two-service", "qits-two").json("s", "getIt").path("v").asText());
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
  }

  private static Path provider(Path root, String application, String value) throws Exception {
    Path tree = Files.createDirectories(root.resolve("golden-masters/s"));
    Files.writeString(root.resolve("golden-masters/index.json"), """
        {"formatVersion": 1, "provider": "%s", "states": [{"name": "s", "params": {}, "operations": [
          {"operationId": "getIt", "method": "GET", "path": "/it", "status": 200, "file": "s/getIt.json",
           "frozen": {"ids": [], "instants": [], "strings": [], "listFilteredTo": null}}]}]}
        """.formatted(application));
    Files.writeString(tree.resolve("getIt.json"), "{\"v\": \"" + value + "\"}");
    return root;
  }
}
