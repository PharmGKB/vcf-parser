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
    for (String id : new String[] { "DEL", "INS", "DUP", "INV", "CNV", "BND" }) {
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

  @Test
  public void testWhitespaceInComponentRejected() {
    // VCFv4.2.tex line 105: "ID values are case sensitive strings and may not contain whitespace or angle brackets."
    // reproduces test/vcf/{4.1,4.2}/failed/failed_meta_alt_006.vcf's ##CauseOfFailure=The ALT ID contains a whitespace
    assertThrows(VcfFormatException.class, () -> new AltStructuralVariant("DEL:A B"));
  }

  @Test
  public void testOpeningAngleBracketInComponentRejected() {
    // reproduces failed_meta_alt_008.vcf's ##CauseOfFailure=The ALT ID contains an opening angle bracket
    assertThrows(VcfFormatException.class, () -> new AltStructuralVariant("DEL:A<B"));
  }

  @Test
  public void testCommaInComponentRejected() {
    // VCFv4.2.tex line 105 is silent on commas specifically; VCFv4.3.tex line 200 (unchanged through 4.4/4.5)
    // clarifies the same sentence to "...must not contain whitespace, commas or angle brackets" -- used as
    // VCFv4.2-silent clarifying guidance per AGENTS.md. Reproduces test/vcf/{4.2,4.3}/failed/
    // failed_meta_alt_007.vcf's ##CauseOfFailure=The ALT ID contains a comma
    assertThrows(VcfFormatException.class, () -> new AltStructuralVariant("DEL:A,B"));
  }

  @Test
  public void testClosingAngleBracketInComponentRejected() {
    // reproduces failed_meta_alt_009.vcf's ##CauseOfFailure=The ALT ID contains a closing angle bracket
    assertThrows(VcfFormatException.class, () -> new AltStructuralVariant("DEL:A>B"));
  }

  @Test
  public void testConstructorRejectsNullInsteadOfThrowingNpe() {
    // string is typed non-@Nullable, but nothing enforces that at compile time; must reject a null with
    // VcfFormatException, not let it reach a raw NullPointerException at string.isEmpty()
    assertThrows(VcfFormatException.class, () -> new AltStructuralVariant(null));
  }

  @Test
  public void testGetComponentsIsUnmodifiable() {
    AltStructuralVariant alt = new AltStructuralVariant("INS:ME:LINE");
    assertThrows(UnsupportedOperationException.class, () -> alt.getComponents().add("X"));
    // injecting a null this way used to surface later as a confusing NullPointerException from
    // getReservedComponent() instead of failing at the point of misuse
    assertThrows(UnsupportedOperationException.class, () -> alt.getComponents().set(0, null));
  }
}
