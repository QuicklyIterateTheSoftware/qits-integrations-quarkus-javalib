package eu.wohlben.qits.pact.consumer;

import au.com.dius.pact.consumer.ConsumerPactRunnerKt;
import au.com.dius.pact.consumer.PactVerificationResult;
import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.consumer.dsl.Matchers;
import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.consumer.model.MockProviderConfig;
import au.com.dius.pact.core.model.DefaultPactWriter;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.support.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.pact.consumer.GoldenMasters.Operation;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;

/**
 * <b>Everything one consumer asks one provider</b> (epic qits-546, ticket qits-1149): its rows, the
 * pact they make, the consumer test that runs a row against a pact mock server, and the committed
 * pact file {@code pacts/<consumer>_<provider>.json}.
 *
 * <pre>{@code
 * static final GoldenMasters IDP = GoldenMasters.of("qits-idp-service", "qits-idp");
 * static final GoldenInteraction JWKS =
 *     GoldenInteraction.of(Trigger.operation("listThings"), "a published signing key", "getJwks")
 *         .consumes("keys[].kid", "keys[].n");
 * static final ConsumerPact PACT = ConsumerPact.of("my-repository", IDP, JWKS);
 *
 * @Test @Disabled("needs provider state 'a published signing key' for getJwks in qits-idp-service")
 * void readsTheKeys() {
 *   PACT.run(JWKS, (url, recorded) -> assertThat(new MyClient(url).keys()).isNotEmpty());
 * }
 *
 * @Test void theCommittedPactIsWhatTheRowsWrite() { PACT.compareOrWritePactFile(); }
 * }</pre>
 *
 * <p><b>A row whose state the provider has not recorded</b> is left out of the pact file, and
 * {@link #run} fails with {@link ProviderStateMissing}: {@code needs provider state '<state>' for
 * <operationId> in <provider>}. Such a row's test carries that sentence in {@code @Disabled}; the
 * day the provider records the state, the pact file test fails until the file is rewritten, and
 * deleting {@code @Disabled} switches the row on.
 *
 * <p><b>One mock server per row</b>: two rows may send the identical request and differ only in
 * their trigger, which one mock server serving every interaction could not tell apart.
 */
public final class ConsumerPact {

  /** Rewrites committed pact files instead of comparing them: {@code -Dgolden.update=true}. */
  public static final String UPDATE_PROPERTY = "golden.update";

  /** Overrides where the committed pact files live (default: {@code pacts/} in the repository root). */
  public static final String PACTS_DIR_PROPERTY = "qits.pacts.dir";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String consumer;
  private final GoldenMasters masters;
  private final List<GoldenInteraction> rows;

  private ConsumerPact(String consumer, GoldenMasters masters, List<GoldenInteraction> rows) {
    this.consumer = Objects.requireNonNull(consumer, "consumer");
    this.masters = Objects.requireNonNull(masters, "masters");
    this.rows = List.copyOf(rows);
    Set<String> unique = new HashSet<>();
    for (GoldenInteraction row : this.rows) {
      if (!unique.add(row.description() + "\u0000" + row.state())) {
        throw new IllegalArgumentException("two rows share description and state: " + row.description()
            + " [" + row.state() + "]");
      }
    }
  }

  /**
   * One consumer's rows against one provider.
   *
   * @param consumer the consumer's repository name, as the pact names it
   */
  public static ConsumerPact of(String consumer, GoldenMasters masters, GoldenInteraction... rows) {
    return new ConsumerPact(consumer, masters, List.of(rows));
  }

  /** {@link #of(String, GoldenMasters, GoldenInteraction...)} over a list. */
  public static ConsumerPact of(String consumer, GoldenMasters masters, List<GoldenInteraction> rows) {
    return new ConsumerPact(consumer, masters, rows);
  }

  public String consumer() {
    return consumer;
  }

  public GoldenMasters masters() {
    return masters;
  }

  public List<GoldenInteraction> rows() {
    return rows;
  }

  /** The committed pact's file name: both sides by repository name. */
  public String file() {
    return consumer + "_" + masters.repository() + ".json";
  }

