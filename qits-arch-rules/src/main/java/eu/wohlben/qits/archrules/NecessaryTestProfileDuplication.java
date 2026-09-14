package eu.wohlben.qits.archrules;

/**
 * The written, priced exception to {@link TestProfileBudgetRules}: a second (third, fourth…)
 * {@code QuarkusTestProfile} in a module that genuinely needs one.
 *
 * <pre>{@code
 * // The keycloak-less profile. It cannot merge into ServiceProfile: that one boots the OIDC
 * // client against MockIdp, and these tests exist to prove the 401 path when no issuer answers
 * // at all - a config the other profile cannot also hold. (In a real class this reason belongs
 * // in the javadoc, where a reviewer reads it.)
 * public class NoIssuerProfile implements QuarkusTestProfile, NecessaryTestProfileDuplication {}
 * }</pre>
 *
 * <p><b>What implementing this costs.</b> It is not an annotation that silences a test; it is a
 * purchase, and the price is fixed:
 *
 * <ul>
 *   <li><b>A Quarkus restart.</b> Every distinct profile is a distinct application. The surefire
 *       fork stops the running one and boots another, and the suite pays that boot in wall clock.
 *   <li><b>About 125 MB of metaspace that is never given back.</b> The stopped application's
 *       classloader is <em>retained</em> for the rest of the fork. This is not a leak to be fixed by
 *       a flag: {@code -XX:MaxMetaspaceSize} does not reclaim it, it only converts the eventual
 *       {@code SIGKILL} into an {@code OutOfMemoryError: Metaspace}.
 *   <li><b>A step container capped at 4 g.</b> qits-ci runs every build step under a hard
 *       {@code QITS_CI_MEMORY_LIMIT=4g}, and the kernel's OOM killer does not negotiate: the run
 *       ends {@code Process Exit Code: 137} with no stack trace and no failing test to read.
 * </ul>
 *
 * <p>So the budget is roughly a dozen profiles per module before the fork is dead, and every module
 * in the fork spends from the same 4 g. Measured on qits-workspaces-service, 2026-09-14: fourteen
 * applications in one module's suite, 1.71 GiB committed metaspace, a 3.41 GiB fork, and four
 * consecutive CI gates killed at 137.
 *
 * <p><b>Say why in the javadoc of the class that implements this.</b> The marker records that
 * somebody decided; the javadoc records what they decided and on what grounds, which is the only
 * part a reviewer can disagree with. "It needs a different config map" is not a reason — that is
 * true of every profile, including the accidental ones this rule was written for, which all differed
 * only because each minted a fresh temp directory for a config key and so was unequal to every other
 * <em>by construction</em>. The reason has to be why the two configurations cannot be one.
 *
 * <p>This is an ordinary interface with no members, kept here beside the rule rather than in any one
 * service, so that any project enabling {@code qits-arch-rules} can implement it without another
 * dependency. The rule matches it through the class hierarchy ArchUnit imported, so a profile may
 * inherit it from a base class as long as that base class is on the import path.
 */
public interface NecessaryTestProfileDuplication {}
