package org.pharmgkb.parser.vcf.model;

import java.io.StringWriter;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.pharmgkb.parser.vcf.InternalUtils;
import org.pharmgkb.parser.vcf.VcfFormatException;
import org.pharmgkb.parser.vcf.VcfWarnings;

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
    IdDescriptionMetadata md = new IdDescriptionMetadata("TEST",
        InternalUtils.extractProperties(new String[]{"ID=X", "Description=\"d\"", "Custom=extra"}, VcfWarnings.DEFAULT),
        VcfWarnings.DEFAULT);
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
      new IdDescriptionMetadata("TEST", InternalUtils.extractProperties(
          new String[]{"ID=DEL", "Type=String", "Number=1", "Description=\"Deletion\""}, VcfWarnings.DEFAULT),
          VcfWarnings.DEFAULT);
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
      new IdDescriptionMetadata("TEST",
          InternalUtils.extractProperties(new String[]{"ID=DEL", "Description=\"Deletion\""}, VcfWarnings.DEFAULT),
          VcfWarnings.DEFAULT);
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testDirectValidateDoesNotDoubleWarnAttributeOrder() {
    // regression test: IdMetadata.validate() (this class's superclass) and IdDescriptionMetadata.validate() each
    // used to call checkAttributeOrder() -- calling .validate() directly (e.g. after mutating properties via
    // getPropertiesRaw(), as VcfWriter's validateKeyedMetadataEntries does) fired the attribute-order warning twice
    IdDescriptionMetadata md = new IdDescriptionMetadata("TEST",
        InternalUtils.extractProperties(new String[]{"ID=DEL", "Type=String", "Number=1", "Description=\"Deletion\""}, VcfWarnings.DEFAULT),
        VcfWarnings.DEFAULT);
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      md.validate();
      assertEquals(1, appender.countMessagesContaining("declared out of canonical order"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testTypedConstructorRejectsNullOrBlankId() {
    assertThrows(VcfFormatException.class, () -> new IdDescriptionMetadata("TEST", null, "d"));
    assertThrows(VcfFormatException.class, () -> new IdDescriptionMetadata("TEST", "", "d"));
    assertThrows(VcfFormatException.class, () -> new IdDescriptionMetadata("TEST", "   ", "d"));
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
      new IdDescriptionMetadata("TEST",
          InternalUtils.extractProperties(new String[]{"ID=A B", "Description=\"d\""}, VcfWarnings.DEFAULT),
          VcfWarnings.DEFAULT);
      assertTrue(appender.anyMessageContains("must not contain whitespace"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testWarningEmbedsCallerSuppliedLabelNotSharedClassName() {
    // Regression test for findings19.md Minor #1: ALT/FILTER/SAMPLE all share this concrete class, so before the
    // label mechanism existed, every warning said "IdDescriptionMetadata" regardless of which of the three it was.
    for (String label : new String[] { "ALT", "FILTER", "SAMPLE" }) {
      ch.qos.logback.classic.Logger root =
          (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
      org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
          new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
      appender.start();
      root.addAppender(appender);
      try {
        new IdDescriptionMetadata(label,
            InternalUtils.extractProperties(new String[]{"ID=A B", "Description=\"d\""}, VcfWarnings.DEFAULT),
            VcfWarnings.DEFAULT);
        assertTrue(appender.anyMessageContains(label + " metadata ID"));
        assertFalse(appender.anyMessageContains("IdDescriptionMetadata"));
      } finally {
        root.detachAppender(appender);
        appender.stop();
      }
    }
  }

  @Test
  public void testTypedConstructorRejectsWhitespaceInIdInsteadOfWarning() {
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> new IdDescriptionMetadata("FILTER", "bad id", "some description"));
    assertTrue(ex.getMessage().contains("must not contain whitespace"));
  }

  @Test
  public void testMapConstructorRequiresWarningsInstance() {
    Map<String, String> props = InternalUtils.extractProperties(
        new String[] {"ID=bad id", "Description=\"d\""}, VcfWarnings.DEFAULT);
    assertThrows(NullPointerException.class, () -> new IdDescriptionMetadata("FILTER", props, null));
  }

  @Test
  public void testMapConstructorWarnsForWhitespaceInIdToSuppliedSink() {
    Map<String, String> props = InternalUtils.extractProperties(
        new String[] {"ID=bad id", "Description=\"d\""}, VcfWarnings.DEFAULT);
    StringWriter sink = new StringWriter();
    new IdDescriptionMetadata("FILTER", props, new VcfWarnings(sink));
    assertTrue(sink.toString().contains("must not contain whitespace"));
  }

  @Test
  public void testTypedConstructorRejectsCommaInIdInsteadOfWarning() {
    // regression test: IdMetadata's generic ID-grammar check previously only threw (strict path) for whitespace,
    // never a comma, for every type sharing this class (##FILTER, ##SAMPLE, ##ALT)
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> new IdDescriptionMetadata("FILTER", "A,B", "some description"));
    assertTrue(ex.getMessage().contains("must not contain a comma"));
  }

  @Test
  public void testMapConstructorWarnsForCommaInIdToSuppliedSink() {
    // regression test: only reachable via the Map-based (lenient) constructor or live mutation, since a real
    // parsed header line's own comma would already have been consumed by the top-level attribute-list splitter
    // before an ID value containing one could exist
    Map<String, String> props = InternalUtils.extractProperties(
        new String[] {"ID=A,B", "Description=\"d\""}, VcfWarnings.DEFAULT);
    StringWriter sink = new StringWriter();
    new IdDescriptionMetadata("FILTER", props, new VcfWarnings(sink));
    assertTrue(sink.toString().contains("must not contain a comma"));
  }

  @Test
  public void testColonlessAltIdWithCommaWarns() {
    // regression test: VcfMetadata.validateAlt()'s colon-gated dispatch never checked a colonless ALT ID for a
    // comma at all (its own comma-checking, via AltStructuralVariant, only ran for a colon-containing ID) --
    // closed here, at the shared IdMetadata.init() level, rather than in validateAlt() itself, since a comma is
    // never valid in ANY of ALT's spec-sanctioned colonless conventions (an IUPAC ambiguity code or a
    // CN0-style copy-number allele) either, the same as every other type this class covers
    Map<String, String> props = InternalUtils.extractProperties(
        new String[] {"ID=A,B", "Description=\"d\""}, VcfWarnings.DEFAULT);
    StringWriter sink = new StringWriter();
    new IdDescriptionMetadata("ALT", props, new VcfWarnings(sink));
    assertTrue(sink.toString().contains("must not contain a comma"));
  }

  @Test
  public void testTypedConstructorDoesNotCallWarnBasedInit() {
    // regression guard: the typed constructor must not warn even to the shared logger for a well-formed input --
    // if a future change accidentally reintroduces a call to init() here, this would still pass (init() is a
    // no-op for well-formed input), so this test's real value is documented in testTypedConstructorRejectsWhitespaceInIdInsteadOfWarning
    // actually exercising the throw path instead of a silent warn
    IdDescriptionMetadata md = new IdDescriptionMetadata("FILTER", "q10", "quality below 10");
    assertEquals("q10", md.getId());
    assertEquals("quality below 10", md.getDescription());
  }

}
