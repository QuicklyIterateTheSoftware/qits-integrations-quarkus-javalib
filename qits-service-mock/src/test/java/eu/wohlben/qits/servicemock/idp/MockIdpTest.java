package eu.wohlben.qits.servicemock.idp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.servicemock.MockService;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.consumer.InvalidJwtException;
import org.jose4j.jwt.consumer.JwtConsumer;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.jose4j.keys.resolvers.JwksVerificationKeyResolver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The interop proof: the JWKS the mock serves and the tokens it mints are accepted by jose4j —
 * the verifier quarkus-oidc itself is built on — resolved the way a consumer resolves them (key
 * picked from the served set by {@code kid}).
 */
class MockIdpTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String AUDIENCE = "qits-platform";

  private static MockIdp idp;

  @BeforeAll
  static void start() {
    idp = MockIdp.start();
  }

  @AfterAll
  static void stop() {
    idp.close();
  }

  @Test
  void jwksDocumentMirrorsTheRealIdpShape() throws Exception {
    JsonNode jwks = MAPPER.readTree(get("/idp/jwks").body());
    JsonNode key = jwks.get("keys").get(0);

    assertEquals("RSA", key.get("kty").asText());
    assertEquals("sig", key.get("use").asText());
    assertEquals("RS256", key.get("alg").asText());
    assertEquals(idp.kid(), key.get("kid").asText());
    // the modulus round-trips: no leading zero byte survived into the base64url
    byte[] n = Base64.getUrlDecoder().decode(key.get("n").asText());
    assertTrue(n[0] != 0, "leading zero byte in modulus");
    assertEquals(idp.publicKey().getModulus(), new BigInteger(1, n));
    assertEquals(idp.publicKey().getPublicExponent(),
        new BigInteger(1, Base64.getUrlDecoder().decode(key.get("e").asText())));
  }

  @Test
  void mintedTokenVerifiesThroughJose4jAgainstTheServedJwks() throws Exception {
    String token =
        idp.token().subject("qits-ci").audience(AUDIENCE).groups("qits:system").mint();

    JwtClaims claims = consumer().processToClaims(token);
    assertEquals("qits-ci", claims.getSubject());
    assertEquals(List.of(AUDIENCE), claims.getAudience());
    assertEquals(List.of("qits:system"), claims.getStringListClaimValue("groups"));
    assertNotNull(claims.getJwtId());
  }

  @Test
  void strangerAndWrongKidAndExpiredTokensAreRejected() {
    JwtConsumer consumer = consumer();
    assertThrows(
        InvalidJwtException.class,
        () -> consumer.processToClaims(
            idp.token().audience(AUDIENCE).signedByUnknownKey().mint()),
        "token signed by an unpublished key must not verify");
    assertThrows(
        InvalidJwtException.class,
        () -> consumer.processToClaims(idp.token().audience(AUDIENCE).kid("no-such-kid").mint()),
        "token with an unknown kid must not resolve a key");
    assertThrows(
        InvalidJwtException.class,
        () -> consumer.processToClaims(
            idp.token().audience(AUDIENCE).ttl(Duration.ofMinutes(-5)).mint()),
        "expired token must not verify");
  }

  @Test
  void everyRequestIsRecordedIncludingUnknownPaths() throws Exception {
    idp.reset();
    assertEquals(200, get("/idp/jwks").statusCode());
    assertEquals(404, get("/idp/no-such-path").statusCode());
    assertEquals(200, get("/idp/.well-known/openid-configuration").statusCode());

    List<String> paths =
        idp.recordedRequests().stream().map(MockService.RecordedRequest::path).toList();
    assertEquals(
        List.of("/idp/jwks", "/idp/no-such-path", "/idp/.well-known/openid-configuration"), paths);
    assertTrue(
        idp.recordedRequests().stream().allMatch(r -> "GET".equals(r.method())), "methods recorded");
    // the delegating handle needs no code of its own for the answered status to come through
    assertEquals(
        List.of(200, 404, 200),
        idp.recordedRequests().stream().map(MockService.RecordedRequest::status).toList());

    idp.reset();
    assertEquals(List.of(), idp.recordedRequests(), "reset clears recordings");
  }

  @Test
  void attachedHandleSharesServerAndKeypair() throws Exception {
    MockIdp owned = MockIdp.ensureStarted();
    MockIdp attached = MockIdp.attach();

    assertEquals(owned.baseUrl(), attached.baseUrl());
    assertEquals(owned.kid(), attached.kid());
    assertEquals(owned.publicKey().getModulus(), attached.publicKey().getModulus());

    // a token minted by the ATTACHED handle verifies against the JWKS the OWNED server serves
    String jwksJson =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(owned.baseUrl() + "/jwks")).GET().build(),
                HttpResponse.BodyHandlers.ofString())
            .body();
    JwtConsumer consumer =
        new JwtConsumerBuilder()
            .setVerificationKeyResolver(
                new JwksVerificationKeyResolver(new JsonWebKeySet(jwksJson).getJsonWebKeys()))
            .setExpectedIssuer(attached.issuer())
            .setExpectedAudience(AUDIENCE)
            .setRequireExpirationTime()
            .build();
    consumer.processToClaims(attached.token().audience(AUDIENCE).groups("qits:system").mint());
  }

  private static JwtConsumer consumer() {
    try {
      String jwksJson = get("/idp/jwks").body();
      return new JwtConsumerBuilder()
          .setVerificationKeyResolver(
              new JwksVerificationKeyResolver(new JsonWebKeySet(jwksJson).getJsonWebKeys()))
          .setExpectedIssuer(idp.issuer())
          .setExpectedAudience(AUDIENCE)
          .setRequireExpirationTime()
          .build();
    } catch (Exception e) {
      throw new IllegalStateException("failed to build the jose4j consumer", e);
    }
  }

  private static HttpResponse<String> get(String path) throws Exception {
    try (HttpClient client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port() + path)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }

  private static int port() {
    return Integer.parseInt(idp.baseUrl().replaceAll(".*:(\\d+)/idp$", "$1"));
  }
}
