package eu.wohlben.qits.auth;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

/**
 * The deployed posture (dev-user fallback blanked): the gateway-injected header is the identity, and
 * its absence is anonymous rather than denied.
 *
 * <p>That last point is the one worth stating. This jar carries no authorization policy for user
 * traffic, by design (migration-auth-plan.md §12). So every assertion below is about <em>who the
 * request is</em>, never about a status code. A test that expected a 401 would be asserting a
 * security control the forward-auth pair does not have and must not grow.
 *
 * <p>This is the moved copy of the suite the eight services each carried. It asserts the same four
 * things, so "moved verbatim in behaviour" is a claim the build checks.
 */
@QuarkusTest
@TestProfile(NoDevUserProfile.class)
class ForwardAuthTest {

  @Test
  void theGatewayInjectedHeaderEstablishesTheIdentity() {
    given()
        .header("X-Qits-User", "alice")
        .when()
        .get("/test-identity")
        .then()
        .statusCode(200)
        .body("anonymous", equalTo(false))
        .body("principal", equalTo("alice"));
  }

  @Test
  void noHeaderIsAnonymousAndStillServed() {
    // Anonymous is "no name for the audit row", not a security state — the request proceeds.
    given().when().get("/test-identity").then().statusCode(200).body("anonymous", equalTo(true));
  }

  @Test
  void aBlankHeaderIsAnonymousNotAnEmptyPrincipal() {
    given()
        .header("X-Qits-User", "  ")
        .when()
        .get("/test-identity")
        .then()
        .statusCode(200)
        .body("anonymous", equalTo(true));
  }

  @Test
  void theIdentityCarriesTheRolesAssertedByTheEdge() {
    given()
        .header("X-Qits-User", "alice")
        .header("X-Qits-Roles", "qits:admin, qits:admin, qits:admin,,")
        .when()
        .get("/test-identity")
        .then()
        .statusCode(200)
        .body("principal", equalTo("alice"))
        .body("roles", containsInAnyOrder("qits:admin"));
  }

  @Test
  void rolesAllowedAcceptsTheForwardedRole() {
    given()
        .header("X-Qits-User", "alice")
        .header("X-Qits-Roles", "qits:admin")
        .when()
        .get("/admin-echo")
        .then()
        .statusCode(200)
        .body(equalTo("ok"));
  }

  @Test
  void rolesAllowedRejectsAUserWithoutTheForwardedRole() {
    given()
        .header("X-Qits-User", "alice")
        .header("X-Qits-Roles", "qits:reader")
        .when()
        .get("/admin-echo")
        .then()
        .statusCode(403);
  }

  @Test
  void rolesAllowedChallengesAnAnonymousRequest() {
    given().when().get("/admin-echo").then().statusCode(401);
  }
}
