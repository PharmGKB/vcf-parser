package org.pharmgkb.parser.vcf.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;


public class ReservedStructuralVariantCodeTest {

  @Test
  public void testFromIdResolvesAllReservedCodes() {
    assertEquals(ReservedStructuralVariantCode.Deletion, ReservedStructuralVariantCode.fromId("DEL"));
    assertEquals(ReservedStructuralVariantCode.Insertion, ReservedStructuralVariantCode.fromId("INS"));
    assertEquals(ReservedStructuralVariantCode.Duplication, ReservedStructuralVariantCode.fromId("DUP"));
    assertEquals(ReservedStructuralVariantCode.Inversion, ReservedStructuralVariantCode.fromId("INV"));
    assertEquals(ReservedStructuralVariantCode.Cnv, ReservedStructuralVariantCode.fromId("CNV"));
    assertEquals(ReservedStructuralVariantCode.Tandem, ReservedStructuralVariantCode.fromId("TANDEM"));
    assertEquals(ReservedStructuralVariantCode.MobileElement, ReservedStructuralVariantCode.fromId("ME"));
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
}
