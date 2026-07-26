package org.pharmgkb.parser.vcf.model;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.pharmgkb.parser.vcf.VcfFormatException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link ContigMetadata}.
 * @author Douglas Myers-Turnbull
 */
public class ContigMetadataTest {

  @Test
  public void testNoId() {
    // IdMetadata.init()'s warnIfMissing() should warn when ID is absent
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      Map<String, String> map = new HashMap<>();
      map.put(ContigMetadata.URL, "one");
      new ContigMetadata(map);
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
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

  @Test
  public void testBadLength() {
    // ContigMetadata.init()'s catch (NumberFormatException) block should warn on a non-numeric length
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      Map<String, String> map = new HashMap<>();
      map.put(ContigMetadata.ID, "id");
      map.put(ContigMetadata.ASSEMBLY, "assembly");
      map.put(ContigMetadata.LENGTH, "234asdgasdgasdg");
      map.put(ContigMetadata.URL, "url");
      new ContigMetadata(map);
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
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
  public void testNullUrlNotSerialized() {
    // a null URL argument must not be stored (it would otherwise serialize as the literal "URL=null")
    ContigMetadata contig = new ContigMetadata("chr1", 10, "asm", null, null, null, null);
    assertNull(contig.getUrl());
    assertFalse(contig.getPropertyKeys().contains(ContigMetadata.URL));
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

  @Test
  public void testGetLengthMissingThrowsVcfFormatException() {
    // length is not required by the spec (init() only warns for it), but getLength() previously let a raw,
    // confusing NumberFormatException("Cannot parse null string") leak out instead of VcfFormatException, unlike
    // every other typed getter in the codebase (getQuality, convertProperty, parseAlleleIndex, etc.)
    Map<String, String> map = new HashMap<>();
    map.put(ContigMetadata.ID, "id");
    map.put(ContigMetadata.ASSEMBLY, "assembly");
    ContigMetadata contig = new ContigMetadata(map);
    assertThrows(VcfFormatException.class, contig::getLength);
  }

  @Test
  public void testGetLengthNotANumberThrowsVcfFormatException() {
    Map<String, String> map = new HashMap<>();
    map.put(ContigMetadata.ID, "id");
    map.put(ContigMetadata.ASSEMBLY, "assembly");
    map.put(ContigMetadata.LENGTH, "not-a-number");
    ContigMetadata contig = new ContigMetadata(map);
    assertThrows(VcfFormatException.class, contig::getLength);
  }

  @Test
  public void testMissingAssemblyAndLengthDoesNotWarn() {
    // VCFv4.2.tex line 121: "highly recommended (but not required) that the header include tags describing
    // the reference and contigs... all tags are optional." Line 138 repeats "highly recommended (but not
    // required)" specifically for ##contig. Neither assembly nor length is spec-required, so their absence
    // must not warn.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      Map<String, String> map = new HashMap<>();
      map.put(ContigMetadata.ID, "id");
      new ContigMetadata(map);
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

}
