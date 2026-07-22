package org.pharmgkb.parser.vcf;

import java.util.Map;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;

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

}
