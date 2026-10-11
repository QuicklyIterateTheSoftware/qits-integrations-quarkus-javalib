package eu.wohlben.qits.archrules.fixtures.httpclients;

import eu.wohlben.qits.http.QitsHttpClients;
import java.net.http.HttpClient;

/** The prescribed shape: inject the bean, wrap the client. */
public class Wrapped {

  private final HttpClient client;

  public Wrapped(QitsHttpClients clients) {
    this.client = clients.wrap(HttpClient.newBuilder().build());
  }

  HttpClient client() {
    return client;
  }
}
