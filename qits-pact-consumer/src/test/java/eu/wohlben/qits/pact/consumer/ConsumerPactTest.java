package eu.wohlben.qits.pact.consumer;

import static eu.wohlben.qits.pact.consumer.GoldenMastersTest.FIXTURE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import au.com.dius.pact.core.matchers.Mismatch;
import au.com.dius.pact.core.matchers.ResponseMatching;
import au.com.dius.pact.core.model.ContentType;
import au.com.dius.pact.core.model.HttpResponse;
import au.com.dius.pact.core.model.OptionalBody;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.TestAbortedException;

/** From fixture recordings to pact interactions: what is bound, what is sent, and the pact file. */
class ConsumerPactTest {

  static final String CONSUMER = "qits-fixture-consumer";
  static final ObjectMapper MAPPER = new ObjectMapper();
  static final HttpClient HTTP = HttpClient.newHttpClient();

  static final GoldenInteraction GET_THING = GoldenInteraction.of(Trigger.operation("showThing"), "a thing", "getThing")
      .consumes("id", "owner.name", "tags")
      .header("Authorization", "{authorization}");

  private static JsonNode interaction(GoldenInteraction row) {
    JsonNode pact = ConsumerPact.of(CONSUMER, FIXTURE, row).normalisedPact(List.of(row));
    assertEquals(1, pact.path("interactions").size());
    return pact.path("interactions").get(0);
  }

  @Test
  void theAnswerHoldsOnlyWhatTheConsumerReads() {
    JsonNode i = interaction(GET_THING);
    JsonNode body = i.path("response").path("body").path("content");
    assertEquals(List.of("id", "owner", "tags"), List.copyOf(fieldNames(body)));
    assertEquals(List.of("name"), List.copyOf(fieldNames(body.path("owner"))));
    JsonNode rules = i.path("response").path("matchingRules").path("body");
    assertEquals("regex", rules.path("$.id").path("matchers").get(0).path("match").asText());
    assertEquals("type", rules.path("$.owner.name").path("matchers").get(0).path("match").asText());
    assertTrue(rules.has("$.tags"), rules.toString());
    assertEquals("a thing", i.path("providerStates").get(0).path("name").asText());
    assertEquals("showThing: getThing", i.path("description").asText());
  }

  @Test
  void pathAndAuthorizationComeFromTheProviderState() {
    JsonNode request = interaction(GET_THING).path("request");
    assertEquals("/fixture/api/things/00000000-0000-4000-8000-000000000001", request.path("path").asText());
    JsonNode generators = request.path("generators");
    assertEquals("/fixture/api/things/${thingId}", generators.path("path").path("expression").asText(), generators.toString());
    assertEquals("Bearer fixture-token", request.path("headers").path("Authorization").get(0).asText());
    assertEquals("${authorization}",
        generators.path("header").path("Authorization").path("expression").asText(), generators.toString());
  }

  @Test
  void bothReferencesAreCarried() {
    JsonNode references = interaction(GET_THING).path("comments").path("references");
    assertEquals("qits-fixture-service", references.path("qits-call").path("app").asText());
    assertEquals("getThing", references.path("qits-call").path("operationId").asText());
    assertEquals("operation", references.path("qits-trigger").path("kind").asText());
    assertEquals(CONSUMER, references.path("qits-trigger").path("app").asText());
    assertEquals("showThing", references.path("qits-trigger").path("operationId").asText());
  }

  static final Via EVENTSTREAM = new Via("qits-eventstream-javalib", "eu.wohlben.qits:qits-eventstream", "2026.1.100000");

  @Test
  void viaChangesTheFileNameToThreeParts() {
    ConsumerPact direct = ConsumerPact.of(CONSUMER, FIXTURE, GET_THING);
    assertEquals("qits-fixture-consumer_qits-fixture-service.json", direct.file());
    assertTrue(direct.via().isEmpty());

    ConsumerPact viaLibrary = direct.via(EVENTSTREAM);
    assertEquals("qits-fixture-consumer_qits-eventstream-javalib_qits-fixture-service.json", viaLibrary.file());
    assertEquals(EVENTSTREAM, viaLibrary.via().orElseThrow());
    assertEquals(direct.consumer(), viaLibrary.consumer());
    assertEquals(direct.masters(), viaLibrary.masters());
    assertEquals(direct.rows(), viaLibrary.rows());
  }

  /** A client the provider itself publishes: naming its own repository twice would say nothing new. */
  @Test
  void aProviderOwnedClientKeepsTheTwoPartFileName() {
    Via ownClient = new Via(FIXTURE.repository(), "eu.wohlben.qits:qits-fixture-client", "1.0.0");
    ConsumerPact pact = ConsumerPact.of(CONSUMER, FIXTURE, GET_THING).via(ownClient);
    assertEquals("qits-fixture-consumer_qits-fixture-service.json", pact.file());
  }

