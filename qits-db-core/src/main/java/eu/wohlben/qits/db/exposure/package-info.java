/**
 * The entity-exposure markers (qits-1945): three annotations a service puts on its own classes so
 * qits-arch-rules' {@code EntityExposureRules} can tell product data from plumbing.
 *
 * <p><b>The model.</b> Every {@code @jakarta.persistence.Entity} is a <em>domain entity</em> by
 * default, and a domain entity is something a person or another service should be able to read:
 * it must reach at least one JAX-RS operation through a DTO. The analysis finds that path in the
 * bytecode — a DTO's static factory or constructor reading the entity, a mapper method taking the
 * entity and returning the DTO, an assembler reading it and building the DTO, at most one carrier
 * in between — and then follows the DTO to the operations that return it. An entity with no such
 * path is a build failure, unless one of these says why:
 *
 * <ul>
 *   <li>{@link Technical} — the entity is plumbing (an n:n join, a storage row, bookkeeping), not
 *       product data, and is never meant to be read on its own.
 *   <li>{@link Projection} — the entity is this service's local copy of another service's entity;
 *       that service is where it is exposed.
 *   <li>{@link Exposes} — on a DTO, not an entity: the entities it carries, for the paths bytecode
 *       inference cannot see (reflection, a generic copier, a JSON column written by hand).
 * </ul>
 *
 * <p>A service that cannot decide yet lists the entity as a known gap in its arch test, with the
 * ticket that will close it. The markers carry no behaviour and no dependency: they are read from
 * bytecode by fully-qualified name, so qits-arch-rules never imports this package either.
 */
package eu.wohlben.qits.db.exposure;
