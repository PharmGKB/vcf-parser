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

  /**
   * Just makes sure it doesn't throw an exception. However, should probably warn.
   */
  @Test
  public void testExtraProperty() {
    Map<String, String> map = new HashMap<>();
    map.put(ContigMetadata.ID, "id");
    map.put(ContigMetadata.ASSEMBLY, "assembly");
    map.put(ContigMetadata.LENGTH, "23");
    map.put("test", "one");
    new ContigMetadata(map);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testBadLength() {
    Map<String, String> map = new HashMap<>();
    map.put(ContigMetadata.ID, "id");
    map.put(ContigMetadata.ASSEMBLY, "assembly");
    map.put(ContigMetadata.LENGTH, "234asdgasdgasdg");
    map.put(ContigMetadata.URL, "url");
    new ContigMetadata(map);
  }

  @Test
  public void testGetLength() {
    Map<String, String> map = new HashMap<>();
    map.put(ContigMetadata.ID, "id");
    map.put(ContigMetadata.ASSEMBLY, "assembly");
    map.put(ContigMetadata.LENGTH, "23");
    map.put(ContigMetadata.URL, "url");
    ContigMetadata contig = new ContigMetadata(map);
    assertEquals(23, contig.getLength());
  }

  @Test
  public void testGetUrl() {
    Map<String, String> map = new HashMap<>();
    map.put(ContigMetadata.ID, "id");
    map.put(ContigMetadata.ASSEMBLY, "assembly");
    map.put(ContigMetadata.LENGTH, "23");
    map.put(ContigMetadata.URL, "url");
    ContigMetadata contig = new ContigMetadata(map);
    assertEquals("url", contig.getUrl());
  }

}