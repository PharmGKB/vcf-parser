package org.pharmgkb.parser.vcf;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
      assertTrue("String " + test + " was not recognized", VcfUtils.ALT_BASE_PATTERN.matcher(test).matches());
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
      assertTrue("String " + test + " was not recognized", VcfUtils.ALT_BASE_PATTERN.matcher(test).matches());
    }
  }

  @Test
  public void testAltBasePatternBad() throws Exception {
    String[] shouldPass = {"<at", "at>", "", "<", "]34[AT", "[34]AT", "[<xxx[AT"};
    for (String test : shouldPass) {
      assertFalse("String " + test + " was recognized", VcfUtils.ALT_BASE_PATTERN.matcher(test).matches());
    }
  }

  @Test
  public void testSplitProp() throws Exception {
    assertEquals(Pair.of("abc", "\"d=ef\""), VcfUtils.splitProperty("abc=\"d=ef\""));
    assertEquals(Pair.of("\"a=bc\"", "\"d=ef\""), VcfUtils.splitProperty("\"a=bc\"=\"d=ef\""));
    assertEquals(Pair.of("\"a=b=c\"", "\"d=ef\""), VcfUtils.splitProperty("\"a=b=c\"=\"d=ef\""));
  }

}