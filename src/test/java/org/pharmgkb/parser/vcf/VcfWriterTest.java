package org.pharmgkb.parser.vcf;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.pharmgkb.parser.vcf.model.BaseMetadata;
import org.pharmgkb.parser.vcf.model.FormatMetadata;
import org.pharmgkb.parser.vcf.model.FormatType;
import org.pharmgkb.parser.vcf.model.IdDescriptionMetadata;
import org.pharmgkb.parser.vcf.model.InfoMetadata;
import org.pharmgkb.parser.vcf.model.InfoType;
import org.pharmgkb.parser.vcf.model.ReservedFormatProperty;
import org.pharmgkb.parser.vcf.model.VcfMetadata;
import org.pharmgkb.parser.vcf.model.VcfPosition;
import org.pharmgkb.parser.vcf.model.VcfSample;
import org.pharmgkb.parser.vcf.spec.FailingWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"));
    position.getAltBases().add("T");
    position.setRef("");
    assertThrows(VcfFormatException.class,
        () -> writer.writeLine(metadata, position, Collections.emptyList()));
  }

  @Test
  public void testWriteLineDoesNotValidateByDefault() throws Exception {
    // getAltBases() is mutable and does not re-validate (by design, to support transformation pipelines); by
    // default, writeLine does not call position.validate() either, so a position mutated into an invalid state is
    // written without error
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"));
    position.getAltBases().add("not-a-valid-base");
    writer.writeLine(metadata, position, Collections.emptyList());
    assertTrue(sw.toString().contains("not-a-valid-base"));
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
        .addFilter(new IdDescriptionMetadata("STD_FILTER", "Standard filter"))
        .build();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"));
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
  public void testWriteLineValidatesWhenBuiltWithValidateBeforeWrite() throws Exception {
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"));
    position.getAltBases().add("not-a-valid-base");
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
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"));
    position.getAltBases().add("T");
    position.getFormat().add("DP");
    VcfSample sample = new VcfSample(new LinkedHashMap<>());
    sample.putProperty("DP", "1");
    sample.propertyEntrySet().iterator().next().setValue("1:2");

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
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"));
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
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"));
    position.getAltBases().add("T");
    position.getFormat().add("GT");
    VcfSample sample = new VcfSample(new LinkedHashMap<>());
    sample.putProperty("GT", "0/1");
    sample.putProperty("DP", "10");

    assertThrows(VcfFormatException.class,
        () -> writer.writeLine(metadata, position, Collections.singletonList(sample)));
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
    InfoMetadata info = new InfoMetadata(props);
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
    IdDescriptionMetadata alt = new IdDescriptionMetadata("DEL", "Deletion");
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
        .addPedigree(new BaseMetadata(childProps))
        .build();
    metadata.getPedigrees().add(new BaseMetadata(duplicateProps));
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
    InfoMetadata info = new InfoMetadata("NS", "d", InfoType.Integer, "999999999999999999999", null, null);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").addInfo(info).build();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"));
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
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"));
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
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"));
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
    // unlike the typed read accessor (VcfUtils.convertProperty), which drops an empty FT code, this writer
    // preserves raw sample content rather than rewriting it -- the same "read normalizes, write preserves"
    // split already used for a reserved list-typed FORMAT value's empty entry
    FormatMetadata ft = new FormatMetadata(ReservedFormatProperty.Filter.getId(), "d", "1", FormatType.String);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .addFormat(ft)
        .build();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"));
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
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"));
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
  public void testValidateBeforeWriteRejectsLineBeforeHeader() throws Exception {
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"));
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
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"));
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

    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"));
    position.getAltBases().add("T");
    position.getInfo().put("NS", "1");

    assertThrows(VcfFormatException.class,
        () -> writer.writeLine(metadata, position, Collections.emptyList()));
  }

  @Test
  public void testSpecialNumberCardinality() {
    assertEquals(2L, VcfWriter.getExpectedCardinality("A", 2, 2));
    assertEquals(3L, VcfWriter.getExpectedCardinality("R", 2, 2));
    assertEquals(6L, VcfWriter.getExpectedCardinality("G", 2, 2));
    assertEquals(10L, VcfWriter.getExpectedCardinality("G", 2, 3));
  }

  @Test
  public void testGCardinalityOverflowSkipsValidationInsteadOfSentinelValue() {
    // an astronomically large ploidy/allele-count combination overflows long; this must return null (skip
    // validation), consistent with how the numeric-Number branch already skips validation when Number is too large
    // to parse, rather than returning Long.MAX_VALUE as a bogus expected count
    assertNull(VcfWriter.getExpectedCardinality("G", 1000, 1000));
  }

  @Test
  public void testGCardinalityWithUnknownPloidySkipsValidation() {
    // ploidy=null means it could not be determined (e.g. GT is absent for this sample); previously getPloidy()
    // defaulted to 2 in that case, which could produce a false Number=G cardinality warning for a haploid, or
    // higher-ploidy, sample. There's nothing valid to compare against, so this must skip rather than guess.
    // validateInfoValue also now passes null here for the same reason: an INFO value isn't tied to any one
    // sample's ploidy at all (previously hardcoded to 2, which would have falsely flagged a correctly-formed
    // haploid record's Number=G INFO field -- e.g. combinationsWithRepetition(2 alleles, 1 ploidy) = 2 actual
    // values, but the hardcoded ploidy=2 assumption expects combinationsWithRepetition(2, 2) = 3).
    assertNull(VcfWriter.getExpectedCardinality("G", 2, null));
    // Number=A/R/an explicit integer don't depend on ploidy at all, so they must still validate normally even when
    // ploidy is unknown
    assertEquals(2L, VcfWriter.getExpectedCardinality("A", 2, null));
    assertEquals(3L, VcfWriter.getExpectedCardinality("R", 2, null));
    assertEquals(5L, VcfWriter.getExpectedCardinality("5", 2, null));
  }

  @Test
  public void testNoSamples() throws Exception {
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    // ##INFO=<ID=NS,Number=1,Type=Integer,Description="Number of samples with data">
    InfoMetadata info = new InfoMetadata("NS", "Number of samples with data", InfoType.Integer, "1", null, null);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").addInfo(info).build();
    VcfPosition first = new VcfPosition("chr1", 1, "A", new BigDecimal("0"));
    first.getAltBases().add("T");
    first.getInfo().put("NS", "0");
    first.getIds().add("rsa");
    VcfPosition second = new VcfPosition("chr1", 2, "A", new BigDecimal("0"));
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

    VcfPosition none = new VcfPosition("chr1", 1, null, "A", null, null, Collections.singletonList("."), null, null);
    none.getAltBases().add("T");
    VcfPosition passed = new VcfPosition("chr1", 2, null, "A", null, null, Collections.singletonList("PASS"),
        null, null);
    passed.getAltBases().add("T");
    VcfPosition failed = new VcfPosition("chr1", 3, null, "A", null, null, Collections.singletonList("q10"), null, null);
    failed.getAltBases().add("T");

    writer.writeLine(metadata, none, Collections.emptyList());
    writer.writeLine(metadata, passed, Collections.emptyList());
    writer.writeLine(metadata, failed, Collections.emptyList());

    String[] lines = sw.toString().split("\n");
    assertEquals(".", lines[0].split("\t")[6]);    // NONE -> missing value, not PASS
    assertEquals("PASS", lines[1].split("\t")[6]); // PASSED
    assertEquals("q10", lines[2].split("\t")[6]);  // FAILED
  }

  @Test
  public void testWriteInfoWithoutType() throws Exception {
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    // INFO metadata with no Type -> getType() is null; the writer must not NPE
    InfoMetadata info = new InfoMetadata(VcfUtils.extractProperties("ID=NS", "Number=1", "Description=\"n\""));
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").addInfo(info).build();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"));
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
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"));
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
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"));
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
  public void testWriteInfoValueNotMatchingDeclaredTypeWarnsInsteadOfThrowing() throws Exception {
    // VcfUtils.convertProperty/convertElement only ever throw VcfFormatException for a bad value, never
    // IllegalArgumentException; addInfoOrDot's "just test" conversion caught IllegalArgumentException, so this
    // previously propagated out of writeLine uncaught instead of just logging a warning
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    InfoMetadata info = new InfoMetadata("AN", "d", InfoType.Integer, "1", null, null);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").addInfo(info).build();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"));
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
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"));
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
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"));
    position.getAltBases().add("T");
    position.getFormat().add("GT");
    VcfSample sample = new VcfSample(new LinkedHashMap<>());
    sample.putProperty(ReservedFormatProperty.Genotype, "0/1");
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    assertThrows(VcfFormatException.class,
        () -> writer.writeLine(metadata, position, Collections.singletonList(sample)));
  }

  @Test
  public void testWriteLineRejectsTooManySamples() throws Exception {
    // header declares 1 sample, but 2 are provided
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .build();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"));
    position.getAltBases().add("T");
    position.getFormat().add("GT");
    VcfSample sample1 = new VcfSample(new LinkedHashMap<>());
    sample1.putProperty(ReservedFormatProperty.Genotype, "0/1");
    VcfSample sample2 = new VcfSample(new LinkedHashMap<>());
    sample2.putProperty(ReservedFormatProperty.Genotype, "1/1");
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    assertThrows(VcfFormatException.class,
        () -> writer.writeLine(metadata, position, Arrays.asList(sample1, sample2)));
  }

  @Test
  public void testWriteLineRejectsSampleDataWhenHeaderHasNoSamples() throws Exception {
    // header declares no samples (no setColumns call), but FORMAT/sample data is provided anyway
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"));
    position.getAltBases().add("T");
    position.getFormat().add("GT");
    VcfSample sample = new VcfSample(new LinkedHashMap<>());
    sample.putProperty(ReservedFormatProperty.Genotype, "0/1");
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    assertThrows(VcfFormatException.class,
        () -> writer.writeLine(metadata, position, Collections.singletonList(sample)));
  }

  @Test
  public void testWriteLineWritesDotFormatAndSampleWhenFormatEmpty() throws Exception {
    // FORMAT="." (no per-sample format fields) is valid when sample columns are declared -- see
    // VcfPosition.checkFormat's javadoc for the bcftools 1.23.1-verified rationale. A sample's own properties are
    // warned about and dropped, since there is no FORMAT key left for them to correspond to.
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .build();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"));
    position.getAltBases().add("T");
    // position.getFormat() is left empty
    VcfSample sample = new VcfSample(new LinkedHashMap<>());
    sample.putProperty(ReservedFormatProperty.Genotype, "0/1");
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    writer.writeLine(metadata, position, Collections.singletonList(sample));
    assertTrue(sw.toString().endsWith("\tPASS\t.\t.\t.\n"));
  }

  @Test
  public void testWriteLineValidateBeforeWriteAllowsEmptyFormatWithEmptySample() throws Exception {
    // the clean case: FORMAT is empty and the sample has no properties either, so no warning path is exercised;
    // this must also work in validateBeforeWrite() mode, not just the default fast path
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1"))
        .build();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"));
    position.getAltBases().add("T");
    VcfSample sample = new VcfSample(new LinkedHashMap<>());
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    writer.writeHeader(metadata);
    writer.writeLine(metadata, position, Collections.singletonList(sample));
    assertTrue(sw.toString().endsWith("\tPASS\t.\t.\t.\n"));
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
  public void testWriteLineContentRejectionDoesNotInvalidateWriter() throws Exception {
    // a VcfFormatException content rejection (not a write/IO failure) must not invalidate the writer -- a
    // corrected call afterward must succeed normally
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"));
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
        .addInfo(new InfoMetadata("AF", "d", InfoType.String, "1", null, null))
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
  public void testPeriodicCheckDetectsErrorDuringWriteLineWithoutClose() throws Exception {
    // writeLine() no longer flushes/checks on every call (Task 1 removes that); this proves the periodic
    // (every-1000-line) check inside printLine() still catches a failure introduced mid-stream, rather than
    // only close() ever detecting it
    FailingWriter failingWriter = new FailingWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(failingWriter)).build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    writer.writeHeader(metadata);
    failingWriter.startFailing();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"));
    position.getAltBases().add("T");
    assertThrows(UncheckedIOException.class, () -> {
      for (int i = 0; i < 1000; i++) {
        writer.writeLine(metadata, position, Collections.emptyList());
      }
    });
  }

}