  @Test
  void qitsViaIsCarriedOnEveryInteractionWhenSet() {
    ConsumerPact pact = ConsumerPact.of(CONSUMER, FIXTURE, GET_THING).via(EVENTSTREAM);
    JsonNode references = pact.normalisedPact(List.of(GET_THING)).path("interactions").get(0)
        .path("comments").path("references");
    JsonNode via = references.path("qits-via");
    assertEquals(EVENTSTREAM.repository(), via.path("repository").asText());
    assertEquals(EVENTSTREAM.artifact(), via.path("artifact").asText());
    assertEquals(EVENTSTREAM.version(), via.path("version").asText());
    assertEquals("operation", references.path("qits-trigger").path("kind").asText());
    assertEquals(CONSUMER, references.path("qits-trigger").path("app").asText(), "trigger app stays the consumer");
    pact.assertEveryInteractionCarriesBothReferences();
  }

  @Test
  void qitsViaIsAbsentWithoutVia() {
    JsonNode references = interaction(GET_THING).path("comments").path("references");
    assertFalse(references.has("qits-via"), references.toString());
    ConsumerPact.of(CONSUMER, FIXTURE, GET_THING).assertEveryInteractionCarriesBothReferences();
  }

  @Test
  void aStatusOnlyRowHasNoBody() {
    JsonNode i = interaction(GoldenInteraction.of(Trigger.operation("removeThing"), "a thing", "deleteThing"));
    assertEquals(204, i.path("response").path("status").asInt());
    assertFalse(i.path("response").has("body"), i.toString());
    assertFalse(i.path("response").path("headers").has("Content-Type"), i.toString());
    assertFalse(i.path("request").has("body"), i.toString());
  }

  @Test
  void aTopLevelArrayIsMatchedAsContainsWhenTheRecorderFilteredIt() {
    JsonNode i = interaction(GoldenInteraction.of(Trigger.operation("showThings"), "a thing", "listThings")
        .consumes("[].id"));
    JsonNode body = i.path("response").path("body").path("content");
    assertTrue(body.isArray(), body.toString());
    assertEquals(List.of("id"), List.copyOf(fieldNames(body.get(0))));
    JsonNode rules = i.path("response").path("matchingRules").path("body");
    assertEquals(2, rules.path("$").path("matchers").get(0).path("min").asInt(), rules.toString());
    assertFalse(rules.path("$").path("matchers").get(0).has("max"), rules.toString());
    assertEquals("regex", rules.path("$[*].id").path("matchers").get(0).path("match").asText(), rules.toString());
    assertEquals("limit=20", queryOf(i));
  }

  @Test
  void jsonPathSpellingReadsTheSame() {
    JsonNode dotted = interaction(GoldenInteraction.of(Trigger.operation("showThings"), "a thing", "listThings")
        .consumes("[].id")).path("response");
    JsonNode jsonPath = interaction(GoldenInteraction.of(Trigger.operation("showThings"), "a thing", "listThings")
        .consumes("$[*].id")).path("response");
    assertEquals(dotted, jsonPath);
  }

  @Test
  void aLegacyQueryIsSentApartFromThePath() {
    JsonNode request = interaction(GoldenInteraction.of(Trigger.operation("find"), "a thing", "searchThings")
        .consumes("items[].name", "nextCursor")).path("request");
    assertEquals("/fixture/api/things/search", request.path("path").asText());
    assertEquals(List.of("a", "b"), MAPPER.convertValue(request.path("query").path("tag"), List.class));
    assertEquals("${thingId}", request.path("generators").path("query").path("owner").path("expression").asText(),
        request.toString());
  }

