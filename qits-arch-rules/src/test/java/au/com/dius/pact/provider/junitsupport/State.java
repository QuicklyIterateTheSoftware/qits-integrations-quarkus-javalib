package au.com.dius.pact.provider.junitsupport;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A TEST FIXTURE, not pact-jvm's annotation. {@code ProviderStateCoverageRules} matches the real
 * {@code au.com.dius.pact.provider.junitsupport.State} by fully-qualified name and invokes its
 * {@code value()} by name — the rule's javadoc and the module pom say why there is no pact-jvm
 * dependency to import it from — so the fixtures the self-test compiles must carry that exact name,
 * package and all.
 *
 * <p>Only {@code value()} is mirrored, as the {@code String[]} pact-jvm declares; the real
 * annotation's other members ({@code action}, {@code comment}) are none of the rule's business. If
 * pact-jvm ever moves or reshapes the type, this file and the rule change together, and this suite
 * is where the drift surfaces first.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface State {
  String[] value();
}
