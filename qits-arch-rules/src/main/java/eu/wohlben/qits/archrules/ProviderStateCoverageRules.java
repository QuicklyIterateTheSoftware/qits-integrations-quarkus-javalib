package eu.wohlben.qits.archrules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Every provider state the golden masters record has its {@code @State} callback, as a build
 * failure (qits-1754).
 *
 * <p>A provider's {@code golden-masters/index.json} lists every state a consumer can pin, and its
 * pact verifier ({@code ConsumerPactVerificationTest}, sometimes a {@code
 * GatedConsumerPactVerificationTest} beside it) carries one {@code @State("<name>")} method per
 * state, which sets the state up before the interaction replays. Nothing joins the two. A recorded
 * state without its callback is invisible in the provider's own build and stays invisible until a
 * consumer pins a pact naming it — and then it is the consumer's release that turns the provider's
 * gate red, with {@code Provider state change callback failed}, far from the change that caused it.
 *
 * <p>Two directions, and every problem in both is reported at once:
 *
 * <ul>
 *   <li><b>Forward</b> — every {@code states[].name} in the index has a method on one of the
 *       verifiers whose {@code @State} lists that name. The verifiers are a union: a state may be
 *       set up on either class.
 *   <li><b>Reverse</b> — every name a {@code @State} lists is recorded in the index, or is declared
 *       an unrecorded alias. A callback for a state nobody recorded is either dead or the old
 *       spelling of one that was recorded under a new name. An alias is how a service keeps a
 *       callback on purpose — a name consumers still pin from before the recording existed — and
 *       an alias goes stale like anything else: one that is now recorded, or that no {@code @State}
 *       lists any more, fails too.
 * </ul>
 *
 * <p>Enable it in a provider with a test-scope dependency on this module and a plain JUnit test:
 *
 * <pre>{@code
 * class ProviderStateCoverageTest {
 *   @Test
 *   void everyRecordedStateHasItsCallback() {
 *     ProviderStateCoverageRules.assertCovered(ConsumerPactVerificationTest.class);
 *   }
 * }
 * }</pre>
 *
 * <p>The index is found by walking up from the working directory, because surefire runs in the
 * module directory and a multi-module provider keeps {@code golden-masters/} at its repository
 * root. A service with aliases, or an index somewhere else, names both:
 *
 * <pre>{@code
 * ProviderStateCoverageRules.assertCovered(
 *     ProviderStateCoverageRules.findGoldenIndex(),
 *     Set.of("a runner from before the recording"),
 *     ConsumerPactVerificationTest.class,
 *     GatedConsumerPactVerificationTest.class);
 * }</pre>
 *
 * <p><b>The annotation is named as a string</b>, the module's standing trick: this module depends
 * on no pact-jvm, the annotation is read reflectively by its fully-qualified name and its {@code
 * value()} invoked by name, so a bare clone builds and the fleet's pact version stays its own. The
 * price is the usual one — a rename in pact-jvm silently un-matches, and the fixture mirror of
 * {@code au.com.dius.pact.provider.junitsupport.State} in this module's test sources is where that
 * would surface. The index is read with Jackson, a <b>provided</b> dependency every Quarkus
 * consumer already carries on its test classpath.
 */
public final class ProviderStateCoverageRules {

  /** The pact-jvm annotation that marks a provider state callback, matched by name. */
  public static final String STATE_ANNOTATION = "au.com.dius.pact.provider.junitsupport.State";

  /** Where a provider keeps its recorded states, relative to its repository root. */
  public static final String GOLDEN_INDEX = "golden-masters/index.json";

  private static final String CONSEQUENCE =
      "a consumer pinning it turns this provider's gate red with"
          + " 'Provider state change callback failed'";

  private ProviderStateCoverageRules() {}

  /**
   * Asserts coverage against the index {@link #findGoldenIndex()} finds, with no unrecorded
   * aliases.
   *
   * @param verifiers the provider's pact verifier classes; their declared methods are read
   */
  public static void assertCovered(Class<?>... verifiers) {
    assertCovered(findGoldenIndex(), Set.of(), verifiers);
  }

