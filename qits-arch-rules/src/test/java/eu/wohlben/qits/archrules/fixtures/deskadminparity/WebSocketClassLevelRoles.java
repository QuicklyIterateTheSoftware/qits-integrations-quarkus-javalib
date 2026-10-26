package eu.wohlben.qits.archrules.fixtures.deskadminparity;

import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import jakarta.annotation.security.RolesAllowed;

/**
 * A WebSockets Next endpoint, which carries its roles at the class level rather than under a
 * {@code jakarta.ws.rs.Path}. {@code onOpen} has no annotation of its own and passes on the
 * class's grant; {@code onMessage} narrows it away on the callback itself and fails.
 */
@WebSocket(path = "/class-level-socket")
@RolesAllowed({"qits:admin-agent", "qits:desk-admin"})
public class WebSocketClassLevelRoles {

  @OnOpen
  public void onOpen() {}

  @OnTextMessage
  @RolesAllowed({"qits:admin-agent"})
  public void onMessage(String text) {}
}
