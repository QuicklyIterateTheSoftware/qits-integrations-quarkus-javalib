package eu.wohlben.qits.archrules;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.archrules.fixtures.providerstates.ProviderStateFixtures.CoveringVerifier;
import eu.wohlben.qits.archrules.fixtures.providerstates.ProviderStateFixtures.ExtraStateVerifier;
import eu.wohlben.qits.archrules.fixtures.providerstates.ProviderStateFixtures.GatedVerifier;
import eu.wohlben.qits.archrules.fixtures.providerstates.ProviderStateFixtures.MultiNameVerifier;
import eu.wohlben.qits.archrules.fixtures.providerstates.ProviderStateFixtures.UngatedVerifier;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The rule against indexes a test writes out in the shape a provider records them — the extra
 * fields a real state carries ({@code slug}, {@code params}, {@code operations}) included, so the
 * parse is proved to ignore them.
 */
class ProviderStateCoverageRulesTest {

  private static final String ROTATED = "a rotated runner";
  private static final String EXISTS = "a runner exists";
  private static final String LEGACY = "a runner from before the recording";

  @TempDir Path dir;

  private Path index(String... states) throws IOException {
    String entries =
        Arrays.stream(states)
            .map(
                name ->
                    "{\"name\":\""
                        + name
                        + "\",\"slug\":\"x\",\"params\":{},\"dependsOn\":[],\"operations\":[]}")
            .collect(Collectors.joining(","));
    Path index = dir.resolve(ProviderStateCoverageRules.GOLDEN_INDEX);
    Files.createDirectories(index.getParent());
    Files.writeString(
        index,
        "{\"formatVersion\":1,\"provider\":\"qits-fixture\",\"states\":[" + entries + "]}");
    return index;
  }

  @Test
  void aVerifierCoveringEveryRecordedStatePasses() throws IOException {
    Path index = index(ROTATED, EXISTS);
    assertDoesNotThrow(
        () -> ProviderStateCoverageRules.assertCovered(index, Set.of(), CoveringVerifier.class));
  }

  @Test
  void aRecordedStateWithoutItsCallbackFailsAndIsNamed() throws IOException {
    Path index = index(ROTATED, EXISTS, "a deleted runner");

    AssertionError caught =
        assertThrows(
            AssertionError.class,
            () ->
                ProviderStateCoverageRules.assertCovered(index, Set.of(), CoveringVerifier.class));

    String message = caught.getMessage();
    assertTrue(message.contains("missing: 'a deleted runner'"), message);
    assertTrue(message.contains(CoveringVerifier.class.getName()), message);
    assertTrue(message.contains("Provider state change callback failed"), message);
    // Only the missing one: the covered states are not noise in the report.
    assertFalse(message.contains("'" + ROTATED + "'"), message);
  }

  @Test
  void everyProblemIsReportedAtOnce() throws IOException {
    Path index = index(ROTATED, EXISTS, "a deleted runner", "a paused runner");

    AssertionError caught =
        assertThrows(
            AssertionError.class,
            () ->
                ProviderStateCoverageRules.assertCovered(
                    index, Set.of(), ExtraStateVerifier.class));

    String message = caught.getMessage();
    assertTrue(message.contains("missing: 'a deleted runner'"), message);
    assertTrue(message.contains("missing: 'a paused runner'"), message);
    assertTrue(message.contains("unrecorded: '" + LEGACY + "'"), message);
  }

  @Test
  void aCallbackForAnUnrecordedStateFailsUnlessItIsAnAlias() throws IOException {
    Path index = index(ROTATED, EXISTS);

    AssertionError caught =
        assertThrows(
            AssertionError.class,
            () ->
                ProviderStateCoverageRules.assertCovered(
                    index, Set.of(), ExtraStateVerifier.class));
    assertTrue(caught.getMessage().contains("unrecorded: '" + LEGACY + "'"), caught.getMessage());
    assertTrue(
        caught.getMessage().contains("ExtraStateVerifier.legacyRunner"), caught.getMessage());

    assertDoesNotThrow(
        () ->
            ProviderStateCoverageRules.assertCovered(
                index, Set.of(LEGACY), ExtraStateVerifier.class));
  }

  @Test
  void oneCallbackListingSeveralStatesCoversEachOfThem() throws IOException {
    Path index = index(ROTATED, EXISTS);
    assertDoesNotThrow(
        () -> ProviderStateCoverageRules.assertCovered(index, Set.of(), MultiNameVerifier.class));
  }

  @Test
  void theVerifiersAreAUnion() throws IOException {
    Path index = index(ROTATED, EXISTS);

    assertDoesNotThrow(
        () ->
            ProviderStateCoverageRules.assertCovered(
                index, Set.of(), UngatedVerifier.class, GatedVerifier.class));

    // Either half alone is short of a state, which is what makes the union the thing tested.
    AssertionError caught =
        assertThrows(
            AssertionError.class,
            () -> ProviderStateCoverageRules.assertCovered(index, Set.of(), UngatedVerifier.class));
    assertTrue(caught.getMessage().contains("missing: '" + EXISTS + "'"), caught.getMessage());
  }

  @Test
  void anAliasThatIsRecordedNowIsStale() throws IOException {
    Path index = index(ROTATED, EXISTS, LEGACY);

    AssertionError caught =
        assertThrows(
            AssertionError.class,
            () ->
                ProviderStateCoverageRules.assertCovered(
                    index, Set.of(LEGACY), ExtraStateVerifier.class));
    assertTrue(caught.getMessage().contains("stale alias: '" + LEGACY + "'"), caught.getMessage());
    assertTrue(caught.getMessage().contains("recorded in the index now"), caught.getMessage());
  }

  @Test
  void anAliasNoCallbackListsIsStale() throws IOException {
    Path index = index(ROTATED, EXISTS);

    AssertionError caught =
        assertThrows(
            AssertionError.class,
            () ->
                ProviderStateCoverageRules.assertCovered(
                    index, Set.of(LEGACY), CoveringVerifier.class));
    assertTrue(caught.getMessage().contains("stale alias: '" + LEGACY + "'"), caught.getMessage());
    assertTrue(caught.getMessage().contains("listed by no @State"), caught.getMessage());
  }

  @Test
  void theIndexIsFoundByWalkingUp() throws IOException {
    Path index = index(ROTATED);
    Path module = Files.createDirectories(dir.resolve("service").resolve("submodule"));

    assertEquals(index, ProviderStateCoverageRules.findGoldenIndex(module));
  }

  @Test
  void noIndexAnywhereUpFailsClearly() throws IOException {
    Path empty = Files.createDirectories(dir.resolve("nothing-here"));

    AssertionError caught =
        assertThrows(AssertionError.class, () -> ProviderStateCoverageRules.findGoldenIndex(empty));
    assertTrue(caught.getMessage().contains("No golden-masters/index.json"), caught.getMessage());
  }
}
