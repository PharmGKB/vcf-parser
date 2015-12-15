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
    BaseMetadata metadata = new BaseMetadata(map);
    assertEquals("one", metadata.getPropertyRaw("test"));
  }

  @Test(expected = IllegalArgumentException.class)
  public void testKeyNewline() throws Exception {
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("test", "two\nlines");
    new BaseMetadata(map);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testValueNewline() throws Exception {
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("second\ntest", "one");
    new BaseMetadata(map);
  }
}