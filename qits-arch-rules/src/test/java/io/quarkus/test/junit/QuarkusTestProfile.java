package io.quarkus.test.junit;

/**
 * A TEST FIXTURE, not Quarkus' interface. {@code TestProfileBudgetRules} matches the real
 * {@code io.quarkus.test.junit.QuarkusTestProfile} by fully-qualified name — the rule's javadoc and
 * the module pom say why there is no {@code quarkus-junit5} dependency to import it from — so the
 * fixtures the self-test compiles must carry that exact name, package and all.
 *
 * <p>It is deliberately empty. The real interface is all default methods ({@code
 * getConfigOverrides}, {@code getTestResources}, {@code getConfigProfile}, …) and the rule reads
 * none of them: it counts implementations, and a name is the whole of what it needs. If Quarkus ever
 * moves the type, this file and {@link eu.wohlben.qits.archrules.TestProfileBudgetRules} change
 * together, and this suite is where the drift surfaces first.
 */
public interface QuarkusTestProfile {}
