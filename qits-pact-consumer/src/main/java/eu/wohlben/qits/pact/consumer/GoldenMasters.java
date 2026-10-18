package eu.wohlben.qits.pact.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>One provider's recorded answers, read off the test classpath</b> (epic qits-546, ticket
 * qits-1149).
 *
 * <p>A provider records what it answers per provider state — {@code golden-masters/index.json}
 * plus one JSON per (state, operation) — and publishes the tree as {@code
 * eu.wohlben.qits:<app>-golden-masters}. A consumer pins that jar in test scope. Every such jar
 * puts its tree at the same {@code golden-masters/}, so several providers on one classpath are told
 * apart by the index's {@code provider} field, and each recording is read beside its own index:
 * no jar shadows another.
 *
 * <p>The jar is looked up on first use, not on construction, so a consumer may declare a provider
 * whose golden masters are not published yet: {@link #recorded} is then false for every row, and
 * {@link #operation} fails with {@link ProviderStateMissing}.
 */
public final class GoldenMasters {

  /** Where every golden-masters jar puts its tree on the classpath. */
  public static final String ROOT = "golden-masters/";

  private static final Pattern PARAM = Pattern.compile("\\{([A-Za-z0-9_]+)}");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String root;
  private final String repository;
  private final String application;

  private volatile Located located;

  private record Located(JsonNode index, String base) {}

  private GoldenMasters(String root, String repository, String application) {
    this.root = root.endsWith("/") ? root : root + "/";
    this.repository = repository;
    this.application = application;
  }

  /**
   * One provider's golden masters.
   *
   * @param repository the provider as a pact names it: the repository ({@code qits-idp-service})
   * @param application the provider as its index names it: the application ({@code qits-idp})
   */
  public static GoldenMasters of(String repository, String application) {
    return new GoldenMasters(ROOT, repository, application);
  }

  /** {@link #of} under another classpath root: for tests against fixture recordings. */
  public static GoldenMasters at(String root, String repository, String application) {
    return new GoldenMasters(root, repository, application);
  }

  /** The provider's repository name, as the pact and {@code qits-call.app} name it. */
  public String repository() {
    return repository;
  }

  /** The provider's application name, as its index names it. */
  public String application() {
    return application;
  }

  // --- the index ---------------------------------------------------------------------------------

  /**
   * One recorded (state, operation), as the index describes it.
   *
   * @param path the route alone, each {@code {param}} a state param; a legacy recording's query is
   *     moved out of it into {@code query}
   * @param query the recorded query, each name with its values in order; empty for none
   * @param contentType the request body's media type; null when the index names none
   * @param body the request body: JSON as recorded, or a text node for a non-JSON body (a form);
   *     null for an operation that takes none, or one recorded as {@code binaryBody}
   * @param binaryBody a binary request body ({@code bodyBase64} in the index, e.g. a gzipped
   *     bundle), decoded; null unless the recording carries one. Sent byte for byte under {@code
   *     contentType}
   * @param responseHeaders the response headers the provider recorded, by name
   */
  public record Operation(
      String state,
      Map<String, String> params,
      String operationId,
      String method,
      String path,
      Map<String, List<String>> query,
      String contentType,
      JsonNode body,
      byte[] binaryBody,
      int status,
      String file,
      Set<String> ids,
      Set<String> instants,
      Set<String> strings,
      Set<String> keys,
      String listFilteredTo,
      Map<String, String> responseHeaders) {

    /** The path with every {@code {param}} replaced by the state's frozen example. */
    public String examplePath() {
      return substitute(path, params::get);
    }

    /** The path as a provider-state expression: {@code {param}} becomes {@code ${param}}. */
    public String expressionPath() {
      return substitute(path, name -> "${" + name + "}");
    }

    /** Whether {@code template} names a state param. */
    public boolean hasParam(String template) {
      return PARAM.matcher(template).find();
    }

    /** {@code template} with each {@code {param}} replaced by {@code value.apply(param)}. */
    public String substitute(String template, Function<String, String> value) {
      Matcher m = PARAM.matcher(template);
      StringBuilder out = new StringBuilder();
      while (m.find()) {
        String name = m.group(1);
        if (!params.containsKey(name)) {
          throw new IllegalStateException(
              "golden master " + state + "/" + operationId + ": " + template + " names {" + name
                  + "}, which the state's params do not hold");
        }
        m.appendReplacement(out, Matcher.quoteReplacement(value.apply(name)));
      }
      m.appendTail(out);
      return out.toString();
    }

    /** {@code template} with each {@code {param}} replaced by the state's frozen example. */
    public String example(String template) {
      return substitute(template, params::get);
    }

    /** {@code template} as a provider-state expression: {@code {param}} becomes {@code ${param}}. */
    public String expression(String template) {
      return substitute(template, name -> "${" + name + "}");
    }

    /** A recorded response header, the name compared without case; null when not recorded. */
    public String responseHeader(String name) {
      for (Map.Entry<String, String> header : responseHeaders.entrySet()) {
        if (header.getKey().equalsIgnoreCase(name)) {
          return header.getValue();
        }
      }
      return null;
    }
  }

  /** Whether the provider recorded {@code operationId} in {@code state}. */
  public boolean recorded(String state, String operationId) {
    Located found = locate(false);
    return found != null && findOperation(found.index(), state, operationId) != null;
  }

  /** The provider state's frozen example params ({@code clientId}, {@code authorization}, …). */
  public Map<String, String> params(String state) {
    JsonNode node = stateNode(locate(true).index(), state);
    if (node == null) {
      throw new ProviderStateMissing(repository, state, "any operation", application + " records no such state");
    }
    return params(node);
  }

  /**
   * The index entry for one (state, operation).
   *
   * @throws ProviderStateMissing when the provider's golden masters are not on the classpath, or
   *     do not hold the state, or hold it without the operation
   */
  public Operation operation(String state, String operationId) {
    Located found = locate(false);
    if (found == null) {
      throw new ProviderStateMissing(repository, state, operationId,
          "no golden-masters/index.json of " + application + " on the test classpath; is eu.wohlben.qits:"
              + application + "-golden-masters a test dependency?");
    }
    JsonNode stateNode = stateNode(found.index(), state);
    if (stateNode == null) {
      throw new ProviderStateMissing(repository, state, operationId, application + " records no such state");
    }
    JsonNode op = findOperation(found.index(), state, operationId);
    if (op == null) {
      throw new ProviderStateMissing(repository, state, operationId,
          application + " records the state without the operation");
    }
    return toOperation(state, params(stateNode), op);
  }

  /** The recorded answer for one (state, operation), as the jar carries it. */
  public String body(String state, String operationId) {
    Operation op = operation(state, operationId);
    String base = locate(true).base();
    try (InputStream in = URI.create(base + op.file()).toURL().openStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(application + "'s golden master " + op.file() + " cannot be read", e);
    }
  }

  /** {@link #body}, parsed: a fresh tree each call, so a caller may edit it. */
  public JsonNode json(String state, String operationId) {
    try {
      return MAPPER.readTree(body(state, operationId));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // --- reading the jar ---------------------------------------------------------------------------

  private static Operation toOperation(String state, Map<String, String> params, JsonNode op) {
    JsonNode frozen = op.path("frozen");
    JsonNode filtered = frozen.path("listFilteredTo");
    String path = op.path("path").asText();
    Map<String, List<String>> query = new LinkedHashMap<>();
    int at = path.indexOf('?');
    if (at >= 0) {
      // A recording made before the query was its own object: the same query, moved out.
      for (String pair : path.substring(at + 1).split("&")) {
        if (pair.isEmpty()) {
          continue;
        }
        int eq = pair.indexOf('=');
        String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
        String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
        query.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
      }
      path = path.substring(0, at);
    }
    op.path("query").properties().forEach(e -> {
      List<String> values = query.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
      if (e.getValue().isArray()) {
        e.getValue().forEach(v -> values.add(v.asText()));
      } else {
        values.add(e.getValue().asText());
      }
    });
    query.replaceAll((k, v) -> List.copyOf(v));
    JsonNode body = op.get("body");
    JsonNode bodyBase64 = op.get("bodyBase64");
    JsonNode contentType = op.get("contentType");
    Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    JsonNode recordedHeaders = op.has("responseHeaders") ? op.get("responseHeaders") : op.path("headers");
    recordedHeaders.properties().forEach(e -> headers.put(e.getKey(), e.getValue().asText()));
    return new Operation(
        state,
        params,
        op.path("operationId").asText(),
        op.path("method").asText(),
        path,
        Collections.unmodifiableMap(query),
        contentType == null || contentType.isNull() ? null : contentType.asText(),
        body == null || body.isNull() ? null : body,
        bodyBase64 == null || bodyBase64.isNull() ? null : Base64.getDecoder().decode(bodyBase64.asText()),
        op.path("status").asInt(),
        op.path("file").asText(),
        idsOnly(frozen),
        strings(frozen.path("instants")),
        strings(frozen.path("strings")),
        strings(frozen.path("keys")),
        filtered.isTextual() ? filtered.asText() : null,
        Collections.unmodifiableMap(headers));
  }

  private static Map<String, String> params(JsonNode stateNode) {
    Map<String, String> params = new LinkedHashMap<>();
    stateNode.path("params").properties().forEach(e -> params.put(e.getKey(), e.getValue().asText()));
    return Collections.unmodifiableMap(params);
  }

  private static JsonNode stateNode(JsonNode index, String state) {
    for (JsonNode node : index.path("states")) {
      if (state.equals(node.path("name").asText())) {
        return node;
      }
    }
    return null;
  }

  private static JsonNode findOperation(JsonNode index, String state, String operationId) {
    JsonNode stateNode = stateNode(index, state);
    if (stateNode == null) {
      return null;
    }
    for (JsonNode op : stateNode.path("operations")) {
      if (operationId.equals(op.path("operationId").asText())) {
        return op;
      }
    }
    return null;
  }

  /** This provider's index; null (or a failure, if {@code required}) when no jar on the classpath is its. */
  private Located locate(boolean required) {
    Located found = located;
    if (found == null) {
      synchronized (this) {
        if (located == null) {
          located = find();
        }
        found = located;
      }
    }
    if (found == NONE && required) {
      throw new IllegalStateException("no " + root + "index.json on the test classpath is " + application
          + "'s — is eu.wohlben.qits:" + application + "-golden-masters a test dependency?");
    }
    return found == NONE ? null : found;
  }

  private static final Located NONE = new Located(null, null);

  private Located find() {
    List<String> seen = new ArrayList<>();
    try {
      Enumeration<URL> found = loader().getResources(root + "index.json");
      while (found.hasMoreElements()) {
        URL url = found.nextElement();
        JsonNode index;
        try (InputStream in = url.openStream()) {
          index = MAPPER.readTree(in);
        }
        String provider = index.path("provider").asText();
        seen.add(provider);
        if (!application.equals(provider)) {
          continue;
        }
        if (index.path("formatVersion").asInt() != 1) {
          throw new IllegalStateException(url + " is formatVersion " + index.path("formatVersion")
              + "; qits-pact-consumer reads formatVersion 1");
        }
        String text = url.toString();
        return new Located(index, text.substring(0, text.length() - "index.json".length()));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return NONE;
  }

  private static ClassLoader loader() {
    ClassLoader context = Thread.currentThread().getContextClassLoader();
    return context != null ? context : GoldenMasters.class.getClassLoader();
  }

  /**
   * {@code frozen.ids} without the paths {@code frozen.strings} lists too: the recorder lists a path
   * by what each value at it was, so an array holding a whole UUID in some elements and a UUID
   * inside a longer string in others lands in both, and only a type match holds every element.
   */
  private static Set<String> idsOnly(JsonNode frozen) {
    Set<String> ids = new LinkedHashSet<>(strings(frozen.path("ids")));
    ids.removeAll(strings(frozen.path("strings")));
    return Collections.unmodifiableSet(ids);
  }

  private static Set<String> strings(JsonNode array) {
    Set<String> out = new LinkedHashSet<>();
    array.forEach(e -> out.add(e.asText()));
    return Collections.unmodifiableSet(out);
  }
}
