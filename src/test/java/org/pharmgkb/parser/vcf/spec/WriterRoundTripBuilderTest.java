package org.pharmgkb.parser.vcf.spec;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import ch.qos.logback.classic.Logger;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import org.junit.jupiter.api.Test;
import org.pharmgkb.parser.vcf.FormatType;
import org.pharmgkb.parser.vcf.InfoType;
import org.pharmgkb.parser.vcf.VcfContext;
import org.pharmgkb.parser.vcf.VcfMetadata;
import org.pharmgkb.parser.vcf.VcfPosition;
import org.pharmgkb.parser.vcf.VcfSample;
import org.pharmgkb.parser.vcf.VcfWarnings;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WriterRoundTrip}'s coverage is entirely parser-sourced ({@link WriterRoundTrip#parseFile}/
 * {@link WriterRoundTrip#parseText}) -- this class covers the same round-trip machinery starting from
 * directly builder-constructed {@link VcfMetadata}/{@link VcfPosition}/{@link VcfSample} objects instead,
 * through both the default writer and {@code validateBeforeWrite()} (R7, see
 * docs/superpowers/specs/2026-08-26-0138-writer-round-trip-oracle-gaps-r7-design.md).
 */
class WriterRoundTripBuilderTest {

  private static VcfContext strict() {
    return VcfContext.strict("VCFv4.3");
  }

  @Test
  void testSimpleBuilderConstructedRecordRoundTripsIdenticallyThroughBothWriterConfigurations()
      throws IOException {
    VcfContext context = strict();
    VcfMetadata metadata = new VcfMetadata.Builder(context)
        .setFileFormat("VCFv4.3")
        .setColumns(List.of("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .addFormat("GT", "Genotype", "1", FormatType.String)
        .addInfo("DP", "Total depth", InfoType.Integer, "1", null, null)
        .addFilter("PASS", "All filters passed")
        .addAlt("DEL", "Deletion")
        .build();
    ListMultimap<String, String> info = ArrayListMultimap.create();
    info.put("DP", "10");
    VcfPosition position = context.newPosition("1", 100, List.of("rs1"), "A", List.of("T"), null,
        List.of("PASS"), info, List.of("GT"), 2);
    VcfSample sample = context.newSample(List.of("GT"), List.of("0/1"));
    WriterRoundTrip.ParsedFile before =
        new WriterRoundTrip.ParsedFile(metadata, List.of(position), List.of(List.of(sample)));

    String defaultWritten = WriterRoundTrip.write(before, false);
    String validatedWritten = WriterRoundTrip.write(before, true);

    assertEquals(defaultWritten, validatedWritten,
        "a fully-compliant builder-constructed record has no reason for the two writer configurations " +
            "to produce different output");

    WriterRoundTrip.ParsedFile afterDefault = WriterRoundTrip.parseText(defaultWritten);
    Optional<String> diff = WriterRoundTrip.semanticDiff(before, afterDefault);
    assertTrue(diff.isEmpty(), () -> "default writer semantic mismatch: " + diff.orElse(""));
  }

  @Test
  void testMultiRecordMultiSampleBuilderConstructedFileRoundTripsSemantically() throws IOException {
    VcfContext context = strict();
    VcfMetadata metadata = new VcfMetadata.Builder(context)
        .setFileFormat("VCFv4.3")
        .setColumns(List.of("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1", "S2"))
        .addFormat("GT", "Genotype", "1", FormatType.String)
        .addFilter("PASS", "All filters passed")
        .build();
    VcfPosition position1 = context.newPosition("1", 100, null, "A", List.of("T"), null, List.of("PASS"),
        ArrayListMultimap.create(), List.of("GT"), 2);
    VcfPosition position2 = context.newPosition("1", 200, null, "C", List.of("G"), null, List.of("PASS"),
        ArrayListMultimap.create(), List.of("GT"), 3);
    List<VcfSample> samples1 = List.of(context.newSample(List.of("GT"), List.of("0/1")),
        context.newSample(List.of("GT"), List.of("1/1")));
    List<VcfSample> samples2 = List.of(context.newSample(List.of("GT"), List.of("0/0")),
        context.newSample(List.of("GT"), List.of("0/1")));
    WriterRoundTrip.ParsedFile before = new WriterRoundTrip.ParsedFile(metadata, List.of(position1, position2),
        List.of(samples1, samples2));

    for (boolean validate : new boolean[] {false, true}) {
      String written = WriterRoundTrip.write(before, validate);
      WriterRoundTrip.ParsedFile after = WriterRoundTrip.parseText(written);
      Optional<String> diff = WriterRoundTrip.semanticDiff(before, after);
      assertTrue(diff.isEmpty(), () -> "validateBeforeWrite=" + validate + ": " + diff.orElse(""));
    }
  }

  @Test
  void testFormatTypeConversionFailureWarnsDifferentlyButWritesIdenticallyBetweenWriterConfigurations()
      throws IOException {
    VcfContext context = strict();
    VcfMetadata metadata = new VcfMetadata.Builder(context)
        .setFileFormat("VCFv4.3")
        .setColumns(List.of("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .addFormat("XX", "Custom field", "1", FormatType.Integer)
        .addFilter("PASS", "All filters passed")
        .build();
    VcfPosition position = context.newPosition("1", 100, null, "A", List.of("T"), null, List.of("PASS"),
        ArrayListMultimap.create(), List.of("XX"), 2);
    VcfSample sample = context.newSample(List.of("XX"), List.of("abc"));
    WriterRoundTrip.ParsedFile before =
        new WriterRoundTrip.ParsedFile(metadata, List.of(position), List.of(List.of(sample)));

    Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    WarnCapturingAppender defaultAppender = new WarnCapturingAppender();
    defaultAppender.start();
    root.addAppender(defaultAppender);
    String defaultWritten;
    try {
      defaultWritten = WriterRoundTrip.write(before, false);
    } finally {
      root.detachAppender(defaultAppender);
      defaultAppender.stop();
    }

    WarnCapturingAppender validatedAppender = new WarnCapturingAppender();
    validatedAppender.start();
    root.addAppender(validatedAppender);
    String validatedWritten;
    try {
      validatedWritten = WriterRoundTrip.write(before, true);
    } finally {
      root.detachAppender(validatedAppender);
      validatedAppender.stop();
    }

    assertEquals(defaultWritten, validatedWritten,
        "the unconvertible XX value is preserved verbatim by both writer configurations -- this case " +
            "diverges only in the warning text, never in the written output");
    assertTrue(defaultAppender.anyMessageContains("Property XX for sample #0 is not of type Integer"),
        "default writer: expected its own message shape; got " +
            defaultAppender.getRelevantWarnOrHigherMessages(Set.of()));
    assertTrue(validatedAppender.anyMessageContains("FORMAT XX for sample #0 value abc is not of type Long"),
        "validateBeforeWrite(): expected its own, differently-worded message shape; got " +
            validatedAppender.getRelevantWarnOrHigherMessages(Set.of()));
    assertFalse(defaultAppender.anyMessageContains("FORMAT XX for sample #0 value abc is not of type Long"),
        "default writer: should not see validateBeforeWrite()'s message shape; got " +
            defaultAppender.getRelevantWarnOrHigherMessages(Set.of()));
    assertFalse(validatedAppender.anyMessageContains("Property XX for sample #0 is not of type Integer"),
        "validateBeforeWrite(): should not see the default writer's message shape; got " +
            validatedAppender.getRelevantWarnOrHigherMessages(Set.of()));
  }

  private record MatrixCase(String key, String description, String number, FormatType type, String value,
      boolean includeGt, String expectedWarningSubstring) {}

  private static final List<MatrixCase> AT_OR_AFTER_43_CASES = List.of(
      new MatrixCase("GLE", "custom", "1", FormatType.String, "abc", true, null),
      new MatrixCase("CNL", "Copy number genotype likelihood", "G", FormatType.Float,
          "-0.1,-0.2,-0.3,-0.2,-0.2", true, null),
      new MatrixCase("CNP", "Copy number posterior probabilities", "G", FormatType.Float,
          "0.1,0.2,0.3,0.2,0.2", true, null),
      new MatrixCase("PP", "Phred-scaled genotype posterior probabilities", "G", FormatType.Integer,
          "0,3,10", true, null),
      new MatrixCase("AD", "Total read depth for each allele", "R", FormatType.Integer, "10,5", true, null),
      new MatrixCase("ADF", "Read depth for each allele on the forward strand", "R", FormatType.Integer,
          "6,3", true, null),
      new MatrixCase("ADR", "Read depth for each allele on the reverse strand", "R", FormatType.Integer,
          "4,2", true, null),
      new MatrixCase("GP", "d", "G", FormatType.Float, "5.0,5.0,5.0", false, "does not lie in the interval"));

  // raw (pre-substitution) SLF4J message templates for the writer's/version resolver's own fallback and
  // range-check warnings -- used with WarnCapturingAppender.hasWarnOrHigherExcluding so the warning-silence
  // assertion below can tolerate exactly the warnings a given case legitimately expects and nothing else. See
  // VcfVersion.resolve, VcfWriter.writeHeader, and InternalUtils.checkFractionalRange for the source of each.
  private static final String READING_FALLBACK_RAW_TEMPLATE =
      "Reading ##fileformat={} as VCF {} -- this library does not implement {} semantics";
  private static final String WRITING_FALLBACK_RAW_TEMPLATE =
      "Writing ##fileformat={} verbatim, but its content was accepted and normalized under " +
          "VCF {} rules; validity under the declared version is not guaranteed";
  private static final String RANGE_CHECK_RAW_TEMPLATE = "{} {} value \"{}\" does not lie in the interval [0, 1]";

  private static WriterRoundTrip.ParsedFile buildMatrixParsedFile(VcfContext context, String fileFormat,
      MatrixCase c) {
    VcfMetadata.Builder mdBuilder = new VcfMetadata.Builder(context).setFileFormat(fileFormat)
        .setColumns(List.of("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        // ##contig/##SAMPLE declare the record's own CHROM ("1") and #CHROM sample column ("S1") -- without
        // them, VcfWriter's own unrelated cross-checks ("CHROM has no ##contig metadata", "samples in the
        // header but not in the metadata") would warn on every case, which the warning-silence assertion
        // below would otherwise have to (incorrectly) tolerate. Mirrors WriterRoundTripTest's
        // buildVersionMatrixVcf, which declares the same two lines for the same reason.
        .addContig("1", 1_000_000L, null, null, null, null, null)
        .addSample("S1", "S1");
    if (c.includeGt()) {
      mdBuilder.addFormat("GT", "Genotype", "1", FormatType.String);
    }
    mdBuilder.addFormat(c.key(), c.description(), c.number(), c.type());
    VcfMetadata metadata = mdBuilder.build();
    List<String> format = c.includeGt() ? List.of("GT", c.key()) : List.of(c.key());
    List<String> values = c.includeGt() ? List.of("0/1", c.value()) : List.of(c.value());
    VcfPosition position = context.newPosition("1", 100, null, "A", List.of("T"), null, null, null, format, -1);
    VcfSample sample = context.newSample(format, values);
    return new WriterRoundTrip.ParsedFile(metadata, List.of(position), List.of(List.of(sample)));
  }

  private static void assertMatrixCaseRoundTripsCleanly(Supplier<VcfContext> contextSupplier,
      String fileFormat, MatrixCase c, String expectedFallbackWarningSubstring) throws IOException {
    Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    WriterRoundTrip.ParsedFile before;
    String written;
    try {
      // contextSupplier is invoked only after the appender above is attached: VcfContext.lenient(...) resolves
      // the declared fileformat via VcfVersion.resolve, which warns immediately (VcfVersion.java) rather than
      // lazily -- constructing the fallback context before attaching the appender (as the caller previously did)
      // would lose that warning before there was anything listening for it.
      before = buildMatrixParsedFile(contextSupplier.get(), fileFormat, c);
      // validateBeforeWrite=true is required to reach checkReservedFormatConstraints at all -- see
      // VcfWriter.writeLine's m_validateBeforeWrite guard around validateInfo/validateSamples, and
      // WriterRoundTripTest's assertRoundTripsCleanly, which the same fix was applied to first.
      written = WriterRoundTrip.write(before, true);
      Set<String> ignoredTemplates = new HashSet<>();
      if (expectedFallbackWarningSubstring != null) {
        assertTrue(appender.anyMessageContains(expectedFallbackWarningSubstring),
            c.key() + ": expected the fallback warning");
        ignoredTemplates.add(READING_FALLBACK_RAW_TEMPLATE);
        ignoredTemplates.add(WRITING_FALLBACK_RAW_TEMPLATE);
      }
      if (c.expectedWarningSubstring() == null) {
        assertFalse(appender.anyMessageContains("does not lie in the interval"),
            c.key() + " (" + fileFormat + "): unexpected range-check warning");
      } else {
        assertTrue(appender.anyMessageContains(c.expectedWarningSubstring()),
            c.key() + " (" + fileFormat + "): expected a warning containing \"" + c.expectedWarningSubstring() +
                "\"");
        ignoredTemplates.add(RANGE_CHECK_RAW_TEMPLATE);
      }
      // full warning-silence assertion (beyond the specific substrings checked above): this is the check the
      // method's own "Cleanly" name promises but, until R8's final-review fix wave, never actually verified --
      // every one of the 16 builder-side matrix cases previously also emitted unasserted metadata cross-check
      // warnings (silenced above by declaring ##contig/##SAMPLE) and, for GP specifically, an unasserted
      // "Number=G requires 3" cardinality warning alongside the intended range-check warning.
      assertFalse(appender.hasWarnOrHigherExcluding(ignoredTemplates),
          c.key() + " (" + fileFormat + "): unexpected warning(s) beyond the expected ones: " +
              appender.getRelevantWarnOrHigherMessages(ignoredTemplates));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    WriterRoundTrip.ParsedFile after = WriterRoundTrip.parseText(written);
    Optional<String> diff = WriterRoundTrip.semanticDiff(before, after);
    assertTrue(diff.isEmpty(), () -> c.key() + " (" + fileFormat + "): semantic mismatch: " + diff.orElse(""));
  }

  @Test
  void testVersionSensitiveReservedKeysRoundTripAtV43AndUnderFallbackFrom44() throws IOException {
    // R8: builder-sourced counterpart to WriterRoundTripTest's parser-sourced version matrix. This file's
    // own established convention (see its class Javadoc) is direct builder construction, so the pre-4.3
    // side of the matrix is intentionally not duplicated here -- WriterRoundTripTest already covers it
    // parser-sourced, and this class's whole existing suite is 4.3-only. This test covers the 4.3 case AND
    // the "fallback" case (declared VCFv4.4 resolving to V4_3) together, via direct builder construction.
    for (MatrixCase c : AT_OR_AFTER_43_CASES) {
      // A lenient (not strict()) VCFv4.3 context is required here, unlike this file's other tests: unlike
      // VcfParser's own trusted sample construction, VcfContext.newSample's public path always runs
      // checkReservedFormatTypes eagerly (VcfSample's constructor), and strict() throws instead of warning on
      // a content violation (see VcfContext.strict's own Javadoc) -- so a strict context could never construct
      // GP's deliberately out-of-range value at all, let alone reach the writer's own warning this test targets.
      assertMatrixCaseRoundTripsCleanly(
          () -> VcfContext.lenient("VCFv4.3", VcfWarnings.DEFAULT), "VCFv4.3", c, null);
      assertMatrixCaseRoundTripsCleanly(
          () -> VcfContext.lenient("VCFv4.4", VcfWarnings.DEFAULT), "VCFv4.4", c,
          "Reading ##fileformat=VCFv4.4 as VCF 4.3 -- this library does not implement VCFv4.4 semantics");
    }
  }
}
