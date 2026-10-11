package eu.wohlben.qits.archrules.fixtures.httpclients;

import eu.wohlben.qits.http.RawHttpClient;
import java.net.http.HttpClient;

/** The written opt-out, covering the nested class that does the building too. */
@RawHttpClient(reason = "calls an external host")
public class DeclaredRaw {

  final HttpClient client = HttpClient.newHttpClient();

  class Inner {
    final HttpClient another = HttpClient.newBuilder().build();
  }
}