  /** The rows whose (state, operation) the provider recorded: the ones the pact file holds. */
  public List<GoldenInteraction> recorded() {
    return rows.stream().filter(r -> masters.recorded(r.state(), r.operationId())).toList();
  }

  /** The rows still waiting for their provider state. */
  public List<GoldenInteraction> unrecorded() {
    return rows.stream().filter(r -> !masters.recorded(r.state(), r.operationId())).toList();
  }

  /** Why {@code row} waits: {@code needs provider state '<state>' for <operationId> in <provider>}. */
  public String needs(GoldenInteraction row) {
    return ProviderStateMissing.message(masters.repository(), row.state(), row.operationId());
  }

  // --- the pact ----------------------------------------------------------------------------------

  /** The pact of every recorded row. */
  public V4Pact pact() {
    return pact(recorded());
  }

  /** A pact holding only {@code selected}; each must be recorded. */
  public V4Pact pact(List<GoldenInteraction> selected) {
    PactBuilder builder = new PactBuilder(consumer, masters.repository(), PactSpecVersion.V4);
    selected.forEach(row -> add(builder, row));
    return builder.toPact();
  }

  /**
   * Adds the V4 HTTP interaction for {@code row} to {@code builder}: the provider state with its
   * params; the recorded method, path (each {@code {param}} a provider-state expression), query and
   * request body; the row's request headers; the recorded status; the response headers and body
   * paths the row reads, and nothing else (no body and no {@code Content-Type} when it reads no
   * path); and {@code comments.references}, {@code qits-call} and {@code qits-trigger}.
   *
   * @throws ProviderStateMissing when the provider did not record the row's state and operation
   */
  public PactBuilder add(PactBuilder builder, GoldenInteraction row) {
    Operation op = masters.operation(row.state(), row.operationId());
    JsonNode read = row.consumes().isEmpty()
        ? null
        : PactBodies.consumed(masters.json(row.state(), row.operationId()), row.consumes(), op);
    DslPart answer = read == null ? null : PactBodies.responseBody(op, read, row.anyLength());
    Map<String, String> answerHeaders = new LinkedHashMap<>();
    for (String name : row.readsHeaders()) {
      String value = op.responseHeader(name);
      if (value == null) {
        throw new IllegalStateException("golden master " + op.state() + "/" + op.operationId()
            + ": reads the response header " + name + ", which the recording does not hold");
      }
      answerHeaders.put(name, value);
    }
    Map<String, Object> references = new LinkedHashMap<>();
    Map<String, String> call = new LinkedHashMap<>();
    call.put("app", masters.repository());
    call.put("operationId", row.operationId());
    references.put("qits-call", call);
    references.put("qits-trigger", row.trigger().reference(consumer));
    return builder.expectsToReceiveHttpInteraction(row.description(), http -> {
      http.state(row.state(), new LinkedHashMap<String, Object>(op.params()));
      http.withRequest(r -> {
        r.method(op.method());
        if (op.hasParam(op.path())) {
          r.path(Matchers.fromProviderState(op.expressionPath(), op.examplePath()));
        } else {
          r.path(op.path());
        }
        op.query().forEach((name, values) -> {
          if (values.size() == 1 && op.hasParam(values.getFirst())) {
            r.queryParameter(name, Matchers.fromProviderState(op.expression(values.getFirst()),
                op.example(values.getFirst())));
          } else {
            r.queryParameter(name, values.stream().map(op::example).toList());
          }
        });
        row.requestHeaders().forEach((name, value) -> {
          if (op.hasParam(value)) {
            r.header(name, Matchers.fromProviderState(op.expression(value), op.example(value)));
          } else {
            r.header(name, value);
          }
        });
        JsonNode body = op.body();
        if (body != null && body.isTextual()) {
          // A form or another non-JSON body: the recorded text, each {param} its example.
          String type = op.contentType() == null ? "text/plain" : op.contentType();
          r.header("Content-Type", type);
          r.body(op.example(body.asText()), type);
        } else if (body != null) {
          r.header("Content-Type", op.contentType() == null ? "application/json" : op.contentType());
          r.body(PactBodies.requestBody(op));
        }
        return r;
      });
      http.willRespondWith(response -> {
        response.status(op.status());
        answerHeaders.forEach((name, value) -> response.header(name, Matchers.regexp(".+", value)));
        if (answer != null) {
          response.header("Content-Type", Matchers.regexp("application/json.*", "application/json"))
              .body(answer);
        }
        return response;
      });
      // pact-jvm 4.6's DSL has no setter for an arbitrary comment group, but the V4 model's
      // comments map is mutable and written verbatim.
      http.getInteraction().getComments().put("references", Json.toJson(references));
      return http;
    });
  }

