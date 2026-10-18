package eu.wohlben.qits.pact.consumer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * <b>The library a call goes through</b> (ticket qits-1163): for a pact of a call a library makes
 * on behalf of a consuming service, which library, and which version of it the consuming service
 * pins. {@link ConsumerPact#via} attaches one.
 *
 * @param repository the library's repository name, as a pact names it ({@code
 *     qits-eventstream-javalib})
 * @param artifact the library's maven coordinate, {@code groupId:artifactId} ({@code
 *     eu.wohlben.qits:qits-eventstream})
 * @param version the version of {@code artifact} the consuming service pins
 */
public record Via(String repository, String artifact, String version) {

  public Via {
    Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(artifact, "artifact");
    Objects.requireNonNull(version, "version");
  }

  /** The {@code qits-via} group: repository, artifact, then version, all strings. */
  public Map<String, String> reference() {
    Map<String, String> ref = new LinkedHashMap<>();
    ref.put("repository", repository);
    ref.put("artifact", artifact);
    ref.put("version", version);
    return ref;
  }
}
