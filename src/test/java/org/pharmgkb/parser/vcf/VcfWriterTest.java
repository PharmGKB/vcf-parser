package org.pharmgkb.parser.vcf;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import org.pharmgkb.parser.vcf.model.FormatMetadata;
import org.pharmgkb.parser.vcf.model.FormatType;
import org.pharmgkb.parser.vcf.model.InfoMetadata;
import org.pharmgkb.parser.vcf.model.InfoType;
import org.pharmgkb.parser.vcf.model.ReservedFormatProperty;
import org.pharmgkb.parser.vcf.model.VcfMetadata;
import org.pharmgkb.parser.vcf.model.VcfPosition;
import org.pharmgkb.parser.vcf.model.VcfSample;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
  public void testValidateBeforeWriteRejectsEmptySampleValue() throws Exception {
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

    assertThrows(VcfFormatException.class,
        () -> writer.writeLine(metadata, position, Collections.singletonList(sample)));
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
  public void testValidateBeforeWriteHandlesOversizedNumberAsDiagnostic() throws Exception {
    InfoMetadata info = new InfoMetadata("NS", "d", InfoType.Integer, "999999999999999999999", null, null);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").addInfo(info).build();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"));
    position.getAltBases().add("T");
    position.getInfo().put("NS", "1");
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();

    writer.writeLine(metadata, position, Collections.emptyList());
    assertTrue(sw.toString().contains("NS=1"));
  }

  @Test
  public void testSpecialNumberCardinality() {
    assertEquals(2L, VcfWriter.getExpectedCardinality("A", 2, 2));
    assertEquals(3L, VcfWriter.getExpectedCardinality("R", 2, 2));
    assertEquals(6L, VcfWriter.getExpectedCardinality("G", 2, 2));
    assertEquals(10L, VcfWriter.getExpectedCardinality("G", 2, 3));
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
  public void testWriteLineRejectsSampleDataWithoutFormat() throws Exception {
    // header declares 1 sample, and one is provided, but position.getFormat() was never populated: without a
    // FORMAT, addFormatConditionally/addSampleConditionally previously wrote nothing at all for these columns,
    // silently producing a line with fewer columns than the header declares and discarding the sample's properties
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
    assertThrows(VcfFormatException.class,
        () -> writer.writeLine(metadata, position, Collections.singletonList(sample)));
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
    assertThrows(RuntimeException.class, () -> writer.writeHeader(metadata));
  }

}
