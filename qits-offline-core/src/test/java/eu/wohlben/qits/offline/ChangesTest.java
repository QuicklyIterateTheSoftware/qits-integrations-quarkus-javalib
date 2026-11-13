package eu.wohlben.qits.offline;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.offline.testdb.OfflinePgResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Changed-since reads through the fixture's {@code GET /things}, which is {@link Changes#page}
 * over a table with {@code change_xid} and its trigger, exactly as an adopting service has it.
 */
@QuarkusTest
@QuarkusTestResource(OfflinePgResource.class)
@EnabledIf(
    value = "eu.wohlben.qits.offline.testdb.EmbeddedPg#available",
    disabledReason = "zonky's initdb refuses to run as root, which is how the CI step container runs")
class ChangesTest {

  @Inject DataSource dataSource;

  @BeforeEach
  void reset() throws Exception {
    OfflineDb.reset(dataSource);
  }

  @Test
  void aReadWithoutSinceAnswersEverything() {
    create("a", "b", "c");

    JsonPath page = read(null, 0);

    assertEquals(Set.of("a", "b", "c"), Set.copyOf(page.getList("items.id", String.class)));
    assertEquals(List.of(), page.getList("deleted"));
    assertFalse(page.getBoolean("hasMore"));
    assertNotNull(page.getString("nextCursor"));
  }

  @Test
  void aReadSinceACursorAnswersOnlyWhatChangedAfterIt() {
    create("a", "b");
    String cursor = read(null, 0).getString("nextCursor");

    given()
        .header("If-Match", "\"1\"")
        .contentType("application/json")
        .body("{\"name\":\"renamed\"}")
        .put("/things/a")
        .then()
        .statusCode(200);
    create("c");

    JsonPath changed = read(cursor, 0);
    assertEquals(Set.of("a", "c"), Set.copyOf(changed.getList("items.id", String.class)));
    Map<String, Object> a = changed.getMap("items.find { it.id == 'a' }");
    assertEquals("renamed", a.get("name"));
    assertEquals(2, a.get("version"));
    assertFalse(changed.getBoolean("hasMore"));

    JsonPath quiet = read(changed.getString("nextCursor"), 0);
    assertEquals(List.of(), quiet.getList("items"));
    assertEquals(List.of(), quiet.getList("deleted"));
  }

  @Test
  void aFullPageSaysHasMoreAndTheCursorPagesOn() {
    create("a", "b", "c", "d", "e");

    List<String> seen = new ArrayList<>();
    List<Boolean> hasMore = new ArrayList<>();
    String cursor = null;
    do {
      JsonPath page = read(cursor, 2);
      seen.addAll(page.getList("items.id", String.class));
      hasMore.add(page.getBoolean("hasMore"));
      cursor = page.getString("nextCursor");
    } while (hasMore.get(hasMore.size() - 1));

    assertEquals(List.of(true, true, false), hasMore);
    assertEquals(List.of("a", "b", "c", "d", "e"), seen, "ordered by (change_xid, id), no repeats");
    assertEquals(List.of(), read(cursor, 2).getList("items"), "the last cursor is caught up");
  }

  @Test
  void aDeletionArrivesAsATombstoneAndARecreationClearsIt() {
    create("a", "b");
    String cursor = read(null, 0).getString("nextCursor");

    given().header("If-Match", "\"1\"").delete("/things/a").then().statusCode(204);

    JsonPath deleted = read(cursor, 0);
    assertEquals(List.of(), deleted.getList("items"));
    assertEquals(List.of(Map.of("id", "a", "version", 1)), deleted.getList("deleted"));

    create("a");
    JsonPath recreated = read(deleted.getString("nextCursor"), 0);
    assertEquals(List.of("a"), recreated.getList("items.id", String.class));
    assertEquals(List.of(), recreated.getList("deleted"));
  }

  @Test
  void aCursorOlderThanTombstoneRetentionAnswers410() {
    long old = Instant.now().minus(Duration.ofDays(31)).getEpochSecond();
    String expired = new ChangeCursor(1, null, null, old).encode();

    given()
        .queryParam("since", expired)
        .get("/things")
        .then()
        .statusCode(410)
        .contentType("application/problem+json")
        .body("type", equalTo(Problems.CURSOR_EXPIRED));
  }

  @Test
  void aGarbageCursorAnswers400() {
    given()
        .queryParam("since", "not-a-cursor")
        .get("/things")
        .then()
        .statusCode(400)
        .contentType("application/problem+json")
        .body("type", equalTo(Problems.CURSOR_INVALID));
  }

  @Test
  void aRecordedCursorWithinRetentionIsAccepted() {
    long recent = Instant.now().minus(Duration.ofDays(29)).getEpochSecond();
    create("a");
    JsonPath page = read(new ChangeCursor(1, null, null, recent).encode(), 0);
    assertTrue(page.getList("items.id", String.class).contains("a"));
  }

  private static void create(String... ids) {
    for (String id : ids) {
      given()
          .contentType("application/json")
          .body("{\"id\":\"" + id + "\",\"name\":\"" + id + "\"}")
          .post("/things")
          .then()
          .statusCode(201);
    }
  }

  private static JsonPath read(String since, int limit) {
    var request = given().queryParam("limit", limit);
    if (since != null) {
      request.queryParam("since", since);
    }
    return request.get("/things").then().statusCode(200).extract().jsonPath();
  }
}
