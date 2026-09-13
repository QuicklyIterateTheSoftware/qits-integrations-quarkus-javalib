package eu.wohlben.qits.auth;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.json.JsonString;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Reads a validated machine token off a {@link SecurityIdentity}. Static and CDI-free, so a filter,
 * a resource method or a plain unit test can all ask the same questions.
 *
 * <p><b>It validates nothing.</b> By the time an identity reaches here, quarkus-oidc has checked the
 * signature, the issuer and the expiry against qits-idp; this class only reports what the token
 * says. An identity with no {@link JsonWebToken} principal — a forward-auth user, or anonymous — is
 * simply not a machine, which every method below reports as "no" rather than as an error.
 *
 * <p>Most callers want {@link MachineAuth}, which adds the rollout gate and turns a "no" into a
 * status code. Use these methods directly when the decision is more than an equality check.
 */
public final class MachineIdentity {

  /** True when the identity carries a validated machine token. */
  public static boolean isMachine(SecurityIdentity identity) {
    return token(identity) != null;
  }

  /** The token's {@code aud} values, empty when the identity is not a machine. */
  public static Set<String> audiences(SecurityIdentity identity) {
    JsonWebToken jwt = token(identity);
    if (jwt == null || jwt.getAudience() == null) {
      return Set.of();
    }
    return jwt.getAudience();
  }

  /**
   * True when the token is addressed to {@code audience} — a service id, which the caller reads
   * from config because every service id carries an environment — OR to the shared {@code
   * qits.auth.machine.platform-audience} (rulings 2026-09-13: one audience for the whole
   * platform). Read here through {@link ConfigProvider} rather than a parameter, so a caller that
   * already has one audience in hand needs no change to keep working. Use the three-argument
   * overload to pass a known platform audience instead, such as one already read through CDI.
   */
  public static boolean hasAudience(SecurityIdentity identity, String audience) {
    return hasAudience(identity, audience, platformAudienceFromConfig());
  }

  /**
   * The same check as {@link #hasAudience(SecurityIdentity, String)}, with the platform audience
   * given rather than read from config. A blank or {@code null} {@code platformAudience} means "no
   * platform audience" — only {@code audience} counts, which is the behaviour every caller had
   * before the platform audience existed.
   */
  public static boolean hasAudience(
      SecurityIdentity identity, String audience, String platformAudience) {
    Set<String> aud = audiences(identity);
    if (aud.contains(audience)) {
      return true;
    }
    return platformAudience != null && !platformAudience.isBlank() && aud.contains(platformAudience);
  }

  private static String platformAudienceFromConfig() {
    return ConfigProvider.getConfig()
        .getOptionalValue(MachineAuth.PLATFORM_AUDIENCE_KEY, String.class)
        .orElse(null);
  }

  /**
   * A structured claim's value — one of {@link QitsClaims#NAMES}. Empty when the identity is not a
   * machine, or when the claim was not granted to the client.
   */
  public static Optional<String> claim(SecurityIdentity identity, String name) {
    JsonWebToken jwt = token(identity);
    if (jwt == null) {
      return Optional.empty();
    }
    return jwt.claim(name).map(MachineIdentity::asString).filter(v -> !v.isBlank());
  }

  /**
   * True when the claim is present and covers {@code expected}. An absent claim is a mismatch: a
   * token that was never granted a {@code project} may not act on one.
   *
   * <p>A claim value of {@link QitsClaims#ANY} covers every value. That is how a service that acts
   * across all of them holds its claim — qits-artifacts hosts every project's git repositories, so
   * its token says {@code project=*} rather than naming one. The wildcard is read on the token side
   * only: a caller passing {@code "*"} as {@code expected} is asking about a target named {@code
   * "*"} and gets the same equality answer as for any other name.
   */
  public static boolean claimMatches(SecurityIdentity identity, String name, String expected) {
    if (expected == null) {
      return false;
    }
    return claim(identity, name)
        .filter(value -> QitsClaims.ANY.equals(value) || value.equals(expected))
        .isPresent();
  }

  /**
   * Shorthand for the common check: right audience and right {@code project}. "Right audience"
   * also accepts the shared platform audience, through {@link #hasAudience(SecurityIdentity,
   * String)}.
   */
  public static boolean matchesProject(
      SecurityIdentity identity, String audience, String project) {
    return hasAudience(identity, audience) && claimMatches(identity, QitsClaims.PROJECT, project);
  }

  /**
   * Shorthand for the common check: right audience and right {@code workspace}. "Right audience"
   * also accepts the shared platform audience, through {@link #hasAudience(SecurityIdentity,
   * String)}.
   */
  public static boolean matchesWorkspace(
      SecurityIdentity identity, String audience, String workspace) {
    return hasAudience(identity, audience)
        && claimMatches(identity, QitsClaims.WORKSPACE, workspace);
  }

  /**
   * Shorthand for the common check: right audience and right {@code branch}. "Right audience"
   * also accepts the shared platform audience, through {@link #hasAudience(SecurityIdentity,
   * String)}.
   */
  public static boolean matchesBranch(SecurityIdentity identity, String audience, String branch) {
    return hasAudience(identity, audience) && claimMatches(identity, QitsClaims.BRANCH, branch);
  }

  private static JsonWebToken token(SecurityIdentity identity) {
    return identity != null && identity.getPrincipal() instanceof JsonWebToken jwt ? jwt : null;
  }

  // quarkus-oidc hands back a plain String; smallrye-jwt hands back a JSON-P value. Both are legal
  // MP-JWT, so both are read rather than one being assumed.
  private static String asString(Object value) {
    return value instanceof JsonString json ? json.getString() : String.valueOf(value);
  }

  private MachineIdentity() {}
}
