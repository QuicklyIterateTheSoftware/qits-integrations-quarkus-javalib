package eu.wohlben.qits.auth;

import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Machine-token enforcement, behind one platform-wide rollout gate.
 *
 * <p><b>The gate is {@code qits.auth.machine.required}, default {@code false}.</b> Off, every {@code
 * require*} method returns at once and the endpoint behaves exactly as it does today — network
 * trust, no bearer needed. That is what lets a service ship its enforcement code before qits-idp is
 * deployed. On, the same call demands a validated token carrying the platform audience and the
 * matching claim.
 *
 * <p>There is no third state. The gate is read once per call and nothing else varies with it, so a
 * deployment is either "as before" or "enforced" — never partly one and partly the other.
 *
 * <p><b>One audience for every token on the platform</b> (rulings 2026-09-13): {@code
 * qits.auth.machine.platform-audience}, {@code qits-platform}. A token's {@code aud} names it or
 * the call is refused — the audience says the token was minted for this platform, and a role or a
 * claim, never an audience, decides what the caller may then do. A service therefore has no id of
 * its own to configure here, and no enforcing service can be locked out by a token addressed to a
 * sibling: there are no sibling audiences.
 *
 * <p>A blank {@code qits.auth.machine.platform-audience} refuses every enforced call. It is the
 * only audience there is, so blanking it names nothing a token could carry; the deny is what the
 * check already says, spelled out so it cannot be read as an off switch.
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

  /**
   * The one audience every token on the platform carries (rulings 2026-09-13). An enforced call
   * demands it and accepts nothing else, because it is the only audience qits-idp mints. Default
   * {@code qits-platform}; blank names no audience at all and so refuses every enforced call.
   */
  public static final String PLATFORM_AUDIENCE_KEY = "qits.auth.machine.platform-audience";

  @ConfigProperty(name = REQUIRED_KEY, defaultValue = "false")
  boolean required;

  @ConfigProperty(name = PLATFORM_AUDIENCE_KEY, defaultValue = "qits-platform")
  String platformAudience;

  @Inject SecurityIdentity identity;

  MachineAuth() {}

  /** For tests and callers outside CDI. The shipped platform audience applies. */
  MachineAuth(boolean required, SecurityIdentity identity) {
    this(required, "qits-platform", identity);
  }

  /** For tests and callers outside CDI that need to vary the platform audience too. */
  MachineAuth(boolean required, String platformAudience, SecurityIdentity identity) {
    this.required = required;
    this.platformAudience = platformAudience == null ? "" : platformAudience;
    this.identity = identity;
  }

  /** True when the gate is on. Read it to log the posture, not to skip a {@code require*} call. */
  public boolean enforced() {
    return required;
  }

  /** Demands a machine token carrying the platform audience. No claim is inspected. */
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
    return hasPlatformAudience() && MachineIdentity.claimMatches(identity, name, expected);
  }

  private void requireMachineToken() {
    if (!MachineIdentity.isMachine(identity)) {
      throw new UnauthorizedException("Machine token required");
    }
    if (!hasPlatformAudience()) {
      throw new ForbiddenException("Token audience does not include " + platformAudience);
    }
  }

  // Spelled out rather than left to a set lookup: a blank platform audience is not a value a token
  // can carry, so it refuses here instead of matching a token that happens to hold a blank `aud`.
  private boolean hasPlatformAudience() {
    return !platformAudience.isBlank()
        && MachineIdentity.audiences(identity).contains(platformAudience);
  }
}
