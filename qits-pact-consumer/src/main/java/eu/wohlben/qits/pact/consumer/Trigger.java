package eu.wohlben.qits.pact.consumer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What made the consumer make the call: the {@code qits-trigger} reference every interaction
 * carries, and the first half of its description.
 *
 * <p>{@code app} is the application or repository the trigger belongs to. Leave it null and
 * {@link ConsumerPact} fills in its consumer, which is right for every trigger except a UI one
 * that another app owns.
 *
 * @param kind {@code operation}, {@code command}, {@code event}, {@code schedule}, {@code ui}, or
 *     another kind the consumer needs
 * @param key the name of the trigger's own value: {@code operationId}, {@code command}, …
 * @param value the trigger's own value: the operation, the command, the event type, …
 */
public record Trigger(String kind, String app, String key, String value) {

  public Trigger {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(value, "value");
  }

  /** Any kind, owned by the consumer. */
  public static Trigger of(String kind, String key, String value) {
    return new Trigger(kind, null, key, value);
  }

  /** One of the consumer's own operations makes the call. */
  public static Trigger operation(String operationId) {
    return of("operation", "operationId", operationId);
  }

  /** A command a person or an agent types ({@code qits work details}). */
  public static Trigger command(String command) {
    return of("command", "command", command);
  }

  /** An event the consumer reacts to. */
  public static Trigger event(String event) {
    return of("event", "event", event);
  }

  /** A schedule the consumer runs on. */
  public static Trigger schedule(String schedule) {
    return of("schedule", "schedule", schedule);
  }

  /** A UI interaction in {@code app}. */
  public static Trigger ui(String app, String interaction) {
    return new Trigger("ui", app, "interaction", interaction);
  }

  /** The same trigger, owned by {@code app}. */
  public Trigger from(String app) {
    return new Trigger(kind, app, key, value);
  }

  /** The {@code qits-trigger} group: kind, app, then the trigger's own key, all strings. */
  public Map<String, String> reference(String defaultApp) {
    Map<String, String> ref = new LinkedHashMap<>();
    ref.put("kind", kind);
    ref.put("app", app == null ? defaultApp : app);
    ref.put(key, value);
    return ref;
  }
}
