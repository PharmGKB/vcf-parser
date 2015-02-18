package org.pharmgkb.parser.vcf;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests {@link VcfUtils}.
 * @author Douglas Myers-Turnbull
 */
public class VcfUtilsTest {

  @Test
  public void testAltBasePatternGood() throws Exception {
    String[] shouldPass = {"A", "AaT", "*", ".An", "<3&*FH#>", "]34]Antg", "Antg[34[", "Antg]34]", "]34:99]Antg",
        ".]34:99]Antg", "]13:123]T", ".[13:123[T.", "[<xx#$%t>:123[T"};
    for (String test : shouldPass) {
      assertTrue("String " + test + " was not recognized", VcfUtils.ALT_BASE_PATTERN.matcher(test).matches());
    }
  }

  @Test
  public void testAltBasePatternBad() throws Exception {
    String[] shouldPass = {"<at", "at>", "", "<", "]34[AT", "[34]AT", ".", "[<xxx[AT"};
    for (String test : shouldPass) {
      assertFalse("String " + test + " was recognized", VcfUtils.ALT_BASE_PATTERN.matcher(test).matches());
    }
  }

}