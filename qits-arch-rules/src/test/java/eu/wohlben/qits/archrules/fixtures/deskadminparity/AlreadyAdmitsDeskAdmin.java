package eu.wohlben.qits.archrules.fixtures.deskadminparity;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;

/**
 * A route that, despite being named on a refusal list in the test, now admits
 * {@code qits:desk-admin} directly: the refusal entry naming it is stale, and
 * {@code refusedRoutesExistAndRefuse} must fail on it.
 */
@Path("/already-open")
public class AlreadyAdmitsDeskAdmin {

  @PUT
  @RolesAllowed({"qits:admin-agent", "qits:desk-admin"})
  public void wideOpen() {}
}
