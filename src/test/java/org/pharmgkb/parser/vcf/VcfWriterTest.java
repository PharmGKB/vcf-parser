package org.pharmgkb.parser.vcf;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.pharmgkb.parser.vcf.model.FormatMetadata;
import org.pharmgkb.parser.vcf.model.FormatType;
import org.pharmgkb.parser.vcf.model.IdDescriptionMetadata;
import org.pharmgkb.parser.vcf.model.InfoMetadata;
import org.pharmgkb.parser.vcf.model.InfoType;
import org.pharmgkb.parser.vcf.model.PedigreeMetadata;
import org.pharmgkb.parser.vcf.model.ReservedFormatProperty;
import org.pharmgkb.parser.vcf.model.ReservedInfoProperty;
import org.pharmgkb.parser.vcf.model.VcfMetadata;
import org.pharmgkb.parser.vcf.model.VcfPosition;
import org.pharmgkb.parser.vcf.model.VcfSample;
import org.pharmgkb.parser.vcf.spec.FailingWriter;

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

  @Test
  public void testWriteLineRejectsEmptyRef() throws Exception {
    // REF cannot be empty through the constructor, but setRef() does not validate. REF has no missing-value
    // sentinel in the spec, so writing "." here would not be valid VCF either -- just a placeholder that avoids
    // crashing. The writer now rejects this outright instead of emitting non-compliant output.
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getAltBases().add("T");
    position.setRef("");
    writer.writeHeader(metadata);
    VcfFormatException e = assertThrows(VcfFormatException.class,
        () -> writer.writeLine(metadata, position, Collections.emptyList()));
    assertTrue(e.getMessage().contains("has a null or empty REF, which the VCF spec does not allow"));
  }

  @Test
  public void testWriteLineDoesNotValidateByDefault() throws Exception {
    // getAltBases() is mutable and does not re-validate (by design, to support transformation pipelines); by
    // default, writeLine does not call position.validate() either, so a position mutated into an invalid state is
    // written without error
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getAltBases().add("not-a-valid-base");
    writer.writeLine(metadata, position, Collections.emptyList());
    assertTrue(sw.toString().contains("not-a-valid-base"));
  }

  @Test
  public void testMidWriteLineWarningReportsTheLineCurrentlyBeingWritten() throws Exception {
    // regression test: a warning fired while writeLine() is still building the current data line used to read
    // m_lineNumber before this line's own printLine() call had run, under-reporting by exactly one line. A
    // metadata-only header (no INFO/FILTER/FORMAT/ALT/contig/SAMPLE/PEDIGREE declared, no samples) always emits
    // exactly 2 header lines (##fileformat and #CHROM), so the first data line written afterward is line 3.
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getInfo().put("AD", "1"); // AD is not declared in metadata's (empty) INFO map

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
  public void testDefaultWriteRejectsNullIdInsteadOfWritingLiteralNullText() throws Exception {
    // getIds() is a live view; VcfPosition.validate()'s checkIds() null guard only runs under
    // validateBeforeWrite(), so addListOrElse() must guard the default path itself, since
    // StringBuilder.append(Object) would otherwise silently stringify a null entry into the literal text "null"
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getIds().add(null);

    assertThrows(VcfFormatException.class, () -> writer.writeLine(metadata, position, Collections.emptyList()));
  }

  @Test
  public void testDefaultWriteRejectsNullFilterInsteadOfWritingLiteralNullText() throws Exception {
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getFilters().add(null);

    assertThrows(VcfFormatException.class, () -> writer.writeLine(metadata, position, Collections.emptyList()));
  }

  @Test
  public void testDefaultWriteDropsEmptyIdEntry() throws Exception {
    // regression test: the default path previously only null-checked ID/FILTER/ALT entries, silently writing a
    // zero-length VCF field for a non-null empty string inserted via the live getIds()/getFilters()/getAltBases()
    // view. ID is position-independent (nothing indexes into it), so EMPTY_FIELD_HANDLING.md's strategy is to
    // drop the empty entry, matching the parser's own lenient behavior.
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getIds().add("rs1");
    position.getIds().add("");
    position.getIds().add("rs2");

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
    // accidentally-empty column
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getIds().add("");
    position.getIds().add("");

    writer.writeLine(metadata, position, Collections.emptyList());
    // pin down the ID field specifically (between POS and REF) -- a loose "\t.\t" check would trivially match
    // the unrelated ALT/QUAL/FILTER fields elsewhere in the same line, which also render as "."
    assertTrue(sw.toString().contains("chr1\t1\t.\tA\t"));
  }

  @Test
  public void testDefaultWriteDropsEmptyFilterEntry() throws Exception {
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getFilters().add("q10");
    position.getFilters().add("");

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
    // rather than dropped -- dropping would shift every later allele's index
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getAltBases().add("T");
    position.getAltBases().add("");
    position.getAltBases().add("C");

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
  public void testDefaultWriteRejectsNullFormatKeyInsteadOfWritingLiteralNullText() throws Exception {
    // getFormat() is a live view; VcfPosition.validate()'s checkFormat() null guard only runs under
    // validateBeforeWrite(), so addFormatConditionally() must guard the default path itself
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .build();
    writer.writeHeader(metadata);
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getAltBases().add("T");
    position.getFormat().add(null);
    VcfSample sample = new VcfSample(new LinkedHashMap<>());

    assertThrows(VcfFormatException.class,
        () -> writer.writeLine(metadata, position, Collections.singletonList(sample)));
  }

  @Test
  public void testDefaultWriteRejectsNullInfoKeyInsteadOfWritingLiteralNullText() throws Exception {
    // getInfo() is a live LinkedListMultimap view that permits null keys/values; VcfPosition.validate()'s
    // checkInfoEntries() null guard only runs under validateBeforeWrite(), so addInfoOrDot() must guard the
    // default path itself
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getInfo().put(null, "1");

    assertThrows(VcfFormatException.class, () -> writer.writeLine(metadata, position, Collections.emptyList()));
  }

  @Test
  public void testDefaultWriteRejectsNullInfoValueInsteadOfWritingLiteralNullText() throws Exception {
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getInfo().put("NS", null);

    assertThrows(VcfFormatException.class, () -> writer.writeLine(metadata, position, Collections.emptyList()));
  }

  @Test
  public void testDefaultWriteCanonicalizesEmptyNonFlagInfoValueInsteadOfWritingBareFlagKey() throws Exception {
    // position.getInfo() is a live view a caller can mutate directly (e.g. a transformation pipeline). A single
    // empty-string value is the same internal representation VcfPosition.info() uses for a genuine Flag (no "="
    // in the original text), so writing it as a bare key here would silently reinterpret this declared
    // Type=String field as a Flag on reparse -- addInfoOrDot() must canonicalize to the "." missing-value
    // sentinel instead, on the default path too, not just under validateBeforeWrite()
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
        .addInfo(new InfoMetadata("XX", "d", InfoType.String, "1", null, null))
        .build();
    writer.writeHeader(metadata);
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getInfo().put("XX", "");

    writer.writeLine(metadata, position, Collections.emptyList());
    assertTrue(sw.toString().contains("XX=."));
  }

  @Test
  public void testValidateBeforeWriteCanonicalizesEmptyNonFlagInfoValueInsteadOfWritingBareFlagKey() throws Exception {
    // validateInfoValue() already warned about this exact case ("INFO {} contains an empty value") under
    // validateBeforeWrite(), but that warning had no effect on what addInfoOrDot() actually wrote afterward in
    // the same writeLine() call -- it fired, yet the output was neither preserved nor canonicalized, violating
    // this mode's own "warn and canonicalize" contract. Confirms the fix applies in both write modes.
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
        .addInfo(new InfoMetadata("XX", "d", InfoType.String, "1", null, null))
        .build();
    writer.writeHeader(metadata);
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getInfo().put("XX", "");

    writer.writeLine(metadata, position, Collections.emptyList());
    assertTrue(sw.toString().contains("XX=."));
  }

  @Test
  public void testWriteLinePreservesGenuineFlagValueAsBareKey() throws Exception {
    // a key declared Type=Flag must still be written bare (no "="); this is the one empty-value case
    // addInfoOrDot()'s new canonicalization must NOT touch
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
        .addInfo(new InfoMetadata("DB", "d", InfoType.Flag, "0", null, null))
        .build();
    writer.writeHeader(metadata);
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getInfo().put("DB", "");

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
    InfoMetadata info = new InfoMetadata(props, VcfWarnings.DEFAULT);
    assertNull(info.getType());
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").addInfo(info).build();
    writer.writeHeader(metadata);
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getInfo().put("XX", "");

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
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getInfo().put("DB", "");

    writer.writeLine(metadata, position, Collections.emptyList());
    assertTrue(sw.toString().contains("DB"));
    assertFalse(sw.toString().contains("DB="));
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
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    assertThrows(VcfFormatException.class, () -> writer.writeLine(metadata, null, Collections.emptyList()));
  }

  @Test
  public void testWriteLineRejectsNullSamplesInsteadOfThrowingNpe() throws Exception {
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
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
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    assertThrows(VcfFormatException.class, () -> writer.writeLine(null, position, Collections.emptyList()));
  }

  @Test
  public void testDefaultWriteRejectsNullChromosomeInsteadOfWritingLiteralNullText() throws Exception {
    // setChromosome() does not validate its input, by design (see VcfPosition.validate()'s Javadoc);
    // VcfWriter's default path must guard this itself, since CHROM has no missing-value sentinel and
    // StringBuilder.append(Object) would otherwise silently stringify a null into the literal text "null"
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.setChromosome(null);

    assertThrows(VcfFormatException.class, () -> writer.writeLine(metadata, position, Collections.emptyList()));
  }

  @Test
  public void testDefaultWriteRejectsNullRefInsteadOfWritingLiteralNullText() throws Exception {
    // unlike the other null-guard regressions in this class, this previously crashed with a raw
    // NullPointerException even on the default path (position.getRef().isEmpty() ran unconditionally), not just
    // silently stringified to "null"
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.setRef(null);

    assertThrows(VcfFormatException.class, () -> writer.writeLine(metadata, position, Collections.emptyList()));
  }

  @Test
  public void testWriteLineDoesNotWarnAboutPassFilter() throws Exception {
    // PASS is VCF's reserved "all filters passed" keyword (VCFv4.2.tex line 185) and is never expected to have its
    // own ##FILTER=<ID=PASS,...> declaration. FILTER combining PASS with another code now warns instead of
    // throwing during parsing (see VcfPosition.checkFilters), so position.getFilters() can legitimately contain a
    // literal "PASS" entry -- this must not trigger the undeclared-FILTER warning below.
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
        .addFilter(new IdDescriptionMetadata("FILTER", "STD_FILTER", "Standard filter"))
        .build();
    writer.writeHeader(metadata);
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getFilters().add("STD_FILTER");
    position.getFilters().add("PASS");

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
  public void testValidateBeforeWriteWarnsWhenChromSampleMissingFromSampleMetadata() throws Exception {
    // regression test: writeHeader() previously never checked this at all, even though the same mismatch would
    // have been caught had the file been re-parsed from scratch (VcfParser has the identical check, with the
    // same warning text). Checked once here, at writeHeader() time, not on every writeLine() call: #CHROM's
    // sample columns can't change after construction (VcfMetadata.Builder.build() copies them into an immutable
    // list), and a post-writeHeader() mutation to ##SAMPLE itself is already rejected outright by writeLine()'s
    // own header-text-snapshot check, so there's nothing left for a per-writeLine() re-check to catch.
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
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
  public void testWriteLineValidatesWhenBuiltWithValidateBeforeWrite() throws Exception {
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getAltBases().add("not-a-valid-base");
    writer.writeHeader(metadata);
    assertThrows(VcfFormatException.class,
        () -> writer.writeLine(metadata, position, Collections.emptyList()));
  }

  @Test
  public void testValidateBeforeWriteRejectsMutatedSampleValue() throws Exception {
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .build();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getAltBases().add("T");
    position.getFormat().add("DP");
    VcfSample sample = new VcfSample(new LinkedHashMap<>());
    sample.putProperty("DP", "1");
    sample.propertyEntrySet().iterator().next().setValue("1:2");

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
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .build();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getAltBases().add("T");
    position.getFormat().add("DP");
    VcfSample sample = new VcfSample(new LinkedHashMap<>());
    sample.putProperty("DP", "");

    writer.writeHeader(metadata);
    writer.writeLine(metadata, position, Collections.singletonList(sample));
    assertTrue(sw.toString().contains("\tDP\t."));
  }

  @Test
  public void testValidateBeforeWriteRejectsSamplePropertyMissingFromFormat() throws Exception {
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .build();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getAltBases().add("T");
    position.getFormat().add("GT");
    VcfSample sample = new VcfSample(new LinkedHashMap<>());
    sample.putProperty("GT", "0/1");
    sample.putProperty("DP", "10");

    writer.writeHeader(metadata);
    assertThrows(VcfFormatException.class,
        () -> writer.writeLine(metadata, position, Collections.singletonList(sample)));
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
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .addFormat(new FormatMetadata("GT", "Genotype", "1", FormatType.String))
        .addFormat(new FormatMetadata("GLE", "Genotype likelihoods", "G", FormatType.String))
        .build();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getAltBases().add("T");
    position.getFormat().add("GT");
    position.getFormat().add("GLE");
    VcfSample sample = new VcfSample(new LinkedHashMap<>());
    sample.putProperty("GT", "0/1");
    sample.putProperty("GLE", "0:-75.22,1:-223.42,0/0:-323.03,1/0:-99.29,1/1:-802.53");

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
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .addFormat(new FormatMetadata("GT", "Genotype", "1", FormatType.String))
        .addFormat(new FormatMetadata("CNP", "Copy number posterior probabilities", "G", FormatType.Float))
        .build();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getAltBases().add("T");
    position.getFormat().add("GT");
    position.getFormat().add("CNP");
    VcfSample sample = new VcfSample(new LinkedHashMap<>());
    sample.putProperty("GT", "0/1");
    sample.putProperty("CNP", "0.1,0.2,0.3,0.2,0.2");

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
  public void testValidateBeforeWriteChecksUndeclaredReservedInfoKeyCardinality() throws Exception {
    // AC is reserved (Number=A) but has no ##INFO=<ID=AC,...> declaration at all here -- validateInfo must still
    // fall back to AC's own canonical Number/Type, matching getInfo(ReservedInfoProperty)'s identical read-path
    // fallback, rather than silently skipping every check beyond the generic "no INFO metadata" warning
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO"))
        .build();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getAltBases().add("T");
    position.getAltBases().add("C");
    position.getInfo().put("AC", "1"); // 2 ALT alleles but only 1 value -- a real Number=A violation

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
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .addFormat(new FormatMetadata("GT", "Genotype", "1", FormatType.String))
        .build();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getAltBases().add("T");
    position.getFormat().add("GT");
    position.getFormat().add("AD");
    VcfSample sample = new VcfSample(new LinkedHashMap<>());
    sample.putProperty("GT", "0/1");
    sample.putProperty("AD", "10"); // Number=R with 1 ALT allele expects 2 values, only 1 given

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
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .addFormat(new FormatMetadata("PL", "Phred-scaled genotype likelihoods", "G", FormatType.Integer))
        .build();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getAltBases().add("T");
    position.getFormat().add("PL");
    VcfSample sample = new VcfSample(new LinkedHashMap<>());
    sample.putProperty("PL", "1,2"); // assumed diploidy + 1 ALT allele expects 3 values, only 2 given

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
    InfoMetadata info = new InfoMetadata("NS", "d", InfoType.Integer, "1", null, null);
    info.getPropertiesRaw().put("Description", "unquoted,comma");
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").addInfo(info).build();
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();

    assertThrows(VcfFormatException.class, () -> writer.writeHeader(metadata));
  }

  @Test
  public void testValidateBeforeWriteRejectsEqualsSignInRawPropertyKey() throws Exception {
    // a raw property ("##key=value") is reread by VcfParser splitting on the FIRST "=" only; a key containing "="
    // shifts that split point. Verified live: writing key "foo=bar" reparses as key "foo", value "bar=value"
    // instead of the original key "foo=bar", value "value" -- genuine data corruption, not just unusual content.
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    metadata.getRawProperties().put("foo=bar", "value");
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();

    assertThrows(VcfFormatException.class, () -> writer.writeHeader(metadata));
  }

  @Test
  public void testValidateBeforeWriteRejectsNullRawPropertyKey() throws Exception {
    // getRawProperties() is a live view backed by a LinkedListMultimap, which permits null keys/values; a caller
    // inserting a null key must get VcfFormatException from validateMetadata(), not a raw NullPointerException
    // from key.contains("=")
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    metadata.getRawProperties().put(null, "value");
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();

    assertThrows(VcfFormatException.class, () -> writer.writeHeader(metadata));
  }

  @Test
  public void testValidateBeforeWriteRejectsNullRawPropertyValue() throws Exception {
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    metadata.getRawProperties().put("foo", null);
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();

    assertThrows(VcfFormatException.class, () -> writer.writeHeader(metadata));
  }

  @Test
  public void testDefaultWriteRejectsNullRawPropertyKeyInsteadOfWritingLiteralNullText() throws Exception {
    // without validateBeforeWrite(), buildHeaderLines() never calls validateMetadata(); addPropertyLines() must
    // guard this itself, since "##" + name + "=" + string would otherwise silently stringify a null name/value
    // into the literal text "null", fabricating content that was never actually in the property
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    metadata.getRawProperties().put(null, "value");
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();

    assertThrows(VcfFormatException.class, () -> writer.writeHeader(metadata));
  }

  @Test
  public void testDefaultWriteRejectsNullRawPropertyValueInsteadOfWritingLiteralNullText() throws Exception {
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    metadata.getRawProperties().put("foo", null);
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();

    assertThrows(VcfFormatException.class, () -> writer.writeHeader(metadata));
  }

  @Test
  public void testValidateBeforeWriteRejectsNullInfoPropertyValueViaLiveView() throws Exception {
    // getPropertiesRaw() is a live view, so a caller can insert a null value after construction, bypassing the
    // constructor's own validateProperties() check at the time it originally ran. validateMetadata() calls
    // entry.validate() on every keyed entry (BaseMetadata.validate() -> validateProperties(), the same method,
    // re-run), which now rejects a null value there too -- this locks in that re-validation, not a dead branch
    // in validateMetadataProperties() (which used to duplicate this check but is now unreachable for it).
    InfoMetadata info = new InfoMetadata("NS", "d", InfoType.Integer, "1", null, null);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").addInfo(info).build();
    metadata.getInfo().get("NS").getPropertiesRaw().put("Description", null);
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();

    assertThrows(VcfFormatException.class, () -> writer.writeHeader(metadata));
  }

  @Test
  public void testDefaultWriteRejectsNullInfoPropertyKeyInsteadOfWritingLiteralNullText() throws Exception {
    // without validateBeforeWrite(), buildHeaderLines() never calls validateMetadata(); getAllProperties() must
    // guard this itself, since entry.getKey()/entry.getValue() being null would otherwise silently stringify via
    // StringBuilder.append(Object) into the literal text "null", fabricating content never actually in the property
    InfoMetadata info = new InfoMetadata("NS", "d", InfoType.Integer, "1", null, null);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").addInfo(info).build();
    metadata.getInfo().get("NS").getPropertiesRaw().put(null, "value");
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();

    assertThrows(VcfFormatException.class, () -> writer.writeHeader(metadata));
  }

  @Test
  public void testDefaultWriteRejectsNullInfoPropertyValueInsteadOfWritingLiteralNullText() throws Exception {
    InfoMetadata info = new InfoMetadata("NS", "d", InfoType.Integer, "1", null, null);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").addInfo(info).build();
    metadata.getInfo().get("NS").getPropertiesRaw().put("Description", null);
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();

    assertThrows(VcfFormatException.class, () -> writer.writeHeader(metadata));
  }

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
    InfoMetadata info = new InfoMetadata(props, VcfWarnings.DEFAULT);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").addInfo(info).build();
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();

    assertThrows(VcfFormatException.class, () -> writer.writeHeader(metadata));
  }

  @Test
  public void testValidateBeforeWriteRejectsMetadataIdDivergedFromMapKey() throws Exception {
    // getInfo() (and its FORMAT/FILTER/ALT/contig/SAMPLE siblings) return the backing map directly; mutating an
    // entry's own raw ID property, while it stays stored under its original map key, would otherwise make the
    // emitted header declare a different ID than what data lines reference by that map key
    InfoMetadata info = new InfoMetadata("NS", "d", InfoType.Integer, "1", null, null);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").addInfo(info).build();
    metadata.getInfo().get("NS").getPropertiesRaw().put("ID", "DIFFERENT");
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();

    assertThrows(VcfFormatException.class, () -> writer.writeHeader(metadata));
  }

  @Test
  public void testValidateBeforeWriteWarnsOnAltMutatedToInvalidExtraNumber() throws Exception {
    // getAlts() returns the backing map directly; mutating an already-valid entry's raw Number property after
    // construction bypasses addAlt()'s own validation (which only runs once, at add time), so
    // validateBeforeWrite() must re-check it at write time
    IdDescriptionMetadata alt = new IdDescriptionMetadata("ALT", "DEL", "Deletion");
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").addAlt(alt).build();
    metadata.getAlts().get("DEL").getPropertiesRaw().put("Number", "B");
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
    InfoMetadata info = new InfoMetadata("NS", "d", InfoType.Integer, "1", null, null);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").addInfo(info).build();
    Map<String, String> props = metadata.getInfo().get("NS").getPropertiesRaw();
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
    // getRawProperties() returns the backing multimap directly; mutating an existing entry's value to empty
    // bypasses addRawProperty()/addAssembly()/addPedigreeDatabase()'s own checks entirely (those only run once,
    // at insertion time), so validateBeforeWrite() must re-check it at write time. Unlike the parse-time path,
    // this only warns -- it does not remove the entry from the caller's own metadata object.
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
        .addRawProperty("reference", "file:///seq/reference.fasta")
        .build();
    metadata.getRawProperties().remove("reference", "file:///seq/reference.fasta");
    metadata.getRawProperties().put("reference", "");
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
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
        .addRawProperty("reference", "file:///seq/reference.fasta")
        .build();
    metadata.getRawProperties().remove("reference", "file:///seq/reference.fasta");
    metadata.getRawProperties().put("reference", "<http://foo.com>");
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
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    metadata.getRawProperties().put("INFO", "bad");
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    assertThrows(VcfFormatException.class, () -> writer.writeHeader(metadata));
  }

  @Test
  public void testValidateBeforeWriteWarnsOnDuplicatePedigreeIdIntroducedAfterConstruction() throws Exception {
    // Builder.addPedigree() now warns and drops a duplicate ID at construction time (see
    // VcfMetadataTest.testAddPedigreeWarnsAndKeepsFirstOnDuplicateId), so the only way a duplicate PEDIGREE ID can
    // still reach this write-time check is by mutating the mutable List returned by getPedigrees() directly,
    // bypassing Builder entirely -- e.g. a transformation pipeline that appends to an already-built VcfMetadata
    Map<String, String> childProps = new LinkedHashMap<>();
    childProps.put("ID", "Child");
    childProps.put("Father", "Dad");
    Map<String, String> duplicateProps = new LinkedHashMap<>();
    duplicateProps.put("ID", "Child");
    duplicateProps.put("Mother", "Mom");
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
        .addPedigree(new PedigreeMetadata(childProps))
        .build();
    metadata.getPedigrees().add(new PedigreeMetadata(duplicateProps));
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
    InfoMetadata info = new InfoMetadata(props, VcfWarnings.DEFAULT);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").addInfo(info).build();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getAltBases().add("T");
    position.getInfo().put("NS", "1");
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
    InfoMetadata info = new InfoMetadata("AF", "d", InfoType.Float, "A", null, null);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").addInfo(info).build();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getAltBases().add("T");
    position.getAltBases().add("C");
    position.getInfo().put("AF", ".");
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
    FormatMetadata ft = new FormatMetadata(ReservedFormatProperty.Filter.getId(), "d", "1", FormatType.String);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .addFormat(ft)
        .build();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getAltBases().add("T");
    position.getFormat().add("FT");
    VcfSample sample = new VcfSample(new LinkedHashMap<>());
    sample.putProperty("FT", "PASS;q10");

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
    FormatMetadata ft = new FormatMetadata(ReservedFormatProperty.Filter.getId(), "d", "1", FormatType.String);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .addFormat(ft)
        .build();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getAltBases().add("T");
    position.getFormat().add("FT");
    VcfSample sample = new VcfSample(new LinkedHashMap<>());
    sample.putProperty("FT", "PASS;;q10");

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
    FormatMetadata ps = new FormatMetadata(ReservedFormatProperty.PhaseSet.getId(), "d", "1", FormatType.Integer);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .addFormat(ps)
        .build();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getAltBases().add("T");
    position.getFormat().add("PS");
    VcfSample sample = new VcfSample(new LinkedHashMap<>());
    sample.putProperty("PS", "-5");

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
    InfoMetadata ac = new InfoMetadata(ReservedInfoProperty.AlleleCount.getId(), "d", InfoType.Integer, "A",
        null, null);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").addInfo(ac).build();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getAltBases().add("T");
    position.getInfo().put("AC", "-1");

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
    InfoMetadata cigar = new InfoMetadata(ReservedInfoProperty.Cigar.getId(), "d", InfoType.String, "A",
        null, null);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").addInfo(cigar).build();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getAltBases().add("T");
    position.getInfo().put("CIGAR", "123MYCIGAR");

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
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getAltBases().add("T");

    assertThrows(VcfFormatException.class,
        () -> writer.writeLine(metadata, position, Collections.emptyList()));
  }

  @Test
  public void testValidateBeforeWriteRejectsDuplicateHeader() throws Exception {
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();

    writer.writeHeader(metadata);
    assertThrows(VcfFormatException.class, () -> writer.writeHeader(metadata));
  }

  @Test
  public void testValidateBeforeWriteRejectsMetadataMismatchWithHeader() throws Exception {
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    VcfMetadata headerMetadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    VcfMetadata otherMetadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getAltBases().add("T");

    writer.writeHeader(headerMetadata);
    // a different (even if equivalent-looking) VcfMetadata object than the one used for the header would make the
    // emitted header and this record inconsistent if either were mutated independently afterward
    assertThrows(VcfFormatException.class,
        () -> writer.writeLine(otherMetadata, position, Collections.emptyList()));
  }

  @Test
  public void testValidateBeforeWriteRejectsMetadataMutatedAfterHeaderWritten() throws Exception {
    // "metadata != m_headerMetadata" alone only catches a swapped-in different object; it can't catch the SAME
    // object being mutated in place after writeHeader() already flushed the header text (e.g. addInfo() adding a
    // declaration the emitted header never had), which would silently make a data line reference a key the header
    // doesn't declare
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);

    metadata.addInfo(new InfoMetadata("NS", "d", InfoType.Integer, "1", null, null));

    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getAltBases().add("T");
    position.getInfo().put("NS", "1");

    assertThrows(VcfFormatException.class,
        () -> writer.writeLine(metadata, position, Collections.emptyList()));
  }

  @Test
  public void testNoSamples() throws Exception {
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    // ##INFO=<ID=NS,Number=1,Type=Integer,Description="Number of samples with data">
    InfoMetadata info = new InfoMetadata("NS", "Number of samples with data", InfoType.Integer, "1", null, null);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").addInfo(info).build();
    VcfPosition first = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    first.getAltBases().add("T");
    first.getInfo().put("NS", "0");
    first.getIds().add("rsa");
    VcfPosition second = new VcfPosition("chr1", 2, "A", new BigDecimal("0"), -1);
    second.getAltBases().add("T");
    second.getIds().add("rsb");
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
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);

    VcfPosition none = new VcfPosition("chr1", 1, null, "A", null, null, Collections.singletonList("."), null, null, -1);
    none.getAltBases().add("T");
    VcfPosition passed = new VcfPosition("chr1", 2, null, "A", null, null, Collections.singletonList("PASS"),
        null, null, -1);
    passed.getAltBases().add("T");
    VcfPosition failed = new VcfPosition("chr1", 3, null, "A", null, null, Collections.singletonList("q10"), null, null, -1);
    failed.getAltBases().add("T");

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
    InfoMetadata info = new InfoMetadata(InternalUtils.extractProperties(
        new String[]{"ID=NS", "Number=1", "Description=\"n\""}, VcfWarnings.DEFAULT), VcfWarnings.DEFAULT);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").addInfo(info).build();
    writer.writeHeader(metadata);
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getAltBases().add("T");
    position.getInfo().put("NS", "3");
    writer.writeLine(metadata, position, Collections.emptyList());
    assertTrue(sw.toString().contains("NS=3"));
  }

  @Test
  public void testWriteHeaderEscapesAndRoundTripsQuotedDescription() throws Exception {
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    InfoMetadata info = new InfoMetadata("NS", "a \"quoted\" description with a \\ backslash", InfoType.Integer, "1",
        null, null);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").addInfo(info).build();
    writer.writeHeader(metadata);
    String output = sw.toString();
    // the embedded quote and backslash must be escaped in the written line, not left to break the quoted string
    assertTrue(output.contains("Description=\"a \\\"quoted\\\" description with a \\\\ backslash\""), output);

    // round-trip: re-parsing the written header must decode back to the original, unescaped description
    try (BufferedReader reader = new BufferedReader(new StringReader(output));
         VcfParser parser = new VcfParser.Builder().fromReader(reader).parseWith((m, p, s) -> { }).build()) {
      VcfMetadata reparsed = parser.parseMetadata();
      assertEquals("a \"quoted\" description with a \\ backslash", reparsed.getInfo().get("NS").getDescription());
    }
  }

  @Test
  public void testWriteLineWithoutFormatMetadata() throws Exception {
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "SAMPLE1"))
        .build();
    writer.writeHeader(metadata);
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getAltBases().add("T");
    position.getFormat().add("GT");
    VcfSample sample = new VcfSample(new LinkedHashMap<>());
    sample.putProperty(ReservedFormatProperty.Genotype, "0/1");
    // FORMAT metadata is absent: this previously threw NullPointerException
    writer.writeLine(metadata, position, Collections.singletonList(sample));
    assertTrue(sw.toString().contains("\tGT\t0/1"));
  }

  @Test
  public void testWriteLineWithMissingSampleProperty() throws Exception {
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "SAMPLE1"))
        .build();
    writer.writeHeader(metadata);
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getAltBases().add("T");
    position.getFormat().add("GT");
    position.getFormat().add("DP");
    VcfSample sample = new VcfSample(new LinkedHashMap<>());
    sample.putProperty(ReservedFormatProperty.Genotype, "0/1"); // no DP property
    // sample has fewer properties than FORMAT: this previously threw NoSuchElementException
    writer.writeLine(metadata, position, Collections.singletonList(sample));
    assertTrue(sw.toString().contains("\tGT:DP\t0/1:."));
  }

  @Test
  public void testDefaultWriteSubstitutesEmptySampleValueWithDot() throws Exception {
    // regression test: the default path previously only substituted "." for a null sample property value, not a
    // non-null empty one (e.g. via the documented sample.putProperty("DP", "") live-mutation path) --
    // testValidateBeforeWriteNormalizesEmptySampleValue covers the diagnostics-mode case (via VcfSample.validate()),
    // this covers the default-path sibling EMPTY_FIELD_HANDLING.md's finding said was missing
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "SAMPLE1"))
        .build();
    writer.writeHeader(metadata);
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getAltBases().add("T");
    position.getFormat().add("GT");
    position.getFormat().add("DP");
    VcfSample sample = new VcfSample(new LinkedHashMap<>());
    sample.putProperty(ReservedFormatProperty.Genotype, "0/1");
    sample.putProperty("DP", "");

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
    InfoMetadata info = new InfoMetadata("AN", "d", InfoType.Integer, "1", null, null);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").addInfo(info).build();
    writer.writeHeader(metadata);
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getAltBases().add("T");
    position.getInfo().put("AN", "not-a-number"); // Type=Integer declared, but the value doesn't parse as one
    writer.writeLine(metadata, position, Collections.emptyList());
    assertTrue(sw.toString().contains("AN=not-a-number"));
  }

  @Test
  public void testWriteSamplePropertyNotMatchingDeclaredTypeWarnsInsteadOfThrowing() throws Exception {
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    FormatMetadata format = new FormatMetadata("DP", "d", "1", FormatType.Integer);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").addFormat(format)
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "SAMPLE1"))
        .build();
    writer.writeHeader(metadata);
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getAltBases().add("T");
    position.getFormat().add("DP");
    VcfSample sample = new VcfSample(new LinkedHashMap<>());
    sample.putProperty("DP", "not-a-number"); // Type=Integer declared, but the value doesn't parse as one
    writer.writeLine(metadata, position, Collections.singletonList(sample));
    assertTrue(sw.toString().contains("\tDP\tnot-a-number"));
  }

  @Test
  public void testWriteLineRejectsTooFewSamples() throws Exception {
    // header declares 2 samples, but only 1 is provided: the output would not parse back against its own header
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1", "S2"))
        .build();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getAltBases().add("T");
    position.getFormat().add("GT");
    VcfSample sample = new VcfSample(new LinkedHashMap<>());
    sample.putProperty(ReservedFormatProperty.Genotype, "0/1");
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
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .build();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getAltBases().add("T");
    position.getFormat().add("GT");
    VcfSample sample1 = new VcfSample(new LinkedHashMap<>());
    sample1.putProperty(ReservedFormatProperty.Genotype, "0/1");
    VcfSample sample2 = new VcfSample(new LinkedHashMap<>());
    sample2.putProperty(ReservedFormatProperty.Genotype, "1/1");
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
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getAltBases().add("T");
    position.getFormat().add("GT");
    VcfSample sample = new VcfSample(new LinkedHashMap<>());
    sample.putProperty(ReservedFormatProperty.Genotype, "0/1");
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
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .build();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getAltBases().add("T");
    // position.getFormat() is left empty
    VcfSample sample = new VcfSample(new LinkedHashMap<>());
    sample.putProperty(ReservedFormatProperty.Genotype, "0/1");
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
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .build();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getAltBases().add("T");
    VcfSample sample = new VcfSample(new LinkedHashMap<>());
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
    InfoMetadata info = new InfoMetadata("NS", "d", InfoType.Integer, "1", null, null);
    info.getPropertiesRaw().put("Description", "bad\rvalue");
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").addInfo(info).build();
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
    InfoMetadata info = new InfoMetadata("NS", "d", InfoType.Integer, "1", null, null);
    info.getPropertiesRaw().put("Description", "bad\rvalue");
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").addInfo(info).build();
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
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
        .addRawProperty("source", "myProgram")
        .build();
    // bypass addRawProperty's own checkNoLineTerminator to inject a bad second raw property, the same raw-view
    // bypass exercised by testWriteHeaderRejectsLoneCarriageReturnInjectedThroughRawView above
    metadata.getRawProperties().put("Bad", "bad\rvalue");
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
      VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
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
      VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
      metadata.getRawProperties().put("BadEncoding", "bad\uD800surrogate"); // lone high surrogate: invalid UTF-8
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
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    assertThrows(UncheckedIOException.class, () -> writer.writeHeader(metadata));
  }

  @Test
  public void testWriteHeaderInvalidatesWriterAndBlocksFurtherUse() throws Exception {
    FailingWriter failingWriter = new FailingWriter();
    failingWriter.startFailing();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(failingWriter)).build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();

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
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
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
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getAltBases().add("T");
    VcfSample badSample = new VcfSample(new java.util.ArrayList<>(), new java.util.ArrayList<>());
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
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
        .addInfo(new InfoMetadata("AF", "d", InfoType.Float, "A", null, null))
        .build();
    metadata.getInfo().get("AF").putPropertyRaw("ID", "MISMATCHED"); // now stored under key "AF" but own ID differs
    assertThrows(VcfFormatException.class, () -> writer.writeHeader(metadata));
    assertTrue(writer.isValid()); // content rejection, not a write failure -- must not invalidate

    metadata.getInfo().get("AF").putPropertyRaw("ID", "AF"); // fix the mismatch
    writer.writeHeader(metadata); // must succeed now, not silently no-op or throw "already called"
    assertTrue(sw.toString().contains("##INFO=<ID=AF"));
  }

  @Test
  public void testCloseDetectsErrorAndStillReleasesTheWriter() throws Exception {
    // close() must check for a write failure that happened after the last periodic check (or after
    // writeHeader(), if no data lines were written at all) AND still release the underlying resource even
    // though it's about to throw
    FailingWriter failingWriter = new FailingWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(failingWriter)).build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
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
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
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
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    failingWriter.startFailing();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getAltBases().add("T");
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
    VcfMetadata.Builder mdBuilder = new VcfMetadata.Builder();
    mdBuilder.setFileFormat("VCFv4.2");
    mdBuilder.setColumns(List.of("#CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO"));
    VcfMetadata metadata = mdBuilder.build();
    VcfPosition position = new VcfPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);

    Path vcfFile = tempDir.resolve("out.vcf");
    VcfWriter writer = new VcfWriter.Builder()
        .toFile(vcfFile)
        .validateBeforeWrite()
        .warningsToFile()
        .build();
    writer.writeHeader(metadata);
    // an INFO key with no matching metadata declaration triggers a warning in validateBeforeWrite() mode
    position.getInfo().put("UNDECLARED", "1");
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
    VcfMetadata.Builder mdBuilder = new VcfMetadata.Builder();
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
      VcfPosition position = new VcfPosition("chr1", i, null, "A", List.of("T"), null, null, null, null, -1);
      position.getInfo().put("UNDECLARED" + i, "1");
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
    VcfMetadata.Builder mdBuilder = new VcfMetadata.Builder();
    mdBuilder.setFileFormat("VCFv4.2");
    mdBuilder.setColumns(List.of("#CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO"));
    VcfMetadata metadata = mdBuilder.build();
    VcfPosition position = new VcfPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);

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
      // an INFO key with no matching metadata declaration triggers a warning in validateBeforeWrite() mode
      position.getInfo().put("UNDECLARED", "1");
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

    VcfMetadata.Builder mdBuilder = new VcfMetadata.Builder();
    mdBuilder.setFileFormat("VCFv4.2");
    mdBuilder.setColumns(List.of("#CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO"));
    VcfMetadata writeMetadata = mdBuilder.build();
    VcfPosition position = new VcfPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);

    Path outFile = tempDir.resolve("out.vcf");
    Path writerWarnings = tempDir.resolve("writer.warn.log");
    VcfWriter writer = new VcfWriter.Builder()
        .toFile(outFile)
        .validateBeforeWrite()
        .warningsToFile(writerWarnings)
        .build();
    writer.writeHeader(writeMetadata);
    // an INFO key with no matching metadata declaration triggers a warning in validateBeforeWrite() mode
    position.getInfo().put("UNDECLARED", "1");
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

    VcfMetadata.Builder mdBuilder = new VcfMetadata.Builder();
    mdBuilder.setFileFormat("VCFv4.2");
    mdBuilder.setColumns(List.of("#CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO"));
    VcfMetadata writeMetadata = mdBuilder.build();
    VcfPosition position = new VcfPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);

    Path outFile = tempDir.resolve("out.vcf");
    VcfWriter writer = new VcfWriter.Builder()
        .toFile(outFile)
        .validateBeforeWrite()
        .warningsToFile(sharedWarnings)
        .build();
    writer.writeHeader(writeMetadata);
    position.getInfo().put("UNDECLARED", "1");
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
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    VcfFormatException e = assertThrows(VcfFormatException.class, () -> writer.writeHeader(metadata));
    assertTrue(e.getMessage().contains("writeHeader() was already called"));
  }

  @Test
  public void testWriteLineBeforeWriteHeaderThrowsInDefaultMode() throws Exception {
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    VcfFormatException e = assertThrows(VcfFormatException.class,
        () -> writer.writeLine(metadata, position, Collections.emptyList()));
    assertTrue(e.getMessage().contains("writeLine() was called before writeHeader()"));
  }

  @Test
  public void testWriteLineWithDifferentMetadataThrowsInDefaultMode() throws Exception {
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata headerMetadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    VcfMetadata differentMetadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    writer.writeHeader(headerMetadata);
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    VcfFormatException e = assertThrows(VcfFormatException.class,
        () -> writer.writeLine(differentMetadata, position, Collections.emptyList()));
    assertTrue(e.getMessage().contains("writeLine() was given different metadata than writeHeader() used"));
  }

  @Test
  public void testWriteLineDoesNotDetectInPlaceMetadataMutationInDefaultMode() throws Exception {
    // regression test: unlike the 3 checks above, detecting the SAME VcfMetadata object being mutated in place
    // after writeHeader() requires rebuilding the entire header text on every writeLine() call -- a real
    // per-record cost. This specific case is deliberately NOT promoted to the default fast path and remains
    // validateBeforeWrite()-only; this test pins that boundary so it isn't accidentally regressed later.
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    metadata.addInfo(new InfoMetadata("NS", "d", InfoType.Integer, "1", null, null));
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    writer.writeLine(metadata, position, Collections.emptyList());
    // no exception thrown -- the assertion is that this line completes normally
    // and the emitted header genuinely does not reflect the post-header mutation, since it happened too late to be
    // written -- this default-mode path doesn't detect or care
    assertFalse(sw.toString().contains("##INFO=<ID=NS"));
  }

  @Test
  public void testWarningsToFileCapturesLazyQualityGetterFiredDuringWriteLine(
      @org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").setColumns(List.of("#CHROM", "POS",
        "ID", "REF", "ALT", "QUAL", "FILTER", "INFO")).build();
    VcfPosition position = new VcfPosition("1", 100, "A", null, -1);
    position.setRawQuality("notanumber"); // never accessed yet -- writeLine() must be what fires the lazy parse
    Path outFile = tempDir.resolve("out.vcf");
    Path warningsFile = tempDir.resolve("warnings.log");
    try (VcfWriter writer = new VcfWriter.Builder().toFile(outFile).warningsToFile(warningsFile).build()) {
      writer.writeHeader(metadata);
      writer.writeLine(metadata, position, List.of());
    }
    assertTrue(java.nio.file.Files.readString(warningsFile).contains("is not a number"));
  }

  @Test
  public void testWarningsToFileCapturesLazyDuplicateFormatKeyWarningDuringWriteLine(
      @org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
        .addFormat(new FormatMetadata("DP", "depth", "1", FormatType.Integer))
        .setColumns(List.of("#CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .build();
    VcfPosition position = new VcfPosition("1", 100, null, "A", null, null, null, null, List.of("DP"), -1);
    VcfSample sample = new VcfSample(List.of("DP", "DP"), List.of("1", "2")); // duplicate key, never accessed yet
    Path outFile = tempDir.resolve("out.vcf");
    Path warningsFile = tempDir.resolve("warnings.log");
    try (VcfWriter writer = new VcfWriter.Builder().toFile(outFile).warningsToFile(warningsFile).build()) {
      writer.writeHeader(metadata);
      writer.writeLine(metadata, position, List.of(sample));
    }
    assertTrue(java.nio.file.Files.readString(warningsFile).contains("duplicate FORMAT key"));
  }

  @Test
  public void testWarningsToFileCapturesMetadataDeclarationWarningDuringWriteHeaderValidation(
      @org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
    // finding #7 of this plan's external review: the design says writeHeader() claims metadata declarations
    // before validating them, but the tests above only cover writeLine() -- this exercises the writeHeader()
    // side specifically. Build a valid ContigMetadata via the strict constructor, then mutate its raw URL
    // property directly (bypassing the constructor's own throw-on-malformed-URL check, the same way a caller
    // mutating a live view always can), so the warning is only reachable via validateBeforeWrite()'s
    // entry.validate() call inside writeHeader() -- not at construction time.
    org.pharmgkb.parser.vcf.model.ContigMetadata contig =
        new org.pharmgkb.parser.vcf.model.ContigMetadata("1", 1000L, "GRCh38", null, null, null, null);
    contig.getPropertiesRaw().put(org.pharmgkb.parser.vcf.model.ContigMetadata.URL, "not a url");
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
        .addContig(contig)
        .setColumns(List.of("#CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO"))
        .build();
    Path outFile = tempDir.resolve("out.vcf");
    Path warningsFile = tempDir.resolve("warnings.log");
    try (VcfWriter writer = new VcfWriter.Builder().toFile(outFile).warningsToFile(warningsFile)
        .validateBeforeWrite().build()) {
      writer.writeHeader(metadata);
    }
    assertTrue(java.nio.file.Files.readString(warningsFile).contains("is malformed"));
  }

  @Test
  public void testDefaultWriteHeaderClaimsMetadataDeclarationsEvenWithoutValidateBeforeWrite(
      @org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
    // finding #1 of this plan's second-round external review (blocking): writeHeader() must claim every
    // metadata declaration's warning sink unconditionally, not only inside validateBeforeWrite()'s own
    // validation. Metadata declarations have no lazy getters of their own today, so there's no
    // automatically-triggered warning to observe in the default path -- this demonstrates the claim happened
    // by manually calling validate() on the SAME object after a default-mode writeHeader() call and confirming
    // it now warns to the writer's own sink instead of the shared logger, proving setWarningsSink was called.
    org.pharmgkb.parser.vcf.model.ContigMetadata contig =
        new org.pharmgkb.parser.vcf.model.ContigMetadata("1", 1000L, "GRCh38", null, null, null, null);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
        .addContig(contig)
        .setColumns(List.of("#CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO"))
        .build();
    Path outFile = tempDir.resolve("out.vcf");
    Path warningsFile = tempDir.resolve("warnings.log");
    try (VcfWriter writer = new VcfWriter.Builder().toFile(outFile).warningsToFile(warningsFile).build()) {
      writer.writeHeader(metadata); // default mode -- no validateBeforeWrite()
      contig.getPropertiesRaw().put(org.pharmgkb.parser.vcf.model.ContigMetadata.URL, "not a url");
      contig.validate();
    }
    assertTrue(java.nio.file.Files.readString(warningsFile).contains("is malformed"));
  }

  @Test
  public void testSecondWriterReclaimsAnAlreadyClaimedObjectAndOverridesTheFirstsDestination(
      @org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
    // the design's Testing Plan requires proving a re-claim genuinely OVERRIDES a prior destination, not just
    // that a fresh object gets claimed once: the same ContigMetadata is claimed by two different VcfWriters in
    // sequence, and the warning fired after the second claim must reach only the second writer's destination.
    // Both writers stay open across the whole test, so a failed re-claim would be observable as a second
    // "is malformed" line in the FIRST writer's still-open warnings file rather than as a silent SLF4J
    // fallback (a closed VcfWarnings falls back to the shared logger, which would mask the failure).
    org.pharmgkb.parser.vcf.model.ContigMetadata contig =
        new org.pharmgkb.parser.vcf.model.ContigMetadata("1", 1000L, "GRCh38", null, null, null, null);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
        .addContig(contig)
        .setColumns(List.of("#CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO"))
        .build();
    Path firstWarningsFile = tempDir.resolve("first-warnings.log");
    Path secondWarningsFile = tempDir.resolve("second-warnings.log");
    try (VcfWriter firstWriter = new VcfWriter.Builder().toFile(tempDir.resolve("first.vcf"))
        .warningsToFile(firstWarningsFile).build()) {
      firstWriter.writeHeader(metadata); // claims contig for firstWriter
      contig.getPropertiesRaw().put(org.pharmgkb.parser.vcf.model.ContigMetadata.URL, "not a url");
      contig.validate(); // warning #1 -> firstWarningsFile

      try (VcfWriter secondWriter = new VcfWriter.Builder().toFile(tempDir.resolve("second.vcf"))
          .warningsToFile(secondWarningsFile).build()) {
        secondWriter.writeHeader(metadata); // re-claims the SAME contig for secondWriter
        contig.validate(); // warning #2 -> must go to secondWarningsFile, not firstWarningsFile
      }
    }
    assertEquals(1, countMalformedUrlWarnings(firstWarningsFile),
        "the first writer's destination must have received only the pre-re-claim warning");
    assertEquals(1, countMalformedUrlWarnings(secondWarningsFile),
        "the post-re-claim warning must have been routed to the second writer's destination");
  }

  private static long countMalformedUrlWarnings(Path warningsFile) throws IOException {
    return java.nio.file.Files.readAllLines(warningsFile).stream()
        .filter(line -> line.contains("is malformed"))
        .count();
  }

}
