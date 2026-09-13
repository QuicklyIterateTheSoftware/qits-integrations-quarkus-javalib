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

  // Service ids carry their environment, so they are spelled here rather than in QitsClaims.
  private static final String CI = "prod-qits-ci";
  private static final String ARTIFACTS = "prod-qits-artifacts";
  private static final String WORKSPACES = "prod-qits-workspaces";

  private static final SecurityIdentity CI_TOKEN_FOR_QITS =
      TestTokens.machine(CI, CI).claim(QitsClaims.PROJECT, "qits").build();

  private static MachineAuth gateOff(SecurityIdentity identity) {
    return new MachineAuth(false, CI, identity);
  }

  private static MachineAuth gateOn(SecurityIdentity identity) {
    return new MachineAuth(true, CI, identity);
  }

  @Test
  void gateOffPassesEveryCallerThroughUntouched() {
    for (SecurityIdentity identity :
        new SecurityIdentity[] {
          TestTokens.anonymous(),
          TestTokens.user("alice"),
          TestTokens.machine("someone-else", ARTIFACTS).build()
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
            TestTokens.machine(ARTIFACTS, CI)
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
    MachineAuth auth = gateOn(TestTokens.machine(CI, CI).build());

    assertThrows(ForbiddenException.class, () -> auth.requireProject("qits"));
  }

  @Test
  void gateOnRejectsATokenMeantForAnotherService() {
    MachineAuth auth =
        gateOn(
            TestTokens.machine(WORKSPACES, ARTIFACTS)
                .claim(QitsClaims.PROJECT, "qits")
                .build());

    // A 403, not a 401: the caller authenticated, it just is not talking to its own service.
    assertThrows(ForbiddenException.class, auth::require);
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
  void gateOnAcceptsATokenAddressedOnlyToThePlatformAudience() {
    // One audience for every token (rulings 2026-09-13): a token naming the shared platform
    // audience instead of this service's own id still passes.
    SecurityIdentity platformToken =
        TestTokens.machine(CI, "qits-platform").claim(QitsClaims.PROJECT, "qits").build();
    MachineAuth auth = gateOn(platformToken);

    assertDoesNotThrow(auth::require);
    assertDoesNotThrow(() -> auth.requireProject("qits"));
    assertDoesNotThrow(() -> auth.requireClaim(QitsClaims.PROJECT, "qits"));
    assertTrue(auth.permits(QitsClaims.PROJECT, "qits"));
  }

  @Test
  void gateOnRejectsATokenWithNeitherTheOwnNorThePlatformAudience() {
    SecurityIdentity elsewhere =
        TestTokens.machine(CI, "prod-qits-someone-else").claim(QitsClaims.PROJECT, "qits").build();
    MachineAuth auth = gateOn(elsewhere);

    // A 403, exactly today's refusal for a token minted for another service.
    assertThrows(ForbiddenException.class, auth::require);
    assertThrows(ForbiddenException.class, () -> auth.requireProject("qits"));
    assertFalse(auth.permits(QitsClaims.PROJECT, "qits"));
  }

  @Test
  void aBlankPlatformAudienceRestoresTheOldBehaviour() {
    SecurityIdentity platformToken =
        TestTokens.machine(CI, "qits-platform").claim(QitsClaims.PROJECT, "qits").build();
    MachineAuth auth = new MachineAuth(true, CI, "", platformToken);

    assertThrows(ForbiddenException.class, auth::require);
    assertFalse(auth.permits(QitsClaims.PROJECT, "qits"));
  }

  @Test
  void turningTheGateOnWithNoAudienceFailsAtStartup() {
    // Failing the deploy beats accepting a token minted for a different service.
    MachineAuth auth = new MachineAuth(true, null, TestTokens.anonymous());

    assertThrows(IllegalStateException.class, () -> auth.validateConfig(null));
    assertDoesNotThrow(() -> new MachineAuth(false, null, TestTokens.anonymous()).validateConfig(null));
  }
}
