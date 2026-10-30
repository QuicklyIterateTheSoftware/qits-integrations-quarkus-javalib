package eu.wohlben.qits.archrules.fixtures.providerstates;

import au.com.dius.pact.provider.junitsupport.State;

/**
 * Pact verifier shapes for {@code ProviderStateCoverageRules}' self-test. The index each test pairs
 * them with is written out by the test itself.
 */
public final class ProviderStateFixtures {

  private ProviderStateFixtures() {}

  /** One callback per state, as a provider usually writes them; visibility does not matter. */
  public static class CoveringVerifier {
    @State("a rotated runner")
    public void rotatedRunner() {}

    @State("a runner exists")
    void runnerExists() {}
  }

  /** One method setting up two states: both names count. */
  public static class MultiNameVerifier {
    @State({"a rotated runner", "a runner exists"})
    private void eitherRunner() {}
  }

  /** The first half of a provider that splits its callbacks across two verifiers. */
  public static class UngatedVerifier {
    @State("a rotated runner")
    public void rotatedRunner() {}
  }

  /** The second half: a gated verifier holding the state the first one does not. */
  public static class GatedVerifier {
    @State("a runner exists")
    public void runnerExists() {}
  }

  /** Covers the index and then some: one callback for a state nobody recorded. */
  public static class ExtraStateVerifier {
    @State("a rotated runner")
    public void rotatedRunner() {}

    @State("a runner exists")
    public void runnerExists() {}

    @State("a runner from before the recording")
    public void legacyRunner() {}
  }
}
