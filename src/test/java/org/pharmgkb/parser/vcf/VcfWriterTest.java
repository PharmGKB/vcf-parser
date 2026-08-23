package org.pharmgkb.parser.vcf;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pharmgkb.parser.vcf.spec.FailingWriter;

import static org.pharmgkb.parser.vcf.TestUtils.LENIENT_CONTEXT;
import static org.pharmgkb.parser.vcf.TestUtils.STRICT_CONTEXT;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link VcfWriter}.
 * @author Douglas Myers-Turnbull
 */
public class VcfWriterTest {

  /**
   * Builds a {@link VcfMetadata} through its package-private, trusted constructor, which copies but does not
   * re-validate its collections' <em>contents</em>.
   * <p>
   * This is the metadata-layer counterpart of the {@code VcfContext.newPositionCopyingTrustedFields()} and
   * {@code VcfContext.newTrustedSample()} bypasses {@code VcfPositionTest}/{@code VcfSampleTest} already use for
   * the same purpose. Freezing the metadata layer made every {@code VcfMetadata} collection getter an
   * unmodifiable view and deleted every instance mutator, so no public API route can produce the malformed
   * containers several of {@code VcfWriter}'s defensive checks below exist to catch -- a null declaration under
   * a map key, a null raw-property key or value, a duplicate PEDIGREE ID, a line terminator in a raw property.
   * Those checks are still live code, so their tests must keep running against them rather than being deleted
   * along with the public route that used to reach them.
   *
   * @param info the INFO declarations, or {@code null} for none
   * @param pedigrees the PEDIGREE declarations, or {@code null} for none
   * @param rawProperties the generic {@code ##key=value} properties, or {@code null} for none
   */
  private static VcfMetadata trustedMetadata(String fileFormat,
      Map<String, InfoMetadata> info, List<PedigreeMetadata> pedigrees,
      ListMultimap<String, String> rawProperties) {
    return new VcfMetadata(fileFormat, null, info, null, null, null, null, null, pedigrees, List.of(), rawProperties,
        LENIENT_CONTEXT);
  }

  /** Convenience for the common case: only one bad generic raw property matters to the test. */
  private static VcfMetadata trustedMetadataWithRawProperty(String key, String value) {
    ListMultimap<String, String> properties = ArrayListMultimap.create();
    properties.put(key, value);
    return trustedMetadata("VCFv4.2", null, null, properties);
  }

  @Test
  public void testMidWriteLineWarningReportsTheLineCurrentlyBeingWritten() throws Exception {
    // regression test: a warning fired while writeLine() is still building the current data line used to read
    // m_lineNumber before this line's own printLine() call had run, under-reporting by exactly one line. A
    // metadata-only header (no INFO/FILTER/FORMAT/ALT/contig/SAMPLE/PEDIGREE declared, no samples) always emits
    // exactly 2 header lines (##fileformat and #CHROM), so the first data line written afterward is line 3.
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    ListMultimap<String, String> info = ArrayListMultimap.create();
    info.put("AD", "1"); // AD is not declared in metadata's (empty) INFO map
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", null, VcfFloat.of(new BigDecimal("0")), null, info,
        null, -1);

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      writer.writeLine(metadata, position, Collections.emptyList());
      assertTrue(appender.anyMessageContains("(on line 3)"));
      assertFalse(appender.anyMessageContains("(on line 2)"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testDefaultWriteRoundTripsSpecialQualityValuesByteIdentical() throws IOException {
    // QUAL's default write path prefers getRawQuality() whenever it exists, matching every other field --
    // Inf/+Inf/-Inf/NaN must round-trip exactly, not get reformatted through VcfFloat.toString() (which
    // would normalize casing/spelling, e.g. "Inf" -> "INFINITY")
    for (String rawQual : new String[] {"Inf", "+Inf", "-Inf", "NaN"}) {
      StringWriter sw = new StringWriter();
      VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
      VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").build();
      writer.writeHeader(metadata);
      VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, "A", null, -1).toBuilder()
          .rawQuality(rawQual)
          .build();
      writer.writeLine(metadata, position, Collections.emptyList());
      assertTrue(sw.toString().contains("\t" + rawQual + "\t"),
          "expected raw QUAL '" + rawQual + "' preserved verbatim; got: " + sw);
    }
  }

  @Test
  public void testWriteBuilderConstructedSpecialQualityFormatsViaVcfFloat() throws IOException {
    // no raw wire text exists for a Builder-constructed QUAL -- the write path falls through to formatting
    // getQuality()'s VcfFloat value directly
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, "A", null, -1).toBuilder()
        .quality(VcfFloat.ofSpecial(VcfFloat.Special.POSITIVE_INFINITY))
        .build();
    writer.writeLine(metadata, position, Collections.emptyList());
    assertTrue(sw.toString().contains("\tINFINITY\t"), "expected formatted special QUAL; got: " + sw);
  }

