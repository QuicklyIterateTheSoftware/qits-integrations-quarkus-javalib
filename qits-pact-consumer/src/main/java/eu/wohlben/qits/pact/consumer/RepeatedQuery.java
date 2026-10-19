package eu.wohlben.qits.pact.consumer;

import au.com.dius.pact.core.model.Interaction;
import au.com.dius.pact.core.model.generators.Category;
import au.com.dius.pact.core.model.generators.Generator;
import au.com.dius.pact.core.model.generators.GeneratorTestMode;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>The provider's half of a repeated query parameter</b>: each value of {@code
 * attr=repositoryname={repositoryName}&attr=projectid={projectId}} is its own provider-state
 * expression, written by {@link ConsumerPact} as a V4 query generator at {@code attr[0]}, {@code
 * attr[1]} (the key pact-jvm's own DSL writes for a list of matchers). pact-jvm 4.6.21's verifier
 * does not apply such a key — its query generators are keyed by parameter name and give every value
 * the same generated one — so the recorded frozen values would be sent as they are.
 *
 * <p>A provider's {@code ConsumerPactVerificationTest} applies them itself, in its test template,
 * before the request is sent:
 *
 * <pre>{@code
 * @TestTemplate
 * @ExtendWith(PactVerificationInvocationContextProvider.class)
 * void verify(PactVerificationContext context, ClassicHttpRequest request) throws Exception {
 *   request.setUri(RepeatedQuery.generated(request.getUri(), context.getInteraction(),
 *       context.getExecutionContext()));
 *   context.verifyInteraction();
 * }
 * }</pre>
 *
 * A request whose interaction carries no indexed query generator is returned unchanged.
 */
public final class RepeatedQuery {

  private static final Pattern INDEXED = Pattern.compile("^(.+)\\[(\\d+)]$");

  private RepeatedQuery() {}

  /**
   * {@code sent} with each value an indexed query generator names replaced by that generator's
   * value, generated from {@code context} (the verifier's execution context, whose {@code
   * providerState} holds the state's values) as pact-jvm generates the rest of the request.
   */
  public static URI generated(URI sent, Interaction interaction, Map<String, Object> context) {
    if (!interaction.isSynchronousRequestResponse()) {
      return sent;
    }
    Map<String, Generator> query = interaction.asSynchronousRequestResponse().getRequest().getGenerators()
        .getCategories().getOrDefault(Category.QUERY, Map.of());
    Map<String, Map<Integer, Generator>> indexed = new HashMap<>();
    query.forEach((key, generator) -> {
      Matcher m = INDEXED.matcher(key);
      if (m.matches() && generator.correspondsToMode(GeneratorTestMode.Provider)) {
        indexed.computeIfAbsent(m.group(1), k -> new HashMap<>()).put(Integer.parseInt(m.group(2)), generator);
      }
    });
    String raw = sent.getRawQuery();
    if (indexed.isEmpty() || raw == null) {
      return sent;
    }
    Map<String, Integer> seen = new HashMap<>();
    List<String> pairs = new ArrayList<>();
    for (String pair : raw.split("&")) {
      int eq = pair.indexOf('=');
      String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
      int index = seen.merge(name, 1, Integer::sum) - 1;
      Generator generator = indexed.getOrDefault(name, Map.of()).get(index);
      if (generator == null) {
        pairs.add(pair);
      } else {
        String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
        pairs.add(pair.substring(0, eq < 0 ? pair.length() : eq) + "="
            + URLEncoder.encode(String.valueOf(generator.generate(context, value)), StandardCharsets.UTF_8));
      }
    }
    return URI.create(sent.toString().replace("?" + raw, "?" + String.join("&", pairs)));
  }
}
