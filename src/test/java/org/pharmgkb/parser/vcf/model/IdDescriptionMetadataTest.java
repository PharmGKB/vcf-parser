package org.pharmgkb.parser.vcf.model;

import org.junit.jupiter.api.Test;
import org.pharmgkb.parser.vcf.VcfUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests {@link IdDescriptionMetadata} (the base class used for {@code ##ALT}, {@code ##FILTER}, and
 * {@code ##SAMPLE} metadata).
 */
public class IdDescriptionMetadataTest {

  @Test
  public void testExtraPropertyRetained() {
    // VCFv4.2: "For all of the ##INFO, ##FORMAT, ##FILTER, and ##ALT metainformation, extra fields can be included
    // after the default fields" -- an unrecognized property is compliant, not just tolerated, and must be preserved
    IdDescriptionMetadata md = new IdDescriptionMetadata(
        VcfUtils.extractProperties("ID=X", "Description=\"d\"", "Custom=extra"), true);
    assertEquals("extra", md.getPropertyRaw("Custom"));
  }

}