  /**
   * {@code attr=repositoryname={repositoryName}&attr=projectid={projectId}}: every value of a
   * repeated query parameter is the state's example for the mock server and its own provider-state
   * expression for the verifier, so a provider with fresh ids is asked for its own.
   */
  @Test
  void everyValueOfARepeatedQueryParameterIsAProviderStateExpression(@TempDir Path dir) throws Exception {
    var row = GoldenInteraction.of(Trigger.command("qits checkout-daemon"), "an event feed", "listEvents")
        .consumes("[].type");
    JsonNode request = interaction(row).path("request");
    assertEquals(List.of("repositoryname=fixture-repo", "projectid=00000000-0000-4000-8000-000000000031"),
        MAPPER.convertValue(request.path("query").path("attr"), List.class));
    assertEquals(List.of("5"), MAPPER.convertValue(request.path("query").path("limit"), List.class));
    JsonNode generators = request.path("generators").path("query");
    assertEquals("repositoryname=${repositoryName}", generators.path("attr[0]").path("expression").asText(),
        request.toString());
    assertEquals("projectid=${projectId}", generators.path("attr[1]").path("expression").asText(),
        request.toString());

    // the verifier's side, from the written pact as a provider loads it: pact-jvm 4.6.21 generates
    // the request but leaves both values recorded; RepeatedQuery substitutes each from the state
    var pact = ConsumerPact.of(CONSUMER, FIXTURE, row);
    Path file = Files.writeString(dir.resolve("pact.json"), MAPPER.writeValueAsString(pact.normalisedPact(List.of(row))));
    var interaction = au.com.dius.pact.core.model.DefaultPactReader.INSTANCE.loadPact(file.toFile())
        .getInteractions().get(0);
    Map<String, Object> context = Map.of("providerState",
        Map.of("repositoryName", "other repo", "projectId", "fresh-id"));
    var generated = interaction.asSynchronousRequestResponse().getRequest()
        .generatedRequest(context, au.com.dius.pact.core.model.generators.GeneratorTestMode.Provider);
    assertEquals(List.of("repositoryname=fixture-repo", "projectid=00000000-0000-4000-8000-000000000031"),
        generated.getQuery().get("attr"));
    StringBuilder sent = new StringBuilder("http://provider:8080" + generated.getPath() + "?");
    generated.getQuery().forEach((name, values) -> values.forEach(v -> sent.append(name).append('=')
        .append(java.net.URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8)).append('&')));
    URI substituted = RepeatedQuery.generated(URI.create(sent.substring(0, sent.length() - 1)), interaction, context);
    assertEquals("http://provider:8080/fixture/api/events"
        + "?attr=repositoryname%3Dother+repo&attr=projectid%3Dfresh-id&limit=5", substituted.toString());
    URI plain = URI.create("http://provider:8080/fixture/api/things/search?tag=a");
    var single = ConsumerPact.of(CONSUMER, FIXTURE, GET_THING).pact(List.of(GET_THING)).getInteractions().get(0);
    assertEquals(plain, RepeatedQuery.generated(plain, single, context), "no indexed generator: unchanged");

