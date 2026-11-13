package eu.wohlben.qits.offline;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A TEST FIXTURE mirroring qits-offline-core's real annotation by fully-qualified name, the way the
 * {@code db.exposure} mirror does for qits-db-core's markers: {@code OfflineCapableRules} and
 * {@code EntityExposure} match it by name and this module depends on no qits-offline-core, so the
 * fixtures compile against this file, {@link OfflineRecord}, {@link ChangesPage} and {@link
 * Tombstone} instead. The real one is also an {@code @InterceptorBinding}, which the rules never
 * read. A rename over there must change the rules' constants and this mirror together; this suite
 * is where a drift surfaces first.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface OfflineCapable {}
