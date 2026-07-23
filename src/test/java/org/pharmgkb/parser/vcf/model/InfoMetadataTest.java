package org.pharmgkb.parser.vcf.model;

import org.junit.jupiter.api.Test;
import org.pharmgkb.parser.vcf.VcfFormatException;
import org.pharmgkb.parser.vcf.VcfUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;


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

  @Test
  public void testDescriptionWithLineTerminatorRejectedAtConstruction() {
    // a Description containing a newline must be rejected here, not deferred to a generic RuntimeException at write
    // time (see VcfWriter.printLine's single-line check)
    assertThrows(VcfFormatException.class, () ->
        new InfoMetadata("ID", "bad\ndescription", InfoType.String, "1", null, null));
    assertThrows(VcfFormatException.class, () ->
        new InfoMetadata("ID", "bad\rdescription", InfoType.String, "1", null, null));
  }

  @Test
  public void testValidateRefreshesTypeAfterRawMutation() {
    InfoMetadata md = new InfoMetadata("X", "d", InfoType.Integer, "1", null, null);
    md.getPropertiesRaw().put("Type", "String");
    md.validate();
    assertEquals(InfoType.String, md.getType());
  }

  @Test
  public void testExtraPropertyRetained() {
    // VCFv4.2: "For all of the ##INFO, ##FORMAT, ##FILTER, and ##ALT metainformation, extra fields can be included
    // after the default fields" -- an unrecognized property is compliant, not just tolerated, and must be preserved
    InfoMetadata md = new InfoMetadata(VcfUtils.extractProperties(
        "ID=X", "Number=1", "Type=String", "Description=\"d\"", "Custom=extra"));
    assertEquals("extra", md.getPropertyRaw("Custom"));
  }

  @Test
  public void testValidateWithExtraPropertyDoesNotAffectCoreFields() {
    // the typed constructor previously passed isBaseType=true up the chain (see testValidateRefreshesTypeAfterRawMutation
    // for the general validate() re-derivation this exercises); combined with an extra property, validate() must
    // still correctly re-derive Type/Number and not treat Number/Type/Source/Version as unexpected
    InfoMetadata md = new InfoMetadata("X", "d", InfoType.Integer, "1", "src", "1.0");
    md.getPropertiesRaw().put("Custom", "extra");
    md.validate();
    assertEquals(InfoType.Integer, md.getType());
    assertEquals("1", md.getNumber());
    assertEquals("extra", md.getPropertyRaw("Custom"));
  }
}
