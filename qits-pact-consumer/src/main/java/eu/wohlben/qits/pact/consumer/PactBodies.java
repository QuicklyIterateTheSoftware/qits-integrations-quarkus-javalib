package eu.wohlben.qits.pact.consumer;

import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.consumer.dsl.PactDslJsonArray;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslJsonRootValue;
import au.com.dius.pact.core.model.matchingrules.NullMatcher;
import au.com.dius.pact.core.model.matchingrules.RegexMatcher;
import au.com.dius.pact.core.model.matchingrules.TypeMatcher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.pact.consumer.GoldenMasters.Operation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>From a recording to pact-jvm bodies</b>: the request exactly as recorded, the answer cut down
 * to what the consumer reads and matched by type.
 *
 * <p><b>The frozen lists are read from the index, never inferred from a value's shape.</b>
 *
 * <ul>
 *   <li>{@code frozen.ids}: a UUID regex — unless {@code frozen.strings} lists the path too, which
 *       makes it a string (some values there are not a bare UUID);
 *   <li>{@code frozen.instants}: an ISO-8601 regex ({@link #ISO_INSTANT});
 *   <li>{@code frozen.strings} and every other leaf: a type match ({@code type OR null} where some
 *       recorded element held null) — a number recorded as an integer by {@code number}, one recorded
 *       with a fraction or an exponent by {@code type}, which accepts {@code 1.5E8} where pact-jvm's
 *       {@code number} does not;
 *   <li>{@code frozen.keys}: an object keyed by a frozen value, matched with {@code eachKeyLike};
 *       the paths beneath it name the key {@code *};
 *   <li>{@code frozen.listFilteredTo}: the array the recorder reduced to the state's own entities,
 *       {@code minArrayLike(recorded length)}: "contains", never "equals".
 * </ul>
 *
 * <p>Every other non-empty array is {@code minMaxArrayLike(n, n)}, its template the merge of every
 * recorded element. An array beneath an array, whose length differs between the outer elements, is
 * {@code minMaxArrayLike(shortest, longest)}: empty in one element, it is {@code min 0}. A leaf
 * null in some elements is {@code type OR null} at any depth, as the merge recurses. Elements that disagree on which keys they carry are matched element by element
 * instead, each in its place: pact requires every key of a template on every element, and absent
 * is not null. An array named in {@link GoldenInteraction#anyLength} is matched with no length. A
 * leaf named in {@link GoldenInteraction#exact} is the recorded value with no matching rule, so the
 * verifier compares it as equal.
 *
 * <p><b>A request body is the recorded one, exactly.</b> A value recorded as {@code "{param}"} is
 * the state's example here and a provider-state expression for the verifier. A member name
 * recorded that way is the example only: pact-jvm 4.6 generates values, never keys.
 */
final class PactBodies {

  /** An ISO-8601 timestamp, any fraction length, Z or a numeric offset. */
  static final String ISO_INSTANT =
      "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?(Z|[+-]\\d{2}:?\\d{2})$";

  static final String UUID_REGEX =
      "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final Pattern SEGMENT = Pattern.compile("^([^\\[\\]]*)((?:\\[\\])*)$");

  private PactBodies() {}

  // --- consumes ----------------------------------------------------------------------------------

  /**
   * The recorded {@code body} cut down to the paths of {@code consumes}, or null when it names none.
   *
   * @throws IllegalStateException a path the recording does not hold, naming state, operation and
   *     path
   */
  static JsonNode consumed(JsonNode body, List<String> consumes, Operation op) {
    if (consumes.isEmpty()) {
      return null;
    }
    Pick root = new Pick();
    for (String path : consumes) {
      List<String> walk = steps(op.example(path));
      if (!holds(body, walk)) {
        throw new IllegalStateException("golden master " + op.state() + "/" + op.operationId()
            + ": consumes '" + path + "', which the recorded body does not hold");
      }
      Pick node = root;
      for (String step : walk) {
        if (step.equals("[]")) {
          node.elements = node.elements == null ? new Pick() : node.elements;
          node = node.elements;
        } else {
          node = node.keys.computeIfAbsent(step, k -> new Pick());
        }
      }
      // `entries[]` reads the elements, not their fields: only a path ending in a key (or `$`) reads it whole.
      if (walk.isEmpty() || !walk.getLast().equals("[]")) {
        node.whole = true;
      }
    }
    return project(body, root);
  }

  /** Whether the recording holds {@code path}: in at least one element where it crosses an array. */
  static boolean holds(JsonNode body, String path, Operation op) {
    return holds(body, steps(op.example(path)));
  }

  /** Which keys to keep, and what to keep in an array's elements. */
  private static final class Pick {
    boolean whole;
    final Map<String, Pick> keys = new LinkedHashMap<>();
    Pick elements;
  }

  /**
   * One path's steps: a key, or {@code []} for the elements of an array. Accepts the dotted form
   * ({@code entries[].id}) and JSONPath ({@code $.entries[*].id}); {@code $} alone is no steps.
   */
  static List<String> steps(String path) {
    String p = path.replace("[*]", "[]");
    if (p.equals("$")) {
      return List.of();
    }
    if (p.startsWith("$.")) {
      p = p.substring(2);
    } else if (p.startsWith("$[")) {
      p = p.substring(1);
    }
    List<String> out = new ArrayList<>();
    for (String segment : p.split("\\.", -1)) {
      Matcher m = SEGMENT.matcher(segment);
      if (!m.matches() || (m.group(1).isEmpty() && m.group(2).isEmpty())) {
        throw new IllegalArgumentException("'" + path + "' is not a path");
      }
      if (!m.group(1).isEmpty()) {
        out.add(m.group(1));
      }
      for (int i = 0; i < m.group(2).length() / 2; i++) {
        out.add("[]");
      }
    }
    return out;
  }

  private static boolean holds(JsonNode value, List<String> walk) {
    if (walk.isEmpty()) {
      return true;
    }
    String step = walk.getFirst();
    List<String> rest = walk.subList(1, walk.size());
    if (step.equals("[]")) {
      if (!value.isArray()) {
        return false;
      }
      // An empty array holds every path below it: there is nothing recorded there to bind.
      if (value.isEmpty()) {
        return true;
      }
      for (JsonNode element : value) {
        if (holds(element, rest)) {
          return true;
        }
      }
      return false;
    }
    return value.isObject() && value.has(step) && holds(value.get(step), rest);
  }

  /** {@code value} with only what {@code node} keeps. An object element read without fields becomes {@code {}}. */
  private static JsonNode project(JsonNode value, Pick node) {
    if (node.whole || value.isNull()) {
      return value.deepCopy();
    }
    if (value.isArray()) {
      if (node.elements == null) {
        return value.deepCopy();
      }
      var out = MAPPER.createArrayNode();
      value.forEach(v -> out.add(project(v, node.elements)));
      return out;
    }
    if (value.isObject()) {
      var out = MAPPER.createObjectNode();
      node.keys.forEach((key, child) -> {
        if (value.has(key)) {
          out.set(key, project(value.get(key), child));
        }
      });
      return out;
    }
    return value.deepCopy();
  }

  // --- the answer --------------------------------------------------------------------------------

  /** What the answer is built against: the operation, and the arrays matched with no length. */
  private record Context(Operation op, List<Pattern> anyLength, Map<Pattern, Shape> templates, Set<String> exact,
      Set<String> bound) {

    /** The merged template of the arrays at {@code path}, when {@code path} is one of {@code anyLength}. */
    Shape anyLengthTemplate(String path) {
      for (Pattern pattern : anyLength) {
        if (pattern.matcher(path).matches()) {
          Shape template = templates.get(pattern);
          if (template == null) {
            throw unsupported(op, path, "an array with no length, and no element recorded at "
                + pattern.pattern() + " to take its template from");
          }
          return template;
        }
      }
      return null;
    }
  }

  /**
   * The answer's body as a matcher tree: {@code recorded} (already cut to what is consumed), an
   * object or a top-level array.
   */
  static DslPart responseBody(Operation op, JsonNode recorded, List<String> anyLength) {
    return responseBody(op, recorded, anyLength, List.of());
  }

  /**
   * {@link #responseBody(Operation, JsonNode, List)}, the leaves at {@code exact} bound to their
   * recorded value with no matching rule.
   */
  static DslPart responseBody(Operation op, JsonNode recorded, List<String> anyLength, List<String> exact) {
    List<Pattern> patterns = anyLength.stream().map(PactBodies::pattern).toList();
    Map<Pattern, Shape> templates = new LinkedHashMap<>();
    for (Pattern pattern : patterns) {
      List<JsonNode> elements = new ArrayList<>();
      collect(recorded, "$", pattern, elements);
      Shape merged = null;
      for (JsonNode element : elements) {
        merged = merged == null ? Shape.of(element) : Shape.merge(merged, Shape.of(element));
      }
      if (merged != null) {
        templates.put(pattern, merged);
      }
    }
    Set<String> exactPaths = new HashSet<>();
    for (String path : exact) {
      exactPaths.add(internal(steps(op.example(path))));
    }
    Context ctx = new Context(op, patterns, templates, exactPaths, new HashSet<>());
    if (recorded.isObject()) {
      PactDslJsonBody root = new PactDslJsonBody();
      fillObject(root, Shape.of(recorded), "$", ctx);
      Set<String> unbound = new HashSet<>(exactPaths);
      unbound.removeAll(ctx.bound());
      if (!unbound.isEmpty()) {
        throw new IllegalStateException("golden master " + op.state() + "/" + op.operationId() + ": exact names "
            + unbound + ", beneath a keyed object, where one recorded value cannot stand for every key");
      }
      return root;
    }
    if (!exactPaths.isEmpty()) {
      throw unsupported(op, "$", "an array with exact values beneath it");
    }
    if (recorded.isArray()) {
      return rootArray(Shape.of(recorded), ctx);
    }
    throw unsupported(op, "$", "a " + recorded.getNodeType() + " body; only an object or an array is");
  }

  /** {@code steps} as the internal path the builder walks: {@code $.a.b}, {@code $.a[*].b}. */
  static String internal(List<String> steps) {
    StringBuilder out = new StringBuilder("$");
    steps.forEach(step -> out.append(step.equals("[]") ? "[*]" : "." + step));
    return out.toString();
  }

  /** {@code path} in the internal form ({@code $.a[*].b}, {@code *} for any key) as an anchored regex. */
  static Pattern pattern(String path) {
    StringBuilder regex = new StringBuilder("^\\$");
    for (String step : steps(path)) {
      if (step.equals("[]")) {
        regex.append("\\[\\*\\]");
      } else if (step.equals("*")) {
        regex.append("\\.[^.\\[]+");
      } else {
        regex.append("\\.").append(Pattern.quote(step));
      }
    }
    return Pattern.compile(regex.append('$').toString());
  }

  /** Every element of every array in {@code node} whose path matches {@code pattern}. */
  private static void collect(JsonNode node, String path, Pattern pattern, List<JsonNode> out) {
    if (node.isArray()) {
      if (pattern.matcher(path).matches()) {
        node.forEach(out::add);
      }
      node.forEach(e -> collect(e, path + "[*]", pattern, out));
    } else if (node.isObject()) {
      node.properties().forEach(e -> collect(e.getValue(), path + "." + e.getKey(), pattern, out));
    }
  }

  private static DslPart rootArray(Shape array, Context ctx) {
    Operation op = ctx.op();
    Shape template = ctx.anyLengthTemplate("$");
    if (template != null) {
      return rootTemplate(template, 0, 1, null, ctx);
    }
    int n = array.length;
    if (n == 0) {
      return new PactDslJsonArray();
    }
    if (!array.mergeable || !expressible(array.element)) {
      PactDslJsonArray positional = new PactDslJsonArray();
      positional(positional, array.items, "$[*]", ctx);
      return positional;
    }
    boolean filtered = "$".equals(op.listFilteredTo());
    return rootTemplate(array.element, n, n, filtered ? null : n, ctx);
  }

  /** A top-level array of {@code element}: at least {@code min}, at most {@code max} (null: no limit). */
  private static DslPart rootTemplate(Shape element, int min, int examples, Integer max, Context ctx) {
    switch (element.kind) {
      case OBJECT -> {
        PactDslJsonBody template = max == null
            ? PactDslJsonArray.arrayMinLike(min, examples)
            : PactDslJsonArray.arrayMinMaxLike(min, max, examples);
        fillObject(template, element, "$[*]", ctx);
        return template.closeObject();
      }
      case LEAF -> {
        PactDslJsonRootValue value = rootLeaf(element, "$[*]", ctx.op());
        return max == null
            ? PactDslJsonArray.arrayMinLike(min, examples, value)
            : PactDslJsonArray.arrayMinMaxLike(min, max, examples, value);
      }
      default -> throw unsupported(ctx.op(), "$[*]", "an array of " + element.kind);
    }
  }

  private static void fillObject(PactDslJsonBody target, Shape shape, String path, Context ctx) {
    Operation op = ctx.op();
    boolean keyed = op.keys().contains(path);
    for (Map.Entry<String, Shape> field : shape.fields.entrySet()) {
      String name = field.getKey();
      Shape child = field.getValue();
      if (keyed) {
        // The key is a frozen value: any key, every value against one template.
        if (child.kind != Shape.Kind.OBJECT || child.nullable) {
          throw unsupported(op, path + ".*", "a keyed " + child.kind + "; only object values are");
        }
        PactDslJsonBody value = target.eachKeyLike(name);
        fillObject(value, child, path + ".*", ctx);
        value.closeObject();
        continue;
      }
      String childPath = path + "." + name;
      if (ctx.exact().contains(childPath)) {
        ctx.bound().add(childPath);
        if (child.kind != Shape.Kind.NULL) {
          exactLeaf(target, name, child, childPath, op);
          continue;
        }
      }
      switch (child.kind) {
        case NULL -> target.nullValue(name);
        case LEAF -> leaf(target, name, child, childPath, op);
        case OBJECT -> {
          if (child.nullable) {
            throw unsupported(op, childPath, "an object that is null in some elements");
          }
          PactDslJsonBody nested = target.object(name);
          fillObject(nested, child, childPath, ctx);
          nested.closeObject();
        }
        case ARRAY -> array(target, name, child, childPath, ctx);
        default -> throw unsupported(op, childPath, "a " + child.kind);
      }
    }
  }

  /** The recorded value, no matching rule: the verifier compares it as equal. */
  private static void exactLeaf(PactDslJsonBody target, String name, Shape leaf, String path, Operation op) {
    if (leaf.kind != Shape.Kind.LEAF) {
      throw new IllegalStateException("golden master " + op.state() + "/" + op.operationId() + ": exact names "
          + path + ", which holds " + leaf.kind + "; exact binds a single string, number or boolean");
    }
    if (op.ids().contains(path) || op.instants().contains(path)) {
      throw new IllegalStateException("golden master " + op.state() + "/" + op.operationId() + ": exact names "
          + path + ", a frozen value that differs between runs, so it cannot be bound exactly");
    }
    JsonNode example = leaf.example;
    if (example.isTextual()) {
      target.stringValue(name, example.asText());
    } else if (example.isNumber()) {
      target.numberValue(name, example.numberValue());
    } else if (example.isBoolean()) {
      target.booleanValue(name, example.asBoolean());
    } else {
      throw unsupported(op, path, "a " + example.getNodeType() + " leaf");
    }
  }

  private static void leaf(PactDslJsonBody target, String name, Shape leaf, String path, Operation op) {
    JsonNode example = leaf.example;
    if (op.ids().contains(path)) {
      requireText(example, path, op, "ids");
      if (leaf.nullable) {
        target.or(name, example.asText(), new RegexMatcher(UUID_REGEX, example.asText()), NullMatcher.INSTANCE);
      } else {
        target.uuid(name, example.asText());
      }
    } else if (op.instants().contains(path)) {
      requireText(example, path, op, "instants");
      if (leaf.nullable) {
        target.or(name, example.asText(), new RegexMatcher(ISO_INSTANT, example.asText()), NullMatcher.INSTANCE);
      } else {
        target.stringMatcher(name, ISO_INSTANT, example.asText());
      }
    } else if (leaf.nullable) {
      target.or(name, scalar(example), TypeMatcher.INSTANCE, NullMatcher.INSTANCE);
    } else if (example.isTextual()) {
      target.stringType(name, example.asText());
    } else if (example.isNumber() && leaf.fractional) {
      target.and(name, example.numberValue(), TypeMatcher.INSTANCE);
    } else if (example.isNumber()) {
      target.numberType(name, example.numberValue());
    } else if (example.isBoolean()) {
      target.booleanType(name, example.asBoolean());
    } else {
      throw unsupported(op, path, "a " + example.getNodeType() + " leaf");
    }
  }

  private static void array(PactDslJsonBody target, String name, Shape array, String path, Context ctx) {
    Operation op = ctx.op();
    String elementPath = path + "[*]";
    Shape template = ctx.anyLengthTemplate(path);
    if (template != null) {
      arrayTemplate(target, name, template, elementPath, 0, 1, null, ctx);
      return;
    }
    if (array.nullable) {
      throw unsupported(op, path, "an array that is null in some elements");
    }
    int n = array.length;
    if (array.maxLength == 0) {
      // Nothing to build a template from: an empty array with no rule is compared as exactly that.
      target.array(name).closeArray();
      return;
    }
    if (!array.mergeable || !expressible(array.element)) {
      PactDslJsonArray positional = target.array(name);
      positional(positional, array.items, elementPath, ctx);
      positional.closeArray();
      return;
    }
    boolean filtered = path.equals(op.listFilteredTo());
    // Merged from elements of different lengths (an array beneath an array): from the shortest to the
    // longest recorded, so an element whose array is empty or shorter still matches.
    arrayTemplate(target, name, array.element, elementPath, n, Math.max(n, 1),
        filtered ? null : array.maxLength, ctx);
  }

  private static void arrayTemplate(PactDslJsonBody target, String name, Shape element, String elementPath,
      int min, int examples, Integer max, Context ctx) {
    switch (element.kind) {
      case OBJECT -> {
        PactDslJsonBody template = max == null
            ? target.minArrayLike(name, min, examples)
            : target.minMaxArrayLike(name, min, max, examples);
        fillObject(template, element, elementPath, ctx);
        DslPart closed = template.closeObject();
        ((PactDslJsonArray) closed).closeArray();
      }
      case LEAF -> {
        PactDslJsonRootValue value = rootLeaf(element, elementPath, ctx.op());
        if (max == null) {
          target.minArrayLike(name, min, value, examples);
        } else {
          target.minMaxArrayLike(name, min, max, value, examples);
        }
      }
      default -> throw unsupported(ctx.op(), elementPath, "an array of " + element.kind);
    }
  }

  /**
   * Whether one merged template can stand for every element: every key present in every element,
   * nothing that is an object or an array in one element and null in another, and every array
   * beneath mergeable itself.
   */
  private static boolean expressible(Shape shape) {
    return switch (shape.kind) {
      case OBJECT -> shape.fields.values().stream().allMatch(f -> !f.absent
          && !((f.kind == Shape.Kind.OBJECT || f.kind == Shape.Kind.ARRAY) && f.nullable) && expressible(f));
      case ARRAY -> shape.mergeable && (shape.element == null || expressible(shape.element));
      default -> true;
    };
  }

  /** Each element in its place, a leaf by type (or by the index's matcher), as recorded. */
  private static void positional(PactDslJsonArray target, List<Shape> items, String path, Context ctx) {
    Operation op = ctx.op();
    for (Shape item : items) {
      switch (item.kind) {
        case NULL -> target.nullValue();
        case LEAF -> {
          JsonNode example = item.example;
          if (op.ids().contains(path)) {
            requireText(example, path, op, "ids");
            target.uuid(example.asText());
          } else if (op.instants().contains(path)) {
            requireText(example, path, op, "instants");
            target.stringMatcher(ISO_INSTANT, example.asText());
          } else if (example.isTextual()) {
            target.stringType(example.asText());
          } else if (example.isNumber() && item.fractional) {
            target.and(example.numberValue(), TypeMatcher.INSTANCE);
          } else if (example.isNumber()) {
            target.numberType(example.numberValue());
          } else if (example.isBoolean()) {
            target.booleanType(example.asBoolean());
          } else {
            throw unsupported(op, path, "a " + example.getNodeType() + " array element");
          }
        }
        case OBJECT -> {
          PactDslJsonBody object = target.object();
          fillObject(object, item, path, ctx);
          object.closeObject();
        }
        case ARRAY -> {
          PactDslJsonArray inner = target.array();
          positional(inner, item.items, path + "[*]", ctx);
          inner.closeArray();
        }
        default -> throw unsupported(op, path, "a " + item.kind);
      }
    }
  }

  private static PactDslJsonRootValue rootLeaf(Shape leaf, String path, Operation op) {
    if (leaf.nullable) {
      throw unsupported(op, path, "an array holding nulls");
    }
    JsonNode example = leaf.example;
    if (op.ids().contains(path)) {
      requireText(example, path, op, "ids");
      return PactDslJsonRootValue.uuid(example.asText());
    }
    if (op.instants().contains(path)) {
      requireText(example, path, op, "instants");
      return PactDslJsonRootValue.stringMatcher(ISO_INSTANT, example.asText());
    }
    if (example.isTextual()) {
      return PactDslJsonRootValue.stringType(example.asText());
    }
    if (example.isNumber() && leaf.fractional) {
      PactDslJsonRootValue value = new PactDslJsonRootValue();
      value.setValue(example.numberValue());
      value.setMatcher(TypeMatcher.INSTANCE);
      return value;
    }
    if (example.isNumber()) {
      return PactDslJsonRootValue.numberType(example.numberValue());
    }
    if (example.isBoolean()) {
      return PactDslJsonRootValue.booleanType(example.asBoolean());
    }
    throw unsupported(op, path, "a " + example.getNodeType() + " array element");
  }

  private static Object scalar(JsonNode example) {
    if (example.isTextual()) {
      return example.asText();
    }
    if (example.isNumber()) {
      return example.numberValue();
    }
    if (example.isBoolean()) {
      return example.asBoolean();
    }
    throw new IllegalStateException("not a scalar: " + example);
  }

  private static void requireText(JsonNode example, String path, Operation op, String list) {
    if (!example.isTextual()) {
      throw new IllegalStateException("golden master " + op.state() + "/" + op.operationId() + ": frozen." + list
          + " names " + path + ", which holds " + example.getNodeType() + ", not a string");
    }
  }

  private static IllegalStateException unsupported(Operation op, String path, String what) {
    return new IllegalStateException("golden master " + op.state() + "/" + op.operationId() + ": " + path + " is "
        + what + ", which qits-pact-consumer cannot express as a pact matcher yet");
  }

  // --- the request body --------------------------------------------------------------------------

  /** The recorded JSON request, values exact, a {@code "{param}"} value a provider-state expression. */
  static DslPart requestBody(Operation op) {
    JsonNode body = op.body();
    if (body.isObject()) {
      PactDslJsonBody root = new PactDslJsonBody();
      requestObject(root, body, op);
      return root;
    }
    if (body.isArray()) {
      PactDslJsonArray root = new PactDslJsonArray();
      requestArray(root, body, op);
      return root;
    }
    throw new IllegalStateException("golden master " + op.state() + "/" + op.operationId()
        + ": a " + body.getNodeType() + " JSON request body");
  }

  private static void requestObject(PactDslJsonBody target, JsonNode recorded, Operation op) {
    for (Map.Entry<String, JsonNode> field : recorded.properties()) {
      String name = op.example(field.getKey());
      JsonNode value = field.getValue();
      if (value.isObject()) {
        PactDslJsonBody nested = target.object(name);
        requestObject(nested, value, op);
        nested.closeObject();
      } else if (value.isArray()) {
        PactDslJsonArray nested = target.array(name);
        requestArray(nested, value, op);
        nested.closeArray();
      } else if (value.isNull()) {
        target.nullValue(name);
      } else if (value.isTextual() && op.hasParam(value.asText())) {
        target.valueFromProviderState(name, op.expression(value.asText()), op.example(value.asText()));
      } else if (value.isTextual()) {
        target.stringValue(name, value.asText());
      } else if (value.isNumber()) {
        target.numberValue(name, value.numberValue());
      } else if (value.isBoolean()) {
        target.booleanValue(name, value.asBoolean());
      } else {
        throw new IllegalStateException("golden master " + op.state() + "/" + op.operationId()
            + ": a " + value.getNodeType() + " in the request body");
      }
    }
  }

  private static void requestArray(PactDslJsonArray target, JsonNode recorded, Operation op) {
    for (JsonNode value : recorded) {
      if (value.isObject()) {
        PactDslJsonBody nested = target.object();
        requestObject(nested, value, op);
        nested.closeObject();
      } else if (value.isArray()) {
        PactDslJsonArray nested = target.array();
        requestArray(nested, value, op);
        nested.closeArray();
      } else if (value.isNull()) {
        target.nullValue();
      } else if (value.isTextual() && op.hasParam(value.asText())) {
        target.valueFromProviderState(op.expression(value.asText()), op.example(value.asText()));
      } else if (value.isTextual()) {
        target.stringValue(value.asText());
      } else if (value.isNumber()) {
        target.numberValue(value.numberValue());
      } else if (value.isBoolean()) {
        target.booleanValue(value.asBoolean());
      } else {
        throw new IllegalStateException("golden master " + op.state() + "/" + op.operationId()
            + ": a " + value.getNodeType() + " in the request body");
      }
    }
  }

  // --- the shape of a recording ------------------------------------------------------------------

  /**
   * The structure of a recorded value, an array's elements MERGED into one template: field union, a
   * leaf's first non-null example, and {@code nullable} wherever any element held null (or lacked
   * the field). Which matcher a leaf gets is the index's decision, by path.
   */
  static final class Shape {
    enum Kind {
      NULL,
      LEAF,
      OBJECT,
      ARRAY
    }

    Kind kind;
    boolean nullable;
    /** A merged object field that some element does not carry at all: absent, not null. */
    boolean absent;
    JsonNode example;
    final LinkedHashMap<String, Shape> fields = new LinkedHashMap<>();
    Shape element;
    /** An array's length: the shortest and the longest where elements of an outer array were merged. */
    int length;
    int maxLength;
    /** An array's elements, each its own shape, unmerged: the positional fallback's input. */
    final List<Shape> items = new ArrayList<>();
    /** Whether an array's elements merge into one template at all (no OBJECT beside a LEAF). */
    boolean mergeable = true;
    /**
     * A number recorded with a fraction or an exponent, in any merged element: a type match, as
     * pact-jvm's {@code number} and {@code decimal} refuse an answer in exponent form ({@code 1.5E8},
     * a BigDecimal of negative scale) however correct its JSON.
     */
    boolean fractional;

    static Shape of(JsonNode node) {
      Shape shape = new Shape();
      if (node == null || node.isNull() || node.isMissingNode()) {
        shape.kind = Kind.NULL;
        shape.nullable = true;
      } else if (node.isObject()) {
        shape.kind = Kind.OBJECT;
        node.properties().forEach(e -> shape.fields.put(e.getKey(), of(e.getValue())));
      } else if (node.isArray()) {
        shape.kind = Kind.ARRAY;
        shape.length = node.size();
        shape.maxLength = node.size();
        for (JsonNode e : node) {
          shape.items.add(of(e));
          if (shape.mergeable) {
            try {
              shape.element = shape.element == null ? of(e) : merge(shape.element, of(e));
            } catch (IllegalStateException disagree) {
              shape.mergeable = false;
              shape.element = null;
            }
          }
        }
      } else {
        shape.kind = Kind.LEAF;
        shape.example = node;
        shape.fractional = node.isFloatingPointNumber();
      }
      return shape;
    }

    static Shape merge(Shape a, Shape b) {
      if (a.kind == Kind.NULL) {
        b.nullable = true;
        b.absent |= a.absent;
        return b;
      }
      if (b.kind == Kind.NULL) {
        a.nullable = true;
        a.absent |= b.absent;
        return a;
      }
      if (a.kind != b.kind) {
        throw new IllegalStateException("golden master array elements disagree: " + a.kind + " and " + b.kind);
      }
      a.nullable |= b.nullable;
      a.absent |= b.absent;
      a.fractional |= b.fractional;
      switch (a.kind) {
        case OBJECT -> {
          List<String> keys = new ArrayList<>(a.fields.keySet());
          for (String key : b.fields.keySet()) {
            if (!keys.contains(key)) {
              keys.add(key);
            }
          }
          LinkedHashMap<String, Shape> merged = new LinkedHashMap<>();
          for (String key : keys) {
            Shape left = a.fields.get(key);
            Shape right = b.fields.get(key);
            Shape field = left == null ? merge(of(null), right)
                : right == null ? merge(left, of(null)) : merge(left, right);
            field.absent |= left == null || right == null;
            merged.put(key, field);
          }
          a.fields.clear();
          a.fields.putAll(merged);
        }
        case ARRAY -> {
          a.length = Math.min(a.length, b.length);
          a.maxLength = Math.max(a.maxLength, b.maxLength);
          a.mergeable &= b.mergeable;
          a.element = !a.mergeable ? null
              : a.element == null ? b.element : b.element == null ? a.element : merge(a.element, b.element);
        }
        default -> {
          // LEAF: keep a's example; the matcher is a type match, so one example stands for all.
        }
      }
      return a;
    }
  }
}
