package org.pharmgkb.parser.vcf.model;

import org.junit.jupiter.api.Test;
import org.pharmgkb.parser.vcf.VcfUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;


public class FormatMetadataTest {


  @Test
  public void testConstructor() {

    String[] props = new String[4];
    props[0] = "ID=AA";
    props[1] = "Number=1";
    props[2] = "Type=String";
    String desc = "Ancestral Allele, test";
    props[3] = "Description=\"" + desc + "\"";
    FormatMetadata md = new FormatMetadata(VcfUtils.extractProperties(props));
    assertEquals(FormatType.String, md.getType());
    assertEquals(desc, md.getDescription());

    String[] nums = new String[] {
        "0", "23", "."
    };
    for (String n : nums) {
      props[1] = "Number=" + n;
      md = new FormatMetadata(VcfUtils.extractProperties(props));
      assertEquals(n, md.getNumber());
    }

    nums = new String[] {
        "A", "a", "G", "g", "R", "r", "AA", "x", ".g", "23a"
    };
    for (String n : nums) {
      try {
        props[1] = "Number=" + n;
        new FormatMetadata(VcfUtils.extractProperties(props));
      } catch (IllegalArgumentException ex) {
        // expected
      }
    }
  }

  @Test
  public void testExtraPropertyRetained() {
    // VCFv4.2: "For all of the ##INFO, ##FORMAT, ##FILTER, and ##ALT metainformation, extra fields can be included
    // after the default fields" -- an unrecognized property is compliant, not just tolerated, and must be preserved
    FormatMetadata md = new FormatMetadata(VcfUtils.extractProperties(
        "ID=X", "Number=1", "Type=String", "Description=\"d\"", "Custom=extra"));
    assertEquals("extra", md.getPropertyRaw("Custom"));
  }
}
