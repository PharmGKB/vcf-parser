package org.pharmgkb.parser.vcf;

import java.util.Map;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;
import org.pharmgkb.parser.vcf.model.InfoType;
import org.pharmgkb.parser.vcf.model.ReservedFormatProperty;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link VcfUtils}.
 * @author Douglas Myers-Turnbull
 */
public class VcfUtilsTest {

  @Test
  public void testAltBasePatternSimple() throws Exception {
    System.out.println(VcfUtils.ALT_BASE_PATTERN.pattern());
    String[] shouldPass = {".", "A", "AaT", "*", ".An", "An.", "<3&*FH#>", ".TGCA"};
    for (String test : shouldPass) {
      assertTrue(VcfUtils.ALT_BASE_PATTERN.matcher(test).matches(), "String " + test + " was not recognized");
    }
  }

  @Test
  public void testAltBasePatternBreakpoint() throws Exception {
    System.out.println(VcfUtils.ALT_BASE_PATTERN.pattern());
    // all copied from the VCF 4.2 specification, in order
    String[] shouldPass = {" G]17:198982]", " ]13:123456]T", "C[2:321682[", "[17:198983[A", "A]2:321681]",
        " [13:123457[C", "]13 : 123456]AGTNNNNNCAT", " CAGTNNNNNCA[2 : 321682[", "C[<ctg1>: 1[", "] <ctg1 >: 329]A ",
        "C<ctg1 >", " C[<ctg1>: 7[", "] <ctg1 >: 214]A", " ]13 : 123456]T",
        "]13 : 123456]A", " G[13 : 123460[", " ]13 : 123456]T", " C[2 : 321682[", "]2 : 321681]A", ".[13 : 123457[",
        " ]13 : 123456]T", " C[1 : 1[", " ]1 : 0]A", "G[13 : 123457[", " ]13 : 123456]T", " C[2 : 321682[",
        " ]2 : 321681]A", "G]2 : 421681]", "[2 : 421682[T", "A]2 : 321681]", "[2 : 321682[C", " T]13 : 123462]",
        "A]2 : 321687]", " [2 : 321682[C" };
    for (String test : shouldPass) {
      test = test.replaceAll(" ", "");
      assertTrue(VcfUtils.ALT_BASE_PATTERN.matcher(test).matches(), "String " + test + " was not recognized");
    }
  }

  @Test
  public void testAltBasePatternBad() throws Exception {
    String[] shouldPass = {"<at", "at>", "", "<", "]34[AT", "[34]AT", "[<xxx[AT"};
    for (String test : shouldPass) {
      assertFalse(VcfUtils.ALT_BASE_PATTERN.matcher(test).matches(), "String " + test + " was recognized");
    }
  }

  @Test
  public void testAltBasePatternBreakpointAcceptsNonNumericChromosome() {
    // the mate chromosome name is not restricted to digits by the spec; the previous \d+-only pattern only ever
    // passed because every example in the spec itself happens to use a purely numeric chromosome name
    String[] shouldPass = {"A]chr1:100]", "A]X:100]", "G]scaffold_12:55]", "]chrX:1]T", "C[chr1:1["};
    for (String test : shouldPass) {
      assertTrue(VcfUtils.ALT_BASE_PATTERN.matcher(test).matches(), "String " + test + " was not recognized");
    }
  }

  @Test
  public void testAltBasePatternBreakpointRequiresPosition() {
    // "chr:pos" is always both parts together per the spec; there is no valid breakend form giving a chromosome
    // without a position (a truly mate-less breakend uses the unrelated "G."/".A" single-breakend shorthand)
    String[] shouldFail = {"A]2]", "A]chr1]", "C[2[", "]13]T", "[<ctg1>[A"};
    for (String test : shouldFail) {
      assertFalse(VcfUtils.ALT_BASE_PATTERN.matcher(test).matches(), "String " + test + " was recognized");
    }
  }

  @Test
  public void testAltBasePatternAcceptsLargeInsertionShorthand() {
    // VCFv4.2's own "Large Insertions" section gives this as a real example (13 321682 INS0 T C<ctg1> ...),
    // calling it "the shorthand notation" for "the special case of the complete insertion of a sequence between
    // two base pairs": a base run directly followed by a symbolic ID, with NO breakend bracket between them.
    // Confirmed present in the raw VCFv4.2 spec text itself (not just later versions). A prior review round
    // mistakenly "fixed" this as over-permissive and rejected it, based on incomplete spec research -- that
    // change was reverted. Note that the EBI vcf_validator 0.10.2 (github.com/ebivariation/vcf-validator) does
    // NOT implement this shorthand -- it rejects "C<ctg1>" with the same generic error it gives for clearly
    // invalid ALT syntax -- but the VCFv4.2 spec text is unambiguous that this form is valid, so this parser
    // accepts it. This test exists specifically to prevent this exact regression from recurring.
    assertTrue(VcfUtils.ALT_BASE_PATTERN.matcher("C<ctg1>").matches());
  }

