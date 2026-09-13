package eu.wohlben.qits.auth;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Machine-token enforcement, behind one platform-wide rollout gate.
 *
 * <p><b>The gate is {@code qits.auth.machine.required}, default {@code false}.</b> Off, every {@code
 * require*} method returns at once and the endpoint behaves exactly as it does today — network
 * trust, no bearer needed. That is what lets a service ship its enforcement code before qits-idp is
 * deployed. On, the same call demands a validated token addressed to this service and carrying the
 * matching claim.
 *
 * <p>There is no third state. The gate is read once per call and nothing else varies with it, so a
 * deployment is either "as before" or "enforced" — never partly one and partly the other. Turning
 * it on without {@code qits.auth.machine.audience} fails at startup rather than accepting tokens
 * meant for another service.
 *
 * <p>A token also passes when its {@code aud} names {@code qits.auth.machine.platform-audience}
 * (default {@code qits-platform}) instead of this service's own audience — one audience for every
 * token on the platform (rulings 2026-09-13). A blank platform audience turns this off, leaving
 * only the service's own audience.
 *
 * <p>Typical use, from a JAX-RS filter or straight from a resource method:
 *
 * <pre>{@code
 * @Inject MachineAuth machineAuth;
 *
 * @POST
 * public Response postReceive(PostReceiveEvent event) {
 *   machineAuth.requireProject(event.repoId());
 *   ...
 * }
 * }</pre>
 *
 * <p>A failure throws: {@link UnauthorizedException} (401) when the caller presented no machine
 * token, {@link ForbiddenException} (403) when it presented one that does not cover the target.
 * Quarkus REST maps both, so a caller needs no exception mapper of its own.
 */
@ApplicationScoped
public class MachineAuth {

  /** The rollout gate. One key, the same in every service. */
  public static final String REQUIRED_KEY = "qits.auth.machine.required";

  /** This service's own id — the {@code aud} value its tokens must carry. */
  public static final String AUDIENCE_KEY = "qits.auth.machine.audience";

  /**
   * The one audience shared by every token on the platform (rulings 2026-09-13). A machine token
   * whose {@code aud} names this value passes the same checks as one naming {@link #AUDIENCE_KEY}
   * — so a service keeps working through its own cutover to the shared audience. Default {@code
   * qits-platform}; blank turns this off.
   */
  public static final String PLATFORM_AUDIENCE_KEY = "qits.auth.machine.platform-audience";

  @ConfigProperty(name = REQUIRED_KEY, defaultValue = "false")
  boolean required;

  @ConfigProperty(name = AUDIENCE_KEY)
  Optional<String> audience;

  @ConfigProperty(name = PLATFORM_AUDIENCE_KEY, defaultValue = "qits-platform")
  String platformAudience;

  @Inject SecurityIdentity identity;

  MachineAuth() {}

  /** For tests and callers outside CDI. The shipped platform audience applies. */
  MachineAuth(boolean required, String audience, SecurityIdentity identity) {
    this(required, audience, "qits-platform", identity);
  }

  /** For tests and callers outside CDI that need to vary the platform audience too. */
  MachineAuth(boolean required, String audience, String platformAudience, SecurityIdentity identity) {
    this.required = required;
    this.audience = Optional.ofNullable(audience);
    this.platformAudience = platformAudience == null ? "" : platformAudience;
    this.identity = identity;
  }

  // A service that enforces must know which tokens are its own. Caught here rather than at the
  // first request, so the mistake is a failed deploy and not a quietly widened door.
  void validateConfig(@Observes StartupEvent event) {
    if (required && audience.isEmpty()) {
      throw new IllegalStateException(
          REQUIRED_KEY + "=true needs " + AUDIENCE_KEY + " set to this service's id");
    }
  }

  /** True when the gate is on. Read it to log the posture, not to skip a {@code require*} call. */
  public boolean enforced() {
    return required;
  }

  /** Demands a machine token addressed to this service. No claim is inspected. */
  public void require() {
    if (!required) {
      return;
    }
    requireMachineToken();
  }

  /** Demands a machine token whose {@code project} claim equals {@code project}. */
  public void requireProject(String project) {
    requireClaim(QitsClaims.PROJECT, project);
  }

  /** Demands a machine token whose {@code workspace} claim equals {@code workspace}. */
  public void requireWorkspace(String workspace) {
    requireClaim(QitsClaims.WORKSPACE, workspace);
  }

  /** Demands a machine token whose {@code branch} claim equals {@code branch}. */
  public void requireBranch(String branch) {
    requireClaim(QitsClaims.BRANCH, branch);
  }

  /**
   * Demands a machine token whose {@code name} claim equals {@code expected}. An absent claim is a
   * mismatch — a token never granted the claim may not act on it.
   */
  public void requireClaim(String name, String expected) {
    if (!required) {
      return;
    }
    requireMachineToken();
    if (!MachineIdentity.claimMatches(identity, name, expected)) {
      throw new ForbiddenException("Token " + name + " claim does not cover " + expected);
    }
  }

  /**
   * The same decision as {@link #requireClaim} without the throw, for a caller that filters a list
   * rather than guarding one call. Gate off answers {@code true}, matching the endpoint behaviour.
   */
  public boolean permits(String name, String expected) {
    if (!required) {
      return true;
    }
    return audience
            .map(a -> MachineIdentity.hasAudience(identity, a, platformAudience))
            .orElse(false)
        && MachineIdentity.claimMatches(identity, name, expected);
  }

  private void requireMachineToken() {
    if (!MachineIdentity.isMachine(identity)) {
      throw new UnauthorizedException("Machine token required");
    }
    String expected = audience.orElseThrow();
    if (!MachineIdentity.hasAudience(identity, expected, platformAudience)) {
      throw new ForbiddenException("Token audience does not include " + expected);
    }
  }
}
