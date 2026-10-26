package eu.wohlben.qits.archrules.fixtures.deskadminparity;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

/**
 * A route that admits {@code qits:admin-agent} alone and is not on anybody's refusal list:
 * {@code everyAdminAgentRouteAdmitsDeskAdmin} must fail it.
 */
@Path("/failing")
public class Failing {

  @POST
  @RolesAllowed({"qits:admin-agent"})
  public void leaksAdminOnly() {}
}
