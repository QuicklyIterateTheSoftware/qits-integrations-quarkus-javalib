package eu.wohlben.qits.offline;

import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a JAX-RS operation as one an offline client may queue and replay: on a resource class it
 * covers every operation of the class, on a method that one operation.
 *
 * <p>It opts the operation into all of this module's conventions at once — {@link
 * IdempotencyInterceptor} for writes carrying {@code Idempotency-Key}, {@link ETagFilter} for
 * single reads of an {@link OfflineRecord}, and, as the resource's own code, {@link
 * RecordVersions#require} for updates and {@link Changes} for collection reads. A resource without
 * it behaves exactly as it did before the jar arrived.
 *
 * <p>MicroProfile OpenAPI does not read meta-annotations, so the operation also carries {@code
 * @Extension(name = "x-qits-offline", value = "true")} itself; qits-arch-rules holds a service to
 * that.
 */
@InterceptorBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface OfflineCapable {}
