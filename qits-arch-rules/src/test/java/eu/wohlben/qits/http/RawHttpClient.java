package eu.wohlben.qits.http;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A TEST FIXTURE mirroring the real annotation's name — see {@link QitsHttpClients}. CLASS
 * retention, as the real one: the self-test doubles as the proof that the rule reads it from
 * bytecode.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface RawHttpClient {
  String reason();
}
