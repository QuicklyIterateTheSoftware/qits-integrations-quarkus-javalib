package eu.wohlben.qits.archrules.fixtures.deskadminparity;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.Path;

/**
 * A route that admits {@code qits:admin-agent} alone, correctly named on a refusal list:
 * {@code everyAdminAgentRouteAdmitsDeskAdmin} skips it, and {@code refusedRoutesExistAndRefuse}
 * takes the entry as valid.
 */
@Path("/refused")
public class RefusedRoute {

  @DELETE
  @RolesAllowed({"qits:admin-agent"})
  public void ownRightsOnly() {}
}
