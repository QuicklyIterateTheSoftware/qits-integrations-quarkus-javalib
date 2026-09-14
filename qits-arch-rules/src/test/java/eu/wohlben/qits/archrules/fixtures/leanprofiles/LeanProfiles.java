package eu.wohlben.qits.archrules.fixtures.leanprofiles;

import eu.wohlben.qits.archrules.NecessaryTestProfileDuplication;
import io.quarkus.test.junit.QuarkusTestProfile;

/**
 * A module within its budget: one profile every test shares, plus one duplicate that was bought
 * rather than accumulated. The shape the rule must pass without a word.
 */
public final class LeanProfiles {

  private LeanProfiles() {}

  /** The module's one application. Everything that can share a configuration shares this. */
  public static class ServiceProfile implements QuarkusTestProfile {}

  /**
   * The written, priced exception. Its javadoc in a real service would say why the two
   * configurations cannot be one — here, standing in for that reason: this profile answers with no
   * issuer at all, which the profile above cannot also do while booting its OIDC client.
   */
  public static class NoIssuerProfile
      implements QuarkusTestProfile, NecessaryTestProfileDuplication {}

  /**
   * A shared base several profiles extend is the repair this rule asks for, not a violation of it —
   * abstract, so Quarkus never boots an application for it, so it costs no metaspace.
   */
  public abstract static class ProfileBase implements QuarkusTestProfile {}

  /** Not a profile at all: the rule has nothing to say about it. */
  public static class PlainTestSupport {}
}
