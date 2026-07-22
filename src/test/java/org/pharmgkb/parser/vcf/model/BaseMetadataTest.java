package org.pharmgkb.parser.vcf.model;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.pharmgkb.parser.vcf.VcfFormatException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


/**
 * Tests {@link BaseMetadata}.
 * @author Douglas Myers-Turnbull
 */
public class BaseMetadataTest {

  @Test
  public void testGetProperty() throws Exception {
    Map<String, String> map = new HashMap<>();
    map.put("test", "one");
    BaseMetadata metadata = new BaseMetadata(map);
    assertEquals("one", metadata.getPropertyRaw("test"));
  }

  @Test
  public void testKeyNewline() throws Exception {
    assertThrows(VcfFormatException.class, () -> {
      Map<String, String> map = new HashMap<>();
      map.put("test", "two\nlines");
      new BaseMetadata(map);
    });
  }

  @Test
  public void testValueNewline() throws Exception {
    assertThrows(VcfFormatException.class, () -> {
      Map<String, String> map = new HashMap<>();
      map.put("second\ntest", "one");
      new BaseMetadata(map);
    });
  }

  @Test
  public void testPutPropertyRawRejectsLineTerminator() {
    BaseMetadata metadata = new BaseMetadata(new HashMap<>());
    assertThrows(VcfFormatException.class, () -> metadata.putPropertyRaw("key", "bad\nvalue"));
    assertThrows(VcfFormatException.class, () -> metadata.putPropertyRaw("key", "bad\rvalue"));
    assertThrows(VcfFormatException.class, () -> metadata.putPropertyRaw("bad\nkey", "value"));
  }

  @Test
  public void testPutAndQuotePropertyRejectsLineTerminator() {
    BaseMetadata metadata = new BaseMetadata(new HashMap<>());
    assertThrows(VcfFormatException.class, () -> metadata.putAndQuoteProperty("key", "bad\nvalue"));
    assertThrows(VcfFormatException.class, () -> metadata.putAndQuoteProperty("key", "bad\rvalue"));
    // removing a property (value == null) is not subject to the check
    metadata.putAndQuoteProperty("key", "fine");
    metadata.putAndQuoteProperty("key", null);
  }
}
