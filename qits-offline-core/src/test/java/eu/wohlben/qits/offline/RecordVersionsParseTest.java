package eu.wohlben.qits.offline;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** {@link RecordVersions#require}'s reading of {@code If-Match}, without a server. */
class RecordVersionsParseTest {

  @ParameterizedTest
  @ValueSource(strings = {"\"7\"", "7", " \"7\" "})
  void aStrongVersionWithOrWithoutQuotesMatches(String ifMatch) {
    assertDoesNotThrow(() -> RecordVersions.require(ifMatch, 7, () -> "unused"));
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "  ", "*", "W/\"7\"", "\"7", "abc", "\"7\", \"8\"", "-7"})
  void anythingElseIsAVersionRequired(String ifMatch) {
    assertThrows(
        VersionRequiredException.class, () -> RecordVersions.require(ifMatch, 7, () -> "unused"));
  }

  @Test
  void anotherVersionIsStaleAndCarriesTheCurrentRecord() {
    Object current = new Object();
    StaleVersionException stale =
        assertThrows(
            StaleVersionException.class, () -> RecordVersions.require("\"6\"", 7, () -> current));
    assertSame(current, stale.current());
    assertEquals(7L, stale.currentVersion());
    assertEquals(412, stale.status());
  }

  @Test
  void theETagIsQuoted() {
    assertEquals("\"12\"", RecordVersions.etag(12));
  }
}
