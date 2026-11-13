package eu.wohlben.qits.offline;

import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The version check an offline update or delete makes, inside its write transaction, after it has
 * loaded the record:
 *
 * <pre>{@code
 * @PUT @Path("{id}") @OfflineCapable
 * public Thing update(@PathParam("id") String id, @HeaderParam("If-Match") String ifMatch, ThingInput in) {
 *   Thing current = things.load(id);
 *   RecordVersions.require(ifMatch, current.version(), () -> current);
 *   ...
 * }
 * }</pre>
 *
 * A {@code POST} create takes no {@code If-Match}: there is no version to name, and {@code
 * Idempotency-Key} is what makes its replay safe.
 */
public final class RecordVersions {

  private static final Pattern STRONG = Pattern.compile("\"(\\d{1,18})\"|(\\d{1,18})");

  private RecordVersions() {}

  /**
   * Passes only when {@code ifMatch} names exactly {@code currentVersion}.
   *
   * @throws VersionRequiredException when {@code ifMatch} is null, blank, {@code *}, weak ({@code
   *     W/…}) or not {@code "<digits>"} (the quotes are optional)
   * @throws StaleVersionException when it names another version; {@code current} is called only
   *     then
   */
  public static void require(String ifMatch, long currentVersion, Supplier<?> current) {
    long named = parse(ifMatch);
    if (named != currentVersion) {
      throw new StaleVersionException(current == null ? null : current.get(), currentVersion);
    }
  }

  /** The strong entity tag for a version: {@code "<version>"}, quotes included. */
  public static String etag(long version) {
    return "\"" + version + "\"";
  }

  static long parse(String ifMatch) {
    if (ifMatch == null || ifMatch.isBlank()) {
      throw new VersionRequiredException(
          "This write needs If-Match naming the version it was made against.");
    }
    String value = ifMatch.trim();
    if (value.equals("*")) {
      throw new VersionRequiredException(
          "If-Match: * would overwrite any version. Name the version this write was made against.");
    }
    Matcher matcher = STRONG.matcher(value);
    if (!matcher.matches()) {
      throw new VersionRequiredException(
          "If-Match must be one strong version, \"<digits>\"; got " + abbreviate(value) + ".");
    }
    return Long.parseLong(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
  }

  private static String abbreviate(String value) {
    return value.length() <= 40 ? value : value.substring(0, 40) + "…";
  }
}
