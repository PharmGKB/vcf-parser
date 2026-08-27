package org.pharmgkb.parser.vcf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

  @Test
  public void testVersionAwareFromIdWithholdsKeysAddedAtV4_3() {
    // R8: the INFO-side mirror of ReservedFormatPropertyTest.testVersionAwareFromIdWithholdsKeysAddedAtV4_3
    // -- AD/ADF/ADR have no VCFv4.2 declaration at all (see each constant's own comment and
    // ReservedInfoProperty.isAddedAtV4_3), so fromId(String, VcfVersion) must not classify them as reserved
    // under a version that predates their introduction. Previously untested at this version-aware layer for
    // the INFO side (only ReservedFormatPropertyTest's FORMAT-side twin existed). V4_1 is included alongside
    // V4_2 since VcfVersion.java documents them as currently behaviorally identical everywhere.
    for (ReservedInfoProperty property : java.util.List.of(
        ReservedInfoProperty.AlleleDepth, ReservedInfoProperty.AlleleDepthForward,
        ReservedInfoProperty.AlleleDepthReverse)) {
      assertTrue(ReservedInfoProperty.isAddedAtV4_3(property));
      assertNull(ReservedInfoProperty.fromId(property.getId(), VcfVersion.V4_1));
      assertNull(ReservedInfoProperty.fromId(property.getId(), VcfVersion.V4_2));
      assertEquals(property, ReservedInfoProperty.fromId(property.getId(), VcfVersion.V4_3));
    }
    // every other reserved key is unaffected by isAddedAtV4_3
    assertFalse(ReservedInfoProperty.isAddedAtV4_3(ReservedInfoProperty.AlleleCount));
    assertFalse(ReservedInfoProperty.isAddedAtV4_3(null));
  }
}
