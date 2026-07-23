package org.pharmgkb.parser.vcf;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.pharmgkb.common.util.PathUtils;
import org.pharmgkb.parser.vcf.model.IdDescriptionMetadata;
import org.pharmgkb.parser.vcf.model.ReservedFormatProperty;
import org.pharmgkb.parser.vcf.model.ReservedInfoProperty;
import org.pharmgkb.parser.vcf.model.VcfMetadata;
import org.pharmgkb.parser.vcf.model.VcfPosition;
import org.pharmgkb.parser.vcf.model.VcfSample;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;

/**
 * JUnit test case for {@link VcfParser}.
 *
 * @author Mark Woon
 */
public class VcfParserTest {

  /**
   * The char-based {@link VcfParser#toList} must behave exactly like {@code Pattern.split} with limit -1 (leading,
   * interior, and trailing empty fields are all kept; a string containing no delimiter yields a single-element list).
   * Callers are responsible for handling any empty entries in the result. Verify that differentially across the
   * delimiters used and a range of edge-case inputs.
   */
  @Test
  void testToListMatchesPatternSplit() {
    String[] inputs = {
        "", "a", "abc",
        "a,b", "a:b:c", "x;y;z", "p\tq\tr",
        "a,", "a,,", ",a", ",,a", "a,,b", ",a,",
        ",", ",,", ";", ":", "\t", "\t\t\t",
        "0/1:35,40:75", "GT:AD:DP", "0|1", "./.", "PASS",
    };
    for (char delim : new char[] { '\t', ':', ',', ';' }) {
      for (String input : inputs) {
        List<String> expected = Arrays.asList(Pattern.compile(String.valueOf(delim)).split(input, -1));
        assertEquals(expected, VcfParser.toList(delim, input),
            () -> "delim='" + delim + "' input='" + input + "'");
      }
    }
  }

  @Test
  void testBasic() throws IOException {
    try (BufferedReader reader = Files.newBufferedReader(PathUtils.getPathToResource("/basic.vcf"));
         VcfParser parser = new VcfParser.Builder()
             .fromReader(reader)
             .parseWith((metadata, position, sampleData) -> {
               assertEquals("chr1", position.getChromosome());
               assertEquals(5, position.getPosition());
               assertEquals(Arrays.asList("rsa", "rsb"), position.getIds());
               assertEquals("Aa", position.getRef());
               assertEquals(Arrays.asList("Tt", "Gg", "Cc"), position.getAltBases());
               assertEquals(new BigDecimal("5.2e-10"), position.getQuality());
               assertTrue(position.getFilters().isEmpty());
               assertTrue(position.getInfo().isEmpty());
             })
             .build()) {
      parser.parse();
    }
  }

  @Test
  void testHeader()  {
    VcfFormatException ex = assertThrows(VcfFormatException.class, () -> {
      try (BufferedReader reader = Files.newBufferedReader(PathUtils.getPathToResource("/bad_header.vcf"));
           VcfParser parser = new VcfParser.Builder()
               .fromReader(reader)
               .parseWith((metadata, position, sampleData) -> {
                 fail("Should not reach here");
               })
               .build()) {
        parser.parse();
      }
    });
    assertEquals(2, ex.getLineNumber());
    assertThat(ex.getMessage(), containsString("Header line"));
    assertThat(ex.getMessage(), containsString("mandatory (tab-delimited) columns"));
  }

  @Test
  void testBadData()  {
    VcfFormatException ex = assertThrows(VcfFormatException.class, () -> {
      try (BufferedReader reader = Files.newBufferedReader(PathUtils.getPathToResource("/bad_data.vcf"));
           VcfParser parser = new VcfParser.Builder()
               .fromReader(reader)
               .parseWith((metadata, position, sampleData) -> {
                 fail("Should not reach here");
               })
               .build()) {
        parser.parse();
      }
    });
    assertEquals(3, ex.getLineNumber());
    assertThat(ex.getMessage(), containsString("Data line"));
    assertThat(ex.getMessage(), containsString("expected number of columns"));
  }


