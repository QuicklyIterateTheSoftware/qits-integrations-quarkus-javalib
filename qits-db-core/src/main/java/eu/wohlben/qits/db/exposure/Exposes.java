package eu.wohlben.qits.db.exposure;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares, on a DTO, the entities it carries — for the paths qits-arch-rules' bytecode inference
 * cannot see. The analysis links an entity to a DTO on its own when a factory, constructor, mapper
 * or assembler reads the one and builds the other; this annotation is for the rest: a reflective
 * or generic copier, a value read through a native query, a DTO assembled from a JSON column.
 *
 * <p>It is a claim a reviewer reads, not an inference, so prefer the code path the analysis sees
 * and reach for this only where there is none. Never on an entity: an entity is linked to itself
 * when an operation returns it directly, and {@code markersAreWellFormed} fails {@code @Exposes}
 * there. See the package javadoc.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Exposes {

  /** The entity classes this DTO carries. */
  Class<?>[] value();
}
