package eu.wohlben.qits.telemetry;

/**
 * Where an export's bearer comes from. One method, so the header logic can be proven against a
 * token that changes on command, without an idp.
 */
@FunctionalInterface
public interface BearerSource {

  /** The current token, or why there is none. Must return within a bounded time. */
  Lookup lookup();

  /** A token, or the reason there is none. Exactly one of the two is non-null. */
  record Lookup(String token, String reason) {

    public static Lookup of(String token) {
      return token == null || token.isBlank()
          ? none("the oidc client answered an empty access token")
          : new Lookup(token, null);
    }

    public static Lookup none(String reason) {
      return new Lookup(null, reason);
    }

    public boolean present() {
      return token != null;
    }
  }
}
