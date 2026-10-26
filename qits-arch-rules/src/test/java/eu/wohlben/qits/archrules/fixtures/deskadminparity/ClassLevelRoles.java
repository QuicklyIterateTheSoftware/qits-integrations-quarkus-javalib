package eu.wohlben.qits.archrules.fixtures.deskadminparity;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

/**
 * A class-level {@code @RolesAllowed}, read as a route's effective roles when the route carries
 * none of its own — and fully replaced, not extended, when it does. {@code fallsBackToTheClass}
 * has no method-level annotation and passes on the class's grant; {@code overridesTheClass} has
 * its own, narrower one and fails despite the class admitting {@code qits:desk-admin}.
 */
@Path("/class-level")
@RolesAllowed({"qits:admin-agent", "qits:desk-admin"})
public class ClassLevelRoles {

  @GET
  public void fallsBackToTheClass() {}

  @POST
  @RolesAllowed({"qits:admin-agent"})
  public void overridesTheClass() {}
}
