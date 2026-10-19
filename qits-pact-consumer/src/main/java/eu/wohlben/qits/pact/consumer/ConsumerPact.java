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
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;

/**
 * <b>Everything one consumer asks one provider</b> (epic qits-546, ticket qits-1149): its rows, the
 * pact they make, the consumer test that runs a row against a pact mock server, and the committed
 * pact file {@code pacts/<consumer>_<provider>.json}.
 *
 * <p><b>A call made through a library</b> (ticket qits-1163) is still pacted by the service that
 * consumes the answer, not by the library: the service is the {@code consumer}, {@link #via}
 * names the library, and the committed file becomes the three-part {@code
 * pacts/<consumer>_<library-repo>_<provider>.json} — see {@link #file}. Every such row is pacted
 * with the version the consuming service <em>currently</em> pins — {@code
 * comments.references.qits-via.version} always names it — so a library bump is not a contract
 * change; only the committed label can lag, and {@link #compareOrWritePactFile} refreshes it in
 * place rather than failing on that alone.
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
  private final Via via;

  private ConsumerPact(String consumer, GoldenMasters masters, List<GoldenInteraction> rows, Via via) {
    this.consumer = Objects.requireNonNull(consumer, "consumer");
    this.masters = Objects.requireNonNull(masters, "masters");
    this.rows = List.copyOf(rows);
    this.via = via;
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
    return new ConsumerPact(consumer, masters, List.of(rows), null);
  }

  /** {@link #of(String, GoldenMasters, GoldenInteraction...)} over a list. */
  public static ConsumerPact of(String consumer, GoldenMasters masters, List<GoldenInteraction> rows) {
    return new ConsumerPact(consumer, masters, rows, null);
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

  /**
   * The same pact, made through {@code via}: a library calls the provider on the consumer's
   * behalf, so the consumer stays {@code consumer} and {@code qits-trigger.app}, but the pact
   * also names the library and the file carries all three parties.
   */
  public ConsumerPact via(Via via) {
    return new ConsumerPact(consumer, masters, rows, via);
  }

  /** The library the calls go through, when this pact is one of those; empty for a direct call. */
  public Optional<Via> via() {
    return Optional.ofNullable(via);
  }

  /**
   * The committed pact's file name: both sides by repository name, plus the library's repository
   * name in the middle when {@link #via} is set — {@code <consumer>_<via-repo>_<provider>.json} —
   * <b>except</b> when the library and the provider share a repository (a client the provider
   * publishes from its own repository, such as {@code qits-containers-client} from {@code
   * qits-containers-service}), where naming it twice would say nothing a two-part name does not
   * already say, so the name stays the plain {@code <consumer>_<provider>.json}.
   */
  public String file() {
    if (via != null && !via.repository().equals(masters.repository())) {
      return consumer + "_" + via.repository() + "_" + masters.repository() + ".json";
    }
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
   * request body (a binary one, recorded as {@code bodyBase64}, byte for byte under its recorded
   * content type); the row's request headers; the recorded status; the response headers and body
   * paths the row reads (by type, or exactly where {@link GoldenInteraction#exact} names them),
   * and nothing else (no body and no {@code Content-Type} when it reads no
   * path; when it does, a {@code Content-Type} matching the recorded media type, see {@link
   * #answerContentType}); and {@code comments.references}, {@code qits-call} and {@code qits-trigger} (and,
   * when {@link #via} is set, {@code qits-via} naming the library — {@code qits-trigger}'s app
   * stays the consumer either way).
   *
   * @throws ProviderStateMissing when the provider did not record the row's state and operation
   */
  public PactBuilder add(PactBuilder builder, GoldenInteraction row) {
    Operation op = masters.operation(row.state(), row.operationId());
    JsonNode read = row.consumes().isEmpty()
        ? null
        : PactBodies.consumed(masters.json(row.state(), row.operationId()), row.consumes(), op);
    for (String path : row.exact()) {
      if (!PactBodies.holds(masters.json(row.state(), row.operationId()), path, op)) {
        throw new IllegalStateException("golden master " + op.state() + "/" + op.operationId()
            + ": exact '" + path + "', which the recorded body does not hold");
      }
    }
    DslPart answer = read == null ? null : PactBodies.responseBody(op, read, row.anyLength(), row.exact());
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
    if (via != null) {
      references.put("qits-via", via.reference());
    }
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
          } else if (values.stream().anyMatch(op::hasParam)) {
            // A repeated parameter: each value its own expression, generated at name[index].
            r.queryParameter(name, values.stream().<Object>map(v -> op.hasParam(v)
                ? Matchers.fromProviderState(op.expression(v), op.example(v)) : v).toList());
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
        if (op.binaryBody() != null) {
          // A binary upload (bodyBase64 in the index): the recorded bytes, under the recorded type.
          String type = op.contentType() == null ? "application/octet-stream" : op.contentType();
          r.header("Content-Type", type);
          r.body(op.binaryBody(), type);
        } else if (body != null && body.isTextual()) {
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
          response.header("Content-Type", answerContentType(op)).body(answer);
        }
        return response;
      });
      // pact-jvm 4.6's DSL has no setter for an arbitrary comment group, but the V4 model's
      // comments map is mutable and written verbatim.
      http.getInteraction().getComments().put("references", Json.toJson(references));
      oneOfEachHeader(http.getInteraction().getRequest().getHeaders());
      return http;
    });
  }

  /**
   * The {@code Content-Type} matcher of a JSON answer: {@code application/json.*} — or, when the
   * recording's own {@code Content-Type} names another media type ({@code
   * application/vnd.cyclonedx+json; version=1.6}), that media type followed by anything, its
   * recorded value the example. The verifier compares the real answer's header against it, so a
   * hard-coded {@code application/json} would refuse a provider that answers what it recorded.
   */
  static Object answerContentType(Operation op) {
    String recorded = op.responseHeader("Content-Type");
    String media = recorded == null ? "" : recorded.split(";", 2)[0].trim();
    if (media.isEmpty() || media.equalsIgnoreCase("application/json")) {
      return Matchers.regexp("application/json.*", "application/json");
    }
    return Matchers.regexp(media.replaceAll("[^A-Za-z0-9/-]", "\\\\$0") + ".*", recorded);
  }

  /**
   * Keeps the first of headers whose names differ only in case: pact-jvm adds its own
   * {@code content-type} beside the {@code Content-Type} set here, and a request must carry one.
   */
  static void oneOfEachHeader(Map<String, List<String>> headers) {
    Set<String> seen = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    headers.keySet().removeIf(name -> !seen.add(name));
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
   *
   * <p><b>A difference confined to {@code comments.references.qits-via.version}</b> (ticket
   * qits-1163) does not fail: every row made through a library is pacted with the version the
   * consuming service <em>currently</em> pins, so a qits-maintenance bump of that library is
   * exactly such a diff, and only the committed label lags until this runs locally. Such a diff
   * instead rewrites the committed file in place — no {@code -Dgolden.update} needed — and prints
   * {@code via version refreshed <old> → <new>}. Any other difference, including one that also
   * touches the version, still fails as before.
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
      String actual = normalise(raw);
      if (GoldenFiles.updating() || !refreshedViaVersion(committed, actual)) {
        GoldenFiles.compareOrWrite(committed, actual);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Whether {@code committed} and {@code actual} differ only in {@code
   * comments.references.qits-via.version} — masked to the same placeholder on both sides before
   * comparing — in which case {@code committed} is rewritten to {@code actual} (the real,
   * unmasked pact) and the refresh is printed. Returns {@code false}, doing nothing, when the
   * files are identical, {@code committed} does not exist yet, or any other difference remains
   * once the version is masked: each of those is left to {@link GoldenFiles#compareOrWrite} to
   * handle as it always has.
   */
  private boolean refreshedViaVersion(Path committed, String actual) throws IOException {
    if (!Files.exists(committed)) {
      return false;
    }
    String committedText = Files.readString(committed);
    if (committedText.equals(actual)) {
      return false;
    }
    JsonNode committedPact = MAPPER.readTree(committedText);
    JsonNode actualPact = MAPPER.readTree(actual);
    if (!maskViaVersions(committedPact).equals(maskViaVersions(actualPact))) {
      return false;
    }
    Files.writeString(committed, actual);
    System.out.println("via version refreshed " + viaVersion(committedPact) + " → " + viaVersion(actualPact));
    return true;
  }

  /** {@code pact}, deep-copied, with every interaction's {@code qits-via.version} set to {@code "*"}. */
  private static JsonNode maskViaVersions(JsonNode pact) {
    JsonNode masked = pact.deepCopy();
    masked.path("interactions").forEach(interaction -> {
      if (interaction.path("comments").path("references").path("qits-via") instanceof ObjectNode via) {
        via.put("version", "*");
      }
    });
    return masked;
  }

  /** The first {@code qits-via.version} found in {@code pact}'s interactions; {@code "?"} when none carry one. */
  private static String viaVersion(JsonNode pact) {
    for (JsonNode interaction : pact.path("interactions")) {
      JsonNode version = interaction.path("comments").path("references").path("qits-via").path("version");
      if (version.isTextual()) {
        return version.asText();
      }
    }
    return "?";
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
   * value, every value a non-blank string; one interaction per recorded row. When {@link #via}
   * is set, every interaction also carries {@code qits-via} naming the library (repository,
   * artifact, version) and {@code qits-trigger.app} still names the consumer; when it is not,
   * no interaction carries {@code qits-via}.
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
      if (via != null) {
        Assertions.assertEquals(consumer, trigger.path("app").asText(), description + ": trigger app stays the consumer");
        JsonNode viaRef = references.path("qits-via");
        assertStrings(description, viaRef, 3);
        Assertions.assertEquals(via.repository(), viaRef.path("repository").asText(), description);
        Assertions.assertEquals(via.artifact(), viaRef.path("artifact").asText(), description);
        Assertions.assertEquals(via.version(), viaRef.path("version").asText(), description);
      } else {
        Assertions.assertFalse(references.has("qits-via"), description + ": no via, but qits-via is present");
      }
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
