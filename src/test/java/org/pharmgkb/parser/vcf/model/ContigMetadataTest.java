package org.pharmgkb.parser.vcf.model;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * Tests {@link ContigMetadata}.
 * @author Douglas Myers-Turnbull
 */
public class ContigMetadataTest {

  @Test(expected = IllegalArgumentException.class)
  public void testNoId() {
    Map<String, String> map = new HashMap<>();
    map.put(ContigMetadata.URL, "one");
    new ContigMetadata(map);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testExtraProperty() {
    Map<String, String> map = new HashMap<>();
    map.put(ContigMetadata.ID, "id");
    map.put("test", "one");
    new ContigMetadata(map);
  }

  @Test
  public void testGetUrl() {
    Map<String, String> map = new HashMap<>();
    map.put(ContigMetadata.ID, "id");
    map.put(ContigMetadata.URL, "url");
    ContigMetadata contig = new ContigMetadata(map);
    assertEquals("url", contig.getUrl());
  }

}