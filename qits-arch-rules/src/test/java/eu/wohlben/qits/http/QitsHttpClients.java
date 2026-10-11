package eu.wohlben.qits.http;

import java.net.http.HttpClient;

/**
 * A TEST FIXTURE, not the real type. {@code HttpClientRules} matches qits-http-core's types by
 * fully-qualified name — the pom says why there is no dependency to import the real ones — so the
 * fixtures carry those exact names, top-level. Two files mirror the two names the rule judges:
 * this class and {@link RawHttpClient}. It also builds a raw client itself, which the rule must
 * forgive: this package is where the wrapping happens.
 */
public class QitsHttpClients {

  private final HttpClient fallback = HttpClient.newHttpClient();

  public HttpClient wrap(HttpClient raw) {
    return raw == null ? fallback : raw;
  }
}
