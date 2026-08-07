package org.pharmgkb.parser.vcf;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import org.junit.jupiter.api.Test;
import org.pharmgkb.parser.vcf.model.ReservedInfoProperty;
import org.pharmgkb.parser.vcf.model.VcfFloat;
import org.pharmgkb.parser.vcf.model.VcfPosition;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link VcfPosition}.
 * @author Douglas Myers-Turnbull
 */
public class VcfPositionTest {

  @Test
  public void testLazyQuality() {
    VcfPosition value = newPosition();
    value.setRawQuality("29.5");
    assertEquals(new BigDecimal("29.5"), value.getQuality());
    assertEquals(new BigDecimal("29.5"), value.getQuality()); // stable across repeated reads

    VcfPosition missing = newPosition();
    missing.setRawQuality(".");
    assertNull(missing.getQuality());

    VcfPosition empty = newPosition();
    empty.setRawQuality("");
    assertNull(empty.getQuality());

    // an invalid QUAL is a value-quality issue, not a structural one; it warns and is treated as missing, rather
    // than throwing
    VcfPosition bad = newPosition();
    bad.setRawQuality("not-a-number");
    assertNull(bad.getQuality());

    // setQuality overrides any pending raw value
    VcfPosition override = newPosition();
    override.setRawQuality("1");
    override.setQuality(new BigDecimal("2"));
    assertEquals(new BigDecimal("2"), override.getQuality());
  }

