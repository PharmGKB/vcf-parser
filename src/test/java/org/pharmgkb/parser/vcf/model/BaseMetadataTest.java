package org.pharmgkb.parser.vcf.model;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

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

  @Test(expected = IllegalArgumentException.class)
  public void testKeyNewline() throws Exception {
    Map<String, String> map = new HashMap<>();
    map.put("test", "two\nlines");
    new BaseMetadata(map);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testValueNewline() throws Exception {
    Map<String, String> map = new HashMap<>();
    map.put("second\ntest", "one");
    new BaseMetadata(map);
  }
}