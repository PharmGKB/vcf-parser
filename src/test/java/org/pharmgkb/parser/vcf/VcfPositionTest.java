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

import static org.pharmgkb.parser.vcf.TestUtils.STRICT_CONTEXT;
import static org.pharmgkb.parser.vcf.TestUtils.LENIENT_CONTEXT;
import static org.pharmgkb.parser.vcf.TestUtils.lenientContext;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link VcfPosition}.
 * @author Douglas Myers-Turnbull
 */
public class VcfPositionTest {

  @Test
  public void testLazyQuality() {
    VcfPosition value = newPosition().toBuilder().rawQuality("29.5").build();
    assertEquals(VcfFloat.of(new BigDecimal("29.5")), value.getQuality());
    assertEquals(VcfFloat.of(new BigDecimal("29.5")), value.getQuality()); // stable across repeated reads

    VcfPosition missing = newPosition().toBuilder().rawQuality(".").build();
    assertNull(missing.getQuality());

    VcfPosition empty = newPosition().toBuilder().rawQuality("").build();
    assertNull(empty.getQuality());

    // an invalid QUAL cannot be represented as a number at all, so getQuality() throws unconditionally (matching
    // InternalUtils.convertElement's identical decision for INFO/FORMAT reserved keys) -- but the original text
    // remains available via getRawQuality() either way, both before and after the throw
    VcfPosition bad = newPosition().toBuilder().rawQuality("not-a-number").build();
    assertEquals("not-a-number", bad.getRawQuality());
    assertThrows(VcfFormatException.class, bad::getQuality);
    assertEquals("not-a-number", bad.getRawQuality());
    // every call re-throws (not just the first) -- see getQuality()'s own Javadoc
    assertThrows(VcfFormatException.class, bad::getQuality);

    // Builder#quality overrides any pending raw value
    VcfPosition override = newPosition().toBuilder().rawQuality("1").quality(VcfFloat.of(new BigDecimal("2"))).build();
    assertEquals(VcfFloat.of(new BigDecimal("2")), override.getQuality());
    // no raw text ever existed for this position -- it was constructed directly from a VcfFloat
    assertNull(override.getRawQuality());
  }

  @Test
  public void testGetRawQualityIsPreservedAfterSuccessfulParseAndUnderLenientContextToo() {
    // full symmetry with INFO's own permanently-available raw per-key values: getRawQuality() keeps returning
    // the original wire text even after getQuality() has already parsed it successfully -- not just for the
    // malformed case above
    VcfPosition value = newPosition().toBuilder().rawQuality("29.5").build();
    assertEquals(VcfFloat.of(new BigDecimal("29.5")), value.getQuality());
    assertEquals("29.5", value.getRawQuality());

    // getQuality()'s unconditional throw is not gated by isLenient() -- VcfParser itself stays lenient
    // regardless (it never calls getQuality() during parse()), but a lenient caller who does call it gets the
    // same throw a strict caller does, since there is no sane recoverable value either way
    VcfPosition lenientBad = lenientPosition().toBuilder().rawQuality("not-a-number").build();
    assertThrows(VcfFormatException.class, lenientBad::getQuality);
    assertEquals("not-a-number", lenientBad.getRawQuality());
  }

  @Test
  public void testGetQualitySucceedsForSpecialFloatValues() {
    // VCFv4.3.tex line 100: "NAN, INF, INFINITY" (case-insensitive, optionally signed) are valid Float
    // literals -- QUAL is VCF-spec-typed Float, so getQuality() must accept them the same way every other
    // Float-typed reserved key already does via InternalUtils.convertElement's VcfFloat.parse(value,
    // warnings) call
    VcfPosition inf = newPosition().toBuilder().rawQuality("Inf").build();
    assertEquals(VcfFloat.ofSpecial(VcfFloat.Special.POSITIVE_INFINITY), inf.getQuality());
    assertEquals("Inf", inf.getRawQuality());

    VcfPosition plusInf = newPosition().toBuilder().rawQuality("+Inf").build();
    assertEquals(VcfFloat.ofSpecial(VcfFloat.Special.POSITIVE_INFINITY), plusInf.getQuality());

    VcfPosition negInf = newPosition().toBuilder().rawQuality("-INFINITY").build();
    assertEquals(VcfFloat.ofSpecial(VcfFloat.Special.NEGATIVE_INFINITY), negInf.getQuality());

    VcfPosition nan = newPosition().toBuilder().rawQuality("nan").build();
    assertEquals(VcfFloat.ofSpecial(VcfFloat.Special.NAN), nan.getQuality());
  }

  @Test
  public void testBuilderConstructedSpecialQualityHasNoRawTextAndReturnsUnchanged() {
    // a caller can now construct a special-value QUAL directly, with no wire text ever existing for it --
    // getQuality() returns exactly what was set, and getRawQuality() stays null (mirrors the existing
    // BigDecimal-equivalent case pinned by testLazyQuality's "Builder#quality overrides any pending raw
    // value" assertion)
    VcfPosition position = newPosition().toBuilder()
        .quality(VcfFloat.ofSpecial(VcfFloat.Special.POSITIVE_INFINITY))
        .build();
    assertEquals(VcfFloat.ofSpecial(VcfFloat.Special.POSITIVE_INFINITY), position.getQuality());
    assertNull(position.getRawQuality());
  }

