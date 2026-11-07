package eu.wohlben.qits.db.exposure;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an {@code @Entity} as this service's local copy of another service's entity — rows fed
 * from an event stream or a sync, kept here to join against. The owning service is where the
 * entity is exposed, so qits-arch-rules' {@code everyDomainEntityIsExposed} does not ask this one
 * to reach an operation as well.
 *
 * <p>{@link #of()} names the original as {@code "<application>:<EntitySimpleName>"} — the
 * deployed application name, not the repository name, and the entity's simple class name there:
 * {@code @Projection(of = "qits-projects:Repository")}. {@code markersAreWellFormed} checks the
 * shape ({@code ^[a-z0-9-]+:[A-Z][A-Za-z0-9]*$}); that the named entity exists is for the owning
 * service's own map to show.
 *
 * <p>Only on an entity, and never together with {@link Technical}. See the package javadoc.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Projection {

  /** The original, as {@code "<application>:<EntitySimpleName>"}, e.g. {@code "qits-projects:Repository"}. */
  String of();
}
