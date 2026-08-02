package org.pharmgkb.parser.vcf.model;

import java.io.StringWriter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.pharmgkb.parser.vcf.VcfFormatException;
import org.pharmgkb.parser.vcf.VcfWarnings;

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
  public void testConstructorRejectsNullPropertiesMapInsteadOfThrowingNpe() {
    // properties is typed non-@Nullable, but nothing enforces that at compile time; must reject a null with
    // VcfFormatException, not let it reach a raw NullPointerException at new LinkedHashMap<>(properties)
    assertThrows(VcfFormatException.class, () -> new BaseMetadata("TEST", null));
  }

  @Test
  public void testGetProperty() throws Exception {
    Map<String, String> map = new HashMap<>();
    map.put("test", "one");
    BaseMetadata metadata = new BaseMetadata("TEST", map);
    assertEquals("one", metadata.getPropertyRaw("test"));
  }

  @Test
  public void testConstructorDoesNotAliasCallersMap() {
    // the constructor used to store the caller's map by reference, so mutating it after construction
    // silently corrupted this instance's state; it now defensively copies into its own LinkedHashMap
    Map<String, String> map = new LinkedHashMap<>();
    map.put("test", "one");
    BaseMetadata metadata = new BaseMetadata("TEST", map);
    map.put("test", "mutated-after-construction");
    map.put("extra", "should not appear");
    assertEquals("one", metadata.getPropertyRaw("test"));
    assertFalse(metadata.getPropertyKeys().contains("extra"));
  }

  @Test
  public void testGetPropertiesUnquoted() {
    Map<String, String> map = new LinkedHashMap<>();
    map.put("a", "\"quoted\"");
    map.put("b", "unquoted");
    BaseMetadata metadata = new BaseMetadata("TEST", map);
    Map<String, String> unquoted = metadata.getPropertiesUnquoted();
    assertEquals(2, unquoted.size());
    assertEquals("quoted", unquoted.get("a"));
    assertEquals("unquoted", unquoted.get("b"));
  }

  @Test
  public void testKeyNewline() throws Exception {
    assertThrows(VcfFormatException.class, () -> {
      Map<String, String> map = new HashMap<>();
      map.put("test", "two\nlines");
      new BaseMetadata("TEST", map);
    });
  }

  @Test
  public void testValueNewline() throws Exception {
    assertThrows(VcfFormatException.class, () -> {
      Map<String, String> map = new HashMap<>();
      map.put("second\ntest", "one");
      new BaseMetadata("TEST", map);
    });
  }

  @Test
  public void testPutPropertyRawRejectsLineTerminator() {
    BaseMetadata metadata = new BaseMetadata("TEST", new HashMap<>());
    assertThrows(VcfFormatException.class, () -> metadata.putPropertyRaw("key", "bad\nvalue"));
    assertThrows(VcfFormatException.class, () -> metadata.putPropertyRaw("key", "bad\rvalue"));
    assertThrows(VcfFormatException.class, () -> metadata.putPropertyRaw("bad\nkey", "value"));
  }

  @Test
  public void testPutPropertyRawRejectsNull() {
    BaseMetadata metadata = new BaseMetadata("TEST", new HashMap<>());
    assertThrows(VcfFormatException.class, () -> metadata.putPropertyRaw("key", null));
  }

  @Test
  public void testPutPropertyRawRejectsNullKeyInsteadOfThrowingNpe() {
    // key is typed non-@Nullable, but nothing enforces that at compile time; must reject a null with
    // VcfFormatException, not let it reach a raw NullPointerException at InternalUtils.checkNoLineTerminator(key, value)
    BaseMetadata metadata = new BaseMetadata("TEST", new HashMap<>());
    assertThrows(VcfFormatException.class, () -> metadata.putPropertyRaw(null, "value"));
  }

  @Test
  public void testPutAndQuotePropertyRejectsLineTerminator() {
    BaseMetadata metadata = new BaseMetadata("TEST", new HashMap<>());
    assertThrows(VcfFormatException.class, () -> metadata.putAndQuoteProperty("key", "bad\nvalue"));
    assertThrows(VcfFormatException.class, () -> metadata.putAndQuoteProperty("key", "bad\rvalue"));
  }

  @Test
  public void testPutAndQuotePropertyRejectsNull() {
    BaseMetadata metadata = new BaseMetadata("TEST", new HashMap<>());
    assertThrows(VcfFormatException.class, () -> metadata.putAndQuoteProperty("key", null));
  }

  @Test
  public void testPutAndQuotePropertyRejectsNullKeyInsteadOfThrowingNpe() {
    BaseMetadata metadata = new BaseMetadata("TEST", new HashMap<>());
    assertThrows(VcfFormatException.class, () -> metadata.putAndQuoteProperty(null, "value"));
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
    BaseMetadata metadata = new BaseMetadata("TEST", map);
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      metadata.checkAttributeOrder();
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
    BaseMetadata metadata = new BaseMetadata("TEST", map);
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      metadata.checkAttributeOrder();
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
    BaseMetadata metadata = new BaseMetadata("TEST", map);
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      metadata.checkAttributeOrder();
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testValidateThrowsForNullPropertyValueInsertedViaGetPropertiesRaw() {
    // regression test: putPropertyRaw/putAndQuoteProperty already reject a null value at insertion, but
    // validateProperties() (called by validate(), and unconditionally at construction) only checked for a null
    // key, tolerating a null value inserted directly through getPropertiesRaw()'s live view
    Map<String, String> map = new LinkedHashMap<>();
    map.put("ID", "DP");
    BaseMetadata metadata = new BaseMetadata("TEST", map);
    metadata.getPropertiesRaw().put("Extra", null);
    VcfFormatException e = assertThrows(VcfFormatException.class, metadata::validate);
    assertTrue(e.getMessage().contains("null value"));
  }

  @Test
  public void testCheckAttributeOrderThrowsForNullPropertyName() {
    // regression test: checkAttributeOrder() used to let CANONICAL_ATTRIBUTE_ORDER.contains(key) throw an
    // uncaught NullPointerException for a null key (reachable via getPropertiesRaw().put(null, ...)) instead of
    // the clean VcfFormatException validateProperties() already throws for the identical invalid state.
    Map<String, String> map = new LinkedHashMap<>();
    map.put("ID", "DP");
    BaseMetadata metadata = new BaseMetadata("TEST", map);
    metadata.getPropertiesRaw().put(null, "x");
    VcfFormatException e = assertThrows(VcfFormatException.class, metadata::checkAttributeOrder);
    assertTrue(e.getMessage().contains("null property name"));
  }

  @Test
  public void testEnsureNoExtrasWarnsToConfiguredSink() {
    BaseMetadata md = new BaseMetadata("TESTLABEL", Map.of("Extra", "value"));
    StringWriter sink = new StringWriter();
    md.setWarningsSink(new VcfWarnings(sink));
    md.ensureNoExtras("ID");
    assertTrue(sink.toString().contains("TESTLABEL metadata line contains unexpected property Extra"));
  }

  @Test
  public void testSetWarningsSinkRejectsNull() {
    BaseMetadata md = new BaseMetadata("TESTLABEL", Map.of());
    assertThrows(NullPointerException.class, () -> md.setWarningsSink(null));
  }

  @Test
  public void testDefaultWarningsSinkIsSharedLogger() {
    // no setWarningsSink call at all -- must warn to the shared logger, not throw or silently no-op
    BaseMetadata md = new BaseMetadata("TESTLABEL", Map.of("Extra", "value"));
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender = new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      md.ensureNoExtras("ID");
      assertTrue(appender.anyMessageContains("unexpected property Extra"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }
}
