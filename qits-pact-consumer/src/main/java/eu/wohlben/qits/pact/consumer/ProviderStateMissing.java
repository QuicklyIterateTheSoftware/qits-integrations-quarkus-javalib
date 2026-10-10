package eu.wohlben.qits.pact.consumer;

/**
 * A row asks for a (provider state, operation) the provider has not recorded. An {@link
 * AssertionError}, so the test that runs the row fails rather than errors. The message is the one
 * a skipped row's {@code @Disabled} carries: {@code needs provider state '<state>' for <operationId>
 * in <provider>}.
 */
public final class ProviderStateMissing extends AssertionError {

  public ProviderStateMissing(String provider, String state, String operationId, String detail) {
    super(message(provider, state, operationId) + (detail == null ? "" : " (" + detail + ")"));
  }

  /** The reason a row waits: {@code needs provider state '<state>' for <operationId> in <provider>}. */
  public static String message(String provider, String state, String operationId) {
    return "needs provider state '" + state + "' for " + operationId + " in " + provider;
  }
}
