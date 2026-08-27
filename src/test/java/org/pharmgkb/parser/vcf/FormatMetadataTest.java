package org.pharmgkb.parser.vcf;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.pharmgkb.parser.vcf.TestUtils.STRICT_CONTEXT;
import static org.pharmgkb.parser.vcf.TestUtils.LENIENT_CONTEXT;
import static org.pharmgkb.parser.vcf.TestUtils.lenientContext;
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
    FormatMetadata md = new FormatMetadata(LENIENT_CONTEXT,
        InternalUtils.extractProperties(props, VcfWarnings.DEFAULT));
    assertEquals(FormatType.String, md.getType());
    assertEquals(desc, md.getDescription());

    String[] nums = new String[] {
        "0", "23", "."
    };
    for (String n : nums) {
      props[1] = "Number=" + n;
      md = new FormatMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(props, VcfWarnings.DEFAULT));
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
      new FormatMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(props, VcfWarnings.DEFAULT));
    }
  }

  @Test
  public void testTypedConstructorRejectsNullTypeInsteadOfThrowingNpe() {
    // type is typed non-@Nullable, but nothing enforces that at compile time; must reject a null with
    // VcfFormatException, not let it reach a raw NullPointerException at type.name()
    assertThrows(VcfFormatException.class, () -> new FormatMetadata(STRICT_CONTEXT, "GT", "d", "1", null));
  }

  @Test
  public void testTypedConstructorRejectsZeroNumber() {
    // regression test: FORMAT has no Flag type (VCFv4.3.tex:190 lists only Integer/Float/Character/String),
    // so Number=0 -- spec-tied exclusively to Type=Flag (VCFv4.3.tex:171) -- can never legitimately appear on
    // a real ##FORMAT declaration, regardless of the declared Type. Unlike INFO's Flag/Number=0 pairing, this
    // fires unconditionally.
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> new FormatMetadata(STRICT_CONTEXT, "XYZ", "d", "0", FormatType.Integer));
    assertTrue(ex.getMessage().contains("has Number '0', but FORMAT has no Flag type"));
  }

  @Test
  public void testMapConstructorWarnsForZeroNumber() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      new FormatMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
          new String[]{"ID=XYZ", "Number=0", "Type=Integer", "Description=\"d\""}, VcfWarnings.DEFAULT));
      assertTrue(appender.anyMessageContains("has Number '0', but FORMAT has no Flag type"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testExtraPropertyRetained() {
    // VCFv4.2: "For all of the ##INFO, ##FORMAT, ##FILTER, and ##ALT metainformation, extra fields can be included
    // after the default fields" -- an unrecognized property is compliant, not just tolerated, and must be preserved
    FormatMetadata md = new FormatMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
        new String[]{"ID=X", "Number=1", "Type=String", "Description=\"d\"", "Custom=extra"}, VcfWarnings.DEFAULT));
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
      md = new FormatMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
          new String[]{"ID=GT", "Number=G", "Type=String", "Description=\"d\""}, VcfWarnings.DEFAULT));
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
      new FormatMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
          new String[]{"ID=GT", "Number=1", "Type=Integer", "Description=\"d\""}, VcfWarnings.DEFAULT));
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
      new FormatMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
          new String[]{"ID=GT", "Number=1", "Type=String", "Description=\"d\""}, VcfWarnings.DEFAULT));
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
      new FormatMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
          new String[]{"ID=GLE", "Number=G", "Type=String", "Description=\"d\""}, VcfWarnings.DEFAULT));
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testGleReservedNumberTypeCheckIsVersionGated() {
    // behavior 4 at the declaration site: a ##FORMAT=<ID=GLE,...> whose Number/Type differ from GLE's reserved
    // 4.1/4.2 canonical values (Number=G, Type=String) is non-compliant under <=4.2 -- hts-specs itself says so
    // (test/vcf/4.2/failed/failed_meta_format_029.vcf, ##CauseOfFailure=FORMAT GLE Number is not G) -- but is a
    // perfectly ordinary custom-key declaration under 4.3+, which removed the key (VCFv4.3.tex line 2168).
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      VcfContext lenient41 = VcfContext.lenient("VCFv4.1", VcfWarnings.DEFAULT);
      new FormatMetadata(lenient41, InternalUtils.extractProperties(
          new String[]{"ID=GLE", "Number=2", "Type=Integer", "Description=\"d\""}, VcfWarnings.DEFAULT));
      assertTrue(appender.anyMessageContains("has Number '2' but the reserved definition expects 'G'"));
      assertTrue(appender.anyMessageContains("has Type 'Integer' but the reserved definition expects 'String'"));
      long warnsUnder41 = appender.countMessagesContaining("but the reserved definition expects");

      new FormatMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
          new String[]{"ID=GLE", "Number=2", "Type=Integer", "Description=\"d\""}, VcfWarnings.DEFAULT));
      assertEquals(warnsUnder41 * 2, appender.countMessagesContaining("but the reserved definition expects"));
      long warnsUnder42 = appender.countMessagesContaining("but the reserved definition expects");

      VcfContext lenient43 = VcfContext.lenient("VCFv4.3", VcfWarnings.DEFAULT);
      new FormatMetadata(lenient43, InternalUtils.extractProperties(
          new String[]{"ID=GLE", "Number=2", "Type=Integer", "Description=\"d\""}, VcfWarnings.DEFAULT));
      assertEquals(warnsUnder42, appender.countMessagesContaining("but the reserved definition expects"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    // the attribute constructor is the second, independent route into the same check; under a strict 4.3
    // context the same declaration must be accepted outright rather than thrown on
    VcfContext strict43 = VcfContext.strict("VCFv4.3");
    FormatMetadata md = new FormatMetadata(strict43, "GLE", "d", "2", FormatType.Integer);
    assertEquals("2", md.getNumber());
    assertEquals(FormatType.Integer, md.getType());
    assertThrows(VcfFormatException.class,
        () -> new FormatMetadata(STRICT_CONTEXT, "GLE", "d", "2", FormatType.Integer));
  }

  @Test
  public void testCorrectlyDeclaredCnpDoesNotWarn() {
    // CNP keeps expectedNumber="G" (the literal, only spec value) despite being exempted from the generic
    // value-cardinality check elsewhere (see InternalUtils.isExemptFromGenotypeOrderingCardinalityCheck) --
    // this declared-Number check is a separate concern and must still accept a correct declaration cleanly
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      new FormatMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
          new String[]{"ID=CNP", "Number=G", "Type=Float", "Description=\"d\""}, VcfWarnings.DEFAULT));
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testIncorrectlyDeclaredCnpWarns() {
    // a CNP declared with a Number other than "G" is still a real mismatch worth flagging, even though the
    // generic value-cardinality check itself is skipped for CNP -- CNP has no VCFv4.2 declaration at all
    // (ReservedFormatProperty.isAddedAtV4_3), so this must be checked under a VCFv4.3+ context, not
    // LENIENT_CONTEXT's VCFv4.2 (see testCnpReservedNumberTypeCheckIsVersionGated for the pre-4.3 side)
    VcfContext lenient43 = VcfContext.lenient("VCFv4.3", VcfWarnings.DEFAULT);
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      new FormatMetadata(lenient43, InternalUtils.extractProperties(
          new String[]{"ID=CNP", "Number=1", "Type=Float", "Description=\"d\""}, VcfWarnings.DEFAULT));
      assertTrue(appender.anyMessageContains("reserved definition expects"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testPpReservedNumberTypeCheckIsVersionGated() {
    // Mirror image of testGleReservedNumberTypeCheckIsVersionGated above: PP is reserved (Number=G,
    // Type=Integer, i.e. Long.class) only as of VCFv4.3.tex's Reserved genotype keys table
    // (table:reserved-genotypes, line 475: "PP & G & Integer & Phred-scaled genotype posterior probabilities
    // rounded to the closest integer"); VCFv4.2.tex does not table -- or mention at all -- PP. Regression
    // test: ReservedFormatProperty.fromId(String, VcfVersion) previously gated key REMOVAL only (GLE), not
    // key INTRODUCTION, so a VCFv4.2 ##FORMAT=<ID=PP,...> was incorrectly checked against PP's 4.3 reserved
    // definition -- see ReservedFormatProperty.isAddedAtV4_3's own comment for the fix.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      // under 4.1/4.2, PP is not yet reserved, so a mismatched declaration is an ordinary custom key and
      // must not warn
      VcfContext lenient41 = VcfContext.lenient("VCFv4.1", VcfWarnings.DEFAULT);
      FormatMetadata md41 = new FormatMetadata(lenient41, InternalUtils.extractProperties(
          new String[]{"ID=PP", "Number=1", "Type=String", "Description=\"d\""}, VcfWarnings.DEFAULT));
      assertFalse(appender.hasWarnOrHigher());
      assertEquals("1", md41.getNumber());
      assertEquals(FormatType.String, md41.getType());

      FormatMetadata md42 = new FormatMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
          new String[]{"ID=PP", "Number=1", "Type=String", "Description=\"d\""}, VcfWarnings.DEFAULT));
      assertFalse(appender.hasWarnOrHigher());
      assertEquals("1", md42.getNumber());
      assertEquals(FormatType.String, md42.getType());

      // under 4.3+, PP is reserved and the same mismatch must warn (but still preserve the caller's own
      // declared values, not substitute the reserved definition's)
      VcfContext lenient43 = VcfContext.lenient("VCFv4.3", VcfWarnings.DEFAULT);
      FormatMetadata md43 = new FormatMetadata(lenient43, InternalUtils.extractProperties(
          new String[]{"ID=PP", "Number=1", "Type=String", "Description=\"d\""}, VcfWarnings.DEFAULT));
      assertTrue(appender.anyMessageContains("has Number '1' but the reserved definition expects 'G'"));
      assertTrue(appender.anyMessageContains("has Type 'String' but the reserved definition expects 'Long'"));
      assertEquals("1", md43.getNumber());
      assertEquals(FormatType.String, md43.getType());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    // a strict 4.2 context accepts the same declaration outright (PP isn't reserved there); a strict 4.3
    // context throws instead of warning, exactly as it would for any other reserved-key mismatch (see
    // testTypedConstructorRejectsReservedNumberMismatch above)
    FormatMetadata strictMd42 = new FormatMetadata(STRICT_CONTEXT, "PP", "d", "1", FormatType.String);
    assertEquals("1", strictMd42.getNumber());
    VcfContext strict43 = VcfContext.strict("VCFv4.3");
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> new FormatMetadata(strict43, "PP", "d", "1", FormatType.String));
    assertTrue(ex.getMessage().contains("reserved definition expects"));
  }

  @Test
  public void testCnpReservedNumberTypeCheckIsVersionGated() {
    // Same shape as testPpReservedNumberTypeCheckIsVersionGated above, for CNP: reserved as of VCFv4.3.tex's
    // "FORMAT keys used for structural variants" section (sv-format-keys, line 679:
    // "##FORMAT=<ID=CNP,Number=G,Type=Float,Description=\"Copy number posterior probabilities\">").
    // VCFv4.2.tex does not mention CNP at all. Regression test for the same fix as the PP test above.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      VcfContext lenient41 = VcfContext.lenient("VCFv4.1", VcfWarnings.DEFAULT);
      FormatMetadata md41 = new FormatMetadata(lenient41, InternalUtils.extractProperties(
          new String[]{"ID=CNP", "Number=1", "Type=String", "Description=\"d\""}, VcfWarnings.DEFAULT));
      assertFalse(appender.hasWarnOrHigher());
      assertEquals("1", md41.getNumber());
      assertEquals(FormatType.String, md41.getType());

      FormatMetadata md42 = new FormatMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
          new String[]{"ID=CNP", "Number=1", "Type=String", "Description=\"d\""}, VcfWarnings.DEFAULT));
      assertFalse(appender.hasWarnOrHigher());
      assertEquals("1", md42.getNumber());
      assertEquals(FormatType.String, md42.getType());

      VcfContext lenient43 = VcfContext.lenient("VCFv4.3", VcfWarnings.DEFAULT);
      FormatMetadata md43 = new FormatMetadata(lenient43, InternalUtils.extractProperties(
          new String[]{"ID=CNP", "Number=1", "Type=String", "Description=\"d\""}, VcfWarnings.DEFAULT));
      assertTrue(appender.anyMessageContains("has Number '1' but the reserved definition expects 'G'"));
      assertTrue(appender.anyMessageContains("has Type 'String' but the reserved definition expects 'VcfFloat'"));
      assertEquals("1", md43.getNumber());
      assertEquals(FormatType.String, md43.getType());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    FormatMetadata strictMd42 = new FormatMetadata(STRICT_CONTEXT, "CNP", "d", "1", FormatType.String);
    assertEquals("1", strictMd42.getNumber());
    VcfContext strict43 = VcfContext.strict("VCFv4.3");
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> new FormatMetadata(strict43, "CNP", "d", "1", FormatType.String));
    assertTrue(ex.getMessage().contains("reserved definition expects"));
  }

  @Test
  public void testCnlReservedNumberIsVersionAware() {
    // CNL is the one reserved key whose expected Number genuinely differs by version: VCFv4.2.tex:308 declares
    // "##FORMAT=<ID=CNL,Number=.,Type=Float,...>"; VCFv4.3.tex:678 (the target) redeclares it
    // "##FORMAT=<ID=CNL,Number=G,Type=Float,...>". A declaration correct for one version is therefore incorrect
    // for the other, in both directions.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      // 4.1/4.2: Number=. is correct, Number=G is not
      VcfContext lenient41 = VcfContext.lenient("VCFv4.1", VcfWarnings.DEFAULT);
      new FormatMetadata(lenient41, InternalUtils.extractProperties(
          new String[]{"ID=CNL", "Number=.", "Type=Float", "Description=\"d\""}, VcfWarnings.DEFAULT));
      assertFalse(appender.hasWarnOrHigher());
      new FormatMetadata(lenient41, InternalUtils.extractProperties(
          new String[]{"ID=CNL", "Number=G", "Type=Float", "Description=\"d\""}, VcfWarnings.DEFAULT));
      assertTrue(appender.anyMessageContains("has Number 'G' but the reserved definition expects '.'"));
      long warnsUnder41 = appender.countMessagesContaining("but the reserved definition expects");

      new FormatMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
          new String[]{"ID=CNL", "Number=.", "Type=Float", "Description=\"d\""}, VcfWarnings.DEFAULT));
      assertEquals(warnsUnder41, appender.countMessagesContaining("but the reserved definition expects"));
      new FormatMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
          new String[]{"ID=CNL", "Number=G", "Type=Float", "Description=\"d\""}, VcfWarnings.DEFAULT));
      // count-based, not anyMessageContains: the V4_1 construction just above already emitted the identical
      // "has Number 'G' but the reserved definition expects '.'" message, so anyMessageContains would pass
      // vacuously here even if this V4_2 construction warned zero new times -- mirrors
      // testGleReservedNumberTypeCheckIsVersionGated's warnsUnder41 * 2 pattern (here doubling from 1 to 2
      // since CNL's Number-only mismatch emits a single message per construction, not GLE's two).
      assertEquals(warnsUnder41 * 2, appender.countMessagesContaining("but the reserved definition expects"));
      long warnsUnder42 = appender.countMessagesContaining("but the reserved definition expects");

      // 4.3 (the target): Number=G is correct, Number=. is not
      VcfContext lenient43 = VcfContext.lenient("VCFv4.3", VcfWarnings.DEFAULT);
      new FormatMetadata(lenient43, InternalUtils.extractProperties(
          new String[]{"ID=CNL", "Number=G", "Type=Float", "Description=\"d\""}, VcfWarnings.DEFAULT));
      assertEquals(warnsUnder42, appender.countMessagesContaining("but the reserved definition expects"));
      new FormatMetadata(lenient43, InternalUtils.extractProperties(
          new String[]{"ID=CNL", "Number=.", "Type=Float", "Description=\"d\""}, VcfWarnings.DEFAULT));
      assertTrue(appender.anyMessageContains("has Number '.' but the reserved definition expects 'G'"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    // strict contexts throw instead of warning, in both directions
    assertThrows(VcfFormatException.class,
        () -> new FormatMetadata(STRICT_CONTEXT, "CNL", "d", "G", FormatType.Float));
    VcfContext strict43 = VcfContext.strict("VCFv4.3");
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> new FormatMetadata(strict43, "CNL", "d", ".", FormatType.Float));
    assertTrue(ex.getMessage().contains("reserved definition expects"));
    // a correctly-declared CNL is accepted outright by each version's own strict context
    FormatMetadata md42 = new FormatMetadata(STRICT_CONTEXT, "CNL", "d", ".", FormatType.Float);
    assertEquals(".", md42.getNumber());
    FormatMetadata md43 = new FormatMetadata(strict43, "CNL", "d", "G", FormatType.Float);
    assertEquals("G", md43.getNumber());
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
      new FormatMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
          new String[]{"ID=MYCUSTOM", "Number=3", "Type=String", "Description=\"d\""}, VcfWarnings.DEFAULT));
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testMapConstructorIsNotAttributeOrderChecked() {
    // reproduces test/vcf/{4.1,4.2}/failed/failed_meta_format_003.vcf's
    // ##CauseOfFailure=Incorrect order of FORMAT fields -- attribute-order checking was removed from every
    // keyed declaration type (see VCF_SPEC_AMBIGUITIES.md's "##META attribute order" entry), so this now
    // warns about nothing at all.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      new FormatMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
          new String[]{"Number=1", "Type=Integer", "Description=\"Raw read depth\"", "ID=DP"}, VcfWarnings.DEFAULT));
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testTypedConstructorInsertsPropertiesInCanonicalOrder() {
    // Not enforced by any validation any more (attribute order isn't checked at all), but the typed
    // constructor still inserts properties in the ID, Number, Type, Description order for output readability,
    // matching VCFv4.4.tex:134-135's "to aid human readability" recommendation.
    FormatMetadata format = new FormatMetadata(STRICT_CONTEXT, "DP", "Raw read depth", "1", FormatType.Integer);
    assertEquals(List.of("ID", "Number", "Type", "Description"), new ArrayList<>(format.getPropertyKeys()));
  }

  @Test
  public void testTypedConstructorRejectsNullOrBlankId() {
    assertThrows(VcfFormatException.class, () -> new FormatMetadata(STRICT_CONTEXT, null, "d", "1", FormatType.String));
    assertThrows(VcfFormatException.class, () -> new FormatMetadata(STRICT_CONTEXT, "", "d", "1", FormatType.String));
    assertThrows(VcfFormatException.class,
        () -> new FormatMetadata(STRICT_CONTEXT, "   ", "d", "1", FormatType.String));
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
      new FormatMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
          new String[]{"ID=A B", "Number=1", "Type=String", "Description=\"d\""}, VcfWarnings.DEFAULT));
      assertTrue(appender.anyMessageContains("must not contain whitespace"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testGetReservedNumberReturnsReservedCode() {
    FormatMetadata md = new FormatMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
        new String[]{"ID=X", "Number=G", "Type=String", "Description=\"d\""}, VcfWarnings.DEFAULT));
    assertEquals(SpecialVcfNumber.ONE_PER_GENOTYPE, md.getReservedNumber());
  }

  @Test
  public void testGetReservedNumberReturnsNullWhenNumberIsMissing() {
    // regression test: getReservedNumber() used to throw NullPointerException here instead of returning null,
    // because SpecialVcfNumber.fromId previously had no null guard; it now returns null for a null id.
    FormatMetadata md = new FormatMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
        new String[]{"ID=X", "Type=String", "Description=\"d\""}, VcfWarnings.DEFAULT));
    assertNull(md.getNumber());
    assertNull(md.getReservedNumber());
  }

  @Test
  public void testTypedConstructorRejectsInvalidNumber() {
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> new FormatMetadata(STRICT_CONTEXT, "XX", "desc", "not-a-number", FormatType.Integer));
    assertTrue(ex.getMessage().contains("Number is not a valid VCF number"));
  }

  @Test
  public void testTypedConstructorRejectsNullNumberWithSpecificMessage() {
    // regression test: see InfoMetadata's identical test -- putPropertyRaw(NUMBER, number) used to run
    // before this class's own Number-format check below it, so a null Number threw BaseMetadata's generic
    // "Metadata property \"Number\" has a null value" message instead of this class's own, more specific one.
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> new FormatMetadata(STRICT_CONTEXT, "XX", "desc", null, FormatType.Integer));
    assertTrue(ex.getMessage().contains("FORMAT Number is not a valid VCF number: 'null'"));
  }

  @Test
  public void testTypedConstructorPrioritizesInvalidNumberOverNullType() {
    // documents a real, narrow behavior change from moving the Number-format check ahead of putPropertyRaw:
    // when Number is malformed (non-null) AND Type is null simultaneously, the Number-format error now wins.
    // Previously the Type-null check fired first (it ran before the malformed-Number check, which used to
    // sit after Type/Description handling), so this exact input used to throw "FORMAT type is null" instead.
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> new FormatMetadata(STRICT_CONTEXT, "XX", "desc", "not-a-number", null));
    assertTrue(ex.getMessage().contains("Number is not a valid VCF number"));
  }

  @Test
  public void testTypedConstructorRejectsReservedNumberMismatch() {
    // DP is reserved FORMAT with expected Number "1"
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> new FormatMetadata(STRICT_CONTEXT, "DP", "desc", "2", FormatType.Integer));
    assertTrue(ex.getMessage().contains("reserved definition expects"));
  }

  @Test
  public void testAdReservedNumberCheckIsVersionGated() {
    // AD is reserved with expected Number "R" only as of VCFv4.3.tex's Reserved genotype keys table --
    // VCFv4.2.tex does not table FORMAT AD at all (ReservedFormatProperty.isAddedAtV4_3). "Number=." is the
    // form many real-world 4.2 pipelines emit for a custom AD-named key; regression test for the same fix as
    // the PP/CNP version-gating tests above -- this must not warn/throw under 4.2, but must under 4.3+.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      VcfContext lenient41 = VcfContext.lenient("VCFv4.1", VcfWarnings.DEFAULT);
      FormatMetadata md41 = new FormatMetadata(lenient41, InternalUtils.extractProperties(
          new String[]{"ID=AD", "Number=.", "Type=Integer", "Description=\"d\""}, VcfWarnings.DEFAULT));
      assertFalse(appender.hasWarnOrHigher());
      assertEquals(".", md41.getNumber());

      FormatMetadata md42 = new FormatMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
          new String[]{"ID=AD", "Number=.", "Type=Integer", "Description=\"d\""}, VcfWarnings.DEFAULT));
      assertFalse(appender.hasWarnOrHigher());
      assertEquals(".", md42.getNumber());

      VcfContext lenient43 = VcfContext.lenient("VCFv4.3", VcfWarnings.DEFAULT);
      FormatMetadata md43 = new FormatMetadata(lenient43, InternalUtils.extractProperties(
          new String[]{"ID=AD", "Number=.", "Type=Integer", "Description=\"d\""}, VcfWarnings.DEFAULT));
      assertTrue(appender.anyMessageContains("reserved definition expects"));
      assertEquals(".", md43.getNumber());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    FormatMetadata strictMd42 = new FormatMetadata(STRICT_CONTEXT, "AD", "desc", ".", FormatType.Integer);
    assertEquals(".", strictMd42.getNumber());
    VcfContext strict43 = VcfContext.strict("VCFv4.3");
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> new FormatMetadata(strict43, "AD", "desc", ".", FormatType.Integer));
    assertTrue(ex.getMessage().contains("reserved definition expects"));
  }

  @Test
  public void testTypedConstructorAcceptsValidFormat() {
    FormatMetadata md = new FormatMetadata(STRICT_CONTEXT, "DP", "read depth", "1", FormatType.Integer);
    assertEquals(FormatType.Integer, md.getType());
  }

  @Test
  public void testMapConstructorRequiresContextInstance() {
    java.util.Map<String, String> props = InternalUtils.extractProperties(
        new String[] {"ID=XX", "Number=bad", "Type=Integer", "Description=\"d\""}, VcfWarnings.DEFAULT);
    assertThrows(NullPointerException.class, () -> new FormatMetadata(null, props));
  }

  @Test
  public void testMapConstructorWarnsInsteadOfThrowingForInvalidNumber() {
    java.util.Map<String, String> props = InternalUtils.extractProperties(
        new String[] {"ID=XX", "Number=bad", "Type=Integer", "Description=\"d\""}, VcfWarnings.DEFAULT);
    StringWriter sink = new StringWriter();
    FormatMetadata md = new FormatMetadata(lenientContext(new VcfWarnings(sink)), props);
    assertEquals("bad", md.getNumber());
    assertTrue(sink.toString().contains("Number is not a valid VCF number"));
  }

  @Test
  public void testTypedConstructorRejectsInvalidTagNameCharacter() {
    assertThrows(VcfFormatException.class,
        () -> new FormatMetadata(STRICT_CONTEXT, "AB*C", "d", "1", FormatType.String));
  }

  @Test
  public void testTypedConstructorRejectsLeadingDigit() {
    assertThrows(VcfFormatException.class,
        () -> new FormatMetadata(STRICT_CONTEXT, "1ABC", "d", "1", FormatType.String));
  }

  @Test
  public void testTypedConstructorAcceptsValidTagName() {
    FormatMetadata md = new FormatMetadata(STRICT_CONTEXT, "DP", "d", "1", FormatType.Integer);
    assertEquals("DP", md.getId());
  }

  @Test
  public void testLenientConstructorWarnsOnInvalidTagNameCharacter() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    FormatMetadata md;
    try {
      md = new FormatMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
          new String[]{"ID=AB*C", "Number=1", "Type=String", "Description=\"d\""}, VcfWarnings.DEFAULT));
      assertTrue(appender.anyMessageContains("does not match the required tag-name grammar"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertEquals("AB*C", md.getId());
  }

  @Test
  public void testLenientConstructorDoesNotDoubleWarnForCommaContainingId() {
    // a comma in the ID is already warned about by IdMetadata's own shared check; this new tag-name-grammar
    // check must not ALSO warn about the same defect a second time
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      new FormatMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
          new String[]{"ID=AB,C", "Number=1", "Type=String", "Description=\"d\""}, VcfWarnings.DEFAULT));
      assertEquals(0, appender.countMessagesContaining("does not match the required tag-name grammar"));
      assertEquals(1, appender.countMessagesContaining("must not contain a comma"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testTypedConstructorDoesNotDoubleWarnForCommaContainingId() {
    // regression guard: the typed constructor previously checked the tag-name grammar against the raw id
    // without stripping commas/whitespace first, re-warning about a comma IdMetadata's own super(...) call had
    // already reported -- mirrors testLenientConstructorDoesNotDoubleWarnForCommaContainingId above, which
    // already covered this correctly for the Map-based constructor
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      new FormatMetadata(LENIENT_CONTEXT, "AB,C", "d", "1", FormatType.String);
      assertEquals(0, appender.countMessagesContaining("does not match the required tag-name grammar"));
      assertEquals(1, appender.countMessagesContaining("must not contain a comma"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testLenientConstructorWarnsForBothCommaAndInvalidCharacterDefects() {
    // regression test: the old guard skipped the entire tag-name-grammar check whenever a comma (or whitespace)
    // was present anywhere in the id, which silently suppressed a genuinely different defect (e.g. an invalid
    // character) coexisting in the same id -- confirmed against bcftools 1.23.1, which flags the '*' in
    // "AB,C*D" as "Invalid tag name" independently of the comma/whitespace. Both defects must now be reported.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      new FormatMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
          new String[]{"ID=AB,C*D", "Number=1", "Type=String", "Description=\"d\""}, VcfWarnings.DEFAULT));
      assertEquals(1, appender.countMessagesContaining("must not contain a comma"));
      assertEquals(1, appender.countMessagesContaining("does not match the required tag-name grammar"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testLenientConstructorDoesNotWarnForValidTagName() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      new FormatMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
          new String[]{"ID=DP", "Number=1", "Type=Integer", "Description=\"d\""}, VcfWarnings.DEFAULT));
      assertFalse(appender.anyMessageContains("does not match the required tag-name grammar"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testTypedConstructorAppliesReservedKeyValidationToQuotedId() {
    // regression test: the typed constructor previously validated the raw (possibly quoted) id/number instead
    // of unquoting first, matching neither its own tag-name-grammar check nor the ReservedFormatProperty lookup
    // against a quoted value -- so ID="GT" silently skipped the Number/Type mismatch check this class's own
    // init() (the Map-based constructor's path) already performs for the equivalent unquoted declaration.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      new FormatMetadata(LENIENT_CONTEXT, "\"GT\"", "d", "\"2\"", FormatType.Integer);
      assertEquals(0, appender.countMessagesContaining("does not match the required tag-name grammar"));
      assertEquals(0, appender.countMessagesContaining("is not a valid VCF number"));
      assertEquals(1, appender.countMessagesContaining("but the reserved definition expects '1'"));
      assertEquals(1, appender.countMessagesContaining("but the reserved definition expects 'String'"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testTypedConstructorRejects1000GUnlikeInfo() {
    // unlike INFO_PATTERN, FORMAT_PATTERN has no 1000G legacy exception -- confirmed directly against
    // bcftools 1.23.1, which accepts ##INFO=<ID=1000G,...> silently but flags ##FORMAT=<ID=1000G,...> as
    // "Invalid tag name"
    assertThrows(VcfFormatException.class,
        () -> new FormatMetadata(STRICT_CONTEXT, "1000G", "d", "1", FormatType.String));
  }

  @Test
  public void testReservedKeyWithMissingNumberViaTypedConstructorWarnsOnceNotTwice() {
    // regression test for the fifth re-verification round's finding #7: the typed constructor's
    // reserved-Number-mismatch check had no unquotedNumber != null guard, unlike init()'s own identical
    // check (the Map-based constructor), so a reserved key (here DP, expected Number "1") with Number
    // simply absent fired both the legitimate "Number is not a valid VCF number: 'null'" warning AND a
    // second, redundant "has Number 'null' but the reserved definition expects '1'" one.
    StringWriter sink = new StringWriter();
    new FormatMetadata(lenientContext(new VcfWarnings(sink)), "DP", "d", null, FormatType.Integer);
    assertTrue(sink.toString().contains("Number is not a valid VCF number: 'null'"), sink.toString());
    assertFalse(sink.toString().contains("but the reserved definition expects"), sink.toString());
  }
}