  @Test
  void testCommentAfterHeaderRejected() throws IOException {
    // VCF has no comment syntax; a "#"-prefixed line after the column header must be rejected, not silently skipped
    try (BufferedReader reader = Files.newBufferedReader(PathUtils.getPathToResource("/has_comment.vcf"));
         VcfParser parser = new VcfParser.Builder()
             .fromReader(reader)
             .parseWith((metadata, position, sampleData) -> {
               assertEquals("chr1", position.getChromosome());
               assertEquals(5, position.getPosition());
               assertEquals(Arrays.asList("rsa", "rsb"), position.getIds());
               assertEquals("Aa", position.getRef());
               assertEquals(Arrays.asList("Tt", "Gg", "Cc"), position.getAltBases());
               assertEquals(new BigDecimal("5.2e-10"), position.getQuality());
               assertTrue(position.getFilters().isEmpty());
               assertTrue(position.getInfo().isEmpty());
             })
             .build()) {
      assertThrows(VcfFormatException.class, parser::parse);
    }
  }

  @Test
  void testWithSamples() throws IOException {
    try (BufferedReader reader = Files.newBufferedReader(PathUtils.getPathToResource("/vcfposition.vcf"));
         VcfParser parser = new VcfParser.Builder()
             .fromReader(reader)
             .parseWith((metadata, position, sampleData) -> {

               switch ((int) position.getPosition()) {

                 case 1:
                   assertEquals("A", position.getInfo(ReservedInfoProperty.AncestralAllele));
                   assertEquals(true, position.getInfo(ReservedInfoProperty.Hapmap2)); // flag is set
                   assertNull(position.getInfo(ReservedInfoProperty.ThousandGenomes)); // property doesn't exist

                   List<BigDecimal> b = position.getInfo(ReservedInfoProperty.AlleleFrequency);
                   assertNotNull(b);
                   assertEquals(1, b.size());
                   assertEquals(new BigDecimal("0.124"), b.get(0));

                   List<Long> c = position.getInfo(ReservedInfoProperty.AlleleCount);
                   assertNotNull(c);
                   assertEquals(2, c.size());
                   assertEquals(5L, (long)c.get(0));
                   assertEquals(10L, (long)c.get(1));
                   break;

                 case 2:
                   VcfSample sample = sampleData.get(0);
                   List<BigDecimal> d = sample.getProperty(ReservedFormatProperty.GenotypePosteriorProbabilitiesPhredScaled);
                   assertNotNull(d);
                   assertEquals(2, d.size());
                   assertEquals(new BigDecimal("0.05"), d.get(0));
                   assertEquals(new BigDecimal("0.06"), d.get(1));
                   break;
               }
             })
             .build()) {
      parser.parse();
    }
  }


  @Test
  void testDuplicateFileFormatThrows() throws IOException {
    String vcf = "##fileformat=VCFv4.2\n##fileformat=VCFv4.1\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n";
    assertThrows(VcfFormatException.class, () -> parseMetadataOf(vcf));
  }

  @Test
  void testFileFormatNotFirstThrows() throws IOException {
    String vcf = "##INFO=<ID=NS,Number=1,Type=Integer,Description=\"n\">\n##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n";
    assertThrows(VcfFormatException.class, () -> parseMetadataOf(vcf));
  }

  @Test
  void testUnsupportedFileFormatVersionThrows() throws IOException {
    // below the 4.0 floor
    assertThrows(VcfFormatException.class,
        () -> parseMetadataOf("##fileformat=VCFv3.3\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n"));
    // malformed version
    assertThrows(VcfFormatException.class,
        () -> parseMetadataOf("##fileformat=VCFv4..2\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n"));
  }

  @Test
  void testBlankFirstLineThrows() throws IOException {
    String vcf = "\n##fileformat=VCFv4.2\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n";
    assertThrows(VcfFormatException.class, () -> parseMetadataOf(vcf));
  }