  @Test
  public void testGetQualityDoesNotHangOnPathologicallyLongAdversarialValue() {
    // regression test: getQuality() ultimately calls VcfFloat.parseDecimal, which used to call
    // `new BigDecimal(value)` with no length bound -- a CPU-exhaustion vector via an attacker-controlled QUAL
    // value. An oversized value is now rejected before the expensive parse is attempted, treated the same as
    // any other unparseable QUAL (throws, rather than hanging). 3 seconds is a wide margin above the fixed
    // behavior's near-instant rejection at this size.
    VcfPosition pathological = newPosition().toBuilder().rawQuality("1".repeat(500_000)).build();
    assertTimeoutPreemptively(java.time.Duration.ofSeconds(3),
        () -> assertThrows(VcfFormatException.class, pathological::getQuality));
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
      VcfPosition value = newPosition().toBuilder().rawQuality("5.").build();
      assertEquals(VcfFloat.of(new BigDecimal("5")), value.getQuality());
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
      VcfPosition value = newPosition().toBuilder().rawQuality("29.5").build();
      value.getQuality();
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testLazyInfo() {
    VcfPosition p = newPosition().toBuilder().rawInfo("DP=35;AF=0.5,0.25;FLAG").build();
    assertEquals(Collections.singletonList("35"), p.getInfo("DP"));
    assertEquals(Arrays.asList("0.5", "0.25"), p.getInfo("AF"));
    assertTrue(p.hasInfo("FLAG"));
    assertEquals(Collections.singletonList(""), p.getInfo("FLAG"));
    assertNull(p.getInfo("MISSING"));
    assertFalse(p.hasInfo("MISSING"));

    VcfPosition dot = newPosition().toBuilder().rawInfo(".").build();
    assertTrue(dot.getInfo().isEmpty());
    assertFalse(dot.hasInfo("X"));
  }

  @Test
  public void testLazyInfoWithEmptyEntries() {
    // VCF does not allow zero-length fields; these warn and normalize rather than throw or silently drop-then-hide
    // the problem (see EMPTY_FIELD_HANDLING.md). DP=10;;AF=0.5 -> the empty prop between ';'s is dropped entirely.
    VcfPosition emptyProp = newPosition().toBuilder().rawInfo("DP=10;;AF=0.5").build();
    assertEquals(Collections.singletonList("10"), emptyProp.getInfo("DP"));
    assertEquals(Collections.singletonList("0.5"), emptyProp.getInfo("AF"));
    assertEquals(2, emptyProp.getInfo().size());

    // a trailing ';' is the same as an interior empty prop
    VcfPosition trailingProp = newPosition().toBuilder().rawInfo("DP=10;").build();
    assertEquals(Collections.singletonList("10"), trailingProp.getInfo("DP"));
    assertEquals(1, trailingProp.getInfo().size());

    // AD=1,,2 -> the empty value between ','s is treated as the missing value '.'
    VcfPosition emptyValue = newPosition().toBuilder().rawInfo("AD=1,,2").build();
    assertEquals(Arrays.asList("1", ".", "2"), emptyValue.getInfo("AD"));

    // a trailing ',' is the same as an interior empty value
    VcfPosition trailingValue = newPosition().toBuilder().rawInfo("AD=1,2,").build();
    assertEquals(Arrays.asList("1", "2", "."), trailingValue.getInfo("AD"));

    // AD= (no comma at all) -> a single missing value, not a flag (which is stored the same way internally, but only
    // when there is no '=' sign at all)
    VcfPosition wholeEmpty = newPosition().toBuilder().rawInfo("AD=").build();
    assertEquals(Collections.singletonList("."), wholeEmpty.getInfo("AD"));

    // a flag (no '=' sign) is unaffected: its sentinel empty value must not be confused with a real empty field
    VcfPosition flag = newPosition().toBuilder().rawInfo("DB;DP=10").build();
    assertTrue(flag.hasInfo("DB"));
    assertEquals(Collections.singletonList(""), flag.getInfo("DB"));
    assertEquals(Collections.singletonList("10"), flag.getInfo("DP"));
  }

  @Test
  public void testLazyInfoDotCannotCombineWithOtherProperties() {
    // "." (no INFO at all) cannot be combined with a real property, the same as ALT/FILTER's exclusivity rule for
    // their own "." sentinel
    VcfPosition combined = newPosition().toBuilder().rawInfo(".;DP=1").build();
    assertThrows(VcfFormatException.class, combined::getInfo);

    // a stray trailing ';' after a lone "." is not "combined with other content" (the only other split part is an
    // empty-string artifact, already warned about and dropped elsewhere) -- must not throw, and the leftover "."
    // is still the "no INFO at all" sentinel, not a literal property named "."
    VcfPosition trailingSemicolon = newPosition().toBuilder().rawInfo(".;").build();
    assertTrue(trailingSemicolon.getInfo().isEmpty());
  }

  @Test
  public void testLazyInfoExceptionHasNoLineNumberWhenNotConstructedByParser() {
    // regression test: a VcfPosition NOT produced by parsing a real file (lineNumber=-1, the newPosition() helper's
    // default) must keep today's existing bare-message exception shape -- proving this feature is purely additive
    // for every non-parser-constructed VcfPosition, existing or future
    VcfPosition combined = newPosition().toBuilder().rawInfo(".;DP=1").build();
    VcfFormatException ex = assertThrows(VcfFormatException.class, combined::getInfo);
    assertEquals(-1, ex.getLineNumber());
    assertFalse(ex.getMessage().contains("[Line #"));
  }

  @Test
  public void testLazyInfoValueContainingEqualsSignWarnsAndPreserves() {
    // VCFv4.2's INFO grammar note ("no whitespace, semicolons, or equals-signs permitted") technically disallows
    // this, but bcftools 1.23.1 accepts it silently (verified live: "DP=1=2" round-trips as-is, splitting only on
    // the first '='); this parser matches that tolerance instead of rejecting it
    VcfPosition position = newPosition().toBuilder().rawInfo("DP=1=2").build();
    assertEquals(Collections.singletonList("1=2"), position.getInfo("DP"));
  }

  @Test
  public void testLazyInfoDuplicateKeyKeepsOnlyFirstOccurrence() {
    // VCFv4.2 is silent on duplicate top-level INFO keys, but VCFv4.3 explicitly disallows them ("Duplicate keys
    // are not allowed"). bcftools 1.23.1 doesn't reject this, though -- verified live: "DP=1;DP=2" is accepted
    // with no warning, and bcftools keeps only the first occurrence ("1"), not "1,2". This parser warns (rather
    // than throwing) and matches that same "keep first, drop rest" behavior, instead of silently merging
    // duplicates into what would look like a legitimate multi-value list.
    VcfPosition position = newPosition().toBuilder().rawInfo("DP=1;DP=2").build();
    assertEquals(Collections.singletonList("1"), position.getInfo("DP"));

    // a duplicate flag-form key (no "=") behaves the same way
    VcfPosition flagPosition = newPosition().toBuilder().rawInfo("DB;DB").build();
    assertEquals(Collections.singletonList(""), flagPosition.getInfo("DB"));
  }

  @Test
  public void testGetInfoMappingQuality() {
    // MQ is a float reserved property; getInfo must convert it (it was previously typed Float.class, which threw)
    VcfPosition p = newPosition().toBuilder().rawInfo("MQ=52.4").build();
    assertEquals(VcfFloat.of(new BigDecimal("52.4")), p.getInfo(ReservedInfoProperty.MappingQuality));
  }

  @Test
  public void testGetInfoCigarIsListValued() {
    // VCFv4.2 only describes CIGAR in prose with no formal Number, but VCFv4.3/4.4/4.5 all declare it Number=A
    // (one value per ALT allele); a multi-allelic record's CIGAR must therefore convert to a List, not a scalar
    VcfPosition p = newPosition().toBuilder().altBases(List.of("T", "C")).rawInfo("CIGAR=1M,2M").build();
    assertEquals(Arrays.asList("1M", "2M"), p.getInfo(ReservedInfoProperty.Cigar));
  }

  @Test
  public void testGetInfoAlleleDepthIsListValued() {
    // AD/ADF/ADR were previously missing from ReservedInfoProperty entirely, so getInfo(ReservedInfoProperty)
    // could not be called with them at all; Number=R means one value per allele, including REF, so a biallelic
    // record's AD converts to a two-element List, not a scalar
    VcfPosition p = newPosition().toBuilder().altBases(List.of("T")).rawInfo("AD=10,5").build();
    assertEquals(Arrays.asList(10L, 5L), p.getInfo(ReservedInfoProperty.AlleleDepth));
  }

  @Test
  public void testGetInfoFloatAcceptsSpecialValues() {
    // VCFv4.3.tex (the target) states it accepts NAN/INF/INFINITY (case-insensitive, optionally signed) in
    // addition to a normal decimal number; VCFv4.2 doesn't define Float's grammar at all -- see VcfFloat's own
    // Javadoc for the citation. A plain BigDecimal can't represent these special values at all
    VcfPosition nan = newPosition().toBuilder().rawInfo("MQ=NaN").build();
    assertEquals(VcfFloat.ofSpecial(VcfFloat.Special.NAN), nan.getInfo(ReservedInfoProperty.MappingQuality));

    VcfPosition posInf = newPosition().toBuilder().rawInfo("MQ=INFINITY").build();
    assertEquals(VcfFloat.ofSpecial(VcfFloat.Special.POSITIVE_INFINITY),
        posInf.getInfo(ReservedInfoProperty.MappingQuality));

    VcfPosition negInf = newPosition().toBuilder().rawInfo("MQ=-inf").build();
    assertEquals(VcfFloat.ofSpecial(VcfFloat.Special.NEGATIVE_INFINITY),
        negInf.getInfo(ReservedInfoProperty.MappingQuality));
  }

  @Test
  public void testGetInfoHomologySequence() {
    // HomologySequence must resolve the HOMSEQ key (it was previously misdefined as HOMLEN)
    assertEquals("HOMSEQ", ReservedInfoProperty.HomologySequence.getId());
    // Number=. is genuinely multi-valued (per VCFv4.3's clarifying example for this family of keys), so this
    // returns a List rather than a bare String
    VcfPosition p = newPosition().toBuilder().rawInfo("HOMSEQ=ACGT").build();
    assertEquals(Collections.singletonList("ACGT"), p.getInfo(ReservedInfoProperty.HomologySequence));
  }

  @Test
  public void testGetInfoStructuralVariantLengthIsMultiValued() {
    // SVLEN was previously declared isList=false, but VCFv4.3 confirms with an example (SVLEN=-100,-110 for a
    // deletion with two ALT alleles) that Number=. here means genuinely multi-valued, one per ALT allele
    VcfPosition p = newPosition().toBuilder().rawInfo("SVLEN=-100,-110").build();
    assertEquals(Arrays.asList(-100L, -110L), p.getInfo(ReservedInfoProperty.StructuralVariantLength));
  }

  @Test
  public void testGetInfoListTypedKeyCardinalityViolationWarnsButKeepsAllValues() {
    // AC is Number=A (list-typed): with 1 ALT allele, exactly 1 value is expected, but 2 are given. Unlike the
    // scalar-key violation below (which truncates to the first value), a list-typed violation warns but returns
    // every given value unchanged -- this is the INFO-side counterpart of VcfSample.getProperty(VcfPosition,
    // VcfMetadata, String)'s identical FORMAT-side check, added earlier this session. Uses lenientPosition(),
    // not newPosition(): a cardinality mismatch now throws under strict (see
    // testGetInfoCardinalityViolationThrowsUnderStrict below) -- this test is specifically about the warn path.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      VcfPosition p = lenientPosition().toBuilder().altBases(List.of("T")).rawInfo("AC=1,2").build();
      assertEquals(Arrays.asList(1L, 2L), p.getInfo(ReservedInfoProperty.AlleleCount));
      assertTrue(appender.anyMessageContains("INFO AC has 2 value(s), but Number=A requires 1"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testGetInfoFixedCountKeyCardinalityViolationWarns() {
    // CIPOS is a fixed Number=2 (not ALT-allele-derived); 3 values given instead of 2 must still warn under
    // lenient (see the class comment above on lenientPosition() vs newPosition())
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      VcfPosition p = lenientPosition().toBuilder().rawInfo("CIPOS=1,2,3").build();
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
      VcfPosition p = newPosition().toBuilder().altBases(List.of("T", "C")).rawInfo("AC=1,2").build();
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
      VcfPosition p = newPosition().toBuilder().altBases(List.of("T")).rawInfo("AD=").build();
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
    // hiding the cardinality violation entirely (no warning, no throw). Uses lenientPosition(): this test is
    // about the warn path specifically -- see testGetInfoCardinalityViolationThrowsUnderStrict below for strict.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      VcfPosition p = lenientPosition().toBuilder().rawInfo("AA=1,23").build();
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
    // should warn and recover, not throw under lenient (see lenientPosition() vs newPosition() above)
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      VcfPosition p = lenientPosition().toBuilder().rawInfo("DP=1,23").build();
      assertEquals(Long.valueOf(1L), p.getInfo(ReservedInfoProperty.Depth));
      assertEquals(1, appender.countMessagesContaining("INFO DP has 2 value(s), but Number=1 requires 1"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testGetInfoCardinalityViolationThrowsUnderStrict() {
    // the actual fix: a strict VcfContext now throws VcfFormatException for a cardinality mismatch instead of
    // warning and best-effort recovering, matching this library's established isLenient()-gated read-side
    // dispatch elsewhere (InternalUtils.warnIfWrongCardinality). newPosition() (strict) is deliberate here,
    // unlike the warn-path tests above.
    VcfPosition p = newPosition().toBuilder().rawInfo("DP=1,23").build();
    VcfFormatException ex = assertThrows(VcfFormatException.class, () -> p.getInfo(ReservedInfoProperty.Depth));
    assertTrue(ex.getMessage().contains("INFO DP has 2 value(s), but Number=1 requires 1"));

    // the non-reserved-key overload (getInfo(VcfMetadata, String)) shares the identical dispatch
    VcfMetadata metadata = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2")
        .addInfo("XX", "custom", InfoType.Integer, "1", null, null).build();
    VcfPosition p2 = newPosition().toBuilder().rawInfo("XX=1,2").build();
    VcfFormatException ex2 = assertThrows(VcfFormatException.class, () -> p2.getInfo(metadata, "XX"));
    assertTrue(ex2.getMessage().contains("INFO XX has 2 value(s), but Number=1 requires 1"));
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
      VcfPosition p = newPosition().toBuilder().rawInfo("AA=A").build();
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
    VcfPosition p = newPosition().toBuilder().rawInfo("AN=2184;AC=249;AF=0.11").build();
    assertEquals(Arrays.asList("AN", "AC", "AF"), new ArrayList<>(p.getInfoKeys()));
  }

  private static VcfPosition newPosition() {
    return STRICT_CONTEXT.newPosition("chr", 1, null, "C", null, null, null, null, null, -1);
  }

  private static VcfPosition lenientPosition() {
    return LENIENT_CONTEXT.newPosition("chr", 1, null, "C", null, null, null, null, null, -1);
  }

  @Test
  public void testGetAllele() {
    VcfPosition position = STRICT_CONTEXT.newPosition("chr1", 1, null, "C", new ArrayList<>(Arrays.asList("A", "T")),
        null, null,
        null, null, -1);
    assertEquals("C", position.getAllele(0));
    assertEquals("A", position.getAllele(1));
    assertEquals("T", position.getAllele(2));
    assertThrows(IndexOutOfBoundsException.class, () -> position.getAllele(3));
  }

  @Test
  public void testGetAlleleShortConstructor() {
    VcfPosition position = STRICT_CONTEXT.newPosition("chr1", 1, "C", VcfFloat.of(new BigDecimal("0.0")), -1);
    assertEquals("C", position.getAllele(0));
    assertThrows(IndexOutOfBoundsException.class, () -> position.getAllele(1));
  }

  @Test
  public void testFilterStatus() {
    VcfPosition none = STRICT_CONTEXT.newPosition("chr1", 1, null, "C", null, null, Collections.singletonList("."),
        null, null, -1);
    assertEquals(VcfPosition.FilterStatus.NONE, none.getFilterStatus());
    assertTrue(none.isPassingAllFilters()); // no filters applied is treated as passing
    assertTrue(none.getFilters().isEmpty());

    VcfPosition passed = STRICT_CONTEXT.newPosition("chr1", 1, null, "C", null, null, Collections.singletonList("PASS"),
        null, null, -1);
    assertEquals(VcfPosition.FilterStatus.PASSED, passed.getFilterStatus());
    assertTrue(passed.isPassingAllFilters());
    assertEquals(Collections.singletonList("PASS"), passed.getFilters());

    // regression test: an unspecified FILTER is NONE, not an implicit PASSED -- PASS must always be explicit,
    // matching bcftools 1.23.1 (neither "bcftools annotate -x FILTER" nor a literal empty FILTER string ever
    // produces PASS; see VcfPosition.getFilterStatus()'s Javadoc)
    VcfPosition noFilters = STRICT_CONTEXT.newPosition("chr1", 1, null, "C", null, null, null, null, null, -1);
    assertEquals(VcfPosition.FilterStatus.NONE, noFilters.getFilterStatus());

    VcfPosition failed = STRICT_CONTEXT.newPosition("chr1", 1, null, "C", null, null, Collections.singletonList("q10"),
        null, null, -1);
    assertEquals(VcfPosition.FilterStatus.FAILED, failed.getFilterStatus());
    assertFalse(failed.isPassingAllFilters());
    assertEquals(Collections.singletonList("q10"), failed.getFilters());
  }

  @Test
  public void testBadFilterDotWithOthers() {
    assertThrows(VcfFormatException.class, () ->
        STRICT_CONTEXT.newPosition("chr1", 1, null, "C", null, null, Arrays.asList("q10", "."), null, null, -1));
  }

  @Test
  public void testNoFilters() {
    VcfPosition position = STRICT_CONTEXT.newPosition("chr", 1, null, "C", null, null, null, null, null, -1);
    assertTrue(position.getFilters().isEmpty());
    assertTrue(position.isPassingAllFilters());
    assertEquals(VcfPosition.FilterStatus.NONE, position.getFilterStatus());
  }

  @Test
  public void testHasFilters() {
    VcfPosition position = STRICT_CONTEXT.newPosition("chr", 1, null, "C", null, null, Collections.singletonList("bad"),
        null, null, -1);
    assertEquals(1, position.getFilters().size());
    assertEquals("bad", position.getFilters().get(0));
    assertFalse(position.isPassingAllFilters());
  }

  @Test
  public void testBadFilter1() {
    VcfPosition position = STRICT_CONTEXT.newPosition("chr", 1, null, "C", null, null,
        Collections.singletonList("PASS"),
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
    VcfPosition position = lenientContext(new VcfWarnings(sink)).newPosition("chr", 1, null, "C", null, null,
        Arrays.asList("bad", "PASS"),
        null, null, -1);
    assertTrue(sink.toString().contains("PASS along with other filters"));
    assertEquals(Arrays.asList("bad", "PASS"), position.getFilters());
  }

  @Test
  public void testEmptyIdEntriesDropped() {
    // VCF does not allow zero-length fields; ID is a position-independent list, so an empty entry is dropped rather
    // than throwing (see EMPTY_FIELD_HANDLING.md). Uses the lenient 11-arg constructor overload -- the bare 10-arg
    // constructor is now strict and throws for an empty entry instead of dropping it.
    VcfPosition interior = LENIENT_CONTEXT.newPosition("chr", 1, new ArrayList<>(Arrays.asList("rs1", "", "rs2")), "C",
        null, null,
        null, null, null, -1);
    assertEquals(Arrays.asList("rs1", "rs2"), interior.getIds());

    VcfPosition trailing = LENIENT_CONTEXT.newPosition("chr", 1, new ArrayList<>(Arrays.asList("rs3", "")), "C", null,
        null,
        null, null, null, -1);
    assertEquals(Collections.singletonList("rs3"), trailing.getIds());
  }

  @Test
  public void testEmptyFilterEntriesDropped() {
    // Uses the lenient 11-arg constructor overload -- the bare 10-arg constructor is now strict and throws for
    // an empty entry instead of dropping it.
    VcfPosition interior = LENIENT_CONTEXT.newPosition("chr", 1, null, "C", null, null,
        new ArrayList<>(Arrays.asList("q10", "", "q20")), null, null, -1);
    assertEquals(Arrays.asList("q10", "q20"), interior.getFilters());

    VcfPosition trailing = LENIENT_CONTEXT.newPosition("chr", 1, null, "C", null, null,
        new ArrayList<>(Arrays.asList("q10", "")), null, null, -1);
    assertEquals(Collections.singletonList("q10"), trailing.getFilters());
  }

  @Test
  public void testEmptyAltEntriesSubstitutedWithDot() {
    // ALT is position-dependent (GT indexes into it -- see testGetAlleleAfterAltDotSubstitutionPreservesIndex),
    // so an empty entry is substituted with '.', not dropped, unlike ID/FILTER above. Uses the lenient 11-arg
    // constructor overload -- the bare 10-arg constructor is now strict and throws for an empty entry instead of
    // substituting it.
    VcfPosition interior = LENIENT_CONTEXT.newPosition("chr", 1, null, "C",
        new ArrayList<>(Arrays.asList("T", "", "G")), null, null, null, null, -1);
    assertEquals(Arrays.asList("T", ".", "G"), interior.getAltBases());

    VcfPosition trailing = LENIENT_CONTEXT.newPosition("chr", 1, null, "C",
        new ArrayList<>(Arrays.asList("T", "")), null, null, null, null, -1);
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "C",
        new ArrayList<>(Arrays.asList("T", "C", "", "G")), null, null, null, null, -1);
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr", 1, null, "C", null, null, null, null,
        new ArrayList<>(Arrays.asList("GT", "", "GQ")), -1);
    assertEquals(Arrays.asList("GT", "", "GQ"), position.getFormat());
  }

  @Test
  public void testDuplicateFormatKeyRejected() {
    // VCFv4.3+ states outright that duplicate FORMAT keys are not allowed
    assertThrows(VcfFormatException.class, () -> STRICT_CONTEXT.newPosition("chr", 1, null, "C", null, null, null, null,
        new ArrayList<>(Arrays.asList("GT", "DP", "DP")), -1));
  }

  @Test
  public void testMultipleEmptyFormatKeysNotTreatedAsDuplicates() {
    // an empty FORMAT key is already handled (kept as-is, warned about) separately; two of them are not a "duplicate"
    // in the sense the spec means. Uses the lenient 11-arg constructor overload -- the bare 10-arg constructor is
    // now strict and throws for an empty sub-field name instead of keeping it.
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr", 1, null, "C", null, null, null, null,
        new ArrayList<>(Arrays.asList("GT", "", "")), -1);
    assertEquals(Arrays.asList("GT", "", ""), position.getFormat());
  }

  @Test
  public void testLoneDotFormatMeansNoFormatFields() {
    // FORMAT="." (no per-sample format fields at all) is valid when sample columns are present -- VCFv4.2 through
    // 4.5 are all silent on this exact case, but bcftools 1.23.1 accepts and correctly interprets it; see
    // VcfPosition.checkFormat's javadoc for the full rationale
    VcfPosition position = STRICT_CONTEXT.newPosition("chr", 1, null, "C", null, null, null, null,
        new ArrayList<>(Collections.singletonList(".")), -1);
    assertTrue(position.getFormat().isEmpty());
  }

  @Test
  public void testBadChromosome() {
    assertThrows(VcfFormatException.class, () -> {
      STRICT_CONTEXT.newPosition("chr 1", 1, null, "C", null, null, null, null, null, -1); // whitespace is not allowed
    });
  }

  @Test
  public void testColonInChromosomeAllowed() {
    // the VCF spec forbids only whitespace in CHROM; a colon is allowed
    STRICT_CONTEXT.newPosition("chr:1", 1, null, "C", null, null, null, null, null, -1);
  }

  @Test
  public void testInvalidCharactersRejectedAnywhereInChromosome() {
    // VCFv4.3.tex (the target) states this fuller grammar directly in its own "changes since previous version"
    // appendix (~line 2135-2141, unchanged in 4.4/4.5): VCF contig names must match SAM reference-sequence-name
    // rules. VCFv4.2.tex line 179 only states "no whitespace permitted" for CHROM, predating this rule rather
    // than conflicting with it. These 13 characters are invalid anywhere in an unwrapped CHROM value.
    char[] invalidChars = { '\\', ',', '"', '`', '\'', '(', ')', '{', '}', '[', ']', '<', '>' };
    for (char c : invalidChars) {
      String chrom = "chr" + c + "1";
      assertThrows(VcfFormatException.class, () -> STRICT_CONTEXT.newPosition(chrom, 1, "C", VcfFloat.of(new BigDecimal("0")), -1),
          () -> "CHROM \"" + chrom + "\" containing '" + c + "' should have been rejected");
    }
  }

  @Test
  public void testAsteriskAndEqualsRejectedOnlyAsFirstCharacter() {
    // VCFv4.3.tex's appendix: "* is now valid when not the first character"; "*"/"=" as the first character
    // "were already invalid and remain so"
    assertThrows(VcfFormatException.class, () -> STRICT_CONTEXT.newPosition("*chr1", 1, "C", VcfFloat.of(new BigDecimal("0")), -1));
    assertThrows(VcfFormatException.class, () -> STRICT_CONTEXT.newPosition("=chr1", 1, "C", VcfFloat.of(new BigDecimal("0")), -1));
    STRICT_CONTEXT.newPosition("chr*1", 1, "C", VcfFloat.of(new BigDecimal("0")), -1); // '*' elsewhere is fine
    STRICT_CONTEXT.newPosition("chr1*", 1, "C", VcfFloat.of(new BigDecimal("0")), -1);
    STRICT_CONTEXT.newPosition("chr=1", 1, "C", VcfFloat.of(new BigDecimal("0")), -1); // '=' elsewhere was never restricted
  }

  @Test
  public void testWellFormedAngleBracketWrappedChromosomeAccepted() {
    // VCFv4.2.tex line 179: CHROM's other valid form, "an angle-bracketed ID String (<ID>) pointing to a contig
    // in the assembly file"
    STRICT_CONTEXT.newPosition("<1>", 1, "C", VcfFloat.of(new BigDecimal("0")), -1);
    STRICT_CONTEXT.newPosition("<chr1>", 1, "C", VcfFloat.of(new BigDecimal("0")), -1);
  }

  @Test
  public void testMalformedAngleBracketChromosomeRejected() {
    assertThrows(VcfFormatException.class,
        () -> STRICT_CONTEXT.newPosition("<1", 1, "C", VcfFloat.of(new BigDecimal("0")), -1));    // unclosed
    assertThrows(VcfFormatException.class,
        () -> STRICT_CONTEXT.newPosition("1>", 1, "C", VcfFloat.of(new BigDecimal("0")), -1));    // no opening
    assertThrows(VcfFormatException.class,
        () -> STRICT_CONTEXT.newPosition("<>", 1, "C", VcfFloat.of(new BigDecimal("0")), -1));    // empty content after unwrap
    assertThrows(VcfFormatException.class,
        () -> STRICT_CONTEXT.newPosition("<1>2>", 1, "C", VcfFloat.of(new BigDecimal("0")), -1)); // malformed nested '>'
  }

  @Test
  public void testShortConstructorValidates() {
    assertThrows(VcfFormatException.class,
        () -> STRICT_CONTEXT.newPosition("", 1, "C", VcfFloat.of(new BigDecimal("0")), -1));      // empty CHROM
    assertThrows(VcfFormatException.class,
        () -> STRICT_CONTEXT.newPosition("chr1", -1, "C", VcfFloat.of(new BigDecimal("0")), -1)); // negative POS
    assertThrows(VcfFormatException.class,
        () -> STRICT_CONTEXT.newPosition("chr1", 1, "X", VcfFloat.of(new BigDecimal("0")), -1));  // invalid REF
  }

  @Test
  public void testConstructorRejectsNullCollectionElementsInsteadOfThrowingNpe() {
    // a parsed value can never be null; only a caller-supplied list/multimap passed directly to a public
    // factory could contain one (VcfPosition has no mutation route since Task 8's freeze, so a null element
    // can now only reach checkIds/checkAltBases/checkFilters/checkFormat/checkInfoEntries at construction
    // time). Each must reject a null element with VcfFormatException, not let it reach a raw NullPointerException
    // deeper in the check.
    assertThrows(VcfFormatException.class, () ->
        STRICT_CONTEXT.newPosition("chr", 1, Arrays.asList("rs1", null), "C", null, null, null, null, null, -1));

    assertThrows(VcfFormatException.class, () ->
        STRICT_CONTEXT.newPosition("chr", 1, null, "C", Arrays.asList("T", null), null, null, null, null, -1));

    assertThrows(VcfFormatException.class, () ->
        STRICT_CONTEXT.newPosition("chr", 1, null, "C", null, null, Arrays.asList("q10", null), null, null, -1));

    assertThrows(VcfFormatException.class, () ->
        STRICT_CONTEXT.newPosition("chr", 1, null, "C", null, null, null, null, Arrays.asList("GT", null), -1));

    ListMultimap<String, String> infoNullKey = ArrayListMultimap.create();
    infoNullKey.put(null, "value");
    assertThrows(VcfFormatException.class, () ->
        STRICT_CONTEXT.newPosition("chr", 1, null, "C", null, null, null, infoNullKey, null, -1));

    ListMultimap<String, String> infoNullValue = ArrayListMultimap.create();
    infoNullValue.put("KEY", null);
    assertThrows(VcfFormatException.class, () ->
        STRICT_CONTEXT.newPosition("chr", 1, null, "C", null, null, null, infoNullValue, null, -1));
  }

  @Test
  public void testConstructorRejectsNullChromosomeInsteadOfThrowingNpe() {
    // chr is typed non-@Nullable, but nothing enforces that at compile time; checkChromosome() must reject a null
    // with VcfFormatException, not let it reach a raw NullPointerException at chr.isEmpty()
    assertThrows(VcfFormatException.class,
        () -> STRICT_CONTEXT.newPosition(null, 1, null, "C", null, null, null, null, null, -1));
  }

  @Test
  public void testStrictConstructorThrowsForNonPrintableAsciiInChromosome() {
    // regression test: VCFv4.3.tex:258-259, "they may contain any printable ASCII characters in the range
    // [!-~]" -- previously unenforced (INVALID_CONTIG_NAME_CHAR_PATTERN only blocklists specific punctuation
    // WITHIN that range, never the range's own outer bound), so a DEL character (0x7F) or non-ASCII
    // character in CHROM parsed with zero warnings. Confirmed live that bcftools 1.23.1 warns on both.
    assertThrows(VcfFormatException.class,
        () -> STRICT_CONTEXT.newPosition("chr\u007F1", 100, null, "A", null, null, null, null, null, -1));
    assertThrows(VcfFormatException.class,
        () -> STRICT_CONTEXT.newPosition("chr\u00E41", 100, null, "A", null, null, null, null, null, -1));
  }

  @Test
  public void testLenientConstructorWarnsInsteadOfThrowingForNonPrintableAsciiInChromosome() {
    // unlike every other CHROM character-set check in checkChromosome (all unconditional throws, matching
    // this method's own established "CHROM is structural" floor), this ONE check has a warn path, because
    // bcftools itself only warns (never rejects) on it -- see checkChromosome's own checkWarnings parameter
    // comment for why this single check is the exception, not the rule, in that method.
    StringWriter sink = new StringWriter();
    VcfPosition position = lenientContext(new VcfWarnings(sink)).newPosition("chr\u007F1", 100, null, "A", null,
        null, null, null, null, -1);
    assertEquals("chr\u007F1", position.getChromosome());
    assertTrue(sink.toString().contains("non-printable-ASCII character"));
  }

  @Test
  public void testValidateWarnsInsteadOfThrowingForNonPrintableAsciiInChromosomeEvenUnderStrictContext() {
    // validate() always warns for a recoverable condition rather than throwing, even under a strict context
    // -- matching checkIds/checkAltBases/checkFilters/checkFormat's identical existing treatment
    StringWriter sink = new StringWriter();
    VcfPosition position = lenientContext(new VcfWarnings(sink)).newPosition("chr\u007F1", 100, null, "A", null,
        null, null, null, null, -1);
    position.validate();
    assertTrue(sink.toString().contains("non-printable-ASCII character"));
  }

  @Test
  public void testConstructorRejectsNullRefInsteadOfThrowingNpe() {
    assertThrows(VcfFormatException.class,
        () -> STRICT_CONTEXT.newPosition("chr", 1, null, null, null, null, null, null, null, -1));
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
        () -> STRICT_CONTEXT.newPosition("chr1", 1, null, "B", null, null, null, null, null, -1));
    assertTrue(ex.getMessage().contains("Invalid reference base 'B'"));
  }

  @Test
  public void testFilterStatusIsNotStuckAfterRebuildingWithADifferentFilterList() {
    // regression test: FilterStatus is derived fresh from getFilters()'s current contents every time, so a
    // position rebuilt (via toBuilder()) from one that was normalized from "." can still report PASSED once its
    // FILTER list is explicitly set to ["PASS"] -- there is no separate persisted flag that could get stuck
    VcfPosition dot = STRICT_CONTEXT.newPosition("chr1", 1, null, "C", null, null,
        new ArrayList<>(List.of(".")), null, null, -1);
    assertEquals(VcfPosition.FilterStatus.NONE, dot.getFilterStatus());

    VcfPosition pass = dot.toBuilder().filters(List.of("PASS")).build();
    assertEquals(VcfPosition.FilterStatus.PASSED, pass.getFilterStatus());
    assertEquals(Collections.singletonList("PASS"), pass.getFilters());
  }

  @Test
  public void testTelomericPositions() {
    STRICT_CONTEXT.newPosition("chr1", 0, null, "C", null, null, null, null, null, -1); // telomere at start is valid
    assertThrows(VcfFormatException.class, () -> // a negative POS is invalid
        STRICT_CONTEXT.newPosition("chr1", -1, null, "C", null, null, null, null, null, -1));
  }

  @Test
  public void testGtMustBeFirstFormat() {
    STRICT_CONTEXT.newPosition("chr1", 1, null, "C", null, null, null, null, Arrays.asList("GT", "DP"), -1); // valid
    assertThrows(VcfFormatException.class, () -> // GT not first is invalid
        STRICT_CONTEXT.newPosition("chr1", 1, null, "C", null, null, null, null, Arrays.asList("DP", "GT"), -1));
  }

  @Test
  public void testEmptyChromosome() {
    assertThrows(VcfFormatException.class, () ->
        STRICT_CONTEXT.newPosition("", 1, null, "C", null, null, null, null, null, -1));
  }

  @Test
  public void testDuplicateIdRejected() {
    STRICT_CONTEXT.newPosition("chr1", 1, Arrays.asList("rs1", "rs2"), "C", null, null, null, null, null,
        -1); // unique IDs are fine
    assertThrows(VcfFormatException.class, () ->
        STRICT_CONTEXT.newPosition("chr1", 1, Arrays.asList("rs1", "rs1"), "C", null, null, null, null, null, -1));
  }

  @Test
  public void testBadId() {
    assertThrows(VcfFormatException.class, () -> {
      STRICT_CONTEXT.newPosition("chr1", 1, Collections.singletonList(";"), "C", null, null, null, null, null, -1);
    });
  }

  @Test
  public void testBadRefBase() {
    assertThrows(VcfFormatException.class, () -> {
      STRICT_CONTEXT.newPosition("chr1", 1, null, "X", null, null, null, null, null, -1);
    });
  }

  @Test
  public void testBadVarBase() {
    assertThrows(VcfFormatException.class, () -> {
      STRICT_CONTEXT.newPosition("chr1", 1, null, "C", Collections.singletonList("X"), null, null, null, null, -1);
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
    STRICT_CONTEXT.newPosition("chr1", 1, null, "C", Collections.singletonList("."), null, null, null, null,
        -1); // "." alone is fine
    VcfPosition afterReal = STRICT_CONTEXT.newPosition("chr1", 1, null, "C", new ArrayList<>(Arrays.asList("A", ".")),
        null,
        null, null, null, -1);
    assertEquals(Arrays.asList("A", "."), afterReal.getAltBases());
    VcfPosition beforeReal = STRICT_CONTEXT.newPosition("chr1", 1, null, "C", new ArrayList<>(Arrays.asList(".", "A")),
        null,
        null, null, null, -1);
    assertEquals(Arrays.asList(".", "A"), beforeReal.getAltBases());
  }

  @Test
  public void testAltEmptyEntrySubstitutionPreservesGtIndexAcrossDeclaredVersions() {
    // Task 7: EMPTY_FIELD_HANDLING.md's ALT '.'-substitution is unconditional -- GT indexes into ALT
    // positionally (VCFv4.2.tex line 217: "1 for the first allele listed in ALT, 2 for the second allele listed
    // in ALT and so on"; VcfPosition.getAllele(int) implements this as m_altBases.get(index - 1)), so dropping
    // an empty entry would shift every later allele's index -- a 4.2-grounded requirement, not a 4.3+ one.
    // VCFv4.3.tex line 350 / VCFv4.4.tex line 367 / VCFv4.5.tex line 416 merely ratify '.' as a sanctioned
    // per-entry ALT token, a claim 4.2 is silent on rather than one 4.2 forbids. Pins the GT-index invariant end
    // to end (nothing did before this), via ordinary construction under both a 4.2-declared and a 4.3-declared
    // context, and asserts the two declared versions behave identically -- a future version-gated branch here
    // would be a regression, not a fix.
    for (String declaredFileFormat : Arrays.asList("VCFv4.2", "VCFv4.3")) {
      VcfContext context = VcfContext.lenient(declaredFileFormat, VcfWarnings.DEFAULT);

      // interior empty entry: T,,C -> T,.,C
      VcfPosition interior = context.newPosition("chr1", 1, null, "A",
          new ArrayList<>(Arrays.asList("T", "", "C")), null, null, null, null, -1);
      assertEquals(Arrays.asList("T", ".", "C"), interior.getAltBases(), declaredFileFormat);
      assertEquals("A", interior.getAllele(0), declaredFileFormat);
      assertEquals("T", interior.getAllele(1), declaredFileFormat); // before the empty index
      assertEquals(".", interior.getAllele(2), declaredFileFormat); // at the empty index
      assertEquals("C", interior.getAllele(3), declaredFileFormat); // after the empty index

      VcfSample gtAcrossAllFour = context.newSample(List.of("GT"), List.of("0/1/2/3"));
      VcfSample.Genotype genotypeInterior = gtAcrossAllFour.getGenotype(interior);
      assertEquals(Arrays.asList("A", "T", ".", "C"), genotypeInterior.getAlleles(), declaredFileFormat);

      // trailing empty entry: T, -> T,.
      VcfPosition trailing = context.newPosition("chr1", 1, null, "A",
          new ArrayList<>(Arrays.asList("T", "")), null, null, null, null, -1);
      assertEquals(Arrays.asList("T", "."), trailing.getAltBases(), declaredFileFormat);
      assertEquals("A", trailing.getAllele(0), declaredFileFormat);
      assertEquals("T", trailing.getAllele(1), declaredFileFormat); // before the empty index
      assertEquals(".", trailing.getAllele(2), declaredFileFormat); // at the empty index

      VcfSample gtAcrossBothOfTwo = context.newSample(List.of("GT"), List.of("0/1/2"));
      VcfSample.Genotype genotypeTrailing = gtAcrossBothOfTwo.getGenotype(trailing);
      assertEquals(Arrays.asList("A", "T", "."), genotypeTrailing.getAlleles(), declaredFileFormat);
    }
  }

  @Test
  public void testBuilderAltBasesSubstitutesEmptyEntryBeforeDelegatingToTrustedFactory() {
    // Companion to testAltEmptyEntrySubstitutionPreservesGtIndexAcrossDeclaredVersions, but for the other
    // construction route: VcfPosition.Builder#altBases marks the field dirty, and build() runs checkAltBases
    // (this class's Builder#build() Javadoc) *before* delegating to newPositionCopyingTrustedFields, so a
    // Builder-made position already holds the substituted list -- unlike the writer's own trusted-factory route
    // (VcfWriterTest.testDefaultWriteSubstitutesEmptyAltEntryWithDotUnconditionallyAcrossDeclaredVersions), which
    // deliberately bypasses this check. Same unconditional-across-versions invariant as above.
    for (String declaredFileFormat : Arrays.asList("VCFv4.2", "VCFv4.3")) {
      VcfContext context = VcfContext.lenient(declaredFileFormat, VcfWarnings.DEFAULT);
      VcfPosition seed = context.newPosition("chr1", 1, null, "A", List.of("G"), null, null, null, null, -1);

      VcfPosition interior = seed.toBuilder()
          .altBases(new ArrayList<>(Arrays.asList("T", "", "C")))
          .build();
      assertEquals(Arrays.asList("T", ".", "C"), interior.getAltBases(), declaredFileFormat);

      VcfPosition trailing = seed.toBuilder()
          .altBases(new ArrayList<>(Arrays.asList("T", "")))
          .build();
      assertEquals(Arrays.asList("T", "."), trailing.getAltBases(), declaredFileFormat);
    }
  }

  @Test
  public void testZeroFilter() {
    assertThrows(VcfFormatException.class, () -> {
      STRICT_CONTEXT.newPosition("chr1", 1, null, "C", null, null, Collections.singletonList("0"), null, null, -1);
    });
  }

  @Test
  public void testFilterWithWhitespace() {
    assertThrows(VcfFormatException.class, () -> {
      STRICT_CONTEXT.newPosition("chr1", 1, null, "C", null, null, Collections.singletonList("adsf\nsdf"), null, null,
          -1);
    });
  }

  @Test
  public void testFilterWithMultipleNewlinesRejected() {
    // a wrapping ".*\s.*" pattern matched with matches() would fail to detect whitespace here (2+ line terminators);
    // the whitespace check must still catch it
    assertThrows(VcfFormatException.class, () -> {
      STRICT_CONTEXT.newPosition("chr1", 1, null, "C", null, null, Collections.singletonList("a\nb\nc"), null, null,
          -1);
    });
  }

  @Test
  public void testInfoWithWhitespace() {
    assertThrows(VcfFormatException.class, () -> {
      ListMultimap<String, String> map = ArrayListMultimap.create();
      map.put("anid", "a\nvalue");
      STRICT_CONTEXT.newPosition("chr1", 1, null, "C", null, null, null, map, null, -1);
    });
  }

  @Test
  public void testBadFormat() {
    assertThrows(VcfFormatException.class, () -> {
      STRICT_CONTEXT.newPosition("chr1", 1, null, "C", null, null, null, null, Collections.singletonList("+"), -1);
    });
  }

  @Test
  public void testInfoValueWithStructuralCharactersRejected() {
    // a raw ";" or "," in an INFO value (or ";"/"=" in a key) would corrupt round-trip parsing (a parsed value can
    // never contain them, since they were already split on to arrive at the value); reject them at construction
    ListMultimap<String, String> semicolonInValue = ArrayListMultimap.create();
    semicolonInValue.put("KEY", "a;b");
    assertThrows(VcfFormatException.class, () ->
        STRICT_CONTEXT.newPosition("chr", 1, null, "C", null, null, null, semicolonInValue, null, -1));

    ListMultimap<String, String> commaInValue = ArrayListMultimap.create();
    commaInValue.put("KEY", "a,b");
    assertThrows(VcfFormatException.class, () ->
        STRICT_CONTEXT.newPosition("chr", 1, null, "C", null, null, null, commaInValue, null, -1));

    ListMultimap<String, String> semicolonInKey = ArrayListMultimap.create();
    semicolonInKey.put("KEY;X", "value");
    assertThrows(VcfFormatException.class, () ->
        STRICT_CONTEXT.newPosition("chr", 1, null, "C", null, null, null, semicolonInKey, null, -1));

    ListMultimap<String, String> equalsInKey = ArrayListMultimap.create();
    equalsInKey.put("KEY=X", "value");
    assertThrows(VcfFormatException.class, () ->
        STRICT_CONTEXT.newPosition("chr", 1, null, "C", null, null, null, equalsInKey, null, -1));
  }

  @Test
  public void testCheckAltBasesDoesNotMutateCallersImmutableList() {
    // fillEmptyEntriesWithDot mutates its argument in place; checkAltBases must defensively copy first so an
    // immutable or caller-owned list passed to the constructor is never mutated or rejected with
    // UnsupportedOperationException. Uses the lenient 11-arg constructor overload -- the bare 10-arg constructor
    // is now strict and throws for an empty entry instead of substituting it.
    List<String> immutable = List.of("T", "");
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "C", immutable, null, null, null, null, -1);
    assertEquals(Arrays.asList("T", "."), position.getAltBases());
    assertEquals(Arrays.asList("T", ""), immutable);
  }

  @Test
  public void testFactoryCopiesAltBasesEvenWhenNoEmptyEntryNeedsNormalizing() {
    // VcfContext.newPosition defensively copies every mutable input, so a caller mutating their own reference
    // afterward can never reach the position's backing state -- an immutable List.of(...) argument can't be
    // mutated to prove that either way, so this uses a caller-owned mutable list instead.
    List<String> callerOwned = new ArrayList<>(List.of("T", "C"));
    VcfPosition position = STRICT_CONTEXT.newPosition("chr1", 1, null, "C", callerOwned, null, null, null, null, -1);
    assertEquals(Arrays.asList("T", "C"), position.getAltBases());
    callerOwned.add("G");
    assertEquals(Arrays.asList("T", "C"), position.getAltBases());
  }

  @Test
  public void testConstructorThrowsForSemicolonInId() {
    // ID's own list-join delimiter embedded in a single entry: "rs1;rs2" as ONE intended ID reproduces on the
    // wire as two IDs, which bcftools 1.23.1 accepts silently with no warning at all (see
    // BcftoolsDependencyTest#testIdSemicolonEntryBcftoolsAcceptsSilently). checkIds() already rejected this
    // unconditionally (VCFv4.3.tex:338, "String, no whitespace or semicolons permitted, duplicate values not
    // allowed"); this test pins the construction-time rejection itself, which had no unit coverage. Both
    // contexts are asserted, since the check is NOT gated on strict/lenient.
    VcfFormatException strictEx = assertThrows(VcfFormatException.class,
        () -> STRICT_CONTEXT.newPosition("1", 100, List.of("rs1;rs2"), "A", null, null, null, null, null, -1));
    assertTrue(strictEx.getMessage().contains("contains whitespace or semicolons"));
    VcfFormatException lenientEx = assertThrows(VcfFormatException.class,
        () -> LENIENT_CONTEXT.newPosition("1", 100, List.of("rs1;rs2"), "A", null, null, null, null, null, -1));
    assertTrue(lenientEx.getMessage().contains("contains whitespace or semicolons"));
  }

  @Test
  public void testConstructorThrowsForCommaInAltEntry() {
    // ALT's own list-join delimiter embedded in a single entry: "T,C" as ONE intended allele is
    // byte-indistinguishable on the wire from two genuinely separate alleles, which bcftools splits into two
    // records (see BcftoolsDependencyTest#testAltCommaEntryIsIndistinguishableFromTwoAllelesToBcftools).
    // ALT_BASE_PATTERN's character classes structurally exclude ',' from every branch, so checkAltBases()
    // already rejected this unconditionally; this test pins the construction-time rejection itself, which had
    // no unit coverage. Both contexts are asserted, since the check is NOT gated on strict/lenient.
    VcfFormatException strictEx = assertThrows(VcfFormatException.class,
        () -> STRICT_CONTEXT.newPosition("1", 100, null, "A", List.of("T,C"), null, null, null, null, -1));
    assertTrue(strictEx.getMessage().contains("Invalid alternate base"));
    VcfFormatException lenientEx = assertThrows(VcfFormatException.class,
        () -> LENIENT_CONTEXT.newPosition("1", 100, null, "A", List.of("T,C"), null, null, null, null, -1));
    assertTrue(lenientEx.getMessage().contains("Invalid alternate base"));
  }

  @Test
  public void testConstructorAcceptsBreakendAltWithColonInMateChromosome() {
    // regression test: a breakend ALT whose mate chromosome contains a colon (e.g. an HLA allele name) used to
    // be rejected unconditionally -- InternalUtils.ALT_BASE_PATTERN's mate-location sub-pattern excluded ':'
    // from the unbracketed chromosome class, even though VCFv4.3.tex:2140-2141's own errata explicitly says
    // this is legal and unambiguous ("the ':pos' part is always present"). Both contexts accept it, since
    // checkAltBases's grammar check is not gated on strict/lenient.
    VcfPosition strict = STRICT_CONTEXT.newPosition("1", 100, null, "C",
        List.of("C[HLA-A*01:01:200["), null, null, null, null, -1);
    assertEquals("C[HLA-A*01:01:200[", strict.getAltBases().get(0));
    VcfPosition lenient = LENIENT_CONTEXT.newPosition("1", 100, null, "C",
        List.of("C[HLA-A*01:01:200["), null, null, null, null, -1);
    assertEquals("C[HLA-A*01:01:200[", lenient.getAltBases().get(0));
  }

  @Test
  public void testStrictConstructorThrowsForPassCombinedWithOtherFilters() {
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> STRICT_CONTEXT.newPosition("1", 100, null, "A", null, null, List.of("PASS", "q10"), null, null, -1));
    assertTrue(ex.getMessage().contains("PASS along with other filters"));
  }

  @Test
  public void testStrictConstructorThrowsForWhitespaceInFilter() {
    // regression test: no prior coverage existed for this unconditional throw (reachable regardless of strict/
    // lenient mode) -- hts-specs test/vcf/4.2/failed/failed_body_filter_003.vcf, ##CauseOfFailure=FILTER
    // contains whitespaces (literal FILTER="my filter")
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> STRICT_CONTEXT.newPosition("1", 100, null, "A", null, null, List.of("my filter"), null, null, -1));
    assertTrue(ex.getMessage().contains("contains whitespace"));
  }

  @Test
  public void testStrictConstructorThrowsForSemicolonInFilter() {
    // VCFv4.3.tex:362 states the identical rule ID and FILTER share ("String, no whitespace or semicolons
    // permitted"); checkIds() already enforces the semicolon half for ID (VcfPosition.java:377-379) but
    // checkFilters() never did for FILTER -- this closes that gap.
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> STRICT_CONTEXT.newPosition("1", 100, null, "A", null, null, List.of("a;b"), null, null, -1));
    assertTrue(ex.getMessage().contains("contains a semicolon"));
  }

  @Test
  public void testLenientConstructorAlsoThrowsForSemicolonInFilter() {
    // unconditional, like checkIds()'s identical check -- not gated on strict/lenient, since FILTER's own
    // list-join delimiter embedded in one entry corrupts the entry count on round-trip regardless of context
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> LENIENT_CONTEXT.newPosition("1", 100, null, "A", null, null, List.of("a;b"), null, null, -1));
    assertTrue(ex.getMessage().contains("contains a semicolon"));
  }

  @Test
  public void testStrictConstructorThrowsForColonInFormatKey() {
    // VCFv4.3.tex:429 ("colon-separated FORMAT keys matching the regular expression ^[A-Za-z_][0-9A-Za-z_.]*$")
    // -- FORMAT_PATTERN already excludes ':' from its character class, so this was already unconditional
    // under strict (matches() fails, warnings == null, throws); this test pins that continues to hold after
    // Step 3 adds a separate, explicit check for the same character.
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> STRICT_CONTEXT.newPosition("1", 100, null, "A", null, null, null, null, List.of("GT:AA"), -1));
    assertTrue(ex.getMessage().contains("contains a colon"));
  }

