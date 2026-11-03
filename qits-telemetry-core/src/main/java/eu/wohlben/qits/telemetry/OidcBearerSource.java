package eu.wohlben.qits.telemetry;

import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.OidcClients;
import io.quarkus.oidc.client.runtime.TokensHelper;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * The bearer from the named oidc client, {@code quarkus.oidc-client.qits} unless {@code
 * qits.telemetry.bearer.client} names another — the same client, and the same {@link TokensHelper}
 * cache-and-refresh, as qits-maintenance's {@code PeerTokens} and qits-docs' {@code DocsBearer}.
 *
 * <p><b>Bounded, never indefinite.</b> {@link TokensHelper} answers a cached token at once and goes
 * to the idp only when it has expired (or is inside the client's {@code refresh-token-time-skew});
 * that fetch is awaited for at most {@code qits.telemetry.bearer.timeout} (5s). The caller is the
 * JDK sender's own {@code jdkhttp-executor} thread, never an event loop and never the thread that
 * produced the span or log, so even the bounded wait stalls only one export. A fetch that times
 * out keeps running inside the helper, and the export after it finds the token cached.
 *
 * <p><b>A token this cannot mint is a reason, not an exception</b> — the estate's stance (PeerTokens,
 * DocsBearer): the export then goes out bare and the edge's 401 is the refusal that counts. {@link
 * BearerHeaders} says so once.
 *
 * <p>The client is looked up lazily through {@link OidcClients}, never injected with
 * {@code @NamedOidcClient}: a service that opts out ({@code qits.telemetry.bearer.enabled=false})
 * may not configure the client at all, and an injection point would make it mandatory.
 */
@Singleton
public class OidcBearerSource implements BearerSource {

  @Inject Instance<OidcClients> clients;

  private final TokensHelper helper = new TokensHelper();

  private volatile OidcClient client;

  @Override
  public Lookup lookup() {
    TelemetrySettings settings = TelemetrySettings.read();
    String name = settings.client();
    if (!enabled(name)) {
      return Lookup.none("quarkus.oidc-client." + name + ".client-enabled is false");
    }
    try {
      OidcClient resolved = resolve(name);
      if (resolved == null) {
        return Lookup.none("no oidc client named '" + name + "' is configured");
      }
      Duration timeout = settings.tokenTimeout();
      return Lookup.of(helper.getTokens(resolved).await().atMost(timeout).getAccessToken());
    } catch (RuntimeException e) {
      return Lookup.none("the oidc client '" + name + "' could not mint a token: " + e);
    }
  }

  private OidcClient resolve(String name) {
    OidcClient current = client;
    if (current == null && clients.isResolvable()) {
      current = clients.get().getClient(name);
      client = current;
    }
    return current;
  }

  /** The extension's own switch; Quarkus' default for it is {@code true}. */
  private static boolean enabled(String name) {
    return ConfigProvider.getConfig()
        .getOptionalValue("quarkus.oidc-client." + name + ".client-enabled", Boolean.class)
        .orElse(true);
  }
}