  @Test
  public void testExtractPropertiesWithEscapes() {
    // an escaped backslash (\\) and an escaped double-quote (\") inside a quoted Description must not crash and must be
    // preserved (the split must not treat the escaped quote as a real quote)
    Map<String, String> props = VcfUtils.extractPropertiesFromLine("ID=X,Description=\"a \\\\ b \\\" c\"");
    assertEquals("X", props.get("ID"));
    assertEquals("\"a \\\\ b \\\" c\"", props.get("Description"));
  }

  @Test
  public void testStrayBackslashDoesNotSuppressDelimiter() {
    // a backslash not followed by "\\" or "\"" has no defined escape meaning and must not consume/protect the following
    // delimiter; before the fix, the comma here was incorrectly treated as escaped, merging both properties into one
    // blob with two "=" signs, which then threw instead of parsing to two properties
    Map<String, String> props = VcfUtils.extractPropertiesFromLine("Source=A\\,Number=1");
    assertEquals("A\\", props.get("Source"));
    assertEquals("1", props.get("Number"));
  }

  @Test
  public void testExtractPropertiesDuplicateAttributeWarnsAndKeepsLast() {
    // a declaration with a repeated attribute (e.g. ##INFO=<ID=X,Number=1,Number=2,...>) silently kept only the
    // last value with no diagnostic at all; must at least warn (VCF metadata-declaration policy is lenient, not a
    // structural violation, so this doesn't throw)
    Map<String, String> props = VcfUtils.extractPropertiesFromLine("ID=X,Number=1,Number=2");
    assertEquals("2", props.get("Number"));
  }

  @Test
  public void testExtractPropertiesNoPlaceholderCollision() {
    // a value literally containing the old "~~~~" placeholder must be preserved, and an escaped quote must not be
    // treated as a real quote (so the comma inside the quoted value is not a top-level delimiter)
    Map<String, String> props = VcfUtils.extractPropertiesFromLine(
        "ID=X,Description=\"literal ~~~~ and \\\"q, v\\\"\"");
    assertEquals("X", props.get("ID"));
    assertEquals("\"literal ~~~~ and \\\"q, v\\\"\"", props.get("Description"));
  }

  @Test
  public void testSymbolicAltRejectsWhitespace() {
    // an angle-bracketed ALT ID may not contain whitespace, commas, or angle brackets
    assertTrue(VcfUtils.ALT_BASE_PATTERN.matcher("<DEL>").matches());
    assertFalse(VcfUtils.ALT_BASE_PATTERN.matcher("<DEL bad>").matches());
    assertFalse(VcfUtils.ALT_BASE_PATTERN.matcher("<a,b>").matches());
    assertFalse(VcfUtils.ALT_BASE_PATTERN.matcher("<a<b>").matches());
  }

  @Test
  public void testQuoteEscapesBackslashAndQuote() {
    assertEquals("\"plain\"", VcfUtils.quote("plain"));
    assertEquals("\"a \\\\ b\"", VcfUtils.quote("a \\ b"));
    assertEquals("\"a \\\"quoted\\\" b\"", VcfUtils.quote("a \"quoted\" b"));
  }

  @Test
  public void testUnquoteDecodesEscapes() {
    assertEquals("plain", VcfUtils.unquote("\"plain\""));
    assertEquals("a \\ b", VcfUtils.unquote("\"a \\\\ b\""));
    assertEquals("a \"quoted\" b", VcfUtils.unquote("\"a \\\"quoted\\\" b\""));
    // not wrapped in quotes: returned as-is
    assertEquals("unquoted", VcfUtils.unquote("unquoted"));
    // single-quote-character input must not crash (previously threw StringIndexOutOfBoundsException)
    assertEquals("\"", VcfUtils.unquote("\""));
  }

  @Test
  public void testQuoteUnquoteRoundTrip() {
    String[] values = { "plain", "a \\ b", "a \"quoted\" b", "\\\"both\\\"", "" };
    for (String value : values) {
      assertEquals(value, VcfUtils.unquote(VcfUtils.quote(value)), "round-trip failed for: " + value);
    }
  }

  @Test
  public void testSplitProp() throws Exception {
    assertEquals(Pair.of("abc", "\"d=ef\""), VcfUtils.splitProperty("abc=\"d=ef\""));
    assertEquals(Pair.of("\"a=bc\"", "\"d=ef\""), VcfUtils.splitProperty("\"a=bc\"=\"d=ef\""));
    assertEquals(Pair.of("\"a=b=c\"", "\"d=ef\""), VcfUtils.splitProperty("\"a=b=c\"=\"d=ef\""));
  }

