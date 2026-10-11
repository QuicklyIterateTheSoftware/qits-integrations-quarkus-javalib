package eu.wohlben.qits.http;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The written opt-out: this class builds a {@code java.net.http.HttpClient} and deliberately does
 * not pass it through {@link QitsHttpClients#wrap}. qits-arch-rules' {@code HttpClientRules} fails
 * a build on any other unwrapped client, so the exception is one reviewable line instead of a
 * silent hole in the trace.
 *
 * <p>The legitimate cases are calls that leave the platform — an external host has no use for
 * {@code X-Qits-Caller} and no business receiving the platform's trace ids — and transports that
 * must not trace themselves, such as an OTLP exporter whose every export would otherwise open a
 * span to be exported. "It was easier" is not one; say which it is in {@link #reason}.
 *
 * <p>CLASS retention on purpose: ArchUnit reads it from bytecode, and nothing needs it at runtime.
 * It goes on the top-level class — the rule counts a client built in a nested class or lambda
 * against the class that encloses it.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface RawHttpClient {

  /** Why this client stays unwrapped, for the reviewer who reads the annotation. */
  String reason();
}
