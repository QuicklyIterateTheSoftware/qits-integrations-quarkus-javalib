package eu.wohlben.qits.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A loopback TCP server that does, per connection, the next thing it was told to: answer with a
 * status and headers, reset the connection once the request arrived, or hang up during whatever
 * the client said first (which, for an {@code https://} client, is the TLS handshake). Raw sockets
 * because a reset and a dropped handshake are exactly what an HTTP server library will not do on
 * request. Every connection is {@code Connection: close}, so one connection is one attempt.
 */
final class ScriptedServer implements AutoCloseable {

  interface Action {
    void perform(Socket socket, String requestHead) throws IOException;
  }

  private final ServerSocket socket;
  private final ConcurrentLinkedQueue<Action> script = new ConcurrentLinkedQueue<>();
  private final List<String> methods = Collections.synchronizedList(new ArrayList<>());
  private final AtomicInteger connections = new AtomicInteger();
  private volatile Action otherwise = respond(200);
  private final Thread acceptor;

  ScriptedServer() throws IOException {
    this(0);
  }

  private ScriptedServer(int port) throws IOException {
    ServerSocket bound = new ServerSocket();
    bound.setReuseAddress(true);
    bound.bind(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), port), 50);
    socket = bound;
    acceptor = Thread.ofVirtual().start(this::accept);
  }

  /** A server on a port of the test's choosing — the one that refused a moment ago. */
  static ScriptedServer on(int port) throws IOException {
    return new ScriptedServer(port);
  }

  /** The next connections, in order; after them, {@link #otherwise}. */
  ScriptedServer then(Action... actions) {
    script.addAll(List.of(actions));
    return this;
  }

  ScriptedServer otherwise(Action action) {
    otherwise = action;
    return this;
  }

  int port() {
    return socket.getLocalPort();
  }

  String url(String path) {
    return "http://127.0.0.1:" + port() + path;
  }

  int connections() {
    return connections.get();
  }

  /** The request methods that arrived, one per connection that got as far as a request line. */
  List<String> methods() {
    return List.copyOf(methods);
  }

  static Action respond(int status, String... headerPairs) {
    return (s, head) -> {
      StringBuilder out = new StringBuilder("HTTP/1.1 ").append(status).append(" X\r\n");
      for (int i = 0; i + 1 < headerPairs.length; i += 2) {
        out.append(headerPairs[i]).append(": ").append(headerPairs[i + 1]).append("\r\n");
      }
      byte[] body = ("status " + status).getBytes(StandardCharsets.UTF_8);
      boolean bodiless = isHead(head);
      out.append("Content-Type: text/plain\r\nContent-Length: ")
          .append(body.length)
          .append("\r\nConnection: close\r\n\r\n");
      OutputStream o = s.getOutputStream();
      o.write(out.toString().getBytes(StandardCharsets.US_ASCII));
      if (!bodiless) {
        o.write(body);
      }
      o.flush();
      s.close();
    };
  }

  /** The request arrived; the answer is a TCP reset and not one response byte. */
  static Action reset() {
    return (s, head) -> {
      s.setSoLinger(true, 0);
      s.close();
    };
  }

  /** Whatever the client said first was read, then the connection was closed without a word. */
  static Action hangUp() {
    return (s, head) -> s.close();
  }

  private static boolean isHead(String requestHead) {
    return requestHead.toUpperCase(Locale.ROOT).startsWith("HEAD ");
  }

  private void accept() {
    while (!socket.isClosed()) {
      Socket s;
      try {
        s = socket.accept();
      } catch (IOException closed) {
        return;
      }
      connections.incrementAndGet();
      Action action = script.poll();
      Action chosen = action == null ? otherwise : action;
      Thread.ofVirtual()
          .start(
              () -> {
                try (s) {
                  String head = readHead(s.getInputStream());
                  if (head.matches("(?s)^[A-Z]+ .*")) {
                    methods.add(head.substring(0, head.indexOf(' ')));
                  }
                  chosen.perform(s, head);
                } catch (IOException ignored) {
                  // the client went away; the test asserts on what it saw
                }
              });
    }
  }

  /**
   * The request line and headers, plus any body the Content-Length announces; for a TLS client,
   * whatever its first flight was.
   */
  private static String readHead(InputStream in) throws IOException {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    int b;
    int matched = 0;
    byte[] end = {'\r', '\n', '\r', '\n'};
    while ((b = in.read()) != -1) {
      buf.write(b);
      matched = b == end[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
      if (matched == 4) {
        break;
      }
      if (buf.size() == 5 && buf.toByteArray()[0] == 0x16) {
        // A TLS record, not HTTP: its first five bytes are enough to know the handshake began.
        return "TLS";
      }
    }
    String head = buf.toString(StandardCharsets.ISO_8859_1);
    for (String line : head.split("\r\n")) {
      if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
        int length = Integer.parseInt(line.substring(15).trim());
        in.readNBytes(length);
      }
      if (line.toLowerCase(Locale.ROOT).replace(" ", "").equals("transfer-encoding:chunked")) {
        // Drained to the last chunk, so the answer never races a body still being written.
        int matchedEnd = 0;
        byte[] last = {'0', '\r', '\n', '\r', '\n'};
        while (matchedEnd < last.length && (b = in.read()) != -1) {
          matchedEnd = b == last[matchedEnd] ? matchedEnd + 1 : (b == '0' ? 1 : 0);
        }
      }
    }
    return head;
  }

  @Override
  public void close() throws IOException {
    socket.close();
  }
}