  @Test
  public void testCheckNoLineTerminator() {
    VcfUtils.checkNoLineTerminator("key", "value"); // no line terminator: does not throw
    VcfUtils.checkNoLineTerminator("key", null); // a null value is not checked (nothing to check)
    assertThrows(VcfFormatException.class, () -> VcfUtils.checkNoLineTerminator("bad\nkey", "value"));
    assertThrows(VcfFormatException.class, () -> VcfUtils.checkNoLineTerminator("key", "bad\nvalue"));
    assertThrows(VcfFormatException.class, () -> VcfUtils.checkNoLineTerminator("key", "bad\rvalue"));
  }

  @Test
  public void testConvertPropertyCharacterReturnsCharacterNotString() {
    // convertElement's Character.class branch previously returned the raw String; type erasure let that past
    // convertProperty() itself, but it then threw ClassCastException at the caller's assignment site below
    Character value = VcfUtils.convertProperty(InfoType.Character, "A");
    assertEquals(Character.valueOf('A'), value);
  }

  @Test
  public void testCheckReservedFormatConstraintsFtWarnsButDoesNotThrow() {
    // FT's grammar (PASS/./semicolon-separated codes, no whitespace, PASS/'.' not combined with other codes) isn't
    // expressible through its declared Type=String, so it's checked separately; this is non-structural sample
    // content, so an invalid value warns rather than throws
    assertEquals("PASS", VcfUtils.checkReservedFormatConstraints("FT", "PASS")); // valid: does not throw
    assertEquals("q10;q20", VcfUtils.checkReservedFormatConstraints("FT", "q10;q20")); // valid: does not throw
    assertEquals("q10 q20", VcfUtils.checkReservedFormatConstraints("FT", "q10 q20")); // whitespace in a code: warns
    assertEquals("PASS;q10", VcfUtils.checkReservedFormatConstraints("FT", "PASS;q10")); // PASS + code: warns
    assertNull(VcfUtils.checkReservedFormatConstraints("FT", null)); // no value set: nothing to check
    assertEquals(".", VcfUtils.checkReservedFormatConstraints("FT", ".")); // missing value: nothing to check
  }

  @Test
  public void testCheckReservedFormatConstraintsFtDropsEmptyCodes() {
    // an empty semicolon-separated code is warned about and dropped, the same "warn and drop" convention already
    // used for an empty entry in FILTER (FT's grammar is explicitly modeled on FILTER's) and every other
    // position-independent list in this library -- unlike PASS/'.' combined with a genuine other code (see above),
    // which is a real grammar violation and must not be silently rewritten away
    assertEquals("q10;q20", VcfUtils.checkReservedFormatConstraints("FT", "q10;;q20"));
    assertEquals("q10", VcfUtils.checkReservedFormatConstraints("FT", "q10;"));
    assertEquals("q10", VcfUtils.checkReservedFormatConstraints("FT", ";q10"));
    // dropping the empty artifacts must not leave a false "combined with PASS" warning when PASS is the only
    // remaining real content (e.g. a trailing ';' after PASS)
    assertEquals("PASS", VcfUtils.checkReservedFormatConstraints("FT", "PASS;"));
  }

  @Test
  public void testCheckReservedFormatConstraintsPsWarnsButDoesNotThrow() {
    // PS must be a non-negative 32-bit integer, but that constraint isn't expressible through its declared
    // Type=Integer (which maps to a 64-bit Long), so it's checked separately; this is non-structural sample
    // content, so an out-of-range value warns rather than throws
    VcfUtils.checkReservedFormatConstraints("PS", "0"); // valid: does not throw
    VcfUtils.checkReservedFormatConstraints("PS", String.valueOf(Integer.MAX_VALUE)); // valid: does not throw
    VcfUtils.checkReservedFormatConstraints("PS", "-5"); // negative: warns, does not throw
    VcfUtils.checkReservedFormatConstraints("PS", String.valueOf(Integer.MAX_VALUE + 1L)); // out of 32-bit range: warns
  }

  @Test
  public void testConvertPropertyPhaseSetPreservesOutOfRangeValue() {
    // the typed accessor still returns the parsed Long even when it violates PS's non-negative-32-bit constraint,
    // consistent with the "warn and preserve" policy for non-structural sample content
    Long value = VcfUtils.convertProperty(ReservedFormatProperty.PhaseSet, "-5");
    assertEquals(-5L, value);
  }

  @Test
  public void testConvertPropertyFilterDropsEmptyCodes() {
    // unlike PS, the typed FT accessor returns a normalized value: an empty semicolon-separated code is dropped,
    // matching the "warn and drop" convention used for FILTER's own empty entries
    String value = VcfUtils.convertProperty(ReservedFormatProperty.Filter, "q10;;q20");
    assertEquals("q10;q20", value);
  }

}
