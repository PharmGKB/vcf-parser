package org.pharmgkb.parser.vcf;

import org.junit.jupiter.api.Test;

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
  public void testReservedCodeAtWrongLevelRejected() {
    // regression test: no prior test pinned this branch. VCFv4.2.tex line 114's "Reserved subtypes" table
    // defines TANDEM only at level 1 (as DUP:TANDEM) -- used bare, at level 0, it's a reserved code that has
    // drifted from its one defined position in that table, a genuinely different defect from "not a reserved
    // code at all" (testInvalidTopLevelCode) or "wrong parent for its level" (testMismatchedParent)
    VcfFormatException ex = assertThrows(VcfFormatException.class, () -> new AltStructuralVariant("TANDEM"));
    assertTrue(ex.getMessage().contains("is a reserved code of level 1, not 0"));
  }

  @Test
  public void testEmptyCodeRejected() {
    assertThrows(VcfFormatException.class, () -> new AltStructuralVariant(""));
  }

  @Test
  public void testWhitespaceOrCommaRejectionMessageIsTruncated() {
    // regression test found by a full-tree sweep beyond the fifth re-verification round's own named scope:
    // this unconditional throw embedded the raw code string verbatim and unbounded. The other two
    // reserved-code-mismatch throws in this class (testReservedCodeAtWrongLevelRejected's shape and the
    // parent-mismatch shape) were fixed the same way but aren't independently testable with a huge value:
    // both only fire once a component has already resolved to a real (always short) reserved code.
    String hugeCode = "x".repeat(150) + ",y";
    VcfFormatException ex = assertThrows(VcfFormatException.class, () -> new AltStructuralVariant(hugeCode));
    assertTrue(ex.getMessage().contains("truncated"), ex.getMessage());
    // the length bound alone is not discriminative (a leaky "truncated" notice appended without actually
    // cutting the string would still pass it, with only ~6 chars of margin) -- found by the ninth
    // re-verification round's test-coverage pass; asserting the raw untruncated value is absent proves
    // truncation actually happened
    assertFalse(ex.getMessage().contains(hugeCode), "raw untruncated value must not appear: " + ex.getMessage());
    assertTrue(ex.getMessage().length() < 250, "message was not bounded: " + ex.getMessage().length() + " chars");
  }

  @Test
  public void testEmptyComponentRejectionMessageIsTruncated() {
    // same finding, the empty-component-at-level throw
    String hugeCode = "x".repeat(150) + "::";
    VcfFormatException ex = assertThrows(VcfFormatException.class, () -> new AltStructuralVariant(hugeCode));
    assertTrue(ex.getMessage().contains("truncated"), ex.getMessage());
    // see testWhitespaceOrCommaRejectionMessageIsTruncated's identical comment on why this is needed alongside
    // the length bound
    assertFalse(ex.getMessage().contains(hugeCode), "raw untruncated value must not appear: " + ex.getMessage());
    assertTrue(ex.getMessage().length() < 250, "message was not bounded: " + ex.getMessage().length() + " chars");
  }

  @Test
  public void testInvalidTopLevelCodeRejectionMessageIsTruncated() {
    // same finding, the unrecognized-top-level-code throw
    String hugeCode = "x".repeat(150);
    VcfFormatException ex = assertThrows(VcfFormatException.class, () -> new AltStructuralVariant(hugeCode));
    assertTrue(ex.getMessage().contains("truncated"), ex.getMessage());
    // see testWhitespaceOrCommaRejectionMessageIsTruncated's identical comment on why this is needed alongside
    // the length bound
    assertFalse(ex.getMessage().contains(hugeCode), "raw untruncated value must not appear: " + ex.getMessage());
    assertTrue(ex.getMessage().length() < 250, "message was not bounded: " + ex.getMessage().length() + " chars");
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
    // VCFv4.3.tex line 200 (the target, unchanged through 4.4/4.5) states directly "...must not contain
    // whitespace, commas or angle brackets"; VCFv4.2.tex line 105 states the same sentence without the comma,
    // predating this rule rather than conflicting with it. Reproduces test/vcf/{4.2,4.3}/failed/
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
