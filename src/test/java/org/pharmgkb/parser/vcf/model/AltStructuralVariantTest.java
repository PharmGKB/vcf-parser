package org.pharmgkb.parser.vcf.model;

import org.junit.jupiter.api.Test;
import org.pharmgkb.parser.vcf.VcfFormatException;

import static org.junit.jupiter.api.Assertions.*;


/**
 * Tests {@link AltStructuralVariant}.
 */
public class AltStructuralVariantTest {

  @Test
  public void testTopLevelCodes() {
    // every top-level reserved code (level 0, no parent) must be accepted on its own
    for (String id : new String[] { "DEL", "INS", "DUP", "INV", "CNV" }) {
      AltStructuralVariant alt = new AltStructuralVariant(id);
      assertEquals(ReservedStructuralVariantCode.fromId(id), alt.getReservedComponent(0));
    }
  }

  @Test
  public void testReservedSubtype() {
    AltStructuralVariant alt = new AltStructuralVariant("DUP:TANDEM");
    assertEquals(ReservedStructuralVariantCode.Duplication, alt.getReservedComponent(0));
    assertEquals(ReservedStructuralVariantCode.Tandem, alt.getReservedComponent(1));
  }

  @Test
  public void testNonReservedSubtype() {
    // a non-reserved level-1+ component (e.g. a custom mobile-element name) is allowed and not resolved
    AltStructuralVariant alt = new AltStructuralVariant("INS:ME:LINE");
    assertEquals(ReservedStructuralVariantCode.Insertion, alt.getReservedComponent(0));
    assertEquals(ReservedStructuralVariantCode.MobileElement, alt.getReservedComponent(1));
    assertNull(alt.getReservedComponent(2));
    assertEquals("LINE", alt.getComponent(2));
  }

  @Test
  public void testInvalidTopLevelCode() {
    assertThrows(VcfFormatException.class, () -> new AltStructuralVariant("NOTREAL"));
  }

  @Test
  public void testMismatchedParent() {
    // TANDEM is only a valid child of DUP
    assertThrows(VcfFormatException.class, () -> new AltStructuralVariant("DEL:TANDEM"));
  }

  @Test
  public void testEmptyCodeRejected() {
    assertThrows(VcfFormatException.class, () -> new AltStructuralVariant(""));
  }

  @Test
  public void testTrailingEmptyComponentRejected() {
    // a trailing colon must not be silently dropped (Pattern.split's default limit drops trailing empty strings)
    assertThrows(VcfFormatException.class, () -> new AltStructuralVariant("INS:ME:"));
  }

  @Test
  public void testInteriorEmptyComponentRejected() {
    assertThrows(VcfFormatException.class, () -> new AltStructuralVariant("INS::LINE"));
  }
}