    // the consumer's side: the mock server takes the examples, both values
    pact.run(row, (url, recorded) -> {
      String query = "?attr=" + java.net.URLEncoder.encode("repositoryname=fixture-repo", "UTF-8")
          + "&attr=" + java.net.URLEncoder.encode("projectid=00000000-0000-4000-8000-000000000031", "UTF-8")
          + "&limit=5";
      var answer = HTTP.send(HttpRequest.newBuilder(URI.create(url + recorded.examplePath() + query)).build(),
          java.net.http.HttpResponse.BodyHandlers.ofString());
      assertEquals(200, answer.statusCode());
    });
    assertThrows(AssertionError.class, () -> pact.run(row, (url, recorded) ->
        HTTP.send(HttpRequest.newBuilder(URI.create(url + recorded.examplePath()
            + "?attr=repositoryname%3Dfixture-repo&attr=projectid%3Dother&limit=5")).build(),
            java.net.http.HttpResponse.BodyHandlers.ofString())));
  }

  @Test
  void aJsonRequestBodyIsTheRecordedOneWithStateValuesAsExpressions() {
    JsonNode request = interaction(GoldenInteraction.of(Trigger.operation("addThing"), "a thing", "createThing")
        .consumes("id")).path("request");
    assertEquals("second", request.path("body").path("content").path("name").asText());
    assertEquals("00000000-0000-4000-8000-000000000001", request.path("body").path("content").path("parent").asText());
    assertEquals("${thingId}",
        request.path("generators").path("body").path("$.parent").path("expression").asText(), request.toString());
    assertTrue(request.path("headers").path("Content-Type").toString().contains("application/json"));
  }

  @Test
  void aFormBodyIsSentWithItsContentType() {
    JsonNode request = interaction(GoldenInteraction.of(Trigger.command("qits login"), "a token request", "token")
        .consumes("access_token", "expires_in")).path("request");
    assertEquals("grant_type=client_credentials&client_id=fixture-client", request.path("body").path("content").asText());
    assertTrue(request.path("headers").path("Content-Type").toString().contains("application/x-www-form-urlencoded"),
        request.toString());
  }

  static final byte[] BUNDLE = java.util.Base64.getDecoder().decode("Zml4dHVyZSBidW5kbGUgAAEC/w==");

  static final GoldenInteraction PUT_BUNDLE =
      GoldenInteraction.of(Trigger.command("qits publish docs"), "a bundle", "putBundle").consumes("version");

  /** A body recorded as bodyBase64 is sent byte for byte, under its recorded type: not dropped. */
  @Test
  void aBinaryBodyIsSentByteForByteWithItsContentType() {
    JsonNode request = interaction(PUT_BUNDLE).path("request");
    JsonNode body = request.path("body");
    assertEquals("base64", body.path("encoded").asText(), request.toString());
    assertEquals("application/gzip", body.path("contentType").asText(), request.toString());
    assertTrue(java.util.Arrays.equals(BUNDLE, java.util.Base64.getDecoder().decode(body.path("content").asText())));
    assertTrue(request.path("headers").path("Content-Type").toString().contains("application/gzip"), request.toString());
    assertEquals("/fixture/api/bundles/2100.1.0", request.path("path").asText());
  }

  @Test
  void aBinaryRowRunsAgainstAMockServerOnlyWithTheRecordedBytes() {
    ConsumerPact pact = ConsumerPact.of(CONSUMER, FIXTURE, PUT_BUNDLE);
    pact.run(PUT_BUNDLE, (url, recorded) -> {
      java.net.http.HttpResponse<String> answer = HTTP.send(HttpRequest.newBuilder(URI.create(url + recorded.examplePath()))
          .header("Content-Type", "application/gzip").PUT(HttpRequest.BodyPublishers.ofByteArray(BUNDLE)).build(),
          java.net.http.HttpResponse.BodyHandlers.ofString());
      assertEquals(201, answer.statusCode());
    });
    assertThrows(AssertionError.class, () -> pact.run(PUT_BUNDLE, (url, recorded) ->
        HTTP.send(HttpRequest.newBuilder(URI.create(url + recorded.examplePath()))
            .header("Content-Type", "application/gzip").PUT(HttpRequest.BodyPublishers.noBody()).build(),
            java.net.http.HttpResponse.BodyHandlers.ofString())));
  }

  /** A JSON answer of another media type is matched on that type, never a hard-coded application/json. */
  @Test
  void aJsonAnswerOfAnotherMediaTypeIsMatchedOnTheRecordedType() {
    var row = GoldenInteraction.of(Trigger.operation("readSbom"), "a bundle", "getSbom").consumes("bomFormat");
    JsonNode response = interaction(row).path("response");
    assertEquals("application/vnd.cyclonedx+json; version=1.6", response.path("headers").path("Content-Type").get(0).asText());
    JsonNode rule = response.path("matchingRules").path("header").path("Content-Type").path("matchers").get(0);
    assertEquals("application/vnd\\.cyclonedx\\+json.*", rule.path("regex").asText(), response.toString());
    var expected = ConsumerPact.of(CONSUMER, FIXTURE, row).pact(List.of(row))
        .getInteractions().get(0).asSynchronousRequestResponse().getResponse();
    String sbom = "{\"bomFormat\": \"CycloneDX\"}";
    var accepted = ResponseMatching.responseMismatches(expected, new HttpResponse(200,
        Map.of("Content-Type", List.of("application/vnd.cyclonedx+json; version=1.6")),
        OptionalBody.body(sbom, new ContentType("application/vnd.cyclonedx+json"))));
    assertTrue(accepted.isEmpty(), accepted.stream().map(Mismatch::description).toList().toString());
    var refused = ResponseMatching.responseMismatches(expected, answer(sbom));
    assertFalse(refused.isEmpty(), "plain application/json is not the recorded type");
  }

  @Test
  void aPlainJsonAnswerKeepsTheApplicationJsonMatcher() {
    for (var row : List.of(GET_THING, GoldenInteraction.of(Trigger.operation("showRuns"), "a bundle", "listRuns")
        .consumes("runs[].runId"))) {
      JsonNode rule = interaction(row).path("response").path("matchingRules").path("header")
          .path("Content-Type").path("matchers").get(0);
      assertEquals("application/json.*", rule.path("regex").asText(), row.description());
    }
  }

  /** A path the recorder lists under both ids and strings holds non-UUIDs too: a type match. */
  @Test
  void aPathFrozenAsBothIdAndStringIsMatchedAsAString() {
    var row = GoldenInteraction.of(Trigger.operation("showRuns"), "a bundle", "listRuns").consumes("runs[].runId");
    assertFalse(FIXTURE.operation("a bundle", "listRuns").ids().contains("$.runs[*].runId"));
    JsonNode rules = interaction(row).path("response").path("matchingRules").path("body");
    assertEquals("type", rules.path("$.runs[*].runId").path("matchers").get(0).path("match").asText(), rules.toString());
    var expected = ConsumerPact.of(CONSUMER, FIXTURE, row).pact(List.of(row))
        .getInteractions().get(0).asSynchronousRequestResponse().getResponse();
    var mismatches = ResponseMatching.responseMismatches(expected, answer(
        "{\"runs\": [{\"runId\": \"bump-00000000-0000-4000-8000-000000000033\"}, {\"runId\": \"00000000-0000-4000-8000-000000000034\"}]}"));
    assertTrue(mismatches.isEmpty(), mismatches.stream().map(Mismatch::description).toList().toString());
  }

  @Test
  void aResponseHeaderTheConsumerReadsIsBound() {
    JsonNode response = interaction(GoldenInteraction.of(Trigger.operation("readFile"), "a thing", "getThingFile")
        .readsHeader("Git-Commit-Sha")).path("response");
    assertEquals("e447df8a0cabaeafedbf4c0235e013bb29697cd9", response.path("headers").path("Git-Commit-Sha").get(0).asText());
    assertTrue(response.path("matchingRules").path("header").has("Git-Commit-Sha"), response.toString());
    assertFalse(response.has("body"), response.toString());
  }

  @Test
  void aHeaderTheRecordingLacksIsRefused() {
    var row = GoldenInteraction.of(Trigger.operation("readFile"), "a thing", "getThingFile").readsHeader("ETag");
    var refused = assertThrows(IllegalStateException.class, () -> interaction(row));
    assertTrue(refused.getMessage().contains("ETag"), refused.getMessage());
  }

  @Test
  void aPathTheRecordingLacksIsRefused() {
    var row = GoldenInteraction.of(Trigger.operation("showThing"), "a thing", "getThing").consumes("colour");
    var refused = assertThrows(IllegalStateException.class, () -> interaction(row));
    assertTrue(refused.getMessage().contains("consumes 'colour'"), refused.getMessage());
  }

  @Test
  void keyedObjectsAndArraysOfAnyLength() {
    JsonNode i = interaction(GoldenInteraction.of(Trigger.operation("showRegistry"), "a registry", "getRegistry")
        .consumes("archetypes[].phases", "byId")
        .anyLength("archetypes[].phases.*.flow"));
    JsonNode rules = i.path("response").path("matchingRules").path("body");
    for (String status : List.of("OPEN", "READY", "DONE")) {
      JsonNode flow = rules.path("$.archetypes[*].phases." + status + ".flow").path("matchers").get(0);
      assertEquals(0, flow.path("min").asInt(), rules.toString());
      assertFalse(flow.has("max"), rules.toString());
    }
    assertEquals("values", rules.path("$.byId").path("matchers").get(0).path("match").asText(), rules.toString());
  }

  /**
   * Variance below the top level: a field null in some elements of a nested object, an array empty in
   * some elements. The pact accepts every recorded shape in every element, and still refuses a wrong type.
   */
  @Test
  void nestedNullsAndNestedEmptyArraysAreAccepted() {
    var row = GoldenInteraction.of(Trigger.operation("showEntries"), "an entry log", "listEntries")
        .consumes("entries[].id", "entries[].agent.removedAt", "entries[].branches");
    var expected = ConsumerPact.of(CONSUMER, FIXTURE, row).pact(List.of(row))
        .getInteractions().get(0).asSynchronousRequestResponse().getResponse();
    String removed = """
        {"id": "00000000-0000-4000-8000-0000000000%s", "agent": {"removedAt": "2026-02-03T04:05:06Z"}, "branches": %s}""";
    String present = """
        {"id": "00000000-0000-4000-8000-0000000000%s", "agent": {"removedAt": null}, "branches": %s}""";
    for (String entries : List.of(
        present.formatted("21", "[]") + "," + present.formatted("22", "[]"),
        removed.formatted("21", "[\"y\"]") + "," + removed.formatted("22", "[\"z\"]"),
        removed.formatted("21", "[]") + "," + present.formatted("22", "[\"y\"]"))) {
      var mismatches = ResponseMatching.responseMismatches(expected, answer("{\"entries\": [" + entries + "]}"));
      assertTrue(mismatches.isEmpty(), entries + " -> " + mismatches.stream().map(Mismatch::description).toList());
    }
    String wrong = removed.formatted("21", "[]").replace("2026-02-03T04:05:06Z", "yesterday")
        + "," + present.formatted("22", "[1]");
    String refused = ResponseMatching.responseMismatches(expected, answer("{\"entries\": [" + wrong + "]}"))
        .stream().map(Mismatch::description).toList().toString();
    assertTrue(refused.contains("Expected 'yesterday' to match"), refused);
    assertTrue(refused.contains("Expected 1 (Integer) to be the same type as 'x'"), refused);
  }

  /**
   * A gauge recorded as {@code 1.5E8}: pact-jvm's {@code number} (and {@code decimal}) refuse a
   * number in exponent form, as its BigDecimal has a negative scale. A number recorded with a
   * fraction or an exponent, in any element, is a type match; an integer keeps {@code number}.
   */
  @Test
  void aNumberInExponentFormVerifiesWhateverItsNotation() {
    var row = GoldenInteraction.of(Trigger.operation("showGauges"), "a gauge", "listGauges")
        .consumes("gauges[].value", "peak", "ratio", "count", "samples", "parts");
    var expected = ConsumerPact.of(CONSUMER, FIXTURE, row).pact(List.of(row))
        .getInteractions().get(0).asSynchronousRequestResponse().getResponse();
    for (String value : List.of("1.5E8", "2.5E-3", "1E10", "150000000", "0.25", "7")) {
      String body = """
          {"gauges": [{"value": %1$s}, {"value": %1$s}], "peak": %1$s, "ratio": %1$s, "count": 3,
           "samples": [%1$s, %1$s], "parts": [%1$s, {"name": "heap"}]}""".formatted(value);
      var mismatches = ResponseMatching.responseMismatches(expected, answer(body));
      assertTrue(mismatches.isEmpty(), value + " -> " + mismatches.stream().map(Mismatch::description).toList());
    }
    String refused = ResponseMatching.responseMismatches(expected, answer("""
        {"gauges": [{"value": "1.5E8"}, {"value": 1}], "peak": 1, "ratio": 1, "count": 3,
         "samples": [1, 1], "parts": [1, {"name": "heap"}]}"""))
        .stream().map(Mismatch::description).toList().toString();
    assertTrue(refused.contains("'1.5E8' (String) to be the same type as"), refused);

    JsonNode rules = interaction(row).path("response").path("matchingRules").path("body");
    for (String path : List.of("$.gauges[*].value", "$.peak", "$.ratio", "$.samples[*]", "$.parts[0]")) {
      assertEquals("type", rules.path(path).path("matchers").get(0).path("match").asText(), path + ": " + rules);
    }
    assertEquals("number", rules.path("$.count").path("matchers").get(0).path("match").asText(), rules.toString());
  }

  private static HttpResponse answer(String json) {
    return new HttpResponse(200, Map.of("Content-Type", List.of("application/json")),
        OptionalBody.body(json, ContentType.getJSON()));
  }

  static final GoldenInteraction TOKEN = GoldenInteraction.of(Trigger.command("qits login"), "a token request", "token")
      .consumes("access_token", "token_type", "scope");

  /** An exact leaf is the recorded value with no rule; the others stay type matches. */
  @Test
  void anExactLeafIsBoundToItsRecordedValue() {
    var row = TOKEN.exact("token_type");
    JsonNode i = interaction(row);
    JsonNode rules = i.path("response").path("matchingRules").path("body");
    assertFalse(rules.has("$.token_type"), rules.toString());
    assertEquals("type", rules.path("$.access_token").path("matchers").get(0).path("match").asText(), rules.toString());
    assertEquals("Bearer", i.path("response").path("body").path("content").path("token_type").asText());
    assertEquals(interaction(row).path("response"), interaction(TOKEN.exact("$.token_type")).path("response"));

    var expected = ConsumerPact.of(CONSUMER, FIXTURE, row).pact(List.of(row))
        .getInteractions().get(0).asSynchronousRequestResponse().getResponse();
    String other = "{\"access_token\": \"another\", \"token_type\": \"Bearer\", \"scope\": \"other\"}";
    assertTrue(ResponseMatching.responseMismatches(expected, answer(other)).isEmpty());
    String wrong = other.replace("\"Bearer\"", "\"bearer-ish\"");
    String refused = ResponseMatching.responseMismatches(expected, answer(wrong))
        .stream().map(Mismatch::description).toList().toString();
    assertTrue(refused.contains("bearer-ish"), refused);
  }

  @Test
  void anExactLeafBeneathAWholeConsumedObjectIsBound() {
    JsonNode i = interaction(GoldenInteraction.of(Trigger.operation("showThing"), "a thing", "getThing")
        .consumes("owner").exact("owner.name"));
    JsonNode rules = i.path("response").path("matchingRules").path("body");
    assertFalse(rules.has("$.owner.name"), rules.toString());
    assertEquals("fixture", i.path("response").path("body").path("content").path("owner").path("name").asText());
  }

  @Test
  void anExactPathThatIsNotConsumedIsRefused() {
    var refused = assertThrows(IllegalArgumentException.class, () -> TOKEN.exact("expires_in"));
    assertTrue(refused.getMessage().contains("exact 'expires_in' is not consumed"), refused.getMessage());
    var elements = GoldenInteraction.of(Trigger.operation("find"), "a thing", "searchThings").consumes("items[]");
    assertThrows(IllegalArgumentException.class, () -> elements.exact("items"));
  }

  @Test
  void anExactPathThatCannotStandForOneValueIsRefused() {
    var search = GoldenInteraction.of(Trigger.operation("find"), "a thing", "searchThings").consumes("items[].name");
    var array = assertThrows(IllegalArgumentException.class, () -> search.exact("items[].name"));
    assertTrue(array.getMessage().contains("crosses an array"), array.getMessage());
    assertThrows(IllegalArgumentException.class, () -> TOKEN.exact("$"));
    var thing = GoldenInteraction.of(Trigger.operation("showThing"), "a thing", "getThing").consumes("id", "owner");
    for (String path : List.of("id", "owner", "owner.colour")) {
      var refused = assertThrows(IllegalStateException.class, () -> interaction(thing.exact(path)));
      assertTrue(refused.getMessage().contains(path.equals("owner.colour") ? "does not hold" : "exact"),
          refused.getMessage());
    }
    var keyed = GoldenInteraction.of(Trigger.operation("showRegistry"), "a registry", "getRegistry")
        .consumes("byId").exact("byId.00000000-0000-4000-8000-000000000001.status");
    var refused = assertThrows(IllegalStateException.class, () -> interaction(keyed));
    assertTrue(refused.getMessage().contains("keyed object"), refused.getMessage());
  }

  /** pact-jvm adds its own lower-case content-type; the request carries one. */
  @Test
  void aRequestCarriesOneContentType() {
    for (var row : List.of(TOKEN, GoldenInteraction.of(Trigger.operation("addThing"), "a thing", "createThing").consumes("id"))) {
      JsonNode headers = interaction(row).path("request").path("headers");
      assertEquals(1, fieldNames(headers).stream().filter("content-type"::equalsIgnoreCase).count(), headers.toString());
    }
    var headers = new java.util.LinkedHashMap<String, List<String>>();
    headers.put("Content-Type", List.of("application/json"));
    headers.put("Authorization", List.of("Bearer x"));
    headers.put("content-type", List.of("application/json"));
    ConsumerPact.oneOfEachHeader(headers);
    assertEquals(List.of("Content-Type", "Authorization"), List.copyOf(headers.keySet()));
  }

  /** The row runs against a mock server: the right request passes, a wrong one fails. */
  @Test
  void aRowRunsAgainstAMockServer() {
    ConsumerPact pact = ConsumerPact.of(CONSUMER, FIXTURE, GET_THING);
    pact.run(GET_THING, (url, recorded) -> {
      java.net.http.HttpResponse<String> answer = HTTP.send(HttpRequest.newBuilder(URI.create(url + recorded.examplePath()))
          .header("Authorization", recorded.params().get("authorization")).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
      assertEquals(200, answer.statusCode());
      assertEquals("fixture", MAPPER.readTree(answer.body()).path("owner").path("name").asText());
    });
    assertThrows(AssertionError.class, () -> pact.run(GET_THING, (url, recorded) ->
        HTTP.send(HttpRequest.newBuilder(URI.create(url + "/fixture/api/elsewhere")).build(),
            java.net.http.HttpResponse.BodyHandlers.ofString())));
  }

  @Test
  void aFormRowAndAQueryRowRunAgainstAMockServer() {
    var token = GoldenInteraction.of(Trigger.command("qits login"), "a token request", "token").consumes("access_token");
    ConsumerPact.of(CONSUMER, FIXTURE, token).run(token, (url, recorded) -> {
      java.net.http.HttpResponse<String> answer = HTTP.send(HttpRequest.newBuilder(URI.create(url + recorded.path()))
          .header("Content-Type", "application/x-www-form-urlencoded")
          .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials&client_id=fixture-client")).build(),
          java.net.http.HttpResponse.BodyHandlers.ofString());
      assertEquals("fixture-access-token", MAPPER.readTree(answer.body()).path("access_token").asText());
    });
    var search = GoldenInteraction.of(Trigger.operation("find"), "a thing", "searchThings").consumes("items[].name");
    ConsumerPact.of(CONSUMER, FIXTURE, search).run(search, (url, recorded) -> {
      java.net.http.HttpResponse<String> answer = HTTP.send(HttpRequest.newBuilder(URI.create(url + recorded.path()
          + "?tag=a&tag=b&owner=" + recorded.params().get("thingId"))).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
      assertEquals("first", MAPPER.readTree(answer.body()).path("items").get(0).path("name").asText());
    });
  }

  @Test
  void anUnrecordedRowFailsToRunNamingTheState() {
    var waiting = GoldenInteraction.of(Trigger.operation("renameThing"), "a renamed thing", "renameThing");
    ConsumerPact pact = ConsumerPact.of(CONSUMER, FIXTURE, GET_THING, waiting);
    assertEquals(List.of(waiting), pact.unrecorded());
    assertEquals(List.of(GET_THING), pact.recorded());
    ProviderStateMissing missing = assertThrows(ProviderStateMissing.class, () -> pact.run(waiting, (u, r) -> {}));
    assertTrue(missing.getMessage().startsWith(
        "needs provider state 'a renamed thing' for renameThing in qits-fixture-service"), missing.getMessage());
  }

  @Test
  void theSameDescriptionAndStateTwiceIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> ConsumerPact.of(CONSUMER, FIXTURE, GET_THING, GET_THING));
  }

  @Test
  void thePactFileIsWrittenOnRequestAndComparedOtherwise(@TempDir Path dir) throws Exception {
    var waiting = GoldenInteraction.of(Trigger.operation("renameThing"), "a renamed thing", "renameThing");
    ConsumerPact pact = ConsumerPact.of(CONSUMER, FIXTURE, GET_THING, waiting);
    Path file = dir.resolve("qits-fixture-consumer_qits-fixture-service.json");
    withProperties(dir, false, () -> assertThrows(AssertionError.class, pact::compareOrWritePactFile));
    withProperties(dir, true, pact::compareOrWritePactFile);
    assertTrue(Files.readString(file).endsWith("}\n"));
    JsonNode written = MAPPER.readTree(file.toFile());
    assertEquals(1, written.path("interactions").size(), "only the recorded row");
    assertFalse(written.path("metadata").has("pact-jvm"));
    withProperties(dir, false, pact::compareOrWritePactFile);
    pact.assertEveryInteractionCarriesBothReferences();
    Files.writeString(file, Files.readString(file).replace("showThing", "showAnotherThing"));
    withProperties(dir, false, () -> assertThrows(AssertionError.class, pact::compareOrWritePactFile));
  }

  /** A qits-maintenance bump of the via library: the version-only diff refreshes, not fails. */
  @Test
  void aLibraryBumpOnlyRefreshesTheViaVersion(@TempDir Path dir) throws Exception {
    Via oldVersion = new Via("qits-eventstream-javalib", "eu.wohlben.qits:qits-eventstream", "1.0.0");
    Via newVersion = new Via("qits-eventstream-javalib", "eu.wohlben.qits:qits-eventstream", "2.0.0");
    ConsumerPact was = ConsumerPact.of(CONSUMER, FIXTURE, GET_THING).via(oldVersion);
    Path file = dir.resolve("qits-fixture-consumer_qits-eventstream-javalib_qits-fixture-service.json");
    withProperties(dir, true, was::compareOrWritePactFile);
    assertTrue(Files.readString(file).contains("\"version\": \"1.0.0\""));

    ConsumerPact bumped = ConsumerPact.of(CONSUMER, FIXTURE, GET_THING).via(newVersion);
    ByteArrayOutputStream printed = new ByteArrayOutputStream();
    PrintStream original = System.out;
    System.setOut(new PrintStream(printed));
    try {
      withProperties(dir, false, bumped::compareOrWritePactFile); // must not throw
    } finally {
      System.setOut(original);
    }
    assertTrue(printed.toString().contains("via version refreshed 1.0.0"), printed.toString());
    assertTrue(printed.toString().contains("2.0.0"), printed.toString());
    String committedAfter = Files.readString(file);
    assertTrue(committedAfter.contains("\"version\": \"2.0.0\""), committedAfter);
    assertFalse(committedAfter.contains("\"version\": \"1.0.0\""), committedAfter);
    bumped.assertEveryInteractionCarriesBothReferences();
  }

  /** A version bump alongside an unrelated change is still a real diff, and still fails. */
  @Test
  void aRealDiffAlongsideTheVersionStillFails(@TempDir Path dir) throws Exception {
    Via oldVersion = new Via("qits-eventstream-javalib", "eu.wohlben.qits:qits-eventstream", "1.0.0");
    Via newVersion = new Via("qits-eventstream-javalib", "eu.wohlben.qits:qits-eventstream", "2.0.0");
    ConsumerPact was = ConsumerPact.of(CONSUMER, FIXTURE, GET_THING).via(oldVersion);
    Path file = dir.resolve("qits-fixture-consumer_qits-eventstream-javalib_qits-fixture-service.json");
    withProperties(dir, true, was::compareOrWritePactFile);
    Files.writeString(file, Files.readString(file).replace("showThing", "showAnotherThing"));

    ConsumerPact bumped = ConsumerPact.of(CONSUMER, FIXTURE, GET_THING).via(newVersion);
    withProperties(dir, false, () -> assertThrows(AssertionError.class, bumped::compareOrWritePactFile));
    assertTrue(Files.readString(file).contains("\"version\": \"1.0.0\""), "untouched: the real diff left it as is");
  }

  @Test
  void noRecordedRowAndNoFileIsSkipped(@TempDir Path dir) {
    ConsumerPact pact = ConsumerPact.of(CONSUMER, FIXTURE,
        GoldenInteraction.of(Trigger.operation("renameThing"), "a renamed thing", "renameThing"));
    withProperties(dir, false, () -> {
      TestAbortedException skipped = assertThrows(TestAbortedException.class, pact::compareOrWritePactFile);
      assertTrue(skipped.getMessage().contains("needs provider state 'a renamed thing'"), skipped.getMessage());
    });
  }

  private static void withProperties(Path dir, boolean update, ThrowingRunnable body) {
    System.setProperty(ConsumerPact.PACTS_DIR_PROPERTY, dir.toString());
    System.setProperty(ConsumerPact.UPDATE_PROPERTY, Boolean.toString(update));
    try {
      body.run();
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      System.clearProperty(ConsumerPact.PACTS_DIR_PROPERTY);
      System.clearProperty(ConsumerPact.UPDATE_PROPERTY);
    }
  }

  interface ThrowingRunnable {
    void run() throws Exception;
  }

  private static String queryOf(JsonNode interaction) {
    StringBuilder out = new StringBuilder();
    interaction.path("request").path("query").properties().forEach(e -> e.getValue().forEach(v ->
        out.append(out.isEmpty() ? "" : "&").append(e.getKey()).append('=').append(v.asText())));
    return out.toString();
  }

  private static List<String> fieldNames(JsonNode node) {
    return node.properties().stream().map(java.util.Map.Entry::getKey).toList();
  }
}