  // --- the consumer test -------------------------------------------------------------------------

  /** The consumer's real client, pointed at the mock server, making the call and asserting what it read. */
  @FunctionalInterface
  public interface Call {
    void run(String baseUrl, Operation recorded) throws Exception;
  }

  /**
   * Runs {@code call} against a mock server that answers {@code row}'s interaction and nothing
   * else, and fails unless the call made exactly that request.
   *
   * @throws ProviderStateMissing when the provider did not record the row's state and operation
   */
  public void run(GoldenInteraction row, Call call) {
    Operation op = masters.operation(row.state(), row.operationId());
    PactVerificationResult result = ConsumerPactRunnerKt.runConsumerTest(
        pact(List.of(row)),
        MockProviderConfig.createDefault(PactSpecVersion.V4),
        (mockServer, context) -> {
          call.run(mockServer.getUrl(), op);
          return null;
        });
    if (result instanceof PactVerificationResult.Error error) {
      if (error.getError() instanceof AssertionError assertion) {
        throw assertion;
      }
      Assertions.fail(row.description() + ": " + error.getError(), error.getError());
    }
    if (!(result instanceof PactVerificationResult.Ok)) {
      Assertions.fail(row.description() + ": " + result.getDescription() + " — " + result);
    }
  }

  // --- the committed file ------------------------------------------------------------------------

