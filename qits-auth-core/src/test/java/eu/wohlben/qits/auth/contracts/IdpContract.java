package eu.wohlben.qits.auth.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import eu.wohlben.qits.pact.consumer.Trigger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.jose4j.jwk.JsonWebKey;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.jwk.RsaJsonWebKey;

/**
 * <b>What every service asks qits-idp-service for its bearer checks</b> (ticket qits-1149): the
 * JWKS. Each service's quarkus-oidc fetches {@code GET /idp/jwks} the first time it checks a
 * bearer, and again when a token names a key it does not know. It finds the key by {@code kid} and
 * reads {@code kty}, {@code n} and {@code e} to build it, and {@code alg} and {@code use} to select
 * it. The OIDC setup every service shares comes from this library, so the pact is this library's.
 */
final class IdpContract {

  /** The consumer, as the pact names it: this repository. */
  static final String CONSUMER = "qits-integrations-quarkus-javalib";

  /** qits-idp's golden masters, once published: {@code eu.wohlben.qits:qits-idp-golden-masters}. */
  static final GoldenMasters IDP = GoldenMasters.of("qits-idp-service", "qits-idp");

  /** The reason the row waits, as {@code @Disabled} needs it: a constant. */
  static final String WAITING = "needs provider state 'a published signing key' for getJwks in qits-idp-service";

  static final GoldenInteraction JWKS =
      GoldenInteraction.of(Trigger.of("library", "mechanism", "quarkus-oidc verifies a bearer"),
              "a published signing key", "getJwks")
          .consumes("keys[].kid", "keys[].kty", "keys[].n", "keys[].e", "keys[].alg", "keys[].use");

  /** The pact against {@code masters}: the real ones, or a fixture for the machinery test. */
  static ConsumerPact pact(GoldenMasters masters) {
    return ConsumerPact.of(CONSUMER, masters, JWKS);
  }

  static final ConsumerPact PACT = pact(IDP);

  /** Fetches the JWKS and finds the signing key the way quarkus-oidc does: by kid, through jose4j. */
  static final ConsumerPact.Call READ_THE_KEYS = (url, recorded) -> {
    HttpResponse<String> answer = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create(url + recorded.path())).header("Accept", "application/json").build(),
        HttpResponse.BodyHandlers.ofString());
    assertEquals(200, answer.statusCode());
    JsonWebKeySet keys = new JsonWebKeySet(answer.body());
    JsonWebKey key = keys.findJsonWebKey(recorded.params().get("kid"), RsaJsonWebKey.KEY_TYPE, "sig", "RS256");
    assertNotNull(key, () -> "the key named by the state's kid, an RSA signing key for RS256, in " + answer.body()
        + " parsed as " + keys.getJsonWebKeys());
    assertNotNull(((RsaJsonWebKey) key).getRsaPublicKey().getModulus());
  };

  private IdpContract() {}
}
