package eu.wohlben.qits.db.exposure;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A TEST FIXTURE mirroring qits-db-core's real marker by fully-qualified name, the way the {@code
 * eventstream} mirror does for {@code CausedRow}: {@code EntityExposureRules} matches the markers
 * by name and this module depends on no qits-db-core, so the fixtures compile against these three
 * files ({@link Projection}, {@link Exposes}) instead. A rename over there must change the rules'
 * constants and this mirror together; this suite is where a drift surfaces first.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Technical {
  String reason();
}
