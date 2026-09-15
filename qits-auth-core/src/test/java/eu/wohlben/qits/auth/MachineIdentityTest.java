package eu.wohlben.qits.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.security.identity.SecurityIdentity;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Reading a machine token off an identity: what is there, what is not, and what that means. */
class MachineIdentityTest {

  /** The one audience every token on the platform carries. */
  private static final String PLATFORM = "qits-platform";

  // Service ids carry their environment, so they are spelled here rather than in QitsClaims.
  private static final String CI = "prod-qits-ci";
  private static final String ARTIFACTS = "prod-qits-artifacts";
  private static final String WORKSPACES = "prod-qits-workspaces";

  @Test
  void aJwtPrincipalIsAMachineAndAnythingElseIsNot() {
    assertTrue(MachineIdentity.isMachine(TestTokens.machine(CI).build()));
    assertFalse(MachineIdentity.isMachine(TestTokens.user("alice")));
    assertFalse(MachineIdentity.isMachine(TestTokens.anonymous()));
    assertFalse(MachineIdentity.isMachine(null));
  }

  @Test
  void anExtraAudienceIsMatchedExactly() {
    SecurityIdentity identity = TestTokens.machine(CI, WORKSPACES).build();

    assertTrue(MachineIdentity.hasAudience(identity, WORKSPACES));
    assertFalse(MachineIdentity.hasAudience(identity, ARTIFACTS));
    assertFalse(MachineIdentity.hasAudience(TestTokens.user("alice"), WORKSPACES));
  }

  @Test
  void hasAudienceAcceptsThePlatformAudience() {
    // The two-argument overload reads qits.auth.machine.platform-audience off config; this
    // module's shipped default is "qits-platform", the one audience every token carries.
    SecurityIdentity platformOnly = TestTokens.machine(CI, PLATFORM).build();

    assertTrue(MachineIdentity.hasAudience(platformOnly, WORKSPACES));
    assertTrue(MachineIdentity.matchesProject(
        TestTokens.machine(CI, PLATFORM).claim(QitsClaims.PROJECT, "qits").build(),
        WORKSPACES,
        "qits"));
  }

  @Test
  void hasAudienceWithABlankPlatformAudienceLeavesOnlyTheNamedOne() {
    SecurityIdentity platformOnly = TestTokens.machine(CI, PLATFORM).build();

    assertFalse(MachineIdentity.hasAudience(platformOnly, WORKSPACES, ""));
    assertFalse(MachineIdentity.hasAudience(platformOnly, WORKSPACES, null));
    assertTrue(MachineIdentity.hasAudience(platformOnly, PLATFORM, ""));
  }

  @Test
  void aGrantedClaimReadsBackAndAnUngrantedOneIsEmpty() {
    SecurityIdentity identity =
        TestTokens.machine(CI, CI).claim(QitsClaims.PROJECT, "qits").build();

    assertEquals(Optional.of("qits"), MachineIdentity.claim(identity, QitsClaims.PROJECT));
    assertEquals(Optional.empty(), MachineIdentity.claim(identity, QitsClaims.WORKSPACE));
    assertEquals(Optional.empty(), MachineIdentity.claim(TestTokens.user("alice"), QitsClaims.PROJECT));
  }

  @Test
  void anAbsentClaimIsAMismatchNotAWildcard() {
    SecurityIdentity noClaims = TestTokens.machine(CI, CI).build();

    assertFalse(MachineIdentity.claimMatches(noClaims, QitsClaims.PROJECT, "qits"));
  }

  @Test
  void aClaimMatchIsExact() {
    SecurityIdentity identity =
        TestTokens.machine(CI, CI).claim(QitsClaims.PROJECT, "qits").build();

    assertTrue(MachineIdentity.claimMatches(identity, QitsClaims.PROJECT, "qits"));
    assertFalse(MachineIdentity.claimMatches(identity, QitsClaims.PROJECT, "qits-other"));
    assertFalse(MachineIdentity.claimMatches(identity, QitsClaims.PROJECT, null));
  }

  @Test
  void aWildcardClaimCoversEveryValue() {
    SecurityIdentity everyProject =
        TestTokens.machine(ARTIFACTS, CI)
            .claim(QitsClaims.PROJECT, QitsClaims.ANY)
            .build();

    assertTrue(MachineIdentity.claimMatches(everyProject, QitsClaims.PROJECT, "qits"));
    assertTrue(MachineIdentity.claimMatches(everyProject, QitsClaims.PROJECT, "anything-else"));
    assertTrue(MachineIdentity.matchesProject(everyProject, CI, "qits"));
    // The wildcard is on the claim it was granted for, not on the others.
    assertFalse(MachineIdentity.claimMatches(everyProject, QitsClaims.WORKSPACE, "ws-1"));
    // Still the wrong service.
    assertFalse(MachineIdentity.matchesProject(everyProject, WORKSPACES, "qits"));
  }

  @Test
  void askingAboutAStarIsAnOrdinaryQuestion() {
    // The caller names its target; a token for one project does not cover "every project" just
    // because the target spells the wildcard.
    SecurityIdentity oneProject =
        TestTokens.machine(CI, CI).claim(QitsClaims.PROJECT, "qits").build();

    assertFalse(MachineIdentity.claimMatches(oneProject, QitsClaims.PROJECT, QitsClaims.ANY));
  }

  @Test
  void theShorthandsWantBothTheAudienceAndTheClaim() {
    SecurityIdentity identity =
        TestTokens.machine(WORKSPACES, ARTIFACTS)
            .claim(QitsClaims.WORKSPACE, "ws-1")
            .claim(QitsClaims.BRANCH, "feature/x")
            .build();

    assertTrue(MachineIdentity.matchesWorkspace(identity, ARTIFACTS, "ws-1"));
    assertTrue(MachineIdentity.matchesBranch(identity, ARTIFACTS, "feature/x"));
    // Right claim, wrong service: a token minted for artifacts says nothing to qits-ci.
    assertFalse(MachineIdentity.matchesWorkspace(identity, CI, "ws-1"));
    assertFalse(MachineIdentity.matchesProject(identity, ARTIFACTS, "ws-1"));
  }
}
