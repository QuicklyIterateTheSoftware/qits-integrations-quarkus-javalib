package eu.wohlben.qits.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.Test;

/**
 * The gate and the checks behind it. The gate half is the important half: off must mean "exactly as
 * today" for every caller, however wrong the identity looks.
 */
class MachineAuthTest {

  /** The one audience every token on the platform carries. */
  private static final String PLATFORM = "qits-platform";

  // Client ids carry their environment, so they are spelled here rather than in QitsClaims.
  private static final String CI = "prod-qits-ci";
  private static final String ARTIFACTS = "prod-qits-artifacts";

  private static final SecurityIdentity CI_TOKEN_FOR_QITS =
      TestTokens.machine(CI, PLATFORM).claim(QitsClaims.PROJECT, "qits").build();

  private static MachineAuth gateOff(SecurityIdentity identity) {
    return new MachineAuth(false, identity);
  }

  private static MachineAuth gateOn(SecurityIdentity identity) {
    return new MachineAuth(true, identity);
  }

  @Test
  void gateOffPassesEveryCallerThroughUntouched() {
    for (SecurityIdentity identity :
        new SecurityIdentity[] {
          TestTokens.anonymous(),
          TestTokens.user("alice"),
          TestTokens.machine("someone-else", "somewhere-else").build()
        }) {
      MachineAuth auth = gateOff(identity);
      assertFalse(auth.enforced());
      assertDoesNotThrow(auth::require);
      assertDoesNotThrow(() -> auth.requireProject("qits"));
      assertDoesNotThrow(() -> auth.requireWorkspace("ws-1"));
      assertDoesNotThrow(() -> auth.requireBranch("main"));
      assertTrue(auth.permits(QitsClaims.PROJECT, "qits"));
    }
  }

  @Test
  void gateOnAcceptsTheMatchingToken() {
    MachineAuth auth = gateOn(CI_TOKEN_FOR_QITS);

    assertTrue(auth.enforced());
    assertDoesNotThrow(auth::require);
    assertDoesNotThrow(() -> auth.requireProject("qits"));
    assertDoesNotThrow(() -> auth.requireClaim(QitsClaims.PROJECT, "qits"));
    assertTrue(auth.permits(QitsClaims.PROJECT, "qits"));
  }

  @Test
  void gateOnRejectsAMismatchedProject() {
    MachineAuth auth = gateOn(CI_TOKEN_FOR_QITS);

    assertThrows(ForbiddenException.class, () -> auth.requireProject("some-other-project"));
    assertFalse(auth.permits(QitsClaims.PROJECT, "some-other-project"));
  }

  @Test
  void gateOnAcceptsAWildcardTokenForAnyTarget() {
    // How the git-host holds its grant: one client acting for every project.
    MachineAuth auth =
        gateOn(
            TestTokens.machine(ARTIFACTS, PLATFORM)
                .claim(QitsClaims.PROJECT, QitsClaims.ANY)
                .build());

    assertDoesNotThrow(() -> auth.requireProject("qits"));
    assertDoesNotThrow(() -> auth.requireProject("some-other-project"));
    assertTrue(auth.permits(QitsClaims.PROJECT, "qits"));
    // One wildcard claim does not grant the others.
    assertThrows(ForbiddenException.class, () -> auth.requireWorkspace("ws-1"));
  }

  @Test
  void gateOnRejectsAnUngrantedClaim() {
    MachineAuth auth = gateOn(TestTokens.machine(CI, PLATFORM).build());

    assertThrows(ForbiddenException.class, () -> auth.requireProject("qits"));
  }

  @Test
  void gateOnRejectsANonMachineCaller() {
    // A 401, not a 403: no machine credential was presented at all, so the answer is "show one".
    assertThrows(UnauthorizedException.class, () -> gateOn(TestTokens.user("alice")).require());
    assertThrows(
        UnauthorizedException.class, () -> gateOn(TestTokens.anonymous()).requireProject("qits"));
    assertFalse(gateOn(TestTokens.anonymous()).permits(QitsClaims.PROJECT, "qits"));
  }

  @Test
  void gateOnRejectsATokenWithoutThePlatformAudience() {
    // The only audience qits-idp mints is qits-platform, so anything else is a token this platform
    // did not issue for this platform. A 403, not a 401: the caller authenticated.
    SecurityIdentity elsewhere =
        TestTokens.machine(CI, "somewhere-else").claim(QitsClaims.PROJECT, "qits").build();
    MachineAuth auth = gateOn(elsewhere);

    assertThrows(ForbiddenException.class, auth::require);
    assertThrows(ForbiddenException.class, () -> auth.requireProject("qits"));
    assertFalse(auth.permits(QitsClaims.PROJECT, "qits"));
  }

  @Test
  void gateOnRejectsATokenWithNoAudienceAtAll() {
    MachineAuth auth =
        gateOn(TestTokens.machine(CI).claim(QitsClaims.PROJECT, "qits").build());

    assertThrows(ForbiddenException.class, auth::require);
    assertFalse(auth.permits(QitsClaims.PROJECT, "qits"));
  }

  @Test
  void aBlankPlatformAudienceRefusesEveryEnforcedCall() {
    // Blanking the only audience there is names nothing a token could carry, so it denies rather
    // than opening the door — and a token with a blank `aud` does not sneak through either.
    MachineAuth auth = new MachineAuth(true, "", CI_TOKEN_FOR_QITS);

    assertThrows(ForbiddenException.class, auth::require);
    assertThrows(ForbiddenException.class, () -> auth.requireProject("qits"));
    assertFalse(auth.permits(QitsClaims.PROJECT, "qits"));
    assertThrows(
        ForbiddenException.class,
        () -> new MachineAuth(true, "", TestTokens.machine(CI, "").build()).require());
  }
}
