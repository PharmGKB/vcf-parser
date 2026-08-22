package org.pharmgkb.parser.vcf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


public class ReservedStructuralVariantCodeTest {

  @Test
  public void testFromIdResolvesAllReservedCodes() {
    assertEquals(ReservedStructuralVariantCode.Deletion, ReservedStructuralVariantCode.fromId("DEL"));
    assertEquals(ReservedStructuralVariantCode.Insertion, ReservedStructuralVariantCode.fromId("INS"));
    assertEquals(ReservedStructuralVariantCode.Duplication, ReservedStructuralVariantCode.fromId("DUP"));
    assertEquals(ReservedStructuralVariantCode.Inversion, ReservedStructuralVariantCode.fromId("INV"));
    assertEquals(ReservedStructuralVariantCode.Cnv, ReservedStructuralVariantCode.fromId("CNV"));
    assertEquals(ReservedStructuralVariantCode.Breakend, ReservedStructuralVariantCode.fromId("BND"));
    assertEquals(ReservedStructuralVariantCode.Tandem, ReservedStructuralVariantCode.fromId("TANDEM"));
    assertEquals(ReservedStructuralVariantCode.MobileElement, ReservedStructuralVariantCode.fromId("ME"));
  }

  @Test
  public void testBreakendIsALevelZeroCodeWithNoParents() {
    // regression test: BND (VCFv4.3.tex line 208, a 4.3+ addition to VCFv4.2's original 5-code list) was
    // previously unrecognized, causing new AltStructuralVariant("BND") to throw.
    assertEquals(0, ReservedStructuralVariantCode.Breakend.getLevelInSpecification());
    assertEquals(0, ReservedStructuralVariantCode.Breakend.getParentCodes().size());
  }

  @Test
  public void testFromIdReturnsNullForNonReservedCode() {
    assertNull(ReservedStructuralVariantCode.fromId("NOT_A_RESERVED_CODE"));
  }

  @Test
  public void testFromIdIsCaseSensitive() {
    // AltStructuralVariant's class Javadoc states these codes are case-sensitive.
    assertNull(ReservedStructuralVariantCode.fromId("del"));
  }

  @Test
  public void testFromIdReturnsNullForNullId() {
    assertNull(ReservedStructuralVariantCode.fromId(null));
  }

  @Test
  public void testGetParentCodesIsUnmodifiable() {
    // Tandem has a real, non-empty parent list (Duplication) -- unlike a level-0 code, which has none.
    assertThrows(UnsupportedOperationException.class,
        () -> ReservedStructuralVariantCode.Tandem.getParentCodes().set(0, ReservedStructuralVariantCode.Deletion));
  }

  @Test
  public void testIsReservedSymbolicAlleleNameRecognizesEveryLevel0CodeAndReservedLevel1Combination() {
    // every bare level-0 code (VCFv4.3.tex lines 203-208) plus every reserved level-1 subtype combined with
    // each of its own reserved parents (lines 213-215) -- 6 + 3 = 9 total
    for (String id : new String[] {"DEL", "INS", "DUP", "INV", "CNV", "BND", "DUP:TANDEM", "DEL:ME", "INS:ME"}) {
      assertTrue(ReservedStructuralVariantCode.isReservedSymbolicAlleleName(id), id + " should be reserved");
    }
  }

  @Test
  public void testIsReservedSymbolicAlleleNameRejectsCustomSubtypeAndBareLevel1Code() {
    // a custom subtype under a reserved parent is not itself a reserved NAME (VCFv4.4.tex:221, implementations
    // may define their own subtypes), and a level-1 code used bare (never reserved at level 0) isn't either
    assertFalse(ReservedStructuralVariantCode.isReservedSymbolicAlleleName("INS:ME:LINE"));
    assertFalse(ReservedStructuralVariantCode.isReservedSymbolicAlleleName("TANDEM"));
    assertFalse(ReservedStructuralVariantCode.isReservedSymbolicAlleleName("NOT_A_RESERVED_CODE"));
  }

  @Test
  public void testIsReservedSymbolicAlleleNameReturnsFalseForNull() {
    assertFalse(ReservedStructuralVariantCode.isReservedSymbolicAlleleName(null));
  }
}
