package org.pharmgkb.parser.vcf.model;

import org.junit.Test;
import org.pharmgkb.parser.vcf.VcfUtils;

import static org.junit.Assert.assertEquals;


public class InfoMetadataTest {


  @Test
  public void testConstructor() {

    String[] props = new String[4];
    props[0] = "ID=AA";
    props[1] = "Number=1";
    props[2] = "Type=String";
    String desc = "Ancestral Allele, test";
    props[3] = "Description=\"" + desc + "\"";
    InfoMetadata md = new InfoMetadata(VcfUtils.extractProperties(props));
    assertEquals(InfoType.String, md.getType());
    assertEquals(desc, md.getDescription());

    String[] nums = {
        "0", "23", "A", "G", "R", "."
    };
    for (String n : nums) {
      props[1] = "Number=" + n;
      md = new InfoMetadata(VcfUtils.extractProperties(props));
      assertEquals(n, md.getNumber());
    }

    nums = new String[] { // these should all cause warnings
        "AA", "x", ".g", "23a"
    };
    for (String n : nums) {
      props[1] = "Number=" + n;
      new InfoMetadata(VcfUtils.extractProperties(props));
    }
  }
}