  @Test
  void testStrayLineBeforeHeaderThrows() throws IOException {
    String vcf = "##fileformat=VCFv4.2\nnot-metadata\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n";
    assertThrows(VcfFormatException.class, () -> parseMetadataOf(vcf));
  }

  @Test
  void testDuplicateSampleNameThrows() throws IOException {
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\ts1\ts1\n";
    assertThrows(VcfFormatException.class, () -> parseMetadataOf(vcf));
  }

  private static void parseMetadataOf(String vcf) throws IOException {
    try (BufferedReader reader = new BufferedReader(new StringReader(vcf));
         VcfParser parser = new VcfParser.Builder()
             .fromReader(reader)
             .parseWith((metadata, position, sampleData) -> { })
             .build()) {
      parser.parseMetadata();
    }
  }

  @Test
  void testMissingHeaderThrows() throws IOException {
    // metadata present but no "#CHROM" column header before EOF must be rejected (not silently yield zero records)
    String vcf = "##fileformat=VCFv4.2\n" +
        "chr1\t100\t.\tA\tT\t.\tPASS\t.\n";
    try (BufferedReader reader = new BufferedReader(new StringReader(vcf));
         VcfParser parser = new VcfParser.Builder()
             .fromReader(reader)
             .parseWith((metadata, position, sampleData) -> { })
             .build()) {
      assertThrows(VcfFormatException.class, parser::parseMetadata);
    }
  }

  @Test
  void testBadColumnNameThrows() throws IOException {
    // the 8 fixed columns must have their exact spec names in order
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tWRONG\n";
    try (BufferedReader reader = new BufferedReader(new StringReader(vcf));
         VcfParser parser = new VcfParser.Builder()
             .fromReader(reader)
             .parseWith((metadata, position, sampleData) -> { })
             .build()) {
      assertThrows(VcfFormatException.class, parser::parseMetadata);
    }
  }

  @Test
  void testBadFormatColumnThrows() throws IOException {
    // when sample columns are present, column 9 must be "FORMAT"
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tNOTFORMAT\tsample1\n";
    try (BufferedReader reader = new BufferedReader(new StringReader(vcf));
         VcfParser parser = new VcfParser.Builder()
             .fromReader(reader)
             .parseWith((metadata, position, sampleData) -> { })
             .build()) {
      assertThrows(VcfFormatException.class, parser::parseMetadata);
    }
  }

  @Test
  void testEmptyFixedFieldRejected() throws IOException {
    // an empty fixed field (here ALT) is invalid; the missing value must be "."
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "chr1\t100\t.\tA\t\t.\tPASS\t.\n";
    try (BufferedReader reader = new BufferedReader(new StringReader(vcf));
         VcfParser parser = new VcfParser.Builder()
             .fromReader(reader)
             .parseWith((metadata, position, sampleData) -> { })
             .build()) {
      assertThrows(VcfFormatException.class, parser::parse);
    }
  }

  @Test
  void testEmptyInfoRejected() throws IOException {
    // an empty INFO field is malformed (the missing value must be "."); the strict parser must reject it
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tsample1\n" +
        "chr1\t100\t.\tA\tT\t.\tPASS\t\tGT\t0/1\n";
    try (BufferedReader reader = new BufferedReader(new StringReader(vcf));
         VcfParser parser = new VcfParser.Builder()
             .fromReader(reader)
             .parseWith((metadata, position, sampleData) -> { })
             .build()) {
      assertThrows(VcfFormatException.class, parser::parse);
    }
  }

  @Test
  void testDroppedTrailingFormatFields() throws IOException {
    // per the VCF spec, trailing FORMAT sub-fields may be dropped; the sample "0/1" under FORMAT GT:DP:GQ must parse,
    // with the dropped fields read as the missing value rather than throwing
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tsample1\n" +
        "chr1\t100\t.\tA\tT\t.\tPASS\t.\tGT:DP:GQ\t0/1\n";
    List<VcfSample> captured = new ArrayList<>();
    try (BufferedReader reader = new BufferedReader(new StringReader(vcf));
         VcfParser parser = new VcfParser.Builder()
             .fromReader(reader)
             .parseWith((metadata, position, sampleData) -> captured.addAll(sampleData))
             .build()) {
      parser.parse();
    }
    assertEquals(1, captured.size());
    VcfSample sample = captured.get(0);
    assertEquals("0/1", sample.getProperty("GT"));
    assertEquals(".", sample.getProperty("DP"));
    assertEquals(".", sample.getProperty("GQ"));
  }