  @Test
  public void testGetQualityDoesNotHangOnPathologicallyLongAdversarialValue() {
    // regression test: getQuality() ultimately calls VcfFloat.parseDecimal, which used to call
    // `new BigDecimal(value)` with no length bound -- a CPU-exhaustion vector via an attacker-controlled QUAL
    // value. An oversized value is now rejected before the expensive parse is attempted, treated the same as
    // any other unparseable QUAL (warns, falls back to the missing value). 3 seconds is a wide margin above
    // the fixed behavior's near-instant fallback at this size.
    VcfPosition pathological = newPosition();
    pathological.setRawQuality("1".repeat(500_000));
    assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () -> assertNull(pathological.getQuality()));
  }

  @Test
  public void testQualityWarnsOnGrammarMismatchButStillParses() {
    // regression test: BigDecimal accepts "5." (a trailing decimal point with no digits after it), but
    // VCFv4.3.tex line 100's Float grammar (QUAL is VCF-spec-typed Float) requires at least one digit after
    // the decimal point -- VcfFloat.parse() already warns rather than silently accepting this; getQuality()
    // used to accept it with no diagnostic at all
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      VcfPosition value = newPosition();
      value.setRawQuality("5.");
      assertEquals(new BigDecimal("5"), value.getQuality());
      assertTrue(appender.hasWarnOrHigher());
      assertTrue(appender.anyMessageContains("does not match the VCF Float grammar"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testQualityDoesNotWarnOnNormalValue() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      VcfPosition value = newPosition();
      value.setRawQuality("29.5");
      value.getQuality();
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testLazyInfo() {
    VcfPosition p = newPosition();
    p.setRawInfo("DP=35;AF=0.5,0.25;FLAG");
    assertEquals(Collections.singletonList("35"), p.getInfo("DP"));
    assertEquals(Arrays.asList("0.5", "0.25"), p.getInfo("AF"));
    assertTrue(p.hasInfo("FLAG"));
    assertEquals(Collections.singletonList(""), p.getInfo("FLAG"));
    assertNull(p.getInfo("MISSING"));
    assertFalse(p.hasInfo("MISSING"));

    VcfPosition dot = newPosition();
    dot.setRawInfo(".");
    assertTrue(dot.getInfo().isEmpty());
    assertFalse(dot.hasInfo("X"));
  }

  @Test
  public void testLazyInfoWithEmptyEntries() {
    // VCF does not allow zero-length fields; these warn and normalize rather than throw or silently drop-then-hide
    // the problem (see EMPTY_FIELD_HANDLING.md). DP=10;;AF=0.5 -> the empty prop between ';'s is dropped entirely.
    VcfPosition emptyProp = newPosition();
    emptyProp.setRawInfo("DP=10;;AF=0.5");
    assertEquals(Collections.singletonList("10"), emptyProp.getInfo("DP"));
    assertEquals(Collections.singletonList("0.5"), emptyProp.getInfo("AF"));
    assertEquals(2, emptyProp.getInfo().size());

    // a trailing ';' is the same as an interior empty prop
    VcfPosition trailingProp = newPosition();
    trailingProp.setRawInfo("DP=10;");
    assertEquals(Collections.singletonList("10"), trailingProp.getInfo("DP"));
    assertEquals(1, trailingProp.getInfo().size());

    // AD=1,,2 -> the empty value between ','s is treated as the missing value '.'
    VcfPosition emptyValue = newPosition();
    emptyValue.setRawInfo("AD=1,,2");
    assertEquals(Arrays.asList("1", ".", "2"), emptyValue.getInfo("AD"));

    // a trailing ',' is the same as an interior empty value
    VcfPosition trailingValue = newPosition();
    trailingValue.setRawInfo("AD=1,2,");
    assertEquals(Arrays.asList("1", "2", "."), trailingValue.getInfo("AD"));

    // AD= (no comma at all) -> a single missing value, not a flag (which is stored the same way internally, but only
    // when there is no '=' sign at all)
    VcfPosition wholeEmpty = newPosition();
    wholeEmpty.setRawInfo("AD=");
    assertEquals(Collections.singletonList("."), wholeEmpty.getInfo("AD"));

    // a flag (no '=' sign) is unaffected: its sentinel empty value must not be confused with a real empty field
    VcfPosition flag = newPosition();
    flag.setRawInfo("DB;DP=10");
    assertTrue(flag.hasInfo("DB"));
    assertEquals(Collections.singletonList(""), flag.getInfo("DB"));
    assertEquals(Collections.singletonList("10"), flag.getInfo("DP"));
  }

  @Test
  public void testLazyInfoDotCannotCombineWithOtherProperties() {
    // "." (no INFO at all) cannot be combined with a real property, the same as ALT/FILTER's exclusivity rule for
    // their own "." sentinel
    VcfPosition combined = newPosition();
    combined.setRawInfo(".;DP=1");
    assertThrows(VcfFormatException.class, combined::getInfo);

    // a stray trailing ';' after a lone "." is not "combined with other content" (the only other split part is an
    // empty-string artifact, already warned about and dropped elsewhere) -- must not throw, and the leftover "."
    // is still the "no INFO at all" sentinel, not a literal property named "."
    VcfPosition trailingSemicolon = newPosition();
    trailingSemicolon.setRawInfo(".;");
    assertTrue(trailingSemicolon.getInfo().isEmpty());
  }

  @Test
  public void testLazyInfoExceptionHasNoLineNumberWhenNotConstructedByParser() {
    // regression test: a VcfPosition NOT produced by parsing a real file (lineNumber=-1, the newPosition() helper's
    // default) must keep today's existing bare-message exception shape -- proving this feature is purely additive
    // for every non-parser-constructed VcfPosition, existing or future
    VcfPosition combined = newPosition();
    combined.setRawInfo(".;DP=1");
    VcfFormatException ex = assertThrows(VcfFormatException.class, combined::getInfo);
    assertEquals(-1, ex.getLineNumber());
    assertFalse(ex.getMessage().contains("[Line #"));
  }

  @Test
  public void testLazyInfoValueContainingEqualsSignWarnsAndPreserves() {
    // VCFv4.2's INFO grammar note ("no whitespace, semicolons, or equals-signs permitted") technically disallows
    // this, but bcftools 1.23.1 accepts it silently (verified live: "DP=1=2" round-trips as-is, splitting only on
    // the first '='); this parser matches that tolerance instead of rejecting it
    VcfPosition position = newPosition();
    position.setRawInfo("DP=1=2");
    assertEquals(Collections.singletonList("1=2"), position.getInfo("DP"));
  }

  @Test
  public void testLazyInfoDuplicateKeyKeepsOnlyFirstOccurrence() {
    // VCFv4.2 is silent on duplicate top-level INFO keys, but VCFv4.3 explicitly disallows them ("Duplicate keys
    // are not allowed"). bcftools 1.23.1 doesn't reject this, though -- verified live: "DP=1;DP=2" is accepted
    // with no warning, and bcftools keeps only the first occurrence ("1"), not "1,2". This parser warns (rather
    // than throwing) and matches that same "keep first, drop rest" behavior, instead of silently merging
    // duplicates into what would look like a legitimate multi-value list.
    VcfPosition position = newPosition();
    position.setRawInfo("DP=1;DP=2");
    assertEquals(Collections.singletonList("1"), position.getInfo("DP"));

    // a duplicate flag-form key (no "=") behaves the same way
    VcfPosition flagPosition = newPosition();
    flagPosition.setRawInfo("DB;DB");
    assertEquals(Collections.singletonList(""), flagPosition.getInfo("DB"));
  }

  @Test
  public void testGetInfoMappingQuality() {
    // MQ is a float reserved property; getInfo must convert it (it was previously typed Float.class, which threw)
    VcfPosition p = newPosition();
    p.setRawInfo("MQ=52.4");
    assertEquals(VcfFloat.of(new BigDecimal("52.4")), p.getInfo(ReservedInfoProperty.MappingQuality));
  }

  @Test
  public void testGetInfoCigarIsListValued() {
    // VCFv4.2 only describes CIGAR in prose with no formal Number, but VCFv4.3/4.4/4.5 all declare it Number=A
    // (one value per ALT allele); a multi-allelic record's CIGAR must therefore convert to a List, not a scalar
    VcfPosition p = newPosition();
    p.getAltBases().add("T");
    p.getAltBases().add("C");
    p.setRawInfo("CIGAR=1M,2M");
    assertEquals(Arrays.asList("1M", "2M"), p.getInfo(ReservedInfoProperty.Cigar));
  }

  @Test
  public void testGetInfoAlleleDepthIsListValued() {
    // AD/ADF/ADR were previously missing from ReservedInfoProperty entirely, so getInfo(ReservedInfoProperty)
    // could not be called with them at all; Number=R means one value per allele, including REF, so a biallelic
    // record's AD converts to a two-element List, not a scalar
    VcfPosition p = newPosition();
    p.getAltBases().add("T");
    p.setRawInfo("AD=10,5");
    assertEquals(Arrays.asList(10L, 5L), p.getInfo(ReservedInfoProperty.AlleleDepth));
  }

  @Test
  public void testGetInfoFloatAcceptsSpecialValues() {
    // VCFv4.2 doesn't define Float's grammar at all; VCFv4.5 clarifies it accepts NAN/INF/INFINITY (case-insensitive,
    // optionally signed) in addition to a normal decimal number -- a plain BigDecimal can't represent these at all
    VcfPosition nan = newPosition();
    nan.setRawInfo("MQ=NaN");
    assertEquals(VcfFloat.ofSpecial(VcfFloat.Special.NAN), nan.getInfo(ReservedInfoProperty.MappingQuality));

    VcfPosition posInf = newPosition();
    posInf.setRawInfo("MQ=INFINITY");
    assertEquals(VcfFloat.ofSpecial(VcfFloat.Special.POSITIVE_INFINITY), posInf.getInfo(ReservedInfoProperty.MappingQuality));

    VcfPosition negInf = newPosition();
    negInf.setRawInfo("MQ=-inf");
    assertEquals(VcfFloat.ofSpecial(VcfFloat.Special.NEGATIVE_INFINITY), negInf.getInfo(ReservedInfoProperty.MappingQuality));
  }

  @Test
  public void testGetInfoHomologySequence() {
    // HomologySequence must resolve the HOMSEQ key (it was previously misdefined as HOMLEN)
    assertEquals("HOMSEQ", ReservedInfoProperty.HomologySequence.getId());
    // Number=. is genuinely multi-valued (per VCFv4.3's clarifying example for this family of keys), so this
    // returns a List rather than a bare String
    VcfPosition p = newPosition();
    p.setRawInfo("HOMSEQ=ACGT");
    assertEquals(Collections.singletonList("ACGT"), p.getInfo(ReservedInfoProperty.HomologySequence));
  }

  @Test
  public void testGetInfoStructuralVariantLengthIsMultiValued() {
    // SVLEN was previously declared isList=false, but VCFv4.3 confirms with an example (SVLEN=-100,-110 for a
    // deletion with two ALT alleles) that Number=. here means genuinely multi-valued, one per ALT allele
    VcfPosition p = newPosition();
    p.setRawInfo("SVLEN=-100,-110");
    assertEquals(Arrays.asList(-100L, -110L), p.getInfo(ReservedInfoProperty.StructuralVariantLength));
  }

  @Test
  public void testGetInfoListTypedKeyCardinalityViolationWarnsButKeepsAllValues() {
    // AC is Number=A (list-typed): with 1 ALT allele, exactly 1 value is expected, but 2 are given. Unlike the
    // scalar-key violation below (which truncates to the first value), a list-typed violation warns but returns
    // every given value unchanged -- this is the INFO-side counterpart of VcfSample.getProperty(VcfPosition,
    // VcfMetadata, String)'s identical FORMAT-side check, added earlier this session
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      VcfPosition p = newPosition();
      p.getAltBases().add("T");
      p.setRawInfo("AC=1,2");
      assertEquals(Arrays.asList(1L, 2L), p.getInfo(ReservedInfoProperty.AlleleCount));
      assertTrue(appender.anyMessageContains("INFO AC has 2 value(s), but Number=A requires 1"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testGetInfoFixedCountKeyCardinalityViolationWarns() {
    // CIPOS is a fixed Number=2 (not ALT-allele-derived); 3 values given instead of 2 must still warn
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      VcfPosition p = newPosition();
      p.setRawInfo("CIPOS=1,2,3");
      assertEquals(Arrays.asList(1L, 2L, 3L), p.getInfo(ReservedInfoProperty.ConfidenceIntervalForPosition));
      assertTrue(appender.anyMessageContains("INFO CIPOS has 3 value(s), but Number=2 requires 2"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testGetInfoListTypedKeyCorrectCardinalityDoesNotWarn() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      VcfPosition p = newPosition();
      p.getAltBases().add("T");
      p.getAltBases().add("C");
      p.setRawInfo("AC=1,2");
      assertEquals(Arrays.asList(1L, 2L), p.getInfo(ReservedInfoProperty.AlleleCount));
      assertFalse(appender.anyMessageContains("value(s), but Number="));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testGetInfoListTypedKeyDotSentinelDoesNotWarnCardinality() {
    // AD's whole value normalizes to a single "." entry when the raw INFO text is "AD=" (an entirely empty
    // value, per EMPTY_FIELD_HANDLING.md's documented table) -- that represents this key's value being absent
    // altogether, not a real 1-element value that violates AD's Number=R (2 expected, for 1 ALT allele)
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      VcfPosition p = newPosition();
      p.getAltBases().add("T");
      p.setRawInfo("AD=");
      assertEquals(Collections.singletonList("."), p.getInfo("AD"));
      assertNull(p.getInfo(ReservedInfoProperty.AlleleDepth));
      assertFalse(appender.anyMessageContains("value(s), but Number="));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testGetInfoStringTypedScalarKeyCardinalityViolationWarnsAndKeepsFirstValue() {
    // AA is Number=1/String-typed; info() unconditionally comma-splits every raw INFO value regardless of the
    // key's declared cardinality, and the pre-fix code rejoined the split pieces with "," before Type conversion
    // -- for a String-typed key, that rejoined "1,23" round-tripped as one opaque, unvalidated string, silently
    // hiding the cardinality violation entirely (no warning, no throw)
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      VcfPosition p = newPosition();
      p.setRawInfo("AA=1,23");
      assertEquals("1", p.getInfo(ReservedInfoProperty.AncestralAllele));
      assertEquals(1, appender.countMessagesContaining("INFO AA has 2 value(s), but Number=1 requires 1"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testGetInfoNumericTypedScalarKeyCardinalityViolationWarnsInsteadOfThrowing() {
    // DP is Number=1/Long-typed; the pre-fix rejoin instead crashed here (Long.parseLong("1,23") throws
    // VcfFormatException), even though AGENTS.md's Parser Validation Policy says a non-structural INFO problem
    // should warn and recover, not throw
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      VcfPosition p = newPosition();
      p.setRawInfo("DP=1,23");
      assertEquals(Long.valueOf(1L), p.getInfo(ReservedInfoProperty.Depth));
      assertEquals(1, appender.countMessagesContaining("INFO DP has 2 value(s), but Number=1 requires 1"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testGetInfoScalarKeySingleValueDoesNotWarnCardinality() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      VcfPosition p = newPosition();
      p.setRawInfo("AA=A");
      assertEquals("A", p.getInfo(ReservedInfoProperty.AncestralAllele));
      assertEquals(0, appender.countMessagesContaining("value(s), but Number="));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testGetInfoKeysPreservesInsertionOrder() {
    // confirmed via direct reproduction during the writer-ordering investigation: ArrayListMultimap's key
    // iteration order is hash-based, not insertion order, so this previously came back as [AC, AF, AN]
    VcfPosition p = newPosition();
    p.setRawInfo("AN=2184;AC=249;AF=0.11");
    assertEquals(Arrays.asList("AN", "AC", "AF"), new ArrayList<>(p.getInfoKeys()));
  }

  private static VcfPosition newPosition() {
    return new VcfPosition("chr", 1, null, "C", null, null, null, null, null, -1);
  }

  @Test
  public void testGetAllele() {
    VcfPosition position = new VcfPosition("chr1", 1, null, "C", new ArrayList<>(Arrays.asList("A", "T")), null, null,
        null, null, -1);
    assertEquals("C", position.getAllele(0));
    assertEquals("A", position.getAllele(1));
    assertEquals("T", position.getAllele(2));
    assertThrows(IndexOutOfBoundsException.class, () -> position.getAllele(3));

    // lookup reflects a mutation to REF (no stale cache)
    position.setRef("G");
    assertEquals("G", position.getAllele(0));

    // lookup reflects a mutation to the ALT list (no stale cache)
    position.getAltBases().add("N");
    assertEquals("N", position.getAllele(3));
  }

  @Test
  public void testGetAlleleShortConstructor() {
    VcfPosition position = new VcfPosition("chr1", 1, "C", new BigDecimal("0.0"), -1);
    assertEquals("C", position.getAllele(0));
    assertThrows(IndexOutOfBoundsException.class, () -> position.getAllele(1));
  }

  @Test
  public void testFilterStatus() {
    VcfPosition none = new VcfPosition("chr1", 1, null, "C", null, null, Collections.singletonList("."),
        null, null, -1);
    assertEquals(VcfPosition.FilterStatus.NONE, none.getFilterStatus());
    assertTrue(none.isPassingAllFilters()); // no filters applied is treated as passing
    assertTrue(none.getFilters().isEmpty());

    VcfPosition passed = new VcfPosition("chr1", 1, null, "C", null, null, Collections.singletonList("PASS"),
        null, null, -1);
    assertEquals(VcfPosition.FilterStatus.PASSED, passed.getFilterStatus());
    assertTrue(passed.isPassingAllFilters());
    assertEquals(Collections.singletonList("PASS"), passed.getFilters());

    // regression test: an unspecified FILTER is NONE, not an implicit PASSED -- PASS must always be explicit,
    // matching bcftools 1.23.1 (neither "bcftools annotate -x FILTER" nor a literal empty FILTER string ever
    // produces PASS; see VcfPosition.getFilterStatus()'s Javadoc)
    VcfPosition noFilters = new VcfPosition("chr1", 1, null, "C", null, null, null, null, null, -1);
    assertEquals(VcfPosition.FilterStatus.NONE, noFilters.getFilterStatus());

    VcfPosition failed = new VcfPosition("chr1", 1, null, "C", null, null, Collections.singletonList("q10"),
        null, null, -1);
    assertEquals(VcfPosition.FilterStatus.FAILED, failed.getFilterStatus());
    assertFalse(failed.isPassingAllFilters());
    assertEquals(Collections.singletonList("q10"), failed.getFilters());
  }

  @Test
  public void testBadFilterDotWithOthers() {
    assertThrows(VcfFormatException.class, () ->
        new VcfPosition("chr1", 1, null, "C", null, null, Arrays.asList("q10", "."), null, null, -1));
  }

  @Test
  public void testNoFilters() {
    VcfPosition position = new VcfPosition("chr", 1, null, "C", null, null, null, null, null, -1);
    assertTrue(position.getFilters().isEmpty());
    assertTrue(position.isPassingAllFilters());
    assertEquals(VcfPosition.FilterStatus.NONE, position.getFilterStatus());
  }

  @Test
  public void testHasFilters() {
    VcfPosition position = new VcfPosition("chr", 1, null, "C", null, null, Collections.singletonList("bad"),
        null, null, -1);
    assertEquals(1, position.getFilters().size());
    assertEquals("bad", position.getFilters().get(0));
    assertFalse(position.isPassingAllFilters());
  }

  @Test
  public void testBadFilter1() {
    VcfPosition position = new VcfPosition("chr", 1, null, "C", null, null, Collections.singletonList("PASS"),
        null, null, -1);
    assertEquals(Collections.singletonList("PASS"), position.getFilters());
    assertTrue(position.isPassingAllFilters());
  }

  @Test
  public void testBadFilter2() {
    // PASS combined with another code now warns, not throws -- but only via the lenient 11-arg constructor
    // overload (see VcfPosition.checkFilters); the bare 10-arg constructor is now strict and throws for this.
    // hts-specs' own passed/complexfile_passed_000.vcf fixture (all versions) does exactly this and is expected
    // to parse.
    StringWriter sink = new StringWriter();
    VcfPosition position = new VcfPosition("chr", 1, null, "C", null, null, Arrays.asList("bad", "PASS"),
        null, null, -1, new VcfWarnings(sink));
    assertTrue(sink.toString().contains("PASS along with other filters"));
    assertEquals(Arrays.asList("bad", "PASS"), position.getFilters());
  }

  @Test
  public void testEmptyIdEntriesDropped() {
    // VCF does not allow zero-length fields; ID is a position-independent list, so an empty entry is dropped rather
    // than throwing (see EMPTY_FIELD_HANDLING.md). Uses the lenient 11-arg constructor overload -- the bare 10-arg
    // constructor is now strict and throws for an empty entry instead of dropping it.
    VcfPosition interior = new VcfPosition("chr", 1, new ArrayList<>(Arrays.asList("rs1", "", "rs2")), "C", null, null,
        null, null, null, -1, VcfWarnings.DEFAULT);
    assertEquals(Arrays.asList("rs1", "rs2"), interior.getIds());

    VcfPosition trailing = new VcfPosition("chr", 1, new ArrayList<>(Arrays.asList("rs3", "")), "C", null, null,
        null, null, null, -1, VcfWarnings.DEFAULT);
    assertEquals(Collections.singletonList("rs3"), trailing.getIds());
  }

  @Test
  public void testEmptyFilterEntriesDropped() {
    // Uses the lenient 11-arg constructor overload -- the bare 10-arg constructor is now strict and throws for
    // an empty entry instead of dropping it.
    VcfPosition interior = new VcfPosition("chr", 1, null, "C", null, null,
        new ArrayList<>(Arrays.asList("q10", "", "q20")), null, null, -1, VcfWarnings.DEFAULT);
    assertEquals(Arrays.asList("q10", "q20"), interior.getFilters());

    VcfPosition trailing = new VcfPosition("chr", 1, null, "C", null, null,
        new ArrayList<>(Arrays.asList("q10", "")), null, null, -1, VcfWarnings.DEFAULT);
    assertEquals(Collections.singletonList("q10"), trailing.getFilters());
  }

  @Test
  public void testEmptyAltEntriesSubstitutedWithDot() {
    // ALT is position-dependent (GT indexes into it -- see testGetAlleleAfterAltDotSubstitutionPreservesIndex),
    // so an empty entry is substituted with '.', not dropped, unlike ID/FILTER above. Uses the lenient 11-arg
    // constructor overload -- the bare 10-arg constructor is now strict and throws for an empty entry instead of
    // substituting it.
    VcfPosition interior = new VcfPosition("chr", 1, null, "C",
        new ArrayList<>(Arrays.asList("T", "", "G")), null, null, null, null, -1, VcfWarnings.DEFAULT);
    assertEquals(Arrays.asList("T", ".", "G"), interior.getAltBases());

    VcfPosition trailing = new VcfPosition("chr", 1, null, "C",
        new ArrayList<>(Arrays.asList("T", "")), null, null, null, null, -1, VcfWarnings.DEFAULT);
    assertEquals(Arrays.asList("T", "."), trailing.getAltBases());
  }

  @Test
  public void testGetAlleleAfterAltDotSubstitutionPreservesIndex() {
    // regression test for the actual bug: dropping an empty ALT entry (the old behavior) shifted every subsequent
    // allele's index down by one, so GT=4 (meaning "G") would silently resolve to the wrong allele after
    // normalization instead of throwing or staying correct. Substituting '.' instead preserves every index --
    // confirmed against bcftools 1.23.1, which does the same thing (see EMPTY_FIELD_HANDLING.md). Uses the
    // lenient 11-arg constructor overload -- the bare 10-arg constructor is now strict and throws for an empty
    // entry instead of substituting it.
    VcfPosition position = new VcfPosition("chr1", 1, null, "C",
        new ArrayList<>(Arrays.asList("T", "C", "", "G")), null, null, null, null, -1, VcfWarnings.DEFAULT);
    assertEquals(Arrays.asList("T", "C", ".", "G"), position.getAltBases());
    assertEquals("C", position.getAllele(0));
    assertEquals("T", position.getAllele(1));
    assertEquals("C", position.getAllele(2));
    assertEquals(".", position.getAllele(3));
    assertEquals("G", position.getAllele(4));
  }

  @Test
  public void testEmptyFormatSubFieldKeptAsIs() {
    // unlike ID/FILTER/ALT, an empty FORMAT key is kept (not dropped): every sample's colon-split values are matched
    // to FORMAT keys by index, and dropping this key would misalign every sample's values with the wrong key. Uses
    // the lenient 11-arg constructor overload -- the bare 10-arg constructor is now strict and throws for an empty
    // sub-field name instead of keeping it.
    VcfPosition position = new VcfPosition("chr", 1, null, "C", null, null, null, null,
        new ArrayList<>(Arrays.asList("GT", "", "GQ")), -1, VcfWarnings.DEFAULT);
    assertEquals(Arrays.asList("GT", "", "GQ"), position.getFormat());
  }

  @Test
  public void testDuplicateFormatKeyRejected() {
    // VCFv4.3+ states outright that duplicate FORMAT keys are not allowed
    assertThrows(VcfFormatException.class, () -> new VcfPosition("chr", 1, null, "C", null, null, null, null,
        new ArrayList<>(Arrays.asList("GT", "DP", "DP")), -1));
  }

  @Test
  public void testMultipleEmptyFormatKeysNotTreatedAsDuplicates() {
    // an empty FORMAT key is already handled (kept as-is, warned about) separately; two of them are not a "duplicate"
    // in the sense the spec means. Uses the lenient 11-arg constructor overload -- the bare 10-arg constructor is
    // now strict and throws for an empty sub-field name instead of keeping it.
    VcfPosition position = new VcfPosition("chr", 1, null, "C", null, null, null, null,
        new ArrayList<>(Arrays.asList("GT", "", "")), -1, VcfWarnings.DEFAULT);
    assertEquals(Arrays.asList("GT", "", ""), position.getFormat());
  }

  @Test
  public void testLoneDotFormatMeansNoFormatFields() {
    // FORMAT="." (no per-sample format fields at all) is valid when sample columns are present -- VCFv4.2 through
    // 4.5 are all silent on this exact case, but bcftools 1.23.1 accepts and correctly interprets it; see
    // VcfPosition.checkFormat's javadoc for the full rationale
    VcfPosition position = new VcfPosition("chr", 1, null, "C", null, null, null, null,
        new ArrayList<>(Collections.singletonList(".")), -1);
    assertTrue(position.getFormat().isEmpty());
  }

  @Test
  public void testBadChromosome() {
    assertThrows(VcfFormatException.class, () -> {
      new VcfPosition("chr 1", 1, null, "C", null, null, null, null, null, -1); // whitespace is not allowed
    });
  }

  @Test
  public void testColonInChromosomeAllowed() {
    // the VCF spec forbids only whitespace in CHROM; a colon is allowed
    new VcfPosition("chr:1", 1, null, "C", null, null, null, null, null, -1);
  }

  @Test
  public void testInvalidCharactersRejectedAnywhereInChromosome() {
    // VCFv4.2.tex line 179 only states "no whitespace permitted" for CHROM; this fuller grammar is VCFv4.2-silent
    // clarifying guidance from VCFv4.3.tex's own "changes since previous version" appendix (~line 2135-2141,
    // unchanged in 4.4/4.5): VCF contig names must match SAM reference-sequence-name rules. These 13 characters
    // are invalid anywhere in an unwrapped CHROM value.
    char[] invalidChars = { '\\', ',', '"', '`', '\'', '(', ')', '{', '}', '[', ']', '<', '>' };
    for (char c : invalidChars) {
      String chrom = "chr" + c + "1";
      assertThrows(VcfFormatException.class, () -> new VcfPosition(chrom, 1, "C", new BigDecimal("0"), -1),
          () -> "CHROM \"" + chrom + "\" containing '" + c + "' should have been rejected");
    }
  }

  @Test
  public void testAsteriskAndEqualsRejectedOnlyAsFirstCharacter() {
    // VCFv4.3.tex's appendix: "* is now valid when not the first character"; "*"/"=" as the first character
    // "were already invalid and remain so"
    assertThrows(VcfFormatException.class, () -> new VcfPosition("*chr1", 1, "C", new BigDecimal("0"), -1));
    assertThrows(VcfFormatException.class, () -> new VcfPosition("=chr1", 1, "C", new BigDecimal("0"), -1));
    new VcfPosition("chr*1", 1, "C", new BigDecimal("0"), -1); // '*' elsewhere is fine
    new VcfPosition("chr1*", 1, "C", new BigDecimal("0"), -1);
    new VcfPosition("chr=1", 1, "C", new BigDecimal("0"), -1); // '=' elsewhere was never restricted
  }

  @Test
  public void testWellFormedAngleBracketWrappedChromosomeAccepted() {
    // VCFv4.2.tex line 179: CHROM's other valid form, "an angle-bracketed ID String (<ID>) pointing to a contig
    // in the assembly file"
    new VcfPosition("<1>", 1, "C", new BigDecimal("0"), -1);
    new VcfPosition("<chr1>", 1, "C", new BigDecimal("0"), -1);
  }

  @Test
  public void testMalformedAngleBracketChromosomeRejected() {
    assertThrows(VcfFormatException.class, () -> new VcfPosition("<1", 1, "C", new BigDecimal("0"), -1));    // unclosed
    assertThrows(VcfFormatException.class, () -> new VcfPosition("1>", 1, "C", new BigDecimal("0"), -1));    // no opening
    assertThrows(VcfFormatException.class, () -> new VcfPosition("<>", 1, "C", new BigDecimal("0"), -1));    // empty content after unwrap
    assertThrows(VcfFormatException.class, () -> new VcfPosition("<1>2>", 1, "C", new BigDecimal("0"), -1)); // malformed nested '>'
  }

  @Test
  public void testShortConstructorValidates() {
    assertThrows(VcfFormatException.class, () -> new VcfPosition("", 1, "C", new BigDecimal("0"), -1));      // empty CHROM
    assertThrows(VcfFormatException.class, () -> new VcfPosition("chr1", -1, "C", new BigDecimal("0"), -1)); // negative POS
    assertThrows(VcfFormatException.class, () -> new VcfPosition("chr1", 1, "X", new BigDecimal("0"), -1));  // invalid REF
  }

  @Test
  public void testValidateCatchesUnvalidatedMutation() {
    VcfPosition position = new VcfPosition("chr1", 1, null, "C", new ArrayList<>(Arrays.asList("A", "T")), null, null,
        null, null, -1);
    position.validate(); // valid as constructed

    // setters and the mutable lists returned by accessors do not validate on their own...
    position.setRef("X");
    // ...but validate() catches the resulting invalid state
    assertThrows(VcfFormatException.class, position::validate);

    position.setRef("C");
    position.validate(); // valid again

    position.getAltBases().add("A"); // duplicate is fine for ALT (only "." exclusivity and pattern are checked)
    position.validate();

    position.getIds().add("rs1");
    position.getIds().add("rs1"); // duplicate ID -- validate() warns (via m_warnings, never null), doesn't
    // throw, matching testValidateAlwaysWarnsRegardlessOfOriginalConstructor's established pattern; see
    // VCF_SPEC_AMBIGUITIES.md's "Duplicate ID/FILTER entries within one record" entry
    position.validate();
  }

  @Test
  public void testValidateRejectsNullEntriesInsteadOfThrowingNpe() {
    // a parsed value can never be null; only a value set directly through a mutable accessor (getIds(), getAltBases(),
    // getFilters(), getFormat(), getInfo()) could be. Each of validate()'s five post-mutation checkers must reject a
    // null element with VcfFormatException, not let it reach a raw NullPointerException deeper in the check.
    VcfPosition ids = newPosition();
    ids.getIds().add(null);
    assertThrows(VcfFormatException.class, ids::validate);

    VcfPosition altBases = newPosition();
    altBases.getAltBases().add(null);
    assertThrows(VcfFormatException.class, altBases::validate);

    VcfPosition filters = newPosition();
    filters.getFilters().add(null);
    assertThrows(VcfFormatException.class, filters::validate);

    VcfPosition format = newPosition();
    format.getFormat().add(null);
    assertThrows(VcfFormatException.class, format::validate);

    VcfPosition infoKey = newPosition();
    infoKey.getInfo().put(null, "value");
    assertThrows(VcfFormatException.class, infoKey::validate);

    VcfPosition infoValue = newPosition();
    infoValue.getInfo().put("KEY", null);
    assertThrows(VcfFormatException.class, infoValue::validate);
  }

  @Test
  public void testConstructorRejectsNullChromosomeInsteadOfThrowingNpe() {
    // chr is typed non-@Nullable, but nothing enforces that at compile time; checkChromosome() must reject a null
    // with VcfFormatException, not let it reach a raw NullPointerException at chr.isEmpty()
    assertThrows(VcfFormatException.class,
        () -> new VcfPosition(null, 1, null, "C", null, null, null, null, null, -1));
  }

  @Test
  public void testConstructorRejectsNullRefInsteadOfThrowingNpe() {
    assertThrows(VcfFormatException.class,
        () -> new VcfPosition("chr", 1, null, null, null, null, null, null, null, -1));
  }

  @Test
  public void testValidateRejectsNullChromosomeInsteadOfThrowingNpe() {
    // setChromosome() does not validate its input, by design (see validate()'s Javadoc); validate() must still
    // reject a null via checkChromosome() with VcfFormatException, not a raw NullPointerException
    VcfPosition position = newPosition();
    position.setChromosome(null);
    assertThrows(VcfFormatException.class, position::validate);
  }

  @Test
  public void testValidateRejectsNullRefInsteadOfThrowingNpe() {
    VcfPosition position = newPosition();
    position.setRef(null);
    assertThrows(VcfFormatException.class, position::validate);
  }

  @Test
  public void testGetInfoAndHasInfoReservedPropertyOverloadsAreNullTolerant() {
    // getInfo(String)/hasInfo(String) are already null-tolerant (a null id is trivially "not present"); the
    // ReservedInfoProperty overloads must match that instead of raw-NPEing at key.getId()
    VcfPosition position = newPosition();
    assertNull(position.getInfo((ReservedInfoProperty) null));
    assertFalse(position.hasInfo((ReservedInfoProperty) null));
  }

  @Test
  public void testInvalidRefBaseThrowsWithSpecificMessage() {
    // VCFv4.2.tex line 182: "REF - reference base(s): Each base must be one of A,C,G,T,N (case insensitive)."
    // hts-specs test/vcf/4.2/failed/failed_body_ref_001.vcf (##CauseOfFailure=REF has a non-valid base,
    // REF="B") confirms this is a structural rejection. No prior test pinned this specific message.
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> new VcfPosition("chr1", 1, null, "B", null, null, null, null, null, -1));
    assertTrue(ex.getMessage().contains("Invalid reference base 'B'"));
  }

  @Test
  public void testValidateAttachesLineNumberToNonInfoCheckFailures() {
    // regression test: validate() previously annotated only its checkInfoEntries(...) call with line-number
    // metadata, leaving its other checks (checkChromosome, checkPosition, checkRef, checkIds, checkAltBases,
    // checkFilters, checkFormat) unannotated. m_lineNumber identifies which line this whole record came from, not
    // which line a specific value came from, so validate() now wraps its entire body in one guarded catch --
    // proving a non-INFO check failure (an invalid REF base, via setRef) also gets line-number attachment.
    VcfPosition position = new VcfPosition("chr1", 1, null, "C", null, null, null, null, null, 42);
    position.validate(); // valid as constructed
    position.setRef("X"); // invalid REF base -- fails checkRef, not checkInfoEntries
    VcfFormatException ex = assertThrows(VcfFormatException.class, position::validate);
    assertEquals(42, ex.getLineNumber());
    assertTrue(ex.getMessage().contains("[Line #42]"));
  }

  @Test
  public void testValidateNormalizesLoneFilterSentinel() {
    VcfPosition dot = new VcfPosition("chr1", 1, null, "C", null, null, null, null, null, -1);
    dot.getFilters().add(".");
    dot.validate();
    assertEquals(VcfPosition.FilterStatus.NONE, dot.getFilterStatus());
    assertTrue(dot.getFilters().isEmpty());

    // a lone "PASS" is left as-is by validate() -- it is not a sentinel to normalize away, it IS the
    // representation of PASSED
    VcfPosition pass = new VcfPosition("chr1", 1, null, "C", null, null, null, null, null, -1);
    pass.getFilters().add("PASS");
    pass.validate();
    assertEquals(VcfPosition.FilterStatus.PASSED, pass.getFilterStatus());
    assertEquals(Collections.singletonList("PASS"), pass.getFilters());

    // validate() is idempotent: calling it again after normalization is a no-op
    dot.validate();
    assertEquals(VcfPosition.FilterStatus.NONE, dot.getFilterStatus());
  }

  @Test
  public void testValidateResetsFilterStatusAfterDotThenPass() {
    // regression test: FilterStatus is derived fresh from getFilters()'s contents every time, so a position that
    // was ever normalized from "." can still report PASSED once its list is explicitly set to ["PASS"] -- there is
    // no separate persisted flag that could get stuck
    VcfPosition position = new VcfPosition("chr1", 1, null, "C", null, null, null, null, null, -1);
    position.getFilters().add(".");
    position.validate();
    assertEquals(VcfPosition.FilterStatus.NONE, position.getFilterStatus());

    position.getFilters().add("PASS");
    position.validate();
    assertEquals(VcfPosition.FilterStatus.PASSED, position.getFilterStatus());
  }

  @Test
  public void testClearingFiltersFromNoneStaysNoneNotPassed() {
    // regression test: clearing FILTER always produces NONE, regardless of what state the position was in before
    // -- FilterStatus is derived fresh from getFilters()'s contents, not a separate persisted flag. Verified this
    // matches bcftools 1.23.1's own convention: "bcftools annotate -x FILTER" on a fixture with FILTER values ".",
    // "PASS", and a real filter code converts ALL of them to "." (never to "PASS").
    VcfPosition position = new VcfPosition("chr1", 1, null, "C", null, null, null, null, null, -1);
    position.getFilters().add(".");
    position.validate();
    assertEquals(VcfPosition.FilterStatus.NONE, position.getFilterStatus());

    position.getFilters().clear(); // already empty; a genuine no-op on the list itself
    position.validate();
    assertEquals(VcfPosition.FilterStatus.NONE, position.getFilterStatus());

    // the only way to reach PASSED from here is the documented "PASS" sentinel, not merely clearing
    position.getFilters().add("PASS");
    position.validate();
    assertEquals(VcfPosition.FilterStatus.PASSED, position.getFilterStatus());
  }

  @Test
  public void testClearingFiltersFromFailedProducesNoneNotPassed() {
    // regression test: clearing a position that actually FAILED a filter (not one that started as NONE)
    // previously left a stale "true" default behind and
    // reported PASSED, which contradicts bcftools 1.23.1's "annotate -x FILTER" convention (a real filter code
    // becomes "." when cleared, never "PASS"). Verified live against the compiled library before this fix:
    // constructing with FILTER=q10 then clearing+validating produced PASSED, not NONE.
    VcfPosition position = new VcfPosition("chr1", 1, null, "C", null, null,
        new ArrayList<>(Collections.singletonList("q10")), null, null, -1);
    assertEquals(VcfPosition.FilterStatus.FAILED, position.getFilterStatus());

    position.getFilters().clear();
    position.validate();
    assertEquals(VcfPosition.FilterStatus.NONE, position.getFilterStatus());
  }

  @Test
  public void testPassFilterIsIndependentlyMutableRegardlessOfInputListMutability() {
    // regression test: a lone "PASS" must be defensively copied into a fresh, mutable list, exactly like "." --
    // otherwise passing an immutable list (e.g. List.of("PASS")) makes getFilters() permanently immutable,
    // breaking the documented "clear() to change FilterStatus" pattern this same redesign introduced
    VcfPosition position = new VcfPosition("chr1", 1, null, "C", null, null, List.of("PASS"), null, null, -1);
    assertEquals(VcfPosition.FilterStatus.PASSED, position.getFilterStatus());
    position.getFilters().clear(); // must not throw UnsupportedOperationException
    position.validate();
    assertEquals(VcfPosition.FilterStatus.NONE, position.getFilterStatus());
  }

  @Test
  public void testTelomericPositions() {
    new VcfPosition("chr1", 0, null, "C", null, null, null, null, null, -1); // telomere at start is valid
    assertThrows(VcfFormatException.class, () -> // a negative POS is invalid
        new VcfPosition("chr1", -1, null, "C", null, null, null, null, null, -1));
  }

  @Test
  public void testGtMustBeFirstFormat() {
    new VcfPosition("chr1", 1, null, "C", null, null, null, null, Arrays.asList("GT", "DP"), -1); // valid
    assertThrows(VcfFormatException.class, () -> // GT not first is invalid
        new VcfPosition("chr1", 1, null, "C", null, null, null, null, Arrays.asList("DP", "GT"), -1));
  }

  @Test
  public void testEmptyChromosome() {
    assertThrows(VcfFormatException.class, () ->
        new VcfPosition("", 1, null, "C", null, null, null, null, null, -1));
  }

  @Test
  public void testDuplicateIdRejected() {
    new VcfPosition("chr1", 1, Arrays.asList("rs1", "rs2"), "C", null, null, null, null, null, -1); // unique IDs are fine
    assertThrows(VcfFormatException.class, () ->
        new VcfPosition("chr1", 1, Arrays.asList("rs1", "rs1"), "C", null, null, null, null, null, -1));
  }

  @Test
  public void testBadId() {
    assertThrows(VcfFormatException.class, () -> {
      new VcfPosition("chr1", 1, Collections.singletonList(";"), "C", null, null, null, null, null, -1);
    });
  }

  @Test
  public void testBadRefBase() {
    assertThrows(VcfFormatException.class, () -> {
      new VcfPosition("chr1", 1, null, "X", null, null, null, null, null, -1);
    });
  }

  @Test
  public void testBadVarBase() {
    assertThrows(VcfFormatException.class, () -> {
      new VcfPosition("chr1", 1, null, "C", Collections.singletonList("X"), null, null, null, null, -1);
    });
  }

  @Test
  public void testAltMissingValueCombinedWithAlleleAccepted() {
    // VCFv4.2.tex line 183 only states that '.' replaces the ENTIRE ALT field "if there are no alternative
    // alleles"; it is silent on '.' as one entry among several. VCFv4.3.tex line 350 lists '.' (no variant) as one
    // option among several for the same comma-separated list; VCFv4.4.tex line 367 / VCFv4.5.tex line 416 say this
    // more explicitly: "Each allele in this list must be one of: ... the MISSING value '.' (no variant); ...".
    // Confirmed bcftools 1.23.1 accepts this with no warning, whether the '.' is literal or substituted, and
    // genotype indices before/at/after the dot still resolve correctly.
    new VcfPosition("chr1", 1, null, "C", Collections.singletonList("."), null, null, null, null, -1); // "." alone is fine
    VcfPosition afterReal = new VcfPosition("chr1", 1, null, "C", new ArrayList<>(Arrays.asList("A", ".")), null,
        null, null, null, -1);
    assertEquals(Arrays.asList("A", "."), afterReal.getAltBases());
    VcfPosition beforeReal = new VcfPosition("chr1", 1, null, "C", new ArrayList<>(Arrays.asList(".", "A")), null,
        null, null, null, -1);
    assertEquals(Arrays.asList(".", "A"), beforeReal.getAltBases());
  }

  @Test
  public void testZeroFilter() {
    assertThrows(VcfFormatException.class, () -> {
      new VcfPosition("chr1", 1, null, "C", null, null, Collections.singletonList("0"), null, null, -1);
    });
  }

  @Test
  public void testFilterWithWhitespace() {
    assertThrows(VcfFormatException.class, () -> {
      new VcfPosition("chr1", 1, null, "C", null, null, Collections.singletonList("adsf\nsdf"), null, null, -1);
    });
  }

  @Test
  public void testFilterWithMultipleNewlinesRejected() {
    // a wrapping ".*\s.*" pattern matched with matches() would fail to detect whitespace here (2+ line terminators);
    // the whitespace check must still catch it
    assertThrows(VcfFormatException.class, () -> {
      new VcfPosition("chr1", 1, null, "C", null, null, Collections.singletonList("a\nb\nc"), null, null, -1);
    });
  }

  @Test
  public void testInfoWithWhitespace() {
    assertThrows(VcfFormatException.class, () -> {
      ListMultimap<String, String> map = ArrayListMultimap.create();
      map.put("anid", "a\nvalue");
      new VcfPosition("chr1", 1, null, "C", null, null, null, map, null, -1);
    });
  }

  @Test
  public void testBadFormat() {
    assertThrows(VcfFormatException.class, () -> {
      new VcfPosition("chr1", 1, null, "C", null, null, null, null, Collections.singletonList("+"), -1);
    });
  }

  @Test
  public void testInfoValueWithStructuralCharactersRejected() {
    // a raw ";" or "," in an INFO value (or ";"/"=" in a key) would corrupt round-trip parsing (a parsed value can
    // never contain them, since they were already split on to arrive at the value); reject them, including via a
    // direct mutation through getInfo(), not just at construction
    VcfPosition semicolonInValue = newPosition();
    semicolonInValue.getInfo().put("KEY", "a;b");
    assertThrows(VcfFormatException.class, semicolonInValue::validate);

    VcfPosition commaInValue = newPosition();
    commaInValue.getInfo().put("KEY", "a,b");
    assertThrows(VcfFormatException.class, commaInValue::validate);

    VcfPosition semicolonInKey = newPosition();
    semicolonInKey.getInfo().put("KEY;X", "value");
    assertThrows(VcfFormatException.class, semicolonInKey::validate);

    VcfPosition equalsInKey = newPosition();
    equalsInKey.getInfo().put("KEY=X", "value");
    assertThrows(VcfFormatException.class, equalsInKey::validate);
  }

  @Test
  public void testCheckAltBasesDoesNotMutateCallersImmutableList() {
    // fillEmptyEntriesWithDot mutates its argument in place; checkAltBases must defensively copy first so an
    // immutable or caller-owned list passed to the constructor is never mutated or rejected with
    // UnsupportedOperationException. Uses the lenient 11-arg constructor overload -- the bare 10-arg constructor
    // is now strict and throws for an empty entry instead of substituting it.
    List<String> immutable = List.of("T", "");
    VcfPosition position = new VcfPosition("chr1", 1, null, "C", immutable, null, null, null, null, -1,
        VcfWarnings.DEFAULT);
    assertEquals(Arrays.asList("T", "."), position.getAltBases());
    assertEquals(Arrays.asList("T", ""), immutable);
  }

  @Test
  public void testCheckAltBasesDoesNotCopyWhenNoEmptyEntry() {
    // optimization: when altBases has no empty entry to normalize, checkAltBases must NOT copy it -- the
    // returned list's mutability then depends on what was passed in, exactly like getIds()/getFilters()/
    // getFormat()/getInfo() already do (see the primary constructor's Javadoc). An immutable list with no empty
    // entry therefore stays immutable, and add() on the result throws.
    List<String> immutable = List.of("T", "C");
    VcfPosition position = new VcfPosition("chr1", 1, null, "C", immutable, null, null, null, null, -1);
    assertEquals(Arrays.asList("T", "C"), position.getAltBases());
    assertThrows(UnsupportedOperationException.class, () -> position.getAltBases().add("G"));
  }

  @Test
  public void testStrictConstructorThrowsForPassCombinedWithOtherFilters() {
    VcfFormatException ex = assertThrows(VcfFormatException.class, () -> new VcfPosition(
        "1", 100, null, "A", null, null, List.of("PASS", "q10"), null, null, -1));
    assertTrue(ex.getMessage().contains("PASS along with other filters"));
  }

  @Test
  public void testStrictConstructorThrowsForWhitespaceInFilter() {
    // regression test: no prior coverage existed for this unconditional throw (reachable regardless of strict/
    // lenient mode) -- hts-specs test/vcf/4.2/failed/failed_body_filter_003.vcf, ##CauseOfFailure=FILTER
    // contains whitespaces (literal FILTER="my filter")
    VcfFormatException ex = assertThrows(VcfFormatException.class, () -> new VcfPosition(
        "1", 100, null, "A", null, null, List.of("my filter"), null, null, -1));
    assertTrue(ex.getMessage().contains("contains whitespace"));
  }

  @Test
  public void testStrictConstructorThrowsForWhitespaceInInfo() {
    // regression test: no prior coverage existed for this unconditional throw (reachable regardless of strict/
    // lenient mode) -- hts-specs test/vcf/4.2/failed/failed_body_info_028.vcf, ##CauseOfFailure=INFO contains a
    // whitespace (literal INFO="RS Q=123")
    com.google.common.collect.ListMultimap<String, String> info = com.google.common.collect.LinkedListMultimap.create();
    info.put("RS Q", "123");
    VcfFormatException ex = assertThrows(VcfFormatException.class, () -> new VcfPosition(
        "1", 100, null, "A", null, null, null, info, null, -1));
    assertTrue(ex.getMessage().contains("contains whitespace"));
  }

  @Test
  public void testStrictConstructorThrowsForInfoValueContainingEquals() {
    com.google.common.collect.ListMultimap<String, String> info = com.google.common.collect.LinkedListMultimap.create();
    info.put("DP", "1=2");
    VcfFormatException ex = assertThrows(VcfFormatException.class, () -> new VcfPosition(
        "1", 100, null, "A", null, null, null, info, null, -1));
    assertTrue(ex.getMessage().contains("contains '='"));
  }

  @Test
  public void testStrictConstructorThrowsForInfoDotCombinedWithOtherProperties() {
    // regression test: the eager (ListMultimap) constructor previously bypassed this check entirely --
    // testLazyInfoDotCannotCombineWithOtherProperties above already covers the lazy raw-text path
    com.google.common.collect.ListMultimap<String, String> info = com.google.common.collect.LinkedListMultimap.create();
    info.put(".", "");
    info.put("DP", "5");
    VcfFormatException ex = assertThrows(VcfFormatException.class, () -> new VcfPosition(
        "1", 100, null, "A", null, null, null, info, null, -1));
    assertTrue(ex.getMessage().contains("'.' (missing value) along with other properties"));
  }

  @Test
  public void testStrictConstructorAcceptsInfoKeyLiterallyNamedDotWithARealValue() {
    // regression test: a caught-too-broadly first draft of the "'.' combined with other properties" check
    // (checkInfoEntries, keyed off info.size() > 1) newly rejected this, which the lazy raw-text path
    // (equivalent to raw text ".=5;DP=1") has always accepted -- its own props.contains(".") only matches a
    // bare "." token, never ".=value", so a "." key with a real, non-empty value is a merely-unusual literal
    // key name, not the "no INFO at all" sentinel, and must not be conflated with it
    com.google.common.collect.ListMultimap<String, String> info = com.google.common.collect.LinkedListMultimap.create();
    info.put(".", "5");
    info.put("DP", "1");
    VcfPosition position = new VcfPosition("1", 100, null, "A", null, null, null, info, null, -1);
    assertEquals(Collections.singletonList("5"), position.getInfo("."));
    assertEquals(Collections.singletonList("1"), position.getInfo("DP"));
  }

  @Test
  public void testStrictConstructorAcceptsInfoDotKeyWithMultipleValuesAndNoOtherProperty() {
    // regression test: a caught-too-broadly first draft of the "'.' combined with other properties" check used
    // info.size() (which counts key-VALUE pairs, not distinct keys), so a single "." key with more than one
    // comma-derived value (raw text ".=1,2") false-positived as "combined with other properties" even though no
    // other property is present at all
    com.google.common.collect.ListMultimap<String, String> info = com.google.common.collect.LinkedListMultimap.create();
    info.put(".", "1");
    info.put(".", "2");
    VcfPosition position = new VcfPosition("1", 100, null, "A", null, null, null, info, null, -1);
    assertEquals(Arrays.asList("1", "2"), position.getInfo("."));
  }

  @Test
  public void testStrictConstructorThrowsFormattedExceptionNotNpeForNullInfoValueUnderDotKey() {
    // regression test: an earlier draft of checkInfoEntries computed the "." exclusivity check BEFORE the
    // per-entry null-value check, so a null value under key "." alone raw-crashed with NullPointerException
    // (from dotValues.stream().anyMatch(v -> !v.isEmpty())) instead of the intended VcfFormatException -- the
    // "." check now runs after the loop, over the already-null-checked result, so the loop's own null-value
    // check fires first
    com.google.common.collect.ListMultimap<String, String> info = com.google.common.collect.LinkedListMultimap.create();
    info.put(".", null);
    VcfFormatException ex = assertThrows(VcfFormatException.class, () -> new VcfPosition(
        "1", 100, null, "A", null, null, null, info, null, -1));
    assertTrue(ex.getMessage().contains("INFO value for key \".\" is null"));
  }

  @Test
  public void testLenientConstructorAcceptsInfoDotSentinelAfterEmptyKeyIsDropped() {
    // regression test: an earlier draft's "." exclusivity check ran BEFORE the loop dropped the empty-key
    // entry, so {"": "5", ".": ""} wrongly hard-threw in lenient mode ("combined with other properties") even
    // though the only OTHER entry present is the empty-key one lenient mode is supposed to warn-and-drop --
    // once dropped, only "." remains, which must be accepted, not rejected
    com.google.common.collect.ListMultimap<String, String> info = com.google.common.collect.LinkedListMultimap.create();
    info.put("", "5");
    info.put(".", "");
    StringWriter sink = new StringWriter();
    VcfPosition position = new VcfPosition("1", 100, null, "A", null, null, null, info, null, -1,
        new VcfWarnings(sink));
    assertTrue(sink.toString().contains("has an empty key"));
    // the eager constructor does not drop a lone "." key the way the lazy raw-text path does (a documented,
    // separately-tracked residual asymmetry, not part of this fix's scope) -- the point of this test is only
    // that the empty-key entry's drop must happen BEFORE the "." exclusivity check runs, not that this
    // asymmetry itself is resolved
    assertEquals(Collections.singletonList(""), position.getInfo("."));
  }

  @Test
  public void testLenientConstructorDropsInfoEmptyKeyWithWarning() {
    // regression test: the eager (ListMultimap) constructor and validate() previously bypassed this check
    // entirely -- info()'s own raw-text-parsing branch (VcfParser's path) already drops an empty INFO key
    // with a warning; a caller building a ListMultimap directly got neither the drop nor the warning
    com.google.common.collect.ListMultimap<String, String> info = com.google.common.collect.LinkedListMultimap.create();
    info.put("", "5");
    info.put("DP", "10");
    StringWriter sink = new StringWriter();
    VcfPosition position = new VcfPosition("1", 100, null, "A", null, null, null, info, null, -1,
        new VcfWarnings(sink));
    assertEquals(Collections.singletonList("10"), position.getInfo("DP"));
    assertNull(position.getInfo(""));
    assertTrue(sink.toString().contains("has an empty key"));

    // re-validating an already-constructed position (e.g. after a live mutation via getInfo()) must catch the
    // same defect, not just the constructor
    VcfPosition mutated = newPosition();
    mutated.getInfo().put("", "1");
    mutated.getInfo().put("AF", "0.5");
    mutated.validate();
    assertEquals(Collections.singletonList("0.5"), mutated.getInfo("AF"));
    assertNull(mutated.getInfo(""));
  }

  @Test
  public void testStrictConstructorThrowsForInfoEmptyKey() {
    com.google.common.collect.ListMultimap<String, String> info = com.google.common.collect.LinkedListMultimap.create();
    info.put("", "5");
    VcfFormatException ex = assertThrows(VcfFormatException.class, () -> new VcfPosition(
        "1", 100, null, "A", null, null, null, info, null, -1));
    assertTrue(ex.getMessage().contains("has an empty key"));
  }

  @Test
  public void testStrictConstructorThrowsForEmptyFormatSubfield() {
    List<String> format = new java.util.ArrayList<>(List.of("GT", "", "DP"));
    VcfFormatException ex = assertThrows(VcfFormatException.class, () -> new VcfPosition(
        "1", 100, null, "A", null, null, null, null, format, -1));
    assertTrue(ex.getMessage().contains("empty sub-field name"));
  }

  @Test
  public void testStrictConstructorThrowsForEmptyIdEntry() {
    List<String> ids = new java.util.ArrayList<>(List.of("rs1", ""));
    VcfFormatException ex = assertThrows(VcfFormatException.class, () -> new VcfPosition(
        "1", 100, ids, "A", null, null, null, null, null, -1));
    assertTrue(ex.getMessage().contains("empty entry"));
  }

  @Test
  public void testLenientConstructorRejectsNullWarnings() {
    assertThrows(NullPointerException.class, () -> new VcfPosition(
        "1", 100, null, "A", null, null, List.of("PASS", "q10"), null, null, -1, null));
  }

  @Test
  public void testLenientConstructorWarnsInsteadOfThrowingForPassCombinedWithOtherFilters() {
    StringWriter sink = new StringWriter();
    VcfPosition position = new VcfPosition(
        "1", 100, null, "A", null, null, List.of("PASS", "q10"), null, null, -1, new VcfWarnings(sink));
    assertEquals(List.of("PASS", "q10"), position.getFilters());
    assertTrue(sink.toString().contains("PASS along with other filters"));
  }

  @Test
  public void testLenientConstructorWarnsInsteadOfThrowingForDuplicateId() {
    StringWriter sink = new StringWriter();
    VcfPosition position = new VcfPosition(
        "1", 100, List.of("rs1", "rs1"), "A", null, null, null, null, null, -1, new VcfWarnings(sink));
    assertEquals(List.of("rs1", "rs1"), position.getIds());
    assertTrue(sink.toString().contains("Duplicate ID"));
  }

  @Test
  public void testLenientConstructorWarnsForNonAdjacentDuplicateId() {
    // regression test: isDuplicateAt (an allocation-free scan added to replace a per-call HashSet<String>)
    // must scan ALL earlier elements, not just the immediately preceding one -- "rs1" and "rs2" are not
    // adjacent to their duplicate, so a scan that only compared consecutive elements would miss this
    StringWriter sink = new StringWriter();
    VcfPosition position = new VcfPosition(
        "1", 100, List.of("rs1", "rs2", "rs1"), "A", null, null, null, null, null, -1, new VcfWarnings(sink));
    assertEquals(List.of("rs1", "rs2", "rs1"), position.getIds());
    assertTrue(sink.toString().contains("Duplicate ID \"rs1\""));
  }

  @Test
  public void testLenientConstructorHandlesManyIdsWithoutQuadraticSlowdown() {
    // regression test: isDuplicateAt's allocation-free scan is O(n) per call (O(n^2) overall) -- fine for a
    // typically-tiny ID list, but ID/FILTER/FORMAT are delimiter-separated lists inside a single column whose
    // element count is bounded only by line length, i.e. attacker-controlled and unbounded, the same DoS shape
    // as the already-fixed ##PEDIGREE O(n^2) issue in VcfMetadata.hasPedigreeIdCollision. An earlier version of
    // this fix wrongly assumed this list's length was bounded by column structure; a real measurement showed a
    // 100,000-ID single record going from 91 ms (HashSet) to 25.4 SECONDS (pure scan) -- DUPLICATE_SCAN_THRESHOLD
    // exists to keep this bounded by falling back to a HashSet once the list is no longer small. 3 seconds is a
    // wide margin above the fixed behavior's near-instant completion at this size.
    List<String> ids = new java.util.ArrayList<>(50_000);
    for (int i = 0; i < 50_000; i++) {
      ids.add("rs" + i);
    }
    assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () -> {
      StringWriter sink = new StringWriter();
      VcfPosition position = new VcfPosition(
          "1", 100, ids, "A", null, null, null, null, null, -1, new VcfWarnings(sink));
      assertEquals(50_000, position.getIds().size());
      assertEquals("", sink.toString());
    });
  }

  @Test
  public void testStrictConstructorThrowsForDuplicateFilterCode() {
    VcfFormatException ex = assertThrows(VcfFormatException.class, () -> new VcfPosition(
        "1", 100, null, "A", null, null, List.of("hp23", "hp23"), null, null, -1));
    assertTrue(ex.getMessage().contains("Duplicate FILTER code"));
  }

  @Test
  public void testConstructorThrowsForNonAdjacentDuplicateFormatKey() {
    // regression test for checkFormat's own isDuplicateAt-based duplicate check (no prior test existed for a
    // duplicate FORMAT key at all) -- "DP" and "DP" are not adjacent, proving the scan checks all earlier
    // elements, not just the immediately preceding one
    VcfFormatException ex = assertThrows(VcfFormatException.class, () -> new VcfPosition(
        "1", 100, null, "A", null, null, null, null, List.of("DP", "AD", "DP"), -1));
    assertTrue(ex.getMessage().contains("Duplicate FORMAT key \"DP\""));
  }

  @Test
  public void testNewWithTrustedFormatSkipsFormatValidationThatThePublicConstructorEnforces() {
    // regression/contract test: newWithTrustedFormat() is VcfParser's own per-record FORMAT-cache fast path --
    // it must skip checkFormat's validation entirely, unlike the public constructor, which must still reject
    // the identical malformed list ("DP" duplicated). This pins the actual difference in behavior, not just
    // that newWithTrustedFormat() doesn't crash.
    List<String> duplicateFormat = List.of("DP", "AD", "DP");
    assertThrows(VcfFormatException.class, () -> new VcfPosition(
        "1", 100, null, "A", null, null, null, null, duplicateFormat, -1));
    VcfPosition trusted = VcfPosition.newWithTrustedFormat(
        "1", 100, null, "A", null, null, null, null, duplicateFormat, -1, VcfWarnings.DEFAULT);
    assertEquals(duplicateFormat, trusted.getFormat());
  }

  @Test
  public void testNewWithTrustedFormatProducesEquivalentPositionToPublicConstructorForWellFormedInput() {
    // for input the public constructor would accept anyway (VcfParser's actual, already-validated cache
    // content), newWithTrustedFormat() must produce an observably identical FORMAT list
    List<String> format = List.of("GT", "DP", "AD");
    VcfPosition trusted = VcfPosition.newWithTrustedFormat(
        "1", 100, null, "A", null, null, null, null, format, -1, VcfWarnings.DEFAULT);
    VcfPosition untrusted = new VcfPosition(
        "1", 100, null, "A", null, null, null, null, format, -1, VcfWarnings.DEFAULT);
    assertEquals(untrusted.getFormat(), trusted.getFormat());
  }

  @Test
  public void testLenientConstructorWarnsInsteadOfThrowingForDuplicateFilterCode() {
    StringWriter sink = new StringWriter();
    VcfPosition position = new VcfPosition(
        "1", 100, null, "A", null, null, List.of("hp23", "hp23"), null, null, -1, new VcfWarnings(sink));
    assertEquals(List.of("hp23", "hp23"), position.getFilters());
    assertTrue(sink.toString().contains("Duplicate FILTER code"));
  }

  @Test
  public void testValidateAlwaysWarnsRegardlessOfOriginalConstructor() {
    // built via the STRICT constructor with a valid FILTER list, then mutated to the ambiguous case, then
    // validate()'d -- must warn (via whatever m_warnings currently is), never throw, since validate() is
    // re-validation, not construction
    VcfPosition position = new VcfPosition("1", 100, null, "A", null, null,
        new java.util.ArrayList<>(List.of("q10")), null, null, -1);
    StringWriter sink = new StringWriter();
    position.setWarningsSink(new VcfWarnings(sink));
    position.getFilters().add(0, "PASS");
    assertDoesNotThrow(position::validate);
    assertTrue(sink.toString().contains("PASS along with other filters"));
  }

  @Test
  public void testGetQualityWarnsToConfiguredSinkForNonNumericValue() {
    VcfPosition position = new VcfPosition("1", 100, "A", null, -1);
    position.setRawQuality("not-a-number");
    StringWriter sink = new StringWriter();
    position.setWarningsSink(new VcfWarnings(sink));
    assertNull(position.getQuality());
    assertTrue(sink.toString().contains("is not a number"));
  }

}