  @Test
  public void testLenientConstructorAlsoThrowsForColonInFormatKey() {
    // the actual fix: previously, LENIENT_CONTEXT would only WARN here (FORMAT_PATTERN mismatch is
    // warn(lenient)/throw(strict)) and keep "GT:AA" as a single, corrupted key -- confirmed live that writing
    // this out and re-reading with bcftools silently misassigns every subsequent sample value (see
    // BcftoolsDependencyTest#testFormatColonCorruptedKeyBcftoolsMisalignsSampleValues, added in Task 2). This
    // is the one BREAKING CHANGE this task introduces: LENIENT_CONTEXT.newPosition(...) with a
    // colon-containing FORMAT key now throws where it previously warned and continued.
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> LENIENT_CONTEXT.newPosition("1", 100, null, "A", null, null, null, null, List.of("GT:AA"), -1));
    assertTrue(ex.getMessage().contains("contains a colon"));
  }

  @Test
  public void testStrictConstructorThrowsForTabInFormatKey() {
    // VCFv4.3.tex:429 ("colon-separated FORMAT keys matching the regular expression ^[A-Za-z_][0-9A-Za-z_.]*$")
    // -- FORMAT_PATTERN already excludes a tab from its character class, so this was already unconditional
    // under strict (matches() fails, warnings == null, throws); this test pins that continues to hold after
    // the new explicit tab/line-terminator check runs first.
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> STRICT_CONTEXT.newPosition("1", 100, null, "A", null, null, null, null, List.of("GT\tAA"), -1));
    assertTrue(ex.getMessage().contains("contains a tab or line terminator"));
  }

  @Test
  public void testLenientConstructorAlsoThrowsForTabInFormatKey() {
    // the actual fix: previously, LENIENT_CONTEXT would only WARN here (FORMAT_PATTERN mismatch is
    // warn(lenient)/throw(strict)) and keep "GT\tAA" as a single, corrupted key -- confirmed live that writing
    // this out and re-reading with bcftools fails outright, unable to parse the file at all (see
    // BcftoolsDependencyTest#testFormatTabCorruptedKeyBcftoolsFatallyFailsToParse). This is the one BREAKING
    // CHANGE this fix introduces: LENIENT_CONTEXT.newPosition(...) with a tab- or line-terminator-containing
    // FORMAT key now throws where it previously warned and continued. A plain space, vertical tab, or form
    // feed is deliberately NOT included -- confirmed live those are non-structural (bcftools only warns, exit
    // 0, no column shift), so they remain governed by the pre-existing warn-or-throw FORMAT_PATTERN check.
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> LENIENT_CONTEXT.newPosition("1", 100, null, "A", null, null, null, null, List.of("GT\tAA"), -1));
    assertTrue(ex.getMessage().contains("contains a tab or line terminator"));
  }

  @Test
  public void testStrictConstructorThrowsForWhitespaceInInfo() {
    // regression test: no prior coverage existed for this unconditional throw (reachable regardless of strict/
    // lenient mode) -- hts-specs test/vcf/4.2/failed/failed_body_info_028.vcf, ##CauseOfFailure=INFO contains a
    // whitespace (literal INFO="RS Q=123")
    com.google.common.collect.ListMultimap<String, String> info = com.google.common.collect.LinkedListMultimap.create();
    info.put("RS Q", "123");
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> STRICT_CONTEXT.newPosition("1", 100, null, "A", null, null, null, info, null, -1));
    assertTrue(ex.getMessage().contains("contains whitespace"));
  }

  @Test
  public void testStrictConstructorThrowsForInfoValueContainingEquals() {
    com.google.common.collect.ListMultimap<String, String> info = com.google.common.collect.LinkedListMultimap.create();
    info.put("DP", "1=2");
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> STRICT_CONTEXT.newPosition("1", 100, null, "A", null, null, null, info, null, -1));
    assertTrue(ex.getMessage().contains("contains '='"));
  }

  @Test
  public void testStrictConstructorThrowsForInfoDotCombinedWithOtherProperties() {
    // regression test: the eager (ListMultimap) constructor previously bypassed this check entirely --
    // testLazyInfoDotCannotCombineWithOtherProperties above already covers the lazy raw-text path
    com.google.common.collect.ListMultimap<String, String> info = com.google.common.collect.LinkedListMultimap.create();
    info.put(".", "");
    info.put("DP", "5");
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> STRICT_CONTEXT.newPosition("1", 100, null, "A", null, null, null, info, null, -1));
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
    VcfPosition position = STRICT_CONTEXT.newPosition("1", 100, null, "A", null, null, null, info, null, -1);
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
    VcfPosition position = STRICT_CONTEXT.newPosition("1", 100, null, "A", null, null, null, info, null, -1);
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
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> STRICT_CONTEXT.newPosition("1", 100, null, "A", null, null, null, info, null, -1));
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
    VcfPosition position = lenientContext(new VcfWarnings(sink)).newPosition("1", 100, null, "A", null, null, null,
        info, null, -1);
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
    VcfPosition position = lenientContext(new VcfWarnings(sink)).newPosition("1", 100, null, "A", null, null, null,
        info, null, -1);
    assertEquals(Collections.singletonList("10"), position.getInfo("DP"));
    assertNull(position.getInfo(""));
    assertTrue(sink.toString().contains("has an empty key"));
  }

  @Test
  public void testStrictConstructorThrowsForInfoEmptyKey() {
    com.google.common.collect.ListMultimap<String, String> info = com.google.common.collect.LinkedListMultimap.create();
    info.put("", "5");
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> STRICT_CONTEXT.newPosition("1", 100, null, "A", null, null, null, info, null, -1));
    assertTrue(ex.getMessage().contains("has an empty key"));
  }

  @Test
  public void testGetInfoReservedPropertyTreatsEmptyValueAsMissingForScalarNumericKey() {
    // regression test: InternalUtils.convertProperty(Class<?>, String, boolean, VcfWarnings)'s scalar (isList
    // = false) branch previously had no empty-value check, unlike its own list branch immediately below (which
    // already warns and substitutes null for an empty comma-separated element). An empty (but non-".") value
    // reached convertElement() directly for a scalar reserved key -- for DP (Long-typed), that raw-crashed the
    // typed conversion with a confusing "Expected integer; got " VcfFormatException instead of the graceful
    // null this library's missing-value convention specifies everywhere else, even though AGENTS.md's Parser
    // Validation Policy floor ("report a parser finding only when invalid content is silently accepted...")
    // is squarely triggered here. Lenient context, not strict: an
    // empty value now throws under strict (InternalUtils.convertProperty's own isLenient()-aware dispatch) --
    // this test is about the substitution itself, covered separately for strict by
    // testGetInfoReservedInfoPropertyEmptyValueThrowsUnderStrictContext below.
    ListMultimap<String, String> info = ArrayListMultimap.create();
    info.put("DP", "");
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr", 1, null, "C", null, null, null, info, null, -1);
    Long dp = position.getInfo(ReservedInfoProperty.Depth);
    assertNull(dp);
  }

  @Test
  public void testGetInfoReservedPropertyTreatsEmptyValueAsMissingForScalarStringKey() {
    // same defect, worse symptom for a String-typed scalar reserved key (AA): convertElement() for String is a
    // no-op passthrough, so the empty value doesn't throw -- it silently returns "" instead of null, violating
    // the "absent -> null" contract every other reserved-property getter in this class follows. Lenient
    // context, not strict -- see the sibling numeric-key test above for why.
    ListMultimap<String, String> info = ArrayListMultimap.create();
    info.put("AA", "");
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr", 1, null, "C", null, null, null, info, null, -1);
    String aa = position.getInfo(ReservedInfoProperty.AncestralAllele);
    assertNull(aa);
  }

  @Test
  public void testGetInfoReservedInfoPropertyEmptyValueThrowsUnderStrictContext() {
    // end-to-end: a strict VcfContext must throw VcfFormatException, not warn-and-substitute, when a reserved
    // key's value is empty -- InternalUtils.convertProperty(Class, String, boolean, VcfWarnings, VcfWarnings)'s
    // empty-value branches are isLenient()-aware, matching VcfPosition's own construction-time checks' treatment
    // of the analogous empty-entry condition. This now fires at construction, not at getInfo() read time:
    // checkReservedInfoTypes eagerly runs this exact conversion for every reserved key present in an
    // already-split ListMultimap passed directly to newPosition (manual construction only -- never reachable
    // via VcfParser, which always stays lazy).
    ListMultimap<String, String> info = ArrayListMultimap.create();
    info.put("DP", "");
    VcfFormatException ex = assertThrows(VcfFormatException.class, () ->
        STRICT_CONTEXT.newPosition("chr", 1, null, "C", null, null, null, info, null, -1));
    assertTrue(ex.getMessage().contains("is an empty entry"));
  }

  @Test
  public void testGetInfoReservedInfoPropertyAlleleCountThrowsUnderStrictContext() {
    // end-to-end: a strict VcfContext must throw VcfFormatException for a negative AC value --
    // InternalUtilsTest's own strict-throw coverage for checkNonNegativeInteger only exercises
    // checkReservedInfoConstraints/convertProperty(ReservedProperty, ...) directly, not this actual call path.
    // This now fires at construction (checkReservedInfoTypes), not at a later getInfo() call -- see
    // testGetInfoReservedInfoPropertyEmptyValueThrowsUnderStrictContext's comment for why.
    ListMultimap<String, String> info = ArrayListMultimap.create();
    info.put("AC", "-5");
    VcfFormatException ex = assertThrows(VcfFormatException.class, () ->
        STRICT_CONTEXT.newPosition("1", 100, null, "A", List.of("T"), null, null, info, null, -1));
    assertTrue(ex.getMessage().contains("is a negative number"));
  }

  @Test
  public void testGetInfoReservedPropertyViaRawInfoStillDefersThrowToReadTimeUnderStrictContext() {
    // checkReservedInfoTypes only runs on the EAGER (already-split ListMultimap) construction path -- the LAZY
    // rawInfo() path stays lazy even under a strict context, per this class's own "QUAL and INFO parse lazily
    // on first access" design (see its own Javadoc), so a bad reserved value supplied this way still only
    // throws when the typed getter is actually called, exactly as getInfo(ReservedInfoProperty)'s read-path
    // behavior always has, unaffected by the new construction-time check above.
    VcfPosition position = STRICT_CONTEXT.newPosition("1", 100, null, "A", List.of("T"), null, null, null, null, -1)
        .toBuilder().rawInfo("AC=-5").build();
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> position.getInfo(ReservedInfoProperty.AlleleCount));
    assertTrue(ex.getMessage().contains("is a negative number"));
  }

  @Test
  public void testGetInfoTwoArgThrowsOnBcfReservedSentinelUnderStrictContextForNonReservedKey() {
    // end-to-end coverage for checkInteger32BitRange's "BCF-reserved sentinel" message specifically (distinct
    // from the "outside the 32-bit signed range" overflow message already covered end-to-end elsewhere in this
    // class) -- a non-reserved key avoids FORMAT/PS's own checkNonNegative32BitInteger, which would otherwise
    // shadow this message by throwing its own "not a non-negative 32-bit integer" first (see
    // VcfSampleTest.testGetPropertyReservedFormatPropertyPhaseSetThrowsFirstViolationOnlyUnderStrictContext)
    VcfMetadata metadata = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2")
        .addInfo("INT", "custom integer", InfoType.Integer, "1", null, null).build();
    ListMultimap<String, String> info = ArrayListMultimap.create();
    info.put("INT", "-2147483648");
    VcfPosition position = STRICT_CONTEXT.newPosition("1", 100, null, "A", List.of("T"), null, null, info, null, -1);
    VcfFormatException ex = assertThrows(VcfFormatException.class, () -> position.getInfo(metadata, "INT"));
    assertTrue(ex.getMessage().contains("BCF-reserved sentinel"));
  }

  @Test
  public void testGetInfoReservedPropertyStillTreatsEmptyValueAsTrueForFlagKey() {
    // regression guard for the fix above: a Boolean-typed (Flag) reserved key's empty value is a different,
    // pre-existing, deliberate encoding (VcfPosition.info()'s own "if (idx == -1) info.put(key, \"\")" for a
    // bare flag with no "=" at all) meaning "present", not "missing" -- convertProperty's new empty-value check
    // must not swallow this into null the way it now does for every other scalar type
    ListMultimap<String, String> info = ArrayListMultimap.create();
    info.put("H2", "");
    VcfPosition position = STRICT_CONTEXT.newPosition("chr", 1, null, "C", null, null, null, info, null, -1);
    Boolean h2 = position.getInfo(ReservedInfoProperty.Hapmap2);
    assertEquals(Boolean.TRUE, h2);
  }

  @Test
  public void testGetInfoTwoArgReturnsNullForNullId() {
    VcfContext ctx = VcfContext.lenient("VCFv4.2", VcfWarnings.DEFAULT);
    VcfMetadata metadata = new VcfMetadata.Builder(ctx).setFileFormat("VCFv4.2").build();
    VcfPosition position = ctx.newPosition("1", 100, null, "A", List.of("T"), null, null, null, null, -1);
    assertNull(position.getInfo(metadata, null));
  }

  @Test
  public void testGetInfoTwoArgThrowsNpeForNullMetadata() {
    VcfContext ctx = VcfContext.lenient("VCFv4.2", VcfWarnings.DEFAULT);
    VcfPosition position = ctx.newPosition("1", 100, null, "A", List.of("T"), null, null, null, null, -1);
    assertThrows(NullPointerException.class, () -> position.getInfo(null, "AC"));
  }

  @Test
  public void testGetInfoTwoArgThrowsForMetadataBuiltUnderADifferentContext() {
    VcfContext ctx1 = VcfContext.lenient("VCFv4.2", VcfWarnings.DEFAULT);
    VcfContext ctx2 = VcfContext.lenient("VCFv4.2", VcfWarnings.DEFAULT);
    VcfMetadata metadata = new VcfMetadata.Builder(ctx2).setFileFormat("VCFv4.2").build();
    VcfPosition position = ctx1.newPosition("1", 100, null, "A", List.of("T"), null, null, null, null, -1);
    assertThrows(VcfFormatException.class, () -> position.getInfo(metadata, "AC"));
  }

  @Test
  public void testGetInfoTwoArgResolvesReservedKeyCanonicalType() {
    VcfContext ctx = VcfContext.lenient("VCFv4.2", VcfWarnings.DEFAULT);
    VcfMetadata metadata = new VcfMetadata.Builder(ctx).setFileFormat("VCFv4.2").build();
    ListMultimap<String, String> info = ArrayListMultimap.create();
    info.put("DP", "10");
    VcfPosition position = ctx.newPosition("1", 100, null, "A", List.of("T"), null, null, info, null, -1);
    Long value = position.getInfo(metadata, "DP");
    assertEquals(10L, value);
  }

  @Test
  public void testGetInfoTwoArgResolvesNonReservedKeyDeclaredType() {
    VcfContext ctx = VcfContext.lenient("VCFv4.2", VcfWarnings.DEFAULT);
    VcfMetadata metadata = new VcfMetadata.Builder(ctx).setFileFormat("VCFv4.2")
        .addInfo("INT", "custom integer", InfoType.Integer, "1", null, null).build();
    ListMultimap<String, String> info = ArrayListMultimap.create();
    info.put("INT", "5");
    VcfPosition position = ctx.newPosition("1", 100, null, "A", List.of("T"), null, null, info, null, -1);
    Long value = position.getInfo(metadata, "INT");
    assertEquals(5L, value);
  }

  @Test
  public void testGetInfoTwoArgWarnsOnOverflowUnderLenientContextForNonReservedKey() {
    StringWriter sink = new StringWriter();
    VcfContext ctx = VcfContext.lenient("VCFv4.2", new VcfWarnings(sink));
    VcfMetadata metadata = new VcfMetadata.Builder(ctx).setFileFormat("VCFv4.2")
        .addInfo("INT", "custom integer", InfoType.Integer, "1", null, null).build();
    ListMultimap<String, String> info = ArrayListMultimap.create();
    info.put("INT", "2147483648");
    VcfPosition position = ctx.newPosition("1", 100, null, "A", List.of("T"), null, null, info, null, -1);
    Long value = position.getInfo(metadata, "INT");
    assertEquals(2147483648L, value);
    assertTrue(sink.toString().contains("outside the 32-bit signed range"));
  }

  @Test
  public void testGetInfoTwoArgWarnsOnReservedSentinelForNonReservedKey() {
    StringWriter sink = new StringWriter();
    VcfContext ctx = VcfContext.lenient("VCFv4.2", new VcfWarnings(sink));
    VcfMetadata metadata = new VcfMetadata.Builder(ctx).setFileFormat("VCFv4.2")
        .addInfo("INT", "custom integer", InfoType.Integer, "1", null, null).build();
    ListMultimap<String, String> info = ArrayListMultimap.create();
    info.put("INT", "-2147483648");
    VcfPosition position = ctx.newPosition("1", 100, null, "A", List.of("T"), null, null, info, null, -1);
    Long value = position.getInfo(metadata, "INT");
    assertEquals(-2147483648L, value);
    assertTrue(sink.toString().contains("BCF-reserved sentinel"));
  }

  @Test
  public void testGetInfoTwoArgThrowsOnOverflowUnderStrictContextForNonReservedKey() {
    VcfContext ctx = VcfContext.strict("VCFv4.2");
    VcfMetadata metadata = new VcfMetadata.Builder(ctx).setFileFormat("VCFv4.2")
        .addInfo("INT", "custom integer", InfoType.Integer, "1", null, null).build();
    ListMultimap<String, String> info = ArrayListMultimap.create();
    info.put("INT", "2147483648");
    VcfPosition position = ctx.newPosition("1", 100, null, "A", List.of("T"), null, null, info, null, -1);
    VcfFormatException ex = assertThrows(VcfFormatException.class, () -> position.getInfo(metadata, "INT"));
    assertTrue(ex.getMessage().contains("outside the 32-bit signed range"));
  }

  @Test
  public void testGetInfoReservedInfoPropertyAlleleFrequencyThrowsUnderStrictContext() {
    // a strict VcfContext must throw VcfFormatException for an out-of-[0,1]-range AF value -- InternalUtilsTest's
    // own strict-throw coverage for checkUnitInterval only exercises the package-private
    // checkReservedInfoConstraints helper directly, not this actual call path. Fires at construction now, not at
    // a later getInfo() call -- see testGetInfoReservedInfoPropertyEmptyValueThrowsUnderStrictContext's comment.
    ListMultimap<String, String> info = ArrayListMultimap.create();
    info.put("AF", "-0.01");
    VcfFormatException ex = assertThrows(VcfFormatException.class, () ->
        STRICT_CONTEXT.newPosition("1", 100, null, "A", List.of("T"), null, null, info, null, -1));
    assertTrue(ex.getMessage().contains("does not lie in the interval [0, 1]"));
  }

  @Test
  public void testGetInfoReservedInfoPropertyCigarThrowsUnderStrictContext() {
    // a strict VcfContext must throw VcfFormatException for a grammar-invalid CIGAR value -- InternalUtilsTest's
    // own strict-throw coverage for checkCigarGrammar only exercises the package-private
    // checkReservedInfoConstraints helper directly, not this actual call path. Fires at construction now, not at
    // a later getInfo() call -- see testGetInfoReservedInfoPropertyEmptyValueThrowsUnderStrictContext's comment.
    ListMultimap<String, String> info = ArrayListMultimap.create();
    info.put("CIGAR", "123MYCIGAR");
    VcfFormatException ex = assertThrows(VcfFormatException.class, () ->
        STRICT_CONTEXT.newPosition("1", 100, null, "A", List.of("T"), null, null, info, null, -1));
    assertTrue(ex.getMessage().contains("does not match the expected CIGAR grammar"));
  }

  @Test
  public void testStrictConstructorThrowsForEmptyFormatSubfield() {
    List<String> format = new java.util.ArrayList<>(List.of("GT", "", "DP"));
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> STRICT_CONTEXT.newPosition("1", 100, null, "A", null, null, null, null, format, -1));
    assertTrue(ex.getMessage().contains("empty sub-field name"));
  }

  @Test
  public void testStrictConstructorThrowsForInvalidFormatKeyGrammar() {
    // "1KG" starts with a digit, failing InternalUtils.FORMAT_PATTERN
    List<String> format = new java.util.ArrayList<>(List.of("GT", "1KG"));
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> STRICT_CONTEXT.newPosition("1", 100, null, "A", null, null, null, null, format, -1));
    assertTrue(ex.getMessage().contains("tag-name grammar"));
  }

  @Test
  public void testLenientConstructorWarnsInsteadOfThrowingForInvalidFormatKeyGrammar() {
    // regression test for findings37.md item 1: this check previously had no lenient path at all -- a single
    // non-compliant FORMAT key aborted the whole parse regardless of which constructor was used. Confirmed
    // against bcftools 1.23.1 (VCF_SPEC_AMBIGUITIES.md, "Data-line FORMAT key fails tag-name grammar" entry)
    // that a warn, not an abort, is the correct behavior.
    StringWriter sink = new StringWriter();
    List<String> format = new java.util.ArrayList<>(List.of("GT", "1KG"));
    VcfPosition position = lenientContext(new VcfWarnings(sink)).newPosition("1", 100, null, "A", null, null, null,
        null, format, -1);
    assertEquals(List.of("GT", "1KG"), position.getFormat());
    assertTrue(sink.toString().contains("tag-name grammar"));
  }

  @Test
  public void testLenientConstructorWarnsInsteadOfThrowingForSpaceInFormatKey() {
    // pins the boundary the tab/line-terminator check deliberately does NOT cross: a plain space is a
    // tag-name-grammar violation like any other (a leading digit, illegal punctuation), not a structural one
    // -- confirmed live (bcftools 1.23.1) that a space-corrupted FORMAT key ("A B", the same shape hts-specs'
    // own test/vcf/4.3/failed/failed_body_format_001.vcf uses) only produces "[W::vcf_parse_format_dict2]
    // FORMAT 'A B' is not defined in the header"/"[W::bcf_hrec_check] Invalid tag name", exit 0, no column
    // shift, no data loss -- unlike a tab, which fails the file outright. This must keep warning, not throw,
    // under lenient.
    StringWriter sink = new StringWriter();
    List<String> format = new java.util.ArrayList<>(List.of("GT", "A B"));
    VcfPosition position = lenientContext(new VcfWarnings(sink)).newPosition("1", 100, null, "A", null, null, null,
        null, format, -1);
    assertEquals(List.of("GT", "A B"), position.getFormat());
    assertTrue(sink.toString().contains("tag-name grammar"));
  }

  @Test
  public void testLenientConstructorWarnsBothGrammarAndDuplicateForTwoInvalidFormatKeys() {
    // regression test: a grammar-invalid key skips checkFormat's own unconditional-throw isDuplicateAt call
    // (that check is deliberately fatal, too severe for content already downgraded to a warning) -- but a
    // grammar-invalid duplicate is not silently un-flagged as a duplicate altogether: a second, warn-only
    // isDuplicateAt check right alongside the grammar warning now catches it too, eagerly, rather than
    // leaving it to be discovered only if a caller later reads that sample's properties (which independently,
    // lazily, warns "Sample contains duplicate FORMAT key" via VcfSample.properties() regardless). Two
    // identical malformed keys warn about BOTH the grammar violation (twice, once per occurrence) and the
    // duplicate (once, for the second occurrence).
    StringWriter sink = new StringWriter();
    List<String> format = new java.util.ArrayList<>(List.of("GT", "1KG", "1KG"));
    VcfPosition position = lenientContext(new VcfWarnings(sink)).newPosition("1", 100, null, "A", null, null, null,
        null, format, -1);
    assertEquals(List.of("GT", "1KG", "1KG"), position.getFormat());
    assertEquals(2, org.apache.commons.lang3.StringUtils.countMatches(sink.toString(), "tag-name grammar"));
    assertTrue(sink.toString().contains("Duplicate FORMAT key \"1KG\""));
  }

  @Test
  public void testStrictConstructorThrowsForEmptyIdEntry() {
    List<String> ids = new java.util.ArrayList<>(List.of("rs1", ""));
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> STRICT_CONTEXT.newPosition("1", 100, ids, "A", null, null, null, null, null, -1));
    assertTrue(ex.getMessage().contains("empty entry"));
  }

  @Test
  public void testConstructorRejectsNullContext() {
    // the warnings sink (and the strict-vs-lenient decision) now arrive via VcfContext, so this is where the
    // former "lenient constructor rejects a null VcfWarnings" guarantee lives. VcfContext's own constructor
    // still rejects a null sink -- see VcfContextTest.
    assertThrows(NullPointerException.class, () -> new VcfPosition(null, "1", 100, null, "A", null, null,
        List.of("PASS", "q10"), null, null, -1, false, false, null, null));
  }

  @Test
  public void testLenientConstructorWarnsInsteadOfThrowingForPassCombinedWithOtherFilters() {
    StringWriter sink = new StringWriter();
    VcfPosition position = lenientContext(new VcfWarnings(sink)).newPosition("1", 100, null, "A", null, null,
        List.of("PASS", "q10"), null, null, -1);
    assertEquals(List.of("PASS", "q10"), position.getFilters());
    assertTrue(sink.toString().contains("PASS along with other filters"));
  }

  @Test
  public void testLenientConstructorWarnsInsteadOfThrowingForDuplicateId() {
    StringWriter sink = new StringWriter();
    VcfPosition position = lenientContext(new VcfWarnings(sink)).newPosition("1", 100, List.of("rs1", "rs1"), "A", null,
        null, null, null, null, -1);
    assertEquals(List.of("rs1", "rs1"), position.getIds());
    assertTrue(sink.toString().contains("Duplicate ID"));
  }

  @Test
  public void testLenientConstructorWarnsForNonAdjacentDuplicateId() {
    // regression test: isDuplicateAt (an allocation-free scan added to replace a per-call HashSet<String>)
    // must scan ALL earlier elements, not just the immediately preceding one -- "rs1" and "rs2" are not
    // adjacent to their duplicate, so a scan that only compared consecutive elements would miss this
    StringWriter sink = new StringWriter();
    VcfPosition position = lenientContext(new VcfWarnings(sink)).newPosition("1", 100, List.of("rs1", "rs2", "rs1"),
        "A", null, null, null, null, null, -1);
    assertEquals(List.of("rs1", "rs2", "rs1"), position.getIds());
    assertTrue(sink.toString().contains("Duplicate ID \"rs1\""));
  }

  @Test
  public void testLenientConstructorHandlesManyIdsWithoutQuadraticSlowdown() {
    // regression test: isDuplicateAt's allocation-free scan is O(n) per call (O(n^2) overall) -- fine for a
    // typically-tiny ID list, but ID/FILTER/FORMAT are delimiter-separated lists inside a single column whose
    // element count is bounded only by line length, i.e. attacker-controlled and unbounded, the same DoS shape
    // as the already-fixed ##PEDIGREE O(n^2) issue in VcfMetadata.Builder.addPedigree. An earlier version of
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
      VcfPosition position = lenientContext(new VcfWarnings(sink)).newPosition("1", 100, ids, "A", null, null, null,
          null, null, -1);
      assertEquals(50_000, position.getIds().size());
      assertEquals("", sink.toString());
    });
  }

  @Test
  public void testStrictConstructorThrowsForDuplicateFilterCode() {
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> STRICT_CONTEXT.newPosition("1", 100, null, "A", null, null, List.of("hp23", "hp23"), null, null, -1));
    assertTrue(ex.getMessage().contains("Duplicate FILTER code"));
  }

  @Test
  public void testConstructorThrowsForNonAdjacentDuplicateFormatKey() {
    // regression test for checkFormat's own isDuplicateAt-based duplicate check (no prior test existed for a
    // duplicate FORMAT key at all) -- "DP" and "DP" are not adjacent, proving the scan checks all earlier
    // elements, not just the immediately preceding one
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> STRICT_CONTEXT.newPosition("1", 100, null, "A", null, null, null, null, List.of("DP", "AD", "DP"), -1));
    assertTrue(ex.getMessage().contains("Duplicate FORMAT key \"DP\""));
  }

  @Test
  public void testNewWithTrustedFormatSkipsFormatValidationThatThePublicConstructorEnforces() {
    // regression/contract test: newWithTrustedFormat() is VcfParser's own per-record FORMAT-cache fast path --
    // it must skip checkFormat's validation entirely, unlike the public constructor, which must still reject
    // the identical malformed list ("DP" duplicated). This pins the actual difference in behavior, not just
    // that newWithTrustedFormat() doesn't crash.
    List<String> duplicateFormat = List.of("DP", "AD", "DP");
    assertThrows(VcfFormatException.class,
        () -> LENIENT_CONTEXT.newPosition("1", 100, null, "A", null, null, null, null, duplicateFormat, -1));
    VcfPosition trusted = LENIENT_CONTEXT.newPositionWithTrustedFormat("1", 100, null, "A", null, null, null, null,
        duplicateFormat, -1);
    assertEquals(duplicateFormat, trusted.getFormat());
  }

  @Test
  public void testNewWithTrustedFormatProducesEquivalentPositionToPublicConstructorForWellFormedInput() {
    // for input the public constructor would accept anyway (VcfParser's actual, already-validated cache
    // content), newWithTrustedFormat() must produce an observably identical FORMAT list
    List<String> format = List.of("GT", "DP", "AD");
    VcfPosition trusted = LENIENT_CONTEXT.newPositionWithTrustedFormat("1", 100, null, "A", null, null, null, null,
        format, -1);
    VcfPosition untrusted = LENIENT_CONTEXT.newPosition("1", 100, null, "A", null, null, null, null, format, -1);
    assertEquals(untrusted.getFormat(), trusted.getFormat());
  }

  @Test
  public void testLenientConstructorWarnsInsteadOfThrowingForDuplicateFilterCode() {
    StringWriter sink = new StringWriter();
    VcfPosition position = lenientContext(new VcfWarnings(sink)).newPosition("1", 100, null, "A", null, null,
        List.of("hp23", "hp23"), null, null, -1);
    assertEquals(List.of("hp23", "hp23"), position.getFilters());
    assertTrue(sink.toString().contains("Duplicate FILTER code"));
  }

  @Test
  public void testGetQualityThrowsRegardlessOfLenientContextForNonNumericValue() {
    // getQuality()'s unconditional throw for an unparseable value is NOT isLenient()-gated (unlike a
    // cardinality/range mismatch elsewhere in this library) -- a lenient context does not turn this into a
    // warning, since there is no sane value to preserve or normalize to and the raw text remains available
    // via getRawQuality() either way. VcfParser itself is unaffected: it never calls getQuality() during
    // parse(), so parsing a real file stays fully lenient regardless of this method's own throw.
    VcfPosition position = lenientContext(new VcfWarnings(new StringWriter())).newPosition("1", 100, "A", null, -1)
        .toBuilder().rawQuality("not-a-number").build();
    VcfFormatException ex = assertThrows(VcfFormatException.class, position::getQuality);
    assertTrue(ex.getMessage().contains("is not a number"));
  }

  @Test
  public void testGetQualityExceptionIncludesLineNumberAndCause() {
    // regression test: getQuality()'s deferred-parse exception previously threw a bare
    // new VcfFormatException(String) with no line number and no cause, unlike its sibling deferred getters
    // (info()/getInfo()) after the 225659c refactor extracted withLineNumber() -- this was the one remaining
    // call site that refactor didn't reach (it wasn't one of the five it was scoped to). Losing the cause also
    // flattened a more specific underlying diagnosis (e.g. VcfFloat's own "too long to be a valid VCF Float")
    // down to this method's generic "is not a number" message.
    VcfPosition position = lenientContext(new VcfWarnings(new StringWriter())).newPosition("1", 100, "A", null, 42)
        .toBuilder().rawQuality("not-a-number").build();
    VcfFormatException ex = assertThrows(VcfFormatException.class, position::getQuality);
    assertEquals(42, ex.getLineNumber());
    assertNotNull(ex.getCause());
  }

  @Test
  public void testValidateAlwaysWarnsRegardlessOfOriginalConstructor() {
    // regression test: a recoverable condition validate() (or the lazy info() parse it forces) re-checks always
    // warns -- via whatever m_warnings currently is -- never throws, because that path passes m_warnings
    // unconditionally rather than deriving strict-vs-lenient from the context the way the eager constructor path
    // does. Contrast with identical content (an empty INFO key) reaching the EAGER constructor directly under
    // the same strict context, which throws -- see testStrictConstructorThrowsForInfoEmptyKey.
    //
    // VcfPosition has no mutation route, so the only way left to reach this asymmetry is Builder#rawInfo(): raw
    // INFO text is never validated at construction or build() time (only at first access, e.g. getInfo(), or via
    // this method) -- see validate()'s own Javadoc -- so a strict-context position can still hold unvalidated
    // raw INFO after build() returns. Task 7: setWarningsSink was removed, and VcfContext.strict(...) has no way
    // to supply a custom VcfWarnings (it always uses VcfWarnings.DEFAULT), so this observes the warning via the
    // shared SLF4J logger instead of a redirected sink -- that's still the right way to pin this, since
    // switching construction to a custom lenientContext(...) would lose the strict-construction contrast this
    // test is actually about.
    VcfPosition position = STRICT_CONTEXT.newPosition("1", 100, null, "A", null, null, null, null, null, -1)
        .toBuilder().rawInfo("=orphan").build();
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      assertDoesNotThrow(position::validate);
      assertTrue(appender.anyMessageContains("has an empty key"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testToBuilderCopiesEveryFieldAndPreservesContext() {
    VcfPosition original = STRICT_CONTEXT.newPosition("chr1", 100, List.of("rs1"), "A", List.of("T"),
        VcfFloat.of(new BigDecimal("50")), List.of("PASS"), null, List.of("GT"), 7);
    VcfPosition copy = original.toBuilder().build();
    assertEquals(original.getChromosome(), copy.getChromosome());
    assertEquals(original.getPosition(), copy.getPosition());
    assertEquals(original.getIds(), copy.getIds());
    assertEquals(original.getRef(), copy.getRef());
    assertEquals(original.getAltBases(), copy.getAltBases());
    assertEquals(original.getQuality(), copy.getQuality());
    assertEquals(original.getFilters(), copy.getFilters());
    assertEquals(original.getFormat(), copy.getFormat());
    assertSame(original.getContext(), copy.getContext());
  }

  @Test
  public void testToBuilderChangeOneFieldLeavesSourceUntouched() {
    VcfPosition original = STRICT_CONTEXT.newPosition("chr1", 100, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("50")),
        null, null, null, -1);
    VcfPosition modified = original.toBuilder().quality(VcfFloat.of(BigDecimal.ZERO)).build();
    assertEquals(VcfFloat.of(new BigDecimal("50")), original.getQuality());
    assertEquals(VcfFloat.of(BigDecimal.ZERO), modified.getQuality());
    assertEquals("chr1", modified.getChromosome());
  }

  @Test
  public void testBuilderSettersDefensivelyCopyEveryMutableInput() {
    VcfPosition original = STRICT_CONTEXT.newPosition("chr1", 100, null, "A", List.of("T"), null, null, null, null,
        -1);
    List<String> ids = new ArrayList<>(List.of("rs1"));
    List<String> alts = new ArrayList<>(List.of("T"));
    List<String> filters = new ArrayList<>(List.of("PASS"));
    List<String> format = new ArrayList<>(List.of("GT"));
    ListMultimap<String, String> info = ArrayListMultimap.create();
    info.put("NS", "3");
    VcfPosition copy = original.toBuilder().ids(ids).altBases(alts).filters(filters).format(format).info(info)
        .build();
    ids.add("rs2");
    alts.add("G");
    filters.add("q10");
    format.add("DP");
    info.put("NS", "4");
    assertEquals(List.of("rs1"), copy.getIds());
    assertEquals(List.of("T"), copy.getAltBases());
    assertEquals(List.of("PASS"), copy.getFilters());
    assertEquals(List.of("GT"), copy.getFormat());
    assertEquals(List.of("3"), copy.getInfo().get("NS"));
  }

  @Test
  public void testToBuilderPreservesRawQualityAndInfoWithoutMaterializingOrWarning() {
    // both QUAL and INFO are left as raw, not-yet-parsed text (the parser's own construction shape) -- QUAL's
    // raw text is even unparseable, so materializing it via getQuality() WOULD emit a warning if toBuilder()
    // read through the getter instead of the raw field directly (see toBuilder()'s own Javadoc)
    StringWriter sink = new StringWriter();
    VcfPosition parsed = lenientContext(new VcfWarnings(sink)).newPosition("chr1", 100, "A", null, -1)
        .toBuilder().rawQuality("not-a-number").rawInfo("NS=3").build();
    assertEquals("", sink.toString(), "attaching raw QUAL/INFO via the builder must not materialize or warn");
    VcfPosition copy = parsed.toBuilder().build();
    assertEquals("", sink.toString(), "copying alone must not materialize or warn");
    // both resolve identically once actually read -- QUAL throws the same way on both (see getQuality()'s own
    // Javadoc), and getRawQuality() confirms the preserved raw text itself matches too
    assertThrows(VcfFormatException.class, parsed::getQuality);
    assertThrows(VcfFormatException.class, copy::getQuality);
    assertEquals(parsed.getRawQuality(), copy.getRawQuality());
    assertEquals(parsed.getInfo(), copy.getInfo());
  }

  @Test
  public void testBuilderChromosomeAndRefRejectNullInsteadOfThrowingNpe() {
    VcfPosition.Builder builder = STRICT_CONTEXT.newPosition("chr1", 100, "A", null, -1).toBuilder();
    assertThrows(VcfFormatException.class, () -> builder.chromosome(null));
    assertThrows(VcfFormatException.class, () -> builder.ref(null));
  }

  @Test
  public void testToBuilderBuildEmitsNoDiagnosticsForFieldsUnchangedSinceAnAlreadyWarnedConstruction() {
    // constructing with a duplicate ID, a duplicate FILTER code, and a grammar-invalid FORMAT key each warns
    // once; copying that already-warned, otherwise-unchanged state via toBuilder() must not re-emit any of the
    // three a second time -- the source already passed (recovered from) each check once
    StringWriter sink = new StringWriter();
    VcfPosition original = lenientContext(new VcfWarnings(sink)).newPosition("chr1", 100,
        new ArrayList<>(List.of("rs1", "rs1")), "A", null, null, new ArrayList<>(List.of("q10", "q10")), null,
        new ArrayList<>(List.of("1BAD")), -1);
    assertTrue(sink.toString().contains("Duplicate ID"), sink::toString);
    assertTrue(sink.toString().contains("Duplicate FILTER code"), sink::toString);
    assertTrue(sink.toString().contains("does not match"), sink::toString);
    sink.getBuffer().setLength(0);
    VcfPosition copy = original.toBuilder().build();
    assertEquals("", sink.toString(), "copying alone must not re-emit a diagnostic the source already emitted");
    assertEquals(List.of("rs1", "rs1"), copy.getIds());
    assertEquals(List.of("q10", "q10"), copy.getFilters());
    assertEquals(List.of("1BAD"), copy.getFormat());
  }

  @Test
  public void testToBuilderBuildRevalidatesOnlyTheFieldASetterActuallyChanged() {
    // ids is unchanged (must not re-warn about its pre-existing duplicate); filters is explicitly re-set to a
    // freshly-invalid value via the builder's own setter (must be validated exactly as a fresh construction
    // would be, i.e. still warn)
    StringWriter sink = new StringWriter();
    VcfPosition original = lenientContext(new VcfWarnings(sink)).newPosition("chr1", 100,
        new ArrayList<>(List.of("rs1", "rs1")), "A", null, null, null, null, null, -1);
    sink.getBuffer().setLength(0);
    VcfPosition copy = original.toBuilder().filters(new ArrayList<>(List.of("q10", "q10"))).build();
    assertFalse(sink.toString().contains("Duplicate ID"), sink::toString);
    assertTrue(sink.toString().contains("Duplicate FILTER code"), sink::toString);
    assertEquals(List.of("q10", "q10"), copy.getFilters());
  }

  // --- Task 4 / D4 regression tests: see VcfPercentEncodingTest for the codec's own unit tests.

  @Test
  public void testInfoValueContainingRawSemicolonOrCommaStillRejected() {
    // pins VcfPosition.checkInfoEntries's existing guard: percent-encoding is an opt-in caller utility, never a
    // parse/write-time escape mechanism, so a raw structural delimiter in an INFO value is still a hard
    // rejection, not something that could now be represented "encoded" behind the scenes. Unconditional
    // (VcfFormatException) regardless of context leniency -- this is a structural violation, not content
    // leniency -- so both a strict and lenient context are checked here.
    ListMultimap<String, String> semicolon = ArrayListMultimap.create();
    semicolon.put("XY", "a;b");
    assertThrows(VcfFormatException.class,
        () -> STRICT_CONTEXT.newPosition("chr1", 1, null, "A", null, null, null, semicolon, null, -1));
    assertThrows(VcfFormatException.class,
        () -> LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", null, null, null, semicolon, null, -1));

    ListMultimap<String, String> comma = ArrayListMultimap.create();
    comma.put("XY", "a,b");
    assertThrows(VcfFormatException.class,
        () -> STRICT_CONTEXT.newPosition("chr1", 1, null, "A", null, null, null, comma, null, -1));
    assertThrows(VcfFormatException.class,
        () -> LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", null, null, null, comma, null, -1));
  }

  @Test
  public void testGetInfoIdenticalUnder42And43ResolvedContexts() {
    // no getter may return a different value for identical raw input just because the resolved VcfVersion
    // differs -- there is no version-dependent percent-decoding (or any other transformation) to diverge on
    ListMultimap<String, String> info42 = ArrayListMultimap.create();
    info42.put("XY", "a%3Bb%03Ac%41d");
    VcfPosition pos42 = VcfContext.strict("VCFv4.2").newPosition("chr1", 1, null, "A", null, null, null, info42,
        null, -1);

    ListMultimap<String, String> info43 = ArrayListMultimap.create();
    info43.put("XY", "a%3Bb%03Ac%41d");
    VcfPosition pos43 = VcfContext.strict("VCFv4.3").newPosition("chr1", 1, null, "A", null, null, null, info43,
        null, -1);

    assertEquals(pos42.getInfo(), pos43.getInfo());
    assertEquals(pos42.getInfo("XY"), pos43.getInfo("XY"));
  }

  /**
   * Every character {@code InternalUtils.containsWhitespace} matches except U+0020 SPACE -- i.e. exactly the
   * set an INFO value must still reject after the space relaxation. Kept exhaustive on purpose: a partial set
   * is what would let an implementation that dropped the value-side check outright still pass.
   */
  private static final char[] NON_SPACE_WHITESPACE = { '\t', '\n', '\u000B', '\f', '\r' };

  @Test
  public void testInfoValueAcceptsSpace() {
    // VCFv4.3.tex line 366 ("Space characters are allowed in values", verbatim at VCFv4.4.tex line 382 and
    // VCFv4.5.tex line 431) relaxes VCFv4.2.tex line 186's blanket "no whitespace, semicolons, or equals-signs
    // permitted". This library adopts that relaxation unconditionally rather than gating it on the declared
    // version -- STRICT_CONTEXT and LENIENT_CONTEXT are both VCFv4.2 contexts, so both must accept it. See
    // VALIDATION_REFERENCE.md's "Rules applied at every declared version".
    for (VcfContext context : List.of(STRICT_CONTEXT, LENIENT_CONTEXT)) {
      ListMultimap<String, String> info = ArrayListMultimap.create();
      info.put("AA", "a b");
      VcfPosition position = context.newPosition("chr1", 1, null, "A", null, null, null, info, null, -1);
      assertEquals(Collections.singletonList("a b"), position.getInfo("AA"));
    }
  }

  @Test
  public void testInfoValueRejectsWhitespaceOtherThanSpace() {
    // The relaxation above is U+0020-only: the spec says "space characters", not whitespace, and no later
    // version widens it. The other five are structural, not merely non-conformant -- VcfWriter.addInfoOrDot
    // writes an INFO value straight into the record column, so a tab adds a column and an LF/CR ends the
    // record, and nothing downstream catches either. They therefore stay an UNCONDITIONAL throw (LENIENT_CONTEXT
    // here, not STRICT_CONTEXT, pins that it is not warn-and-recover), per AGENTS.md's Parser Validation Policy.
    for (char c : NON_SPACE_WHITESPACE) {
      String label = String.format("U+%04X", (int) c);
      ListMultimap<String, String> info = ArrayListMultimap.create();
      info.put("AA", "a" + c + "b");

      // route 1: the public factory
      VcfFormatException fromFactory = assertThrows(VcfFormatException.class,
          () -> LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", null, null, null, info, null, -1), label);
      assertTrue(fromFactory.getMessage().contains("contains whitespace other than a space"), label);

      // route 2: VcfPosition.Builder#info, which re-runs checkInfoEntries at build() time
      VcfPosition clean = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", null, null, null, null, null, -1);
      VcfFormatException fromBuilder = assertThrows(VcfFormatException.class,
          () -> clean.toBuilder().info(info).build(), label);
      assertTrue(fromBuilder.getMessage().contains("contains whitespace other than a space"), label);
    }
  }

  @Test
  public void testInfoKeyRejectsEveryWhitespaceCharacterIncludingSpace() {
    // Companion to testInfoValueRejectsWhitespaceOtherThanSpace: the value-side relaxation must not have
    // widened to the key. VCFv4.3.tex line 368's INFO key grammar (^([A-Za-z_][0-9A-Za-z_.]*|1000G)$, unchanged
    // at VCFv4.4.tex line 384 and VCFv4.5.tex line 433) excludes every whitespace character from the key's own
    // character class, and VCFv4.2.tex line 186 forbids it too -- so a space in a KEY is still rejected. The
    // assertFalse pins WHICH branch fired: the key-side message has no "other than a space" qualifier.
    for (char c : new char[] { ' ', '\t', '\n', '\u000B', '\f', '\r' }) {
      String label = String.format("U+%04X", (int) c);
      ListMultimap<String, String> info = ArrayListMultimap.create();
      info.put("A" + c + "A", "1");
      VcfFormatException ex = assertThrows(VcfFormatException.class,
          () -> LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", null, null, null, info, null, -1), label);
      assertTrue(ex.getMessage().contains("contains whitespace"), label);
      assertFalse(ex.getMessage().contains("other than a space"), label);
    }
  }

}
