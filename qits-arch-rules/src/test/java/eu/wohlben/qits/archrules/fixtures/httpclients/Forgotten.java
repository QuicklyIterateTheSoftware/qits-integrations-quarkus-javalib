package eu.wohlben.qits.archrules.fixtures.httpclients;

import java.net.http.HttpClient;

/** Builds a raw client and decided nothing: the shape the rule exists to catch. */
public class Forgotten {

  private final HttpClient client = HttpClient.newBuilder().build();

  HttpClient client() {
    return client;
  }
}
