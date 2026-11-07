package eu.wohlben.qits.archrules.exposure.fixture.markers;

import eu.wohlben.qits.db.exposure.Exposes;
import eu.wohlben.qits.db.exposure.Projection;
import eu.wohlben.qits.db.exposure.Technical;
import jakarta.persistence.Entity;

/** Each way of getting a marker wrong, beside one of each done right. */
public final class MarkerFixtures {

  private MarkerFixtures() {}

  @Entity
  @Technical(reason = "outbox rows the publisher drains")
  public static class FineTechnical {}

  @Entity
  @Projection(of = "qits-projects:Repository")
  public static class FineProjection {}

  @Exposes(FineTechnical.class)
  public record FineDto(String name) {}

  @Entity
  @Technical(reason = "  ")
  public static class BlankReason {}

  @Entity
  @Projection(of = "Repository")
  public static class BadProjection {}

  @Entity
  @Technical(reason = "a join")
  @Projection(of = "qits-projects:Repository")
  public static class BothMarkers {}

  @Technical(reason = "not an entity at all")
  public record TechnicalDto(String name) {}

  @Projection(of = "qits-projects:Repository")
  public record ProjectionDto(String name) {}

  @Entity
  @Exposes(FineTechnical.class)
  public static class ExposingEntity {}
}
