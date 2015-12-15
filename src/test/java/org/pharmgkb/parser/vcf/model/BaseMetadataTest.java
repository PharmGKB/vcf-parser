package org.pharmgkb.parser.vcf.model;

import org.junit.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * Tests {@link BaseMetadata}.
 * @author Douglas Myers-Turnbull
 */
public class BaseMetadataTest {

  @Test
  public void testGetProperty() throws Exception {
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("test", "one");
    X metadata = new X(map);
    assertEquals("one", metadata.getPropertyRaw("test"));
  }

  @Test(expected = IllegalArgumentException.class)
  public void testKeyNewline() throws Exception {
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("test", "two\nlines");
    new X(map);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testValueNewline() throws Exception {
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("second\ntest", "one");
    new X(map);
  }

  private static class X extends BaseMetadata {

    public X(LinkedHashMap<String, String> map) {
      super();
      map.entrySet().stream().forEach(e -> putPropertyRaw(e.getKey(), e.getValue()));
      super.init();
    }
  }
}