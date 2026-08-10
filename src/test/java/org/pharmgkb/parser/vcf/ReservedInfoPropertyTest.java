package org.pharmgkb.parser.vcf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ReservedInfoPropertyTest {

  @Test
  public void testFromIdResolvesReservedKeys() {
    assertEquals(ReservedInfoProperty.AlleleCount, ReservedInfoProperty.fromId("AC"));
    assertEquals(ReservedInfoProperty.End, ReservedInfoProperty.fromId("END"));
    assertEquals(ReservedInfoProperty.ThousandGenomes, ReservedInfoProperty.fromId("1000G"));
  }

  @Test
  public void testFromIdReturnsNullForNonReservedKey() {
    assertNull(ReservedInfoProperty.fromId("NOT_A_RESERVED_KEY"));
  }

  @Test
  public void testFromIdReturnsNullForNullId() {
    assertNull(ReservedInfoProperty.fromId(null));
  }

  @Test
  public void testGetExpectedNumberForStandardKeys() {
    // sourced from VCFv4.3's Reserved INFO Keys table (table:reserved-info); VCFv4.2 only describes these in prose
    assertEquals("A", ReservedInfoProperty.AlleleCount.getExpectedNumber());
    assertEquals("1", ReservedInfoProperty.AlleleNumber.getExpectedNumber());
    assertEquals("A", ReservedInfoProperty.Cigar.getExpectedNumber());
    assertEquals("0", ReservedInfoProperty.Dbsnp.getExpectedNumber());
  }

  @Test
  public void testGetExpectedNumberForFixedCountKeys() {
    // these keys have a literal fixed Number (not a variable-list code like A/R/G/.); confirmed directly against
    // VCFv4.2.tex/VCFv4.3.tex's tables
    assertEquals("4", ReservedInfoProperty.StrandBias.getExpectedNumber());
    assertEquals("2", ReservedInfoProperty.ConfidenceIntervalForPosition.getExpectedNumber());
    assertEquals("2", ReservedInfoProperty.ConfidenceIntervalForEnd.getExpectedNumber());
    assertEquals("4", ReservedInfoProperty.MobileElementInfo.getExpectedNumber());
  }

  @Test
  public void testFromIdResolvesAlleleDepthKeys() {
    // AD/ADF/ADR are common, frequently-used real-world INFO keys; sourced from VCFv4.3's Reserved INFO Keys
    // table (table:reserved-info) -- VCFv4.2 does not table them at all
    assertEquals(ReservedInfoProperty.AlleleDepth, ReservedInfoProperty.fromId("AD"));
    assertEquals(ReservedInfoProperty.AlleleDepthForward, ReservedInfoProperty.fromId("ADF"));
    assertEquals(ReservedInfoProperty.AlleleDepthReverse, ReservedInfoProperty.fromId("ADR"));
    assertEquals("R", ReservedInfoProperty.AlleleDepth.getExpectedNumber());
    assertEquals("R", ReservedInfoProperty.AlleleDepthForward.getExpectedNumber());
    assertEquals("R", ReservedInfoProperty.AlleleDepthReverse.getExpectedNumber());
    assertEquals(Long.class, ReservedInfoProperty.AlleleDepth.getType());
    assertTrue(ReservedInfoProperty.AlleleDepth.isList());
  }

  @Test
  public void testGetExpectedNumberForStructuralVariantKeys() {
    // these are declared natively in VCFv4.3.tex (the target) itself, unchanged from VCFv4.2.tex
    assertEquals("0", ReservedInfoProperty.Imprecise.getExpectedNumber());
    assertEquals("1", ReservedInfoProperty.End.getExpectedNumber());
    assertEquals(".", ReservedInfoProperty.StructuralVariantLength.getExpectedNumber());
    assertEquals(".", ReservedInfoProperty.HomologyLength.getExpectedNumber());
  }

  @Test
  public void testGetExpectedNumberWithNullVersionRejectsViaInterfaceDefault() {
    // ReservedInfoProperty never overrides getExpectedNumber(VcfVersion) -- it inherits ReservedProperty's own
    // default, which is where the null check lives so it applies uniformly to every implementor, not just
    // ReservedFormatProperty's CNL override (see ReservedFormatPropertyTest's own version of this test)
    assertThrows(VcfFormatException.class, () -> ReservedInfoProperty.AlleleCount.getExpectedNumber(null));
  }
}