  @Test
  void testEmptySampleValueFilledWithDot() throws IOException {
    // unlike a genuinely dropped trailing sub-field (above), an explicit empty sub-field (interior or trailing) is
    // not allowed by VCF as a zero-length field; it's filled with the missing value '.' rather than thrown, since
    // dropping it (like ID/FILTER/ALT) would misalign the remaining values with their FORMAT keys
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tsample1\tsample2\n" +
        "chr1\t100\t.\tA\tT\t.\tPASS\t.\tGT:DP:GQ\t0/1::30\t0/1:\n";
    List<VcfSample> captured = new ArrayList<>();
    try (BufferedReader reader = new BufferedReader(new StringReader(vcf));
         VcfParser parser = new VcfParser.Builder()
             .fromReader(reader)
             .parseWith((metadata, position, sampleData) -> captured.addAll(sampleData))
             .build()) {
      parser.parse();
    }
    assertEquals(2, captured.size());
    VcfSample interior = captured.get(0);
    assertEquals("0/1", interior.getProperty("GT"));
    assertEquals(".", interior.getProperty("DP"));
    assertEquals("30", interior.getProperty("GQ"));
    VcfSample trailing = captured.get(1);
    assertEquals("0/1", trailing.getProperty("GT"));
    assertEquals(".", trailing.getProperty("DP"));
    assertEquals(".", trailing.getProperty("GQ"));
  }

  @Test
  void testGleSampleValueRoundTrips() throws IOException {
    String gle = "0:-75.22,1:-223.42,0/0:-323.03,1/0:-99.29,1/1:-802.53";
    String input = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tsample1\n" +
        "chr1\t100\t.\tA\tT\t.\tPASS\t.\tGT:GLE:DP\t0/1:" + gle + ":50\n";
    List<VcfPosition> positions = new ArrayList<>();
    List<VcfSample> samples = new ArrayList<>();
    VcfMetadata[] metadata = new VcfMetadata[1];
    try (VcfParser parser = new VcfParser.Builder()
        .fromReader(new BufferedReader(new StringReader(input)))
        .parseWith(new VcfLineParser() {
          @Override
          public void parseMetadata(VcfMetadata value) {
            metadata[0] = value;
          }

          @Override
          public void parseLine(VcfMetadata ignored, VcfPosition position, List<VcfSample> values) {
            positions.add(position);
            samples.addAll(values);
          }
        })
        .build()) {
      parser.parse();
    }
    assertEquals(gle, samples.get(0).getProperty("GLE"));
    assertEquals("50", samples.get(0).getProperty("DP"));

    StringWriter output = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(output)).build();
    writer.writeHeader(metadata[0]);
    writer.writeLine(metadata[0], positions.get(0), samples);

