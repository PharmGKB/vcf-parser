package org.pharmgkb.parser.vcf;

import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.pharmgkb.parser.vcf.TestUtils.STRICT_CONTEXT;
import static org.pharmgkb.parser.vcf.TestUtils.LENIENT_CONTEXT;
import static org.pharmgkb.parser.vcf.TestUtils.lenientContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    IdDescriptionMetadata md = new IdDescriptionMetadata(LENIENT_CONTEXT, "TEST",
        InternalUtils.extractProperties(new String[]{"ID=X", "Description=\"d\"", "Custom=extra"},
            VcfWarnings.DEFAULT));
    assertEquals("extra", md.getPropertyRaw("Custom"));
  }

  @Test
  public void testMapConstructorIsNotAttributeOrderChecked() {
    // reproduces test/vcf/{4.1,4.2}/failed/failed_meta_alt_004.vcf's ##CauseOfFailure=Incorrect order of ALT
    // fields -- attribute-order checking was removed from every keyed declaration type (see
    // VCF_SPEC_AMBIGUITIES.md's "##META attribute order" entry), so this now warns about nothing at all.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      new IdDescriptionMetadata(LENIENT_CONTEXT, "TEST", InternalUtils.extractProperties(
          new String[]{"ID=DEL", "Type=String", "Number=1", "Description=\"Deletion\""}, VcfWarnings.DEFAULT));
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testTypedConstructorRejectsNullOrBlankId() {
    assertThrows(VcfFormatException.class, () -> new IdDescriptionMetadata(STRICT_CONTEXT, "TEST", null, "d"));
    assertThrows(VcfFormatException.class, () -> new IdDescriptionMetadata(STRICT_CONTEXT, "TEST", "", "d"));
    assertThrows(VcfFormatException.class, () -> new IdDescriptionMetadata(STRICT_CONTEXT, "TEST", "   ", "d"));
  }

  @Test
  public void testTypedConstructorRejectsQuotedBlankId() {
    // regression test: the typed (label, id, description) constructor now unquotes id before checking, same
    // as the Map-based path -- a caller passing the literal quoted-whitespace/quoted-empty string (e.g. via
    // VcfMetadata.Builder.addFilter(String, String)) must be rejected as blank, not accepted as a two-/
    // five-character non-blank raw ID.
    assertThrows(VcfFormatException.class, () -> new IdDescriptionMetadata(STRICT_CONTEXT, "TEST", "\"\"", "d"));
    assertThrows(VcfFormatException.class, () -> new IdDescriptionMetadata(STRICT_CONTEXT, "TEST", "\"   \"", "d"));
  }

  @Test
  public void testTypedConstructorWarnsBlankNotWhitespaceForQuotedBlankId() {
    // regression test: before the typed constructor unquoted its id parameter, a quoted-whitespace id here
    // warned "must not contain whitespace" (misleading -- the real space characters between the quotes fired
    // containsWhitespace on the raw string) instead of "is blank". Both quoted-blank shapes must now warn
    // "must not be blank" and never "must not contain whitespace".
    StringWriter sink = new StringWriter();
    new IdDescriptionMetadata(lenientContext(new VcfWarnings(sink)), "TEST", "\"   \"", "d");
    assertTrue(sink.toString().contains("must not be blank"));
    assertFalse(sink.toString().contains("must not contain whitespace"));
  }

  @Test
  public void testBlankIdWarns() {
    // regression test: a present-but-blank ID (ID="") was previously silently accepted for this class (shared
    // by ##ALT, ##FILTER, and ##SAMPLE) -- warnIfMissing only tests for null, and the whitespace check below it
    // doesn't fire either, since an empty string has no characters to contain whitespace. Only reachable via the
    // Map-based constructor or live mutation, since the typed (label, id) constructor already throws for both
    // null and blank.
    Map<String, String> props = InternalUtils.extractProperties(
        new String[] {"ID=", "Description=\"d\""}, VcfWarnings.DEFAULT);
    StringWriter sink = new StringWriter();
    new IdDescriptionMetadata(lenientContext(new VcfWarnings(sink)), "TEST", props);
    assertTrue(sink.toString().contains("metadata ID is blank"));
  }

  @Test
  public void testWhitespaceOnlyIdWarnsAsBlankNotAsContainingWhitespace() {
    // regression test: a whitespace-only ID (e.g. "   ") previously fell through to only the "must not contain
    // whitespace" warning below, never the "is blank" one -- id.isEmpty() is false for a non-zero-length
    // whitespace-only string, so the blank check above never fired. That wording misleadingly reads as "trim it
    // and it's fine," when there is no usable content at all once trimmed. Built via a hand-populated Map
    // (not InternalUtils.extractProperties) so the raw "   " value definitely survives unmodified into the ID
    // property, the same way ContigMetadataTest's own whitespace test builds its map directly.
    Map<String, String> map = new HashMap<>();
    map.put("ID", "   ");
    map.put("Description", "\"d\"");
    StringWriter sink = new StringWriter();
    new IdDescriptionMetadata(lenientContext(new VcfWarnings(sink)), "TEST", map);
    assertTrue(sink.toString().contains("metadata ID is blank"));
    assertFalse(sink.toString().contains("must not contain whitespace"));
  }

  @Test
  public void testQuotedEmptyIdWarnsAsBlank() {
    // regression test for the retired WONT_FIX.md entry: a quoted-empty ID (ID="") arrives at init() as the
    // raw two-character string "\"\"" -- getPropertyRaw(ID).trim().isEmpty() is false for that literal string
    // (the quote characters aren't whitespace), so it previously escaped every check below with no diagnostic
    // at all. init() now reads getPropertyUnquoted(ID), which strips the quotes first, same as testBlankIdWarns'
    // unquoted equivalent above.
    Map<String, String> props = InternalUtils.extractProperties(
        new String[] {"ID=\"\"", "Description=\"d\""}, VcfWarnings.DEFAULT);
    StringWriter sink = new StringWriter();
    new IdDescriptionMetadata(lenientContext(new VcfWarnings(sink)), "FILTER", props);
    assertTrue(sink.toString().contains("metadata ID is blank"));
    assertFalse(sink.toString().contains("must not contain whitespace"));
  }

  @Test
  public void testQuotedWhitespaceOnlyIdWarnsAsBlank() {
    // regression test for the retired WONT_FIX.md entry: a quoted-whitespace ID (ID="   ") arrives at init() as
    // the raw five-character string "\"   \"" (quotes included) -- its real space characters make
    // containsWhitespace(id) fire on the raw string, so this previously warned "must not contain whitespace"
    // (the wrong diagnostic) instead of "is blank". init() now reads getPropertyUnquoted(ID) first, so the
    // quotes are stripped before either check runs, same as testWhitespaceOnlyIdWarnsAsBlankNotAsContainingWhitespace's
    // unquoted equivalent above.
    Map<String, String> props = InternalUtils.extractProperties(
        new String[] {"ID=\"   \"", "Description=\"d\""}, VcfWarnings.DEFAULT);
    StringWriter sink = new StringWriter();
    new IdDescriptionMetadata(lenientContext(new VcfWarnings(sink)), "FILTER", props);
    assertTrue(sink.toString().contains("metadata ID is blank"));
    assertFalse(sink.toString().contains("must not contain whitespace"));
  }

  @Test
  public void testMapConstructorThrowsOnQuotedBlankIdUnderStrict() {
    // strict-context counterpart of testQuotedEmptyIdWarnsAsBlank/testQuotedWhitespaceOnlyIdWarnsAsBlank: the
    // same still-quoted raw content must throw under a strict VcfContext, not just warn
    Map<String, String> emptyProps = InternalUtils.extractProperties(
        new String[] {"ID=\"\"", "Description=\"d\""}, VcfWarnings.DEFAULT);
    assertThrows(VcfFormatException.class, () -> new IdDescriptionMetadata(STRICT_CONTEXT, "FILTER", emptyProps));
    Map<String, String> whitespaceProps = InternalUtils.extractProperties(
        new String[] {"ID=\"   \"", "Description=\"d\""}, VcfWarnings.DEFAULT);
    assertThrows(VcfFormatException.class,
        () -> new IdDescriptionMetadata(STRICT_CONTEXT, "FILTER", whitespaceProps));
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
      new IdDescriptionMetadata(LENIENT_CONTEXT, "TEST",
          InternalUtils.extractProperties(new String[]{"ID=A B", "Description=\"d\""}, VcfWarnings.DEFAULT));
      assertTrue(appender.anyMessageContains("must not contain whitespace"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testWarningEmbedsCallerSuppliedLabelNotSharedClassName() {
    // Regression test: ALT/FILTER/SAMPLE all share this concrete class, so before the
    // label mechanism existed, every warning said "IdDescriptionMetadata" regardless of which of the three it was.
    for (String label : new String[] { "ALT", "FILTER", "SAMPLE" }) {
      ch.qos.logback.classic.Logger root =
          (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
      org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
          new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
      appender.start();
      root.addAppender(appender);
      try {
        new IdDescriptionMetadata(LENIENT_CONTEXT, label,
            InternalUtils.extractProperties(new String[]{"ID=A B", "Description=\"d\""}, VcfWarnings.DEFAULT));
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
        () -> new IdDescriptionMetadata(STRICT_CONTEXT, "FILTER", "bad id", "some description"));
    assertTrue(ex.getMessage().contains("must not contain whitespace"));
  }

  @Test
  public void testMapConstructorRequiresContextInstance() {
    Map<String, String> props = InternalUtils.extractProperties(
        new String[] {"ID=bad id", "Description=\"d\""}, VcfWarnings.DEFAULT);
    assertThrows(NullPointerException.class, () -> new IdDescriptionMetadata(null, "FILTER", props));
  }

  @Test
  public void testMapConstructorWarnsForWhitespaceInIdToSuppliedSink() {
    Map<String, String> props = InternalUtils.extractProperties(
        new String[] {"ID=bad id", "Description=\"d\""}, VcfWarnings.DEFAULT);
    StringWriter sink = new StringWriter();
    new IdDescriptionMetadata(lenientContext(new VcfWarnings(sink)), "FILTER", props);
    assertTrue(sink.toString().contains("must not contain whitespace"));
  }

  @Test
  public void testTypedConstructorRejectsCommaInIdInsteadOfWarning() {
    // regression test: IdMetadata's generic ID-grammar check previously only threw (strict path) for whitespace,
    // never a comma, for every type sharing this class (##FILTER, ##SAMPLE, ##ALT)
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> new IdDescriptionMetadata(STRICT_CONTEXT, "FILTER", "A,B", "some description"));
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
    new IdDescriptionMetadata(lenientContext(new VcfWarnings(sink)), "FILTER", props);
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
    new IdDescriptionMetadata(lenientContext(new VcfWarnings(sink)), "ALT", props);
    assertTrue(sink.toString().contains("must not contain a comma"));
  }

  @Test
  public void testTypedConstructorDoesNotCallWarnBasedInit() {
    // regression guard: the typed constructor must not warn even to the shared logger for a well-formed input --
    // if a future change accidentally reintroduces a call to init() here, this would still pass (init() is a
    // no-op for well-formed input), so this test's real value is documented in testTypedConstructorRejectsWhitespaceInIdInsteadOfWarning
    // actually exercising the throw path instead of a silent warn
    IdDescriptionMetadata md = new IdDescriptionMetadata(STRICT_CONTEXT, "FILTER", "q10", "quality below 10");
    assertEquals("q10", md.getId());
    assertEquals("quality below 10", md.getDescription());
  }

  @Test
  public void testSampleWithMissingDescriptionDoesNotWarnEitherConstructor() {
    // regression test: no VCF version (4.1-4.5) has a "required fields" sentence for ##SAMPLE, unlike
    // ##ALT/##FILTER (VCFv4.4.tex:180/:201) -- HTS_SPECS_BASELINE_STATUS.md's own recorded reading is "no spec
    // text (4.2-4.5) marks any ##SAMPLE field but ID as mandatory." A missing Description must not warn for
    // SAMPLE, on either constructor shape, even under a lenient context.
    StringWriter typedSink = new StringWriter();
    new IdDescriptionMetadata(lenientContext(new VcfWarnings(typedSink)), "SAMPLE", "S1", null);
    assertFalse(typedSink.toString().contains("required metadata property"));

    Map<String, String> props = InternalUtils.extractProperties(
        new String[] {"ID=S1", "Assay=WholeGenome"}, VcfWarnings.DEFAULT);
    StringWriter mapSink = new StringWriter();
    new IdDescriptionMetadata(lenientContext(new VcfWarnings(mapSink)), "SAMPLE", props);
    assertFalse(mapSink.toString().contains("required metadata property"));
  }

  @Test
  public void testSampleWithMissingDescriptionIsConstructibleUnderStrictContext() {
    // the same content that must not warn under a lenient context (see the test just above) must also not
    // throw under a strict one -- a spec-legal ##SAMPLE declaration must be constructible either way
    IdDescriptionMetadata md = new IdDescriptionMetadata(STRICT_CONTEXT, "SAMPLE", "S1", null);
    assertEquals("S1", md.getId());
    assertNull(md.getDescription());
  }

  @Test
  public void testAltAndFilterStillWarnForMissingDescription() {
    // regression guard: the SAMPLE exemption above must not have loosened ALT/FILTER's genuine requirement
    // (VCFv4.4.tex:180/:201) -- both still warn on either constructor shape
    for (String label : new String[] { "ALT", "FILTER" }) {
      StringWriter typedSink = new StringWriter();
      new IdDescriptionMetadata(lenientContext(new VcfWarnings(typedSink)), label, "X", null);
      assertTrue(typedSink.toString().contains("required metadata property"), label + " (typed) should warn");

      Map<String, String> props = InternalUtils.extractProperties(new String[] {"ID=X"}, VcfWarnings.DEFAULT);
      StringWriter mapSink = new StringWriter();
      new IdDescriptionMetadata(lenientContext(new VcfWarnings(mapSink)), label, props);
      assertTrue(mapSink.toString().contains("required metadata property"), label + " (map) should warn");
    }
  }

  @Test
  public void testSampleDescriptionStillCheckedForQuotingWhenPresent() {
    // the SAMPLE exemption is scoped to the missing-Description requirement only -- a present-but-unquoted
    // Description on ##SAMPLE must still warn, via the Map-based constructor's init() (the only shape that can
    // even represent an unquoted Description -- the typed constructor always quotes it itself)
    Map<String, String> props = InternalUtils.extractProperties(
        new String[] {"ID=S1", "Description=unquoted"}, VcfWarnings.DEFAULT);
    StringWriter sink = new StringWriter();
    new IdDescriptionMetadata(lenientContext(new VcfWarnings(sink)), "SAMPLE", props);
    assertTrue(sink.toString().contains("should be quoted"));
  }

}
