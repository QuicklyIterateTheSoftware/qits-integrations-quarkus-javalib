package eu.wohlben.qits.auth.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * The JWKS pact against qits-idp-service, {@code pacts/qits-integrations-quarkus-javalib_qits-idp-service.json}.
 *
 * <p>qits-idp does not publish its golden masters yet, so the row waits: its test is disabled with
 * the reason, and the pact file test is skipped while no row is recorded. The day {@code
 * eu.wohlben.qits:qits-idp-golden-masters} is a test dependency here and records the state: delete
 * {@code @Disabled}, run once with {@code -Dgolden.update=true}, and add the pact to {@code
 * contracts:} in {@code .config/qits/release.yml}.
 */
class IdpPactTest {

  @Test
  @Disabled(IdpContract.WAITING)
  void quarkusOidcFindsThePublishedSigningKey() {
    IdpContract.PACT.run(IdpContract.JWKS, IdpContract.READ_THE_KEYS);
  }

  @Test
  void theCommittedPactIsWhatTheRowWrites() {
    IdpContract.PACT.compareOrWritePactFile();
  }

  @Test
  void theDisabledReasonIsTheOneTheGuardGives() {
    assertEquals(IdpContract.WAITING, IdpContract.PACT.needs(IdpContract.JWKS));
  }

  // --- the row against a fixture recording, so it is proven to build and run before qits-idp publishes

  static final ConsumerPact FIXTURE = IdpContract.pact(
      GoldenMasters.at("fixtures/qits-idp/golden-masters/", "qits-idp-service", "qits-idp"));

  @Test
  void theRowRunsAgainstAFixtureRecording() {
    FIXTURE.run(IdpContract.JWKS, IdpContract.READ_THE_KEYS);
  }

  @Test
  void theRowBindsOnlyTheKeyFieldsQuarkusOidcReads() {
    JsonNode interaction = FIXTURE.normalisedPact().path("interactions").get(0);
    assertEquals("GET", interaction.path("request").path("method").asText());
    assertEquals("/idp/jwks", interaction.path("request").path("path").asText());
    JsonNode key = interaction.path("response").path("body").path("content").path("keys").get(0);
    assertEquals(List.of("alg", "e", "kid", "kty", "n", "use"),
        key.properties().stream().map(Map.Entry::getKey).sorted().toList());
    JsonNode keys = interaction.path("response").path("matchingRules").path("body").path("$.keys");
    assertEquals(1, keys.path("matchers").get(0).path("min").asInt(), "at least the one key: keys rotate");
    FIXTURE.assertEveryInteractionCarriesBothReferences();
  }
}
