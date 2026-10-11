package eu.wohlben.qits.archrules.fixtures.httpclients;

import java.net.http.HttpClient;
import java.util.function.Supplier;

/** The same omission behind a lambda and inside a nested class: still this class's decision. */
public class ForgottenInALambda {

  final Supplier<HttpClient> lazily = () -> HttpClient.newHttpClient();

  static class Holder {
    final HttpClient client = HttpClient.newHttpClient();
  }
}
