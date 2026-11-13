package eu.wohlben.qits.offline.fixture;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** A resource that did not opt in: it must behave exactly as before the jar arrived. */
@Path("/plain")
@Produces(MediaType.APPLICATION_JSON)
public class PlainResource {

  @GET
  public Thing get() {
    return new Thing("plain", "not offline", 7);
  }
}