  /**
   * The pact of the recorded rows, normalised, against the committed {@code pacts/<file>}: compared
   * by default, rewritten under {@code -Dgolden.update=true} (or {@code QITS_GOLDEN_UPDATE=true}).
   * The raw pact is also left in {@code target/pacts/} for inspection. Skipped, naming what is
   * missing, while no row is recorded and no file is committed: there is nothing to publish yet.
   */
  public void compareOrWritePactFile() {
    Path committed = pactsDirectory().resolve(file());
    List<GoldenInteraction> recorded = recorded();
    if (recorded.isEmpty()) {
      Assertions.assertFalse(Files.exists(committed), committed
          + " is committed, but no row of it is recorded any more: " + unrecorded().stream().map(this::needs).toList());
      Assumptions.abort("no pact to write yet: " + unrecorded().stream().map(this::needs).toList());
    }
    try {
      String raw = written(recorded);
      Path scratch = Path.of("target", "pacts", file());
      Files.createDirectories(scratch.getParent());
      Files.writeString(scratch, raw);
      GoldenFiles.compareOrWrite(committed, normalise(raw));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The pact of the recorded rows, normalised as the committed file is. */
  public JsonNode normalisedPact() {
    return normalisedPact(recorded());
  }

  /** The pact of {@code selected}, normalised as the committed file is. */
  public JsonNode normalisedPact(List<GoldenInteraction> selected) {
    try {
      return MAPPER.readTree(normalise(written(selected)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Both references on every interaction, as the platform reads them: {@code qits-call} naming
   * this provider and the operation, {@code qits-trigger} naming kind, app and the trigger's own
   * value, every value a non-blank string; one interaction per recorded row.
   */
  public void assertEveryInteractionCarriesBothReferences() {
    JsonNode pact = normalisedPact();
    Assertions.assertEquals(consumer, pact.path("consumer").path("name").asText());
    Assertions.assertEquals(masters.repository(), pact.path("provider").path("name").asText());
    Assertions.assertEquals("4.0", pact.path("metadata").path("pactSpecification").path("version").asText());
    JsonNode interactions = pact.path("interactions");
    Assertions.assertEquals(recorded().size(), interactions.size(), "one interaction per recorded row");
    for (JsonNode interaction : interactions) {
      String description = interaction.path("description").asText();
      JsonNode references = interaction.path("comments").path("references");
      JsonNode call = references.path("qits-call");
      assertStrings(description, call, 2);
      Assertions.assertEquals(masters.repository(), call.path("app").asText(), description);
      JsonNode trigger = references.path("qits-trigger");
      assertStrings(description, trigger, 3);
      Assertions.assertTrue(trigger.has("kind") && trigger.has("app"), description + ": trigger kind and app");
    }
  }

  private static void assertStrings(String description, JsonNode group, int size) {
    Assertions.assertTrue(group.isObject(), description + ": reference group missing");
    Assertions.assertEquals(size, group.size(), description + ": " + group + " holds other keys");
    group.properties().forEach(e -> Assertions.assertTrue(
        e.getValue().isTextual() && !e.getValue().asText().isBlank(),
        description + ": " + e.getKey() + " must be a non-blank string, got " + e.getValue()));
  }

  /** The pact as pact-jvm's own writer serialises it. */
  private String written(List<GoldenInteraction> selected) {
    StringWriter out = new StringWriter();
    try (PrintWriter writer = new PrintWriter(out)) {
      DefaultPactWriter.INSTANCE.writePact(pact(selected), writer, PactSpecVersion.V4);
    }
    return out.toString();
  }

  /**
   * Interactions sorted by description then provider state, pact-jvm's own version stripped from
   * {@code metadata} (a library bump is not a contract change, and a changed file is what
   * republishes the pacts jar), 2-space indentation and one trailing newline.
   */
  public static String normalise(String raw) throws IOException {
    ObjectNode pact = (ObjectNode) MAPPER.readTree(raw);
    if (pact.path("metadata") instanceof ObjectNode meta) {
      meta.remove("pact-jvm");
    }
    if (pact.path("interactions") instanceof ArrayNode interactions) {
      List<JsonNode> sorted = new ArrayList<>();
      interactions.forEach(sorted::add);
      sorted.sort(Comparator.comparing((JsonNode i) -> i.path("description").asText())
          .thenComparing(i -> i.path("providerStates").path(0).path("name").asText()));
      interactions.removeAll();
      sorted.forEach(interactions::add);
    }
    StringBuilder out = new StringBuilder();
    print(pact, "", out);
    return out.append('\n').toString();
  }

  /** {@code JSON.stringify(value, null, 2)}: no space before a colon, empty containers as {} / []. */
  private static void print(JsonNode node, String indent, StringBuilder out) throws IOException {
    String inner = indent + "  ";
    if (node.isObject()) {
      if (node.isEmpty()) {
        out.append("{}");
        return;
      }
      out.append("{\n");
      var fields = node.properties().iterator();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        out.append(inner).append(MAPPER.writeValueAsString(field.getKey())).append(": ");
        print(field.getValue(), inner, out);
        out.append(fields.hasNext() ? ",\n" : "\n");
      }
      out.append(indent).append('}');
    } else if (node.isArray()) {
      if (node.isEmpty()) {
        out.append("[]");
        return;
      }
      out.append("[\n");
      for (int i = 0; i < node.size(); i++) {
        out.append(inner);
        print(node.get(i), inner, out);
        out.append(i < node.size() - 1 ? ",\n" : "\n");
      }
      out.append(indent).append(']');
    } else {
      out.append(MAPPER.writeValueAsString(node));
    }
  }

  /**
   * Where the committed pact files live: {@code -Dqits.pacts.dir} when set, else {@code pacts/} in
   * the repository root — the topmost directory of the unbroken chain of {@code pom.xml} directories
   * above the working directory, so it does not matter which module surefire runs in.
   */
  public static Path pactsDirectory() {
    String override = System.getProperty(PACTS_DIR_PROPERTY);
    if (override != null && !override.isBlank()) {
      return Path.of(override);
    }
    Path dir = Path.of("").toAbsolutePath();
    if (!Files.isRegularFile(dir.resolve("pom.xml"))) {
      throw new IllegalStateException("no pom.xml in " + dir + "; set -D" + PACTS_DIR_PROPERTY);
    }
    while (dir.getParent() != null && Files.isRegularFile(dir.getParent().resolve("pom.xml"))) {
      dir = dir.getParent();
    }
    return dir.resolve("pacts");
  }
}
