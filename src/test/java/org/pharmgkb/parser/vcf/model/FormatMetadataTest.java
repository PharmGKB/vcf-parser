package org.pharmgkb.parser.vcf.model;

import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import org.pharmgkb.parser.vcf.InternalUtils;
import org.pharmgkb.parser.vcf.VcfFormatException;
import org.pharmgkb.parser.vcf.VcfWarnings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


public class FormatMetadataTest {


  @Test
  public void testConstructor() {

    String[] props = new String[4];
    props[0] = "ID=AA";
    props[1] = "Number=1";
    props[2] = "Type=String";
    String desc = "Ancestral Allele, test";
    props[3] = "Description=\"" + desc + "\"";
    FormatMetadata md = new FormatMetadata(InternalUtils.extractProperties(props, VcfWarnings.DEFAULT),
        VcfWarnings.DEFAULT);
    assertEquals(FormatType.String, md.getType());
    assertEquals(desc, md.getDescription());

    String[] nums = new String[] {
        "0", "23", "."
    };
    for (String n : nums) {
      props[1] = "Number=" + n;
      md = new FormatMetadata(InternalUtils.extractProperties(props, VcfWarnings.DEFAULT), VcfWarnings.DEFAULT);
      assertEquals(n, md.getNumber());
    }

    // an invalid Number value only warns under this project's permissive policy (checkNumberProperty never
    // throws), matching the sibling InfoMetadataTest.testConstructor's analogous case -- constructing with
    // each of these must not throw
    nums = new String[] {
        "A", "a", "G", "g", "R", "r", "AA", "x", ".g", "23a"
    };
    for (String n : nums) {
      props[1] = "Number=" + n;
      new FormatMetadata(InternalUtils.extractProperties(props, VcfWarnings.DEFAULT), VcfWarnings.DEFAULT);
    }
  }

  @Test
  public void testTypedConstructorRejectsNullTypeInsteadOfThrowingNpe() {
    // type is typed non-@Nullable, but nothing enforces that at compile time; must reject a null with
    // VcfFormatException, not let it reach a raw NullPointerException at type.name()
    assertThrows(VcfFormatException.class, () -> new FormatMetadata("GT", "d", "1", null));
  }

  @Test
  public void testExtraPropertyRetained() {
    // VCFv4.2: "For all of the ##INFO, ##FORMAT, ##FILTER, and ##ALT metainformation, extra fields can be included
    // after the default fields" -- an unrecognized property is compliant, not just tolerated, and must be preserved
    FormatMetadata md = new FormatMetadata(InternalUtils.extractProperties(
        new String[]{"ID=X", "Number=1", "Type=String", "Description=\"d\"", "Custom=extra"}, VcfWarnings.DEFAULT),
        VcfWarnings.DEFAULT);
    assertEquals("extra", md.getPropertyRaw("Custom"));
  }

