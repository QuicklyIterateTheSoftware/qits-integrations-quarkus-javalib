package eu.wohlben.qits.offline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The cursor's wire form, and that anything else is refused as {@link CursorInvalidException}. */
class ChangeCursorTest {

  @Test
  void aCursorRoundTrips() {
    ChangeCursor last = new ChangeCursor(1234, null, null, 1_700_000_000);
    ChangeCursor mid = new ChangeCursor(10, new ChangeCursor.After(12, "a/b"), 11L, 1_700_000_000);

    assertEquals(last, ChangeCursor.decode(last.encode()));
    assertEquals(mid, ChangeCursor.decode(mid.encode()));
  }

  @Test
  void theWireFormIsBase64UrlJson() {
    String json =
        new String(
            Base64.getUrlDecoder()
                .decode(new ChangeCursor(5, new ChangeCursor.After(6, "x"), 4L, 9).encode()),
            StandardCharsets.UTF_8);
    assertEquals("{\"x\":5,\"a\":[6,\"x\"],\"m\":4,\"t\":9}", json);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "garbage!",
        "e30", // {}
        "eyJ4IjoiYSIsInQiOjF9", // {"x":"a","t":1}
        "eyJ4IjoxLCJ0IjoxLCJhIjpbMV19", // {"x":1,"t":1,"a":[1]}
        "eyJ4IjotMSwidCI6MX0", // {"x":-1,"t":1}
        "WzFd" // [1]
      })
  void anythingElseIsInvalid(String since) {
    assertThrows(CursorInvalidException.class, () -> ChangeCursor.decode(since));
  }
}
