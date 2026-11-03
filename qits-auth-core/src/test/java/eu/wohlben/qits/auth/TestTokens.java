package eu.wohlben.qits.auth;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Builds the two identities the claim checks have to tell apart: one carrying a validated machine
 * token, one not.
 *
 * <p>The token is a hand-made {@link JsonWebToken} rather than a signed one from a running idp.
 * qits-auth-core validates nothing — it reads a principal quarkus-oidc has already vouched for — so
 * a signature here would test the extension, not this code. What matters is the shape: a
 * {@code JsonWebToken} principal, an {@code aud} set, and the granted claims.
 */
final class TestTokens {

  /** A named identity whose principal is not a JWT — anything that is not a machine. */
  static SecurityIdentity user(String name) {
    return QuarkusSecurityIdentity.builder().setPrincipal(new QuarkusPrincipal(name)).build();
  }

  /** Anonymous — no principal at all. */
  static SecurityIdentity anonymous() {
    return QuarkusSecurityIdentity.builder().setAnonymous(true).build();
  }

  /** A machine token for {@code clientId}, addressed to {@code audiences}, with no claims yet. */
  static Builder machine(String clientId, String... audiences) {
    return new Builder(clientId, Set.of(audiences));
  }

  static final class Builder {

    private final String clientId;
    private final Map<String, Object> claims = new HashMap<>();

    private Builder(String clientId, Set<String> audiences) {
      this.clientId = clientId;
      claims.put("aud", audiences);
    }

    Builder claim(String name, Object value) {
      claims.put(name, value);
      return this;
    }

    SecurityIdentity build() {
      return QuarkusSecurityIdentity.builder().setPrincipal(new FakeJwt(clientId, claims)).build();
    }
  }

  private record FakeJwt(String name, Map<String, Object> claims) implements JsonWebToken {

    @Override
    public String getName() {
      return name;
    }

    @Override
    public Set<String> getClaimNames() {
      return claims.keySet();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getClaim(String claimName) {
      return (T) claims.get(claimName);
    }
  }

  private TestTokens() {}
}
