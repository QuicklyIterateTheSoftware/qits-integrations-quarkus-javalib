package eu.wohlben.qits.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The header supplier against a token that changes on command — no idp, no Quarkus boot. */
class BearerHeadersTest {

  private final AtomicReference<BearerSource.Lookup> current = new AtomicReference<>();
  private final BearerHeaders headers = new BearerHeaders(current::get);

  private final List<LogRecord> logged = new CopyOnWriteArrayList<>();
  private final Logger logger = Logger.getLogger(BearerHeaders.class.getName());
  private final Handler capture =
      new Handler() {
        @Override
        public void publish(LogRecord record) {
          logged.add(record);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
      };

  @BeforeEach
  void listen() {
    logger.addHandler(capture);
  }

  @AfterEach
  void stopListening() {
    logger.removeHandler(capture);
  }

  @Test
  void attachesTheCurrentToken() {
    current.set(BearerSource.Lookup.of("one"));
    assertEquals(Map.of("Authorization", "Bearer one"), headers.get());
  }

  @Test
  void picksUpARefreshedTokenOnTheNextExport() {
    current.set(BearerSource.Lookup.of("one"));
    assertEquals("Bearer one", headers.get().get("Authorization"));
    current.set(BearerSource.Lookup.of("two"));
    assertEquals("Bearer two", headers.get().get("Authorization"));
  }

  @Test
  void noTokenMeansNoHeaderAndOneWarnAcrossManyExports() {
    current.set(BearerSource.Lookup.none("idp unreachable"));
    for (int i = 0; i < 25; i++) {
      assertEquals(Map.of(), headers.get());
    }
    List<LogRecord> warns = warns();
    assertEquals(1, warns.size(), "one WARN for the outage, not one per batch: " + warns);
    assertTrue(formatted(warns.get(0)).contains("idp unreachable"), formatted(warns.get(0)));
  }

  @Test
  void aThrowingSourceIsNoTokenNotAFailedExport() {
    BearerHeaders throwing =
        new BearerHeaders(
            () -> {
              throw new IllegalStateException("boom");
            });
    assertEquals(Map.of(), throwing.get());
    assertEquals(Map.of(), throwing.get());
    assertEquals(1, warns().size());
  }

  @Test
  void aSecondOutageWarnsAgainAfterTheTokenCameBack() {
    current.set(BearerSource.Lookup.none("first"));
    headers.get();
    headers.get();
    current.set(BearerSource.Lookup.of("back"));
    assertEquals("Bearer back", headers.get().get("Authorization"));
    current.set(BearerSource.Lookup.none("second"));
    headers.get();
    headers.get();
    List<LogRecord> warns = warns();
    assertEquals(2, warns.size(), warns.toString());
    assertTrue(formatted(warns.get(1)).contains("second"));
  }

  @Test
  void aBlankTokenCountsAsNone() {
    current.set(BearerSource.Lookup.of(" "));
    assertEquals(Map.of(), headers.get());
    assertEquals(1, warns().size());
  }

  private List<LogRecord> warns() {
    return logged.stream().filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue()).toList();
  }

  private static String formatted(LogRecord record) {
    Object[] params = record.getParameters();
    return params == null
        ? record.getMessage()
        : String.format(record.getMessage(), params);
  }
}
