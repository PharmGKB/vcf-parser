package org.pharmgkb.parser.vcf;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import org.junit.jupiter.api.Test;
import org.pharmgkb.parser.vcf.model.ReservedInfoProperty;
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

    // an invalid QUAL is validated lazily: setting it does not throw, reading it does
    VcfPosition bad = newPosition();
    bad.setRawQuality("not-a-number");
    assertThrows(VcfFormatException.class, bad::getQuality);

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
  public void testGetInfoMappingQuality() {
    // MQ is a float reserved property; getInfo must convert it (it was previously typed Float.class, which threw)
    VcfPosition p = newPosition();
    p.setRawInfo("MQ=52.4");
    assertEquals(new BigDecimal("52.4"), p.getInfo(ReservedInfoProperty.MappingQuality));
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

  private static VcfPosition newPosition() {
    return new VcfPosition("chr", 1, null, "C", null, null, null, null, null);
  }

  @Test
  public void testGetAllele() {
    VcfPosition position = new VcfPosition("chr1", 1, null, "C", new ArrayList<>(Arrays.asList("A", "T")), null, null,
        null, null);
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
    VcfPosition position = new VcfPosition("chr1", 1, "C", new BigDecimal("0.0"));
    assertEquals("C", position.getAllele(0));
    assertThrows(IndexOutOfBoundsException.class, () -> position.getAllele(1));
  }

  @Test
  public void testFilterStatus() {
    VcfPosition none = new VcfPosition("chr1", 1, null, "C", null, null, Collections.singletonList("."),
        null, null);
    assertEquals(VcfPosition.FilterStatus.NONE, none.getFilterStatus());
    assertTrue(none.isPassingAllFilters()); // no filters applied is treated as passing
    assertTrue(none.getFilters().isEmpty());

    VcfPosition passed = new VcfPosition("chr1", 1, null, "C", null, null, Collections.singletonList("PASS"),
        null, null);
    assertEquals(VcfPosition.FilterStatus.PASSED, passed.getFilterStatus());
    assertTrue(passed.isPassingAllFilters());

    VcfPosition noFilters = new VcfPosition("chr1", 1, null, "C", null, null, null, null, null);
    assertEquals(VcfPosition.FilterStatus.PASSED, noFilters.getFilterStatus());

    VcfPosition failed = new VcfPosition("chr1", 1, null, "C", null, null, Collections.singletonList("q10"),
        null, null);
    assertEquals(VcfPosition.FilterStatus.FAILED, failed.getFilterStatus());
    assertFalse(failed.isPassingAllFilters());
    assertEquals(Collections.singletonList("q10"), failed.getFilters());
  }

  @Test
  public void testBadFilterDotWithOthers() {
    assertThrows(VcfFormatException.class, () ->
        new VcfPosition("chr1", 1, null, "C", null, null, Arrays.asList("q10", "."), null, null));
  }

  @Test
  public void testNoFilters() {
    VcfPosition position = new VcfPosition("chr", 1, null, "C", null, null, null, null, null);
    assertTrue(position.getFilters().isEmpty());
    assertTrue(position.isPassingAllFilters());
  }

  @Test
  public void testHasFilters() {
    VcfPosition position = new VcfPosition("chr", 1, null, "C", null, null, Collections.singletonList("bad"),
        null, null);
    assertEquals(1, position.getFilters().size());
    assertEquals("bad", position.getFilters().get(0));
    assertFalse(position.isPassingAllFilters());
  }

  @Test
  public void testBadFilter1() {
    VcfPosition position = new VcfPosition("chr", 1, null, "C", null, null, Collections.singletonList("PASS"),
        null, null);
    assertTrue(position.getFilters().isEmpty());
    assertTrue(position.isPassingAllFilters());
  }

  @Test
  public void testBadFilter2() {
    assertThrows(VcfFormatException.class, () -> {
      new VcfPosition("chr", 1, null, "C", null, null, Arrays.asList("bad", "PASS"),
          null, null);
    });
  }

  @Test
  public void testEmptyIdEntriesDropped() {
    // VCF does not allow zero-length fields; ID is a position-independent list, so an empty entry is dropped rather
    // than throwing (see EMPTY_FIELD_HANDLING.md)
    VcfPosition interior = new VcfPosition("chr", 1, new ArrayList<>(Arrays.asList("rs1", "", "rs2")), "C", null, null,
        null, null, null);
    assertEquals(Arrays.asList("rs1", "rs2"), interior.getIds());

    VcfPosition trailing = new VcfPosition("chr", 1, new ArrayList<>(Arrays.asList("rs3", "")), "C", null, null,
        null, null, null);
    assertEquals(Collections.singletonList("rs3"), trailing.getIds());
  }

  @Test
  public void testEmptyFilterEntriesDropped() {
    VcfPosition interior = new VcfPosition("chr", 1, null, "C", null, null,
        new ArrayList<>(Arrays.asList("q10", "", "q20")), null, null);
    assertEquals(Arrays.asList("q10", "q20"), interior.getFilters());

    VcfPosition trailing = new VcfPosition("chr", 1, null, "C", null, null,
        new ArrayList<>(Arrays.asList("q10", "")), null, null);
    assertEquals(Collections.singletonList("q10"), trailing.getFilters());
  }

  @Test
  public void testEmptyAltEntriesDropped() {
    VcfPosition interior = new VcfPosition("chr", 1, null, "C",
        new ArrayList<>(Arrays.asList("T", "", "G")), null, null, null, null);
    assertEquals(Arrays.asList("T", "G"), interior.getAltBases());

    VcfPosition trailing = new VcfPosition("chr", 1, null, "C",
        new ArrayList<>(Arrays.asList("T", "")), null, null, null, null);
    assertEquals(Collections.singletonList("T"), trailing.getAltBases());
  }

  @Test
  public void testEmptyFormatSubFieldKeptAsIs() {
    // unlike ID/FILTER/ALT, an empty FORMAT key is kept (not dropped): every sample's colon-split values are matched
    // to FORMAT keys by index, and dropping this key would misalign every sample's values with the wrong key
    VcfPosition position = new VcfPosition("chr", 1, null, "C", null, null, null, null,
        new ArrayList<>(Arrays.asList("GT", "", "GQ")));
    assertEquals(Arrays.asList("GT", "", "GQ"), position.getFormat());
  }

  @Test
  public void testDuplicateFormatKeyRejected() {
    // VCFv4.3+ states outright that duplicate FORMAT keys are not allowed
    assertThrows(VcfFormatException.class, () -> new VcfPosition("chr", 1, null, "C", null, null, null, null,
        new ArrayList<>(Arrays.asList("GT", "DP", "DP"))));
  }

  @Test
  public void testMultipleEmptyFormatKeysNotTreatedAsDuplicates() {
    // an empty FORMAT key is already handled (kept as-is, warned about) separately; two of them are not a "duplicate"
    // in the sense the spec means
    VcfPosition position = new VcfPosition("chr", 1, null, "C", null, null, null, null,
        new ArrayList<>(Arrays.asList("GT", "", "")));
    assertEquals(Arrays.asList("GT", "", ""), position.getFormat());
  }

  @Test
  public void testBadChromosome() {
    assertThrows(VcfFormatException.class, () -> {
      new VcfPosition("chr 1", 1, null, "C", null, null, null, null, null); // whitespace is not allowed
    });
  }

  @Test
  public void testColonInChromosomeAllowed() {
    // the VCF spec forbids only whitespace in CHROM; a colon is allowed
    new VcfPosition("chr:1", 1, null, "C", null, null, null, null, null);
  }

  @Test
  public void testShortConstructorValidates() {
    assertThrows(VcfFormatException.class, () -> new VcfPosition("", 1, "C", new BigDecimal("0")));      // empty CHROM
    assertThrows(VcfFormatException.class, () -> new VcfPosition("chr1", -1, "C", new BigDecimal("0"))); // negative POS
    assertThrows(VcfFormatException.class, () -> new VcfPosition("chr1", 1, "X", new BigDecimal("0")));  // invalid REF
  }

  @Test
  public void testValidateCatchesUnvalidatedMutation() {
    VcfPosition position = new VcfPosition("chr1", 1, null, "C", new ArrayList<>(Arrays.asList("A", "T")), null, null,
        null, null);
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
  public void testValidateNormalizesLoneFilterSentinel() {
    VcfPosition dot = new VcfPosition("chr1", 1, null, "C", null, null, null, null, null);
    dot.getFilters().add(".");
    dot.validate();
    assertEquals(VcfPosition.FilterStatus.NONE, dot.getFilterStatus());
    assertTrue(dot.getFilters().isEmpty());

    VcfPosition pass = new VcfPosition("chr1", 1, null, "C", null, null, null, null, null);
    pass.getFilters().add("PASS");
    pass.validate();
    assertEquals(VcfPosition.FilterStatus.PASSED, pass.getFilterStatus());
    assertTrue(pass.getFilters().isEmpty());

    // validate() is idempotent: calling it again after normalization is a no-op
    dot.validate();
    assertEquals(VcfPosition.FilterStatus.NONE, dot.getFilterStatus());
  }

  @Test
  public void testTelomericPositions() {
    new VcfPosition("chr1", 0, null, "C", null, null, null, null, null); // telomere at start is valid
    assertThrows(VcfFormatException.class, () -> // a negative POS is invalid
        new VcfPosition("chr1", -1, null, "C", null, null, null, null, null));
  }

  @Test
  public void testGtMustBeFirstFormat() {
    new VcfPosition("chr1", 1, null, "C", null, null, null, null, Arrays.asList("GT", "DP")); // valid
    assertThrows(VcfFormatException.class, () -> // GT not first is invalid
        new VcfPosition("chr1", 1, null, "C", null, null, null, null, Arrays.asList("DP", "GT")));
  }

  @Test
  public void testEmptyChromosome() {
    assertThrows(VcfFormatException.class, () ->
        new VcfPosition("", 1, null, "C", null, null, null, null, null));
  }

  @Test
  public void testDuplicateIdRejected() {
    new VcfPosition("chr1", 1, Arrays.asList("rs1", "rs2"), "C", null, null, null, null, null); // unique IDs are fine
    assertThrows(VcfFormatException.class, () ->
        new VcfPosition("chr1", 1, Arrays.asList("rs1", "rs1"), "C", null, null, null, null, null));
  }

  @Test
  public void testBadId() {
    assertThrows(VcfFormatException.class, () -> {
      new VcfPosition("chr1", 1, Collections.singletonList(";"), "C", null, null, null, null, null);
    });
  }

  @Test
  public void testBadRefBase() {
    assertThrows(VcfFormatException.class, () -> {
      new VcfPosition("chr1", 1, null, "X", null, null, null, null, null);
    });
  }

  @Test
  public void testBadVarBase() {
    assertThrows(VcfFormatException.class, () -> {
      new VcfPosition("chr1", 1, null, "C", Collections.singletonList("X"), null, null, null, null);
    });
  }

  @Test
  public void testAltMissingValueCombinedWithAlleleRejected() {
    new VcfPosition("chr1", 1, null, "C", Collections.singletonList("."), null, null, null, null); // "." alone is fine
    assertThrows(VcfFormatException.class, () -> // "." cannot be combined with a real allele
        new VcfPosition("chr1", 1, null, "C", Arrays.asList("A", "."), null, null, null, null));
    assertThrows(VcfFormatException.class, () ->
        new VcfPosition("chr1", 1, null, "C", Arrays.asList(".", "A"), null, null, null, null));
  }

  @Test
  public void testZeroFilter() {
    assertThrows(VcfFormatException.class, () -> {
      new VcfPosition("chr1", 1, null, "C", null, null, Collections.singletonList("0"), null, null);
    });
  }

  @Test
  public void testFilterWithWhitespace() {
    assertThrows(VcfFormatException.class, () -> {
      new VcfPosition("chr1", 1, null, "C", null, null, Collections.singletonList("adsf\nsdf"), null, null);
    });
  }

  @Test
  public void testFilterWithMultipleNewlinesRejected() {
    // a wrapping ".*\s.*" pattern matched with matches() would fail to detect whitespace here (2+ line terminators);
    // the whitespace check must still catch it
    assertThrows(VcfFormatException.class, () -> {
      new VcfPosition("chr1", 1, null, "C", null, null, Collections.singletonList("a\nb\nc"), null, null);
    });
  }

  @Test
  public void testInfoWithWhitespace() {
    assertThrows(VcfFormatException.class, () -> {
      ListMultimap<String, String> map = ArrayListMultimap.create();
      map.put("anid", "a\nvalue");
      new VcfPosition("chr1", 1, null, "C", null, null, null, map, null);
    });
  }

  @Test
  public void testBadFormat() {
    assertThrows(VcfFormatException.class, () -> {
      new VcfPosition("chr1", 1, null, "C", null, null, null, null, Collections.singletonList("+"));
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

}
