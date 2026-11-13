package eu.wohlben.qits.offline;

import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.ext.Provider;
import java.lang.reflect.Method;

/**
 * Gives a single offline read its version on the wire: a {@code GET} of an {@link OfflineCapable}
 * operation answering 200 with an {@link OfflineRecord} gets {@code ETag: "<version>"} (strong),
 * which is what the client sends back as {@code If-Match}. An {@code ETag} the resource set itself
 * is kept.
 */
@Provider
public class ETagFilter implements ContainerResponseFilter {

  @Context ResourceInfo resourceInfo;

  @Override
  public void filter(ContainerRequestContext request, ContainerResponseContext response) {
    if (!HttpMethod.GET.equals(request.getMethod())
        || response.getStatus() != 200
        || !(response.getEntity() instanceof OfflineRecord record)
        || response.getHeaders().containsKey(HttpHeaders.ETAG)
        || !offline(resourceInfo)) {
      return;
    }
    response.getHeaders().putSingle(HttpHeaders.ETAG, RecordVersions.etag(record.version()));
  }

  static boolean offline(ResourceInfo info) {
    if (info == null) {
      return false;
    }
    Method method = info.getResourceMethod();
    Class<?> type = info.getResourceClass();
    return (method != null && method.isAnnotationPresent(OfflineCapable.class))
        || (type != null && type.isAnnotationPresent(OfflineCapable.class));
  }
}
