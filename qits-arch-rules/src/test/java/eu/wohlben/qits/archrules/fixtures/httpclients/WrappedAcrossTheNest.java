package eu.wohlben.qits.archrules.fixtures.httpclients;

import eu.wohlben.qits.http.QitsHttpClients;
import java.net.http.HttpClient;
import java.util.function.Function;

/**
 * The bean held by the outer class, the raw client built in a nested one and a lambda: the nest is
 * judged as one, so this passes.
 */
public class WrappedAcrossTheNest {

  QitsHttpClients clients;

  static class Factory {
    HttpClient build() {
      return HttpClient.newBuilder().build();
    }
  }

  final Function<QitsHttpClients, HttpClient> later =
      c -> c.wrap(HttpClient.newHttpClient());
}
