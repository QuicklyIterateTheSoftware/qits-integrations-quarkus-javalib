package eu.wohlben.qits.db.exposure;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A TEST FIXTURE mirroring qits-db-core's real marker by name — see {@link Technical}. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Projection {
  String of();
}
