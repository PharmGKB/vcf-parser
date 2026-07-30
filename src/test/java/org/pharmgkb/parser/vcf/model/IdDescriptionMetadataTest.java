package org.pharmgkb.parser.vcf.model;

import org.junit.jupiter.api.Test;
import org.pharmgkb.parser.vcf.VcfFormatException;
import org.pharmgkb.parser.vcf.VcfUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        VcfUtils.extractProperties("ID=X", "Description=\"d\"", "Custom=extra"));
    assertEquals("extra", md.getPropertyRaw("Custom"));
  }

  @Test
  public void testAttributeOrderWarnsWhenOutOfCanonicalOrder() {
    // reproduces test/vcf/{4.1,4.2}/failed/failed_meta_alt_004.vcf's ##CauseOfFailure=Incorrect order of ALT fields
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      new IdDescriptionMetadata(VcfUtils.extractProperties(
          "ID=DEL", "Type=String", "Number=1", "Description=\"Deletion\""));
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testAttributeOrderDoesNotWarnForCanonicalIdThenDescription() {
    // ##ALT/##FILTER's own canonical form is just ID,Description -- the common case
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      new IdDescriptionMetadata(VcfUtils.extractProperties("ID=DEL", "Description=\"Deletion\""));
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testTypedConstructorRejectsNullOrBlankId() {
    assertThrows(VcfFormatException.class, () -> new IdDescriptionMetadata(null, "d"));
    assertThrows(VcfFormatException.class, () -> new IdDescriptionMetadata("", "d"));
    assertThrows(VcfFormatException.class, () -> new IdDescriptionMetadata("   ", "d"));
  }

  @Test
  public void testIdWithWhitespaceWarns() {
    // regression test: an ID containing whitespace was previously silently accepted for this class (shared by
    // ##ALT, ##FILTER, and ##SAMPLE -- the underlying code path is identical regardless of which of the three
    // this instance ends up representing, so one test covers all three)
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      new IdDescriptionMetadata(VcfUtils.extractProperties("ID=A B", "Description=\"d\""));
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

}
