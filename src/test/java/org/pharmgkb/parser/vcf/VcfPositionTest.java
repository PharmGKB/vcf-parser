package org.pharmgkb.parser.vcf;

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
    // PASS combined with another code now warns, not throws (see VcfPosition.checkFilters) -- hts-specs' own
    // passed/complexfile_passed_000.vcf fixture (all versions) does exactly this and is expected to parse
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfPosition position;
    try {
      position = new VcfPosition("chr", 1, null, "C", null, null, Arrays.asList("bad", "PASS"),
          null, null, -1);
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertEquals(Arrays.asList("bad", "PASS"), position.getFilters());
  }

  @Test
  public void testEmptyIdEntriesDropped() {
    // VCF does not allow zero-length fields; ID is a position-independent list, so an empty entry is dropped rather
    // than throwing (see EMPTY_FIELD_HANDLING.md)
    VcfPosition interior = new VcfPosition("chr", 1, new ArrayList<>(Arrays.asList("rs1", "", "rs2")), "C", null, null,
        null, null, null, -1);
    assertEquals(Arrays.asList("rs1", "rs2"), interior.getIds());

    VcfPosition trailing = new VcfPosition("chr", 1, new ArrayList<>(Arrays.asList("rs3", "")), "C", null, null,
        null, null, null, -1);
    assertEquals(Collections.singletonList("rs3"), trailing.getIds());
  }

  @Test
  public void testEmptyFilterEntriesDropped() {
    VcfPosition interior = new VcfPosition("chr", 1, null, "C", null, null,
        new ArrayList<>(Arrays.asList("q10", "", "q20")), null, null, -1);
    assertEquals(Arrays.asList("q10", "q20"), interior.getFilters());

    VcfPosition trailing = new VcfPosition("chr", 1, null, "C", null, null,
        new ArrayList<>(Arrays.asList("q10", "")), null, null, -1);
    assertEquals(Collections.singletonList("q10"), trailing.getFilters());
  }

  @Test
  public void testEmptyAltEntriesSubstitutedWithDot() {
    // ALT is position-dependent (GT indexes into it -- see testGetAlleleAfterAltDotSubstitutionPreservesIndex),
    // so an empty entry is substituted with '.', not dropped, unlike ID/FILTER above
    VcfPosition interior = new VcfPosition("chr", 1, null, "C",
        new ArrayList<>(Arrays.asList("T", "", "G")), null, null, null, null, -1);
    assertEquals(Arrays.asList("T", ".", "G"), interior.getAltBases());

    VcfPosition trailing = new VcfPosition("chr", 1, null, "C",
        new ArrayList<>(Arrays.asList("T", "")), null, null, null, null, -1);
    assertEquals(Arrays.asList("T", "."), trailing.getAltBases());
  }

  @Test
  public void testGetAlleleAfterAltDotSubstitutionPreservesIndex() {
    // regression test for the actual bug: dropping an empty ALT entry (the old behavior) shifted every subsequent
    // allele's index down by one, so GT=4 (meaning "G") would silently resolve to the wrong allele after
    // normalization instead of throwing or staying correct. Substituting '.' instead preserves every index --
    // confirmed against bcftools 1.23.1, which does the same thing (see EMPTY_FIELD_HANDLING.md)
    VcfPosition position = new VcfPosition("chr1", 1, null, "C",
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
    // to FORMAT keys by index, and dropping this key would misalign every sample's values with the wrong key
    VcfPosition position = new VcfPosition("chr", 1, null, "C", null, null, null, null,
        new ArrayList<>(Arrays.asList("GT", "", "GQ")), -1);
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
    // in the sense the spec means
    VcfPosition position = new VcfPosition("chr", 1, null, "C", null, null, null, null,
        new ArrayList<>(Arrays.asList("GT", "", "")), -1);
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
    position.getIds().add("rs1"); // duplicate ID
    assertThrows(VcfFormatException.class, position::validate);
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
    // regression test: this is the original bug findings19.md item 1 investigated -- clearing a position that
    // actually FAILED a filter (not one that started as NONE) previously left a stale "true" default behind and
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
    // UnsupportedOperationException
    List<String> immutable = List.of("T", "");
    VcfPosition position = new VcfPosition("chr1", 1, null, "C", immutable, null, null, null, null, -1);
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

}
