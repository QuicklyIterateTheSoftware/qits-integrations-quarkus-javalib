package eu.wohlben.qits.offline;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.offline.fixture.ThingResource;
import eu.wohlben.qits.offline.testdb.OfflinePgResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * {@link IdempotencyInterceptor} against a real postgres, through the fixture resource: a key runs
 * its write once, a repeat replays the first answer, a reused key with another body is refused,
 * and a failed write leaves the key free.
 */
@QuarkusTest
@QuarkusTestResource(OfflinePgResource.class)
@EnabledIf(
    value = "eu.wohlben.qits.offline.testdb.EmbeddedPg#available",
    disabledReason = "zonky's initdb refuses to run as root, which is how the CI step container runs")
class IdempotencyTest {

  @Inject DataSource dataSource;

  @BeforeEach
  void reset() throws Exception {
    OfflineDb.reset(dataSource);
  }

  @Test
  void aRepeatedKeyAnswersTheFirstResultAndRunsTheMethodOnce() {
    Response first = create("key-1", "a", "first");
    first.then().statusCode(201).header("Idempotency-Replayed", nullValue());

    Response again = create("key-1", "a", "first");
    again
        .then()
        .statusCode(201)
        .header("Idempotency-Replayed", "true")
        .header("Location", first.header("Location"))
        .header("ETag", "\"1\"");
    assertEquals(first.body().asString(), again.body().asString());
    assertEquals(1, ThingResource.CREATES.get(), "the replay must not run the method");
  }

  @Test
  void aReplayOfAMethodDeclaringADtoAnswersTheStoredBody() throws Exception {
    create("key-c", "a", "first").then().statusCode(201);

    Response first = update("key-u", "a", "\"1\"", "second");
    first.then().statusCode(200).body("version", equalTo(2)).header("Idempotency-Replayed", nullValue());
    // If-Match is now stale, which proves the replay never reaches RecordVersions.require.
    Response again = update("key-u", "a", "\"1\"", "second");
    again.then().statusCode(200).header("Idempotency-Replayed", "true");
    assertEquals(first.body().asString(), again.body().asString());
    assertEquals(1, ThingResource.UPDATES.get());
    assertEquals(1, OfflineDb.count(dataSource, "id = 'a' and version = 2"));
  }

  @Test
  void twoParallelRequestsWithOneKeyRunTheMethodOnce() throws Exception {
    ThingResource.createDelayMillis = 700;
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch go = new CountDownLatch(1);
      List<CompletableFuture<Response>> calls =
          List.of(
              CompletableFuture.supplyAsync(() -> await(go, "key-p"), pool),
              CompletableFuture.supplyAsync(() -> await(go, "key-p"), pool));
      go.countDown();
      List<Response> answers = calls.stream().map(CompletableFuture::join).toList();

      answers.forEach(answer -> answer.then().statusCode(201));
      assertEquals(1, ThingResource.CREATES.get(), "the duplicate must wait, then replay");
      assertEquals(
          1,
          answers.stream().filter(a -> "true".equals(a.header("Idempotency-Replayed"))).count());
      assertEquals(1, OfflineDb.count(dataSource, "id = 'p'"));
    } finally {
      pool.shutdownNow();
      pool.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void aReusedKeyWithAnotherBodyIsRefused() {
    create("key-r", "a", "first").then().statusCode(201);

    create("key-r", "b", "other")
        .then()
        .statusCode(422)
        .contentType("application/problem+json")
        .body("type", equalTo(Problems.IDEMPOTENCY_KEY_REUSED))
        .body("status", equalTo(422));
    assertEquals(1, ThingResource.CREATES.get());
  }

  @Test
  void aThrownFailureFreesTheKey() throws Exception {
    ThingResource.FAIL_NEXT_CREATE.set("throw");
    create("key-t", "a", "first").then().statusCode(500);
    assertEquals(0, OfflineDb.count(dataSource, "true"), "the write rolls back with the key");

    create("key-t", "a", "first").then().statusCode(201).header("Idempotency-Replayed", nullValue());
    assertEquals(2, ThingResource.CREATES.get(), "the retry runs again");
  }

  @Test
  void aNon2xxResponseFreesTheKey() throws Exception {
    ThingResource.FAIL_NEXT_CREATE.set("response");
    create("key-n", "a", "first").then().statusCode(409);
    assertEquals(0, OfflineDb.count(dataSource, "true"), "the write rolls back with the key");

    create("key-n", "a", "first").then().statusCode(201).header("Idempotency-Replayed", nullValue());
    assertEquals(2, ThingResource.CREATES.get());
  }

  @Test
  void anInvalidKeyIsRefusedBeforeTheMethodRuns() {
    create("k".repeat(256), "a", "first")
        .then()
        .statusCode(400)
        .contentType("application/problem+json")
        .body("type", equalTo(Problems.IDEMPOTENCY_KEY_INVALID));
    assertEquals(0, ThingResource.CREATES.get());
  }

  @Test
  void withoutAKeyTheWriteRunsAsUsual() {
    given()
        .contentType("application/json")
        .body("{\"id\":\"a\",\"name\":\"x\"}")
        .post("/things")
        .then()
        .statusCode(201)
        .header("Idempotency-Replayed", nullValue());
    assertNull(ThingResource.FAIL_NEXT_CREATE.get());
    assertEquals(1, ThingResource.CREATES.get());
  }

  private static Response await(CountDownLatch go, String key) {
    try {
      go.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
    return create(key, "p", "parallel");
  }

  static Response create(String key, String id, String name) {
    return given()
        .header("Idempotency-Key", key)
        .contentType("application/json")
        .body("{\"id\":\"" + id + "\",\"name\":\"" + name + "\"}")
        .post("/things");
  }

  static Response update(String key, String id, String ifMatch, String name) {
    return given()
        .header("Idempotency-Key", key)
        .header("If-Match", ifMatch)
        .contentType("application/json")
        .body("{\"name\":\"" + name + "\"}")
        .put("/things/" + id);
  }
}