  /**
   * Asserts coverage against a given index. Every problem is collected first and reported in one
   * {@link AssertionError} — a provider fixing one state per build learns nothing the first message
   * could not have told it.
   *
   * @param goldenIndex the provider's {@code golden-masters/index.json}
   * @param unrecordedAliases state names a {@code @State} may list without the index recording
   *     them; each must be listed by some {@code @State} and must not be recorded
   * @param verifiers the provider's pact verifier classes; their declared methods, of any
   *     visibility, are read, and superclasses are not
   * @throws AssertionError naming each missing state with the verifiers searched, each unrecorded
   *     state with the method listing it, and each stale alias
   */
  public static void assertCovered(
      Path goldenIndex, Set<String> unrecordedAliases, Class<?>... verifiers) {
    if (verifiers == null || verifiers.length == 0) {
      throw new IllegalArgumentException("name at least one pact verifier class");
    }
    Set<String> recorded = recordedStates(goldenIndex);
    TreeMap<String, List<String>> callbacks = stateCallbacks(verifiers);
    String searched =
        Arrays.stream(verifiers).map(Class::getName).collect(Collectors.joining(", "));

    List<String> problems = new ArrayList<>();
    for (String state : new TreeSet<>(recorded)) {
      if (!callbacks.containsKey(state)) {
        problems.add(
            "  missing: '"
                + state
                + "' is recorded but no @State method on "
                + searched
                + " lists it — "
                + CONSEQUENCE
                + ".");
      }
    }
    for (var callback : callbacks.entrySet()) {
      String state = callback.getKey();
      if (!recorded.contains(state) && !unrecordedAliases.contains(state)) {
        problems.add(
            "  unrecorded: '"
                + state
                + "' on "
                + String.join(", ", callback.getValue())
                + " is not in "
                + goldenIndex
                + " — record it, rename the callback to the recorded name, or declare it an"
                + " unrecorded alias.");
      }
    }
    for (String alias : new TreeSet<>(unrecordedAliases)) {
      if (recorded.contains(alias)) {
        problems.add(
            "  stale alias: '" + alias + "' is recorded in the index now; drop it from the aliases.");
      } else if (!callbacks.containsKey(alias)) {
        problems.add(
            "  stale alias: '"
                + alias
                + "' is listed by no @State method on "
                + searched
                + "; drop it from the aliases.");
      }
    }
    if (!problems.isEmpty()) {
      throw new AssertionError(
          "The provider states in "
              + goldenIndex
              + " and the @State callbacks on "
              + searched
              + " disagree. A recorded state without its callback fails nothing in this build;"
              + " instead "
              + CONSEQUENCE
              + ":\n"
              + String.join("\n", problems));
    }
  }

  /**
   * Finds {@value #GOLDEN_INDEX} by walking up from the working directory.
   *
   * @throws AssertionError if no directory from the working directory up holds one
   */
  public static Path findGoldenIndex() {
    return findGoldenIndex(Path.of("").toAbsolutePath());
  }

  /** The walk itself, from a given directory; package-private for the self-test. */
  static Path findGoldenIndex(Path start) {
    for (Path dir = start.toAbsolutePath().normalize(); dir != null; dir = dir.getParent()) {
      Path candidate = dir.resolve(GOLDEN_INDEX);
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    throw new AssertionError(
        "No "
            + GOLDEN_INDEX
            + " in "
            + start.toAbsolutePath().normalize()
            + " or any directory above it. A provider records its states there; pass the path"
            + " explicitly if it lives somewhere else.");
  }

  /** The {@code states[].name} values the index records. */
  private static Set<String> recordedStates(Path goldenIndex) {
    JsonNode root;
    try {
      root = new ObjectMapper().readTree(goldenIndex.toFile());
    } catch (IOException unreadable) {
      throw new AssertionError("Cannot read " + goldenIndex + ": " + unreadable.getMessage());
    }
    JsonNode states = root == null ? null : root.get("states");
    if (states == null || !states.isArray()) {
      throw new AssertionError(goldenIndex + " has no \"states\" array.");
    }
    Set<String> names = new LinkedHashSet<>();
    for (JsonNode state : states) {
      JsonNode name = state.get("name");
      if (name == null || !name.isTextual()) {
        throw new AssertionError(goldenIndex + " has a state without a \"name\": " + state);
      }
      names.add(name.asText());
    }
    return names;
  }

  /** Every name a {@code @State} lists, to the {@code Class.method} entries that list it. */
  private static TreeMap<String, List<String>> stateCallbacks(Class<?>... verifiers) {
    TreeMap<String, List<String>> callbacks = new TreeMap<>();
    for (Class<?> verifier : verifiers) {
      for (Method method : verifier.getDeclaredMethods()) {
        for (Annotation annotation : method.getAnnotations()) {
          if (!STATE_ANNOTATION.equals(annotation.annotationType().getName())) {
            continue;
          }
          for (String name : stateNames(annotation)) {
            callbacks
                .computeIfAbsent(name, n -> new ArrayList<>())
                .add(verifier.getSimpleName() + "." + method.getName());
          }
        }
      }
    }
    return callbacks;
  }

  /** {@code value()}, invoked by name: pact-jvm declares it {@code String[]}. */
  private static List<String> stateNames(Annotation state) {
    Object value;
    try {
      value = state.annotationType().getMethod("value").invoke(state);
    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
      throw new AssertionError(
          STATE_ANNOTATION + " no longer reads as expected: value() failed with " + e, e);
    }
    if (value instanceof String[] names) {
      return List.of(names);
    }
    if (value instanceof String name) {
      return List.of(name);
    }
    throw new AssertionError(
        STATE_ANNOTATION + ".value() returned " + value + "; expected String[]");
  }
}
