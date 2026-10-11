package eu.wohlben.qits.http;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.opentelemetry.api.OpenTelemetry;
import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;

/** The hand-built bean a test passes where CDI would inject one. */
class QitsHttpClientsTest {

  @Test
  void aHandBuiltBeanWrapsAndWrapsOnlyOnce() {
    QitsHttpClients clients = QitsHttpClients.of(OpenTelemetry.noop(), null);
    HttpClient wrapped = clients.wrap(HttpClient.newHttpClient());
    assertInstanceOf(TracingHttpClient.class, wrapped);
    assertSame(wrapped, clients.wrap(wrapped));
  }
}
