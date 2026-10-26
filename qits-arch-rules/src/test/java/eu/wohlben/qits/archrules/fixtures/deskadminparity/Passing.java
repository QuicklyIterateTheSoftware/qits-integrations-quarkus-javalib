package eu.wohlben.qits.archrules.fixtures.deskadminparity;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/** A route that already admits both roles: {@code everyAdminAgentRouteAdmitsDeskAdmin} passes it. */
@Path("/passing")
public class Passing {

  @GET
  @RolesAllowed({"qits:admin-agent", "qits:desk-admin"})
  public void bothRoles() {}
}
