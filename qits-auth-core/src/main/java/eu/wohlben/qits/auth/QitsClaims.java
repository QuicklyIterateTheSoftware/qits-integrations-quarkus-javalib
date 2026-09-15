package eu.wohlben.qits.auth;

import java.util.Set;

/**
 * The vocabulary of a qits machine token: the structured claim names, and the value that covers
 * every one of them.
 *
 * <p><b>Claims, not scopes.</b> qits-idp issues no scope strings. A token says that it was minted
 * for this platform ({@code aud}, always {@code qits-platform}) and what it is about ({@code
 * project} / {@code workspace} / {@code branch}); the resource service decides what that permits.
 * Spell the names from here rather than inline — a typo in a claim name reads as "no claim", which
 * is a silent pass on an unenforced path and a silent denial on an enforced one.
 *
 * <p>Claims appear on a token only when granted to the client, so absence is normal and never an
 * error by itself.
 *
 * <p><b>No service id is a constant here, and none may become one.</b> A service id is the client id
 * when a service asks for a token — the {@code aud} it receives is the platform's one audience, not
 * an id — and every qits service is an environment service: it is named {@code <env>-qits-<app>} —
 * {@code prod-qits-ci} — and the environment is not known until deploy time. So a service reads its
 * own id and its peers' from injected config, which is what every one of them already does.
 * Anything named here would be true in one environment and wrong in every other.
 *
 * <p>There used to be constants: {@code CI}, {@code CD}, {@code WORKSPACES}, {@code GATEWAY}, and
 * last {@code ARTIFACTS} with a {@code SERVICE_IDS} set holding it alone. They were removed as each
 * service became an environment service, and none of them ever had a caller outside a test fixture.
 * A test that wants an id spells it, next to the other ids it spells.
 */
public final class QitsClaims {

  /** The project a token is about. */
  public static final String PROJECT = "project";

  /** The workspace a token is about. */
  public static final String WORKSPACE = "workspace";

  /** The branch a token is about. Only meaningful beside {@link #WORKSPACE}. */
  public static final String BRANCH = "branch";

  /** Every structured claim qits-idp can grant. */
  public static final Set<String> NAMES = Set.of(PROJECT, WORKSPACE, BRANCH);

  /**
   * The claim value that covers every value. A client granted {@code project=*} may act on any
   * project — the services that serve all of them, such as the git host holding every project's
   * repositories, hold their claims this way.
   *
   * <p>It is a token-side value only. Asking whether a token covers the literal target {@code "*"}
   * is answered like any other name, so a caller cannot widen its own check by passing it.
   */
  public static final String ANY = "*";

  private QitsClaims() {}
}
