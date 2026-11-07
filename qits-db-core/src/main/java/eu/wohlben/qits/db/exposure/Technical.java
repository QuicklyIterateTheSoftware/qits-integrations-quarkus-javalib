package eu.wohlben.qits.db.exposure;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an {@code @Entity} as plumbing rather than product data: an n:n join table, a storage or
 * outbox row, a cursor, a lock — something no operation should hand out on its own, and that
 * qits-arch-rules' {@code everyDomainEntityIsExposed} therefore does not ask to reach one.
 *
 * <p>The reason is required and must not be blank: the marker is an opt-out a reviewer reads, and
 * "technical" alone says nothing a reviewer can check. Say what the rows are for, e.g.
 * {@code @Technical(reason = "n:n join of repositories and projects")}.
 *
 * <p>Only on an entity, and never together with {@link Projection}; {@code markersAreWellFormed}
 * fails either misuse. See the package javadoc for the whole model.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Technical {

  /** Why these rows are plumbing and not product data. Required, not blank. */
  String reason();
}
