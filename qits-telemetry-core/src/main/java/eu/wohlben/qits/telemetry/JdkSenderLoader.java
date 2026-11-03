package eu.wohlben.qits.telemetry;

import io.opentelemetry.common.ComponentLoader;
import io.opentelemetry.exporter.sender.jdk.internal.JdkHttpSenderProvider;
import java.util.List;
import java.util.Set;

/**
 * The SPI loader handed to the upstream builders: the java.net.http sender, named rather than
 * discovered, and nothing else.
 *
 * <p><b>Why not ServiceLoader.</b> The builders default to {@code ServiceLoader} over the context
 * class loader. That works in a JVM, but in a native image it depends on the provider's {@code
 * META-INF/services} entry and its constructor having been registered at build time, and a miss
 * there is "No HttpSenderProvider found" at the first export of a binary whose JVM tests were
 * green. Constructed with {@code new}, the provider is an ordinary reachable class and needs no
 * reflection metadata at all. It also makes the choice deterministic should a service ever carry a
 * second sender (okhttp) on its classpath.
 *
 * <p>Every other SPI answers empty: the only other one the OTLP/HTTP builders consult is the
 * compressor registry, where {@code gzip} and {@code none} are built in.
 */
final class JdkSenderLoader implements ComponentLoader {

  static final JdkSenderLoader INSTANCE = new JdkSenderLoader();

  /**
   * The SPI's name before and after OpenTelemetry moved it out of the internal package ({@code
   * exporter.internal.http} up to 1.57, {@code sdk.common.export} by 1.62). Named, never
   * referenced, so one jar links against both: Quarkus 3.34.6 and 3.40.1 consumers alike.
   */
  private static final Set<String> HTTP_SENDER_PROVIDER =
      Set.of(
          "io.opentelemetry.exporter.internal.http.HttpSenderProvider",
          "io.opentelemetry.sdk.common.export.HttpSenderProvider");

  private JdkSenderLoader() {}

  @Override
  @SuppressWarnings("unchecked")
  public <T> Iterable<T> load(Class<T> spiClass) {
    if (HTTP_SENDER_PROVIDER.contains(spiClass.getName())) {
      return (Iterable<T>) List.of(new JdkHttpSenderProvider());
    }
    return List.of();
  }
}
