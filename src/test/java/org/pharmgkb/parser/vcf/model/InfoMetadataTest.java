package org.pharmgkb.parser.vcf.model;

import org.junit.jupiter.api.Test;
import org.pharmgkb.parser.vcf.VcfFormatException;
import org.pharmgkb.parser.vcf.VcfUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


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
  public void testReservedNumberMismatchWarnsAndPreservesValue() {
    // AC's reserved Number is "A" (VCFv4.3's Reserved INFO Keys table); declaring it "1" is a mismatch
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    InfoMetadata md;
    try {
      md = new InfoMetadata(VcfUtils.extractProperties(
          "ID=AC", "Number=1", "Type=Integer", "Description=\"d\""));
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    // warn-and-preserve, not reject-and-rewrite: the declared (non-compliant) value is unchanged
    assertEquals("1", md.getNumber());
  }

  @Test
  public void testReservedTypeMismatchWarns() {
    // AC's reserved Type is Integer; declaring it String is a mismatch
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      new InfoMetadata(VcfUtils.extractProperties("ID=AC", "Number=A", "Type=String", "Description=\"d\""));
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testCorrectlyDeclaredReservedKeyDoesNotWarn() {
    // AC declared exactly as the reserved definition expects (Number=A, Type=Integer) must not warn
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      new InfoMetadata(VcfUtils.extractProperties("ID=AC", "Number=A", "Type=Integer", "Description=\"d\""));
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testCorrectlyDeclaredStructuralVariantKeyDoesNotWarn() {
    // END is declared natively in VCFv4.2.tex itself (Number=1, Type=Integer) -- exercises the "no 4.3
    // clarification needed" sourcing path, not just the "standard" 4.3-table path
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      new InfoMetadata(VcfUtils.extractProperties("ID=END", "Number=1", "Type=Integer", "Description=\"d\""));
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testNonReservedKeyIsNotChecked() {
    // a custom/non-reserved ID has no canonical Number/Type to check against -- VCFv4.2 explicitly allows extra
    // fields, so this must not warn regardless of what Number/Type it declares
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      new InfoMetadata(VcfUtils.extractProperties("ID=MYCUSTOM", "Number=3", "Type=String", "Description=\"d\""));
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
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

  @Test
  public void testAttributeOrderWarnsWhenOutOfCanonicalOrder() {
    // reproduces test/vcf/{4.1,4.2}/failed/failed_meta_info_003.vcf's ##CauseOfFailure=Incorrect order of INFO fields
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      new InfoMetadata(VcfUtils.extractProperties(
          "Number=1", "Type=Integer", "Description=\"Raw read depth\"", "ID=DP"));
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testTypedConstructorDoesNotWarnAboutAttributeOrder() {
    // the typed constructor's own internal property-insertion order matches the canonical
    // ID, Number, Type, Description, Source, Version order (see the constructor's own comment); checkAttributeOrder
    // is called directly here (it isn't wired into init()/validate() for a typed-constructed instance -- see
    // IdDescriptionMetadata's Map-based-constructor-only wiring -- so this is the only way to exercise it against
    // one). Source/Version are both non-null here because an earlier version of this constructor inserted
    // Description after Source/Version instead of before them, which only this specific case would have caught.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      InfoMetadata info = new InfoMetadata("DP", "Raw read depth", InfoType.Integer, "1", "src", "1.0");
      info.checkAttributeOrder();
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testTypedConstructorRejectsNullOrBlankId() {
    assertThrows(VcfFormatException.class, () -> new InfoMetadata(null, "d", InfoType.String, "1", null, null));
    assertThrows(VcfFormatException.class, () -> new InfoMetadata("", "d", InfoType.String, "1", null, null));
    assertThrows(VcfFormatException.class, () -> new InfoMetadata("   ", "d", InfoType.String, "1", null, null));
  }

  @Test
  public void testUnquotedDescriptionWarningAttributedToInfoMetadataNotIdDescriptionMetadata() {
    // regression test: IdDescriptionMetadata.init()'s warn calls must use the runtime class's own logger, not
    // its own static sf_logger -- otherwise an InfoMetadata's Description warning is misattributed to
    // IdDescriptionMetadata's logger category instead of InfoMetadata's
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      new InfoMetadata(VcfUtils.extractProperties("ID=AA", "Number=1", "Type=String", "Description=unquoted"));
      assertTrue(appender.hasWarnOrHigher());
      assertTrue(appender.anyMessageContains("InfoMetadata"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testIdWithWhitespaceWarns() {
    // regression test: an INFO ID containing whitespace was previously silently accepted -- bcftools 1.23.1
    // warns "Invalid tag name" for this exact case
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      new InfoMetadata(VcfUtils.extractProperties("ID=A B", "Number=1", "Type=String", "Description=\"d\""));
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }
}
