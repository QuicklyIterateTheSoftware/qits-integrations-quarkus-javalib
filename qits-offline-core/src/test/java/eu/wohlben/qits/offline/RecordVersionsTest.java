package eu.wohlben.qits.offline;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import eu.wohlben.qits.offline.testdb.OfflinePgResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Versions on the wire: a single offline read carries its {@code ETag}, a write naming an older
 * version is refused 412 with the record as it is now, and a write naming none is refused 428.
 */
@QuarkusTest
@QuarkusTestResource(OfflinePgResource.class)
@EnabledIf(
    value = "eu.wohlben.qits.offline.testdb.EmbeddedPg#available",
    disabledReason = "zonky's initdb refuses to run as root, which is how the CI step container runs")
class RecordVersionsTest {

  @Inject DataSource dataSource;

  @BeforeEach
  void aThingAtVersionTwo() throws Exception {
    OfflineDb.reset(dataSource);
    IdempotencyTest.create("seed", "a", "first").then().statusCode(201);
    put("a", "\"1\"", "second").then().statusCode(200).body("version", equalTo(2));
  }

  @Test
  void aSingleOfflineReadCarriesItsVersionAsAStrongETag() {
    given().get("/things/a").then().statusCode(200).header("ETag", "\"2\"");
  }

  @Test
  void aResourceThatDidNotOptInGetsNoETag() {
    given().get("/plain").then().statusCode(200).header("ETag", nullValue());
  }

  @Test
  void aStaleIfMatchAnswers412WithTheCurrentRecord() {
    put("a", "\"1\"", "lost")
        .then()
        .statusCode(412)
        .contentType("application/problem+json")
        .header("ETag", "\"2\"")
        .body("type", equalTo(Problems.STALE_VERSION))
        .body("status", equalTo(412))
        .body("currentVersion", equalTo(2))
        .body("current.name", equalTo("second"))
        .body("current.version", equalTo(2));
  }

  @Test
  void aMissingIfMatchAnswers428() {
    given()
        .contentType("application/json")
        .body("{\"name\":\"x\"}")
        .put("/things/a")
        .then()
        .statusCode(428)
        .contentType("application/problem+json")
        .body("type", equalTo(Problems.VERSION_REQUIRED));
  }

  @Test
  void aWildcardIfMatchAnswers428() {
    put("a", "*", "x").then().statusCode(428).body("type", equalTo(Problems.VERSION_REQUIRED));
  }

  @Test
  void aMatchingIfMatchDeletes() {
    given().header("If-Match", "\"2\"").delete("/things/a").then().statusCode(204);
    given().get("/things/a").then().statusCode(404);
  }

  @Test
  void aStaleDeleteIsRefusedToo() {
    given()
        .header("If-Match", "1")
        .delete("/things/a")
        .then()
        .statusCode(412)
        .body("type", equalTo(Problems.STALE_VERSION));
  }

  @Test
  void anOptimisticLockFailureAnswers412WithoutCurrent() {
    given()
        .contentType("application/json")
        .put("/things/race/a")
        .then()
        .statusCode(412)
        .contentType("application/problem+json")
        .body("type", equalTo(Problems.STALE_VERSION))
        .body("current", nullValue());
  }

  private static io.restassured.response.Response put(String id, String ifMatch, String name) {
    return given()
        .header("If-Match", ifMatch)
        .contentType("application/json")
        .body("{\"name\":\"" + name + "\"}")
        .put("/things/" + id);
  }
}
