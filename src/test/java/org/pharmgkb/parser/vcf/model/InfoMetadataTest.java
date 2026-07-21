package org.pharmgkb.parser.vcf.model;

import org.junit.jupiter.api.Test;
import org.pharmgkb.parser.vcf.VcfUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;


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

  @Test
  public void testFlagWithNonZeroNumberWarns() {
    // Type=Flag with Number != 0 is malformed; it should warn, not throw
    InfoMetadata md = new InfoMetadata(VcfUtils.extractProperties(
        "ID=X", "Number=1", "Type=Flag", "Description=\"d\""));
    assertEquals("1", md.getNumber());
    assertEquals(InfoType.Flag, md.getType());
  }

  @Test
  public void testUnquotedDescriptionWarns() {
    // an unquoted Description is malformed; it should warn, not throw
    InfoMetadata md = new InfoMetadata(VcfUtils.extractProperties(
        "ID=X", "Number=1", "Type=String", "Description=unquoted"));
    assertEquals("X", md.getId());
  }

  @Test
  public void testInvalidTypeWarns() {
    // an invalid Type should warn (not throw); the type is left null
    InfoMetadata md = new InfoMetadata(VcfUtils.extractProperties(
        "ID=X", "Number=1", "Type=NotAType", "Description=\"d\""));
    assertNull(md.getType());
  }

  @Test
  public void testMissingTypeWarns() {
    // a missing Type should warn (not throw); the type is left null
    InfoMetadata md = new InfoMetadata(VcfUtils.extractProperties(
        "ID=X", "Number=1", "Description=\"d\""));
    assertNull(md.getType());
  }
}