    List<VcfSample> reparsed = new ArrayList<>();
    try (VcfParser parser = new VcfParser.Builder()
        .fromReader(new BufferedReader(new StringReader(output.toString())))
        .parseWith((ignored, position, values) -> reparsed.addAll(values))
        .build()) {
      parser.parse();
    }
    assertEquals(gle, reparsed.get(0).getProperty("GLE"));
    assertEquals("50", reparsed.get(0).getProperty("DP"));
  }

  @Test
  void testIsModifiable() throws IOException {
    try (BufferedReader reader = Files.newBufferedReader(PathUtils.getPathToResource("/vcfposition.vcf"));
         VcfParser parser = new VcfParser.Builder()
             .fromReader(reader)
             .parseWith((metadata, position, sampleData) -> {
               position.setRef("test");
               position.getAltBases().add("test");
               position.getIds().add("test");
               position.getFormat().add("test");
               position.getFilters().add("none");
             })
             .build()) {
      parser.parse();
    }
  }

  @Test
  void testNoSamples() throws IOException {

    try (BufferedReader reader = Files.newBufferedReader(PathUtils.getPathToResource("/no_samples.vcf"));
         VcfParser parser = new VcfParser.Builder()
             .fromReader(reader)
             .parseWith((metadata, position, sampleData) -> {
               assertNotNull(position.getFormat());
               assertTrue(position.getFormat().isEmpty());
               assertTrue(sampleData.isEmpty());
               assertNotNull(position.getInfo());
               if (position.getPosition() == 1) {
                 assertEquals(1, position.getInfo().size());
                 assertEquals(Collections.singletonList("0"), position.getInfo().get("NS"));
               } else if (position.getPosition() == 2) {
                 assertTrue(position.getInfo().isEmpty());
               }
             })
             .build()) {
      parser.parse();
    }
  }

  @Test
  void testCnv() throws Exception {

    try (BufferedReader reader = Files.newBufferedReader(PathUtils.getPathToResource("/cnv.vcf"));
         VcfParser parser = new VcfParser.Builder()
             .fromReader(reader)
             .parseWith((metadata, position, sampleData) -> {
               for (String base : position.getAltBases()) {
                 assertTrue(base.startsWith("<CN"));
                 assertTrue(base.endsWith(">"));
               }
               assertEquals(metadata.getNumSamples(), sampleData.size());
             })
             .build()) {
      parser.parse();
      VcfMetadata vcfMetadata = parser.getMetadata();
      assertNotNull(vcfMetadata);
      IdDescriptionMetadata md1 = vcfMetadata.getAlt("CN0");
      assertNotNull(md1);
      IdDescriptionMetadata md2 = vcfMetadata.getAlt("<CN0>");
      assertNotNull(md2);
      assertEquals(md1, md2);

      assertEquals("HG00096", vcfMetadata.getSampleName(0));
      assertEquals("HG00099", vcfMetadata.getSampleName(2));
    }
  }


  @Test
  void testRsidOnly() throws Exception {

    VcfLineParser lineParser = (metadata, position, sampleData) -> {
      assertEquals(1, position.getIds().size());
      assertTrue(position.getIds().get(0).matches("rs\\d+"));
      assertEquals(metadata.getNumSamples(), sampleData.size());
    };

    // read from reader
    try (BufferedReader reader = Files.newBufferedReader(PathUtils.getPathToResource("/rsid.vcf"));
         VcfParser parser = new VcfParser.Builder()
             .fromReader(reader)
             .rsidsOnly()
             .parseWith(lineParser)
             .build()) {
      parser.parse();
    }

    // read from file
    Path dataFile = PathUtils.getPathToResource("/rsid.vcf");
    assertTrue(Files.exists(dataFile));
    try (VcfParser parser = new VcfParser.Builder().fromFile(dataFile).rsidsOnly().parseWith(lineParser).build()) {
      parser.parse();
    }
  }


  @Test
  void testFile() throws Exception {

    try (VcfParser ignored = new VcfParser.Builder()
        .fromFile(Paths.get("foo.txt"))
        .parseWith((metadata, position, sampleData) -> {})
        .build()) {
      fail("Didn't catch invalid path");
    } catch (IllegalArgumentException ex) {
      // expected
      assertTrue(ex.getMessage().contains("Not a VCF file"));
    }

    try (BufferedReader reader = Files.newBufferedReader(PathUtils.getPathToResource("/notvcf.vcf"));
         VcfParser parser = new VcfParser.Builder()
             .fromReader(reader)
             .parseWith((metadata, position, sampleData) -> {})
             .build()) {
      parser.parseMetadata();
      fail("Didn't catch invalid version");
    } catch (VcfFormatException ex) {
      // expected
      assertTrue(ex.getMessage().contains("Not a VCF file"));
    }
  }
}
