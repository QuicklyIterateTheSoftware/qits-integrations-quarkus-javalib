package eu.wohlben.qits.archrules;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaGenericArrayType;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.domain.JavaTypeVariable;
import com.tngtech.archunit.core.domain.JavaWildcardType;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The offline conventions (qits-2148): an operation an offline client may queue and replay —
 * qits-offline-core's {@code @OfflineCapable} on the operation or on its declaring class — is
 * built the way that module's README says, so the client can rely on it.
 *
 * <p>An <b>operation</b> is a method annotated with one of jakarta.ws.rs' {@code GET}, {@code
 * POST}, {@code PUT}, {@code PATCH}, {@code DELETE}. Its <b>answer</b> is its declared return type
 * looked through {@code Uni}, {@code RestResponse}, {@code CompletionStage}, {@code
 * CompletableFuture} and {@code Optional}, as {@link EntityExposure}'s cardinality looks through
 * them ({@code Uni<RestResponse<List<Dto>>>} answers {@code List<Dto>}). Four rules:
 *
 * <ul>
 *   <li>{@link #updatesTakeIfMatch()} — a {@code PUT}, {@code PATCH} or {@code DELETE} takes a
 *       {@code @HeaderParam("If-Match")} parameter (the header name matched case-insensitively),
 *       the version the client read, for {@code RecordVersions.require}. A {@code POST} create
 *       needs none: {@code Idempotency-Key} covers it.
 *   <li>{@link #collectionReadsTakeSince()} — a {@code GET} never answers a collection, an array,
 *       a stream, a map or a {@code Multi}: a collection is read as the changes since a cursor,
 *       {@code ChangesPage<T>}, deletions included. A {@code GET} answering {@code ChangesPage}
 *       takes a {@code @QueryParam("since")} parameter.
 *   <li>{@link #readsAnswerOfflineRecords()} — what a {@code GET} answers, or {@code ChangesPage}'s
 *       type argument, implements {@code eu.wohlben.qits.offline.OfflineRecord}, so the client can
 *       store it by id and replace it only by a higher version.
 *   <li>{@link #operationsCarryOfflineMark()} — the method carries MicroProfile OpenAPI's {@code
 *       @Extension(name = "x-qits-offline")}, directly or inside {@code @Extensions}. MP OpenAPI
 *       does not read meta-annotations, so {@code @OfflineCapable} cannot add it; the exported
 *       {@code docs/openapi.yml} is where the PWA lint rules (qits-2150) read which operations are
 *       offline.
 * </ul>
 *
 * <p><b>What cannot be checked passes.</b> A {@code GET} answering a raw {@code
 * jakarta.ws.rs.core.Response} (or a raw {@code RestResponse}, {@code Uni}, a type variable, a
 * wildcard without a bound, {@code Object}) names no type in its bytecode, so {@link
 * #collectionReadsTakeSince()} and {@link #readsAnswerOfflineRecords()} let it pass: write such an
 * operation with a typed answer where it can be one, or keep its contract by review. A {@code
 * ChangesPage} without a type argument passes {@link #readsAnswerOfflineRecords()} the same way.
 *
 * <p>Enable them in a service with a test-scope dependency on this module:
 *
 * <pre>{@code
 * @AnalyzeClasses(packages = "eu.wohlben.qits.myservice",
 *     importOptions = ImportOption.DoNotIncludeTests.class)
 * class OfflineCapableTest {
 *   @ArchTest
 *   static final ArchRule IF_MATCH = OfflineCapableRules.updatesTakeIfMatch();
 *
 *   @ArchTest
 *   static final ArchRule SINCE = OfflineCapableRules.collectionReadsTakeSince();
 *
 *   @ArchTest
 *   static final ArchRule RECORDS = OfflineCapableRules.readsAnswerOfflineRecords();
 *
 *   @ArchTest
 *   static final ArchRule MARK = OfflineCapableRules.operationsCarryOfflineMark();
 * }
 * }</pre>
 *
 * <p>{@link #all()} folds the four into one {@link CompositeArchRule} for a service that wants a
 * single line; separate fields report each rule on its own test. Every rule is {@code
 * allowEmptyShould(true)}, for {@link CausationRowRules}' reason: a service with no offline
 * operation — most of them — passes.
 *
 * <p><b>Every judged type is named as a string, never imported</b>, for {@link CausationRowRules}'
 * reasons: this module carries no JAX-RS, MicroProfile OpenAPI, Mutiny or RESTEasy Reactive, and
 * no qits-offline-core either. A rename over there silently un-matches here; the fixture mirror of
 * qits-offline-core's types in this module's test sources, beside the real JAX-RS and MicroProfile
 * OpenAPI annotations, is where that surfaces first.
 */
public final class OfflineCapableRules {

  static final String OFFLINE_RECORD = "eu.wohlben.qits.offline.OfflineRecord";
  static final String HEADER_PARAM = "jakarta.ws.rs.HeaderParam";
  static final String QUERY_PARAM = "jakarta.ws.rs.QueryParam";
  static final String RESPONSE = "jakarta.ws.rs.core.Response";
  static final String EXTENSION =
      "org.eclipse.microprofile.openapi.annotations.extensions.Extension";
  static final String EXTENSIONS =
      "org.eclipse.microprofile.openapi.annotations.extensions.Extensions";

  /** The OpenAPI extension every offline operation carries. */
  public static final String OFFLINE_EXTENSION = "x-qits-offline";

  private static final Set<String> VERBS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
  private static final Set<String> UPDATES = Set.of("PUT", "PATCH", "DELETE");

  private OfflineCapableRules() {}

  /** Every offline {@code PUT}, {@code PATCH} and {@code DELETE} takes the version it read. */
  public static ArchRule updatesTakeIfMatch() {
    return methods()
        .that(areOfflineOperations(UPDATES))
        .should(takeIfMatch())
        .because(
            "an offline update replayed against a changed record must be refused, never applied:"
                + " the server is authoritative and never last-write-wins (qits-2148)")
        .allowEmptyShould(true);
  }

  /** No offline {@code GET} answers a whole collection; a {@code ChangesPage} read takes since. */
  public static ArchRule collectionReadsTakeSince() {
    return methods()
        .that(areOfflineOperations(Set.of("GET")))
        .should(readCollectionsAsChangesSince())
        .because(
            "a client keeps a collection by reading what changed since its cursor, deletions"
                + " included; a whole collection says nothing about the rows deleted (qits-2148)")
        .allowEmptyShould(true);
  }

  /** What every offline {@code GET} answers is an {@code OfflineRecord}. */
  public static ArchRule readsAnswerOfflineRecords() {
    return methods()
        .that(areOfflineOperations(Set.of("GET")))
        .should(answerOfflineRecords())
        .because(
            "a client stores what it read by id and replaces it only by a higher version, so"
                + " every record it reads carries both (qits-2148)")
        .allowEmptyShould(true);
  }

  /** Every offline operation carries {@code @Extension(name = "x-qits-offline")}. */
  public static ArchRule operationsCarryOfflineMark() {
    return methods()
        .that(areOfflineOperations(VERBS))
        .should(carryOfflineMark())
        .because(
            "the PWA lint rules read which operations are offline from the OpenAPI, and"
                + " MicroProfile OpenAPI does not read @OfflineCapable (qits-2148)")
        .allowEmptyShould(true);
  }

  /** The four rules as one, for a service that wants a single {@code @ArchTest} line. */
  public static ArchRule all() {
    return CompositeArchRule.of(updatesTakeIfMatch())
        .and(collectionReadsTakeSince())
        .and(readsAnswerOfflineRecords())
        .and(operationsCarryOfflineMark());
  }

  // ---------------------------------------------------------------------------------------------

  private static DescribedPredicate<JavaMethod> areOfflineOperations(Set<String> verbs) {
    return DescribedPredicate.describe(
        "are @OfflineCapable " + String.join("/", new TreeSet<>(verbs)) + " operations",
        method ->
            EntityExposure.verb(method).filter(verbs::contains).isPresent()
                && EntityExposure.offline(method));
  }

  private static ArchCondition<JavaMethod> takeIfMatch() {
    return new ArchCondition<>("take an @HeaderParam(\"If-Match\") parameter") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        String site = EntityExposure.site(method);
        if (hasParameter(method, HEADER_PARAM, "If-Match", true)) {
          events.add(SimpleConditionEvent.satisfied(method, site + " takes If-Match"));
        } else {
          events.add(
              SimpleConditionEvent.violated(
                  method,
                  site
                      + " is an offline "
                      + EntityExposure.verb(method).orElseThrow()
                      + " without an @HeaderParam(\"If-Match\") parameter (qits-2148); take"
                      + " @HeaderParam(\"If-Match\") String ifMatch and check it with"
                      + " RecordVersions.require"));
        }
      }
    };
  }

  private static ArchCondition<JavaMethod> readCollectionsAsChangesSince() {
    return new ArchCondition<>(
        "answer a collection only as ChangesPage, with an @QueryParam(\"since\") parameter") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        String site = EntityExposure.site(method);
        JavaType answered = EntityExposure.answered(method.getReturnType());
        if (isChangesPage(answered)) {
          if (hasParameter(method, QUERY_PARAM, "since", false)) {
            events.add(SimpleConditionEvent.satisfied(method, site + " reads changes since"));
          } else {
            events.add(
                SimpleConditionEvent.violated(
                    method,
                    site
                        + " answers ChangesPage without an @QueryParam(\"since\") parameter"
                        + " (qits-2148); take @QueryParam(\"since\") String since and pass it to"
                        + " Changes.page"));
          }
        } else if (EntityExposure.plural(answered)) {
          events.add(
              SimpleConditionEvent.violated(
                  method,
                  site
                      + " is an offline GET answering a collection, "
                      + answered.getName()
                      + " (qits-2148); answer ChangesPage<T> and take @QueryParam(\"since\")"
                      + " String since"));
        } else {
          events.add(SimpleConditionEvent.satisfied(method, site + " answers no collection"));
        }
      }
    };
  }

  private static ArchCondition<JavaMethod> answerOfflineRecords() {
    return new ArchCondition<>("answer an " + OFFLINE_RECORD) {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        String site = EntityExposure.site(method);
        Optional<JavaClass> record = recordType(method.getReturnType());
        if (record.isEmpty()) {
          events.add(
              SimpleConditionEvent.satisfied(
                  method, site + " answers nothing the bytecode can name; not checked"));
        } else if (record.get().isAssignableTo(OFFLINE_RECORD)) {
          events.add(SimpleConditionEvent.satisfied(method, site + " answers an OfflineRecord"));
        } else {
          events.add(
              SimpleConditionEvent.violated(
                  method,
                  site
                      + " is an offline GET answering "
                      + record.get().getName()
                      + ", which does not implement "
                      + OFFLINE_RECORD
                      + " (qits-2148); give it id() and version() and implement the interface"));
        }
      }
    };
  }

  private static ArchCondition<JavaMethod> carryOfflineMark() {
    return new ArchCondition<>(
        "carry @Extension(name = \"" + OFFLINE_EXTENSION + "\", value = \"true\")") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        String site = EntityExposure.site(method);
        if (hasOfflineExtension(method)) {
          events.add(SimpleConditionEvent.satisfied(method, site + " carries the offline mark"));
        } else {
          events.add(
              SimpleConditionEvent.violated(
                  method,
                  site
                      + " is @OfflineCapable without @Extension(name = \""
                      + OFFLINE_EXTENSION
                      + "\", value = \"true\") (qits-2148); add it to the method, since"
                      + " MicroProfile OpenAPI does not read @OfflineCapable"));
        }
      }
    };
  }

  // ---------------------------------------------------------------------------------------------

  private static boolean isChangesPage(JavaType type) {
    return type.toErasure().getName().equals(EntityExposure.CHANGES_PAGE);
  }

  /**
   * The class a {@code GET} answers one or several of: its {@link EntityExposure#answered} type,
   * {@code ChangesPage}'s type argument, a collection's element or an array's component; empty
   * where the bytecode names none — a raw {@code Response} or wrapper, a type variable, {@code
   * Object}.
   */
  private static Optional<JavaClass> recordType(JavaType returned) {
    JavaType type = EntityExposure.answered(returned);
    for (int depth = 0; depth <= 8; depth++) {
      if (type instanceof JavaTypeVariable<?>) {
        return Optional.empty();
      }
      if (type instanceof JavaWildcardType wildcard) {
        if (wildcard.getUpperBounds().size() != 1) {
          return Optional.empty();
        }
        type = wildcard.getUpperBounds().get(0);
        continue;
      }
      if (type instanceof JavaGenericArrayType array) {
        type = array.getComponentType();
        continue;
      }
      JavaClass raw = type.toErasure();
      if (raw.isArray()) {
        return elementOf(raw.getBaseComponentType());
      }
      if (EntityExposure.RESPONSE_WRAPPERS.contains(raw.getName())) {
        if (!(type instanceof JavaParameterizedType parameterized)
            || parameterized.getActualTypeArguments().isEmpty()) {
          return Optional.empty();
        }
        List<JavaType> arguments = parameterized.getActualTypeArguments();
        // A map answers its values, as the cardinality reads it.
        type = arguments.get(arguments.size() - 1);
        continue;
      }
      return elementOf(raw);
    }
    return Optional.empty();
  }

  private static Optional<JavaClass> elementOf(JavaClass raw) {
    if (raw.getName().equals(RESPONSE) || raw.getName().equals(Object.class.getName())) {
      return Optional.empty();
    }
    return Optional.of(raw);
  }

  private static boolean hasParameter(
      JavaMethod method, String annotation, String value, boolean ignoreCase) {
    for (JavaParameter parameter : method.getParameters()) {
      Optional<String> named =
          parameter
              .tryGetAnnotationOfType(annotation)
              .flatMap(found -> found.get("value"))
              .map(String.class::cast);
      if (named.isPresent()
          && (ignoreCase ? named.get().equalsIgnoreCase(value) : named.get().equals(value))) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasOfflineExtension(JavaMethod method) {
    List<JavaAnnotation<?>> extensions = new ArrayList<>();
    method.tryGetAnnotationOfType(EXTENSION).ifPresent(extensions::add);
    method
        .tryGetAnnotationOfType(EXTENSIONS)
        .flatMap(container -> container.get("value"))
        .filter(Object[].class::isInstance)
        .ifPresent(
            value -> {
              for (Object element : (Object[]) value) {
                if (element instanceof JavaAnnotation<?> annotation) {
                  extensions.add(annotation);
                }
              }
            });
    return extensions.stream()
        .map(extension -> extension.get("name").orElse(null))
        .anyMatch(OFFLINE_EXTENSION::equals);
  }
}
