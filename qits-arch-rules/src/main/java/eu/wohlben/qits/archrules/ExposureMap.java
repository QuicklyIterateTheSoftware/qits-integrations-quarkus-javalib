package eu.wohlben.qits.archrules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;

/**
 * What {@link EntityExposure#analyze} found: for every {@code @Entity} of a service, the types it
 * reaches and the operations those types reach (qits-1945). Immutable, and sorted the way the JSON
 * map is rendered — entities by fully-qualified name, each entity's {@link DtoLink}s by (type,
 * kind, site), its {@link OperationLink}s by (operationId, method, path, dto) — so two analyses of
 * the same bytecode are equal and render the same bytes.
 *
 * <p>The map is the evidence the rules judge and the document a reviewer reads: the rules in
 * {@link EntityExposureRules} only ask whether {@link EntityNode#operations()} is empty, and the
 * JSON carries the whole path, so a red build and the reason for it are read off the same data.
 */
public final class ExposureMap {

  /** How an entity is classified by the qits-db-core markers. */
  public enum Marking {
    /** Unmarked: product data, which must reach an operation. */
    DOMAIN,
    /** {@code @Technical}: plumbing, exempt. */
    TECHNICAL,
    /** {@code @Projection}: another service's entity, exposed there, exempt. */
    PROJECTION;

    /** The lower-case spelling the JSON map uses. */
    public String wire() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** How an entity (or a carrier) is linked to a type. See {@link EntityExposure}. */
  public enum LinkKind {
    /** A static method or constructor declared on the type reads the entity. */
    FACTORY,
    /** A method with an entity parameter builds the type. */
    MAPPER,
    /** A method reads the entity without taking it as a parameter and builds the type. */
    ASSEMBLER,
    /** {@code @Exposes} on the type names the entity. */
    ANNOTATION,
    /** The entity links to a carrier, and the carrier links to the type: one hop. */
    VIA,
    /** The entity itself is what an operation returns. */
    DIRECT;

    /** The lower-case spelling the JSON map uses. */
    public String wire() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** How an operation hands out a type. See {@link EntityExposure}. */
  public enum OperationKind {
    /** The declared return type, unwrapped, is the type or holds it as a field. */
    RETURNS,
    /** The return type says nothing (a {@code Response}); the body builds the type. */
    BODY,
    /** {@code @APIResponse(content = @Content(schema = @Schema(implementation = ...)))}. */
    SCHEMA,
    /** The entity itself is returned. */
    DIRECT;

    /** The lower-case spelling the JSON map uses. */
    public String wire() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /**
   * One link from an entity to a type.
   *
   * @param type the linked type's fully-qualified name (the entity's own for {@link
   *     LinkKind#DIRECT})
   * @param kind how it is linked
   * @param via the carrier's fully-qualified name for {@link LinkKind#VIA}, otherwise null
   * @param site where the link was seen, {@code "Owner.method(ParamSimpleNames)"} or {@code
   *     "Owner.<init>(...)"}; for {@link LinkKind#ANNOTATION} {@code "@Exposes on Owner"}
   */
  public record DtoLink(String type, LinkKind kind, String via, String site) {
    static final Comparator<DtoLink> ORDER =
        Comparator.comparing(DtoLink::type)
            .thenComparing(link -> link.kind().wire())
            .thenComparing(DtoLink::site)
            .thenComparing(link -> link.via() == null ? "" : link.via());
  }

  /**
   * One operation that hands out one of an entity's linked types.
   *
   * @param operationId {@code @Operation(operationId)} if set, else {@code
   *     "<DeclaringSimpleName>.<method>"}
   * @param method the HTTP verb
   * @param path the class {@code @Path} and the method {@code @Path} joined, as written
   * @param dto the linked type the operation hands out
   * @param kind how it hands it out
   */
  public record OperationLink(
      String operationId, String method, String path, String dto, OperationKind kind) {
    static final Comparator<OperationLink> ORDER =
        Comparator.comparing(OperationLink::operationId)
            .thenComparing(OperationLink::method)
            .thenComparing(OperationLink::path)
            .thenComparing(OperationLink::dto)
            .thenComparing(link -> link.kind().wire());
  }

  /**
   * One entity and everything it reaches.
   *
   * @param entity fully-qualified name
   * @param simpleName the simple name known gaps are keyed by
   * @param table {@code @Table(name)}, or null where there is none or it is empty
   * @param marking the qits-db-core marker, or {@link Marking#DOMAIN}
   * @param reason {@code @Technical(reason)}, or null
   * @param projectionOf {@code @Projection(of)}, or null
   * @param dtos the types the entity links to, sorted, deduplicated
   * @param operations the operations those types reach, sorted, deduplicated
   */
  public record EntityNode(
      String entity,
      String simpleName,
      String table,
      Marking marking,
      String reason,
      String projectionOf,
      List<DtoLink> dtos,
      List<OperationLink> operations) {

    public EntityNode {
      TreeSet<DtoLink> sortedDtos = new TreeSet<>(DtoLink.ORDER);
      sortedDtos.addAll(dtos);
      dtos = List.copyOf(sortedDtos);
      TreeSet<OperationLink> sortedOperations = new TreeSet<>(OperationLink.ORDER);
      sortedOperations.addAll(operations);
      operations = List.copyOf(sortedOperations);
    }

    /** At least one operation hands it out. */
    public boolean exposed() {
      return !operations.isEmpty();
    }

    /** Carries {@code @Technical} or {@code @Projection}. */
    public boolean marked() {
      return marking != Marking.DOMAIN;
    }
  }

  private final List<EntityNode> entities;

  ExposureMap(List<EntityNode> entities) {
    List<EntityNode> sorted = new ArrayList<>(entities);
    sorted.sort(Comparator.comparing(EntityNode::entity));
    this.entities = List.copyOf(sorted);
  }

  /** Every imported entity, sorted by fully-qualified name. */
  public List<EntityNode> entities() {
    return entities;
  }

  /** The entity of this fully-qualified name, if it was imported. */
  public Optional<EntityNode> entity(String fullyQualifiedName) {
    return entities.stream().filter(node -> node.entity().equals(fullyQualifiedName)).findFirst();
  }

  /** Every entity of this simple name — the key a known gap uses — usually one, possibly none. */
  public List<EntityNode> entitiesNamed(String simpleName) {
    return entities.stream().filter(node -> node.simpleName().equals(simpleName)).toList();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ExposureMap map && map.entities.equals(entities);
  }

  @Override
  public int hashCode() {
    return entities.hashCode();
  }

  @Override
  public String toString() {
    return "ExposureMap" + entities;
  }
}
