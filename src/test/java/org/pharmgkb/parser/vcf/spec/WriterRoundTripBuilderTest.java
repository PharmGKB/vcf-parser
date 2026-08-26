package org.pharmgkb.parser.vcf.spec;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
}
