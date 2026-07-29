package org.pharmgkb.parser.vcf.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
  public void testGetExpectedNumberForStructuralVariantKeys() {
    // these are declared natively in VCFv4.2.tex itself (no need for VCFv4.3 clarification)
    assertEquals("0", ReservedInfoProperty.Imprecise.getExpectedNumber());
    assertEquals("1", ReservedInfoProperty.End.getExpectedNumber());
    assertEquals(".", ReservedInfoProperty.StructuralVariantLength.getExpectedNumber());
    assertEquals(".", ReservedInfoProperty.HomologyLength.getExpectedNumber());
  }
}
