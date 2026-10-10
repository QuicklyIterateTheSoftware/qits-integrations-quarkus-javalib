package eu.wohlben.qits.pact.consumer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * <b>One row of a consumer pact</b>: which recorded answer (provider state and operation), what
 * caused the call, and what the consumer reads of the answer. The request itself comes from the
 * recording, so a row does not repeat it.
 *
 * <pre>{@code
 * GoldenInteraction.of(Trigger.operation("getGcPlan"), "deployments that pin images", "listDeploymentPins")
 *     .consumes("pins[].applicationName", "pins[].shas")
 * }</pre>
 *
 * @param consumes the body paths the consumer reads, and so the only ones the pact binds. Keys
 *     are dot-separated; {@code []} after a key steps into the elements of an array; {@code
 *     entries[]} alone reads the elements without their fields; a leading {@code []} steps into a
 *     top-level array; {@code {param}} in a key is the state's frozen example. JSONPath spelling
 *     ({@code $.pins[*].image}) is read the same way, and {@code $} alone is the whole body.
 *     Empty: the consumer reads the status only, and the pact has no body.
 * @param requestHeaders headers the consumer sends that the recording does not hold, such as
 *     {@code Authorization}. A {@code {param}} in a value is a provider-state expression, so the
 *     verifier sends what the provider state returned.
 * @param readsHeaders response headers the consumer reads ({@code Git-Commit-Sha}); each must be
 *     in the recording's {@code responseHeaders}.
 * @param anyLength arrays whose length the pact must not pin (a provider that is about to add
 *     elements). Same path spelling as {@code consumes}; {@code *} stands for any one object key.
 *     Such an array is matched by type with no length, its template merged from every element
 *     the recording holds at any path the pattern names.
 * @param exact leaves whose recorded value the pact binds exactly, with no matching rule ({@code
 *     issuer}, {@code error}). Same path spelling as {@code consumes}; each must lie at or beneath
 *     a consumed path, and must not cross an array or a keyed object, where one recorded value
 *     cannot stand for every element.
 */
public record GoldenInteraction(
    Trigger trigger,
    String state,
    String operationId,
    List<String> consumes,
    Map<String, String> requestHeaders,
    List<String> readsHeaders,
    List<String> anyLength,
    List<String> exact) {

  public GoldenInteraction {
    Objects.requireNonNull(trigger, "trigger: every interaction names the entry point that makes it");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(operationId, "operationId");
    consumes = List.copyOf(consumes);
    requestHeaders = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(requestHeaders));
    readsHeaders = List.copyOf(readsHeaders);
    anyLength = List.copyOf(anyLength);
    exact = List.copyOf(exact);
    for (String path : exact) {
      requireExactConsumed(path, consumes);
    }
  }

  /** Refuses an exact path that is not a single leaf at or beneath a consumed path. */
  private static void requireExactConsumed(String path, List<String> consumes) {
    List<String> walk = PactBodies.steps(path);
    if (walk.isEmpty()) {
      throw new IllegalArgumentException("exact '" + path + "' names the whole body; exact binds one value");
    }
    if (walk.contains("[]") || walk.contains("*")) {
      throw new IllegalArgumentException("exact '" + path
          + "' crosses an array or a keyed object, where one recorded value cannot stand for every element");
    }
    for (String consumed : consumes) {
      List<String> read = PactBodies.steps(consumed);
      boolean readsWhole = read.isEmpty() || !read.getLast().equals("[]");
      if (readsWhole && read.size() <= walk.size() && walk.subList(0, read.size()).equals(read)) {
        return;
      }
    }
    throw new IllegalArgumentException("exact '" + path + "' is not consumed; consume it (or a path above it) first");
  }

  /** A row that reads the status only, until {@link #consumes} names more. */
  public static GoldenInteraction of(Trigger trigger, String state, String operationId) {
    return new GoldenInteraction(trigger, state, operationId, List.of(), Map.of(), List.of(), List.of(), List.of());
  }

  /** The same row, reading {@code paths} of the answer as well. */
  public GoldenInteraction consumes(String... paths) {
    return consumes(List.of(paths));
  }

  /** The same row, reading {@code paths} of the answer as well. */
  public GoldenInteraction consumes(List<String> paths) {
    List<String> all = new ArrayList<>(consumes);
    all.addAll(paths);
    return new GoldenInteraction(trigger, state, operationId, all, requestHeaders, readsHeaders, anyLength, exact);
  }

  /** The same row, sending {@code name: value}; a {@code {param}} in the value comes from the state. */
  public GoldenInteraction header(String name, String value) {
    Map<String, String> all = new LinkedHashMap<>(requestHeaders);
    all.put(name, value);
    return new GoldenInteraction(trigger, state, operationId, consumes, all, readsHeaders, anyLength, exact);
  }

  /** The same row, reading the response headers {@code names}. */
  public GoldenInteraction readsHeader(String... names) {
    List<String> all = new ArrayList<>(readsHeaders);
    all.addAll(List.of(names));
    return new GoldenInteraction(trigger, state, operationId, consumes, requestHeaders, all, anyLength, exact);
  }

  /** The same row, not pinning the length of the arrays at {@code patterns}. */
  public GoldenInteraction anyLength(String... patterns) {
    List<String> all = new ArrayList<>(anyLength);
    all.addAll(List.of(patterns));
    return new GoldenInteraction(trigger, state, operationId, consumes, requestHeaders, readsHeaders, all, exact);
  }

  /**
   * The same row, binding the recorded values at {@code paths} exactly instead of by type. Each
   * must be consumed already (it or a path above it), and must not cross an array.
   */
  public GoldenInteraction exact(String... paths) {
    List<String> all = new ArrayList<>(exact);
    all.addAll(List.of(paths));
    return new GoldenInteraction(trigger, state, operationId, consumes, requestHeaders, readsHeaders, anyLength, all);
  }

  /** {@code <trigger value>: <operationId>}: the trigger first, so (description, state) stays unique. */
  public String description() {
    return trigger.value() + ": " + operationId;
  }
}
