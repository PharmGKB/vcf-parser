package org.pharmgkb.parser.vcf.model;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.pharmgkb.parser.vcf.VcfFormatException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


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

  @Test
  public void testCheckAttributeOrderWarnsWhenOutOfCanonicalOrder() {
    // VCFv4.2.tex line 69: ##INFO=<ID=ID,Number=number,Type=type,Description="description",Source="source",
    // Version="version"> -- every canonical template and worked example in the spec uses this relative order
    Map<String, String> map = new LinkedHashMap<>();
    map.put("Number", "1");
    map.put("Type", "Integer");
    map.put("Description", "d");
    map.put("ID", "DP");
    BaseMetadata metadata = new BaseMetadata(map);
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      metadata.checkAttributeOrder(org.slf4j.LoggerFactory.getLogger(BaseMetadataTest.class));
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testCheckAttributeOrderDoesNotWarnWhenCanonical() {
    Map<String, String> map = new LinkedHashMap<>();
    map.put("ID", "DP");
    map.put("Number", "1");
    map.put("Type", "Integer");
    map.put("Description", "d");
    BaseMetadata metadata = new BaseMetadata(map);
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      metadata.checkAttributeOrder(org.slf4j.LoggerFactory.getLogger(BaseMetadataTest.class));
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testCheckAttributeOrderIgnoresUnrecognizedKeysAndTheirPosition() {
    // only ID/Number/Type/Description/Source/Version are order-checked -- an unrecognized extra key, in any
    // position, must not affect the result (VCFv4.2 explicitly allows extra fields)
    Map<String, String> map = new LinkedHashMap<>();
    map.put("Custom", "z");
    map.put("ID", "DP");
    map.put("Description", "d");
    BaseMetadata metadata = new BaseMetadata(map);
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      metadata.checkAttributeOrder(org.slf4j.LoggerFactory.getLogger(BaseMetadataTest.class));
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }
}