  @Test
  public void testReservedNumberMismatchWarnsAndPreservesValue() {
    // GT's reserved Number is "1"; declaring it "G" is a mismatch
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    FormatMetadata md;
    try {
      md = new FormatMetadata(InternalUtils.extractProperties(
          new String[]{"ID=GT", "Number=G", "Type=String", "Description=\"d\""}, VcfWarnings.DEFAULT),
          VcfWarnings.DEFAULT);
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertEquals("G", md.getNumber());
  }

  @Test
  public void testReservedTypeMismatchWarns() {
    // GT's reserved Type is String; declaring it Integer is a mismatch
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      new FormatMetadata(InternalUtils.extractProperties(
          new String[]{"ID=GT", "Number=1", "Type=Integer", "Description=\"d\""}, VcfWarnings.DEFAULT),
          VcfWarnings.DEFAULT);
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testCorrectlyDeclaredReservedKeyDoesNotWarn() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      new FormatMetadata(InternalUtils.extractProperties(
          new String[]{"ID=GT", "Number=1", "Type=String", "Description=\"d\""}, VcfWarnings.DEFAULT),
          VcfWarnings.DEFAULT);
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testCorrectlyDeclaredGleDoesNotWarn() {
    // GLE is the trickiest entry: its expectedNumber ("G") is sourced from hts-specs fixtures, not a formal spec
    // table (see ReservedFormatProperty.GenotypeLikelihoodsOfHeterogenousPloidy's comment) -- a correct declaration
    // must still produce no warning
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      new FormatMetadata(InternalUtils.extractProperties(
          new String[]{"ID=GLE", "Number=G", "Type=String", "Description=\"d\""}, VcfWarnings.DEFAULT),
          VcfWarnings.DEFAULT);
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testNonReservedKeyIsNotChecked() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      new FormatMetadata(InternalUtils.extractProperties(
          new String[]{"ID=MYCUSTOM", "Number=3", "Type=String", "Description=\"d\""}, VcfWarnings.DEFAULT),
          VcfWarnings.DEFAULT);
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testAttributeOrderWarnsWhenOutOfCanonicalOrder() {
    // reproduces test/vcf/{4.1,4.2}/failed/failed_meta_format_003.vcf's
    // ##CauseOfFailure=Incorrect order of FORMAT fields
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      new FormatMetadata(InternalUtils.extractProperties(
          new String[]{"Number=1", "Type=Integer", "Description=\"Raw read depth\"", "ID=DP"}, VcfWarnings.DEFAULT),
          VcfWarnings.DEFAULT);
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testTypedConstructorDoesNotWarnAboutAttributeOrder() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      new FormatMetadata("DP", "Raw read depth", "1", FormatType.Integer);
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testTypedConstructorRejectsNullOrBlankId() {
    assertThrows(VcfFormatException.class, () -> new FormatMetadata(null, "d", "1", FormatType.String));
    assertThrows(VcfFormatException.class, () -> new FormatMetadata("", "d", "1", FormatType.String));
    assertThrows(VcfFormatException.class, () -> new FormatMetadata("   ", "d", "1", FormatType.String));
  }

  @Test
  public void testIdWithWhitespaceWarns() {
    // regression test: a FORMAT ID containing whitespace was previously silently accepted -- bcftools 1.23.1
    // warns "Invalid tag name" for this exact case
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      new FormatMetadata(InternalUtils.extractProperties(
          new String[]{"ID=A B", "Number=1", "Type=String", "Description=\"d\""}, VcfWarnings.DEFAULT),
          VcfWarnings.DEFAULT);
      assertTrue(appender.anyMessageContains("must not contain whitespace"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testGetReservedNumberReturnsReservedCode() {
    FormatMetadata md = new FormatMetadata(InternalUtils.extractProperties(
        new String[]{"ID=X", "Number=G", "Type=String", "Description=\"d\""}, VcfWarnings.DEFAULT),
        VcfWarnings.DEFAULT);
    assertEquals(SpecialVcfNumber.ONE_PER_GENOTYPE, md.getReservedNumber());
  }

  @Test
  public void testGetReservedNumberReturnsNullWhenNumberIsMissing() {
    // regression test: getReservedNumber() used to throw NullPointerException here instead of returning null,
    // because SpecialVcfNumber.fromId previously had no null guard; it now returns null for a null id.
    FormatMetadata md = new FormatMetadata(InternalUtils.extractProperties(
        new String[]{"ID=X", "Type=String", "Description=\"d\""}, VcfWarnings.DEFAULT),
        VcfWarnings.DEFAULT);
    assertNull(md.getNumber());
    assertNull(md.getReservedNumber());
  }

  @Test
  public void testTypedConstructorRejectsInvalidNumber() {
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> new FormatMetadata("XX", "desc", "not-a-number", FormatType.Integer));
    assertTrue(ex.getMessage().contains("Number is not a valid VCF number"));
  }

  @Test
  public void testTypedConstructorRejectsReservedNumberMismatch() {
    // DP is reserved FORMAT with expected Number "1"
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> new FormatMetadata("DP", "desc", "2", FormatType.Integer));
    assertTrue(ex.getMessage().contains("reserved definition expects"));
  }

  @Test
  public void testTypedConstructorAcceptsValidFormat() {
    FormatMetadata md = new FormatMetadata("DP", "read depth", "1", FormatType.Integer);
    assertEquals(FormatType.Integer, md.getType());
  }

  @Test
  public void testMapConstructorRequiresWarningsInstance() {
    java.util.Map<String, String> props = InternalUtils.extractProperties(
        new String[] {"ID=XX", "Number=bad", "Type=Integer", "Description=\"d\""}, VcfWarnings.DEFAULT);
    assertThrows(NullPointerException.class, () -> new FormatMetadata(props, null));
  }

  @Test
  public void testMapConstructorWarnsInsteadOfThrowingForInvalidNumber() {
    java.util.Map<String, String> props = InternalUtils.extractProperties(
        new String[] {"ID=XX", "Number=bad", "Type=Integer", "Description=\"d\""}, VcfWarnings.DEFAULT);
    StringWriter sink = new StringWriter();
    FormatMetadata md = new FormatMetadata(props, new VcfWarnings(sink));
    assertEquals("bad", md.getNumber());
    assertTrue(sink.toString().contains("Number is not a valid VCF number"));
  }
}
