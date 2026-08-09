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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pharmgkb.parser.vcf.spec.FailingWriter;

import static org.pharmgkb.parser.vcf.TestUtils.LENIENT_CONTEXT;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
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
    return new VcfMetadata(fileFormat, null, info, null, null, null, null, pedigrees, List.of(), rawProperties,
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", null, new BigDecimal("0"), null, info,
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
        Arrays.asList("rs1", "", "rs2"), "A", null, new BigDecimal("0"), null, null, null, -1, null, null);

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
        null, new BigDecimal("0"), null, null, null, -1, null, null);

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
        new BigDecimal("0"), Arrays.asList("q10", ""), null, null, -1, null, null);

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
        Arrays.asList("T", "", "C"), new BigDecimal("0"), null, null, null, -1, null, null);

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
        Arrays.asList("rs1", null), "A", null, new BigDecimal("0"), null, null, null, -1, null, null);

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
        Arrays.asList("T", null), new BigDecimal("0"), null, null, null, -1, null, null);

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
        new BigDecimal("0"), Arrays.asList("q10", null), null, null, -1, null, null);

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
        new BigDecimal("0"), null, null, Arrays.asList("GT", null), -1, null, null);
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
        new BigDecimal("0"), null, info, null, -1, null, null);

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
        new BigDecimal("0"), null, info, null, -1, null, null);

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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", null, new BigDecimal("0"), null, info,
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", null, new BigDecimal("0"), null, info,
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", null, new BigDecimal("0"), null, info,
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", null, new BigDecimal("0"), null,
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", null, new BigDecimal("0"), null, info,
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", null, new BigDecimal("0"), null, info,
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", null, new BigDecimal("0"), null, info,
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", null, new BigDecimal("0"), null, info,
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
    VcfPosition leadingPos = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", null, new BigDecimal("0"), null,
        leadingInfo, null, -1);
    leadingWriter.writeLine(metadata, leadingPos, Collections.emptyList());
    assertTrue(leading.toString().contains("XY=.,2"));

    StringWriter trailing = new StringWriter();
    VcfWriter trailingWriter = new VcfWriter.Builder().toWriter(new PrintWriter(trailing)).build();
    trailingWriter.writeHeader(metadata);
    ListMultimap<String, String> trailingInfo = ArrayListMultimap.create();
    trailingInfo.put("XY", "1");
    trailingInfo.put("XY", "");
    VcfPosition trailingPos = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", null, new BigDecimal("0"), null,
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
    VcfPosition allEmptyPos = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", null, new BigDecimal("0"), null,
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", null, new BigDecimal("0"), null, info,
        null, -1);
    writer.writeLine(metadata, position, Collections.emptyList());
    // asserted on the raw written text, not just the reparsed result: the parser's own INFO empty-value
    // handling would independently normalize a literal "XY=1,,2" to the same [1, ".", 2] on reparse, which
    // would let this test pass even if the writer itself never canonicalized anything -- this line is what
    // actually makes the test load-bearing for the writer fix, not just the parser's.
    assertTrue(sw.toString().contains("XY=1,.,2"));

    List<VcfPosition> reparsed = new java.util.ArrayList<>();
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, "A", new BigDecimal("0"), -1);
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, "A", new BigDecimal("0"), -1);
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", null, new BigDecimal("0"),
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
        new BigDecimal("0"), Arrays.asList("STD_FILTER", "."), null, null, -1, null, null);

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
    // (validateSamples -> VcfSample.validate() -> checkNoStructuralDelimiter) is still live, and removing the
    // writer's now-unreachable defenses is Task 11's job, gated on Task 11 Step 1 PROVING they're unreachable.
    // Deleting this test would remove exactly the evidence that proof rests on, and the tripwire if Task 11
    // drops the wrong guard -- the same mistake Task 8 made and had to reverse.
    //
    // newTrustedSample() is the reconstruction route: it skips the structural-delimiter scan by design (its
    // keys/values are documented as coming from a ':'/tab split and so "can never contain a delimiter"), which
    // makes it the only remaining way to hand the writer a sample whose value contains a ':'. Package-private,
    // reachable here because this test is in the same package -- mirroring how Task 8 restored the writer's
    // empty-entry tests via newPositionCopyingTrustedFields().
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .build();
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), new BigDecimal("0"), null,
        null, List.of("DP"), -1);
    VcfSample sample = LENIENT_CONTEXT.newTrustedSample(List.of("DP"), List.of("1:2"));

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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), new BigDecimal("0"), null,
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), new BigDecimal("0"), null,
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), new BigDecimal("0"), null,
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), new BigDecimal("0"), null,
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), new BigDecimal("0"), null,
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), new BigDecimal("0"), null,
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T", "C"), new BigDecimal("0"),
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), new BigDecimal("0"), null,
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), new BigDecimal("0"), null,
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
  public void testValidateBeforeWriteRejectsUnescapedQuoteInMetadataValue() throws Exception {
    // a value that isn't validly quoted (isQuoted() is false) but also contains no ',' or '=' previously slipped
    // through unrejected. Verified live: a value of "abc"def" (embedded unescaped '"', no comma/equals) followed
    // by another property in the same declaration doesn't just misparse -- it fails to parse entirely
    // (VcfFormatException) once re-read, because the stray '"' flips the reader's "inside a quoted value" state
    // for everything that follows.
    Map<String, String> props = new LinkedHashMap<>();
    props.put("ID", "X");
    props.put("Description", "\"d\"");
    props.put("AAAA", "\"abc\"def\"");
    props.put("BBBB", "\"has,a,comma\"");
    InfoMetadata info = new InfoMetadata(LENIENT_CONTEXT, props);
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").addInfo(info).build();
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();

    assertThrows(VcfFormatException.class, () -> writer.writeHeader(metadata));
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
  public void testValidateBeforeWriteWarnsOnMetadataMutatedOutOfCanonicalOrder() throws Exception {
    // reordering the backing map after construction (remove then re-add) bypasses the order check that only runs
    // once, inside IdDescriptionMetadata's Map-based constructor -- validateBeforeWrite() must re-check it
    InfoMetadata info = new InfoMetadata(LENIENT_CONTEXT, "NS", "d", InfoType.Integer, "1", null, null);
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").addInfo(info).build();
    Map<String, String> props = metadata.getInfo().get("NS").trustedMutableProperties();
    String number = props.remove("Number");
    props.put("Number", number);
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), new BigDecimal("0"), null,
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T", "C"), new BigDecimal("0"),
        null, positionInfo, null, -1);
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();

    writer.writeHeader(metadata);
    writer.writeLine(metadata, position, Collections.emptyList());
    assertTrue(sw.toString().contains("AF=."));
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), new BigDecimal("0"), null,
        null, List.of("FT"), -1);
    VcfSample sample = LENIENT_CONTEXT.newSample(new LinkedHashMap<>(Map.of("FT", "PASS;q10")));

    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    writer.writeHeader(metadata);
    writer.writeLine(metadata, position, Collections.singletonList(sample));
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), new BigDecimal("0"), null,
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), new BigDecimal("0"), null,
        null, List.of("PS"), -1);
    VcfSample sample = LENIENT_CONTEXT.newSample(new LinkedHashMap<>(Map.of("PS", "-5")));

    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    writer.writeHeader(metadata);
    writer.writeLine(metadata, position, Collections.singletonList(sample));
    assertTrue(sw.toString().contains("PS\t-5"));
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), new BigDecimal("0"), null,
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), new BigDecimal("0"), null,
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), new BigDecimal("0"), null,
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), new BigDecimal("0"), null,
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), new BigDecimal("0"), null,
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
        new BigDecimal("0"), null, firstInfo, null, -1);
    VcfPosition second = LENIENT_CONTEXT.newPosition("chr1", 2, List.of("rsb"), "A", List.of("T"),
        new BigDecimal("0"), null, null, null, -1);
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), new BigDecimal("0"), null,
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), new BigDecimal("0"), null,
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), new BigDecimal("0"), null,
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), new BigDecimal("0"), null,
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
    InfoMetadata info = new InfoMetadata(LENIENT_CONTEXT, "AN", "d", InfoType.Integer, "1", null, null);
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").addInfo(info).build();
    writer.writeHeader(metadata);
    ListMultimap<String, String> positionInfo = ArrayListMultimap.create();
    positionInfo.put("AN", "not-a-number"); // Type=Integer declared, but the value doesn't parse as one
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), new BigDecimal("0"), null,
        positionInfo, null, -1);
    writer.writeLine(metadata, position, Collections.emptyList());
    assertTrue(sw.toString().contains("AN=not-a-number"));
  }

  @Test
  public void testWriteSamplePropertyNotMatchingDeclaredTypeWarnsInsteadOfThrowing() throws Exception {
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    FormatMetadata format = new FormatMetadata(LENIENT_CONTEXT, "DP", "d", "1", FormatType.Integer);
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").addFormat(format)
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "SAMPLE1"))
        .build();
    writer.writeHeader(metadata);
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), new BigDecimal("0"), null,
        null, List.of("DP"), -1);
    // Type=Integer declared, but the value doesn't parse as one
    VcfSample sample = LENIENT_CONTEXT.newSample(new LinkedHashMap<>(Map.of("DP", "not-a-number")));
    writer.writeLine(metadata, position, Collections.singletonList(sample));
    assertTrue(sw.toString().contains("\tDP\tnot-a-number"));
  }

  @Test
  public void testWriteLineRejectsTooFewSamples() throws Exception {
    // header declares 2 samples, but only 1 is provided: the output would not parse back against its own header
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1", "S2"))
        .build();
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), new BigDecimal("0"), null,
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), new BigDecimal("0"), null,
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), new BigDecimal("0"), null,
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), new BigDecimal("0"), null,
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), new BigDecimal("0"), null,
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), new BigDecimal("0"), null,
        null, null, -1);
    VcfSample badSample = LENIENT_CONTEXT.newSample(new java.util.ArrayList<>(), new java.util.ArrayList<>());
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
    List<PedigreeMetadata> pedigreesWithNullEntry = new java.util.ArrayList<>();
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), new BigDecimal("0"), null,
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, "A", new BigDecimal("0"), -1);
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
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    VcfFormatException e = assertThrows(VcfFormatException.class,
        () -> writer.writeLine(differentMetadata, position, Collections.emptyList()));
    assertTrue(e.getMessage().contains("writeLine() was given different metadata than writeHeader() used"));
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
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").setColumns(List.of("#CHROM", "POS",
        "ID", "REF", "ALT", "QUAL", "FILTER", "INFO")).build();
    VcfPosition position = LENIENT_CONTEXT.newPosition("1", 100, "A", null, -1).toBuilder()
        .rawQuality("notanumber") // never accessed yet -- writeLine() must be what fires the lazy parse
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
        writer.writeHeader(metadata);
        writer.writeLine(metadata, position, List.of());
      }
      assertFalse(java.nio.file.Files.readString(warningsFile).contains("is not a number"));
      assertTrue(appender.anyMessageContains("is not a number"));
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

  @Test
  public void testWriteSideVersionFallbackWarningIsEmittedForAnUnimplementedDeclaration(@TempDir Path tempDir)
      throws Exception {
    // Pinned, not fixed. writeHeader() builds its context with VcfContext.lenient(...), so writing metadata
    // that declares a version this library does not implement reports the same version-fallback warning the
    // parser does -- meaning a parse-then-write round trip of a 4.4/4.5 file warns about it twice, once per
    // side. That is a known, deliberately deferred consequence of giving the writer a context at all: whether
    // the emitted output is actually valid under the declared version is only answerable once version-gated
    // behavior exists, so the writer has nothing better to say yet. This test exists so the diagnostic is not
    // untested and undocumented in the meantime; it is not an assertion that this is the desired end state.
    Path warningsFile = tempDir.resolve("write-warnings.txt");
    StringWriter sw = new StringWriter();
    VcfMetadata metadata = new VcfMetadata.Builder(VcfContext.lenient("VCFv4.5", VcfWarnings.DEFAULT))
        .setColumns(List.of("#CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO"))
        .build();
    try (VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw))
        .warningsToFile(warningsFile).build()) {
      writer.writeHeader(metadata);
    }
    // the declaration itself still round-trips verbatim -- the fallback governs interpretation, not output
    assertTrue(sw.toString().startsWith("##fileformat=VCFv4.5\n"), sw::toString);
    String warnings = Files.readString(warningsFile);
    assertThat(warnings, containsString("VCFv4.5"));
    assertThat(warnings, containsString("4.3"));
  }

}
