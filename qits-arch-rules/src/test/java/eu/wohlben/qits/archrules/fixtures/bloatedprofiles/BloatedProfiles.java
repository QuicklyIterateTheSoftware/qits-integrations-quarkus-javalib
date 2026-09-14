package eu.wohlben.qits.archrules.fixtures.bloatedprofiles;

import eu.wohlben.qits.archrules.NecessaryTestProfileDuplication;
import io.quarkus.test.junit.QuarkusTestProfile;

/**
 * The shape that killed qits-workspaces-service's gate, in miniature: profiles nobody decided to
 * have, each differing from the others and none of them needing to.
 *
 * <p>Three unmarked rather than the two that would suffice, because the message contract is that
 * <em>every</em> offender is named in <em>every</em> violation — two cannot tell the difference
 * between a full roster and a pair. The marked one and the abstract base are here to prove they are
 * not counted: a module that bought its duplicate, or factored its profiles together, is not
 * charged for it.
 */
public final class BloatedProfiles {

  private BloatedProfiles() {}

  /** The profile the module would have if somebody merged the other two into it. */
  public static class ServiceProfile implements QuarkusTestProfile {}

  /**
   * The accidental duplicate, in its commonest fleet spelling: a fresh temp directory for a config
   * key, which makes this profile's config map unequal to every other <b>by construction</b> while
   * buying nothing. This is why the rule counts profiles instead of comparing their configuration.
   */
  public static class FreshTempDirProfile implements QuarkusTestProfile {}

  /** And the one after that, copied from the one before it. */
  public static class AnotherTempDirProfile implements QuarkusTestProfile {}

  /** Bought and written down: not an offender, and absent from the roster. */
  public static class DeliberateProfile
      implements QuarkusTestProfile, NecessaryTestProfileDuplication {}

  /** Never booted, never counted. */
  public abstract static class ProfileBase implements QuarkusTestProfile {}
}