  @Test
  public void testValidateBeforeWriteWarnsForGrammarMismatchedQualButStillWritesRawText() throws Exception {
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, "A", null, -1).toBuilder()
        .rawQuality("5.")
        .build();

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      writer.writeLine(metadata, position, Collections.emptyList());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    // the grammar-mismatch warning comes from getQuality()'s own parse, routed to the position's own
    // construction-time sink (LENIENT_CONTEXT's shared logger), not this writer's own warnings destination
    assertTrue(appender.anyMessageContains("does not match the VCF Float grammar"));
    // raw text is written verbatim regardless -- validateBeforeWrite() only adds a diagnostic, never
    // reformats the output
    assertTrue(sw.toString().contains("\t5.\t"));
  }

  @Test
  public void testValidateBeforeWriteWarnsForUnparseableQualButStillWritesRawText() throws Exception {
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, "A", null, -1).toBuilder()
        .rawQuality("not-a-number")
        .build();

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      writer.writeLine(metadata, position, Collections.emptyList());
      assertTrue(appender.anyMessageContains("is not a number"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertTrue(sw.toString().contains("\tnot-a-number\t"));
  }

  @Test
  public void testDefaultWriteEmitsNoWarningForGrammarMismatchedQualAndWritesRawText() throws Exception {
    // mirrors testValidateBeforeWriteWarnsForGrammarMismatchedQualButStillWritesRawText, minus
    // validateBeforeWrite() -- pins the other half of d569cd5's own BREAKING CHANGE: in DEFAULT mode,
    // writeLine() no longer calls getQuality() at all when raw text exists, so this grammar-mismatch
    // warning must NOT fire merely by writing the record
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, "A", null, -1).toBuilder()
        .rawQuality("5.")
        .build();

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      writer.writeLine(metadata, position, Collections.emptyList());
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertTrue(sw.toString().contains("\t5.\t"));
  }

  @Test
  public void testDefaultWriteDoesNotThrowForUnparseableQualAndWritesRawText() throws Exception {
    // mirrors testValidateBeforeWriteWarnsForUnparseableQualButStillWritesRawText, minus
    // validateBeforeWrite() -- pins AGENTS.md's Writer Round-Trip Policy ("a record accepted by the parser
    // must be writable without losing fields") for QUAL specifically in DEFAULT mode: writeLine() must not
    // throw here, and must preserve the malformed text verbatim, even though it never calls getQuality() at
    // all on this path
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, "A", null, -1).toBuilder()
        .rawQuality("not-a-number")
        .build();
    assertDoesNotThrow(() -> writer.writeLine(metadata, position, Collections.emptyList()));
    assertTrue(sw.toString().contains("\tnot-a-number\t"));
  }

  @Test
  public void testDefaultWriteDropsEmptyIdEntry() throws Exception {
    // regression test: the default path previously only null-checked ID/FILTER/ALT entries, silently writing a
    // zero-length VCF field for a non-null empty string. ID is position-independent (nothing indexes into it), so
    // EMPTY_FIELD_HANDLING.md's strategy is to drop the empty entry, matching the parser's own lenient behavior.
    //
    // VcfPosition's own construction-time checkIds() already drops an empty ID entry (with the identical
    // warning) before a position holding one can exist at all, under both strict and lenient contexts -- so this
    // uses the package-private newPositionCopyingTrustedFields() factory (reachable here only because this test
    // class is in the same package as VcfPosition/VcfContext) to deliberately bypass that check, the same way
    // Builder#build() trusts an unchanged field without re-checking it. This is not testing that a caller can
    // reach this state through the public API -- it can't -- it is testing that VcfWriter.addListOrElse()'s own,
    // separately-implemented copy of this drop-vs-substitute decision (see its own comment) still does the right
    // thing if it were ever reached, since nothing else in this test suite exercises that specific method.
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    VcfPosition position = LENIENT_CONTEXT.newPositionCopyingTrustedFields("chr1", 1,
        Arrays.asList("rs1", "", "rs2"), "A", null, VcfFloat.of(new BigDecimal("0")), null, null, null, -1, null, null);

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      writer.writeLine(metadata, position, Collections.emptyList());
      assertTrue(sw.toString().contains("\trs1;rs2\t"));
      assertTrue(appender.anyMessageContains("ID contains an empty entry"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testDefaultWriteDropsAllEmptyIdEntriesRendersMissingValue() throws Exception {
    // edge case: dropping every entry must still render the field's own missing-value sentinel, not an
    // accidentally-empty column. See testDefaultWriteDropsEmptyIdEntry for why this uses
    // newPositionCopyingTrustedFields() rather than ordinary construction.
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    VcfPosition position = LENIENT_CONTEXT.newPositionCopyingTrustedFields("chr1", 1, Arrays.asList("", ""), "A",
        null, VcfFloat.of(new BigDecimal("0")), null, null, null, -1, null, null);

    writer.writeLine(metadata, position, Collections.emptyList());
    // pin down the ID field specifically (between POS and REF) -- a loose "\t.\t" check would trivially match
    // the unrelated ALT/QUAL/FILTER fields elsewhere in the same line, which also render as "."
    assertTrue(sw.toString().contains("chr1\t1\t.\tA\t"));
  }

  @Test
  public void testDefaultWriteDropsEmptyFilterEntry() throws Exception {
    // see testDefaultWriteDropsEmptyIdEntry for why this uses newPositionCopyingTrustedFields() rather than
    // ordinary construction -- checkFilters() would otherwise already drop the empty entry at construction time.
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    VcfPosition position = LENIENT_CONTEXT.newPositionCopyingTrustedFields("chr1", 1, null, "A", null,
        VcfFloat.of(new BigDecimal("0")), Arrays.asList("q10", ""), null, null, -1, null, null);

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      writer.writeLine(metadata, position, Collections.emptyList());
      assertTrue(sw.toString().contains("\tq10\t"));
      assertTrue(appender.anyMessageContains("FILTER contains an empty entry"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testDefaultWriteSubstitutesEmptyAltEntryWithDot() throws Exception {
    // ALT is position-dependent (GT genotype values index into it), so an empty entry is substituted with "."
    // rather than dropped -- dropping would shift every later allele's index. See testDefaultWriteDropsEmptyIdEntry
    // for why this uses newPositionCopyingTrustedFields() rather than ordinary construction -- checkAltBases()
    // would otherwise already substitute the empty entry at construction time.
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    VcfPosition position = LENIENT_CONTEXT.newPositionCopyingTrustedFields("chr1", 1, null, "A",
        Arrays.asList("T", "", "C"), VcfFloat.of(new BigDecimal("0")), null, null, null, -1, null, null);

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      writer.writeLine(metadata, position, Collections.emptyList());
      assertTrue(sw.toString().contains("\tT,.,C\t"));
      assertTrue(appender.anyMessageContains("ALT contains an empty entry"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testDefaultWriteSubstitutesEmptyAltEntryWithDotUnconditionallyAcrossDeclaredVersions() throws Exception {
    // Task 7: EMPTY_FIELD_HANDLING.md's ALT section states the '.' substitution is unconditional -- GT indexes
    // into ALT positionally (VCFv4.2.tex line 217), so index preservation is a 4.2-grounded requirement, not a
    // 4.3+ one; VCFv4.3.tex line 350 / VCFv4.4.tex line 367 / VCFv4.5.tex line 416 merely ratify '.' as a
    // sanctioned per-entry ALT token, a claim 4.2 is silent on rather than one 4.2 forbids. Pins that
    // addListOrElse()'s substitution branch (see testDefaultWriteSubstitutesEmptyAltEntryWithDot for why this
    // uses newPositionCopyingTrustedFields() rather than ordinary construction) behaves identically whether the
    // header declares VCFv4.2 or VCFv4.3 -- a future version-gated branch here would be a regression, not a fix.
    assertEquals(writeAltThroughTrustedFactory("VCFv4.2", Arrays.asList("T", "", "C")),
        writeAltThroughTrustedFactory("VCFv4.3", Arrays.asList("T", "", "C")));
    assertEquals("T,.,C", writeAltThroughTrustedFactory("VCFv4.2", Arrays.asList("T", "", "C")));
    assertEquals("T,.,C", writeAltThroughTrustedFactory("VCFv4.3", Arrays.asList("T", "", "C")));

    assertEquals(writeAltThroughTrustedFactory("VCFv4.2", Arrays.asList("T", "")),
        writeAltThroughTrustedFactory("VCFv4.3", Arrays.asList("T", "")));
    assertEquals("T,.", writeAltThroughTrustedFactory("VCFv4.2", Arrays.asList("T", "")));
    assertEquals("T,.", writeAltThroughTrustedFactory("VCFv4.3", Arrays.asList("T", "")));
  }

  /**
   * Writes a single record whose ALT column is {@code altBases}, built through the package-private
   * {@code newPositionCopyingTrustedFields()} factory under a context declaring {@code declaredFileFormat} --
   * see {@link #testDefaultWriteSubstitutesEmptyAltEntryWithDot} for why this factory is used instead of
   * ordinary construction. Confirms, before writing, that the built position still carries the raw empty entry
   * (proving {@code checkAltBases()} did not already normalize it, so the assertion on the written output below
   * actually exercises {@code addListOrElse()}'s own substitution branch rather than passing vacuously), then
   * returns the serialized ALT column text.
   *
   * @return the ALT column of the single written data line
   */
  private static String writeAltThroughTrustedFactory(String declaredFileFormat, List<String> altBases)
      throws Exception {
    VcfContext context = VcfContext.lenient(declaredFileFormat, VcfWarnings.DEFAULT);
    VcfPosition position = context.newPositionCopyingTrustedFields("chr1", 1, null, "A", altBases,
        VcfFloat.of(new BigDecimal("0")), null, null, null, -1, null, null);
    assertTrue(position.getAltBases().contains(""),
        "position must still carry the raw empty ALT entry for this test to exercise the writer's own " +
            "substitution branch, not a pre-normalized value");

    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder(context).setFileFormat(declaredFileFormat).build();
    writer.writeHeader(metadata);

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      writer.writeLine(metadata, position, Collections.emptyList());
      assertTrue(appender.anyMessageContains("ALT contains an empty entry"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }

    String[] lines = sw.toString().split("\n");
    String[] columns = lines[lines.length - 1].split("\t", -1);
    return columns[4]; // #CHROM POS ID REF ALT ...
  }

  @Test
  public void testDefaultWriteRejectsNullIdEntryInsteadOfStringifyingIt() throws Exception {
    // regression test: a null ID entry is never reachable via any public API construction path (VcfPosition's
    // own checkIds() rejects it in every ordinary constructor call, in both strict and lenient contexts), but
    // VcfContext.newPositionCopyingTrustedFields skips checkIds outright and is a real production fast path
    // (VcfPosition.Builder.build()), not a test-only bypass -- see addListOrElse()'s own comment. Left unguarded,
    // StringBuilder.append(Object) would otherwise silently stringify a null entry into the literal text "null"
    // instead of a clean rejection. See testDefaultWriteDropsEmptyIdEntry for why this uses that factory rather
    // than ordinary construction.
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    VcfPosition position = LENIENT_CONTEXT.newPositionCopyingTrustedFields("chr1", 1,
        Arrays.asList("rs1", null), "A", null, VcfFloat.of(new BigDecimal("0")), null, null, null, -1, null, null);

    VcfFormatException e = assertThrows(VcfFormatException.class,
        () -> writer.writeLine(metadata, position, Collections.emptyList()));
    assertEquals("ID contains a null entry", e.getMessage());
  }

  @Test
  public void testDefaultWriteRejectsNullAltEntryInsteadOfStringifyingIt() throws Exception {
    // same reasoning as testDefaultWriteRejectsNullIdEntryInsteadOfStringifyingIt, for ALT instead of ID --
    // checkAltBases() rejects a null entry unconditionally, so newPositionCopyingTrustedFields (which skips it)
    // is again the only route that can reach addListOrElse()'s own null guard.
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    VcfPosition position = LENIENT_CONTEXT.newPositionCopyingTrustedFields("chr1", 1, null, "A",
        Arrays.asList("T", null), VcfFloat.of(new BigDecimal("0")), null, null, null, -1, null, null);

    VcfFormatException e = assertThrows(VcfFormatException.class,
        () -> writer.writeLine(metadata, position, Collections.emptyList()));
    assertEquals("ALT contains a null entry", e.getMessage());
  }

  @Test
  public void testDefaultWriteRejectsNullFilterEntryInsteadOfStringifyingIt() throws Exception {
    // same reasoning as testDefaultWriteRejectsNullIdEntryInsteadOfStringifyingIt, for FILTER instead of ID --
    // checkFilters() rejects a null entry unconditionally, so newPositionCopyingTrustedFields (which skips it)
    // is again the only route that can reach addListOrElse()'s own null guard.
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    VcfPosition position = LENIENT_CONTEXT.newPositionCopyingTrustedFields("chr1", 1, null, "A", null,
        VcfFloat.of(new BigDecimal("0")), Arrays.asList("q10", null), null, null, -1, null, null);

    VcfFormatException e = assertThrows(VcfFormatException.class,
        () -> writer.writeLine(metadata, position, Collections.emptyList()));
    assertEquals("FILTER contains a null entry", e.getMessage());
  }

  @Test
  public void testDefaultWriteRejectsNullFormatSubFieldNameInsteadOfStringifyingIt() throws Exception {
    // regression test: a null FORMAT sub-field name is never reachable via any public API construction path
    // (VcfPosition's own checkFormat() rejects it unconditionally), but VcfContext.newPositionWithTrustedFormat/
    // newPositionCopyingTrustedFields skip checkFormat outright and are real production fast paths (VcfParser's
    // per-record FORMAT cache, VcfPosition.Builder.build()), not test-only bypasses -- see
    // addFormatConditionally()'s own comment. Left unguarded, StringBuilder.append(Object) would otherwise
    // silently stringify a null key into the literal text "null" instead of a clean rejection.
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .build();
    writer.writeHeader(metadata);
    VcfPosition position = LENIENT_CONTEXT.newPositionCopyingTrustedFields("chr1", 1, null, "A", null,
        VcfFloat.of(new BigDecimal("0")), null, null, Arrays.asList("GT", null), -1, null, null);
    VcfSample sample = LENIENT_CONTEXT.newSample(new LinkedHashMap<>(Map.of("GT", "0/1")));

    VcfFormatException e = assertThrows(VcfFormatException.class,
        () -> writer.writeLine(metadata, position, Collections.singletonList(sample)));
    assertEquals("FORMAT contains a null sub-field name", e.getMessage());
  }

  @Test
  public void testDefaultWriteRejectsNullInfoKeyInsteadOfStringifyingIt() throws Exception {
    // regression test: a null INFO key is never reachable via any public API construction path (VcfPosition's
    // own checkInfoEntries() rejects it unconditionally), but VcfContext.newPositionCopyingTrustedFields skips
    // checkInfoEntries outright and is a real production fast path (VcfPosition.Builder.build()), not a
    // test-only bypass -- see addInfoOrDot()'s own comment.
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    ListMultimap<String, String> info = ArrayListMultimap.create();
    info.put(null, "1");
    VcfPosition position = LENIENT_CONTEXT.newPositionCopyingTrustedFields("chr1", 1, null, "A", null,
        VcfFloat.of(new BigDecimal("0")), null, info, null, -1, null, null);

    VcfFormatException e = assertThrows(VcfFormatException.class,
        () -> writer.writeLine(metadata, position, Collections.emptyList()));
    assertEquals("INFO column contains a null key", e.getMessage());
  }

  @Test
  public void testDefaultWriteRejectsNullInfoValueInsteadOfStringifyingIt() throws Exception {
    // same reasoning as testDefaultWriteRejectsNullInfoKeyInsteadOfStringifyingIt, for an INFO value instead of
    // an INFO key.
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    ListMultimap<String, String> info = ArrayListMultimap.create();
    info.put("DP", null);
    VcfPosition position = LENIENT_CONTEXT.newPositionCopyingTrustedFields("chr1", 1, null, "A", null,
        VcfFloat.of(new BigDecimal("0")), null, info, null, -1, null, null);

    VcfFormatException e = assertThrows(VcfFormatException.class,
        () -> writer.writeLine(metadata, position, Collections.emptyList()));
    assertEquals("INFO value for key \"DP\" is null", e.getMessage());
  }

  @Test
  public void testDefaultWriteCanonicalizesEmptyNonFlagInfoValueInsteadOfWritingBareFlagKey() throws Exception {
    // position.getInfo() is a live view a caller can mutate directly (e.g. a transformation pipeline). A single
    // empty-string value is the same internal representation VcfPosition.info() uses for a genuine Flag (no "="
    // in the original text), so writing it as a bare key here would silently reinterpret this declared
    // Type=String field as a Flag on reparse -- addInfoOrDot() must canonicalize to the "." missing-value
    // sentinel instead, on the default path too, not just under validateBeforeWrite(). Also confirms this
    // doesn't double-warn: this same value also flows through addInfoOrDot()'s own default-path "just test"
    // type-conversion probe loop, which must skip an empty value rather than triggering
    // InternalUtils.convertProperty's own (context-free) "is an empty entry" warning in addition to this
    // branch's own "has an empty value but is declared Type=" one.
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .addInfo(new InfoMetadata(LENIENT_CONTEXT, "XX", "d", InfoType.String, "1", null, null))
        .build();
    writer.writeHeader(metadata);
    ListMultimap<String, String> info = ArrayListMultimap.create();
    info.put("XX", "");
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", null, VcfFloat.of(new BigDecimal("0")), null, info,
        null, -1);

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      writer.writeLine(metadata, position, Collections.emptyList());
      assertTrue(sw.toString().contains("XX=."));
      assertEquals(1, appender.countMessagesContaining("INFO XX has an empty value but is declared Type="));
      assertFalse(appender.anyMessageContains("is an empty entry"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testValidateBeforeWriteCanonicalizesEmptyNonFlagInfoValueInsteadOfWritingBareFlagKey() throws Exception {
    // validateInfoValue() already warned about this exact case ("INFO {} contains an empty value") under
    // validateBeforeWrite(), but that warning had no effect on what addInfoOrDot() actually wrote afterward in
    // the same writeLine() call -- it fired, yet the output was neither preserved nor canonicalized, violating
    // this mode's own "warn and canonicalize" contract. Confirms the fix applies in both write modes.
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .addInfo(new InfoMetadata(LENIENT_CONTEXT, "XX", "d", InfoType.String, "1", null, null))
        .build();
    writer.writeHeader(metadata);
    ListMultimap<String, String> info = ArrayListMultimap.create();
    info.put("XX", "");
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", null, VcfFloat.of(new BigDecimal("0")), null, info,
        null, -1);

    writer.writeLine(metadata, position, Collections.emptyList());
    assertTrue(sw.toString().contains("XX=."));
  }

  @Test
  public void testWriteLinePreservesGenuineFlagValueAsBareKey() throws Exception {
    // a key declared Type=Flag must still be written bare (no "="); this is the one empty-value case
    // addInfoOrDot()'s new canonicalization must NOT touch
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .addInfo(new InfoMetadata(LENIENT_CONTEXT, "DB", "d", InfoType.Flag, "0", null, null))
        .build();
    writer.writeHeader(metadata);
    ListMultimap<String, String> info = ArrayListMultimap.create();
    info.put("DB", "");
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", null, VcfFloat.of(new BigDecimal("0")), null, info,
        null, -1);

    writer.writeLine(metadata, position, Collections.emptyList());
    assertTrue(sw.toString().contains("DB"));
    assertFalse(sw.toString().contains("DB="));
  }

  @Test
  public void testWriteLineCanonicalizesEmptyInfoValueWhenDeclaredTypeIsUnparseable() throws Exception {
    // a key that IS declared but whose Type doesn't parse to a real InfoType (info.getType() == null) gets no
    // benefit of the doubt the way a completely undeclared key does: a garbled/absent Type is far more likely a
    // malformed non-Flag declaration than an accidental near-miss on the literal "Flag", so this must still
    // canonicalize -- not be treated the same as the undeclared-key case above
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    Map<String, String> props = new LinkedHashMap<>();
    props.put("ID", "XX");
    props.put("Number", "1");
    props.put("Type", "NotARealType");
    props.put("Description", "\"d\"");
    InfoMetadata info = new InfoMetadata(LENIENT_CONTEXT, props);
    assertNull(info.getType());
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").addInfo(info).build();
    writer.writeHeader(metadata);
    ListMultimap<String, String> positionInfo = ArrayListMultimap.create();
    positionInfo.put("XX", "");
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", null, VcfFloat.of(new BigDecimal("0")), null,
        positionInfo, null, -1);

    writer.writeLine(metadata, position, Collections.emptyList());
    assertTrue(sw.toString().contains("XX=."));
  }

  @Test
  public void testWriteLinePreservesUndeclaredInfoFlagAsBareKeyInsteadOfCanonicalizing() throws Exception {
    // an INFO key entirely absent from the ##INFO metadata is ambiguous -- it could be a genuine Flag with no
    // header declaration (a long-standing, deliberately tolerated case: EMPTY_FIELD_HANDLING.md's "INFO flag
    // (no =)" row) or a caller-mutated empty value for some other undeclared key. With no positive evidence
    // either way, addInfoOrDot() must keep the existing bare-key round-trip rather than risk breaking the
    // documented, tested undeclared-Flag case just to fix the declared-non-Flag case above
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    ListMultimap<String, String> info = ArrayListMultimap.create();
    info.put("DB", "");
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", null, VcfFloat.of(new BigDecimal("0")), null, info,
        null, -1);

    writer.writeLine(metadata, position, Collections.emptyList());
    assertTrue(sw.toString().contains("DB"));
    assertFalse(sw.toString().contains("DB="));
  }

  @Test
  public void testDefaultWriteCanonicalizesEmptyEntryInMultiValueInfoListInsteadOfWritingLiteralZeroLengthField()
      throws Exception {
    // addInfoOrDot()'s isEmptyValueList branch only recognizes the whole-list-is-one-empty-string shape; a
    // multi-value list with an empty entry at any position (e.g. a live getInfo() mutation producing
    // ["1", "", "2"]) must also be canonicalized to "." per EMPTY_FIELD_HANDLING.md's position-dependent-list
    // rule, on the default write path too, not just under validateBeforeWrite(). Asserts the warning itself
    // fires (not just the output shape), so this test would fail if the m_warnings.warn(...) call were deleted.
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .addInfo(new InfoMetadata(LENIENT_CONTEXT, "XY", "d", InfoType.Integer, "3", null, null))
        .build();
    writer.writeHeader(metadata);
    ListMultimap<String, String> info = ArrayListMultimap.create();
    info.put("XY", "1");
    info.put("XY", "");
    info.put("XY", "2");
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", null, VcfFloat.of(new BigDecimal("0")), null, info,
        null, -1);

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      writer.writeLine(metadata, position, Collections.emptyList());
      assertTrue(sw.toString().contains("XY=1,.,2"));
      assertFalse(sw.toString().contains("XY=1,,2"));
      assertTrue(appender.anyMessageContains("INFO XY contains an empty value"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testValidateBeforeWriteCanonicalizesEmptyEntryInMultiValueInfoListInsteadOfWritingLiteralZeroLengthField()
      throws Exception {
    // validateInfoValue() already warns "INFO {} contains an empty value" for this exact shape under
    // validateBeforeWrite(), but (before this fix) that warning had no effect on what addInfoOrDot() actually
    // wrote in the same writeLine() call -- confirms the canonicalization also applies in full-diagnostics mode.
    // Also confirms this doesn't double-warn: addInfoOrDot()'s own warning must be suppressed here (declared
    // key, so validateInfoValue() already ran and warned generically), and validateInfoValue()'s own per-element
    // type-conversion loop must skip the empty element rather than triggering InternalUtils.convertProperty's
    // own (context-free) empty-value warning a second time.
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .addInfo(new InfoMetadata(LENIENT_CONTEXT, "XY", "d", InfoType.Integer, "3", null, null))
        .build();
    writer.writeHeader(metadata);
    ListMultimap<String, String> info = ArrayListMultimap.create();
    info.put("XY", "1");
    info.put("XY", "");
    info.put("XY", "2");
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", null, VcfFloat.of(new BigDecimal("0")), null, info,
        null, -1);

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      writer.writeLine(metadata, position, Collections.emptyList());
      assertTrue(sw.toString().contains("XY=1,.,2"));
      assertFalse(sw.toString().contains("XY=1,,2"));
      assertEquals(1, appender.countMessagesContaining("INFO XY contains an empty value"));
      assertFalse(appender.anyMessageContains("is an empty entry"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testValidateBeforeWriteWarnsForEmptyEntryInMultiValueInfoListEvenWhenKeyIsUndeclared()
      throws Exception {
    // regression test: validateInfo() only calls validateInfoValue() for an undeclared key when it's also a
    // reserved property -- for an undeclared, non-reserved key, nothing warns generically. addInfoOrDot()'s own
    // warning must not be unconditionally suppressed just because m_validateBeforeWrite is true, or this exact
    // case would canonicalize with zero warnings, worse than the default write path for the same input.
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").build(); // XY not declared
    writer.writeHeader(metadata);
    ListMultimap<String, String> info = ArrayListMultimap.create();
    info.put("XY", "1");
    info.put("XY", "");
    info.put("XY", "2");
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", null, VcfFloat.of(new BigDecimal("0")), null, info,
        null, -1);

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      writer.writeLine(metadata, position, Collections.emptyList());
      assertTrue(sw.toString().contains("XY=1,.,2"));
      assertFalse(sw.toString().contains("XY=1,,2"));
      assertTrue(appender.anyMessageContains("INFO XY contains an empty value"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testDefaultWriteCanonicalizesEmptyEntryAtEveryPositionInMultiValueInfoList() throws Exception {
    // the old code special-cased values.get(0) outside the per-element loop, so an empty entry specifically at
    // position 0 is the one case most likely to be missed by an incomplete fix; also covers the last position
    // and multiple empty entries in the same list
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .addInfo(new InfoMetadata(LENIENT_CONTEXT, "XY", "d", InfoType.Integer, "2", null, null))
        .addInfo(new InfoMetadata(LENIENT_CONTEXT, "XZ", "d", InfoType.Integer, "3", null, null))
        .build();

    StringWriter leading = new StringWriter();
    VcfWriter leadingWriter = new VcfWriter.Builder().toWriter(new PrintWriter(leading)).build();
    leadingWriter.writeHeader(metadata);
    ListMultimap<String, String> leadingInfo = ArrayListMultimap.create();
    leadingInfo.put("XY", "");
    leadingInfo.put("XY", "2");
    VcfPosition leadingPos = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", null, VcfFloat.of(new BigDecimal("0")), null,
        leadingInfo, null, -1);
    leadingWriter.writeLine(metadata, leadingPos, Collections.emptyList());
    assertTrue(leading.toString().contains("XY=.,2"));

    StringWriter trailing = new StringWriter();
    VcfWriter trailingWriter = new VcfWriter.Builder().toWriter(new PrintWriter(trailing)).build();
    trailingWriter.writeHeader(metadata);
    ListMultimap<String, String> trailingInfo = ArrayListMultimap.create();
    trailingInfo.put("XY", "1");
    trailingInfo.put("XY", "");
    VcfPosition trailingPos = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", null, VcfFloat.of(new BigDecimal("0")), null,
        trailingInfo, null, -1);
    trailingWriter.writeLine(metadata, trailingPos, Collections.emptyList());
    assertTrue(trailing.toString().contains("XY=1,."));

    StringWriter allEmpty = new StringWriter();
    VcfWriter allEmptyWriter = new VcfWriter.Builder().toWriter(new PrintWriter(allEmpty)).build();
    allEmptyWriter.writeHeader(metadata);
    ListMultimap<String, String> allEmptyInfo = ArrayListMultimap.create();
    allEmptyInfo.put("XZ", "");
    allEmptyInfo.put("XZ", "");
    allEmptyInfo.put("XZ", "");
    VcfPosition allEmptyPos = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", null, VcfFloat.of(new BigDecimal("0")), null,
        allEmptyInfo, null, -1);
    allEmptyWriter.writeLine(metadata, allEmptyPos, Collections.emptyList());
    assertTrue(allEmpty.toString().contains("XZ=.,.,."));
  }

  @Test
  public void testWrittenMultiValueInfoWithCanonicalizedEmptyEntryReparsesToTheSameCanonicalValue()
      throws Exception {
    // round-trip check: the "." this fix writes for a live-mutated empty INFO entry must itself be valid,
    // reparseable VCF -- not just a string that happens to look right in isolation
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .addInfo(new InfoMetadata(LENIENT_CONTEXT, "XY", "d", InfoType.Integer, "3", null, null))
        .build();
    writer.writeHeader(metadata);
    ListMultimap<String, String> info = ArrayListMultimap.create();
    info.put("XY", "1");
    info.put("XY", "");
    info.put("XY", "2");
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", null, VcfFloat.of(new BigDecimal("0")), null, info,
        null, -1);
    writer.writeLine(metadata, position, Collections.emptyList());
    // asserted on the raw written text, not just the reparsed result: the parser's own INFO empty-value
    // handling would independently normalize a literal "XY=1,,2" to the same [1, ".", 2] on reparse, which
    // would let this test pass even if the writer itself never canonicalized anything -- this line is what
    // actually makes the test load-bearing for the writer fix, not just the parser's.
    assertTrue(sw.toString().contains("XY=1,.,2"));

    List<VcfPosition> reparsed = new ArrayList<>();
    try (VcfParser parser = new VcfParser.Builder()
        .fromReader(new BufferedReader(new StringReader(sw.toString())))
        .parseWith((md, record) -> reparsed.add(record.getPosition()))
        .build()) {
      parser.parse();
    }
    assertEquals(1, reparsed.size());
    assertEquals(Arrays.asList("1", ".", "2"), reparsed.get(0).getInfo("XY"));
  }

  @Test
  public void testWriteHeaderRejectsNullMetadataInsteadOfThrowingNpe() throws Exception {
    // writeHeader()/writeLine() are the library's two primary entry points, called by every consumer; metadata
    // is typed non-@Nullable, but nothing enforces that at compile time. Must reject a null with
    // VcfFormatException, not let it reach a raw NullPointerException at metadata.getFileFormat() in
    // buildHeaderLines()
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    assertThrows(VcfFormatException.class, () -> writer.writeHeader(null));
  }

  @Test
  public void testWriteLineRejectsNullPositionInsteadOfThrowingNpe() throws Exception {
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    assertThrows(VcfFormatException.class, () -> writer.writeLine(metadata, null, Collections.emptyList()));
  }

  @Test
  public void testWriteLineRejectsNullSamplesInsteadOfThrowingNpe() throws Exception {
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, "A", VcfFloat.of(new BigDecimal("0")), -1);
    assertThrows(VcfFormatException.class, () -> writer.writeLine(metadata, position, null));
  }

  @Test
  public void testToFileRejectsNullPathInsteadOfSilentlyAccepting() {
    // regression test: file is a caller-configuration object (like VcfParser.Builder's Path/Reader
    // arguments), never VCF content -- a raw NullPointerException is this codebase's established convention
    // for this category, not a fail-fast check further down at build() with a message that doesn't identify
    // which setter the caller actually called with null
    assertThrows(NullPointerException.class, () -> new VcfWriter.Builder().toFile(null));
  }

  @Test
  public void testToWriterRejectsNullWriterInsteadOfSilentlyAccepting() {
    assertThrows(NullPointerException.class, () -> new VcfWriter.Builder().toWriter(null));
  }

  @Test
  public void testWarningsToFileRejectsNullPathInsteadOfSilentlyAccepting() {
    // regression test: customFile is a caller-configuration object, matching the already-fixed sibling
    // VcfParser.Builder.warningsToFile(Path). Before this fix, a null customFile silently set the same state
    // as calling the zero-arg warningsToFile() (both set m_warningsFile = null), so build() would silently
    // fall back to the default derived filename instead of failing at the actual mistake.
    assertThrows(NullPointerException.class,
        () -> new VcfWriter.Builder().toWriter(new PrintWriter(new StringWriter())).warningsToFile(null));
  }

  @Test
  public void testWriteLineRejectsNullMetadataViaExistingIdentityCheck() throws Exception {
    // a null metadata is already rejected cleanly by the pre-existing metadata != m_headerMetadata identity
    // check, once a header has been written with non-null metadata (now guaranteed by writeHeader()'s own new
    // null guard) -- confirming this stays true rather than silently regressing
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, "A", VcfFloat.of(new BigDecimal("0")), -1);
    assertThrows(VcfFormatException.class, () -> writer.writeLine(null, position, Collections.emptyList()));
  }

  @Test
  public void testWriteLineDoesNotWarnAboutPassFilter() throws Exception {
    // PASS is VCF's reserved "all filters passed" keyword (VCFv4.2.tex line 185) and is never expected to have its
    // own ##FILTER=<ID=PASS,...> declaration. FILTER combining PASS with another code now warns instead of
    // throwing during parsing (see VcfPosition.checkFilters), so position.getFilters() can legitimately contain a
    // literal "PASS" entry -- this must not trigger the undeclared-FILTER warning below.
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .addFilter(new IdDescriptionMetadata(LENIENT_CONTEXT, "FILTER", "STD_FILTER", "Standard filter"))
        .build();
    writer.writeHeader(metadata);
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", null, VcfFloat.of(new BigDecimal("0")),
        List.of("STD_FILTER", "PASS"), null, null, -1);

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      writer.writeLine(metadata, position, Collections.emptyList());
      assertFalse(appender.anyMessageContains("has FILTER PASS"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testWriteLineDoesNotWarnAboutDotFilter() throws Exception {
    // "." (FILTER "not applied") is VCF's other reserved FILTER sentinel, also never expected to have its own
    // ##FILTER=<ID=.,...> declaration -- the undeclared-FILTER warning above must exclude it the same way it
    // excludes "PASS" (see testWriteLineDoesNotWarnAboutPassFilter). Not reachable via any public API
    // construction path any more: VcfPosition.checkFilters() unconditionally rejects "." combined with another
    // code, in both strict and lenient contexts. VcfContext.newPositionCopyingTrustedFields skips checkFilters
    // outright and is a real production fast path (VcfPosition.Builder.build()), not a test-only bypass -- see
    // testDefaultWriteDropsEmptyIdEntry for why this uses that factory rather than ordinary construction.
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .addFilter(new IdDescriptionMetadata(LENIENT_CONTEXT, "FILTER", "STD_FILTER", "Standard filter"))
        .build();
    writer.writeHeader(metadata);
    VcfPosition position = LENIENT_CONTEXT.newPositionCopyingTrustedFields("chr1", 1, null, "A", null,
        VcfFloat.of(new BigDecimal("0")), Arrays.asList("STD_FILTER", "."), null, null, -1, null, null);

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      writer.writeLine(metadata, position, Collections.emptyList());
      assertFalse(appender.anyMessageContains("has FILTER ."));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testValidateBeforeWriteWarnsWhenChromSampleMissingFromSampleMetadata() throws Exception {
    // regression test: writeHeader() previously never checked this at all, even though the same mismatch would
    // have been caught had the file been re-parsed from scratch (VcfParser has the identical check, with the
    // same warning text). Checked once here, at writeHeader() time, not on every writeLine() call: #CHROM's
    // sample columns can't change after construction (VcfMetadata.Builder.build() copies them into an immutable
    // list), and a post-writeHeader() mutation to ##SAMPLE itself is already rejected outright by writeLine()'s
    // own header-text-snapshot check, so there's nothing left for a per-writeLine() re-check to catch.
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .build();
    // no ##SAMPLE=<ID=S1,...> declaration added -- #CHROM declares 1 sample, metadata declares 0
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      writer.writeHeader(metadata);
      assertTrue(appender.anyMessageContains("There are 1 samples in the header but 0 in the metadata"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testValidateBeforeWriteRejectsStructurallyInvalidSampleValue() throws Exception {
    // restores the coverage of the former testValidateBeforeWriteRejectsMutatedSampleValue, which reached this
    // rejection by mutating an already-built sample -- a route Task 9 removed. The writer-side check it pins
    // (validateSamples -> VcfSample.validate() -> checkNoStructuralDelimiter) is still live. Task 11 (see
    // TODO.md's trusted-factory audit for the full history) has since run and settled this as a PERMANENT
    // design decision, not a pending one: validateBeforeWrite() is the full-output diagnostics mode and must
    // report what it can detect about the object it was handed rather than assume a construction-time
    // invariant, so this check was explicitly kept, not scheduled for removal. Deleting this test would still
    // remove real regression coverage for a check that is staying -- the same mistake Task 8 made and had to
    // reverse -- it just isn't gating a future task's correctness anymore.
    //
    // VcfSample.newDoublyUncheckedForTest() is the reconstruction route, not newTrustedSample(): the latter
    // now self-verifies its own premise via an assert whenever this project's test JVM runs with assertions
    // enabled (Gradle's default), so it can no longer be used to smuggle a genuinely structurally-invalid
    // value past construction -- see VcfSample's trusted-branch Javadoc. newDoublyUncheckedForTest() is the
    // deliberate, doubly-unchecked escape hatch that exists purely so this exact test can still build the
    // fixture VcfWriter's defense-in-depth is written against. Package-private, reachable here because this
    // test is in the same package -- mirroring how Task 8 restored the writer's empty-entry tests via
    // newPositionCopyingTrustedFields().
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .build();
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        null, List.of("DP"), -1);
    VcfSample sample = VcfSample.newDoublyUncheckedForTest(LENIENT_CONTEXT, List.of("DP"), List.of("1:2"));

    writer.writeHeader(metadata);
    assertThrows(VcfFormatException.class,
        () -> writer.writeLine(metadata, position, Collections.singletonList(sample)));
  }

  @Test
  public void testValidateBeforeWriteNormalizesEmptySampleValue() throws Exception {
    // an empty value is not a structural problem (it doesn't corrupt column/sub-field alignment), so it's warned
    // about and normalized to "." rather than rejected, consistent with validateInfoValue/validateFormatValue's
    // warn-only treatment of the same "empty value" case elsewhere in this writer
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .build();
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        null, List.of("DP"), -1);
    VcfSample sample = LENIENT_CONTEXT.newSample(new LinkedHashMap<>(Map.of("DP", "")));

    writer.writeHeader(metadata);
    writer.writeLine(metadata, position, Collections.singletonList(sample));
    assertTrue(sw.toString().contains("\tDP\t."));
  }

  @Test
  public void testValidateBeforeWriteRejectsSamplePropertyMissingFromFormat() throws Exception {
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .build();
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        null, List.of("GT"), -1);
    LinkedHashMap<String, String> sampleProps = new LinkedHashMap<>();
    sampleProps.put("GT", "0/1");
    sampleProps.put("DP", "10");
    VcfSample sample = LENIENT_CONTEXT.newSample(sampleProps);

    writer.writeHeader(metadata);
    assertThrows(VcfFormatException.class,
        () -> writer.writeLine(metadata, position, Collections.singletonList(sample)));
  }

  @Test
  public void testDefaultWriteDropsSamplePropertyNotDeclaredInFormatWithoutMisaligningColumns() throws Exception {
    // regression test: this coverage died with the removal of TransformingVcfLineParserTest (whose
    // testRemovingFormatKeyWithoutUpdatingSampleDropsItInWrittenOutput reached the identical defense by mutating
    // a live VcfPosition's FORMAT list via the now-removed mutate-in-place transformation pipeline). The
    // reconciliation-by-key-name behavior it pins (VcfWriter.java's addSampleConditionally()) is unchanged.
    // Contrast with testValidateBeforeWriteRejectsSamplePropertyMissingFromFormat immediately above: that mode
    // rejects the identical divergence with VcfFormatException, but the default path must instead warn and drop
    // the sample's now-undeclared property, keeping FORMAT and the sample column in sync rather than
    // misaligning every later value.
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .build();
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        null, List.of("GT"), -1);
    LinkedHashMap<String, String> sampleProps = new LinkedHashMap<>();
    sampleProps.put("GT", "0/1");
    sampleProps.put("DP", "5");
    VcfSample sample = LENIENT_CONTEXT.newSample(sampleProps);

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      writer.writeHeader(metadata);
      writer.writeLine(metadata, position, Collections.singletonList(sample));
      assertTrue(sw.toString().contains("\tGT\t0/1"));
      assertFalse(sw.toString().contains("DP"));
      assertTrue(appender.anyMessageContains("contains extra property DP"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testValidateBeforeWriteDoesNotWarnAboutGleCardinality() throws Exception {
    // GLE's declared Number=G describes its conventional expected-value shape for tooling, but GLE's own value is
    // not a comma-delimited list the way every other FORMAT key's Number=G value is -- its commas are part of its
    // internal genotype:likelihood encoding (see ReservedFormatProperty.GenotypeLikelihoodsOfHeterogenousPloidy).
    // validateFormatValue must not compare its un-split value count (always 1) against Number=G's combinatorial
    // expected count, which would always report a false "wrong cardinality" warning.
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .addFormat(new FormatMetadata(LENIENT_CONTEXT, "GT", "Genotype", "1", FormatType.String))
        .addFormat(new FormatMetadata(LENIENT_CONTEXT, "GLE", "Genotype likelihoods", "G", FormatType.String))
        .build();
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        null, List.of("GT", "GLE"), -1);
    LinkedHashMap<String, String> sampleProps = new LinkedHashMap<>();
    sampleProps.put("GT", "0/1");
    sampleProps.put("GLE", "0:-75.22,1:-223.42,0/0:-323.03,1/0:-99.29,1/1:-802.53");
    VcfSample sample = LENIENT_CONTEXT.newSample(sampleProps);

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      writer.writeHeader(metadata);
      writer.writeLine(metadata, position, Collections.singletonList(sample));
      assertFalse(appender.anyMessageContains("Number=G requires"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testValidateBeforeWriteGleCommaExemptionIsVersionGated() throws Exception {
    // behaviors 1 and 2 on the write path. Under <=4.2 GLE's commas are internal to its own encoding, so
    // splitFormatValue keeps the value whole (one element, no empty-element warning) and the Number=G
    // cardinality check is skipped entirely. VCFv4.3 removed the key (VCFv4.3.tex line 2168), so a 4.3 key named
    // GLE splits on commas like any other and is checked against its own declared Number: a 3-element value
    // declared Number=2 must now warn, and its empty middle element must be reported.
    VcfContext lenient43 = VcfContext.lenient("VCFv4.3", VcfWarnings.DEFAULT);

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      writeOneGleRecord(LENIENT_CONTEXT, "VCFv4.2", "a,,b");
      assertFalse(appender.anyMessageContains("contains an empty value"));
      assertFalse(appender.anyMessageContains("Number=2 requires"));

      writeOneGleRecord(lenient43, "VCFv4.3", "a,,b");
      assertTrue(appender.anyMessageContains("contains an empty value"));
      assertTrue(appender.anyMessageContains("but Number=2 requires 2"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  /** Writes one record with FORMAT {@code GLE} declared {@code Number=2,Type=String}, in diagnostics mode. */
  private static void writeOneGleRecord(VcfContext context, String fileFormat, String gleValue) throws Exception {
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    VcfMetadata metadata = new VcfMetadata.Builder(context).setFileFormat(fileFormat)
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .addFormat(new FormatMetadata(context, "GLE", "custom", "2", FormatType.String))
        .build();
    VcfPosition position = context.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        null, List.of("GLE"), -1);
    LinkedHashMap<String, String> sampleProps = new LinkedHashMap<>();
    sampleProps.put("GLE", gleValue);
    VcfSample sample = context.newSample(sampleProps);
    writer.writeHeader(metadata);
    writer.writeLine(metadata, position, Collections.singletonList(sample));
  }

  @Test
  public void testValidateBeforeWriteDoesNotWarnAboutCnpCardinality() throws Exception {
    // CNP's declared Number=G is genuinely a comma-delimited list (unlike GLE), but the list is indexed by
    // "potential copy number," not by the ploidy-derived genotype ordering this codebase's Number=G formula
    // computes -- 5 possible copy-number states is a legitimate CNP value shape for a diploid, biallelic
    // sample even though the genotype-ordering formula would expect 3 (see
    // ReservedFormatProperty.CopyNumberPosteriorProbabilities' own comment)
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .addFormat(new FormatMetadata(LENIENT_CONTEXT, "GT", "Genotype", "1", FormatType.String))
        .addFormat(new FormatMetadata(LENIENT_CONTEXT, "CNP", "Copy number posterior probabilities", "G", FormatType.Float))
        .build();
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        null, List.of("GT", "CNP"), -1);
    LinkedHashMap<String, String> sampleProps = new LinkedHashMap<>();
    sampleProps.put("GT", "0/1");
    sampleProps.put("CNP", "0.1,0.2,0.3,0.2,0.2");
    VcfSample sample = LENIENT_CONTEXT.newSample(sampleProps);

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      writer.writeHeader(metadata);
      writer.writeLine(metadata, position, Collections.singletonList(sample));
      assertFalse(appender.anyMessageContains("Number=G requires"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testValidateBeforeWriteDoesNotWarnAboutCnlCardinalityAtV43() throws Exception {
    // same shape as testValidateBeforeWriteDoesNotWarnAboutCnpCardinality above, for CNL: its declared
    // Number=G is only correct from VCFv4.3 on (VCFv4.2.tex:308 declares it Number=.; VCFv4.3.tex:678
    // redeclares it Number=G -- see ReservedFormatProperty.CopyNumberLikelihood's own comment), and its
    // "potential copy number" G is exempt from the ploidy-derived Number=G cardinality formula the same way
    // CNP's is -- but only from VCFv4.3 on, the one range where CNL's Number is actually G (see
    // InternalUtils.isExemptFromGenotypeOrderingCardinalityCheck)
    VcfContext context43 = VcfContext.strict("VCFv4.3");
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    VcfMetadata metadata = new VcfMetadata.Builder(context43).setFileFormat("VCFv4.3")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .addFormat(new FormatMetadata(context43, "GT", "Genotype", "1", FormatType.String))
        .addFormat(new FormatMetadata(context43, "CNL", "Copy number genotype likelihood", "G", FormatType.Float))
        .build();
    VcfPosition position = context43.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        null, List.of("GT", "CNL"), -1);
    LinkedHashMap<String, String> sampleProps = new LinkedHashMap<>();
    sampleProps.put("GT", "0/1");
    sampleProps.put("CNL", "-0.1,-0.2,-0.3,-0.2,-0.2");
    VcfSample sample = context43.newSample(sampleProps);

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      writer.writeHeader(metadata);
      writer.writeLine(metadata, position, Collections.singletonList(sample));
      assertFalse(appender.anyMessageContains("Number=G requires"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testValidateBeforeWriteWarnsAboutCnlCardinalityWhenDeclarationOverridesNumber() throws Exception {
    // regression test (see InternalUtils.isExemptFromGenotypeOrderingCardinalityCheck's own comment): CNL's
    // genotype-ordering-cardinality exemption applies only while the Number actually in force genuinely is "G".
    // A ##FORMAT=<ID=CNL,Number=4,...> declaration states a real, checkable cardinality instead, so a five-value
    // CNL sample must still be reported as violating its own declared Number=4 -- the exemption must not
    // suppress the ordinary check just because the key is CNL.
    // Lenient, not strict: a declaration overriding a reserved key's canonical Number is exactly the
    // "warn and keep" case, and a strict context throws at FormatMetadata construction ("FORMAT CNL has
    // Number '4' but the reserved definition expects 'G'") before the writer is ever reached. The appender
    // is attached only after the metadata is built, so that construction-time warning is not captured here.
    VcfContext context43 = VcfContext.lenient("VCFv4.3", VcfWarnings.DEFAULT);
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    VcfMetadata metadata = new VcfMetadata.Builder(context43).setFileFormat("VCFv4.3")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .addFormat(new FormatMetadata(context43, "GT", "Genotype", "1", FormatType.String))
        .addFormat(new FormatMetadata(context43, "CNL", "Copy number genotype likelihood", "4", FormatType.Float))
        .build();
    VcfPosition position = context43.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        null, List.of("GT", "CNL"), -1);
    LinkedHashMap<String, String> sampleProps = new LinkedHashMap<>();
    sampleProps.put("GT", "0/1");
    sampleProps.put("CNL", "-0.1,-0.2,-0.3,-0.2,-0.2");
    VcfSample sample = context43.newSample(sampleProps);

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      writer.writeHeader(metadata);
      writer.writeLine(metadata, position, Collections.singletonList(sample));
      assertTrue(appender.anyMessageContains("Number=4 requires"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testValidateBeforeWriteDoesNotDoubleWarnAboutEmptyFormatValue() throws Exception {
    // regression test: validateFormatValue()'s own per-element type-conversion loop previously called
    // InternalUtils.convertProperty() for every element including empty ones, which (since convertProperty now
    // itself warns on an empty scalar value) fired a second, context-free "is an empty entry" warning for the
    // same defect the loop's own generic "FORMAT {} for sample #{} contains an empty value" warning, above it in
    // the same method, already reported -- the INFO-side sibling of this exact bug (validateInfoValue).
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .addFormat(new FormatMetadata(LENIENT_CONTEXT, "GT", "Genotype", "1", FormatType.String))
        .addFormat(new FormatMetadata(LENIENT_CONTEXT, "XY", "d", "3", FormatType.Integer))
        .build();
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        null, List.of("GT", "XY"), -1);
    LinkedHashMap<String, String> sampleProps = new LinkedHashMap<>();
    sampleProps.put("GT", "0/1");
    sampleProps.put("XY", "1,,2");
    VcfSample sample = LENIENT_CONTEXT.newSample(sampleProps);

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      writer.writeHeader(metadata);
      writer.writeLine(metadata, position, Collections.singletonList(sample));
      assertEquals(1, appender.countMessagesContaining("FORMAT XY for sample #0 contains an empty value"));
      assertFalse(appender.anyMessageContains("is an empty entry"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testValidateBeforeWriteChecksUndeclaredReservedInfoKeyCardinality() throws Exception {
    // AC is reserved (Number=A) but has no ##INFO=<ID=AC,...> declaration at all here -- validateInfo must still
    // fall back to AC's own canonical Number/Type, matching getInfo(ReservedInfoProperty)'s identical read-path
    // fallback, rather than silently skipping every check beyond the generic "no INFO metadata" warning
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO"))
        .build();
    ListMultimap<String, String> info = ArrayListMultimap.create();
    info.put("AC", "1"); // 2 ALT alleles but only 1 value -- a real Number=A violation
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T", "C"), VcfFloat.of(new BigDecimal("0")),
        null, info, null, -1);

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      writer.writeHeader(metadata);
      writer.writeLine(metadata, position, Collections.emptyList());
      assertTrue(appender.anyMessageContains("no INFO metadata with that name"));
      assertTrue(appender.anyMessageContains("INFO AC has 1 value(s), but Number=A requires 2"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testValidateBeforeWriteChecksUndeclaredReservedFormatKeyCardinality() throws Exception {
    // AD is reserved (Number=R) but has no ##FORMAT=<ID=AD,...> declaration at all here -- validateSamples must
    // still fall back to AD's own canonical Number/Type, matching getProperty(VcfPosition, VcfMetadata,
    // String)'s identical read-path fallback
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .addFormat(new FormatMetadata(LENIENT_CONTEXT, "GT", "Genotype", "1", FormatType.String))
        .build();
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        null, List.of("GT", "AD"), -1);
    LinkedHashMap<String, String> sampleProps = new LinkedHashMap<>();
    sampleProps.put("GT", "0/1");
    sampleProps.put("AD", "10"); // Number=R with 1 ALT allele expects 2 values, only 1 given
    VcfSample sample = LENIENT_CONTEXT.newSample(sampleProps);

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      writer.writeHeader(metadata);
      writer.writeLine(metadata, position, Collections.singletonList(sample));
      assertTrue(appender.anyMessageContains("no FORMAT metadata with that name"));
      assertTrue(appender.anyMessageContains("FORMAT AD for sample #0 has 1 value(s), but Number=R requires 2"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testValidateBeforeWriteAssumesDiploidyForPlWhenGtAbsent() throws Exception {
    // PL's own spec definition states "without GT field, diploidy is assumed" (see
    // InternalUtils.resolvePloidyForCardinalityCheck's own comment for the full citation) -- regression test
    // for a prior bug where this default was never applied on the write path either, silently skipping the
    // whole cardinality check whenever GT was absent for GL/PL/GP/PP, same as any other Number=G field
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .addFormat(new FormatMetadata(LENIENT_CONTEXT, "PL", "Phred-scaled genotype likelihoods", "G", FormatType.Integer))
        .build();
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        null, List.of("PL"), -1);
    // assumed diploidy + 1 ALT allele expects 3 values, only 2 given
    VcfSample sample = LENIENT_CONTEXT.newSample(new LinkedHashMap<>(Map.of("PL", "1,2")));

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      writer.writeHeader(metadata);
      writer.writeLine(metadata, position, Collections.singletonList(sample));
      assertTrue(appender.anyMessageContains("FORMAT PL for sample #0 has 2 value(s), but Number=G requires 3"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testValidateBeforeWriteWarnsUndeclaredFormatKeyEvenWhenValueIsMissing() throws Exception {
    // Regression test for a prior bug: validateSamples()'s value==null/format==null branches were mutually
    // exclusive (if/else-if), so an undeclared FORMAT key whose value is also missing (VCF's spec-permitted
    // trailing-field omission) warned only "is missing property", silently dropping the "no FORMAT metadata
    // with that name" warning -- even though the default (non-validating) write path always emits that warning
    // for an undeclared key, regardless of whether the value is present. XY is listed in FORMAT but never
    // declared via ##FORMAT=<ID=XY,...>, and the sample supplies only GT, so sample.getProperty("XY") is null.
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .addFormat(new FormatMetadata(LENIENT_CONTEXT, "GT", "Genotype", "1", FormatType.String))
        .build();
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        null, List.of("GT", "XY"), -1);
    VcfSample sample = LENIENT_CONTEXT.newSample(new LinkedHashMap<>(Map.of("GT", "0/1")));

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      writer.writeHeader(metadata);
      writer.writeLine(metadata, position, Collections.singletonList(sample));
      assertTrue(appender.anyMessageContains("no FORMAT metadata with that name"));
      assertTrue(appender.anyMessageContains("Sample #0 is missing property XY"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testValidateBeforeWriteRejectsMetadataDelimiterInjectedThroughRawView() throws Exception {
    InfoMetadata info = new InfoMetadata(LENIENT_CONTEXT, "NS", "d", InfoType.Integer, "1", null, null);
    info.trustedMutableProperties().put("Description", "unquoted,comma");
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").addInfo(info).build();
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();

    assertThrows(VcfFormatException.class, () -> writer.writeHeader(metadata));
  }

  @Test
  public void testValidateBeforeWriteRejectsEqualsSignInRawPropertyKey() throws Exception {
    // a raw property ("##key=value") is reread by VcfParser splitting on the FIRST "=" only; a key containing "="
    // shifts that split point. Verified live: writing key "foo=bar" reparses as key "foo", value "bar=value"
    // instead of the original key "foo=bar", value "value" -- genuine data corruption, not just unusual content.
    VcfMetadata metadata = trustedMetadataWithRawProperty("foo=bar", "value");
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();

    assertThrows(VcfFormatException.class, () -> writer.writeHeader(metadata));
  }

  @Test
  public void testValidateBeforeWriteRejectsNullRawPropertyKey() throws Exception {
    // the backing LinkedListMultimap permits null keys/values, so a null key must get VcfFormatException from
    // validateMetadata(), not a raw NullPointerException from key.contains("="). getRawProperties() is an
    // unmodifiable view since the metadata layer was frozen, so trustedMetadata() is what reaches this state
    // now -- see its Javadoc.
    VcfMetadata metadata = trustedMetadataWithRawProperty(null, "value");
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();

    assertThrows(VcfFormatException.class, () -> writer.writeHeader(metadata));
  }

  @Test
  public void testValidateBeforeWriteRejectsNullRawPropertyValue() throws Exception {
    VcfMetadata metadata = trustedMetadataWithRawProperty("foo", null);
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();

    assertThrows(VcfFormatException.class, () -> writer.writeHeader(metadata));
  }

  // The default-write counterparts of the two tests above (testDefaultWriteRejectsNullRawPropertyKey/
  // ValueInsteadOfWritingLiteralNullText) were removed along with addPropertyLines()'s own null guards: the
  // header-building path no longer re-checks metadata for nulls, since VcfMetadata is immutable and
  // Builder.addRawProperty() rejects a null name/value at insertion. The two validateBeforeWrite() tests above
  // keep validateMetadata()'s equivalent checks covered.

  @Test
  public void testValidateBeforeWriteRejectsNullInfoPropertyValueViaLiveView() throws Exception {
    // getPropertiesRaw() is a live view, so a caller can insert a null value after construction, bypassing the
    // constructor's own validateProperties() check at the time it originally ran. validateMetadata() calls
    // entry.validate() on every keyed entry (BaseMetadata.validate() -> validateProperties(), the same method,
    // re-run), which now rejects a null value there too -- this locks in that re-validation, not a dead branch
    // in validateMetadataProperties() (which used to duplicate this check but is now unreachable for it).
    InfoMetadata info = new InfoMetadata(LENIENT_CONTEXT, "NS", "d", InfoType.Integer, "1", null, null);
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").addInfo(info).build();
    metadata.getInfo().get("NS").trustedMutableProperties().put("Description", null);
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();

    assertThrows(VcfFormatException.class, () -> writer.writeHeader(metadata));
  }

  // The default-write counterparts of the test above (testDefaultWriteRejectsNullInfoPropertyKey/
  // ValueInsteadOfWritingLiteralNullText) were removed along with getAllProperties()'s own null guards, for the
  // same reason as the raw-property pair further up: a declaration's properties map is unmodifiable and every
  // construction path rejects a null key/value, so the header-building path no longer re-checks.

  @Test
  public void testValidateBeforeWriteWarnsAboutUnescapedQuoteInMetadataValue() throws Exception {
    // a value that isn't validly quoted (isQuoted() is false) but also contains no ',' or '=' previously slipped
    // through unrejected, then (until InternalUtils.findEncodingProblems' construction/write-mode split) threw
    // unconditionally in validateBeforeWrite() too. Verified live: a value of "abc"def" (embedded unescaped '"',
    // no comma/equals) selects findEncodingProblems' quoted branch on its leading '"' and fails isQuoted ("has a
    // malformed quoted value"); if written and then re-read, it truncates at the first unescaped '"' it contains
    // (the attribute-list tokenizer finds a quoted value's closing delimiter by position, not by unbalanced-quote
    // parity) -- silently losing the "def\"" tail the caller supplied, even though the following BBBB property
    // below is unaffected. That loss is real but leaves the file readable (unlike an unquoted comma or a
    // malformed bracket value, which findEncodingProblems marks unreadableOutput), so this is now the
    // warn-not-throw branch: this construction path (a direct InfoMetadata(Map) constructor, then the
    // object-taking addInfo route) is exempt from the construction-time throw the same way parser-recovered
    // content is -- see STRUCTURAL_DELIMITER_HANDLING.md's "Why the parse path is exempt" -- so
    // validateBeforeWrite() is the only place left to detect this at all.
    Map<String, String> props = new LinkedHashMap<>();
    props.put("ID", "X");
    props.put("Description", "\"d\"");
    props.put("AAAA", "\"abc\"def\"");
    props.put("BBBB", "\"has,a,comma\"");
    InfoMetadata info = new InfoMetadata(LENIENT_CONTEXT, props);
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").addInfo(info).build();
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      writer.writeHeader(metadata);
      assertTrue(appender.anyMessageContains("malformed quoted value"));
      assertThat(sw.toString(), containsString("AAAA=\"abc\"def\""));
      assertThat(sw.toString(), containsString("BBBB=\"has,a,comma\""));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testValidateBeforeWriteRejectsMetadataIdDivergedFromMapKey() throws Exception {
    // getInfo() (and its FORMAT/FILTER/ALT/contig/SAMPLE siblings) return the backing map directly; mutating an
    // entry's own raw ID property, while it stays stored under its original map key, would otherwise make the
    // emitted header declare a different ID than what data lines reference by that map key
    InfoMetadata info = new InfoMetadata(LENIENT_CONTEXT, "NS", "d", InfoType.Integer, "1", null, null);
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").addInfo(info).build();
    metadata.getInfo().get("NS").trustedMutableProperties().put("ID", "DIFFERENT");
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();

    assertThrows(VcfFormatException.class, () -> writer.writeHeader(metadata));
  }

  @Test
  public void testValidateBeforeWriteDoesNotFalselyReportMismatchForUnmutatedQuotedId() throws Exception {
    // regression test for validateKeyedMetadataEntries()'s own fix: the map's storage key is now each entry's
    // unquoted ID (see VcfMetadata.Builder.addFilter's comment), so the mismatch check below must compare
    // against the entry's unquoted ID too. Before this fix it compared the map key against the still-quoted
    // raw ID property, so an entry whose sole ID was quoted from construction (ID="q10", never mutated) would
    // falsely report as diverged from its own map key even though nothing had drifted.
    IdDescriptionMetadata filter = new IdDescriptionMetadata(LENIENT_CONTEXT, "FILTER",
        InternalUtils.extractProperties(new String[]{"ID=\"q10\"", "Description=\"d\""}, VcfWarnings.DEFAULT));
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .addFilter(filter).build();
    assertEquals("q10", metadata.getFilters().keySet().iterator().next(), "the map key itself must be unquoted");
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();

    writer.writeHeader(metadata);
    // the thing most likely to break from keying on the unquoted ID: the writer must still emit the
    // declaration's own raw (quoted) ID property verbatim, not the unquoted map key -- getPropertiesRaw() is
    // untouched by this fix, only the map's storage key changed
    assertTrue(sw.toString().contains("##FILTER=<ID=\"q10\",Description=\"d\">"),
        "expected the raw, still-quoted ID property verbatim in the written header: " + sw);
  }

  @Test
  public void testWriteThenReparseRoundTripsQuotedFilterId() throws Exception {
    // full parse -> write -> parse round trip for a quoted keyed-type ID (AGENTS.md's Tests section asks for
    // this on a parser-to-writer change): the quoting must survive verbatim through a write, and re-parsing
    // that output must reach the identical state -- same raw (quoted) ID via getPropertyRaw("ID"), same
    // unquoted ID via getId(), same unquoted map key.
    String vcf = "##fileformat=VCFv4.2\n##FILTER=<ID=\"q10\",Description=\"d\">\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "chr1\t1\t.\tA\tT\t.\t.\t.\n";
    VcfMetadata parsed;
    try (BufferedReader reader = new BufferedReader(new StringReader(vcf));
         VcfParser parser = new VcfParser.Builder().fromReader(reader).parseWith((md, record) -> { }).build()) {
      parser.parse();
      parsed = parser.getMetadata();
    }
    assertEquals("q10", parsed.getFilters().keySet().iterator().next());
    assertEquals("\"q10\"", parsed.getFilters().get("q10").getPropertyRaw("ID"));
    assertEquals("q10", parsed.getFilters().get("q10").getId());

    StringWriter sw = new StringWriter();
    try (VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build()) {
      writer.writeHeader(parsed);
    }
    assertTrue(sw.toString().contains("##FILTER=<ID=\"q10\",Description=\"d\">"),
        "expected the quoted ID to survive the write verbatim: " + sw);

    String rewritten = sw.toString() + "chr1\t1\t.\tA\tT\t.\t.\t.\n";
    VcfMetadata reparsed;
    try (BufferedReader reader = new BufferedReader(new StringReader(rewritten));
         VcfParser parser = new VcfParser.Builder().fromReader(reader).parseWith((md, record) -> { }).build()) {
      parser.parse();
      reparsed = parser.getMetadata();
    }
    assertEquals("q10", reparsed.getFilters().keySet().iterator().next());
    assertEquals("\"q10\"", reparsed.getFilters().get("q10").getPropertyRaw("ID"));
    assertEquals("q10", reparsed.getFilters().get("q10").getId());
  }

  @Test
  void testQuotedValuesRoundTripByteIdenticallyForEveryChangedGetter() throws Exception {
    // Declarations are ordered to match buildHeaderLines exactly (INFO, FILTER, FORMAT, contig).
    // The assembly value carries escape sequences deliberately, and is the only field here that can:
    // assembly has no content validation in ContigMetadata.init(), so an escaped value neither warns nor
    // throws. That makes this the one place decode-on-read and preserve-on-write are pinned to the SAME value.
    String header = "##fileformat=VCFv4.2\n" +
        "##INFO=<ID=XX,Number=\"1\",Type=Integer,Description=\"d\">\n" +
        "##FILTER=<ID=\"q10\",Description=\"d\">\n" +
        "##FORMAT=<ID=YY,Number=\"1\",Type=Integer,Description=\"d\">\n" +
        "##contig=<ID=1,length=\"123\",assembly=\"a \\\"b\\\\c\",md5=\"f126cdf8a6e0c7f379d618ff66beb2da\"," +
        "taxonomy=\"x\",URL=\"ftp://example.com/a.fa\">\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n";
    String vcf = header + "chr1\t1\t.\tA\tT\t.\t.\t.\n";

    VcfMetadata parsed;
    try (BufferedReader reader = new BufferedReader(new StringReader(vcf));
         VcfParser parser = new VcfParser.Builder().fromReader(reader).parseWith((md, record) -> { }).build()) {
      parser.parse();
      parsed = parser.getMetadata();
    }
    assertQuotedGettersAndRawForms(parsed);

    // default write path -- validateBeforeWrite() off
    StringWriter sw = new StringWriter();
    try (VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build()) {
      writer.writeHeader(parsed);
    }
    assertEquals(header, sw.toString(), "the header must round-trip byte for byte, including the newline");

    // re-parse the output: proves the preserved encoding survives a second parse, not just a string compare
    VcfMetadata reparsed;
    try (BufferedReader reader = new BufferedReader(new StringReader(sw + "chr1\t1\t.\tA\tT\t.\t.\t.\n"));
         VcfParser parser = new VcfParser.Builder().fromReader(reader).parseWith((md, record) -> { }).build()) {
      parser.parse();
      reparsed = parser.getMetadata();
    }
    assertQuotedGettersAndRawForms(reparsed);
  }

  /**
   * Asserts the value form from each of the seven changed getters AND the encoding from getPropertyRaw.
   * <p>
   * The getPropertyRaw half is the anti-regression requirement: without it, the migration could be made to
   * pass by unquoting at STORAGE instead of at the getter, which would turn every test green while silently
   * destroying round-tripping.
   */
  private static void assertQuotedGettersAndRawForms(VcfMetadata md) {
    InfoMetadata info = md.getInfo().get("XX");
    assertEquals("1", info.getNumber());
    assertEquals("\"1\"", info.getPropertyRaw(InfoMetadata.NUMBER));

    IdDescriptionMetadata filter = md.getFilters().get("q10");
    assertEquals("q10", filter.getId());
    assertEquals("\"q10\"", filter.getPropertyRaw(IdMetadata.ID));

    FormatMetadata format = md.getFormats().get("YY");
    assertEquals("1", format.getNumber());
    assertEquals("\"1\"", format.getPropertyRaw(FormatMetadata.NUMBER));

    ContigMetadata contig = md.getContigs().get("1");
    assertEquals("a \"b\\c", contig.getAssembly());
    assertEquals("\"a \\\"b\\\\c\"", contig.getPropertyRaw(ContigMetadata.ASSEMBLY));
    assertEquals("x", contig.getTaxonomy());
    assertEquals("\"x\"", contig.getPropertyRaw(ContigMetadata.TAXONOMY));
    assertEquals("f126cdf8a6e0c7f379d618ff66beb2da", contig.getMd5());
    assertEquals("\"f126cdf8a6e0c7f379d618ff66beb2da\"", contig.getPropertyRaw(ContigMetadata.MD5));
    assertEquals("ftp://example.com/a.fa", contig.getUrl());
    assertEquals("\"ftp://example.com/a.fa\"", contig.getPropertyRaw(ContigMetadata.URL));
    assertEquals(123L, contig.getLength()); // unchanged getter, rides along free
  }

  @Test
  public void testValidateBeforeWriteWarnsOnAltMutatedToInvalidExtraNumber() throws Exception {
    // getAlts() returns the backing map directly; mutating an already-valid entry's raw Number property after
    // construction bypasses addAlt()'s own validation (which only runs once, at add time), so
    // validateBeforeWrite() must re-check it at write time
    IdDescriptionMetadata alt = new IdDescriptionMetadata(LENIENT_CONTEXT, "ALT", "DEL", "Deletion");
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").addAlt(alt).build();
    metadata.getAlts().get("DEL").trustedMutableProperties().put("Number", "B");
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      writer.writeHeader(metadata);
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testValidateBeforeWriteWarnsOnEmptyGenericPropertyValue() throws Exception {
    // an empty generic property value bypasses addRawProperty()'s own check entirely (that check only runs at
    // insertion time, and drops rather than keeps), so validateBeforeWrite() must re-check it at write time.
    // Unlike the parse-time path, this only warns -- it does not remove the entry from the caller's own
    // metadata object. Reached via trustedMetadata() (see its Javadoc) now that the metadata layer is frozen.
    VcfMetadata metadata = trustedMetadataWithRawProperty("reference", "");
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      writer.writeHeader(metadata);
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    // warn, not drop: the entry is still present in the caller's own metadata object after the write
    assertEquals(Collections.singletonList(""), metadata.getRawValuesOfProperty("reference"));
  }

  @Test
  public void testValidateBeforeWriteRejectsBracketedGenericPropertyValue() throws Exception {
    // getRawProperties() returns the backing multimap directly; mutating an existing entry's value to a
    // bracket-wrapped one bypasses addRawProperty()/addAssembly()/addPedigreeDatabase()'s own insertion-time
    // rejection entirely, so validateBeforeWrite() must re-check it at write time. Unlike the empty-value case
    // above, this throws rather than warns: bcftools 1.23.1 hard-errors ("Could not parse the header line")
    // trying to read a generic property value shaped like "<...>" back, so writing it out would produce output
    // a reference tool cannot parse at all -- exactly the structurally-unparseable-output case
    // validateBeforeWrite() must reject rather than merely warn about.
    VcfMetadata metadata = trustedMetadataWithRawProperty("reference", "<http://foo.com>");
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();

    assertThrows(VcfFormatException.class, () -> writer.writeHeader(metadata));
  }

  @Test
  public void testValidateBeforeWriteRejectsReservedRawPropertyKey() throws Exception {
    // getRawProperties() returns the backing multimap directly; a caller can insert a raw property whose key
    // collides with a structured declaration keyword (here "INFO") after the builder-side check in
    // addRawProperty() has already run once, at insertion time -- validateBeforeWrite() is the only remaining
    // check that can catch this before the writer emits an "##INFO=bad" line VcfParser cannot read back
    VcfMetadata metadata = trustedMetadataWithRawProperty("INFO", "bad");
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    assertThrows(VcfFormatException.class, () -> writer.writeHeader(metadata));
  }

  @Test
  public void testValidateBeforeWriteWarnsOnDuplicatePedigreeIdIntroducedAfterConstruction() throws Exception {
    // Builder.addPedigree() warns and drops a duplicate ID at construction time (see
    // VcfMetadataTest.testAddPedigreeWarnsAndKeepsFirstOnDuplicateId), so a duplicate PEDIGREE ID can only
    // reach this write-time check by bypassing Builder entirely. That used to be getPedigrees()'s mutable
    // List; since the metadata layer was frozen that list is unmodifiable, so trustedMetadata() is the route
    // now -- see its Javadoc. The writer-side check itself is unchanged and still live.
    Map<String, String> childProps = new LinkedHashMap<>();
    childProps.put("ID", "Child");
    childProps.put("Father", "Dad");
    Map<String, String> duplicateProps = new LinkedHashMap<>();
    duplicateProps.put("ID", "Child");
    duplicateProps.put("Mother", "Mom");
    VcfMetadata metadata = trustedMetadata("VCFv4.2", null,
        List.of(new PedigreeMetadata(LENIENT_CONTEXT, childProps),
            new PedigreeMetadata(LENIENT_CONTEXT, duplicateProps)),
        null);
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      writer.writeHeader(metadata);
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    // warn, not drop: both entries are still written -- the writer must not silently remove content the caller
    // already holds a reference to
    String written = sw.toString();
    assertTrue(written.contains("Father=Dad"));
    assertTrue(written.contains("Mother=Mom"));
  }

  @Test
  public void testValidateBeforeWriteWarnsOnBlankPedigreeIdUnder43ButDefaultPathIsSilent() throws Exception {
    // Same rule VcfMetadata.Builder.addPedigree enforces at construction (see
    // VcfMetadataTest.testAddPedigreeWarnsAndKeepsAbsentIdUnder43), re-checked here because validateBeforeWrite()
    // is the full-output diagnostics pass and must report what it can detect about the object it was handed.
    //
    // Built with NO appender attached: under a lenient 4.3 context, VcfMetadata.Builder.addPedigree() itself
    // already warns "has no ID" at construction time. Capturing that construction warning here would satisfy
    // the diagnostics-mode assertion below even if writeHeader()/validateMetadata() emitted nothing, and would
    // simultaneously break the default-path "silent" assertion for the wrong reason -- so each write mode gets
    // its own appender window, attached only around the writeHeader() call under test, never around construction.
    VcfContext lenient43 = VcfContext.lenient("VCFv4.3", VcfWarnings.DEFAULT);
    Map<String, String> props = new LinkedHashMap<>();
    props.put("Original", "G0-ID");
    VcfMetadata metadata = new VcfMetadata.Builder(lenient43).setFileFormat("VCFv4.3")
        .addPedigree(new PedigreeMetadata(lenient43, props))
        .build();

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    StringWriter diagnosticsSw = new StringWriter();
    VcfWriter diagnosticsWriter = new VcfWriter.Builder().toWriter(new PrintWriter(diagnosticsSw))
        .validateBeforeWrite().build();
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender diagnosticsAppender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    diagnosticsAppender.start();
    root.addAppender(diagnosticsAppender);
    try {
      diagnosticsWriter.writeHeader(metadata);
      assertTrue(diagnosticsAppender.anyMessageContains("has no ID"));
    } finally {
      root.detachAppender(diagnosticsAppender);
      diagnosticsAppender.stop();
    }

    StringWriter defaultSw = new StringWriter();
    VcfWriter defaultWriter = new VcfWriter.Builder().toWriter(new PrintWriter(defaultSw)).build();
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender defaultAppender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    defaultAppender.start();
    root.addAppender(defaultAppender);
    try {
      defaultWriter.writeHeader(metadata);
      assertFalse(defaultAppender.anyMessageContains("has no ID"));
    } finally {
      root.detachAppender(defaultAppender);
      defaultAppender.stop();
    }

    assertTrue(diagnosticsSw.toString().contains("##PEDIGREE"));
    assertTrue(defaultSw.toString().contains("##PEDIGREE"));
  }

  @Test
  public void testValidateBeforeWriteWarnsOnPedigreeIdContainingWhitespace() throws Exception {
    // regression test found by the Phase 2 review's re-verification round: unlike the sibling missing/blank-ID
    // check just above (which IS mirrored at write time), validateMetadata()'s PEDIGREE loop had no
    // whitespace-in-ID check at all -- a present, non-blank whitespace-containing ID (e.g. "A B") warns at
    // construction (VcfMetadataTest.testAddPedigreeWarnsButKeepsPresentIdContainingWhitespace) but produced no
    // warning here, unlike every other structured metadata ID type's identical rule. Same "no appender around
    // construction" structure as the blank-ID test above, since addPedigree() itself already warns for this ID
    // at construction time.
    VcfContext lenient43 = VcfContext.lenient("VCFv4.3", VcfWarnings.DEFAULT);
    Map<String, String> props = new LinkedHashMap<>();
    props.put("ID", "A B");
    VcfMetadata metadata = new VcfMetadata.Builder(lenient43).setFileFormat("VCFv4.3")
        .addPedigree(new PedigreeMetadata(lenient43, props))
        .build();

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    StringWriter diagnosticsSw = new StringWriter();
    VcfWriter diagnosticsWriter = new VcfWriter.Builder().toWriter(new PrintWriter(diagnosticsSw))
        .validateBeforeWrite().build();
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender diagnosticsAppender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    diagnosticsAppender.start();
    root.addAppender(diagnosticsAppender);
    try {
      diagnosticsWriter.writeHeader(metadata);
      assertTrue(diagnosticsAppender.anyMessageContains("PEDIGREE metadata ID \"A B\" must not contain whitespace"));
    } finally {
      root.detachAppender(diagnosticsAppender);
      diagnosticsAppender.stop();
    }

    StringWriter defaultSw = new StringWriter();
    VcfWriter defaultWriter = new VcfWriter.Builder().toWriter(new PrintWriter(defaultSw)).build();
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender defaultAppender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    defaultAppender.start();
    root.addAppender(defaultAppender);
    try {
      defaultWriter.writeHeader(metadata);
      assertFalse(defaultAppender.anyMessageContains("must not contain whitespace"));
    } finally {
      root.detachAppender(defaultAppender);
      defaultAppender.stop();
    }

    assertTrue(diagnosticsSw.toString().contains("ID=\"A B\"") || diagnosticsSw.toString().contains("ID=A B"));
    assertTrue(defaultSw.toString().contains("ID=\"A B\"") || defaultSw.toString().contains("ID=A B"));
  }

  @Test
  public void testValidateBeforeWriteWarnsOnQuotedWhitespaceOnlyPedigreeIdUnder43() throws Exception {
    // regression test for the retired WONT_FIX.md entry, write-time side: validateMetadata()'s own PEDIGREE
    // loop mirrors VcfMetadata.Builder.addPedigree's blank check (see that method's comment) and must unquote
    // the raw ID property before comparing, the same as the construction-side check does. Same "no appender
    // around construction" structure as testValidateBeforeWriteWarnsOnBlankPedigreeIdUnder43ButDefaultPathIsSilent
    // above, since addPedigree() itself already warns "has no ID" for this quoted-whitespace ID at construction.
    VcfContext lenient43 = VcfContext.lenient("VCFv4.3", VcfWarnings.DEFAULT);
    Map<String, String> props = new LinkedHashMap<>();
    props.put("ID", "\"   \"");
    VcfMetadata metadata = new VcfMetadata.Builder(lenient43).setFileFormat("VCFv4.3")
        .addPedigree(new PedigreeMetadata(lenient43, props))
        .build();

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    StringWriter diagnosticsSw = new StringWriter();
    VcfWriter diagnosticsWriter = new VcfWriter.Builder().toWriter(new PrintWriter(diagnosticsSw))
        .validateBeforeWrite().build();
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender diagnosticsAppender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    diagnosticsAppender.start();
    root.addAppender(diagnosticsAppender);
    try {
      diagnosticsWriter.writeHeader(metadata);
      assertTrue(diagnosticsAppender.anyMessageContains("has no ID"));
    } finally {
      root.detachAppender(diagnosticsAppender);
      diagnosticsAppender.stop();
    }
    assertTrue(diagnosticsSw.toString().contains("##PEDIGREE"));
  }

  /**
   * Two blank-ID PEDIGREE declarations at 4.3+ must each independently warn "has no ID" and never collide as a
   * spurious "Duplicate ID \"\"" -- validateMetadata()'s blank-ID check must {@code continue} before reaching
   * {@code pedigreeIds.add(id)}, mirroring VcfMetadata.Builder.addPedigree's own early return (see
   * VcfMetadataTest.testTwoBlankIdPedigreesUnder43WarnTwiceAndNeverReportDuplicate for the construction-side
   * equivalent). Built with NO appender attached, for the same reason as the single-blank-ID test above.
   */
  @Test
  public void testValidateBeforeWriteWarnsTwiceOnTwoBlankPedigreeIdsUnder43AndNeverReportsDuplicate()
      throws Exception {
    VcfContext lenient43 = VcfContext.lenient("VCFv4.3", VcfWarnings.DEFAULT);
    Map<String, String> props1 = new LinkedHashMap<>();
    props1.put("ID", "");
    Map<String, String> props2 = new LinkedHashMap<>();
    props2.put("ID", "");
    VcfMetadata metadata = new VcfMetadata.Builder(lenient43).setFileFormat("VCFv4.3")
        .addPedigree(new PedigreeMetadata(lenient43, props1))
        .addPedigree(new PedigreeMetadata(lenient43, props2))
        .build();

    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      writer.writeHeader(metadata);
      assertEquals(2, appender.countMessagesContaining("has no ID"));
      assertFalse(appender.anyMessageContains("Duplicate ID"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  /**
   * The 4.2 arm is unchanged: two ##PEDIGREE declarations sharing a literal empty ID="" still collide as a
   * duplicate at write time, exactly as WONT_FIX.md's "PedigreeMetadata duplicate-ID dedup treats a
   * present-but-blank ID the same as a real ID" entry documents for VCFv4.2 -- confirming the 4.3+ fix above
   * did not touch this pre-existing, bcftools-matched behavior. Built via {@code trustedMetadata()}, not
   * {@code Builder.addPedigree()} twice: under 4.2, the Builder's own construction-time duplicate check
   * would already drop the second blank-ID entry (see VcfMetadataTest.testAddPedigreeBlankIdUnder42IsSilentAndStillDedupes),
   * leaving nothing for this write-time check to find -- exactly the same reason
   * testValidateBeforeWriteWarnsOnDuplicatePedigreeIdIntroducedAfterConstruction above uses it.
   */
  @Test
  public void testValidateBeforeWriteStillReportsDuplicateForTwoBlankPedigreeIdsUnder42() throws Exception {
    Map<String, String> props1 = new LinkedHashMap<>();
    props1.put("ID", "");
    Map<String, String> props2 = new LinkedHashMap<>();
    props2.put("ID", "");
    VcfMetadata metadata = trustedMetadata("VCFv4.2", null,
        List.of(new PedigreeMetadata(LENIENT_CONTEXT, props1),
            new PedigreeMetadata(LENIENT_CONTEXT, props2)),
        null);

    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      writer.writeHeader(metadata);
      assertTrue(appender.anyMessageContains("Duplicate ID"));
      assertFalse(appender.anyMessageContains("has no ID"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testValidateBeforeWriteHandlesOversizedNumberAsDiagnostic() throws Exception {
    // an oversized/unparseable Number can't be constructed via the typed constructor (it throws immediately on
    // a bad reserved cardinality), so this builds the same raw properties the typed constructor would have
    // produced via the Map-based constructor instead -- matching how real VCF header parsing constructs
    // InfoMetadata (VcfParser always uses this constructor, which still warns rather than throws)
    Map<String, String> props = new LinkedHashMap<>();
    props.put("ID", "NS");
    props.put("Description", "\"d\"");
    props.put("Type", "Integer");
    props.put("Number", "999999999999999999999");
    InfoMetadata info = new InfoMetadata(LENIENT_CONTEXT, props);
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").addInfo(info).build();
    ListMultimap<String, String> positionInfo = ArrayListMultimap.create();
    positionInfo.put("NS", "1");
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        positionInfo, null, -1);
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();

    writer.writeHeader(metadata);
    writer.writeLine(metadata, position, Collections.emptyList());
    assertTrue(sw.toString().contains("NS=1"));
  }

  @Test
  public void testValidateBeforeWriteDoesNotFalselyWarnOnWholeFieldMissingInfoValue() throws Exception {
    // Number=A with 2 ALT alleles expects 2 values; an INFO value of the whole-field missing sentinel "." is not a
    // real value with an inspectable count (previously falsely compared as 1 value against the expected 2). This
    // must not throw, and the value must round-trip unchanged.
    InfoMetadata info = new InfoMetadata(LENIENT_CONTEXT, "AF", "d", InfoType.Float, "A", null, null);
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").addInfo(info).build();
    ListMultimap<String, String> positionInfo = ArrayListMultimap.create();
    positionInfo.put("AF", ".");
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T", "C"), VcfFloat.of(new BigDecimal("0")),
        null, positionInfo, null, -1);
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();

    writer.writeHeader(metadata);
    writer.writeLine(metadata, position, Collections.emptyList());
    assertTrue(sw.toString().contains("AF=."));
  }

  @Test
  public void testValidateBeforeWriteWarnsOnOutOfRangeIntegerInfoValueRegardlessOfStrictContext() throws Exception {
    // even data built under a STRICT context only warns here -- validateBeforeWrite()'s own policy for
    // semantically-non-compliant-but-safe content is warn-always, independent of the object's own isLenient()
    VcfContext strict = VcfContext.strict("VCFv4.2");
    InfoMetadata info = new InfoMetadata(strict, "INT", "d", InfoType.Integer, "1", null, null);
    VcfMetadata metadata = new VcfMetadata.Builder(strict).setFileFormat("VCFv4.2").addInfo(info).build();
    ListMultimap<String, String> positionInfo = ArrayListMultimap.create();
    positionInfo.put("INT", "2147483648");
    VcfPosition position = strict.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        positionInfo, null, -1);
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    writer.writeHeader(metadata);

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      assertDoesNotThrow(() -> writer.writeLine(metadata, position, Collections.emptyList()));
      assertTrue(appender.anyMessageContains("outside the 32-bit signed range"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testValidateBeforeWriteWarnsOnOutOfRangeIntegerFormatValueRegardlessOfStrictContext() throws Exception {
    // FORMAT-side sibling of testValidateBeforeWriteWarnsOnOutOfRangeIntegerInfoValueRegardlessOfStrictContext
    // above: even data built under a STRICT context only warns here -- validateBeforeWrite()'s own policy for
    // semantically-non-compliant-but-safe content is warn-always, independent of the object's own isLenient()
    VcfContext strict = VcfContext.strict("VCFv4.2");
    FormatMetadata customFormat = new FormatMetadata(strict, "CUSTOM", "d", "1", FormatType.Integer);
    VcfMetadata metadata = new VcfMetadata.Builder(strict).setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .addFormat(customFormat)
        .build();
    VcfPosition position = strict.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        null, List.of("CUSTOM"), -1);
    VcfSample sample = strict.newSample(new LinkedHashMap<>(Map.of("CUSTOM", "2147483648")));

    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    writer.writeHeader(metadata);

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      assertDoesNotThrow(() -> writer.writeLine(metadata, position, Collections.singletonList(sample)));
      assertTrue(appender.anyMessageContains("FORMAT CUSTOM for sample #0"));
      assertTrue(appender.anyMessageContains("outside the 32-bit signed range"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testValidateBeforeWriteWarnsButPreservesInvalidFtValue() throws Exception {
    // FT's grammar mirrors the FILTER column (PASS/./semicolon-separated codes, no code combined with PASS or '.'),
    // but that constraint isn't expressible through FT's declared Type=String, so it's checked separately; being
    // semantically non-compliant but safely representable, this is a warning, not a rejection
    FormatMetadata ft = new FormatMetadata(LENIENT_CONTEXT, ReservedFormatProperty.Filter.getId(), "d", "1",
        FormatType.String);
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .addFormat(ft)
        .build();
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        null, List.of("FT"), -1);
    VcfSample sample = LENIENT_CONTEXT.newSample(new LinkedHashMap<>(Map.of("FT", "PASS;q10")));

    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    writer.writeHeader(metadata);
    writer.writeLine(metadata, position, Collections.singletonList(sample));
    assertTrue(sw.toString().contains("PASS;q10"));
  }

  @Test
  public void testValidateBeforeWriteWarnsRatherThanThrowsForStrictContextSampleWithInvalidFtValue() throws Exception {
    // FORMAT-side sibling of testValidateBeforeWriteWarnsRatherThanThrowsForStrictContextRecordWithNegativeAcValue:
    // FT (checkFilterLikeValue) is the one of the five isLenient()-aware checks that both throws AND has a
    // normalizing return value the writer deliberately discards -- metadata/position/sample are built under
    // STRICT_CONTEXT specifically to prove validateFormatValue's own checkReservedFormatConstraints call still
    // only warns and preserves raw content for a strict-context record, never throws, matching AGENTS.md's
    // Writer Validation Policy ("the write side always warns").
    FormatMetadata ft = new FormatMetadata(STRICT_CONTEXT, ReservedFormatProperty.Filter.getId(), "d", "1",
        FormatType.String);
    VcfMetadata metadata = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .addFormat(ft)
        .build();
    VcfPosition position = STRICT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        null, List.of("FT"), -1);
    // VcfSample has no lazy/raw construction route the way VcfPosition's INFO/QUAL do -- every public
    // construction path is eager, so checkReservedFormatTypes' new construction-time check now makes it
    // impossible for ANY public route to produce a strict-context sample holding this exact violation (FT's
    // grammar check is isLenient()-aware, so strict construction now throws immediately). newDoublyUncheckedForTest
    // exists precisely for this: synthesizing malformed state a real caller can no longer produce, to keep testing
    // validateBeforeWrite()'s own independent policy in isolation from that now-unrelated construction-time check.
    VcfSample sample = VcfSample.newDoublyUncheckedForTest(STRICT_CONTEXT, List.of("FT"), List.of("PASS;q10"));

    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    writer.writeHeader(metadata);
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      assertDoesNotThrow(() -> writer.writeLine(metadata, position, Collections.singletonList(sample)));
      assertTrue(appender.anyMessageContains("combines PASS or '.' with other filter codes"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertTrue(sw.toString().contains("PASS;q10"));
  }

  @Test
  public void testValidateBeforeWriteDoesNotRewriteFtEmptyCode() throws Exception {
    // unlike the typed read accessor (InternalUtils.convertProperty), which drops an empty FT code, this writer
    // preserves raw sample content rather than rewriting it -- the same "read normalizes, write preserves"
    // split already used for a reserved list-typed FORMAT value's empty entry
    FormatMetadata ft = new FormatMetadata(LENIENT_CONTEXT, ReservedFormatProperty.Filter.getId(), "d", "1",
        FormatType.String);
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .addFormat(ft)
        .build();
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        null, List.of("FT"), -1);
    VcfSample sample = LENIENT_CONTEXT.newSample(new LinkedHashMap<>(Map.of("FT", "PASS;;q10")));

    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    writer.writeHeader(metadata);
    writer.writeLine(metadata, position, Collections.singletonList(sample));
    assertTrue(sw.toString().contains("PASS;;q10"));
  }

  @Test
  public void testValidateBeforeWriteWarnsButPreservesOutOfRangePsValue() throws Exception {
    // PS must be a non-negative 32-bit integer, but that constraint isn't expressible through PS's declared
    // Type=Integer (which maps to a 64-bit Long), so it's checked separately; a negative value is semantically
    // non-compliant but safely representable, so it's a warning, not a rejection
    FormatMetadata ps = new FormatMetadata(LENIENT_CONTEXT, ReservedFormatProperty.PhaseSet.getId(), "d", "1",
        FormatType.Integer);
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .addFormat(ps)
        .build();
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        null, List.of("PS"), -1);
    VcfSample sample = LENIENT_CONTEXT.newSample(new LinkedHashMap<>(Map.of("PS", "-5")));

    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    writer.writeHeader(metadata);
    writer.writeLine(metadata, position, Collections.singletonList(sample));
    assertTrue(sw.toString().contains("PS\t-5"));
  }

  @Test
  public void testValidateBeforeWriteGenotypePosteriorRangeCheckIsVersionGated() throws Exception {
    // GP's [0, 1] range check applies only under VCFv4.3+ (VCFv4.3.tex line 491; see
    // ReservedFormatProperty.GenotypePosterior's own comment) -- under <=4.2 any Float is a legitimate phred
    // value, so validateBeforeWrite() must not warn there for the same out-of-range value. writeLine() requires
    // metadata/position/samples to share one VcfContext (Task 2), and that context is fixed for the lifetime of
    // one writeHeader()/writeLine() sequence, so this uses two independent writers -- one per version -- sharing
    // one warning-capturing appender.
    VcfContext context43 = VcfContext.lenient("VCFv4.3", VcfWarnings.DEFAULT);

    FormatMetadata gp42 = new FormatMetadata(LENIENT_CONTEXT, ReservedFormatProperty.GenotypePosterior.getId(), "d",
        "G", FormatType.Float);
    VcfMetadata metadata42 = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .addFormat(gp42)
        .build();
    VcfPosition position42 = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")),
        null, null, List.of("GP"), -1);
    VcfSample sample42 = LENIENT_CONTEXT.newSample(new LinkedHashMap<>(Map.of("GP", "5.0")));

    FormatMetadata gp43 = new FormatMetadata(context43, ReservedFormatProperty.GenotypePosterior.getId(), "d", "G",
        FormatType.Float);
    VcfMetadata metadata43 = new VcfMetadata.Builder(context43).setFileFormat("VCFv4.3")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .addFormat(gp43)
        .build();
    VcfPosition position43 = context43.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        null, List.of("GP"), -1);
    VcfSample sample43 = context43.newSample(new LinkedHashMap<>(Map.of("GP", "5.0")));

    StringWriter sw42 = new StringWriter();
    VcfWriter writer42 = new VcfWriter.Builder().toWriter(new PrintWriter(sw42)).validateBeforeWrite().build();
    StringWriter sw43 = new StringWriter();
    VcfWriter writer43 = new VcfWriter.Builder().toWriter(new PrintWriter(sw43)).validateBeforeWrite().build();

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      writer42.writeHeader(metadata42);
      writer42.writeLine(metadata42, position42, Collections.singletonList(sample42));
      assertFalse(appender.anyMessageContains("does not lie in the interval"));

      writer43.writeHeader(metadata43);
      writer43.writeLine(metadata43, position43, Collections.singletonList(sample43));
      assertTrue(appender.anyMessageContains("does not lie in the interval"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertTrue(sw42.toString().contains("GP\t5.0"));
    assertTrue(sw43.toString().contains("GP\t5.0"));
  }

  @Test
  public void testValidateBeforeWriteCopyNumberPosteriorProbabilitiesRangeCheckIsUnconditional() throws Exception {
    // regression test found by the Phase 2 review's re-verification round: CNP's [0, 1] range check
    // (VCFv4.3.tex line 689) is unconditional -- unlike GP, CNP has no VCFv4.2 declaration at all, so there is
    // no version gate to test here, just that validateBeforeWrite() catches an out-of-range value at all.
    VcfContext context43 = VcfContext.lenient("VCFv4.3", VcfWarnings.DEFAULT);
    FormatMetadata cnp = new FormatMetadata(context43,
        ReservedFormatProperty.CopyNumberPosteriorProbabilities.getId(), "d", "G", FormatType.Float);
    VcfMetadata metadata = new VcfMetadata.Builder(context43).setFileFormat("VCFv4.3")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .addFormat(cnp)
        .build();
    VcfPosition position = context43.newPosition("chr1", 1, null, "A", List.of("T"),
        VcfFloat.of(new BigDecimal("0")), null, null, List.of("CNP"), -1);
    VcfSample sample = context43.newSample(new LinkedHashMap<>(Map.of("CNP", "5.0")));

    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      writer.writeHeader(metadata);
      writer.writeLine(metadata, position, Collections.singletonList(sample));
      assertTrue(appender.anyMessageContains("does not lie in the interval"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertTrue(sw.toString().contains("CNP\t5.0"));
  }

  @Test
  public void testValidateBeforeWriteGenotypePosteriorMultiValuedWarnsOnceForMiddleElement() throws Exception {
    // GP is Number=G; on a diploid (GT=0|1), one-ALT-allele record, Number=G expects exactly
    // combinationsWithRepetition(2, 2) = 3 values, so this correctly-sized 3-value GP list isolates the
    // range-check assertion from any Number=G cardinality warning (see
    // VcfSampleTest.testGetPropertyWithPositionAndMetadataGenotypePosteriorMultiValuedWarnsOnceForMiddleElement,
    // this test's read-path counterpart, for the full cardinality-math citation). The middle element (5.0) is
    // out of [0, 1]; an implementation that scalar-parsed the whole "0.2,5.0,0.8" value would throw
    // NumberFormatException, fall through to ordinary list conversion, and silently accept it.
    VcfContext context43 = VcfContext.lenient("VCFv4.3", VcfWarnings.DEFAULT);
    List<String> columns = Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1");
    LinkedHashMap<String, String> sampleProperties = new LinkedHashMap<>();
    sampleProperties.put("GT", "0|1");
    sampleProperties.put("GP", "0.2,5.0,0.8");

    FormatMetadata gp42 = new FormatMetadata(LENIENT_CONTEXT, ReservedFormatProperty.GenotypePosterior.getId(), "d",
        "G", FormatType.Float);
    VcfMetadata metadata42 = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").setColumns(columns)
        .addFormat(gp42).build();
    VcfPosition position42 = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")),
        null, null, List.of("GT", "GP"), -1);
    VcfSample sample42 = LENIENT_CONTEXT.newSample(new LinkedHashMap<>(sampleProperties));

    FormatMetadata gp43 = new FormatMetadata(context43, ReservedFormatProperty.GenotypePosterior.getId(), "d", "G",
        FormatType.Float);
    VcfMetadata metadata43 = new VcfMetadata.Builder(context43).setFileFormat("VCFv4.3").setColumns(columns)
        .addFormat(gp43).build();
    VcfPosition position43 = context43.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        null, List.of("GT", "GP"), -1);
    VcfSample sample43 = context43.newSample(new LinkedHashMap<>(sampleProperties));

    StringWriter sw42 = new StringWriter();
    VcfWriter writer42 = new VcfWriter.Builder().toWriter(new PrintWriter(sw42)).validateBeforeWrite().build();
    StringWriter sw43 = new StringWriter();
    VcfWriter writer43 = new VcfWriter.Builder().toWriter(new PrintWriter(sw43)).validateBeforeWrite().build();

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      writer42.writeHeader(metadata42);
      writer42.writeLine(metadata42, position42, Collections.singletonList(sample42));
      assertFalse(appender.anyMessageContains("does not lie in the interval"));
      assertFalse(appender.anyMessageContains("value(s), but Number=G requires"));

      writer43.writeHeader(metadata43);
      writer43.writeLine(metadata43, position43, Collections.singletonList(sample43));
      assertEquals(1, appender.countMessagesContaining("does not lie in the interval"));
      assertTrue(appender.anyMessageContains("5.0"));
      assertFalse(appender.anyMessageContains("value(s), but Number=G requires"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertTrue(sw42.toString().contains("GT:GP\t0|1:0.2,5.0,0.8"));
    assertTrue(sw43.toString().contains("GT:GP\t0|1:0.2,5.0,0.8"));
  }

  @Test
  public void testValidateBeforeWriteWarnsButPreservesNegativeAcValue() throws Exception {
    // AC must be a non-negative integer, but that constraint isn't expressible through AC's declared
    // Type=Integer (which maps to a 64-bit Long), so it's checked separately (mirroring FT/PS's own treatment
    // on the FORMAT side); a negative value is semantically non-compliant but safely representable, so it's a
    // warning, not a rejection. Captures the actual warning (not just the preserved output) so this test
    // genuinely regression-guards validateInfoValue's own checkReservedInfoConstraints call, not just the
    // unrelated fact that this writer never rewrites INFO content.
    InfoMetadata ac = new InfoMetadata(LENIENT_CONTEXT, ReservedInfoProperty.AlleleCount.getId(), "d", InfoType.Integer,
        "A",
        null, null);
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").addInfo(ac).build();
    ListMultimap<String, String> positionInfo = ArrayListMultimap.create();
    positionInfo.put("AC", "-1");
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        positionInfo, null, -1);

    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    writer.writeHeader(metadata);
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      writer.writeLine(metadata, position, Collections.emptyList());
      assertTrue(appender.anyMessageContains("INFO AC value \"-1\" is a negative number"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertTrue(sw.toString().contains("AC=-1"));
  }

  @Test
  public void testValidateBeforeWriteWarnsRatherThanThrowsForStrictContextRecordWithNegativeAcValue() throws Exception {
    // InternalUtils.checkReservedInfoConstraints (and its four siblings, plus checkFilterLikeValue) became
    // isLenient()-aware on the READ side; the write side must stay unaffected regardless of the written
    // record's own strict/lenient origin, per AGENTS.md's Writer Validation Policy ("the write side always
    // warns"). metadata/position are built under STRICT_CONTEXT specifically to prove that: VcfWriter's own
    // validateInfoValue passes its own m_warnings (always non-null) to checkReservedInfoConstraints regardless
    // of what context produced the record being written, so this must still warn and preserve the value, not
    // throw, even though reading AC via position.getInfo(ReservedInfoProperty.AlleleCount) on this same
    // strict-context position would throw (see InternalUtilsTest's end-to-end strict tests).
    InfoMetadata ac = new InfoMetadata(STRICT_CONTEXT, ReservedInfoProperty.AlleleCount.getId(), "d", InfoType.Integer,
        "A", null, null);
    VcfMetadata metadata = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2").addInfo(ac).build();
    // attached via rawInfo(), not a pre-built ListMultimap: checkReservedInfoTypes' new construction-time check
    // only runs on the eager already-split-multimap path (never reachable via VcfParser, which always stays
    // lazy too) -- the lazy rawInfo() path stays lazy even under strict, so this is still the only genuinely
    // public route left to construct a strict-context position carrying an as-yet-unvalidated reserved value
    VcfPosition position = STRICT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        null, null, -1).toBuilder().rawInfo("AC=-1").build();

    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    writer.writeHeader(metadata);
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      writer.writeLine(metadata, position, Collections.emptyList()); // must not throw
      assertTrue(appender.anyMessageContains("INFO AC value \"-1\" is a negative number"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertTrue(sw.toString().contains("AC=-1"));
  }

  @Test
  public void testValidateBeforeWriteRoutesReservedInfoConstraintWarningOnlyToWritersOwnDestination(
      @org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
    // VCF 4.3 retarget, Phase 5 Task 3: writeLine() now also derives the record's resolved version and threads
    // it into validateInfo/validateSamples and, from there, into checkReservedInfoConstraints/
    // checkReservedFormatConstraints -- but the warnings sink those two checks use is still m_warnings, this
    // writer's own configured sink, exactly as before this task. A refactor that instead read the version and
    // warnings sink together off the record's own VcfContext (e.g. passing metadata.getContext() itself, or a
    // pair built from it) would misroute this exact warning to that context's own destination instead. metadata/
    // position here are built under recordContext, a VcfContext with its own independently observable
    // StringWriter-backed sink, wholly separate from both this writer's warningsToFile() destination and the
    // shared SLF4J logger VcfWarnings.DEFAULT would otherwise fall back to, so a misrouted warning cannot hide
    // behind either of those two coincidentally being the same destination.
    StringWriter recordSink = new StringWriter();
    VcfContext recordContext = VcfContext.lenient("VCFv4.2", new VcfWarnings(recordSink));
    InfoMetadata ac = new InfoMetadata(recordContext, ReservedInfoProperty.AlleleCount.getId(), "d", InfoType.Integer,
        "A", null, null);
    VcfMetadata metadata = new VcfMetadata.Builder(recordContext).setFileFormat("VCFv4.2").addInfo(ac).build();
    ListMultimap<String, String> positionInfo = ArrayListMultimap.create();
    positionInfo.put("AC", "-1");
    VcfPosition position = recordContext.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        positionInfo, null, -1);

    Path writerWarningsFile = tempDir.resolve("writer-warnings.log");
    StringWriter sw = new StringWriter();
    try (VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite()
        .warningsToFile(writerWarningsFile).build()) {
      writer.writeHeader(metadata);
      writer.writeLine(metadata, position, Collections.emptyList());
    }
    assertTrue(Files.readString(writerWarningsFile).contains("INFO AC value \"-1\" is a negative number"));
    // checkReservedInfoTypes' new construction-time check ALSO now warns about this exact violation, but to
    // recordContext's own sink (recordSink), since `position` was built under recordContext directly from a
    // pre-built ListMultimap -- this is a second, genuinely independent diagnostic emission point, not a
    // misroute of the writer's own warning: this test's actual claim (validateInfoValue's warning goes to the
    // WRITER's sink, never to the record's own construction-time context) still holds, and is still verified
    // by writerWarningsFile containing the message while metadata/position's own context sink is a DIFFERENT
    // destination than where the writer's warning landed.
    assertTrue(recordSink.toString().contains("INFO AC value \"-1\" is a negative number"));
  }

  @Test
  public void testValidateBeforeWriteWarnsButPreservesInvalidCigarValue() throws Exception {
    // CIGAR's grammar isn't expressible through its declared Type=String (which passes any value through
    // unchanged), so it's checked separately (mirroring FT/PS/AC's own treatment); an invalid value is
    // semantically non-compliant but safely representable, so it's a warning, not a rejection
    InfoMetadata cigar = new InfoMetadata(LENIENT_CONTEXT, ReservedInfoProperty.Cigar.getId(), "d", InfoType.String,
        "A",
        null, null);
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").addInfo(cigar).build();
    ListMultimap<String, String> positionInfo = ArrayListMultimap.create();
    positionInfo.put("CIGAR", "123MYCIGAR");
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        positionInfo, null, -1);

    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    writer.writeHeader(metadata);
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      writer.writeLine(metadata, position, Collections.emptyList());
      assertTrue(appender.anyMessageContains("does not match the expected CIGAR grammar"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertTrue(sw.toString().contains("CIGAR=123MYCIGAR"));
  }

  @Test
  public void testValidateBeforeWriteRejectsLineBeforeHeader() throws Exception {
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").build();
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        null, null, -1);

    assertThrows(VcfFormatException.class,
        () -> writer.writeLine(metadata, position, Collections.emptyList()));
  }

  @Test
  public void testValidateBeforeWriteRejectsDuplicateHeader() throws Exception {
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").build();

    writer.writeHeader(metadata);
    assertThrows(VcfFormatException.class, () -> writer.writeHeader(metadata));
  }

  @Test
  public void testValidateBeforeWriteRejectsMetadataMismatchWithHeader() throws Exception {
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    VcfMetadata headerMetadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").build();
    VcfMetadata otherMetadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").build();
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        null, null, -1);

    writer.writeHeader(headerMetadata);
    // a different (even if equivalent-looking) VcfMetadata object than the one used for the header would make the
    // emitted header and this record inconsistent if either were mutated independently afterward
    assertThrows(VcfFormatException.class,
        () -> writer.writeLine(otherMetadata, position, Collections.emptyList()));
  }

  @Test
  public void testMetadataCannotBeMutatedAfterHeaderWritten() throws Exception {
    // Replaces testValidateBeforeWriteRejectsMetadataMutatedAfterHeaderWritten and
    // testWriteLineDoesNotDetectInPlaceMetadataMutationInDefaultMode. Those pinned VcfWriter's header-text
    // snapshot: writeHeader() recorded the exact header it emitted, and every writeLine() under
    // validateBeforeWrite() rebuilt the header from the same metadata object and compared, so that a caller who
    // mutated that object in place (originally metadata.addInfo(...); later, once the instance mutators were
    // gone, an edit through a live getInfo()/getPropertiesRaw() view) was rejected rather than silently writing
    // a record inconsistent with the header a reader actually sees.
    //
    // VcfMetadata is immutable now, so the same object always produces the same header text and the check was
    // removed (it cost a full header rebuild per record in diagnostics mode). What is asserted instead is that
    // every public route that could once have caused the drift now throws UnsupportedOperationException, and
    // that writing continues normally after the failed attempts.
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    InfoMetadata info = new InfoMetadata(LENIENT_CONTEXT, "NS", "d", InfoType.Integer, "1", null, null);
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .addInfo(info)
        .build();
    writer.writeHeader(metadata);

    InfoMetadata declared = metadata.getInfo().get("NS");
    // editing an existing declaration's properties -- including through Map.Entry.setValue, which an
    // unmodifiableMap must also refuse
    assertThrows(UnsupportedOperationException.class,
        () -> declared.getPropertiesRaw().put("Description", "\"changed after the header\""));
    assertThrows(UnsupportedOperationException.class,
        () -> declared.getPropertiesRaw().entrySet().iterator().next().setValue("\"changed\""));
    assertThrows(UnsupportedOperationException.class, () -> declared.getPropertyKeys().remove("Description"));
    // adding or removing a whole declaration
    assertThrows(UnsupportedOperationException.class, () -> metadata.getInfo().remove("NS"));
    assertThrows(UnsupportedOperationException.class, () -> metadata.getInfo().put("AF", info));
    assertThrows(UnsupportedOperationException.class, () -> metadata.getPedigrees().add(null));
    // and the generic ##key=value properties the header also carries
    assertThrows(UnsupportedOperationException.class, () -> metadata.getRawPropertyKeys().remove("reference"));

    ListMultimap<String, String> positionInfo = ArrayListMultimap.create();
    positionInfo.put("NS", "1");
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        positionInfo, null, -1);
    writer.writeLine(metadata, position, Collections.emptyList());

    assertTrue(sw.toString().contains("Description=\"d\""));
    assertFalse(sw.toString().contains("changed"));
  }

  @Test
  public void testNoSamples() throws Exception {
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    // ##INFO=<ID=NS,Number=1,Type=Integer,Description="Number of samples with data">
    InfoMetadata info = new InfoMetadata(LENIENT_CONTEXT, "NS", "Number of samples with data", InfoType.Integer, "1",
        null, null);
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").addInfo(info).build();
    ListMultimap<String, String> firstInfo = ArrayListMultimap.create();
    firstInfo.put("NS", "0");
    VcfPosition first = LENIENT_CONTEXT.newPosition("chr1", 1, List.of("rsa"), "A", List.of("T"),
        VcfFloat.of(new BigDecimal("0")), null, firstInfo, null, -1);
    VcfPosition second = LENIENT_CONTEXT.newPosition("chr1", 2, List.of("rsb"), "A", List.of("T"),
        VcfFloat.of(new BigDecimal("0")), null, null, null, -1);
    writer.writeHeader(metadata);
    writer.writeLine(metadata, first, Collections.emptyList());
    writer.writeLine(metadata, second, Collections.emptyList());
    String expected = TestUtils.readFileToString(Paths.get(getClass().getResource("/no_samples.vcf").toURI()));
    assertEquals(expected, sw.toString());
  }

  @Test
  public void testWriteFilterStatus() throws Exception {
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);

    VcfPosition none = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), null,
        Collections.singletonList("."), null, null, -1);
    VcfPosition passed = LENIENT_CONTEXT.newPosition("chr1", 2, null, "A", List.of("T"), null,
        Collections.singletonList("PASS"), null, null, -1);
    VcfPosition failed = LENIENT_CONTEXT.newPosition("chr1", 3, null, "A", List.of("T"), null,
        Collections.singletonList("q10"), null, null, -1);

    writer.writeLine(metadata, none, Collections.emptyList());
    writer.writeLine(metadata, passed, Collections.emptyList());
    writer.writeLine(metadata, failed, Collections.emptyList());

    String[] lines = sw.toString().split("\n");
    assertEquals(".", lines[2].split("\t")[6]);    // NONE -> missing value, not PASS
    assertEquals("PASS", lines[3].split("\t")[6]); // PASSED
    assertEquals("q10", lines[4].split("\t")[6]);  // FAILED
  }

  @Test
  public void testWriteInfoWithoutType() throws Exception {
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    // INFO metadata with no Type -> getType() is null; the writer must not NPE
    InfoMetadata info = new InfoMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
        new String[]{"ID=NS", "Number=1", "Description=\"n\""}, VcfWarnings.DEFAULT));
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").addInfo(info).build();
    writer.writeHeader(metadata);
    ListMultimap<String, String> positionInfo = ArrayListMultimap.create();
    positionInfo.put("NS", "3");
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        positionInfo, null, -1);
    writer.writeLine(metadata, position, Collections.emptyList());
    assertTrue(sw.toString().contains("NS=3"));
  }

  @Test
  public void testWriteHeaderEscapesAndRoundTripsQuotedDescription() throws Exception {
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    InfoMetadata info = new InfoMetadata(LENIENT_CONTEXT, "NS", "a \"quoted\" description with a \\ backslash",
        InfoType.Integer, "1",
        null, null);
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").addInfo(info).build();
    writer.writeHeader(metadata);
    String output = sw.toString();
    // the embedded quote and backslash must be escaped in the written line, not left to break the quoted string
    assertTrue(output.contains("Description=\"a \\\"quoted\\\" description with a \\\\ backslash\""), output);

    // round-trip: re-parsing the written header must decode back to the original, unescaped description
    try (BufferedReader reader = new BufferedReader(new StringReader(output));
         VcfParser parser = new VcfParser.Builder().fromReader(reader).parseWith((m, r) -> { }).build()) {
      VcfMetadata reparsed = parser.parseMetadata();
      assertEquals("a \"quoted\" description with a \\ backslash", reparsed.getInfo().get("NS").getDescription());
    }
  }

  @Test
  public void testWriteLineWithoutFormatMetadata() throws Exception {
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "SAMPLE1"))
        .build();
    writer.writeHeader(metadata);
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        null, List.of("GT"), -1);
    VcfSample sample = LENIENT_CONTEXT.newSample(new LinkedHashMap<>(Map.of("GT", "0/1")));
    // FORMAT metadata is absent: this previously threw NullPointerException
    writer.writeLine(metadata, position, Collections.singletonList(sample));
    assertTrue(sw.toString().contains("\tGT\t0/1"));
  }

  @Test
  public void testWriteLineWithMissingSampleProperty() throws Exception {
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "SAMPLE1"))
        .build();
    writer.writeHeader(metadata);
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        null, List.of("GT", "DP"), -1);
    VcfSample sample = LENIENT_CONTEXT.newSample(new LinkedHashMap<>(Map.of("GT", "0/1"))); // no DP property
    // sample has fewer properties than FORMAT: this previously threw NoSuchElementException
    writer.writeLine(metadata, position, Collections.singletonList(sample));
    assertTrue(sw.toString().contains("\tGT:DP\t0/1:."));
  }

  @Test
  public void testDefaultWriteSubstitutesEmptySampleValueWithDot() throws Exception {
    // regression test: the default path previously only substituted "." for a null sample property value, not a
    // non-null empty one (e.g. an explicit "" property value) --
    // testValidateBeforeWriteNormalizesEmptySampleValue covers the diagnostics-mode case (via VcfSample.validate()),
    // this covers the default-path sibling EMPTY_FIELD_HANDLING.md's finding said was missing
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "SAMPLE1"))
        .build();
    writer.writeHeader(metadata);
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        null, List.of("GT", "DP"), -1);
    LinkedHashMap<String, String> sampleProps = new LinkedHashMap<>();
    sampleProps.put("GT", "0/1");
    sampleProps.put("DP", "");
    VcfSample sample = LENIENT_CONTEXT.newSample(sampleProps);

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      writer.writeLine(metadata, position, Collections.singletonList(sample));
      assertTrue(sw.toString().contains("\tGT:DP\t0/1:."));
      assertTrue(appender.anyMessageContains("property DP has an empty value"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testWriteInfoValueNotMatchingDeclaredTypeWarnsInsteadOfThrowing() throws Exception {
    // InternalUtils.convertProperty/convertElement only ever throw VcfFormatException for a bad value, never
    // IllegalArgumentException; addInfoOrDot's "just test" conversion caught IllegalArgumentException, so this
    // previously propagated out of writeLine uncaught instead of just logging a warning
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    // "XX" (not a reserved key like "AN"): checkReservedInfoTypes' new construction-time check would otherwise
    // reject this value eagerly, before the writer this test is actually exercising ever sees it -- unlike a
    // cardinality/range mismatch, an unparseable value throws unconditionally regardless of context (see
    // InternalUtils.convertElement's own entry in VALIDATION_REFERENCE.md), so LENIENT_CONTEXT does not help here
    InfoMetadata info = new InfoMetadata(LENIENT_CONTEXT, "XX", "d", InfoType.Integer, "1", null, null);
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").addInfo(info).build();
    writer.writeHeader(metadata);
    ListMultimap<String, String> positionInfo = ArrayListMultimap.create();
    positionInfo.put("XX", "not-a-number"); // Type=Integer declared, but the value doesn't parse as one
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        positionInfo, null, -1);
    writer.writeLine(metadata, position, Collections.emptyList());
    assertTrue(sw.toString().contains("XX=not-a-number"));
  }

  @Test
  public void testWriteSamplePropertyNotMatchingDeclaredTypeWarnsInsteadOfThrowing() throws Exception {
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    // "XX" (not a reserved key like "DP"): checkReservedFormatTypes' new construction-time check would otherwise
    // reject this value eagerly, before the writer this test is actually exercising ever sees it -- unlike a
    // cardinality/range mismatch, an unparseable value throws unconditionally regardless of context (see
    // InternalUtils.convertElement's own entry in VALIDATION_REFERENCE.md), so LENIENT_CONTEXT does not help here
    FormatMetadata format = new FormatMetadata(LENIENT_CONTEXT, "XX", "d", "1", FormatType.Integer);
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").addFormat(format)
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "SAMPLE1"))
        .build();
    writer.writeHeader(metadata);
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        null, List.of("XX"), -1);
    // Type=Integer declared, but the value doesn't parse as one
    VcfSample sample = LENIENT_CONTEXT.newSample(new LinkedHashMap<>(Map.of("XX", "not-a-number")));
    writer.writeLine(metadata, position, Collections.singletonList(sample));
    assertTrue(sw.toString().contains("\tXX\tnot-a-number"));
  }

  @Test
  public void testWriteLineRejectsTooFewSamples() throws Exception {
    // header declares 2 samples, but only 1 is provided: the output would not parse back against its own header
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1", "S2"))
        .build();
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        null, List.of("GT"), -1);
    VcfSample sample = LENIENT_CONTEXT.newSample(new LinkedHashMap<>(Map.of("GT", "0/1")));
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    writer.writeHeader(metadata);
    VcfFormatException e = assertThrows(VcfFormatException.class,
        () -> writer.writeLine(metadata, position, Collections.singletonList(sample)));
    assertTrue(e.getMessage().contains("but the header declares"));
  }

  @Test
  public void testWriteLineRejectsTooManySamples() throws Exception {
    // header declares 1 sample, but 2 are provided
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .build();
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        null, List.of("GT"), -1);
    VcfSample sample1 = LENIENT_CONTEXT.newSample(new LinkedHashMap<>(Map.of("GT", "0/1")));
    VcfSample sample2 = LENIENT_CONTEXT.newSample(new LinkedHashMap<>(Map.of("GT", "1/1")));
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    writer.writeHeader(metadata);
    VcfFormatException e = assertThrows(VcfFormatException.class,
        () -> writer.writeLine(metadata, position, Arrays.asList(sample1, sample2)));
    assertTrue(e.getMessage().contains("but the header declares"));
  }

  @Test
  public void testWriteLineRejectsSampleDataWhenHeaderHasNoSamples() throws Exception {
    // header declares no samples (no setColumns call), but FORMAT/sample data is provided anyway
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").build();
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        null, List.of("GT"), -1);
    VcfSample sample = LENIENT_CONTEXT.newSample(new LinkedHashMap<>(Map.of("GT", "0/1")));
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    writer.writeHeader(metadata);
    VcfFormatException e = assertThrows(VcfFormatException.class,
        () -> writer.writeLine(metadata, position, Collections.singletonList(sample)));
    assertTrue(e.getMessage().contains("has FORMAT or sample data, but the header declares no samples"));
  }

  @Test
  public void testWriteLineWritesDotFormatAndSampleWhenFormatEmpty() throws Exception {
    // FORMAT="." (no per-sample format fields) is valid when sample columns are declared -- see
    // VcfPosition.checkFormat's javadoc for the bcftools 1.23.1-verified rationale. A sample's own properties are
    // warned about and dropped, since there is no FORMAT key left for them to correspond to.
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .build();
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        null, null, -1);
    // position.getFormat() is left empty
    VcfSample sample = LENIENT_CONTEXT.newSample(new LinkedHashMap<>(Map.of("GT", "0/1")));
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    writer.writeHeader(metadata);
    writer.writeLine(metadata, position, Collections.singletonList(sample));
    assertTrue(sw.toString().endsWith("\t.\t.\t.\t.\n"));
  }

  @Test
  public void testWriteLineValidateBeforeWriteAllowsEmptyFormatWithEmptySample() throws Exception {
    // the clean case: FORMAT is empty and the sample has no properties either, so no warning path is exercised;
    // this must also work in validateBeforeWrite() mode, not just the default fast path
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .build();
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        null, null, -1);
    VcfSample sample = LENIENT_CONTEXT.newSample(new LinkedHashMap<>());
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    writer.writeHeader(metadata);
    writer.writeLine(metadata, position, Collections.singletonList(sample));
    assertTrue(sw.toString().endsWith("\t.\t.\t.\t.\n"));
  }

  @Test
  public void testWriteHeaderRejectsLoneCarriageReturnInjectedThroughRawView() throws Exception {
    // getPropertiesRaw() bypasses checkNoLineTerminator (see its javadoc); printLine previously only checked for
    // "\n", so a lone "\r" injected this way would silently corrupt the output instead of failing loudly
    InfoMetadata info = new InfoMetadata(LENIENT_CONTEXT, "NS", "d", InfoType.Integer, "1", null, null);
    info.trustedMutableProperties().put("Description", "bad\rvalue");
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").addInfo(info).build();
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    assertThrows(VcfFormatException.class, () -> writer.writeHeader(metadata));
    // a line-terminator content rejection is not a write/IO failure -- it must not poison the writer
    assertTrue(writer.isValid());
  }

  @Test
  public void testValidateBeforeWriteRejectsLineTerminatorInMetadataProperty() throws Exception {
    // validateKeyedMetadataEntries() calls entry.validate() (which already runs checkNoLineTerminator on every
    // property) immediately before its own validateMetadataProperties() call -- pins that this
    // validateBeforeWrite()-mode path still rejects a line terminator via entry.validate() alone, now that
    // validateMetadataProperties() no longer redundantly re-checks the same property for the same thing
    InfoMetadata info = new InfoMetadata(LENIENT_CONTEXT, "NS", "d", InfoType.Integer, "1", null, null);
    info.trustedMutableProperties().put("Description", "bad\rvalue");
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").addInfo(info).build();
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    assertThrows(VcfFormatException.class, () -> writer.writeHeader(metadata));
  }

  @Test
  public void testWriteHeaderIsAtomicWhenALaterLineContainsALineTerminator() throws Exception {
    // regression test: the pre-fix code checked each header line for a terminator inside the same loop that
    // printed it, so lines before the bad one (including ##fileformat, always line 1) were already written to the
    // underlying writer by the time the exception was thrown. That silently broke the documented-safe "catch
    // VcfFormatException, fix the content, retry writeHeader()" pattern, since a retry would then duplicate the
    // already-written clean lines (e.g. a second ##fileformat=VCFv4.2). writeHeader() must scan every line for a
    // terminator before printing any of them, so a rejection leaves nothing written at all.
    // bypass addRawProperty's own checkNoLineTerminator to inject a bad SECOND raw property (the first is
    // clean, and is what proves nothing at all gets written) -- the same trusted bypass exercised by
    // testWriteHeaderRejectsLoneCarriageReturnInjectedThroughRawView above, one level up
    ListMultimap<String, String> rawProperties = ArrayListMultimap.create();
    rawProperties.put("source", "myProgram");
    rawProperties.put("Bad", "bad\rvalue");
    VcfMetadata metadata = trustedMetadata("VCFv4.2", null, null, rawProperties);
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    assertThrows(VcfFormatException.class, () -> writer.writeHeader(metadata));
    // nothing should have been written at all -- not even the clean ##fileformat/##source lines that precede the
    // bad one -- proving the check now runs before any printing, not interleaved with it
    assertEquals("", sw.toString());
    // a line-terminator content rejection is not a write/IO failure -- it must not poison the writer
    assertTrue(writer.isValid());
  }

  @Test
  public void testToFileWritesUtf8() throws Exception {
    // VcfParser.Builder.build() reads files with Files.newBufferedReader (UTF-8 by default); toFile() must
    // write with the same encoding or non-ASCII content can be corrupted on a JVM whose platform-default
    // charset isn't UTF-8
    java.nio.file.Path file = java.nio.file.Files.createTempFile("vcf-writer-charset-test", ".vcf");
    try {
      VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
          .addRawProperty("source", "café")
          .build();
      try (VcfWriter writer = new VcfWriter.Builder().toFile(file).build()) {
        writer.writeHeader(metadata);
      }
      String content = java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
      assertTrue(content.contains("café"));
    } finally {
      java.nio.file.Files.deleteIfExists(file);
    }
  }

  @Test
  public void testWriteHeaderDetectsMalformedEncodingWhenWritingToFile() throws Exception {
    // regression test: writing an unpaired surrogate (invalid as UTF-8) through the toFile(Path) path must now
    // be detected as a write failure, not silently replaced with '?' -- matches VcfParser's own read-side
    // strictness (Files.newBufferedReader already throws MalformedInputException on malformed input)
    java.nio.file.Path file = java.nio.file.Files.createTempFile("vcf-writer-encoding-test", ".vcf");
    VcfWriter writer = null;
    try {
      // lone high surrogate: invalid UTF-8
      VcfMetadata metadata = trustedMetadataWithRawProperty("BadEncoding", "bad\uD800surrogate");
      writer = new VcfWriter.Builder().toFile(file).build();
      final VcfWriter finalWriter = writer;
      assertThrows(UncheckedIOException.class, () -> finalWriter.writeHeader(metadata));
      assertFalse(writer.isValid());
    } finally {
      if (writer != null) {
        try {
          writer.close();
        } catch (UncheckedIOException expected) {
          // already-detected failure; close() re-detects it, which is expected and not the point of this test
        }
      }
      java.nio.file.Files.deleteIfExists(file);
    }
  }

  @Test
  public void testWriteHeaderDetectsWriteFailure() throws Exception {
    // PrintWriter swallows an IOException from its underlying Writer internally (setting only its own error
    // flag) rather than propagating it -- checkForWriteError() is what surfaces this as an actual exception
    FailingWriter failingWriter = new FailingWriter();
    failingWriter.startFailing();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(failingWriter)).build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").build();
    assertThrows(UncheckedIOException.class, () -> writer.writeHeader(metadata));
  }

  @Test
  public void testWriteHeaderInvalidatesWriterAndBlocksFurtherUse() throws Exception {
    FailingWriter failingWriter = new FailingWriter();
    failingWriter.startFailing();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(failingWriter)).build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").build();

    assertThrows(UncheckedIOException.class, () -> writer.writeHeader(metadata));
    assertFalse(writer.isValid());
    assertThrows(IllegalStateException.class, () -> writer.writeHeader(metadata));
  }

  @Test
  public void testWriteAfterCleanCloseThrowsWithClosedMessage() throws Exception {
    // regression test: a clean (no-error) close() must still permanently invalidate the writer -- writing
    // afterward must throw, not silently no-op through the now-closed underlying PrintWriter
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    writer.close();

    assertFalse(writer.isValid());
    IllegalStateException e = assertThrows(IllegalStateException.class, () -> writer.writeHeader(metadata));
    assertTrue(e.getMessage().contains("closed"));
    assertNull(e.getCause());
  }

  @Test
  public void testWriteLineContentRejectionDoesNotInvalidateWriter() throws Exception {
    // a VcfFormatException content rejection (not a write/IO failure) must not invalidate the writer -- a
    // corrected call afterward must succeed normally
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        null, null, -1);
    VcfSample badSample = LENIENT_CONTEXT.newSample(new ArrayList<>(), new ArrayList<>());
    assertThrows(VcfFormatException.class,
        () -> writer.writeLine(metadata, position, Collections.singletonList(badSample)));
    assertTrue(writer.isValid());
  }

  @Test
  public void testHeaderContentRejectionAllowsCorrectedRetry() throws Exception {
    // a validateBeforeWrite() content rejection during writeHeader() must not invalidate the writer or
    // permanently block retrying -- a corrected call afterward must succeed and actually write the header.
    // (Note: the checkForWriteError()/field-assignment reordering fix in writeHeader() has no test-observable
    // effect through the public API on its own, since checkValid() already blocks any retry after a genuine
    // write failure regardless of m_headerWritten's value -- this test covers the content-rejection-then-retry
    // behavior itself, not that specific internal reordering.)
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .addInfo(new InfoMetadata(LENIENT_CONTEXT, "AF", "d", InfoType.Float, "A", null, null))
        .build();
    metadata.getInfo().get("AF").putPropertyRaw("ID", "MISMATCHED"); // now stored under key "AF" but own ID differs
    assertThrows(VcfFormatException.class, () -> writer.writeHeader(metadata));
    assertTrue(writer.isValid()); // content rejection, not a write failure -- must not invalidate

    metadata.getInfo().get("AF").putPropertyRaw("ID", "AF"); // fix the mismatch
    writer.writeHeader(metadata); // must succeed now, not silently no-op or throw "already called"
    assertTrue(sw.toString().contains("##INFO=<ID=AF"));
  }

  @Test
  public void testValidateBeforeWriteRejectsEntirelyRemovedId() throws Exception {
    // regression test: removing ID entirely (rather than changing it to a different value, the
    // testHeaderContentRejectionAllowsCorrectedRetry scenario above) must be rejected too -- previously the
    // id != null guard in validateKeyedMetadataEntries() let a null id skip the mismatch check, so
    // validateBeforeWrite() silently wrote a spec-invalid "##INFO=<Number=A,...>" declaration with no ID at all
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .addInfo(new InfoMetadata(LENIENT_CONTEXT, "AF", "d", InfoType.Float, "A", null, null))
        .build();
    metadata.getInfo().get("AF").trustedMutableProperties().remove("ID"); // now stored under key "AF" but has no ID at all
    assertThrows(VcfFormatException.class, () -> writer.writeHeader(metadata));
    assertTrue(writer.isValid()); // content rejection, not a write failure -- must not invalidate

    metadata.getInfo().get("AF").putPropertyRaw("ID", "AF"); // fix it
    writer.writeHeader(metadata); // must succeed now
    // re-adding ID after remove() appends it at the end of the property map rather than restoring its original
    // first position, so (unlike testHeaderContentRejectionAllowsCorrectedRetry's putPropertyRaw-only fix above)
    // ID=AF lands at the end of the attribute list here, not the start
    assertTrue(sw.toString().contains("ID=AF>"));
  }

  @Test
  public void testValidateBeforeWriteCatchesMismatchedMetaId() throws Exception {
    // pins the attribute-list-tokenizer phase's Task 5 wiring of validateKeyedMetadataEntries("META", ...) into
    // validateMetadata() -- the same ID-vs-map-key drift check the two tests above already pin for INFO. Without
    // that wiring, this would silently write "##META=<ID=MISMATCHED,...>" under the map key "Assay".
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    Map<String, String> props = new LinkedHashMap<>();
    props.put("ID", "Assay");
    props.put("Type", "String");
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .addMeta(props)
        .build();
    metadata.getMetas().get("Assay").putPropertyRaw("ID", "MISMATCHED"); // stored under key "Assay" but own ID differs
    assertThrows(VcfFormatException.class, () -> writer.writeHeader(metadata));
    assertTrue(writer.isValid()); // content rejection, not a write failure -- must not invalidate

    metadata.getMetas().get("Assay").putPropertyRaw("ID", "Assay"); // fix the mismatch
    writer.writeHeader(metadata); // must succeed now
    assertTrue(sw.toString().contains("##META=<ID=Assay"));
  }

  @Test
  public void testValidateBeforeWriteRejectsNullMetadataEntryInsteadOfThrowingNpe() throws Exception {
    // regression test: a null entry in a metadata collection must produce VcfFormatException, not a raw
    // NullPointerException -- verified for both a Map-based kind (INFO, via validateKeyedMetadataEntries) and
    // the List-based kind (PEDIGREE, via validateMetadata()'s own pedigree loop).
    //
    // validateBeforeWrite() only. This used to run in both modes, because buildHeaderLines() ->
    // addMetadataLines() carried its own null-element guard for the default path; that guard is gone (see
    // addMetadataLines()'s own comment), so the default path now assumes what VcfMetadata's immutability
    // guarantees. validateMetadata() keeps its checks, since diagnostics mode must report what it can detect.
    // Reached through trustedMetadata() (see its Javadoc), the only remaining route to a null element.
    Map<String, InfoMetadata> infoWithNullEntry = new LinkedHashMap<>();
    infoWithNullEntry.put("AF", new InfoMetadata(LENIENT_CONTEXT, "AF", "d", InfoType.Float, "A", null, null));
    infoWithNullEntry.put("BADKEY", null);
    VcfMetadata badInfo = trustedMetadata("VCFv4.2", infoWithNullEntry, null, null);
    List<PedigreeMetadata> pedigreesWithNullEntry = new ArrayList<>();
    pedigreesWithNullEntry.add(null);
    VcfMetadata badPedigree = trustedMetadata("VCFv4.2", null, pedigreesWithNullEntry, null);

    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    VcfFormatException infoEx = assertThrows(VcfFormatException.class, () -> writer.writeHeader(badInfo));
    assertTrue(infoEx.getMessage().contains("INFO metadata contains a null entry"), infoEx::getMessage);

    StringWriter sw2 = new StringWriter();
    VcfWriter writer2 = new VcfWriter.Builder().toWriter(new PrintWriter(sw2)).validateBeforeWrite().build();
    VcfFormatException pedigreeEx = assertThrows(VcfFormatException.class, () -> writer2.writeHeader(badPedigree));
    assertTrue(pedigreeEx.getMessage().contains("PEDIGREE metadata contains a null entry"),
        pedigreeEx::getMessage);
  }

  @Test
  public void testValidateBeforeWriteRejectsNullMetadataKeyInsteadOfThrowingNpe() throws Exception {
    // regression test: unlike the null VALUE case above, LinkedHashMap also tolerates a null KEY, so
    // an INFO map with a null KEY reaches validateKeyedMetadataEntries with mapKey itself null -- that method's
    // own null-VALUE guard doesn't catch it; only the later mapKey.equals(id) check further down does.
    // validateMetadata() (called at the start of writeHeader() in validateBeforeWrite() mode) runs
    // validateKeyedMetadataEntries directly, so this fires on the very first writeHeader() call. Reached via
    // trustedMetadata() (see its Javadoc) now that getInfo() is an unmodifiable view.
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    Map<String, InfoMetadata> infoWithNullKey = new LinkedHashMap<>();
    infoWithNullKey.put("AF", new InfoMetadata(LENIENT_CONTEXT, "AF", "d", InfoType.Float, "A", null, null));
    infoWithNullKey.put(null, new InfoMetadata(LENIENT_CONTEXT, "AF2", "d2", InfoType.Float, "A", null, null));
    VcfMetadata metadata = trustedMetadata("VCFv4.2", infoWithNullKey, null, null);

    VcfFormatException ex = assertThrows(VcfFormatException.class, () -> writer.writeHeader(metadata));
    assertTrue(ex.getMessage().contains("INFO metadata contains a null key"));
  }

  @Test
  public void testCloseDetectsErrorAndStillReleasesTheWriter() throws Exception {
    // close() must check for a write failure that happened after the last periodic check (or after
    // writeHeader(), if no data lines were written at all) AND still release the underlying resource even
    // though it's about to throw
    FailingWriter failingWriter = new FailingWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(failingWriter)).build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata); // succeeds: failingWriter isn't failing yet
    failingWriter.startFailing(); // arm the failure for close()'s own checkError()-triggered flush
    assertThrows(UncheckedIOException.class, writer::close);
    assertTrue(failingWriter.wasClosed());
  }

  @Test
  public void testCloseIsIdempotentAfterFailure() throws Exception {
    // regression test: Closeable.close()'s own documented contract says a second call on an already-closed
    // stream has no effect -- today, a second close() after a failure re-throws a fresh exception each time
    FailingWriter failingWriter = new FailingWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(failingWriter)).build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata); // succeeds: failingWriter isn't failing yet
    failingWriter.startFailing();
    UncheckedIOException firstFailure = assertThrows(UncheckedIOException.class, writer::close);
    writer.close(); // second call: must be a no-op, not throw again

    // repeat close() must never clobber m_invalidatingCause -- confirm the writer stays invalid and any further
    // use still reports the ORIGINAL write failure, not a generic "already closed" message
    assertFalse(writer.isValid());
    IllegalStateException e = assertThrows(IllegalStateException.class, () -> writer.writeHeader(metadata));
    assertTrue(e.getMessage().contains("write failure"));
    assertSame(firstFailure, e.getCause());
  }

  @Test
  public void testPeriodicCheckDetectsErrorDuringWriteLineWithoutClose() throws Exception {
    // writeLine() no longer flushes/checks on every call; this proves the periodic
    // (every-1000-line) check inside printLine() still catches a failure introduced mid-stream, rather than
    // only close() ever detecting it -- and (regression) that this also releases the underlying resource
    // immediately, without the caller ever calling close() themselves
    FailingWriter failingWriter = new FailingWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(failingWriter)).build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    failingWriter.startFailing();
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null,
        null, null, -1);
    assertThrows(UncheckedIOException.class, () -> {
      for (int i = 0; i < 1000; i++) {
        writer.writeLine(metadata, position, Collections.emptyList());
      }
    });
    assertTrue(failingWriter.wasClosed());
    // regression test: a caller's own follow-up close() call, after the mid-stream failure already auto-closed
    // the writer, must be a true no-op -- not a second, distinct exception for the same underlying problem
    writer.close();
  }

  @Test
  public void testWarningsToFileDefaultNameDerivedFromTargetPath(@org.junit.jupiter.api.io.TempDir Path tempDir)
      throws IOException {
    VcfMetadata.Builder mdBuilder = new VcfMetadata.Builder(LENIENT_CONTEXT);
    mdBuilder.setFileFormat("VCFv4.2");
    mdBuilder.setColumns(List.of("#CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO"));
    VcfMetadata metadata = mdBuilder.build();
    // an INFO key with no matching metadata declaration triggers a warning in validateBeforeWrite() mode
    ListMultimap<String, String> info = ArrayListMultimap.create();
    info.put("UNDECLARED", "1");
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), null, null, info, null,
        -1);

    Path vcfFile = tempDir.resolve("out.vcf");
    VcfWriter writer = new VcfWriter.Builder()
        .toFile(vcfFile)
        .validateBeforeWrite()
        .warningsToFile()
        .build();
    writer.writeHeader(metadata);
    writer.writeLine(metadata, position, List.of());
    writer.close();

    Path expectedWarningsFile = tempDir.resolve("out.vcf.warn.log");
    assertTrue(java.nio.file.Files.exists(expectedWarningsFile));
    assertTrue(java.nio.file.Files.readString(expectedWarningsFile).contains("UNDECLARED"));
  }

  @Test
  public void testWarningsToFileWithNoArgAndToWriterThrowsIllegalState() {
    assertThrows(IllegalStateException.class, () ->
        new VcfWriter.Builder()
            .toWriter(new PrintWriter(new StringWriter()))
            .warningsToFile()
            .build());
  }

  @Test
  public void testToFileAfterToWriterThrowsIllegalState(@org.junit.jupiter.api.io.TempDir Path tempDir) {
    // toFile() used to silently override toWriter(), discarding the caller's writer with no error and never
    // writing to or closing it -- now a caller mistake this builder rejects immediately, matching
    // VcfParser.Builder's analogous fromFile()/fromReader() mutual-exclusion guard.
    assertThrows(IllegalStateException.class, () ->
        new VcfWriter.Builder()
            .toWriter(new PrintWriter(new StringWriter()))
            .toFile(tempDir.resolve("out.vcf")));
  }

  @Test
  public void testToWriterAfterToFileThrowsIllegalState(@org.junit.jupiter.api.io.TempDir Path tempDir) {
    assertThrows(IllegalStateException.class, () ->
        new VcfWriter.Builder()
            .toFile(tempDir.resolve("out.vcf"))
            .toWriter(new PrintWriter(new StringWriter())));
  }

  @Test
  public void testWarningsToFileAccumulatesAcrossMultipleWriteLineCalls(@org.junit.jupiter.api.io.TempDir Path tempDir)
      throws IOException {
    VcfMetadata.Builder mdBuilder = new VcfMetadata.Builder(LENIENT_CONTEXT);
    mdBuilder.setFileFormat("VCFv4.2");
    mdBuilder.setColumns(List.of("#CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO"));
    VcfMetadata metadata = mdBuilder.build();

    Path vcfFile = tempDir.resolve("out.vcf");
    VcfWriter writer = new VcfWriter.Builder()
        .toFile(vcfFile)
        .validateBeforeWrite()
        .warningsToFile()
        .build();
    writer.writeHeader(metadata);
    for (int i = 1; i <= 3; i++) {
      ListMultimap<String, String> info = ArrayListMultimap.create();
      info.put("UNDECLARED" + i, "1");
      VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", i, null, "A", List.of("T"), null, null, info, null,
          -1);
      writer.writeLine(metadata, position, List.of());
    }
    writer.close();

    String warnings = java.nio.file.Files.readString(tempDir.resolve("out.vcf.warn.log"));
    assertTrue(warnings.contains("UNDECLARED1"));
    assertTrue(warnings.contains("UNDECLARED2"));
    assertTrue(warnings.contains("UNDECLARED3"));
  }

  @Test
  public void testWarningsToFileSuppressesSlf4jOutput(@org.junit.jupiter.api.io.TempDir Path tempDir)
      throws IOException {
    VcfMetadata.Builder mdBuilder = new VcfMetadata.Builder(LENIENT_CONTEXT);
    mdBuilder.setFileFormat("VCFv4.2");
    mdBuilder.setColumns(List.of("#CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO"));
    VcfMetadata metadata = mdBuilder.build();
    // an INFO key with no matching metadata declaration triggers a warning in validateBeforeWrite() mode
    ListMultimap<String, String> info = ArrayListMultimap.create();
    info.put("UNDECLARED", "1");
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), null, null, info, null,
        -1);

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      Path vcfFile = tempDir.resolve("out.vcf");
      VcfWriter writer = new VcfWriter.Builder()
          .toFile(vcfFile)
          .validateBeforeWrite()
          .warningsToFile()
          .build();
      writer.writeHeader(metadata);
      writer.writeLine(metadata, position, List.of());
      writer.close();
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testBuildClosesPrimaryWriterWhenWarningsFileFailsToOpen(@org.junit.jupiter.api.io.TempDir Path tempDir)
      throws IOException {
    // regression test: build() opens the primary file-backed writer (via toFile()) before attempting to open the
    // warnings file; if opening the warnings file fails, build() must close that already-open writer before
    // propagating the exception -- otherwise no VcfWriter is ever returned for the caller to close(), leaking the
    // file descriptor. A warnings path whose parent directory doesn't exist reliably fails
    // Files.newBufferedWriter() with NoSuchFileException (a subclass of IOException).
    Path vcfFile = tempDir.resolve("out.vcf");
    Path invalidWarningsFile = tempDir.resolve("nonexistent-dir").resolve("warnings.log");

    assertThrows(IOException.class, () ->
        new VcfWriter.Builder()
            .toFile(vcfFile)
            .warningsToFile(invalidWarningsFile)
            .build());

    // the primary file was created (Files.newOutputStream() creates it as part of opening for write)
    assertTrue(java.nio.file.Files.exists(vcfFile));

    // Deleting/renaming an open file succeeds on POSIX regardless of whether a process still holds it open, so
    // that alone can't prove the descriptor wasn't leaked here. Inspecting this process's own open-file table
    // (/proc/self/fd on Linux, which is what this project's CI (build.yml: ubuntu-latest) and this dev
    // environment both run on) can: if build()'s fix didn't close the primary writer, its file descriptor would
    // still show up here, pointing at vcfFile, even though build() already returned (by throwing). Skipped where
    // /proc isn't available (e.g. non-Linux) rather than failing the test on an unrelated platform.
    Path procFd = Path.of("/proc/self/fd");
    if (java.nio.file.Files.isDirectory(procFd)) {
      Path absoluteVcfFile = vcfFile.toAbsolutePath().normalize();
      long openFdCount;
      try (var listing = java.nio.file.Files.list(procFd)) {
        openFdCount = listing
            .filter(fd -> {
              try {
                return java.nio.file.Files.readSymbolicLink(fd).equals(absoluteVcfFile);
              } catch (IOException e) {
                return false; // not a symlink we can resolve (e.g. this same fd being closed concurrently)
              }
            })
            .count();
      }
      assertEquals(0, openFdCount, "primary writer's file descriptor leaked after the warnings file failed to open");
    }
  }

  @Test
  public void testCombinedParseAndWriteWarningsToFileDontInterfere(@org.junit.jupiter.api.io.TempDir Path tempDir)
      throws IOException {
    // a metadata line with no ID triggers a warning during parseMetadata() -- see VcfMetadata.addInfo
    String vcf = "##fileformat=VCFv4.2\n" +
        "##INFO=<Number=1,Type=String,Description=\"d\">\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n";
    Path vcfFile = tempDir.resolve("in.vcf");
    java.nio.file.Files.writeString(vcfFile, vcf);
    Path parserWarnings = tempDir.resolve("parser.warn.log");

    VcfParser parser = new VcfParser.Builder()
        .fromFile(vcfFile)
        .warningsToFile(parserWarnings)
        .parseWith(new MemoryMappedVcfLineParser.Builder().build())
        .build();
    parser.parse();
    parser.close();

    VcfMetadata.Builder mdBuilder = new VcfMetadata.Builder(LENIENT_CONTEXT);
    mdBuilder.setFileFormat("VCFv4.2");
    mdBuilder.setColumns(List.of("#CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO"));
    VcfMetadata writeMetadata = mdBuilder.build();
    // an INFO key with no matching metadata declaration triggers a warning in validateBeforeWrite() mode
    ListMultimap<String, String> info = ArrayListMultimap.create();
    info.put("UNDECLARED", "1");
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), null, null, info, null,
        -1);

    Path outFile = tempDir.resolve("out.vcf");
    Path writerWarnings = tempDir.resolve("writer.warn.log");
    VcfWriter writer = new VcfWriter.Builder()
        .toFile(outFile)
        .validateBeforeWrite()
        .warningsToFile(writerWarnings)
        .build();
    writer.writeHeader(writeMetadata);
    writer.writeLine(writeMetadata, position, List.of());
    writer.close();

    String parserWarningsText = java.nio.file.Files.readString(parserWarnings);
    String writerWarningsText = java.nio.file.Files.readString(writerWarnings);
    assertTrue(parserWarningsText.contains("no ID"));
    assertFalse(parserWarningsText.contains("UNDECLARED"));
    assertTrue(writerWarningsText.contains("UNDECLARED"));
    assertFalse(writerWarningsText.contains("no ID"));
  }

  @Test
  public void testCombinedParseAndWriteToSameWarningsFileTruncatesPriorContent(@org.junit.jupiter.api.io.TempDir Path tempDir)
      throws IOException {
    // documents current, verified behavior: VcfWarnings.openFile() and
    // VcfWriter.Builder.build()'s warnings-file open both call Files.newBufferedWriter(path)
    // with no OpenOption, which per its javadoc defaults to CREATE, TRUNCATE_EXISTING, WRITE.
    // Pointing the writer's warningsToFile() at the same path the parser already wrote to and
    // closed therefore silently discards the parser's prior warnings instead of appending to
    // them. This is not a bug fix in this batch (test-coverage only) -- it's recorded here as
    // real, verified behavior worth flagging as a follow-up finding (nothing currently documents
    // this footgun for a caller who reuses one path for both sides). If this is ever
    // intentionally changed to append (or to reject a reused path), update or delete this test --
    // it pins current behavior, not a required contract.
    String vcf = "##fileformat=VCFv4.2\n" +
        "##INFO=<Number=1,Type=String,Description=\"d\">\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n";
    Path vcfFile = tempDir.resolve("in.vcf");
    java.nio.file.Files.writeString(vcfFile, vcf);
    Path sharedWarnings = tempDir.resolve("shared.warn.log");

    VcfParser parser = new VcfParser.Builder()
        .fromFile(vcfFile)
        .warningsToFile(sharedWarnings)
        .parseWith(new MemoryMappedVcfLineParser.Builder().build())
        .build();
    parser.parse();
    parser.close();
    assertTrue(java.nio.file.Files.readString(sharedWarnings).contains("no ID"));

    VcfMetadata.Builder mdBuilder = new VcfMetadata.Builder(LENIENT_CONTEXT);
    mdBuilder.setFileFormat("VCFv4.2");
    mdBuilder.setColumns(List.of("#CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO"));
    VcfMetadata writeMetadata = mdBuilder.build();
    ListMultimap<String, String> info = ArrayListMultimap.create();
    info.put("UNDECLARED", "1");
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), null, null, info, null,
        -1);

    Path outFile = tempDir.resolve("out.vcf");
    VcfWriter writer = new VcfWriter.Builder()
        .toFile(outFile)
        .validateBeforeWrite()
        .warningsToFile(sharedWarnings)
        .build();
    writer.writeHeader(writeMetadata);
    writer.writeLine(writeMetadata, position, List.of());
    writer.close();

    String finalWarnings = java.nio.file.Files.readString(sharedWarnings);
    assertTrue(finalWarnings.contains("UNDECLARED"));
    assertFalse(finalWarnings.contains("no ID"));
  }

  @Test
  public void testWriteHeaderCalledTwiceThrowsInDefaultMode() throws Exception {
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    VcfFormatException e = assertThrows(VcfFormatException.class, () -> writer.writeHeader(metadata));
    assertTrue(e.getMessage().contains("writeHeader() was already called"));
  }

  @Test
  public void testWriteLineBeforeWriteHeaderThrowsInDefaultMode() throws Exception {
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").build();
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, "A", VcfFloat.of(new BigDecimal("0")), -1);
    VcfFormatException e = assertThrows(VcfFormatException.class,
        () -> writer.writeLine(metadata, position, Collections.emptyList()));
    assertTrue(e.getMessage().contains("writeLine() was called before writeHeader()"));
  }

  @Test
  public void testWriteLineWithDifferentMetadataThrowsInDefaultMode() throws Exception {
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata headerMetadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").build();
    VcfMetadata differentMetadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").build();
    writer.writeHeader(headerMetadata);
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, "A", VcfFloat.of(new BigDecimal("0")), -1);
    VcfFormatException e = assertThrows(VcfFormatException.class,
        () -> writer.writeLine(differentMetadata, position, Collections.emptyList()));
    assertTrue(e.getMessage().contains("writeLine() was given different metadata than writeHeader() used"));
  }

  // ---- writeLine: metadata, position and every sample must share one VcfContext (Task 2) ----
  //
  // The invariant is a transitive three-way equality (metadata.getContext() == position.getContext() == every
  // sample's context), and writeLine() already pins metadata to the header instance (see
  // testWriteLineWithDifferentMetadataThrowsInDefaultMode above), so "metadata vs position", "metadata vs
  // samples" and "position vs samples" are not three constructible inputs -- a foreign position is simultaneously
  // a metadata-vs-position and a position-vs-samples mismatch, and a foreign sample is simultaneously
  // metadata-vs-samples and position-vs-samples. Two independent mismatch shapes (foreign position, foreign
  // sample) plus a same-context control prove the full predicate by transitivity, matching the precedent
  // VcfContext.newRecord's own tests set (VcfContextTest.testRecordRejectsComponentsFromDifferentContexts).

  @Test
  public void testWriteLineRejectsPositionFromDifferentContextInDefaultMode() throws Exception {
    // The two contexts are deliberately equal in value and differ only by identity, so this fails if the
    // implementation ever compares context values instead of references (the same trap
    // VcfContextTest.testRecordRejectsComponentsFromDifferentContexts guards for VcfContext.newRecord).
    VcfContext ctxA = VcfContext.lenient("VCFv4.2", new VcfWarnings(new StringWriter()));
    VcfContext ctxB = VcfContext.lenient("VCFv4.2", new VcfWarnings(new StringWriter()));
    VcfMetadata metadata = new VcfMetadata.Builder(ctxA).setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .build();
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    writer.writeHeader(metadata);
    VcfPosition position = ctxB.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null, null,
        List.of("GT"), -1);
    VcfSample sample = ctxA.newSample(new LinkedHashMap<>(Map.of("GT", "0/1")));
    VcfFormatException e = assertThrows(VcfFormatException.class,
        () -> writer.writeLine(metadata, position, Collections.singletonList(sample)));
    assertTrue(e.getMessage().contains("different VcfContext"));
  }

  @Test
  public void testWriteLineRejectsPositionFromDifferentContextWhenValidateBeforeWrite() throws Exception {
    VcfContext ctxA = VcfContext.lenient("VCFv4.2", new VcfWarnings(new StringWriter()));
    VcfContext ctxB = VcfContext.lenient("VCFv4.2", new VcfWarnings(new StringWriter()));
    VcfMetadata metadata = new VcfMetadata.Builder(ctxA).setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .build();
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    writer.writeHeader(metadata);
    VcfPosition position = ctxB.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null, null,
        List.of("GT"), -1);
    VcfSample sample = ctxA.newSample(new LinkedHashMap<>(Map.of("GT", "0/1")));
    VcfFormatException e = assertThrows(VcfFormatException.class,
        () -> writer.writeLine(metadata, position, Collections.singletonList(sample)));
    assertTrue(e.getMessage().contains("different VcfContext"));
  }

  @Test
  public void testWriteLineRejectsSampleFromDifferentContextAtIndex1InDefaultMode() throws Exception {
    // A one-sample case would be satisfied by an implementation that only checks samples.get(0); this uses two
    // samples, with the second one foreign, so the check must actually iterate. The message must name index 1.
    VcfContext ctxA = VcfContext.lenient("VCFv4.2", new VcfWarnings(new StringWriter()));
    VcfContext ctxB = VcfContext.lenient("VCFv4.2", new VcfWarnings(new StringWriter()));
    VcfMetadata metadata = new VcfMetadata.Builder(ctxA).setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1", "S2"))
        .build();
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    writer.writeHeader(metadata);
    VcfPosition position = ctxA.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null, null,
        List.of("GT"), -1);
    VcfSample sample0 = ctxA.newSample(new LinkedHashMap<>(Map.of("GT", "0/1")));
    VcfSample sample1 = ctxB.newSample(new LinkedHashMap<>(Map.of("GT", "1/1")));
    VcfFormatException e = assertThrows(VcfFormatException.class,
        () -> writer.writeLine(metadata, position, Arrays.asList(sample0, sample1)));
    assertTrue(e.getMessage().contains("Sample #1"));
    assertTrue(e.getMessage().contains("different VcfContext"));
  }

  @Test
  public void testWriteLineRejectsSampleFromDifferentContextAtIndex1WhenValidateBeforeWrite() throws Exception {
    VcfContext ctxA = VcfContext.lenient("VCFv4.2", new VcfWarnings(new StringWriter()));
    VcfContext ctxB = VcfContext.lenient("VCFv4.2", new VcfWarnings(new StringWriter()));
    VcfMetadata metadata = new VcfMetadata.Builder(ctxA).setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1", "S2"))
        .build();
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    writer.writeHeader(metadata);
    VcfPosition position = ctxA.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null, null,
        List.of("GT"), -1);
    VcfSample sample0 = ctxA.newSample(new LinkedHashMap<>(Map.of("GT", "0/1")));
    VcfSample sample1 = ctxB.newSample(new LinkedHashMap<>(Map.of("GT", "1/1")));
    VcfFormatException e = assertThrows(VcfFormatException.class,
        () -> writer.writeLine(metadata, position, Arrays.asList(sample0, sample1)));
    assertTrue(e.getMessage().contains("Sample #1"));
    assertTrue(e.getMessage().contains("different VcfContext"));
  }

  @Test
  public void testWriteLineAcceptsPositionAndSamplesFromSameContext() throws Exception {
    // Same-context two-sample control: proves the two tests above fail because of the context mismatch, not
    // because a two-sample writeLine() call throws for some unrelated reason.
    VcfContext ctxA = VcfContext.lenient("VCFv4.2", new VcfWarnings(new StringWriter()));
    VcfMetadata metadata = new VcfMetadata.Builder(ctxA).setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1", "S2"))
        .build();
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    writer.writeHeader(metadata);
    VcfPosition position = ctxA.newPosition("chr1", 1, null, "A", List.of("T"), VcfFloat.of(new BigDecimal("0")), null, null,
        List.of("GT"), -1);
    VcfSample sample0 = ctxA.newSample(new LinkedHashMap<>(Map.of("GT", "0/1")));
    VcfSample sample1 = ctxA.newSample(new LinkedHashMap<>(Map.of("GT", "1/1")));
    writer.writeLine(metadata, position, Arrays.asList(sample0, sample1));
    assertTrue(sw.toString().contains("0/1\t1/1"));
  }

  // testWriteLineDoesNotDetectInPlaceMetadataMutationInDefaultMode used to sit here, pinning the boundary that
  // in-place metadata-mutation detection was validateBeforeWrite()-only (it cost a full header rebuild per
  // record). That boundary no longer exists -- the check is gone from both modes because VcfMetadata is
  // immutable. See testMetadataCannotBeMutatedAfterHeaderWritten, which replaces both tests.

  @Test
  public void testWarningsToFileDoesNotCaptureLazyQualityGetterUnderADifferentContext(
      @org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
    // Task 7: setWarningsSink was removed, so a model's warnings destination is fixed at construction from its
    // own VcfContext and is never re-pointed by a later VcfWriter. `position` is built under LENIENT_CONTEXT
    // (VcfWarnings.DEFAULT, the shared SLF4J logger), so the lazy QUAL parse warning writeLine() triggers goes
    // there -- not to this writer's own warningsToFile() destination, even though the writer's own generated
    // diagnostics (e.g. an undeclared INFO key) still land there.
    //
    // validateBeforeWrite() is required to observe this at all: the default write path prefers getRawQuality()
    // whenever it exists (every parsed record has one) and no longer calls getQuality() in that case -- a
    // deliberate delayed-validation choice (see VcfWriter's own QUAL-block comment), matching how INFO/FORMAT
    // reserved-key grammar mismatches also only surface via validateBeforeWrite() or a caller's own later typed
    // access, never merely by writing the file. "5." (not "notanumber"): getQuality() throws unconditionally
    // for a value it cannot parse as a number at all (see its own Javadoc), so this needs a QUAL that still
    // warns-and-parses instead -- BigDecimal accepts "5." but it doesn't match VCFv4.3's Float grammar (a
    // trailing decimal point with no digits after it), which VcfFloat.parse()/parseDecimal() already warns
    // about without failing the parse (see VcfPositionTest.testQualityWarnsOnGrammarMismatchButStillParses).
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").setColumns(List.of("#CHROM", "POS",
        "ID", "REF", "ALT", "QUAL", "FILTER", "INFO")).build();
    VcfPosition position = LENIENT_CONTEXT.newPosition("1", 100, "A", null, -1).toBuilder()
        .rawQuality("5.") // never accessed yet -- writeLine() must be what fires the lazy parse
        .build();
    Path outFile = tempDir.resolve("out.vcf");
    Path warningsFile = tempDir.resolve("warnings.log");

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      try (VcfWriter writer = new VcfWriter.Builder().toFile(outFile).warningsToFile(warningsFile)
          .validateBeforeWrite().build()) {
        writer.writeHeader(metadata);
        writer.writeLine(metadata, position, List.of());
      }
      assertFalse(java.nio.file.Files.readString(warningsFile).contains("does not match the VCF Float grammar"));
      assertTrue(appender.anyMessageContains("does not match the VCF Float grammar"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testWarningsToFileDoesNotCaptureLazyDuplicateFormatKeyWarningUnderADifferentContext(
      @org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
    // Task 7: same attribution rule as the QUAL case above, for a sample's own lazy duplicate-FORMAT-key
    // warning. `sample` is built under LENIENT_CONTEXT (VcfWarnings.DEFAULT), so this warning also goes to the
    // shared SLF4J logger, not this writer's own warningsToFile() destination.
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .addFormat(new FormatMetadata(LENIENT_CONTEXT, "DP", "depth", "1", FormatType.Integer))
        .setColumns(List.of("#CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .build();
    VcfPosition position = LENIENT_CONTEXT.newPosition("1", 100, null, "A", null, null, null, null, List.of("DP"), -1);
    VcfSample sample = LENIENT_CONTEXT.newSample(List.of("DP", "DP"),
        List.of("1", "2")); // duplicate key, never accessed yet
    Path outFile = tempDir.resolve("out.vcf");
    Path warningsFile = tempDir.resolve("warnings.log");

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      try (VcfWriter writer = new VcfWriter.Builder().toFile(outFile).warningsToFile(warningsFile).build()) {
        writer.writeHeader(metadata);
        writer.writeLine(metadata, position, List.of(sample));
      }
      assertFalse(java.nio.file.Files.readString(warningsFile).contains("duplicate FORMAT key"));
      assertTrue(appender.anyMessageContains("duplicate FORMAT key"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testWarningsToFileDoesNotCaptureMetadataDeclarationWarningDuringWriteHeaderValidation(
      @org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
    // Task 7: writeHeader()'s validateBeforeWrite() validation still calls each declaration's own validate(),
    // but -- setWarningsSink having been removed -- that call now warns to the declaration's own fixed context
    // (here LENIENT_CONTEXT's VcfWarnings.DEFAULT, the shared SLF4J logger), never to this writer's own
    // warningsToFile() destination. Build a valid ContigMetadata via the strict constructor, then mutate its
    // raw URL property directly (bypassing the constructor's own throw-on-malformed-URL check, the same way a
    // caller mutating a live view always can), so the warning is only reachable via validateBeforeWrite()'s
    // entry.validate() call inside writeHeader() -- not at construction time.
    org.pharmgkb.parser.vcf.ContigMetadata contig =
        new org.pharmgkb.parser.vcf.ContigMetadata(LENIENT_CONTEXT, "1", 1000L, "GRCh38", null,
            null, null, null);
    contig.trustedMutableProperties().put(org.pharmgkb.parser.vcf.ContigMetadata.URL, "not a url");
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .addContig(contig)
        .setColumns(List.of("#CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO"))
        .build();
    Path outFile = tempDir.resolve("out.vcf");
    Path warningsFile = tempDir.resolve("warnings.log");

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      try (VcfWriter writer = new VcfWriter.Builder().toFile(outFile).warningsToFile(warningsFile)
          .validateBeforeWrite().build()) {
        writer.writeHeader(metadata);
      }
      assertFalse(java.nio.file.Files.readString(warningsFile).contains("is malformed"));
      assertTrue(appender.anyMessageContains("is malformed"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testDefaultWriteHeaderDoesNotClaimMetadataDeclarations(
      @org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
    // Task 7: writeHeader() used to unconditionally re-point every metadata declaration's warning sink at this
    // writer -- even in default, non-validateBeforeWrite() mode -- via setWarningsSink, so a LATER manual
    // validate() call on the same object (after writeHeader() already returned) would still land in this
    // writer's own destination. setWarningsSink has been removed: a declaration's own warnings destination is
    // fixed at construction, and writeHeader() has no effect on it in either mode.
    org.pharmgkb.parser.vcf.ContigMetadata contig =
        new org.pharmgkb.parser.vcf.ContigMetadata(LENIENT_CONTEXT, "1", 1000L, "GRCh38", null,
            null, null, null);
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .addContig(contig)
        .setColumns(List.of("#CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO"))
        .build();
    Path outFile = tempDir.resolve("out.vcf");
    Path warningsFile = tempDir.resolve("warnings.log");

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      try (VcfWriter writer = new VcfWriter.Builder().toFile(outFile).warningsToFile(warningsFile).build()) {
        writer.writeHeader(metadata); // default mode -- no validateBeforeWrite()
        contig.trustedMutableProperties().put(org.pharmgkb.parser.vcf.ContigMetadata.URL, "not a url");
        contig.validate();
      }
      assertFalse(java.nio.file.Files.readString(warningsFile).contains("is malformed"));
      assertTrue(appender.anyMessageContains("is malformed"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testSecondWriterDoesNotClaimAnAlreadyBuiltObjectEither(
      @org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
    // Task 7: previously, handing the SAME ContigMetadata to two different VcfWriters in sequence re-pointed
    // its warning sink at each one in turn via setWarningsSink, so a warning fired between the two writeHeader()
    // calls went to the first writer's destination and one fired after the second call went to the second's.
    // setWarningsSink has been removed, so neither writeHeader() call has any effect on the contig's own fixed
    // destination (LENIENT_CONTEXT's VcfWarnings.DEFAULT, the shared SLF4J logger) -- both warnings land there,
    // and neither writer's own warningsToFile() destination ever sees either one.
    org.pharmgkb.parser.vcf.ContigMetadata contig =
        new org.pharmgkb.parser.vcf.ContigMetadata(LENIENT_CONTEXT, "1", 1000L, "GRCh38", null,
            null, null, null);
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .addContig(contig)
        .setColumns(List.of("#CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO"))
        .build();
    Path firstWarningsFile = tempDir.resolve("first-warnings.log");
    Path secondWarningsFile = tempDir.resolve("second-warnings.log");

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      try (VcfWriter firstWriter = new VcfWriter.Builder().toFile(tempDir.resolve("first.vcf"))
          .warningsToFile(firstWarningsFile).build()) {
        firstWriter.writeHeader(metadata);
        contig.trustedMutableProperties().put(org.pharmgkb.parser.vcf.ContigMetadata.URL, "not a url");
        contig.validate(); // warning #1 -> the contig's own fixed destination, not firstWarningsFile

        try (VcfWriter secondWriter = new VcfWriter.Builder().toFile(tempDir.resolve("second.vcf"))
            .warningsToFile(secondWarningsFile).build()) {
          secondWriter.writeHeader(metadata);
          contig.validate(); // warning #2 -> the same fixed destination, not secondWarningsFile
        }
      }
      assertEquals(0, countMalformedUrlWarnings(firstWarningsFile),
          "the first writer's destination must not have received a warning that was never routed to it");
      assertEquals(0, countMalformedUrlWarnings(secondWarningsFile),
          "the second writer's destination must not have received a warning that was never routed to it");
      assertEquals(2, appender.countMessagesContaining("is malformed"),
          "both warnings must have reached the contig's own fixed destination instead");
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  private static long countMalformedUrlWarnings(Path warningsFile) throws IOException {
    return java.nio.file.Files.readAllLines(warningsFile).stream()
        .filter(line -> line.contains("is malformed"))
        .count();
  }

  // ---- Builder.targetFileFormat(): the declared version the writer emits ----

  private static VcfMetadata metadataDeclaring(String fileFormat) {
    return new VcfMetadata.Builder(VcfContext.lenient(fileFormat, VcfWarnings.DEFAULT))
        .setColumns(List.of("#CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO"))
        .build();
  }

  @Test
  public void testDefaultInheritsDeclarationFromMetadata() throws Exception {
    StringWriter out = new StringWriter();
    try (VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(out)).build()) {
      writer.writeHeader(metadataDeclaring("VCFv4.1"));
    }
    assertTrue(out.toString().startsWith("##fileformat=VCFv4.1\n"), out::toString);
  }

  @Test
  public void testMatchingTargetOverrideIsAccepted() throws Exception {
    StringWriter out = new StringWriter();
    try (VcfWriter writer = new VcfWriter.Builder().targetFileFormat("VCFv4.2")
        .toWriter(new PrintWriter(out)).build()) {
      writer.writeHeader(metadataDeclaring("VCFv4.2"));
    }
    assertTrue(out.toString().startsWith("##fileformat=VCFv4.2\n"), out::toString);
  }

  @Test
  public void testMalformedTargetThrows() throws Exception {
    try (VcfWriter writer = new VcfWriter.Builder().targetFileFormat("banana")
        .toWriter(new PrintWriter(new StringWriter())).build()) {
      assertThrows(VcfFormatException.class, () -> writer.writeHeader(metadataDeclaring("VCFv4.2")));
    }
  }

  @Test
  public void testTargetFileFormatRejectsNullInsteadOfThrowingNpe() {
    // targetFileFormat is VCF content written verbatim into the output, not a caller-configuration object
    assertThrows(VcfFormatException.class, () -> new VcfWriter.Builder().targetFileFormat(null));
  }

  @Test
  public void testCrossVersionMismatchIsRejectedBeforeAnyBytesAreWritten() throws Exception {
    // the policy is about not EMITTING a declaration the data doesn't conform to, so the rejection has to
    // happen before output begins, not after a partial header
    StringWriter out = new StringWriter();
    try (VcfWriter writer = new VcfWriter.Builder().targetFileFormat("VCFv4.3")
        .toWriter(new PrintWriter(out)).build()) {
      assertThrows(VcfFormatException.class, () -> writer.writeHeader(metadataDeclaring("VCFv4.2")));
      assertEquals("", out.toString(), "nothing may be emitted before the mismatch is rejected");
    }
  }

  @Test
  public void testNonCanonicalTargetMatchesAndKeepsItsOwnSpelling() throws Exception {
    // guards against regressing to raw-string comparison: 4.02 and 4.2 are the same version, so this must NOT
    // be treated as a cross-version write -- and the requested spelling is what gets emitted
    StringWriter out = new StringWriter();
    try (VcfWriter writer = new VcfWriter.Builder().targetFileFormat("VCFv4.02")
        .toWriter(new PrintWriter(out)).build()) {
      writer.writeHeader(metadataDeclaring("VCFv4.2"));
    }
    assertTrue(out.toString().contains("##fileformat=VCFv4.02"), out::toString);
  }

  @Test
  public void testDistinctUnimplementedTargetsStillMismatch() throws Exception {
    // guards against regressing to resolved-version comparison: 4.7 and 4.9 BOTH fall back to 4.3, so
    // comparing resolved versions would wrongly accept this as a match
    try (VcfWriter writer = new VcfWriter.Builder().targetFileFormat("VCFv4.7")
        .toWriter(new PrintWriter(new StringWriter())).build()) {
      assertThrows(VcfFormatException.class, () -> writer.writeHeader(metadataDeclaring("VCFv4.9")));
    }
  }

  @Test
  public void testRetryAfterRejectedHeaderEmitsTheRetriedMetadatasFileFormat() throws Exception {
    // regression test: writeHeader() used to build its VcfContext once and cache it across attempts. A
    // validateBeforeWrite() content rejection does not invalidate the writer (only an IO failure does), so a
    // corrected retry with DIFFERENT metadata is supported -- and the cached context then made the header
    // declare the rejected metadata's version over the accepted metadata's content. That is exactly what the
    // targetFileFormat cross-version check exists to prevent, reached around it: that check only compares an
    // explicit target, which is null here.
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();

    VcfContext ctx41 = VcfContext.lenient("VCFv4.1", VcfWarnings.DEFAULT);
    VcfMetadata rejected = new VcfMetadata.Builder(ctx41)
        .addInfo("AF", "d", InfoType.Float, "A", null, null)
        .build();
    rejected.getInfo().get("AF").putPropertyRaw("ID", "MISMATCHED"); // stored under "AF" but declares another ID
    assertThrows(VcfFormatException.class, () -> writer.writeHeader(rejected));
    assertTrue(writer.isValid());
    assertEquals("", sw.toString(), "nothing may be emitted by the rejected attempt");

    VcfMetadata accepted = new VcfMetadata.Builder(VcfContext.lenient("VCFv4.3", VcfWarnings.DEFAULT)).build();
    writer.writeHeader(accepted);
    assertTrue(sw.toString().startsWith("##fileformat=VCFv4.3\n"), sw::toString);
    assertFalse(sw.toString().contains("VCFv4.1"), sw::toString);
  }

  @Test
  public void testWriteHeaderDerivesFileFormatFromTheMetadataNotItsContext() throws Exception {
    // regression test for the same root cause as above, on the other side: buildHeaderLines() must derive
    // ##fileformat from the metadata argument, not read it back off the fixed VcfContext. Sourcing it from the
    // context would make writeLine()'s recomputed header text identical no matter what the metadata's own
    // declaration said, silently blinding the header-mutation snapshot.
    //
    // This used to be pinned by re-spelling the version through VcfMetadata.setFileFormat() after writeHeader()
    // and asserting writeLine() then rejected the drift. Freezing the metadata layer removed setFileFormat()
    // and made m_fileFormat final, so that drift is now structurally impossible -- which is the stronger
    // guarantee, but leaves the underlying "read it off the metadata, not the context" invariant untested.
    // Pin it directly instead: "VCFv4.02" and "VCFv4.2" are the same version (so they agree with the context,
    // which compares canonicalized) but different bytes, so the emitted line proves which source was used.
    StringWriter sw = new StringWriter();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.02")
        .setColumns(List.of("#CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO"))
        .build();
    assertEquals("VCFv4.2", LENIENT_CONTEXT.getDeclaredFileFormat());
    try (VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build()) {
      writer.writeHeader(metadata);
      assertTrue(sw.toString().startsWith("##fileformat=VCFv4.02\n"), sw::toString);
      // and a data line still writes cleanly: the recomputed header text matches the snapshot, so the
      // header-drift guard does not misfire on a spelling the metadata itself has held all along
      VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 100, "A", null, -1);
      writer.writeLine(metadata, position, List.of());
    }
  }

  /**
   * Builds bare metadata declaring {@code fileFormat} without letting the parse-side fallback warning
   * {@link VcfContext#lenient} emits reach the shared SLF4J logger -- these declarations are deliberately ones
   * this library does not implement, and the writer-side counts below must not be confused with, or polluted by,
   * the model context's own diagnostics.
   */
  private static VcfMetadata unimplementedMetadataDeclaring(String fileFormat) {
    return new VcfMetadata.Builder(VcfContext.lenient(fileFormat, new VcfWarnings(new StringWriter())))
        .setColumns(List.of("#CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO"))
        .build();
  }

  /**
   * @return one entry per warning the writer emitted ({@link VcfWarnings#warn} writes each on its own line),
   * empty for a writer that emitted none. Only meaningful once the writer has been closed -- {@code
   * warningsToFile} opens a buffered writer, so an earlier read can report zero for a warning still in the buffer.
   */
  private static List<String> warningLines(Path warningsFile) throws IOException {
    return Files.readAllLines(warningsFile);
  }

  /**
   * The three declarations that reach each of {@code VcfVersion.resolveWithoutWarning}'s distinct fallback
   * routes, paired with the version each resolves to. {@code VCFv4.0} is the one that falls <em>up</em>, and is
   * the reason the diagnostic names the resolved version rather than hardcoding 4.3.
   */
  private static final Map<String, String> UNIMPLEMENTED_DECLARATIONS =
      Map.of("VCFv4.0", "4.1", "VCFv4.5", "4.3", "VCFv4.7", "4.3");

  @Test
  public void testReportsAnUnimplementedDeclarationExactlyOnce(@TempDir Path tempDir) throws Exception {
    // The report is unconditional now -- a correctness caveat about output that exists, not a
    // validateBeforeWrite() diagnostic a caller opts into -- so both modes must name both the declaration that
    // was emitted and the version whose rules were actually applied. VCFv4.0 falls UP to 4.1, so a hardcoded
    // "4.3" would be wrong for it -- that is the case this loop exists to cover.
    for (Map.Entry<String, String> declaration : UNIMPLEMENTED_DECLARATIONS.entrySet()) {
      String declared = declaration.getKey();
      String resolved = declaration.getValue();
      for (boolean validate : new boolean[] {false, true}) {
        Path warningsFile = tempDir.resolve("mode-" + validate + "-" + declared + ".txt");
        StringWriter sw = new StringWriter();
        VcfWriter.Builder builder = new VcfWriter.Builder().toWriter(new PrintWriter(sw))
            .warningsToFile(warningsFile);
        if (validate) {
          builder.validateBeforeWrite();
        }
        try (VcfWriter writer = builder.build()) {
          writer.writeHeader(unimplementedMetadataDeclaring(declared));
        }
        // the declaration itself still round-trips verbatim -- the fallback governs interpretation, not output
        assertTrue(sw.toString().startsWith("##fileformat=" + declared + "\n"), sw::toString);
        List<String> warnings = warningLines(warningsFile);
        assertEquals(1, warnings.size(),
            () -> "validateBeforeWrite=" + validate + " " + declared + " warned " + warnings.size() +
                " times: " + warnings);
        assertThat(warnings.get(0), containsString(declared));
        assertThat(warnings.get(0), containsString("VCF " + resolved));
        // the old wording claimed the writer was "interpreting" the declaration; it emits it verbatim instead
        assertThat(warnings.get(0), containsString("not guaranteed"));
      }
    }
  }

  @Test
  public void testImplementedDeclarationIsNeverReportedInEitherMode(@TempDir Path tempDir) throws Exception {
    // the diagnostic is about a fallback, not about writing at all: an implemented declaration resolves to
    // itself, so neither mode may say anything
    for (String declared : new String[] {"VCFv4.1", "VCFv4.2", "VCFv4.3"}) {
      for (boolean validate : new boolean[] {false, true}) {
        Path warningsFile = tempDir.resolve("implemented-" + declared + "-" + validate + ".txt");
        VcfWriter.Builder builder = new VcfWriter.Builder().toWriter(new PrintWriter(new StringWriter()))
            .warningsToFile(warningsFile);
        if (validate) {
          builder.validateBeforeWrite();
        }
        try (VcfWriter writer = builder.build()) {
          writer.writeHeader(metadataDeclaring(declared));
        }
        assertEquals(List.of(), warningLines(warningsFile),
            declared + " (validateBeforeWrite=" + validate + ") must not warn");
      }
    }
  }

  @Test
  public void testRejectedHeaderAttemptDoesNotReportTheUnimplementedDeclaration(@TempDir Path tempDir)
      throws Exception {
    // The warn-per-retry half of this fix, stated as counts. The diagnostic is emitted only after every
    // rejection in writeHeader() has been passed and immediately before the first header byte, so a rejected
    // attempt (which writes nothing, and therefore has no "output" for the message to be true of) contributes
    // zero. The corrected retry that actually writes a VCFv4.5 header contributes exactly one, for a total of
    // one across the two-attempt sequence -- not one per attempt.
    for (boolean validate : new boolean[] {false, true}) {
      Path warningsFile = tempDir.resolve("retry-" + validate + ".txt");
      StringWriter sw = new StringWriter();
      VcfWriter.Builder builder = new VcfWriter.Builder().toWriter(new PrintWriter(sw))
          .warningsToFile(warningsFile);
      if (validate) {
        builder.validateBeforeWrite();
      }
      VcfWriter writer = builder.build();

      // attempt 1: VCFv4.5 metadata carrying a lone "\r" in a property, injected through the same trusted raw
      // view testWriteHeaderRejectsLoneCarriageReturnInjectedThroughRawView uses. Rejected in both modes, and --
      // unlike the targetFileFormat cross-version check -- rejected AFTER writeHeader() has built its context and
      // resolved the version, which is what makes this a real test of where the diagnostic fires. Nothing is
      // written, so nothing may be reported.
      VcfContext badContext = VcfContext.lenient("VCFv4.5", new VcfWarnings(new StringWriter()));
      InfoMetadata badInfo = new InfoMetadata(badContext, "NS", "d", InfoType.Integer, "1", null, null);
      badInfo.trustedMutableProperties().put("Description", "bad\rvalue");
      VcfMetadata rejected = new VcfMetadata.Builder(badContext)
          .setColumns(List.of("#CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO"))
          .addInfo(badInfo)
          .build();
      assertThrows(VcfFormatException.class, () -> writer.writeHeader(rejected));
      assertTrue(writer.isValid());
      assertEquals("", sw.toString(), "nothing may be emitted by the rejected attempt");

      // attempt 2: corrected, and actually written
      writer.writeHeader(unimplementedMetadataDeclaring("VCFv4.5"));
      writer.close();
      assertTrue(sw.toString().startsWith("##fileformat=VCFv4.5\n"), sw::toString);
      // The warnings file is only read after close(), never between the two attempts: warningsToFile() opens a
      // BufferedWriter, so a mid-sequence read could report zero simply because the line was still buffered and
      // would pass even against the pre-fix warn-per-attempt behavior. The total below is unambiguous instead --
      // testReportsAnUnimplementedDeclarationExactlyOnce pins that a single written VCFv4.5
      // header contributes exactly 1, so a total of 1 here is what proves the rejected attempt contributed 0.
      List<String> warnings = warningLines(warningsFile);
      assertEquals(1, warnings.size(),
          () -> "validateBeforeWrite=" + validate + " produced " + warnings.size() + " warning(s): " + warnings);
    }
  }

  @Test
  public void testValidateBeforeWriteRejectsWhitespaceOtherThanSpaceInInfoValue() throws Exception {
    // The default write path appends an INFO value verbatim (see VcfWriter.addInfoOrDot), so a tab would add a
    // column and an LF/CR would end the record. Ordinary public construction already rejects these five, so
    // this builds through the package-private newPositionCopyingTrustedFields() factory (reachable here only
    // because this test class shares VcfPosition/VcfContext's package) exactly as testDefaultWriteDropsEmptyIdEntry
    // does. validateBeforeWrite() calls position.validate(), which re-runs checkInfoEntries -- this pins that
    // full-output diagnostics mode rejects rather than emitting a structurally invalid record.
    for (char c : new char[] { '\t', '\n', '\u000B', '\f', '\r' }) {
      String label = String.format("U+%04X", (int) c);
      ListMultimap<String, String> info = ArrayListMultimap.create();
      info.put("AA", "a" + c + "b");
      VcfPosition position = LENIENT_CONTEXT.newPositionCopyingTrustedFields("chr1", 1, null, "A", null,
          VcfFloat.of(new BigDecimal("0")), null, info, null, -1, null, null);

      StringWriter sw = new StringWriter();
      VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
      VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
          .addInfo("AA", "d", InfoType.String, "1", null, null).build();
      writer.writeHeader(metadata);
      VcfFormatException ex = assertThrows(VcfFormatException.class,
          () -> writer.writeLine(metadata, position, Collections.emptyList()), label);
      assertTrue(ex.getMessage().contains("contains whitespace other than a space"), label);
    }
  }

  @Test
  public void testInfoValueWithSpaceRoundTripsAsOneField() throws Exception {
    // The accepted half of the U+0020 relaxation, end to end: a space must survive the writer and come back
    // from the parser as ONE INFO field whose value still contains it -- not two fields, and not a truncated
    // value. Written under a VCFv4.2 declaration on purpose: VCFv4.2.tex line 186 forbids the space and
    // VCFv4.3.tex line 366 allows it, and this library applies the later reading unconditionally.
    StringWriter sw = new StringWriter();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .addInfo("AA", "d", InfoType.String, "1", null, null).build();
    ListMultimap<String, String> info = ArrayListMultimap.create();
    info.put("AA", "a b");
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", null, VcfFloat.of(new BigDecimal("0")), null,
        info, null, -1);
    try (VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build()) {
      writer.writeHeader(metadata);
      writer.writeLine(metadata, position, Collections.emptyList());
    }
    assertThat(sw.toString(), containsString("\tAA=a b\n"));

    List<String> reparsed = new ArrayList<>();
    try (BufferedReader reader = new BufferedReader(new StringReader(sw.toString()));
         VcfParser parser = new VcfParser.Builder()
             .fromReader(reader)
             .parseWith((md, record) -> {
               assertEquals(Collections.singleton("AA"), record.getPosition().getInfoKeys());
               reparsed.addAll(record.getPosition().getInfo().get("AA"));
             })
             .build()) {
      parser.parse();
    }
    assertEquals(Collections.singletonList("a b"), reparsed);
  }

  // --- Quoted-Number regression tests. These pin that a declaration written Number="1"/Number="G" behaves
  // identically to its unquoted spelling on both write paths. InfoMetadata.getNumber() and
  // FormatMetadata.getNumber() return the unquoted value (quoting is an encoding, not part of the value --
  // see VALIDATION_PHILOSOPHY.md's value/encoding rule), so every Number comparison the writer makes now sees
  // the same string for both spellings. They did not before: getNumber() returned the raw "\"1\"", which
  // either failed a comparison it should have passed or failed a parse outright, silently suppressing the
  // diagnostic each test below asserts. That history is why each test names its specific comparison site.

  @Test
  public void testQuotedInfoNumberStillRunsTheOrdinaryCardinalityCheck() throws Exception {
    // validateBeforeWrite() ON: validateInfo/validateSamples run only inside if (m_validateBeforeWrite)
    // (VcfWriter.java:332, :340-341). On the default path this check is never reached at all. Non-reserved key
    // with an explicit Type: a reserved key (DP/AF/AC) would contribute its own reserved-Number/Type
    // diagnostics and the assertion could pass on an unrelated warning.
    String vcf = "##fileformat=VCFv4.2\n" +
        "##INFO=<ID=XX,Number=\"2\",Type=Integer,Description=\"d\">\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "chr1\t1\t.\tA\tT\t.\t.\tXX=1,2,3\n";
    List<VcfRecord> records = new ArrayList<>();
    VcfMetadata metadata;
    try (BufferedReader reader = new BufferedReader(new StringReader(vcf));
         VcfParser parser = new VcfParser.Builder().fromReader(reader)
             .parseWith((md, record) -> records.add(record)).build()) {
      parser.parse();
      metadata = parser.getMetadata();
    }

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    // attached AFTER parsing: construction-time warnings must not be able to satisfy the assertions below
    appender.start();
    root.addAppender(appender);
    try {
      StringWriter sw = new StringWriter();
      try (VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw))
          .validateBeforeWrite().build()) {
        writer.writeHeader(metadata);
        writer.writeLine(metadata, records.get(0).getPosition(), records.get(0).getSamples());
      }
      // positive: the ordinary check runs. Names the key so an unrelated cardinality warning cannot satisfy it.
      assertTrue(appender.anyMessageContains("INFO XX has 3 value(s), but Number=2 requires 2"),
          "expected the ordinary cardinality warning; got " + appender.getRelevantWarnOrHigherMessages(Set.of()));
      // negative: the misleading diagnostic is gone. Alone this would be satisfied by deleting the check.
      assertFalse(appender.anyMessageContains("too large to validate"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  /**
   * Writes one record under a single shared VCFv4.3 context and returns the warnings emitted by the write.
   * <p>
   * One shared VcfContext for metadata, position, sample and writer is mandatory, not stylistic:
   * VcfWriter.java:314 and :319 compare by REFERENCE IDENTITY (position.getContext() != context), so two
   * distinct contexts that both resolve to 4.3 are still rejected. A ##fileformat=VCFv4.3 line is not enough.
   * <p>
   * Record shape is fixed here so Number=G's expected cardinality is determined rather than inferred: one ALT
   * allele and GT=0/1 give combinationsWithRepetition(numAltAlleles + 1 = 2, ploidy = 2) = 3, so the two-element
   * value below is a guaranteed mismatch. Without an explicit GT the ploidy is inferred, and
   * resolvePloidyForCardinalityCheck applies a diploidy default to GL/PL/GP/PP only -- an unspecified fixture
   * can silently produce an expected count that happens to match, making the assertion vacuous.
   */
  private static org.pharmgkb.parser.vcf.spec.WarnCapturingAppender writeOneRecordWithQuotedNumberG(
      String formatKey) throws Exception {
    VcfContext ctx = VcfContext.lenient("VCFv4.3", VcfWarnings.DEFAULT);

    FormatMetadata gt = new FormatMetadata(ctx, InternalUtils.extractProperties(
        new String[] {"ID=GT", "Number=1", "Type=String", "Description=\"gt\""}, VcfWarnings.DEFAULT));
    FormatMetadata subject = new FormatMetadata(ctx, InternalUtils.extractProperties(
        new String[] {"ID=" + formatKey, "Number=\"G\"", "Type=Integer", "Description=\"d\""},
        VcfWarnings.DEFAULT));
    VcfMetadata metadata = new VcfMetadata.Builder(ctx)
        .setFileFormat("VCFv4.3").addFormat(gt).addFormat(subject)
        .setColumns(List.of("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .build();

    VcfPosition position = ctx.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null,
        List.of("GT", formatKey), -1);
    LinkedHashMap<String, String> sampleProps = new LinkedHashMap<>();
    sampleProps.put("GT", "0/1");
    sampleProps.put(formatKey, "1,2");   // two elements against an expected three
    VcfSample sample = ctx.newSample(sampleProps);
    // context identity precondition, asserted before any warning assertion: without this the test could
    // "pass" by observing a construction failure rather than the exemption branch
    assertSame(ctx, position.getContext());
    assertSame(ctx, sample.getContext());

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);   // attached AFTER construction -- setup warnings must not reach the assertions
    try {
      StringWriter sw = new StringWriter();
      try (VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw))
          .validateBeforeWrite().build()) {
        writer.writeHeader(metadata);
        writer.writeLine(metadata, position, List.of(sample));
      }
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    return appender;
  }

  @Test
  public void testQuotedNumberGStillWarnsForANonExemptFormatKey() throws Exception {
    // ZZ is not CNL/CNP/GLE, so the ordinary Number=G cardinality check must run. This is the positive twin:
    // it proves the "G" branch is live, rather than everything-named-G having become exempt.
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender = writeOneRecordWithQuotedNumberG("ZZ");
    assertTrue(appender.anyMessageContains("FORMAT ZZ for sample #0 has 2 value(s), but Number=G requires 3"),
        "expected the ordinary Number=G cardinality warning; got "
            + appender.getRelevantWarnOrHigherMessages(Set.of()));
  }

  @Test
  public void testQuotedNumberGOnCnlAtV43IsStillExempt() throws Exception {
    // identical record shape; the ONLY variable is the FORMAT key, hence the exemption branch
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender = writeOneRecordWithQuotedNumberG("CNL");
    assertFalse(appender.anyMessageContains("but Number=G requires"),
        "CNL's Number=G means per-potential-copy-number, so the genotype-ordering check must stay exempt");
    // This second assertion is what gives this test regression signal of its own, and it is NOT redundant.
    // The first assertion passes both before and after the fix, for unrelated reasons: pre-fix the quoted
    // "G" defeats isExemptFromGenotypeOrderingCardinalityCheck AND getExpectedCardinality, which falls to
    // Long.parseLong, throws, warns "too large to validate" and returns null -- and warnIfWrongCardinality
    // stays silent when expected is null. So no "requires" message appears pre-fix either.
    // The "too large" warning, by contrast, IS emitted pre-fix and must be gone post-fix, because an exempt
    // key never reaches getExpectedCardinality at all.
    assertFalse(appender.anyMessageContains("too large to validate"),
        "an exempt key must not reach getExpectedCardinality, so the quoted-Number parse failure must be gone");
  }

  @Test
  public void testQuotedFormatNumberStillRunsTheDefaultPathTypeCheck() throws Exception {
    // DEFAULT path -- deliberately NOT validateBeforeWrite(): this branch runs only under
    // !m_validateBeforeWrite (VcfWriter.java:967). It pins that a quoted Number reaches the type-conversion
    // probe at all: the probe is gated on Integer.parseInt(format.getNumber()) == 1, and while getNumber()
    // returned the raw value that parse threw into an empty catch, left number null, and skipped the check
    // silently. No negative twin, because the old symptom was an absent diagnostic rather than a misleading
    // one. See testQuotedInfoNumberStillRunsTheDefaultPathTypeCheck for the INFO twin of this same branch.
    String vcf = "##fileformat=VCFv4.2\n" +
        "##FORMAT=<ID=XX,Number=\"1\",Type=Integer,Description=\"d\">\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tS1\n" +
        "chr1\t1\t.\tA\tT\t.\t.\t.\tXX\tabc\n";
    List<VcfRecord> records = new ArrayList<>();
    VcfMetadata metadata;
    try (BufferedReader reader = new BufferedReader(new StringReader(vcf));
         VcfParser parser = new VcfParser.Builder().fromReader(reader)
             .parseWith((md, record) -> records.add(record)).build()) {
      parser.parse();
      metadata = parser.getMetadata();
    }

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);   // after parsing
    try {
      StringWriter sw = new StringWriter();
      try (VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build()) {
        writer.writeHeader(metadata);
        writer.writeLine(metadata, records.get(0).getPosition(), records.get(0).getSamples());
      }
      // names BOTH the key and the Integer type -- that is what distinguishes this repaired default-path
      // branch from the full-diagnostics path the INFO-cardinality test above covers
      assertTrue(appender.anyMessageContains("Property XX for sample #0 is not of type Integer"),
          "expected the default-path type warning; got "
              + appender.getRelevantWarnOrHigherMessages(Set.of()));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testQuotedInfoNumberStillRunsTheDefaultPathTypeCheck() throws Exception {
    // The INFO twin of testQuotedFormatNumberStillRunsTheDefaultPathTypeCheck above, and a separate branch
    // from it: this one is addInfoOrDot (VcfWriter.java:1042-1054), that one is addSampleConditionally
    // (:967-975). Both are gated on !m_validateBeforeWrite, so neither is reachable from
    // testQuotedInfoNumberStillRunsTheOrdinaryCardinalityCheck, which runs with validateBeforeWrite() ON.
    // The message shape differs too -- no sample index here -- which is what the assertion below keys on.
    String vcf = "##fileformat=VCFv4.2\n" +
        "##INFO=<ID=XX,Number=\"1\",Type=Integer,Description=\"d\">\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "chr1\t1\t.\tA\tT\t.\t.\tXX=abc\n";
    List<VcfRecord> records = new ArrayList<>();
    VcfMetadata metadata;
    try (BufferedReader reader = new BufferedReader(new StringReader(vcf));
         VcfParser parser = new VcfParser.Builder().fromReader(reader)
             .parseWith((md, record) -> records.add(record)).build()) {
      parser.parse();
      metadata = parser.getMetadata();
    }

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);   // after parsing
    try {
      StringWriter sw = new StringWriter();
      try (VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build()) {
        writer.writeHeader(metadata);
        writer.writeLine(metadata, records.get(0).getPosition(), records.get(0).getSamples());
      }
      // names the key and the Integer type, and deliberately omits "for sample #" -- asserting on the INFO
      // message shape is what keeps the FORMAT branch from satisfying this test
      assertTrue(appender.anyMessageContains("Property XX is not of type Integer"),
          "expected the default-path INFO type warning; got "
              + appender.getRelevantWarnOrHigherMessages(Set.of()));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

}
