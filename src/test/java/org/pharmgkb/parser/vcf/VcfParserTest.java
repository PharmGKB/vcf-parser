package org.pharmgkb.parser.vcf;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pharmgkb.common.util.PathUtils;

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
             .parseWith((metadata, record) -> {
               VcfPosition position = record.getPosition();
               assertEquals("chr1", position.getChromosome());
               assertEquals(5, position.getPosition());
               assertEquals(Arrays.asList("rsa", "rsb"), position.getIds());
               assertEquals("Aa", position.getRef());
               assertEquals(Arrays.asList("Tt", "Gg", "Cc"), position.getAltBases());
               assertEquals(new BigDecimal("5.2e-10"), position.getQuality());
               assertEquals(Arrays.asList("PASS"), position.getFilters());
               assertTrue(position.getInfo().isEmpty());
             })
             .build()) {
      parser.parse();
    }
  }

  @Test
  void testDeferredInfoValidationExceptionCarriesLineNumberAfterParseReturns() throws IOException {
    // regression test: a VcfFormatException from VcfPosition.getInfo()'s
    // lazy-parse path, thrown AFTER parser.parse() has already returned (not from inside the parseWith callback),
    // must still carry the correct originating line number -- this was previously silently lost, since
    // VcfParser's own line-tracking call stack is gone by the time a caller inspects a collected position later
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "chr1\t1\t.\tA\tT\t.\t.\t.;DP=1\n";
    List<VcfPosition> positions = new ArrayList<>();
    try (VcfParser parser = new VcfParser.Builder()
        .fromReader(new BufferedReader(new StringReader(vcf)))
        .parseWith((metadata, record) -> positions.add(record.getPosition()))
        .build()) {
      parser.parse();
    }
    assertEquals(1, positions.size());
    VcfFormatException ex = assertThrows(VcfFormatException.class, () -> positions.get(0).getInfo());
    assertEquals(3, ex.getLineNumber());
    assertTrue(ex.getMessage().contains("[Line #3]"));
  }

  @Test
  void testDeferredReservedPropertyConversionExceptionCarriesLineNumberAfterParseReturns() throws IOException {
    // regression test: VcfPosition.getInfo(ReservedInfoProperty)'s call to InternalUtils.convertProperty(...) is a
    // fourth deferred throw site distinct from getInfo()'s lazy-parse path covered above -- DP (Depth) is declared
    // as a Long-typed reserved property, so a non-numeric raw value fails type conversion only when this typed
    // accessor is used, after parser.parse() has already returned.
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "chr1\t1\t.\tA\tT\t.\t.\tDP=abc\n";
    List<VcfPosition> positions = new ArrayList<>();
    try (VcfParser parser = new VcfParser.Builder()
        .fromReader(new BufferedReader(new StringReader(vcf)))
        .parseWith((metadata, record) -> positions.add(record.getPosition()))
        .build()) {
      parser.parse();
    }
    assertEquals(1, positions.size());
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> positions.get(0).getInfo(ReservedInfoProperty.Depth));
    assertEquals(3, ex.getLineNumber());
    assertTrue(ex.getMessage().contains("[Line #3]"));
  }

  @Test
  void testHeader()  {
    VcfFormatException ex = assertThrows(VcfFormatException.class, () -> {
      try (BufferedReader reader = Files.newBufferedReader(PathUtils.getPathToResource("/bad_header.vcf"));
           VcfParser parser = new VcfParser.Builder()
               .fromReader(reader)
               .parseWith((metadata, record) -> {
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
               .parseWith((metadata, record) -> {
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
             .parseWith((metadata, record) -> {
               VcfPosition position = record.getPosition();
               assertEquals("chr1", position.getChromosome());
               assertEquals(5, position.getPosition());
               assertEquals(Arrays.asList("rsa", "rsb"), position.getIds());
               assertEquals("Aa", position.getRef());
               assertEquals(Arrays.asList("Tt", "Gg", "Cc"), position.getAltBases());
               assertEquals(new BigDecimal("5.2e-10"), position.getQuality());
               assertEquals(Arrays.asList("PASS"), position.getFilters());
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
             .parseWith((metadata, record) -> {
               VcfPosition position = record.getPosition();
               List<VcfSample> sampleData = record.getSamples();

               switch ((int) position.getPosition()) {

                 case 1:
                   assertEquals("A", position.getInfo(ReservedInfoProperty.AncestralAllele));
                   assertEquals(true, position.getInfo(ReservedInfoProperty.Hapmap2)); // flag is set
                   assertNull(position.getInfo(ReservedInfoProperty.ThousandGenomes)); // property doesn't exist

                   List<VcfFloat> b = position.getInfo(ReservedInfoProperty.AlleleFrequency);
                   assertNotNull(b);
                   assertEquals(1, b.size());
                   assertEquals(VcfFloat.of(new BigDecimal("0.124")), b.get(0));

                   List<Long> c = position.getInfo(ReservedInfoProperty.AlleleCount);
                   assertNotNull(c);
                   assertEquals(2, c.size());
                   assertEquals(5L, (long)c.get(0));
                   assertEquals(10L, (long)c.get(1));
                   break;

                 case 2:
                   VcfSample sample = sampleData.get(0);
                   List<VcfFloat> d = sample.getProperty(ReservedFormatProperty.GenotypePosterior);
                   assertNotNull(d);
                   assertEquals(2, d.size());
                   assertEquals(VcfFloat.of(new BigDecimal("0.05")), d.get(0));
                   assertEquals(VcfFloat.of(new BigDecimal("0.06")), d.get(1));
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
    // only VCF 4.x input is supported
    assertDoesNotThrow(
        () -> parseMetadataOf("##fileformat=VCFv4.99\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n"));
    assertThrows(VcfFormatException.class,
        () -> parseMetadataOf("##fileformat=VCFv3.3\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n"));
    assertThrows(VcfFormatException.class,
        () -> parseMetadataOf("##fileformat=VCFv5.0\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n"));
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
  void testMetadataLineMissingEqualsThrowsClearMessage() throws IOException {
    // a "##" line with no "=" previously threw a raw StringIndexOutOfBoundsException-derived message (from
    // line.substring(2, -1)) instead of one naming the actual problem
    String vcf = "##fileformat=VCFv4.2\n##justsometext\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n";
    VcfFormatException e = assertThrows(VcfFormatException.class, () -> parseMetadataOf(vcf));
    assertThat(e.getMessage(), containsString("missing '='"));
  }

  @Test
  void testDuplicateSampleNameThrows() throws IOException {
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\ts1\ts1\n";
    assertThrows(VcfFormatException.class, () -> parseMetadataOf(vcf));
  }

  @Test
  void testFormatWithNoSamplesThrows() throws IOException {
    // reproduces test/vcf/{4.1,4.2}/failed/failed_header_001.vcf: FORMAT is declared as column 9 but no sample
    // columns follow it. Confirmed bcftools 1.23.1 hard-errors ("Could not parse the #CHROM.. line, either
    // FORMAT is missing or spaces are present instead of tabs", exit code 255) and vcf_validator 0.10.2 reports
    // the file invalid -- FORMAT only has meaning relative to sample columns, so this is a structural violation.
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\n";
    assertThrows(VcfFormatException.class, () -> parseMetadataOf(vcf));
  }

  @Test
  void testEmptySampleNameThrows() throws IOException {
    // a trailing tab after FORMAT produces a well-formed sample count (bypassing testFormatWithNoSamplesThrows'
    // check) but an empty-named sample column. Confirmed bcftools 1.23.1 hard-errors ("Empty sample name:
    // trailing spaces/tabs in the header line?") and vcf_validator 0.10.2 reports the file invalid
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\t\n";
    assertThrows(VcfFormatException.class, () -> parseMetadataOf(vcf));
  }

  @Test
  void testEmptySampleNameInMiddleThrows() throws IOException {
    // same underlying problem as testEmptySampleNameThrows, but the empty name is between two real sample names
    // rather than trailing
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tS1\t\tS2\n";
    assertThrows(VcfFormatException.class, () -> parseMetadataOf(vcf));
  }

  private static void parseMetadataOf(String vcf) throws IOException {
    try (BufferedReader reader = new BufferedReader(new StringReader(vcf));
         VcfParser parser = new VcfParser.Builder()
             .fromReader(reader)
             .parseWith((metadata, record) -> { })
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
             .parseWith((metadata, record) -> { })
             .build()) {
      assertThrows(VcfFormatException.class, parser::parseMetadata);
    }
  }

  @Test
  void testMissingHeaderLeavesMetadataNullAndAllowsRetry() throws IOException {
    // a file that runs out of lines entirely right after ##fileformat (not even a stray non-metadata line)
    // reaches VcfParser.parseMetadata()'s "m_vcfMetadata = mdBuilder.build()" line before the "!foundHeader"
    // check fires. getMetadata() must stay null after the throw (not a structurally-broken non-null object with
    // getNumColumns() == 0), and a retry must not be blocked by the "already parsed" guard.
    String vcf = "##fileformat=VCFv4.2\n";
    try (BufferedReader reader = new BufferedReader(new StringReader(vcf));
         VcfParser parser = new VcfParser.Builder()
             .fromReader(reader)
             .parseWith((metadata, record) -> { })
             .build()) {
      assertThrows(VcfFormatException.class, parser::parseMetadata);
      assertNull(parser.getMetadata());
      // retry must not throw IllegalStateException("Metadata has already been parsed.") -- it throws a
      // VcfFormatException instead, since the underlying reader is already exhausted, but must not be blocked
      // by the false "already parsed" state
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
             .parseWith((metadata, record) -> { })
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
             .parseWith((metadata, record) -> { })
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
             .parseWith((metadata, record) -> { })
             .build()) {
      assertThrows(VcfFormatException.class, parser::parse);
    }
  }

  @Test
  void testAllTabDataLineReportsSpecificEmptyField() throws IOException {
    // a data line consisting only of tab characters used to be caught by the generic "Empty line" check
    // (StringUtils.stripToNull treats it as blank) before ever reaching the more specific per-field check --
    // now it's recognized as splitting into the expected number of (empty) columns and correctly reports
    // which specific field is missing, matching how any other data line with an empty CHROM is reported
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "\t\t\t\t\t\t\t\n";
    VcfFormatException ex = assertThrows(VcfFormatException.class, () -> {
      try (BufferedReader reader = new BufferedReader(new StringReader(vcf));
           VcfParser parser = new VcfParser.Builder()
               .fromReader(reader)
               .parseWith((metadata, record) -> fail("Should not reach here"))
               .build()) {
        parser.parse();
      }
    });
    assertThat(ex.getMessage(), containsString("CHROM field is empty"));
  }

  @Test
  void testBlankDataLineStillReportsEmptyLine() throws IOException {
    // a genuinely blank line (no tab at all) is still reported as "Empty line" -- only an all-tab line that
    // splits into the expected number of columns is treated as a set of empty fields instead (see
    // testAllTabDataLineReportsSpecificEmptyField)
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "   \n";
    VcfFormatException ex = assertThrows(VcfFormatException.class, () -> {
      try (BufferedReader reader = new BufferedReader(new StringReader(vcf));
           VcfParser parser = new VcfParser.Builder()
               .fromReader(reader)
               .parseWith((metadata, record) -> fail("Should not reach here"))
               .build()) {
        parser.parse();
      }
    });
    assertThat(ex.getMessage(), containsString("Empty line"));
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
             .parseWith((metadata, record) -> { })
             .build()) {
      assertThrows(VcfFormatException.class, parser::parse);
    }
  }

  @Test
  void testMissingIdInfoDeclarationDroppedWithoutAbortingParse() throws IOException {
    String vcf = "##fileformat=VCFv4.2\n" +
        "##INFO=<Number=1,Type=String,Description=\"no id\">\n" +
        "##INFO=<ID=DP,Number=1,Type=Integer,Description=\"has id\">\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "1\t1\t.\tA\tT\t.\t.\tDP=10\n";
    VcfMetadata[] captured = new VcfMetadata[1];
    try (BufferedReader reader = new BufferedReader(new StringReader(vcf));
         VcfParser parser = new VcfParser.Builder()
             .fromReader(reader)
             .parseWith((metadata, record) -> captured[0] = metadata)
             .build()) {
      parser.parse();
    }
    assertEquals(1, captured[0].getInfo().size());
    assertTrue(captured[0].getInfo().containsKey("DP"));
  }

  /**
   * Parses {@code resourceName} and returns its {@link VcfMetadata}, without requiring a {@code parseWith}
   * callback that inspects each data line -- {@link VcfParser#parseMetadata()} stops right after the header.
   */
  private static VcfMetadata parseResource(String resourceName) throws IOException {
    try (BufferedReader reader = Files.newBufferedReader(PathUtils.getPathToResource(resourceName));
         VcfParser parser = new VcfParser.Builder()
             .fromReader(reader)
             .parseWith((metadata, record) -> { })
             .build()) {
      return parser.parseMetadata();
    }
  }

  @Test
  void testMetaDeclarationsAreParsedAndRoundTrip() throws Exception {
    // metadata_meta.vcf's four ##META lines are the spec's own worked example (VCFv4.3.tex:273-276), copied
    // verbatim including its Type-before-Number attribute order -- see the fixture's own ##Note= line for why
    // that order is deliberately not attribute-order checked.
    VcfMetadata md = parseResource("/metadata_meta.vcf");
    assertEquals("[WholeGenome, Exome]", md.getMetas().get("Assay").getPropertyRaw("Values"));
    assertEquals("[Blood, Breast, Colon, Lung, ?]", md.getMetas().get("Tissue").getPropertyRaw("Values"));
    // breaking change: ##META no longer appears as a raw property
    assertTrue(md.getRawProperties().get("META").isEmpty());
    StringWriter out = new StringWriter();
    try (VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(out)).build()) {
      writer.writeHeader(md);
    }
    assertThat(out.toString(), containsString("##META=<ID=Assay,Type=String,Number=.,Values=[WholeGenome, Exome]>"));
  }

  @Test
  void testMetaDeclarationsSurviveARebuildBeforeWriting() throws Exception {
    // the round-trip above writes the ORIGINAL object, so it cannot catch a missing toBuilder() copy loop
    VcfMetadata md = parseResource("/metadata_meta.vcf").toBuilder().build();
    StringWriter out = new StringWriter();
    try (VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(out)).build()) {
      writer.writeHeader(md);
    }
    assertThat(out.toString(), containsString("##META=<ID=Assay"));
    assertThat(out.toString(), containsString("##META=<ID=Tissue"));
  }

  @Test
  void testFreeformMetaLineBeforeV4_3IsPreservedAsRawProperty() throws IOException {
    // ##META does not exist before VCFv4.3 (0 occurrences in VCFv4.1.tex/VCFv4.2.tex; VCFv4.3.tex's changelog
    // line 2155 reads "Introduced ##META header lines for defining phenotype metadata"). Regression: this used
    // to be routed unconditionally through removeAngleBrackets/parseMetadataProperty, so a pre-4.3 file's
    // legitimate freeform "##META=..." line (no angle brackets) threw VcfFormatException and aborted the
    // whole parse instead of being preserved like any other unrecognized metadata line.
    String vcf = "##fileformat=VCFv4.2\n" +
        "##META=freeform text\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n";
    try (BufferedReader reader = new BufferedReader(new StringReader(vcf));
         VcfParser parser = new VcfParser.Builder()
             .fromReader(reader)
             .parseWith((metadata, record) -> { })
             .build()) {
      VcfMetadata md = parser.parseMetadata();
      assertTrue(md.getMetas().isEmpty());
      assertEquals(List.of("freeform text"), md.getRawProperties().get("META"));
    }
  }

  @Test
  void testBracketedMetaLineBeforeV4_3IsPreservedAsRawProperty() throws IOException {
    // same version gate as above, but for a well-formed-looking bracketed value: below 4.3 this is not a
    // structured declaration either, so it is preserved verbatim (brackets included) as a raw property rather
    // than being parsed into a MetaMetadata (which would also require an ID it doesn't have here).
    String vcf = "##fileformat=VCFv4.2\n" +
        "##META=<Type=String,Number=1>\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n";
    try (BufferedReader reader = new BufferedReader(new StringReader(vcf));
         VcfParser parser = new VcfParser.Builder()
             .fromReader(reader)
             .parseWith((metadata, record) -> { })
             .build()) {
      VcfMetadata md = parser.parseMetadata();
      assertTrue(md.getMetas().isEmpty());
      assertEquals(List.of("<Type=String,Number=1>"), md.getRawProperties().get("META"));
    }
  }

  @Test
  void testBracketedMetaLineAtV4_3StillRoutesToStructuredPath() throws IOException {
    // control for the two tests above: the same bracketed shape, one version higher, must still take the
    // structured path -- otherwise the version check itself could be backwards or a no-op
    String vcf = "##fileformat=VCFv4.3\n" +
        "##META=<ID=Foo,Type=String,Number=1>\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n";
    try (BufferedReader reader = new BufferedReader(new StringReader(vcf));
         VcfParser parser = new VcfParser.Builder()
             .fromReader(reader)
             .parseWith((metadata, record) -> { })
             .build()) {
      VcfMetadata md = parser.parseMetadata();
      assertTrue(md.getRawProperties().get("META").isEmpty());
      assertTrue(md.getMetas().containsKey("Foo"));
    }
  }

  @Test
  void testMetadataDeclarationsSurviveTurkishDefaultLocale() throws IOException {
    // parseMetadataProperty() used to lower-case propName (without Locale.ROOT) before dispatching on it;
    // under a Turkish/Azeri default locale, "INFO".toLowerCase() produces "ınfo" (dotless i, U+0131), which
    // matched none of the switch's case labels and silently dropped the ##INFO declaration entirely, with
    // no warning or exception. ##FILTER and ##PEDIGREE contain uppercase "I" too and were equally affected;
    // ##ALT/##FORMAT/##contig/##SAMPLE have no "I"/"i" and were unaffected either way.
    String vcf = "##fileformat=VCFv4.2\n" +
        "##INFO=<ID=DP,Number=1,Type=Integer,Description=\"depth\">\n" +
        "##FILTER=<ID=q10,Description=\"quality below 10\">\n" +
        "##PEDIGREE=<ID=cid,Father=fid,Mother=mid>\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "1\t1\t.\tA\tT\t.\tq10\tDP=10\n";
    Locale originalLocale = Locale.getDefault();
    Locale.setDefault(Locale.forLanguageTag("tr-TR"));
    VcfMetadata[] captured = new VcfMetadata[1];
    try (BufferedReader reader = new BufferedReader(new StringReader(vcf));
         VcfParser parser = new VcfParser.Builder()
             .fromReader(reader)
             .parseWith((metadata, record) -> captured[0] = metadata)
             .build()) {
      parser.parse();
    } finally {
      Locale.setDefault(originalLocale);
    }
    assertEquals(1, captured[0].getInfo().size());
    assertTrue(captured[0].getInfo().containsKey("DP"));
    assertEquals(1, captured[0].getFilters().size());
    assertTrue(captured[0].getFilters().containsKey("q10"));
    assertEquals(1, captured[0].getPedigrees().size());
  }

  @Test
  void testFilterAllEmptyEntriesProducesNone() throws IOException {
    // FILTER=";" splits into two empty entries, both dropped with a warning by VcfPosition.checkFilters (see
    // EMPTY_FIELD_HANDLING.md), leaving an empty FILTER list. This must produce FilterStatus.NONE, not PASSED --
    // an empty/no-information FILTER is never an implicit PASS (see VcfPosition.getFilterStatus()'s javadoc).
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "chr1\t100\t.\tA\tT\t.\t;\t.\n";
    List<VcfPosition> positions = new ArrayList<>();
    try (BufferedReader reader = new BufferedReader(new StringReader(vcf));
         VcfParser parser = new VcfParser.Builder()
             .fromReader(reader)
             .parseWith((metadata, record) -> positions.add(record.getPosition()))
             .build()) {
      parser.parse();
    }
    assertEquals(1, positions.size());
    assertEquals(VcfPosition.FilterStatus.NONE, positions.get(0).getFilterStatus());
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
             .parseWith((metadata, record) -> captured.addAll(record.getSamples()))
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
  void testFormatDotMeansNoFormatFields() throws IOException {
    // FORMAT="." (no per-sample format fields at all) is valid when sample columns are present -- VCFv4.2 through
    // 4.5 are all silent on this exact case, but bcftools 1.23.1 accepts and correctly interprets it (verified
    // live: bcftools query returns the missing value for the sample's GT regardless of that sample's own raw
    // column content). See VcfPosition.checkFormat's javadoc for the full rationale.
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tsample1\n" +
        "chr1\t100\t.\tA\tT\t.\tPASS\t.\t.\t.\n";
    List<VcfPosition> positions = new ArrayList<>();
    List<VcfSample> captured = new ArrayList<>();
    try (BufferedReader reader = new BufferedReader(new StringReader(vcf));
         VcfParser parser = new VcfParser.Builder()
             .fromReader(reader)
             .parseWith((metadata, record) -> {
               VcfPosition position = record.getPosition();
               List<VcfSample> sampleData = record.getSamples();
               positions.add(position);
               captured.addAll(sampleData);
             })
             .build()) {
      parser.parse();
    }
    assertTrue(positions.get(0).getFormat().isEmpty());
    assertEquals(1, captured.size());
    assertTrue(captured.get(0).getPropertyKeys().isEmpty());
  }

  @Test
  void testFormatDotIgnoresMismatchedSampleContent() throws IOException {
    // a sample column with real content is ignored (with a warning), not rejected, when FORMAT is "." -- matching
    // bcftools 1.23.1, which silently normalizes such a sample to "." on output regardless of its input content
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tsample1\n" +
        "chr1\t100\t.\tA\tT\t.\tPASS\t.\t.\tfoo\n";
    List<VcfSample> captured = new ArrayList<>();
    try (BufferedReader reader = new BufferedReader(new StringReader(vcf));
         VcfParser parser = new VcfParser.Builder()
             .fromReader(reader)
             .parseWith((metadata, record) -> captured.addAll(record.getSamples()))
             .build()) {
      parser.parse();
    }
    assertEquals(1, captured.size());
    assertTrue(captured.get(0).getPropertyKeys().isEmpty());
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
             .parseWith((metadata, record) -> captured.addAll(record.getSamples()))
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
          public void parseLine(VcfMetadata ignored, VcfRecord record) {
            VcfPosition position = record.getPosition();
            List<VcfSample> values = record.getSamples();
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
        .parseWith((ignored, record) -> reparsed.addAll(record.getSamples()))
        .build()) {
      parser.parse();
    }
    assertEquals(gle, reparsed.get(0).getProperty("GLE"));
    assertEquals("50", reparsed.get(0).getProperty("DP"));
  }

  @Test
  void testGleSampleSplitterIsVersionGated() throws IOException {
    // behavior 5: the parser's own GLE splitter (GLE_VALUE_PATTERN/toSampleValues) never calls
    // InternalUtils.splitFormatValue, so it needs its own gate. VCFv4.3 removed GLE (VCFv4.3.tex line 2168,
    // "Removed unused and ill-defined GLE FORMAT tag"), so from 4.3 GLE's internal colons are ordinary FORMAT
    // sub-field delimiters. The version comes from the parser's own VcfContext (built from ##fileformat), not
    // from any record.
    String gle = "0:-75.22,1:-223.42,0/0:-323.03,1/0:-99.29,1/1:-802.53";
    String line = "chr1\t100\t.\tA\tT\t.\tPASS\t.\tGT:GLE:DP\t0/1:" + gle + ":50\n";
    String header = "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tsample1\n";

    // 4.2: the payload parses, GLE stays one opaque value, and the FORMAT key after it stays aligned
    List<VcfSample> samples42 = new ArrayList<>();
    try (VcfParser parser = new VcfParser.Builder()
        .fromReader(new BufferedReader(new StringReader("##fileformat=VCFv4.2\n" + header + line)))
        .parseWith((ignored, record) -> samples42.addAll(record.getSamples()))
        .build()) {
      parser.parse();
    }
    assertEquals(1, samples42.size());
    assertEquals(gle, samples42.get(0).getProperty("GLE"));
    assertEquals("50", samples42.get(0).getProperty("DP"));

    // 4.3: the identical payload splits on every colon, yielding 5 sample entries for 3 FORMAT keys. The
    // trusted-sample constructor still enforces matching counts (the `trusted` flag skips only the delimiter
    // scan), so the record is REJECTED. Asserting the rejection, not merely "the regex was bypassed": an
    // implementation that padded or truncated the extra entries would swap obsolete GLE semantics for silent
    // column corruption and would otherwise pass.
    List<VcfRecord> delivered = new ArrayList<>();
    try (VcfParser parser = new VcfParser.Builder()
        .fromReader(new BufferedReader(new StringReader("##fileformat=VCFv4.3\n" + header + line)))
        .parseWith((ignored, record) -> delivered.add(record))
        .build()) {
      VcfFormatException e = assertThrows(VcfFormatException.class, parser::parse);
      assertTrue(e.getMessage().contains("Number of FORMAT entries does not match number of sample entries"),
          e.getMessage());
    }
    assertTrue(delivered.isEmpty(), "no record may reach the callback for a rejected line");

    // a 4.3 GLE value with no internal colon is an ordinary custom-key value and parses fine
    List<VcfSample> plain43 = new ArrayList<>();
    try (VcfParser parser = new VcfParser.Builder()
        .fromReader(new BufferedReader(new StringReader("##fileformat=VCFv4.3\n" + header +
            "chr1\t100\t.\tA\tT\t.\tPASS\t.\tGT:GLE:DP\t0/1:abc:50\n")))
        .parseWith((ignored, record) -> plain43.addAll(record.getSamples()))
        .build()) {
      parser.parse();
    }
    assertEquals("abc", plain43.get(0).getProperty("GLE"));
    assertEquals("50", plain43.get(0).getProperty("DP"));
  }

  @Test
  void testGleValuePatternDoesNotStackOverflowOnLongAlleleSequence() throws IOException {
    // regression test: GLE_VALUE_PATTERN's allele-separator and pair-repetition groups must be possessive.
    // Without it, a GLE value with a long sequence of short "|"-separated allele-index tokens blows the JVM
    // call stack directly (an uncaught StackOverflowError, not a graceful VcfFormatException) via Java regex's
    // per-repetition recursive call frames for a quantified group containing alternation -- reproduced through
    // the full parse() entry point since GLE_VALUE_PATTERN itself is private. Confirmed before the fix: a GLE
    // field with ~2,000+ "|"-separated tokens reliably crashed; 5,000 is a wide margin above that threshold.
    StringBuilder alleles = new StringBuilder("0");
    for (int i = 0; i < 5_000; i++) {
      alleles.append("|0");
    }
    String gle = alleles + ":-1.0";
    String input = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tsample1\n" +
        "chr1\t100\t.\tA\tT\t.\tPASS\t.\tGT:GLE:DP\t0/1:" + gle + ":50\n";
    List<VcfSample> samples = new ArrayList<>();
    try (VcfParser parser = new VcfParser.Builder()
        .fromReader(new BufferedReader(new StringReader(input)))
        .parseWith((ignoredMetadata, record) -> samples.addAll(record.getSamples()))
        .build()) {
      parser.parse();
    }
    assertEquals(gle, samples.get(0).getProperty("GLE"));
    assertEquals("50", samples.get(0).getProperty("DP"));
  }

  @Test
  void testNoSamples() throws IOException {

    try (BufferedReader reader = Files.newBufferedReader(PathUtils.getPathToResource("/no_samples.vcf"));
         VcfParser parser = new VcfParser.Builder()
             .fromReader(reader)
             .parseWith((metadata, record) -> {
               VcfPosition position = record.getPosition();
               List<VcfSample> sampleData = record.getSamples();
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
             .parseWith((metadata, record) -> {
               VcfPosition position = record.getPosition();
               List<VcfSample> sampleData = record.getSamples();
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

    VcfLineParser lineParser = (metadata, record) -> {
      VcfPosition position = record.getPosition();
      List<VcfSample> sampleData = record.getSamples();
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
  void testRsidsOnlyRequiresExactComponentMatch() throws IOException {
    // rsidsOnly() must match a complete semicolon-delimited ID component against "rs\d+", not merely find
    // "rs" + digits as a substring anywhere in the raw ID field
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "chr1\t100\tnotrs123suffix\tA\tT\t.\tPASS\t.\n" +
        "chr2\t200\tfoo\tA\tT\t.\tPASS\t.\n" +
        "chr3\t300\tfoo;rs123\tA\tT\t.\tPASS\t.\n";
    List<VcfPosition> positions = new ArrayList<>();
    try (BufferedReader reader = new BufferedReader(new StringReader(vcf));
         VcfParser parser = new VcfParser.Builder()
             .fromReader(reader)
             .rsidsOnly()
             .parseWith((metadata, record) -> positions.add(record.getPosition()))
             .build()) {
      parser.parse();
    }
    assertEquals(1, positions.size());
    assertEquals("chr3", positions.get(0).getChromosome());
  }

  @Test
  void testFile() throws Exception {

    try (VcfParser ignored = new VcfParser.Builder()
        .fromFile(Paths.get("foo.txt"))
        .parseWith((metadata, record) -> {})
        .build()) {
      fail("Didn't catch invalid path");
    } catch (IllegalArgumentException ex) {
      // expected
      assertTrue(ex.getMessage().contains("Not a VCF file"));
    }

    try (BufferedReader reader = Files.newBufferedReader(PathUtils.getPathToResource("/notvcf.vcf"));
         VcfParser parser = new VcfParser.Builder()
             .fromReader(reader)
             .parseWith((metadata, record) -> {})
             .build()) {
      parser.parseMetadata();
      fail("Didn't catch invalid version");
    } catch (VcfFormatException ex) {
      // expected
      assertTrue(ex.getMessage().contains("Not a VCF file"));
    }
  }

  @Test
  void testEmptyInputReportsNoLineNumber() throws IOException {
    // regression test: a completely empty input previously reported "[Line #1]" even though no line was
    // ever actually read -- m_lineNumber was incremented unconditionally before checking whether
    // readLine() returned anything. Now uses -1 (the "no line number attached" sentinel).
    try (BufferedReader reader = new BufferedReader(new StringReader(""));
         VcfParser parser = new VcfParser.Builder()
             .fromReader(reader)
             .parseWith((metadata, record) -> {})
             .build()) {
      VcfFormatException ex = assertThrows(VcfFormatException.class, parser::parseMetadata);
      assertEquals(-1, ex.getLineNumber());
      assertFalse(ex.getMessage().contains("[Line #"));
    }
  }

  @Test
  void testGetLineNumberTracksLinesRead() throws IOException {
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "chr1\t1\t.\tA\tT\t.\t.\t.\n";
    try (BufferedReader reader = new BufferedReader(new StringReader(vcf));
         VcfParser parser = new VcfParser.Builder()
             .fromReader(reader)
             .parseWith((metadata, record) -> {})
             .build()) {
      assertEquals(0, parser.getLineNumber());
      parser.parse();
      assertEquals(3, parser.getLineNumber());
    }
  }

  @Test
  void testContigIdWithWhitespaceWarnsInsteadOfSilentlyAccepting() throws IOException {
    // reproduces test/vcf/{4.1,4.2,4.3}/failed/failed_meta_contig_001.vcf's
    // ##CauseOfFailure=contig ID contains a whitespace -- previously silently accepted with no warning at all
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      String vcf = "##fileformat=VCFv4.2\n##contig=<ID=1 A>\n" +
          "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n";
      parseMetadataOf(vcf);
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void testMetadataLabelsMatchDeclarationKindAtEachParserCallSite() throws IOException {
    // Regression test: VcfParser.parseMetadataProperty passes a literal label
    // ("ALT", "FILTER", "SAMPLE") at each of its IdDescriptionMetadata call sites. IdDescriptionMetadataTest's
    // testWarningEmbedsCallerSuppliedLabelNotSharedClassName only unit-constructs IdDescriptionMetadata directly
    // and confirms whatever label it's given comes through correctly -- it does not verify VcfParser itself
    // supplies the *correct* label at each call site. This test parses a real VCF header through VcfParser so a
    // future accidental swap of one of those literals (e.g. "SAMPLE" for "FILTER") would be caught.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      // an ID containing whitespace triggers IdMetadata.init()'s "{} metadata ID \"{}\" must not contain
      // whitespace" warning for each of ALT/FILTER/SAMPLE, embedding whichever label VcfParser supplied
      String vcf = "##fileformat=VCFv4.2\n" +
          "##ALT=<ID=A B,Description=\"d\">\n" +
          "##FILTER=<ID=A B,Description=\"d\">\n" +
          "##SAMPLE=<ID=A B,Description=\"d\">\n" +
          "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n";
      parseMetadataOf(vcf);
      assertTrue(appender.anyMessageContains("ALT metadata ID"));
      assertTrue(appender.anyMessageContains("FILTER metadata ID"));
      assertTrue(appender.anyMessageContains("SAMPLE metadata ID"));
      assertFalse(appender.anyMessageContains("IdDescriptionMetadata"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  /**
   * Parses {@code vcf} (metadata only, no data records needed) and asserts whether the "has no ID" PEDIGREE
   * diagnostic fired, and that the declaration itself is always still present -- covering all three blank-ID
   * shapes (absent, empty {@code ID=}, whitespace-only {@code ID="   "}) crossed with both VCFv4.2 and VCFv4.3.
   * The blank-ID check unquotes before comparing (see {@code VcfMetadata.Builder.addPedigree}'s own comment and
   * AGENTS.md's "Unquoting before internal use" policy), so a quoted-whitespace/quoted-empty {@code ID} is
   * detected as blank exactly like its unquoted equivalent -- see
   * {@link #testParserPedigreeQuotedWhitespaceOnlyIdUnder43Warns}.
   */
  private static void assertPedigreeIdShapeThroughParser(String pedigreeLine, String fileFormat,
      boolean expectNoIdWarning) throws IOException {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    String vcf = "##fileformat=" + fileFormat + "\n" + pedigreeLine + "\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n";
    VcfMetadata metadata;
    try (BufferedReader reader = new BufferedReader(new StringReader(vcf));
         VcfParser parser = new VcfParser.Builder()
             .fromReader(reader)
             .parseWith((md, record) -> { })
             .build()) {
      parser.parseMetadata();
      metadata = parser.getMetadata();
      assertEquals(expectNoIdWarning, appender.anyMessageContains("has no ID"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertEquals(1, metadata.getPedigrees().size(), "the declaration must be present either way");
  }

  @Test
  void testParserPedigreeAbsentIdUnder43Warns() throws IOException {
    assertPedigreeIdShapeThroughParser("##PEDIGREE=<Original=Something>", "VCFv4.3", true);
  }

  @Test
  void testParserPedigreeEmptyIdUnder43Warns() throws IOException {
    assertPedigreeIdShapeThroughParser("##PEDIGREE=<ID=,Original=Something>", "VCFv4.3", true);
  }

  /**
   * Regression test: the 4.3 "has no ID" gate reads {@code getPropertyUnquoted(ID)}, not {@code
   * getPropertyRaw(ID)} -- see {@code VcfMetadata.Builder.addPedigree}'s own comment -- so a quoted-whitespace
   * {@code ID} ({@code ID="   "}) is caught as blank exactly like its unquoted equivalent
   * ({@link #testParserPedigreeWhitespaceOnlyIdUnder42IsSilent}'s 4.2 counterpart stays silent, since 4.2 has no
   * such gate). This previously escaped detection entirely (see the retired WONT_FIX.md entry and
   * DUPLICATE_METADATA_ID_HANDLING.md's "Missing or empty ID" section); the identical fix applies to a quoted
   * <em>empty</em> {@code ID} ({@code ID=""}) and to all seven structured declaration types.
   */
  @Test
  void testParserPedigreeQuotedWhitespaceOnlyIdUnder43Warns() throws IOException {
    assertPedigreeIdShapeThroughParser("##PEDIGREE=<ID=\"   \",Original=Something>", "VCFv4.3", true);
  }

  @Test
  void testParserPedigreeQuotedEmptyIdUnder43Warns() throws IOException {
    assertPedigreeIdShapeThroughParser("##PEDIGREE=<ID=\"\",Original=Something>", "VCFv4.3", true);
  }

  @Test
  void testParserPedigreeAbsentIdUnder42IsSilent() throws IOException {
    assertPedigreeIdShapeThroughParser("##PEDIGREE=<Original=Something>", "VCFv4.2", false);
  }

  @Test
  void testParserPedigreeEmptyIdUnder42IsSilent() throws IOException {
    assertPedigreeIdShapeThroughParser("##PEDIGREE=<ID=,Original=Something>", "VCFv4.2", false);
  }

  @Test
  void testParserPedigreeWhitespaceOnlyIdUnder42IsSilent() throws IOException {
    assertPedigreeIdShapeThroughParser("##PEDIGREE=<ID=\"   \",Original=Something>", "VCFv4.2", false);
  }

  @Test
  void testThousandGenomesChrYFixtureProducesExactlyThreeKnownWarnings() throws IOException {
    // Real-world regression fixture: the unmodified header (125 metadata lines + #CHROM, 1233 samples) plus 20
    // real data records from 1000 Genomes Phase 3 chrY
    // (ALL.chrY.phase3_integrated_v2b.20130502.genotypes.vcf.gz, downloaded from
    // https://ftp.1000genomes.ebi.ac.uk/vol1/ftp/release/20130502/). All three warnings below are metadata-level
    // (fire once during header parsing, independent of how many data records follow), each a genuine quirk in
    // the real file's own header: `##INFO=<ID=DP,...>` is declared twice with different descriptions;
    // `##FORMAT=<ID=CNP,Number=.,...>` doesn't match `CNP`'s reserved Number=G; and no `##SAMPLE` declarations
    // exist for the 1233 samples in `#CHROM`. Confirmed directly that bcftools 1.23.1 emits none of these three
    // (silently keeps the first `DP` declaration -- same outcome as this library, just no diagnostic; accepts
    // `CNP`'s declared `Number=.` unquestioned; doesn't check for `##SAMPLE` declarations at all) -- this test
    // pins the exact warning set so a regression (either a new warning appearing, or one of these three
    // silently stopping) is caught, not just "a warning fired."
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      try (BufferedReader reader = Files.newBufferedReader(PathUtils.getPathToResource("/thousandgenomes_chrY_excerpt.vcf"));
           VcfParser parser = new VcfParser.Builder()
               .fromReader(reader)
               .parseWith(new org.pharmgkb.parser.vcf.spec.DeferredValidationLineParser())
               .build()) {
        parser.parse();
      }
      Set<String> actual = new HashSet<>(appender.getRelevantWarnOrHigherMessages(Set.of()));
      Set<String> expected = Set.of(
          "There are 1233 samples in the header but 0 in the metadata",
          "Duplicate ID \"DP\" for INFO metadata declaration ignored; keeping the first declaration",
          "FORMAT CNP has Number '.' but the reserved definition expects 'G'");
      assertEquals(expected, actual);
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void testGnomadSvChrYFixtureProducesExactlyTwoKnownWarnings() throws IOException {
    // Real-world regression fixture: the unmodified header (696 metadata lines + #CHROM) from gnomAD v4.1's
    // structural-variant sites release (gnomad.v4.1.sv.sites.vcf.gz, streamed via remote tabix access from
    // https://storage.googleapis.com/gcp-public-data--gnomad/release/4.1/genome_sv/), plus 21 real chrY records
    // -- POS-sorted, two per distinct ALT structural-variant type actually present on chrY (BND, DEL, DUP,
    // INS, INS:ME:ALU, INS:ME:LINE1, CPX, CNV, DEL:ME:LINE1, DEL:ME:HERVK; only one INV exists on chrY at all)
    // -- so AltStructuralVariant's full breadth of real-world reserved/mobile-element/complex codes gets
    // exercised, not just DEL/DUP. This file is site-only: no FORMAT/sample columns in any data line at all.
    // Both warnings below are metadata-level (fire once during header parsing, independent of how many data
    // records follow), each a genuine quirk in the real file's own header: `##INFO=<ID=SVLEN,Number=1,...>`
    // doesn't match SVLEN's reserved Number=.; `##FORMAT=<ID=CNQ,Number=1,Type=Integer,...>` doesn't match
    // CNQ's reserved Type=Float -- declared despite the file never having any FORMAT/sample columns at all,
    // a leftover from the multi-sample pipeline this site-only release was derived from. Confirmed directly
    // that bcftools 1.23.1 emits neither (accepts both declared Number/Type mismatches unquestioned, same
    // pattern already seen for the 1000 Genomes chrY fixture above) -- this test pins the exact warning set
    // so a regression (a new warning appearing, or one of these two silently stopping) is caught either way.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      try (BufferedReader reader = Files.newBufferedReader(PathUtils.getPathToResource("/gnomad_sv_chrY_excerpt.vcf"));
           VcfParser parser = new VcfParser.Builder()
               .fromReader(reader)
               .parseWith(new org.pharmgkb.parser.vcf.spec.DeferredValidationLineParser())
               .build()) {
        parser.parse();
      }
      Set<String> actual = new HashSet<>(appender.getRelevantWarnOrHigherMessages(Set.of()));
      Set<String> expected = Set.of(
          "INFO SVLEN has Number '1' but the reserved definition expects '.'",
          "FORMAT CNQ has Type 'Integer' but the reserved definition expects 'VcfFloat'");
      assertEquals(expected, actual);
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void testEvaCatSaa1FixtureProducesExactlyOneKnownWarning() throws IOException {
    // Real-world regression fixture, committed in full (unlike the excerpted fixtures above -- this file is
    // already tiny): a real individual study submission to the European Variation Archive (EVA), PRJEB105544
    // (Felis catus, SAA1 gene region, https://ftp.ebi.ac.uk/pub/databases/eva/PRJEB105544/cat_SAA1.vcf.gz),
    // 107 records across 50 samples. Notably, this is a genuinely ##fileformat=VCFv4.3-declared file -- every
    // other real-world fixture and every centralized production pipeline checked (1000 Genomes, gnomAD,
    // ClinVar, and even EVA's own re-accessioned version of this same study) declares VCFv4.1/4.2 regardless
    // of how recent the release is; an individual researcher's own submission, using their own tooling before
    // EVA's centralized accessioning pipeline reprocesses it, is the one place a newer declared version
    // actually shows up. The one warning below is metadata-level (fires once during header parsing,
    // independent of how many data records follow): no ##SAMPLE declarations exist for the 50 samples in
    // #CHROM -- the same real-world gap already seen in both fixtures above, now confirmed a third time
    // across a third, unrelated dataset and species. Confirmed directly that bcftools 1.23.1 emits nothing
    // for this file at all (exit 0, no warnings) -- consistent with every other fixture here.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      try (BufferedReader reader = Files.newBufferedReader(PathUtils.getPathToResource("/eva_cat_saa1.vcf"));
           VcfParser parser = new VcfParser.Builder()
               .fromReader(reader)
               .parseWith(new org.pharmgkb.parser.vcf.spec.DeferredValidationLineParser())
               .build()) {
        parser.parse();
      }
      Set<String> actual = new HashSet<>(appender.getRelevantWarnOrHigherMessages(Set.of()));
      Set<String> expected = Set.of("There are 50 samples in the header but 0 in the metadata");
      assertEquals(expected, actual);
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void testHtslibVcf44PhasingFixtureWarnsOnEveryLeadingPhaseMarkerGenotype() throws IOException {
    // Real-world regression fixture, committed in full (already tiny): htslib's own test/vcf44_1.vcf
    // (https://github.com/samtools/htslib/blob/develop/test/vcf44_1.vcf), a deliberately adversarial fixture
    // htslib itself uses to test VCFv4.4's new leading-phase-marker GT syntax -- a genotype may now start with
    // an explicit '/' or '|' before its first allele (e.g. "/0", "|1/0") to state phasing relative to a
    // previous record/phase set, a feature this library's VCFv4.3 target predates and does not implement.
    //
    // This library's GT-splitting (InternalUtils.getPloidy, reached via VcfSample.getProperty(VcfPosition,
    // VcfMetadata, String)'s ploidy-for-cardinality-check resolution) has no special case for a leading
    // separator: splitting "/0" on `[/|]` yields a leading empty allele token, which fails Integer.parseInt
    // and is reported as an "invalid GT" warning -- not a crash, and not a misinterpretation of any value this
    // library actually claims to support, just this library's permissive-parser policy correctly recognizing
    // syntax it doesn't understand as malformed rather than silently misreading it. Confirmed directly that
    // bcftools 1.23.1 (which does implement VCFv4.4's leading-phase-marker semantics) accepts this same file
    // with zero warnings or errors -- a genuine, expected capability gap, not a bug to fix.
    //
    // Asserted as an exact count plus a shape check, rather than a literal Set of every warning message like
    // the fixtures above: this file deliberately exercises ~28 distinct GT value combinations, each producing
    // its own "invalid GT <value>" message, so pinning 37 literal strings would be both unreadable and
    // fragile. Pinning the *count* and the *shape* still catches the same regressions (a new warning category
    // appearing, or the invalid-GT warning silently stopping).
    //
    // Switching to the full DeferredValidationLineParser surfaces a second warning per malformed record (the
    // "contains an empty allele" one) on top of the original "invalid GT" one: a leading separator's empty
    // leading base trips both getProperty(position, metadata, "GT")'s ploidy check and VcfSample.getGenotype's
    // own empty-base handling -- two independent, both-correct observations of the same malformed input, not a
    // new defect. This is expected behavior: multiple deferred checks correctly flagging the same malformed
    // input from different angles.
    //
    // Maintenance note: htslib's test/ directory is a living resource for exactly this kind of edge-case
    // fixture (new VCF-version features get their own adversarial test files as they're added -- this is
    // presently the only VCFv4.4 one and there is no VCFv4.5 one yet). Worth periodically re-checking
    // https://github.com/samtools/htslib/tree/develop/test for new fixtures covering VCF features this
    // library doesn't yet have real-world regression coverage for.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      try (BufferedReader reader = Files.newBufferedReader(PathUtils.getPathToResource("/htslib_vcf44_phasing.vcf"));
           VcfParser parser = new VcfParser.Builder()
               .fromReader(reader)
               // exercises every deferred check, not just GT -- see DeferredValidationLineParser's own Javadoc
               // for why a partial hand-rolled lambda here previously missed 36 of 38 expected warnings
               .parseWith(new org.pharmgkb.parser.vcf.spec.DeferredValidationLineParser())
               .build()) {
        parser.parse();
      }
      List<String> actual = appender.getRelevantWarnOrHigherMessages(Set.of());
      assertEquals(76, actual.size(), actual::toString);
      // this fixture declares VCFv4.4, which this library does not implement, so it now also reports the
      // version fallback exactly once -- the one new diagnostic the file-scoped VcfContext introduces
      assertEquals(1, actual.stream()
              .filter(m -> m.startsWith("Reading ##fileformat=VCFv4.4 as VCF 4.3") && m.contains("does not implement"))
              .count(),
          actual::toString);
      assertEquals(1,
          actual.stream().filter(m -> m.equals("There are 2 samples in the header but 0 in the metadata")).count(),
          actual::toString);
      assertEquals(37, actual.stream().filter(m -> m.startsWith("FORMAT GT has invalid GT ")).count(),
          actual::toString);
      assertEquals(37, actual.stream().filter(m -> m.contains("contains an empty allele")).count(), actual::toString);
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void testPositionOrderingWarnsOnDecreasingPosWithinSameChromosome() throws IOException {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      String vcf = "##fileformat=VCFv4.2\n" +
          "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
          "1\t200\t.\tA\tT\t.\t.\t.\n" +
          "1\t100\t.\tA\tT\t.\t.\t.\n";
      List<VcfPosition> positions = new ArrayList<>();
      try (VcfParser parser = new VcfParser.Builder()
          .fromReader(new BufferedReader(new StringReader(vcf)))
          .parseWith((metadata, record) -> positions.add(record.getPosition()))
          .build()) {
        parser.parse();
      }
      // both records are still delivered -- this warns, it does not throw or drop the record
      assertEquals(2, positions.size());
      assertTrue(appender.anyMessageContains("POS 100 is out of order for CHROM 1"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void testVcfLineParserCallbackRuntimeExceptionPropagatesWithOriginalType() throws IOException {
    // regression test: a RuntimeException thrown by the caller's OWN VcfLineParser callback (a bug in their
    // code, not a VCF-content problem) must propagate as-is, not get silently relabeled as VcfFormatException
    // -- indistinguishable from a genuine "this line is malformed" rejection to any caller who branches on
    // exception type
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "1\t100\t.\tA\tT\t.\t.\t.\n";
    try (VcfParser parser = new VcfParser.Builder()
        .fromReader(new BufferedReader(new StringReader(vcf)))
        .parseWith((metadata, record) -> {
          throw new NullPointerException("caller bug: some field was null");
        })
        .build()) {
      NullPointerException e = assertThrows(NullPointerException.class, parser::parse);
      assertEquals("caller bug: some field was null", e.getMessage());
    }
  }

  @Test
  void testVcfLineParserCallbackDeliberateVcfFormatExceptionStillGetsLineNumberAttached() throws IOException {
    // companion regression test to the one above: a callback that deliberately throws VcfFormatException (to
    // reject an already-structurally-valid record) must still get this line's number attached, exactly as
    // before this method was split into two try/catch blocks
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "1\t100\t.\tA\tT\t.\t.\t.\n";
    try (VcfParser parser = new VcfParser.Builder()
        .fromReader(new BufferedReader(new StringReader(vcf)))
        .parseWith((metadata, record) -> {
          throw new VcfFormatException("caller rejects this record");
        })
        .build()) {
      VcfFormatException e = assertThrows(VcfFormatException.class, parser::parse);
      assertThat(e.getMessage(), containsString("caller rejects this record"));
      assertThat(e.getMessage(), containsString("Line #"));
    }
  }

  @Test
  void testVcfLineParserParseMetadataCallbackDeliberateVcfFormatExceptionGetsLineNumberAttached() throws IOException {
    // regression test: previously, a VcfFormatException thrown deliberately by the callback's own
    // parseMetadata(VcfMetadata) override propagated completely raw -- no line number, no "Error parsing
    // metadata: " prefix -- even though this is structurally identical to
    // testVcfLineParserCallbackDeliberateVcfFormatExceptionStillGetsLineNumberAttached's parseLine() case above,
    // which already got this treatment
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "1\t100\t.\tA\tT\t.\t.\t.\n";
    try (VcfParser parser = new VcfParser.Builder()
        .fromReader(new BufferedReader(new StringReader(vcf)))
        .parseWith(new VcfLineParser() {
          @Override
          public void parseMetadata(VcfMetadata metadata) {
            throw new VcfFormatException("caller rejects this header");
          }

          @Override
          public void parseLine(VcfMetadata metadata, VcfRecord record) {
          }
        })
        .build()) {
      VcfFormatException e = assertThrows(VcfFormatException.class, parser::parse);
      assertThat(e.getMessage(), containsString("caller rejects this header"));
      assertThat(e.getMessage(), containsString("Line #"));
    }
  }

  @Test
  void testVcfLineParserParseMetadataCallbackRuntimeExceptionPropagatesWithOriginalType() throws IOException {
    // companion regression test: a RuntimeException from the parseMetadata(VcfMetadata) callback (a bug in the
    // caller's own code, not a VCF-content problem) must still propagate as-is, not get wrapped -- mirrors
    // testVcfLineParserCallbackRuntimeExceptionPropagatesWithOriginalType's parseLine() case above
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "1\t100\t.\tA\tT\t.\t.\t.\n";
    try (VcfParser parser = new VcfParser.Builder()
        .fromReader(new BufferedReader(new StringReader(vcf)))
        .parseWith(new VcfLineParser() {
          @Override
          public void parseMetadata(VcfMetadata metadata) {
            throw new NullPointerException("caller bug: some field was null");
          }

          @Override
          public void parseLine(VcfMetadata metadata, VcfRecord record) {
          }
        })
        .build()) {
      NullPointerException e = assertThrows(NullPointerException.class, parser::parse);
      assertEquals("caller bug: some field was null", e.getMessage());
    }
  }

  @Test
  void testPositionOrderingAllowsRepeatedPosOnSameChromosome() throws IOException {
    // VCFv4.2.tex line 180: "It is permitted to have multiple records with the same POS."
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      String vcf = "##fileformat=VCFv4.2\n" +
          "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
          "1\t100\t.\tA\tT\t.\t.\t.\n" +
          "1\t100\t.\tA\tG\t.\t.\t.\n" +
          "1\t101\t.\tA\tT\t.\t.\t.\n";
      try (VcfParser parser = new VcfParser.Builder()
          .fromReader(new BufferedReader(new StringReader(vcf)))
          .parseWith((metadata, record) -> { })
          .build()) {
        parser.parse();
      }
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void testChromContiguityWarnsWhenAChromReappearsAfterBeingInterrupted() throws IOException {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      String vcf = "##fileformat=VCFv4.2\n" +
          "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
          "1\t100\t.\tA\tT\t.\t.\t.\n" +
          "2\t100\t.\tA\tT\t.\t.\t.\n" +
          "1\t200\t.\tA\tT\t.\t.\t.\n";
      List<VcfPosition> positions = new ArrayList<>();
      try (VcfParser parser = new VcfParser.Builder()
          .fromReader(new BufferedReader(new StringReader(vcf)))
          .parseWith((metadata, record) -> positions.add(record.getPosition()))
          .build()) {
        parser.parse();
      }
      // all three records are still delivered -- this warns, it does not throw or drop the record
      assertEquals(3, positions.size());
      assertTrue(appender.anyMessageContains("CHROM 1 is not contiguous"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void testChromContiguityAndPositionOrderingProduceNoWarningsForAWellFormedFile() throws IOException {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      String vcf = "##fileformat=VCFv4.2\n" +
          "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
          "1\t100\t.\tA\tT\t.\t.\t.\n" +
          "1\t200\t.\tA\tT\t.\t.\t.\n" +
          "2\t50\t.\tA\tT\t.\t.\t.\n" +
          "2\t60\t.\tA\tT\t.\t.\t.\n";
      try (VcfParser parser = new VcfParser.Builder()
          .fromReader(new BufferedReader(new StringReader(vcf)))
          .parseWith((metadata, record) -> { })
          .build()) {
        parser.parse();
      }
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void testStructurallyRejectedLineDoesNotPoisonPositionOrderingState() throws IOException {
    // regression test: checkPositionOrdering() used to run before VcfPosition was constructed, so a line that
    // was later structurally rejected (here, a negative POS) still permanently updated the ordering/contiguity
    // tracking state. Using parseNextLine() directly in a catch-and-continue loop (the supported, though not
    // separately documented, per-line recovery pattern for parseNextLine() -- m_alreadyFinished is only set on
    // EOF, never on a caught exception -- unlike parse(), which aborts entirely on the first exception) so the
    // rejected line's effect on later lines is observable.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      String vcf = "##fileformat=VCFv4.2\n" +
          "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
          "1\t200\t.\tA\tT\t.\t.\t.\n" +
          "2\t-5\t.\tA\tT\t.\t.\t.\n" +
          "1\t250\t.\tA\tT\t.\t.\t.\n";
      List<VcfPosition> positions = new ArrayList<>();
      try (VcfParser parser = new VcfParser.Builder()
          .fromReader(new BufferedReader(new StringReader(vcf)))
          .parseWith((metadata, record) -> positions.add(record.getPosition()))
          .build()) {
        boolean hasNext = true;
        while (hasNext) {
          try {
            hasNext = parser.parseNextLine();
          } catch (VcfFormatException ex) {
            // catch-and-continue: the middle line (negative POS) is rejected but parsing continues
          }
        }
      }
      // only the two structurally valid records were delivered
      assertEquals(2, positions.size());
      // the rejected CHROM 2 line must never have been treated as a real interruption of CHROM 1's contiguity
      assertFalse(appender.anyMessageContains("CHROM 1 is not contiguous"));
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void testSampleArityRejectedLineDoesNotPoisonPositionOrderingState() throws IOException {
    // same regression as testStructurallyRejectedLineDoesNotPoisonPositionOrderingState above, but for the
    // rejection thrown by VcfSample's own constructor (FORMAT/sample-value-count mismatch) rather than
    // VcfPosition's -- this throw point is reached after VcfPosition has already been successfully constructed,
    // so checkPositionOrdering() must run only after this check too, not merely after VcfPosition's.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      String vcf = "##fileformat=VCFv4.2\n" +
          "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tS1\n" +
          "1\t200\t.\tA\tT\t.\t.\t.\tGT\t0/1\n" +
          "2\t300\t.\tA\tT\t.\t.\t.\tGT\t0/1:5\n" +
          "1\t250\t.\tA\tT\t.\t.\t.\tGT\t0/1\n";
      List<VcfPosition> positions = new ArrayList<>();
      try (VcfParser parser = new VcfParser.Builder()
          .fromReader(new BufferedReader(new StringReader(vcf)))
          .parseWith((metadata, record) -> positions.add(record.getPosition()))
          .build()) {
        boolean hasNext = true;
        while (hasNext) {
          try {
            hasNext = parser.parseNextLine();
          } catch (VcfFormatException ex) {
            // catch-and-continue: the middle line (2 sample values for 1 FORMAT key) is rejected but parsing
            // continues
          }
        }
      }
      // only the two structurally valid records were delivered
      assertEquals(2, positions.size());
      // the rejected CHROM 2 line must never have been treated as a real interruption of CHROM 1's contiguity;
      // unlike testStructurallyRejectedLineDoesNotPoisonPositionOrderingState above, this fixture declares a
      // sample column with no corresponding ##SAMPLE metadata, which triggers its own unrelated "N samples in
      // the header but 0 in the metadata" warning -- so this test checks the specific message, not the absence
      // of any warning at all
      assertFalse(appender.anyMessageContains("CHROM 1 is not contiguous"));
      assertFalse(appender.anyMessageContains("out of order"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void testStructurallyRejectedLineDoesNotPoisonLastPositionWithinSameChromosome() throws IOException {
    // a third variant of the same regression: the rejected line here shares its CHROM with the surrounding
    // valid records (rather than differing, as in testStructurallyRejectedLineDoesNotPoisonPositionOrderingState
    // above), so this specifically targets m_lastPosition poisoning rather than m_closedChromosomes. The
    // rejected line's own POS (99999) is a well-formed, large positive number that must never become the
    // tracked "last position" -- if it did, the next valid record's real POS (250) would be smaller than it and
    // incorrectly warn as out of order. REF "Z" (not a valid IUPAC base) is what makes the middle line rejected,
    // not POS itself.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      String vcf = "##fileformat=VCFv4.2\n" +
          "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
          "1\t200\t.\tA\tT\t.\t.\t.\n" +
          "1\t99999\t.\tZ\tT\t.\t.\t.\n" +
          "1\t250\t.\tA\tT\t.\t.\t.\n";
      List<VcfPosition> positions = new ArrayList<>();
      try (VcfParser parser = new VcfParser.Builder()
          .fromReader(new BufferedReader(new StringReader(vcf)))
          .parseWith((metadata, record) -> positions.add(record.getPosition()))
          .build()) {
        boolean hasNext = true;
        while (hasNext) {
          try {
            hasNext = parser.parseNextLine();
          } catch (VcfFormatException ex) {
            // catch-and-continue: the middle line (invalid REF "Z") is rejected but parsing continues
          }
        }
      }
      // only the two structurally valid records were delivered
      assertEquals(2, positions.size());
      // POS 250 must still be compared against POS 200 (the last real record), not the rejected line's POS 99999
      assertFalse(appender.anyMessageContains("out of order"));
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void testRsidsOnlyTreatsDiscardedLineAsIfItNeverExisted() throws IOException {
    // regression test for a design reversal: rsidsOnly() now discards a non-rsID line via an early return in
    // parseNextLine() BEFORE CHROM/POS are even examined, not after (as an earlier version of this check did).
    // A discarded line must not be able to trigger a POS-ordering warning itself, AND must not corrupt the
    // ordering-tracking state for later, kept records -- the discarded "POS 50" record here is deliberately
    // out of order relative to the real preceding record (rs1, POS 100), but must produce no warning of its
    // own; the next kept record (rs2, POS 75) must still be compared against rs1's real POS (100), not the
    // discarded record's POS (50), and correctly warn as out of order relative to THAT.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      String vcf = "##fileformat=VCFv4.2\n" +
          "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
          "1\t100\trs1\tA\tT\t.\t.\t.\n" +
          "1\t50\tnotanrsid\tA\tT\t.\t.\t.\n" +
          "1\t75\trs2\tA\tT\t.\t.\t.\n";
      List<VcfPosition> positions = new ArrayList<>();
      try (VcfParser parser = new VcfParser.Builder()
          .fromReader(new BufferedReader(new StringReader(vcf)))
          .rsidsOnly()
          .parseWith((metadata, record) -> positions.add(record.getPosition()))
          .build()) {
        parser.parse();
      }
      // only rs1 and rs2 were delivered -- the middle record was discarded by rsidsOnly()
      assertEquals(2, positions.size());
      // the discarded record's own out-of-order POS never triggered a warning
      assertFalse(appender.anyMessageContains("POS 50"));
      // rs2 (POS 75) is still correctly flagged as out of order relative to rs1 (POS 100) -- proving the
      // discarded record's POS never became the tracked "last position" either
      assertTrue(appender.anyMessageContains("POS 75 is out of order for CHROM 1"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void testRsidsOnlyDiscardsLineWithNegativePosWithoutThrowing() throws IOException {
    // a discarded line is treated as though it were never in the file -- VcfPosition (where the negative-POS
    // throw lives) is never constructed for it, so this must not throw despite the malformed POS
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "1\t-5\tnotanrsid\tA\tT\t.\t.\t.\n" +
        "1\t100\trs1\tA\tT\t.\t.\t.\n";
    List<VcfPosition> positions = new ArrayList<>();
    try (VcfParser parser = new VcfParser.Builder()
        .fromReader(new BufferedReader(new StringReader(vcf)))
        .rsidsOnly()
        .parseWith((metadata, record) -> positions.add(record.getPosition()))
        .build()) {
      parser.parse();
    }
    assertEquals(1, positions.size());
  }

  @Test
  void testRsidsOnlyDiscardsLineWithNonNumericPosWithoutThrowing() throws IOException {
    // POS isn't even parsed as a number for a discarded line anymore -- confirming a genuinely non-numeric
    // value doesn't throw either, not just a negative one
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "1\tnotanumber\tnotanrsid\tA\tT\t.\t.\t.\n" +
        "1\t100\trs1\tA\tT\t.\t.\t.\n";
    List<VcfPosition> positions = new ArrayList<>();
    try (VcfParser parser = new VcfParser.Builder()
        .fromReader(new BufferedReader(new StringReader(vcf)))
        .rsidsOnly()
        .parseWith((metadata, record) -> positions.add(record.getPosition()))
        .build()) {
      parser.parse();
    }
    assertEquals(1, positions.size());
  }

  @Test
  void testRsidsOnlyDiscardsLineWithMalformedChromWithoutThrowing() throws IOException {
    // a discarded line's CHROM is never even extracted, let alone validated -- confirming a CHROM containing
    // an invalid character (per VcfPosition.checkChromosome's character-set rules) doesn't throw either
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "chr(1)\t50\tnotanrsid\tA\tT\t.\t.\t.\n" +
        "1\t100\trs1\tA\tT\t.\t.\t.\n";
    List<VcfPosition> positions = new ArrayList<>();
    try (VcfParser parser = new VcfParser.Builder()
        .fromReader(new BufferedReader(new StringReader(vcf)))
        .rsidsOnly()
        .parseWith((metadata, record) -> positions.add(record.getPosition()))
        .build()) {
      parser.parse();
    }
    assertEquals(1, positions.size());
  }

  @Tag("hts-specs")
  @Test
  void testHtsSpecsContiguousAndUnsortedFixturesWarnWithTheSpecificNewMessage() throws IOException {
    assertHtsSpecsFixtureWarns("failed_body_contiguous_000.vcf", "CHROM 1 is not contiguous");
    assertHtsSpecsFixtureWarns("failed_body_contiguous_001.vcf", "CHROM 2 is not contiguous");
    assertHtsSpecsFixtureWarns("failed_body_unsorted_000.vcf", "is out of order for CHROM 1");
  }

  @Tag("hts-specs")
  @Test
  void testHtsSpecsChromCharacterSetFixturesMatchExpectedBehavior() throws IOException {
    // failed_body_chrom_000.vcf's CHROM is the unclosed "<1" (missing closing '>'); failed_body_chrom_003.vcf's
    // CHROM is "chr,1" -- VCFv4.3.tex's "changes since previous version" appendix confirms both an
    // unwrapped/malformed '<' and a ',' are invalid anywhere in CHROM, so both now throw.
    try (VcfParser parser = new VcfParser.Builder()
        .fromFile(Path.of("build/hts-specs-checkout/test/vcf/4.2/failed/failed_body_chrom_000.vcf"))
        .parseWith((metadata, record) -> { })
        .build()) {
      assertThrows(VcfFormatException.class, parser::parse);
    }
    try (VcfParser parser = new VcfParser.Builder()
        .fromFile(Path.of("build/hts-specs-checkout/test/vcf/4.2/failed/failed_body_chrom_003.vcf"))
        .parseWith((metadata, record) -> { })
        .build()) {
      assertThrows(VcfFormatException.class, parser::parse);
    }
    // failed_body_chrom_001.vcf's CHROM is "chr:1" -- the same appendix explicitly says disallowing colon "was
    // unnecessary"; this fixture must keep parsing cleanly. See HTS_SPECS_BASELINE_STATUS.md's "CHROM colon
    // fixture" subsection (added in Task 3) for the full citation.
    try (VcfParser parser = new VcfParser.Builder()
        .fromFile(Path.of("build/hts-specs-checkout/test/vcf/4.2/failed/failed_body_chrom_001.vcf"))
        .parseWith((metadata, record) -> { })
        .build()) {
      parser.parse(); // must not throw
    }
  }

  @Tag("hts-specs")
  @Test
  void testHtsSpecsContigIdAsteriskFixtureIsActuallyValid() throws IOException {
    // failed_meta_contig_003.vcf (4.3-only) claims "##contig=<ID=1.*,length=123456>" is invalid
    // ("contig ID contains illegal character"), but VCFv4.3.tex's own "Changes to VCFv4.3" errata says '*' is
    // only invalid as the first character -- it's the third character here, so this file must parse cleanly.
    // See HTS_SPECS_BASELINE_STATUS.md's "Contig ID asterisk fixture" section for the full cross-version
    // hts-specs self-contradiction this reproduces.
    try (VcfParser parser = new VcfParser.Builder()
        .fromFile(Path.of("build/hts-specs-checkout/test/vcf/4.3/failed/failed_meta_contig_003.vcf"))
        .parseWith((metadata, record) -> { })
        .build()) {
      parser.parse(); // must not throw
    }
    // passed_meta_contig.vcf (4.2) independently declares the identical "1.*" ID as part of a fixture that's
    // expected to parse without incident -- corroborating evidence, checked concretely (not just "didn't
    // throw") by confirming the parsed metadata actually contains this contig declaration.
    List<VcfMetadata> capturedMetadata = new ArrayList<>();
    try (VcfParser parser = new VcfParser.Builder()
        .fromFile(Path.of("build/hts-specs-checkout/test/vcf/4.2/passed/passed_meta_contig.vcf"))
        .parseWith((metadata, record) -> {
          if (capturedMetadata.isEmpty()) {
            capturedMetadata.add(metadata);
          }
        })
        .build()) {
      parser.parse();
    }
    assertFalse(capturedMetadata.isEmpty());
    ContigMetadata contig1Star = capturedMetadata.get(0).getContigs().get("1.*");
    assertNotNull(contig1Star, "passed_meta_contig.vcf's \"1.*\" contig declaration should be present");
  }

  @Tag("hts-specs")
  @Test
  void testHtsSpecsNegativeReservedInfoValueFixturesWarnOnTypedAccess() throws IOException {
    // AC/AN/DP/END/MQ0/NS/AF's declared Type accepts a negative value; the extra non-negative (or, for AF,
    // [0,1]-interval) constraint is only checked lazily, when the typed accessor is actually used -- these
    // fixtures never declare a ##INFO line for their key, confirming getInfo(ReservedInfoProperty) doesn't
    // need one (it uses the reserved property's own hardcoded Type, not a declared metadata entry).
    Object[][] cases = {
        { "failed_body_info_001.vcf", ReservedInfoProperty.AlleleCount },
        { "failed_body_info_004.vcf", ReservedInfoProperty.AlleleFrequency },
        { "failed_body_info_006.vcf", ReservedInfoProperty.AlleleNumber },
        { "failed_body_info_013.vcf", ReservedInfoProperty.Depth },
        { "failed_body_info_016.vcf", ReservedInfoProperty.End },
        { "failed_body_info_021.vcf", ReservedInfoProperty.MappingQualityZeroCount },
        { "failed_body_info_023.vcf", ReservedInfoProperty.NumberOfSamples },
    };
    for (Object[] c : cases) {
      String fixtureName = (String) c[0];
      ReservedInfoProperty key = (ReservedInfoProperty) c[1];
      ch.qos.logback.classic.Logger root =
          (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
      org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
          new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
      appender.start();
      root.addAppender(appender);
      try (VcfParser parser = new VcfParser.Builder()
          .fromFile(Path.of("build/hts-specs-checkout/test/vcf/4.2/failed", fixtureName))
          .parseWith((metadata, record) -> record.getPosition().getInfo(key))
          .build()) {
        parser.parse();
      } finally {
        root.detachAppender(appender);
        appender.stop();
      }
      // hasWarnOrHigher() alone would be a false-positive-prone check here: these fixtures don't declare
      // sample columns in their metadata, so "There are {} samples in the header but {} in the metadata" fires
      // regardless of this check -- match the specific message this check itself produces instead
      assertTrue(appender.anyMessageContains("INFO " + key.getId()),
          () -> fixtureName + " (" + key.getId() + ") should have warned about the negative/out-of-range value");
    }
  }

  @Tag("hts-specs")
  @Test
  void testHtsSpecsInvalidCigarValueFixturesWarnOnTypedAccess() throws IOException {
    // CIGAR's grammar is only checked lazily, when the typed accessor is actually used (same mechanism as
    // testHtsSpecsNegativeReservedInfoValueFixturesWarnOnTypedAccess above) -- neither fixture declares a
    // ##INFO line for CIGAR, confirming getInfo(ReservedInfoProperty) doesn't need one.
    String[] fixtures = { "failed_body_info_010.vcf", "failed_body_info_011.vcf" };
    for (String fixtureName : fixtures) {
      ch.qos.logback.classic.Logger root =
          (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
      org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
          new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
      appender.start();
      root.addAppender(appender);
      try (VcfParser parser = new VcfParser.Builder()
          .fromFile(Path.of("build/hts-specs-checkout/test/vcf/4.2/failed", fixtureName))
          .parseWith((metadata, record) -> record.getPosition().getInfo(ReservedInfoProperty.Cigar))
          .build()) {
        parser.parse();
      } finally {
        root.detachAppender(appender);
        appender.stop();
      }
      // same false-positive-prone "There are {} samples..." warning as the negative-value fixtures above --
      // match the specific message this check produces instead of a bare hasWarnOrHigher()
      assertTrue(appender.anyMessageContains("does not match the expected CIGAR grammar"),
          () -> fixtureName + " should have warned about the invalid CIGAR grammar");
    }
  }

  @Tag("hts-specs")
  @Test
  void testHtsSpecsPlCardinalityFixturesWarnOnTypedAccess() throws IOException {
    // PL's Number=G cardinality is only checked lazily, when the new position+metadata-aware accessor is used
    // (item 5) -- these fixtures' own ##CauseOfFailure= is "cardinality in PL does not match the cardinality
    // derived from allele number and GT ploidy"
    String[] fixtures = {
        "failed_body_samples_ploidy_000.vcf", "failed_body_samples_ploidy_001.vcf",
        "failed_body_samples_ploidy_002.vcf", "failed_body_samples_ploidy_003.vcf",
    };
    for (String fixtureName : fixtures) {
      ch.qos.logback.classic.Logger root =
          (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
      org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
          new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
      appender.start();
      root.addAppender(appender);
      try (VcfParser parser = new VcfParser.Builder()
          .fromFile(Path.of("build/hts-specs-checkout/test/vcf/4.2/failed", fixtureName))
          .parseWith(new org.pharmgkb.parser.vcf.spec.DeferredValidationLineParser())
          .build()) {
        parser.parse();
      } finally {
        root.detachAppender(appender);
        appender.stop();
      }
      assertTrue(appender.anyMessageContains("FORMAT PL"),
          () -> fixtureName + " should have warned about PL's cardinality");
    }
  }

  @Tag("hts-specs")
  @Test
  void testHtsSpecsNonReservedFormatNumberFixturesWarnOnTypedAccess() throws IOException {
    // a non-reserved FORMAT key's declared Number is only checked lazily, when the new position+metadata-aware
    // accessor is used (item 19) -- each fixture's own ##CauseOfFailure= is "Sample field does not match Number
    // definition in meta section"
    Object[][] cases = {
        { "failed_body_sample_004.vcf", "DS" },
        { "failed_body_sample_005.vcf", "AL" },
        { "failed_body_sample_006.vcf", "AL" },
        { "failed_body_sample_010.vcf", "AL" },
    };
    for (Object[] c : cases) {
      String fixtureName = (String) c[0];
      String key = (String) c[1];
      ch.qos.logback.classic.Logger root =
          (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
      org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
          new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
      appender.start();
      root.addAppender(appender);
      try (VcfParser parser = new VcfParser.Builder()
          .fromFile(Path.of("build/hts-specs-checkout/test/vcf/4.2/failed", fixtureName))
          .parseWith((metadata, record) -> {
            VcfPosition position = record.getPosition();
            List<VcfSample> sampleData = record.getSamples();
            for (VcfSample sample : sampleData) {
              sample.getProperty(position, metadata, key);
            }
          })
          .build()) {
        parser.parse();
      } finally {
        root.detachAppender(appender);
        appender.stop();
      }
      assertTrue(appender.anyMessageContains("FORMAT " + key),
          () -> fixtureName + " (" + key + ") should have warned about its Number cardinality");
    }
  }

  @Tag("hts-specs")
  @Test
  void testHtsSpecsNonReservedFormatTypeFixturesThrowOnTypedAccess() throws IOException {
    // a non-reserved FORMAT key's declared Type mismatch still throws (unchanged sibling behavior), not warns --
    // each fixture's own ##CauseOfFailure= is "Sample field does not match Type definition in meta section"
    String[] fixtures = {
        "failed_body_sample_007.vcf", "failed_body_sample_008.vcf", "failed_body_sample_009.vcf",
    };
    for (String fixtureName : fixtures) {
      List<VcfFormatException> thrown = new ArrayList<>();
      try (VcfParser parser = new VcfParser.Builder()
          .fromFile(Path.of("build/hts-specs-checkout/test/vcf/4.2/failed", fixtureName))
          .parseWith((metadata, record) -> {
            VcfPosition position = record.getPosition();
            List<VcfSample> sampleData = record.getSamples();
            for (VcfSample sample : sampleData) {
              try {
                sample.getProperty(position, metadata, "DS");
              } catch (VcfFormatException e) {
                thrown.add(e);
              }
            }
          })
          .build()) {
        parser.parse();
      }
      assertFalse(thrown.isEmpty(), () -> fixtureName + " should have thrown for DS's Type mismatch");
    }
  }

  private static void assertHtsSpecsFixtureWarns(String fixtureName, String expectedMessageFragment)
      throws IOException {
    Path fixture = Path.of("build/hts-specs-checkout/test/vcf/4.2/failed", fixtureName);
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try (VcfParser parser = new VcfParser.Builder()
        .fromFile(fixture)
        .parseWith((metadata, record) -> { })
        .build()) {
      parser.parse();
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertTrue(appender.anyMessageContains(expectedMessageFragment),
        () -> fixtureName + " should have produced a warning containing: " + expectedMessageFragment);
  }

  @Test
  public void testPedigreeMetadataWarningsSinkIsWiredToParserWarningsFile(@TempDir Path tempDir) throws IOException {
    // regression test: VcfParser's PEDIGREE case previously never called setWarningsSink on the constructed
    // PedigreeMetadata, unlike every sibling declaration type. The original trigger for this test was a direct
    // checkAttributeOrder() call (attribute-order checking has since been removed entirely -- see
    // VCF_SPEC_AMBIGUITIES.md's "##META attribute order" entry -- so that method no longer exists); a missing
    // ID under VCFv4.3+ (warn-and-keep under a lenient context, per VcfMetadata.Builder.addPedigree) is used
    // instead to prove a PEDIGREE-sourced warning still correctly reaches the configured warnings file.
    String vcf = "##fileformat=VCFv4.3\n" +
        "##PEDIGREE=<Description=\"d\">\n" + // no ID -- required from VCFv4.3, warned rather than dropped
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "1\t100\t.\tA\tT\t.\t.\t.\n";
    Path vcfFile = tempDir.resolve("sample.vcf");
    Files.writeString(vcfFile, vcf);
    Path warningsFile = tempDir.resolve("warnings.log");

    try (VcfParser parser = new VcfParser.Builder()
        .fromFile(vcfFile)
        .warningsToFile(warningsFile)
        .parseWith((metadata, record) -> { })
        .build()) {
      parser.parse();
    }
    assertTrue(Files.readString(warningsFile).contains("PEDIGREE metadata declaration has no ID"));
  }

  @Test
  public void testMalformedMetadataAttributeWarnsAndDropsInsteadOfAbortingFile(@TempDir Path tempDir) throws IOException {
    // regression test: InternalUtils.extractProperties previously rethrew unconditionally when a comma-delimited
    // attribute inside a metadata declaration's <...> list failed to parse (here, an interior double comma
    // producing an empty attribute), aborting the ENTIRE file parse over one cosmetic header defect -- contradicting
    // AGENTS.md's Parser Validation Policy, which lists metadata declarations among the fields this parser is
    // lenient about. Confirmed live against bcftools 1.23.1 (htslib 1.23.1): it drops just the one malformed
    // declaration and continues (exit 0), never aborting the file. This fix is finer-grained than bcftools -- it
    // drops only the one malformed attribute, preserving the rest of the same declaration.
    String vcf = "##fileformat=VCFv4.2\n" +
        "##INFO=<ID=AF,,Number=1,Type=Float,Description=\"Allele frequency\">\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "1\t100\t.\tA\tT\t.\t.\tAF=0.5\n";
    Path vcfFile = tempDir.resolve("sample.vcf");
    Files.writeString(vcfFile, vcf);
    Path warningsFile = tempDir.resolve("warnings.log");

    VcfMetadata[] metadataHolder = new VcfMetadata[1];
    List<VcfPosition> positions = new ArrayList<>();
    try (VcfParser parser = new VcfParser.Builder()
        .fromFile(vcfFile)
        .warningsToFile(warningsFile)
        .parseWith((metadata, record) -> {
          VcfPosition position = record.getPosition();
          metadataHolder[0] = metadata;
          positions.add(position);
        })
        .build()) {
      parser.parse();
    }
    // the data line after the malformed declaration was reached -- the file parse did not abort. Wording
    // updated for the attribute-list tokenizer (InternalUtils.tokenizeAttributeList): the interior double comma
    // now produces a zero-length attribute detected directly by the tokenizer's own name scan, rather than by
    // extractProperties catching a downstream VcfFormatException and wrapping it as "Could not parse metadata
    // attribute".
    assertEquals(1, positions.size());
    assertTrue(Files.readString(warningsFile).contains("Metadata attribute \"\" has no '='; dropping it"));
    InfoMetadata af = metadataHolder[0].getInfo().get("AF");
    assertNotNull(af);
    assertEquals("1", af.getNumber());
    assertEquals(InfoType.Float, af.getType());
    assertEquals("Allele frequency", af.getDescription());
  }

  @Test
  public void testMetadataDeclarationWithDroppedAttributeSurvivesWriteAndReparse(@TempDir Path tempDir) throws IOException {
    // AGENTS.md's Writer Validation and Round-Trip Policy: "A record accepted by the parser must be writable
    // without losing fields or producing malformed column structure." Change 1 makes the parser accept a
    // declaration it previously rejected outright (a malformed attribute, dropped with a warning) -- this pins
    // that the resulting metadata still writes and reparses cleanly, with every SURVIVING attribute intact.
    String vcf = "##fileformat=VCFv4.2\n" +
        "##INFO=<ID=AF,,Number=1,Type=Float,Description=\"Allele frequency\">\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "1\t100\t.\tA\tT\t.\t.\tAF=0.5\n";
    Path vcfFile = tempDir.resolve("sample.vcf");
    Files.writeString(vcfFile, vcf);

    VcfMetadata[] metadataHolder = new VcfMetadata[1];
    List<VcfPosition> positions = new ArrayList<>();
    try (VcfParser parser = new VcfParser.Builder()
        .fromFile(vcfFile)
        .parseWith((metadata, record) -> {
          VcfPosition position = record.getPosition();
          metadataHolder[0] = metadata;
          positions.add(position);
        })
        .build()) {
      parser.parse();
    }

    StringWriter output = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(output)).build();
    writer.writeHeader(metadataHolder[0]);
    writer.writeLine(metadataHolder[0], positions.get(0), List.of());

    VcfMetadata[] reparsedMetadata = new VcfMetadata[1];
    List<VcfPosition> reparsedPositions = new ArrayList<>();
    try (VcfParser parser = new VcfParser.Builder()
        .fromReader(new BufferedReader(new StringReader(output.toString())))
        .parseWith((metadata, record) -> {
          VcfPosition position = record.getPosition();
          reparsedMetadata[0] = metadata;
          reparsedPositions.add(position);
        })
        .build()) {
      parser.parse();
    }
    assertEquals(1, reparsedPositions.size());
    InfoMetadata reparsedAf = reparsedMetadata[0].getInfo().get("AF");
    assertNotNull(reparsedAf);
    assertEquals("1", reparsedAf.getNumber());
    assertEquals(InfoType.Float, reparsedAf.getType());
    assertEquals("Allele frequency", reparsedAf.getDescription());
    assertEquals(List.of("0.5"), reparsedPositions.get(0).getInfo("AF"));
  }

  @Test
  public void testMalformedFormatKeyWarnsAndContinuesInsteadOfAbortingFile(@TempDir Path tempDir) throws IOException {
    // regression test for findings37.md item 1: VcfPosition.checkFormat previously threw VcfFormatException
    // unconditionally for a data-line FORMAT key failing InternalUtils.FORMAT_PATTERN, with no warn-and-recover
    // path at all -- unlike every other content check in the same method. Since VcfParser.doParse has no
    // per-line try/catch, this aborted the ENTIRE file over one non-compliant FORMAT key on one line, discarding
    // every other record too. Confirmed live against bcftools 1.23.1 (VCF_SPEC_AMBIGUITIES.md, "Data-line
    // FORMAT key fails tag-name grammar" entry): it only warns and parses every record.
    String vcf = "##fileformat=VCFv4.2\n" +
        "##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\ts1\n" +
        "1\t100\t.\tA\tT\t.\t.\t.\tGT:1KG\t0/1:5\n" +
        "1\t200\t.\tA\tT\t.\t.\t.\tGT\t0/1\n";
    Path vcfFile = tempDir.resolve("sample.vcf");
    Files.writeString(vcfFile, vcf);
    Path warningsFile = tempDir.resolve("warnings.log");

    List<VcfPosition> positions = new ArrayList<>();
    List<VcfSample> samples = new ArrayList<>();
    try (VcfParser parser = new VcfParser.Builder()
        .fromFile(vcfFile)
        .warningsToFile(warningsFile)
        .parseWith((metadata, record) -> {
          VcfPosition position = record.getPosition();
          List<VcfSample> sampleData = record.getSamples();
          positions.add(position);
          samples.addAll(sampleData);
        })
        .build()) {
      parser.parse();
    }
    // both records were reached -- the file parse did not abort after the first record's malformed FORMAT key
    assertEquals(2, positions.size());
    assertEquals(List.of("GT", "1KG"), positions.get(0).getFormat());
    assertEquals("5", samples.get(0).getProperty("1KG"));
    assertTrue(Files.readString(warningsFile).contains("tag-name grammar"));
  }

  @Test
  public void testRecordWithMalformedFormatKeySurvivesWriteAndReparse() throws IOException {
    // AGENTS.md's Writer Validation and Round-Trip Policy: a record accepted by the parser (here, one with a
    // malformed FORMAT key that is now warned about and kept, not rejected) must be writable and reparseable
    // without field loss.
    String input = "##fileformat=VCFv4.2\n" +
        "##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\ts1\n" +
        "1\t100\t.\tA\tT\t.\t.\t.\tGT:1KG\t0/1:5\n";
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
          public void parseLine(VcfMetadata ignored, VcfRecord record) {
            VcfPosition position = record.getPosition();
            List<VcfSample> values = record.getSamples();
            positions.add(position);
            samples.addAll(values);
          }
        })
        .build()) {
      parser.parse();
    }
    assertEquals("5", samples.get(0).getProperty("1KG"));

    StringWriter output = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(output)).build();
    writer.writeHeader(metadata[0]);
    writer.writeLine(metadata[0], positions.get(0), samples);

    List<VcfSample> reparsed = new ArrayList<>();
    try (VcfParser parser = new VcfParser.Builder()
        .fromReader(new BufferedReader(new StringReader(output.toString())))
        .parseWith((ignored, record) -> reparsed.addAll(record.getSamples()))
        .build()) {
      parser.parse();
    }
    assertEquals("5", reparsed.get(0).getProperty("1KG"));
  }

  @Test
  public void testFormatValidationCacheReusesValidatedListAcrossRepeatedFormatColumns() throws IOException {
    // real VCFs often repeat the same FORMAT string across large blocks of consecutive records; this pins that
    // each record still gets its own correct, independent FORMAT list when the cache is reused
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tS1\n" +
        "1\t100\t.\tA\tT\t.\t.\t.\tGT:DP:AD\t0/1:10:5,5\n" +
        "1\t101\t.\tA\tT\t.\t.\t.\tGT:DP:AD\t0/1:20:10,10\n" +
        "1\t102\t.\tA\tT\t.\t.\t.\tGT:DP:AD\t1/1:30:0,30\n";
    List<VcfPosition> positions = new ArrayList<>();
    try (VcfParser parser = new VcfParser.Builder()
        .fromReader(new BufferedReader(new StringReader(vcf)))
        .parseWith((metadata, record) -> positions.add(record.getPosition()))
        .build()) {
      parser.parse();
    }
    assertEquals(3, positions.size());
    for (VcfPosition position : positions) {
      assertEquals(List.of("GT", "DP", "AD"), position.getFormat());
    }
  }

  @Test
  public void testFormatValidationCacheHandlesRepeatedDotFormatColumn() throws IOException {
    // the FORMAT cache's raw-text key and VcfParser's own separate "." -> empty-list normalization (done
    // before the cache lookup, not inside checkFormat) overlap at exactly one point: a repeated literal "."
    // FORMAT column. This pins that a cache hit on "." correctly reuses an empty list (not the un-normalized
    // single-element ["."]), and that noFormatFields-driven behavior (a sample column with real content but
    // FORMAT is ".") still applies per-record, not just to the first occurrence.
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tS1\n" +
        "1\t100\t.\tA\tT\t.\t.\t.\t.\t.\n" +
        "1\t101\t.\tA\tT\t.\t.\t.\t.\t.\n";
    List<VcfPosition> positions = new ArrayList<>();
    try (VcfParser parser = new VcfParser.Builder()
        .fromReader(new BufferedReader(new StringReader(vcf)))
        .parseWith((metadata, record) -> positions.add(record.getPosition()))
        .build()) {
      parser.parse();
    }
    assertEquals(2, positions.size());
    assertTrue(positions.get(0).getFormat().isEmpty());
    assertTrue(positions.get(1).getFormat().isEmpty());
  }

  @Test
  public void testFormatValidationCacheWarnsOnlyOnceForRepeatedFormatWithEmptySubField(@TempDir Path tempDir)
      throws IOException {
    // deliberate, chosen behavior change (per this session's design discussion): a FORMAT that would warn
    // (here, an empty sub-field name from an interior double colon) warns only ONCE per contiguous block of
    // identical FORMAT text, not once per record -- the cache skips checkFormat's validation (and therefore
    // its warning) entirely on a cache hit
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tS1\n" +
        "1\t100\t.\tA\tT\t.\t.\t.\tGT::DP\t0/1::10\n" +
        "1\t101\t.\tA\tT\t.\t.\t.\tGT::DP\t0/1::10\n" +
        "1\t102\t.\tA\tT\t.\t.\t.\tGT::DP\t0/1::10\n";
    Path vcfFile = tempDir.resolve("sample.vcf");
    Files.writeString(vcfFile, vcf);
    Path warningsFile = tempDir.resolve("warnings.log");
    List<VcfPosition> positions = new ArrayList<>();
    try (VcfParser parser = new VcfParser.Builder()
        .fromFile(vcfFile)
        .warningsToFile(warningsFile)
        .parseWith((metadata, record) -> positions.add(record.getPosition()))
        .build()) {
      parser.parse();
    }
    assertEquals(3, positions.size());
    long warningCount = Files.readAllLines(warningsFile).stream()
        .filter(line -> line.contains("FORMAT contains an empty sub-field name"))
        .count();
    assertEquals(1, warningCount);
  }

  @Test
  public void testFormatValidationCacheMissesOnDifferentFormatThenRevalidates(@TempDir Path tempDir)
      throws IOException {
    // the cache holds only the single most-recently-validated (raw text, list) pair, not a full history map --
    // a different FORMAT column in between two otherwise-identical ones must still be a cache MISS on the
    // second occurrence, forcing a full re-validation (and re-warning) rather than being satisfied by the
    // no-longer-most-recent earlier occurrence. The trailing 4th record (repeating row 3's FORMAT again,
    // contiguously) is what makes this test distinguish "single most-recent slot" from "no cache at all" AND
    // from "a persistent map remembering every distinct FORMAT ever seen": no cache -> 3 warnings (every row
    // fully re-validates); single-slot (this design) -> 2 (rows 1 and 3 miss and warn, row 4 hits and doesn't);
    // a persistent map -> only 1 (row 3 would incorrectly hit against row 1's still-remembered entry).
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tS1\n" +
        "1\t100\t.\tA\tT\t.\t.\t.\tGT::DP\t0/1::10\n" +
        "1\t101\t.\tA\tT\t.\t.\t.\tGT:DP\t0/1:10\n" +
        "1\t102\t.\tA\tT\t.\t.\t.\tGT::DP\t0/1::10\n" +
        "1\t103\t.\tA\tT\t.\t.\t.\tGT::DP\t0/1::10\n";
    Path vcfFile = tempDir.resolve("sample.vcf");
    Files.writeString(vcfFile, vcf);
    Path warningsFile = tempDir.resolve("warnings.log");
    List<VcfPosition> positions = new ArrayList<>();
    try (VcfParser parser = new VcfParser.Builder()
        .fromFile(vcfFile)
        .warningsToFile(warningsFile)
        .parseWith((metadata, record) -> positions.add(record.getPosition()))
        .build()) {
      parser.parse();
    }
    assertEquals(4, positions.size());
    long warningCount = Files.readAllLines(warningsFile).stream()
        .filter(line -> line.contains("FORMAT contains an empty sub-field name"))
        .count();
    assertEquals(2, warningCount);
  }

  @Test
  public void testWarningsToFileDefaultNameDerivedFromSourcePath(@org.junit.jupiter.api.io.TempDir Path tempDir)
      throws IOException {
    // a metadata line with no ID triggers a warning during parseMetadata() -- see VcfMetadata.addInfo
    String vcf = "##fileformat=VCFv4.2\n" +
        "##INFO=<Number=1,Type=String,Description=\"d\">\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n";
    Path vcfFile = tempDir.resolve("sample.vcf");
    java.nio.file.Files.writeString(vcfFile, vcf);

    VcfParser parser = new VcfParser.Builder()
        .fromFile(vcfFile)
        .warningsToFile()
        .parseWith(new MemoryMappedVcfLineParser.Builder().build())
        .build();
    parser.parse();
    parser.close();

    Path expectedWarningsFile = tempDir.resolve("sample.vcf.warn.log");
    assertTrue(java.nio.file.Files.exists(expectedWarningsFile));
    assertTrue(java.nio.file.Files.readString(expectedWarningsFile).contains("no ID"));
  }

  @Test
  public void testWarningsToFileCustomPath(@org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
    String vcf = "##fileformat=VCFv4.2\n" +
        "##INFO=<Number=1,Type=String,Description=\"d\">\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n";
    Path vcfFile = tempDir.resolve("sample.vcf");
    java.nio.file.Files.writeString(vcfFile, vcf);
    Path customFile = tempDir.resolve("custom-name.log");

    VcfParser parser = new VcfParser.Builder()
        .fromFile(vcfFile)
        .warningsToFile(customFile)
        .parseWith(new MemoryMappedVcfLineParser.Builder().build())
        .build();
    parser.parse();
    parser.close();

    assertTrue(java.nio.file.Files.exists(customFile));
    assertFalse(java.nio.file.Files.exists(tempDir.resolve("sample.vcf.warn.log")));
  }

  @Test
  public void testWarningsToFileWithNoArgAndFromReaderThrowsIllegalState() {
    assertThrows(IllegalStateException.class, () ->
        new VcfParser.Builder()
            .fromReader(new java.io.BufferedReader(new java.io.StringReader("##fileformat=VCFv4.2\n")))
            .warningsToFile()
            .parseWith(new MemoryMappedVcfLineParser.Builder().build())
            .build());
  }

  @Test
  public void testWarningsToFileSuppressesSlf4jOutput(@org.junit.jupiter.api.io.TempDir Path tempDir)
      throws IOException {
    String vcf = "##fileformat=VCFv4.2\n" +
        "##INFO=<Number=1,Type=String,Description=\"d\">\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n";
    Path vcfFile = tempDir.resolve("sample.vcf");
    java.nio.file.Files.writeString(vcfFile, vcf);

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      VcfParser parser = new VcfParser.Builder()
          .fromFile(vcfFile)
          .warningsToFile()
          .parseWith(new MemoryMappedVcfLineParser.Builder().build())
          .build();
      parser.parse();
      parser.close();
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testWarningsToFileCapturesLazyGetterFiredFromAnotherThreadDuringParse(@TempDir Path tempDir)
      throws IOException, InterruptedException {
    // this is the concrete improvement the redesign delivers: with the old ThreadLocal design, a lazy getter
    // (getQuality()) called from a thread OTHER than the one running parse() would never see the active file --
    // ThreadLocal is, by definition, thread-local. With m_warnings passed directly into each VcfPosition, any
    // thread holding that position now correctly routes to the same destination the parsing thread configured.
    //
    // Correction from this plan's external review (finding #1): calling parser.parse() to completion FIRST and
    // only then starting the other thread does not actually test "during parse" -- parse() has already closed
    // the warnings file by the time the other thread runs, so that shape only re-tests the after-close fallback
    // (the second test below). Genuinely exercising "during parse, from another thread" requires the parsing
    // thread to still be inside parse() -- i.e. inside parseLine() -- when the other thread fires the getter, so
    // a latch blocks parseLine() from returning (and thus blocks parse()'s finally-block close()) until the
    // getter thread has run.
    //
    // Second-round correction (finding #2): the first version used raw Thread + join(), which never
    // propagates an exception (including a failed assertTrue inside parseLine(), or an IOException from
    // parse()) back to the JUnit test -- an uncaught exception on a raw Thread only prints to stderr, so a
    // genuine bug here could go undetected while the test still reports green. Using ExecutorService/Future
    // makes get() re-throw whatever either task threw, with a bounded timeout so a real deadlock fails fast
    // instead of hanging the suite.
    //
    // Third-round correction (finding #3): the second version's cleanup only released getterDone (letting the
    // still-blocked parse thread proceed) on the success path -- if getterTask.get() itself threw or timed
    // out, the outer finally called only pool.shutdown(), which does not interrupt a thread already blocked
    // inside parseLine()'s own latch.await(); the parse thread would then sit blocked for its own 5-second
    // timeout on every such failure, and the pool could still be running when the test method returned. Now
    // getterDone is released in an inner finally (so a getter failure doesn't strand the parse thread) and
    // released again in the outer finally as a second safety net, and shutdownNow() + awaitTermination()
    // replace the bare shutdown() so a failed run can't leak a running thread into later tests.
    Path vcfFile = tempDir.resolve("test.vcf");
    Files.writeString(vcfFile, "##fileformat=VCFv4.2\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "1\t100\t.\tA\tT\tnotanumber\t.\t.\n");
    Path warningsFile = tempDir.resolve("warnings.log");
    java.util.concurrent.CountDownLatch positionReady = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch getterDone = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicReference<VcfPosition> positionRef = new java.util.concurrent.atomic.AtomicReference<>();
    VcfParser parser = new VcfParser.Builder()
        .fromFile(vcfFile)
        .warningsToFile(warningsFile)
        .parseWith(new VcfLineParser() {
          @Override public void parseMetadata(VcfMetadata metadata) {}
          @Override public void parseLine(VcfMetadata metadata, VcfRecord record) {
            VcfPosition position = record.getPosition();
            positionRef.set(position);
            positionReady.countDown();
            try {
              // block the parsing thread here -- keeping the warnings file open -- until the other thread has
              // fired the lazy getter; this is what actually exercises "during parse", not just "before close()"
              if (!getterDone.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new AssertionError("getterThread did not signal completion within 5 seconds");
              }
            } catch (InterruptedException e) {
              throw new RuntimeException(e);
            }
          }
        })
        .build();
    java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      java.util.concurrent.Future<?> parseTask = pool.submit(() -> {
        try {
          parser.parse();
        } catch (IOException e) {
          throw new java.io.UncheckedIOException(e);
        }
      });
      assertTrue(positionReady.await(5, java.util.concurrent.TimeUnit.SECONDS));
      java.util.concurrent.Future<?> getterTask = pool.submit(() -> positionRef.get().getQuality());
      try {
        // getterTask completing proves the lazy getter ran and returned without throwing
        assertDoesNotThrow(() -> getterTask.get(5, java.util.concurrent.TimeUnit.SECONDS));
      } finally {
        // release the parse thread regardless of whether the getter succeeded, failed, or timed out --
        // otherwise a getter failure leaves the parse thread blocked in parseLine() needlessly
        getterDone.countDown();
      }
      // separately confirm the parse thread itself also completed cleanly
      assertDoesNotThrow(() -> parseTask.get(5, java.util.concurrent.TimeUnit.SECONDS));
    } finally {
      getterDone.countDown(); // second safety net -- countDown() past zero is a no-op, never harmful
      pool.shutdownNow();
      assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
    }
    assertTrue(Files.readString(warningsFile).contains("is not a number"));
  }

  @Test
  public void testLazyGetterFiredAfterParseReturnsFallsBackToSharedLoggerNotClosedFileWrite(@TempDir Path tempDir)
      throws IOException {
    // documented behavior, preserved: a lazy warning after parse() has returned (and its warnings file closed)
    // does not throw and does not write to the closed file -- it falls back to the shared logger, via
    // VcfWarnings's own closed-flag fallback
    Path vcfFile = tempDir.resolve("test.vcf");
    Files.writeString(vcfFile, "##fileformat=VCFv4.2\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "1\t100\t.\tA\tT\tnotanumber\t.\t.\n");
    Path warningsFile = tempDir.resolve("warnings.log");
    List<VcfPosition> captured = new ArrayList<>();
    VcfParser parser = new VcfParser.Builder()
        .fromFile(vcfFile)
        .warningsToFile(warningsFile)
        .parseWith(new VcfLineParser() {
          @Override public void parseMetadata(VcfMetadata metadata) {}
          @Override public void parseLine(VcfMetadata metadata, VcfRecord record) {
            VcfPosition position = record.getPosition();
            captured.add(position);
          }
        })
        .build();
    parser.parse();
    assertDoesNotThrow(() -> captured.get(0).getQuality());
    assertFalse(Files.readString(warningsFile).contains("is not a number"));
  }
  // ---- VcfContext wiring ----

  private static final String MINIMAL_BODY =
      "##contig=<ID=1>\n" +
      // declared so the file is warning-free on its own terms -- testImplementedDeclaredVersionEmitsNoVersionWarning
      // asserts a completely empty warnings file, which an undeclared sample column would otherwise spoil
      "##SAMPLE=<ID=S1,Description=\"s\">\n" +
      "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tS1\n" +
      "1\t100\t.\tA\tT\t.\tPASS\t.\tGT\t0/1\n";

  private static Path writeMinimalVcf(Path dir, String fileFormat) throws IOException {
    Path file = dir.resolve("minimal-" + fileFormat + ".vcf");
    Files.writeString(file, "##fileformat=" + fileFormat + "\n" + MINIMAL_BODY);
    return file;
  }

  @Test
  void testParserProducedRecordCarriesTheParseContext() throws Exception {
    // fails outright if the parser ever routes through a context-less construction path again
    List<VcfRecord> seen = new ArrayList<>();
    VcfMetadata[] md = new VcfMetadata[1];
    try (BufferedReader reader = new BufferedReader(new StringReader(
        "##fileformat=VCFv4.2\n" + MINIMAL_BODY));
         VcfParser parser = new VcfParser.Builder().fromReader(reader).parseWith((metadata, record) -> {
           md[0] = metadata;
           seen.add(record);
         }).build()) {
      parser.parse();
    }
    VcfRecord record = seen.get(0);
    assertSame(md[0].getContext(), record.getContext());
    assertSame(record.getContext(), record.getPosition().getContext());
    assertSame(record.getContext(), record.getSamples().get(0).getContext());
  }

  @Test
  void testUnimplementedDeclaredVersionWarnsOnceAndStillParses(@TempDir Path tempDir) throws Exception {
    // a VCFv4.5 file parses normally, but reports that it is being interpreted as 4.3. This is the one new
    // diagnostic wiring VcfContext into VcfParser introduces, and the hts-specs conformance gate cannot see
    // it (passed fixtures are checked for exceptions only), so it is pinned here.
    Path warningsFile = tempDir.resolve("warnings.txt");
    List<VcfRecord> seen = new ArrayList<>();
    try (VcfParser parser = new VcfParser.Builder()
        .fromFile(writeMinimalVcf(tempDir, "VCFv4.5"))
        .warningsToFile(warningsFile)
        .parseWith((metadata, record) -> seen.add(record))
        .build()) {
      parser.parse();
    }
    assertEquals(1, seen.size());
    String warnings = Files.readString(warningsFile);
    assertThat(warnings, containsString("VCFv4.5"));
    assertThat(warnings, containsString("4.3"));
    assertEquals(1, warnings.lines().filter(l -> l.contains("does not implement")).count(), warnings);
  }

  @Test
  void testImplementedDeclaredVersionEmitsNoVersionWarning(@TempDir Path tempDir) throws Exception {
    // The regression guard that matters most: the overwhelming majority of this library's real traffic is
    // VCFv4.2, and a spurious per-file version warning on it would be a genuine regression.
    for (String version : new String[] {"VCFv4.1", "VCFv4.2", "VCFv4.3"}) {
      Path warningsFile = tempDir.resolve(version + "-warnings.txt");
      try (VcfParser parser = new VcfParser.Builder()
          .fromFile(writeMinimalVcf(tempDir, version))
          .warningsToFile(warningsFile)
          .parseWith((metadata, record) -> {})
          .build()) {
        parser.parse();
      }
      String warnings = Files.readString(warningsFile);
      assertEquals("", warnings, version + " must parse with no warnings at all, least of all about its version");
    }
  }

  @Test
  void testParseMetadataThenParseSharesOneWarningsDestination(@TempDir Path tempDir) throws Exception {
    // regression test: the VcfContext captures the warnings destination permanently, at the moment
    // parseMetadata() builds it. parse() used to be the only thing that opened the warnings file, so calling
    // parseMetadata() directly first bound the context to the default logger and parse() then swapped the
    // file-backed sink in underneath it -- one parse, two destinations: every model object's warning to the
    // logger, this parser's own direct warn() calls to the file.
    Path file = tempDir.resolve("split.vcf");
    Files.writeString(file,
        "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tS1\n" +
        "1\t100\trs1;rs1\tA\tT\t.\tPASS\t.\tGT\t0/1\n");
    Path warningsFile = tempDir.resolve("warnings.txt");
    try (VcfParser parser = new VcfParser.Builder()
        .fromFile(file)
        .warningsToFile(warningsFile)
        .parseWith((metadata, record) -> {})
        .build()) {
      parser.parseMetadata();   // deliberately called directly, before parse()
      parser.parse();
    }
    String warnings = Files.readString(warningsFile);
    // the parser's own direct warn() site, from parseMetadata()
    assertThat(warnings, containsString("samples in the header but 0 in the metadata"));
    // a warning routed through the VcfContext instead -- VcfPosition.checkIds, reached while parsing the data
    // line. Both must land in the same file.
    assertThat(warnings, containsString("Duplicate ID"));
  }

  @Test
  void testParseMetadataThenParseNextLineFlushesWarningsFileOnClose(@TempDir Path tempDir) throws Exception {
    // regression test: every other warningsToFile test in this class drives the parse through parse(), whose
    // own finally already calls closeWarningsFile() directly. This test never calls parse() at all -- it drives
    // parseMetadata()/parseNextLine() directly, the flow that made close() itself responsible for
    // closeWarningsFile() -- so it's the only test that actually exercises that path. The warnings sink is
    // buffered and not flushed per warn(), so without close()'s closeWarningsFile() this warning would never
    // reach the file.
    Path file = tempDir.resolve("direct.vcf");
    Files.writeString(file,
        "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tS1\n" +
        "1\t100\trs1;rs1\tA\tT\t.\tPASS\t.\tGT\t0/1\n");
    Path warningsFile = tempDir.resolve("warnings.txt");
    VcfParser parser = new VcfParser.Builder()
        .fromFile(file)
        .warningsToFile(warningsFile)
        .parseWith((metadata, record) -> {})
        .build();
    parser.parseMetadata();
    boolean hasNext = true;
    while (hasNext) {
      hasNext = parser.parseNextLine();
    }
    parser.close();
    String warnings = Files.readString(warningsFile);
    assertThat(warnings, containsString("Duplicate ID"));
  }

  /**
   * The declared {@code ##fileformat} values every rule in VALIDATION_REFERENCE.md's "Rules applied at every
   * declared version" section must behave identically under. Each of the tests below asserts
   * one such rule fires the same way at all three, so a future change that gates one of them on the declared
   * version fails here rather than silently diverging.
   */
  private static final List<String> UNGATED_RULE_VERSIONS = List.of("VCFv4.1", "VCFv4.2", "VCFv4.3");

  private static final String UNGATED_RULE_HEADER = "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n";

  /** Parses {@code vcf} fully, with every deferred/lazy check triggered, and returns the warnings captured. */
  private static List<String> warningsFromFullParse(String vcf) throws IOException {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      try (BufferedReader reader = new BufferedReader(new StringReader(vcf));
           VcfParser parser = new VcfParser.Builder()
               .fromReader(reader)
               .parseWith(new org.pharmgkb.parser.vcf.spec.DeferredValidationLineParser())
               .build()) {
        parser.parse();
      }
      return appender.getRelevantWarnOrHigherMessages(Set.of());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  private static void assertWarnsAtEveryVersion(Function<String, String> vcfForVersion,
      String expectedSubstring) throws IOException {
    for (String version : UNGATED_RULE_VERSIONS) {
      List<String> warnings = warningsFromFullParse(vcfForVersion.apply(version));
      assertTrue(warnings.stream().anyMatch(w -> w.contains(expectedSubstring)),
          () -> version + ": expected a warning containing \"" + expectedSubstring + "\", got " + warnings);
    }
  }

  @Test
  void testInfoValueSpaceIsAcceptedAtEveryDeclaredVersion() throws IOException {
    // VCFv4.3.tex line 366's "Space characters are allowed in values" is adopted unconditionally, so a space
    // must survive parsing as part of ONE INFO value even under a VCFv4.1/VCFv4.2 declaration, where
    // VCFv4.2.tex line 186 forbids it.
    for (String version : UNGATED_RULE_VERSIONS) {
      String vcf = "##fileformat=" + version + "\n" +
          "##INFO=<ID=AA,Number=1,Type=String,Description=\"d\">\n" +
          UNGATED_RULE_HEADER +
          "chr1\t1\t.\tA\tT\t.\t.\tAA=a b\n";
      List<String> values = new ArrayList<>();
      try (BufferedReader reader = new BufferedReader(new StringReader(vcf));
           VcfParser parser = new VcfParser.Builder()
               .fromReader(reader)
               .parseWith((metadata, record) -> values.addAll(record.getPosition().getInfo().get("AA")))
               .build()) {
        parser.parse();
      }
      assertEquals(Collections.singletonList("a b"), values, version);
    }
  }

  @Test
  void testInfoValueVerticalTabIsRejectedAtEveryDeclaredVersion() throws IOException {
    // The one non-space whitespace character a real parsed INFO value can actually carry: a tab is the column
    // delimiter and CR/LF end the record, but U+000B and U+000C are neither, so they reach checkInfoEntries
    // from a genuine file. VCFv4.3.tex line 81 disallows them ("non-printable characters U+0000--U+0008,
    // U+000B--U+000C, U+000E--U+001F"), unconditionally in this library, and the space relaxation must not
    // have widened to them.
    for (String version : UNGATED_RULE_VERSIONS) {
      String vcf = "##fileformat=" + version + "\n" +
          "##INFO=<ID=AA,Number=1,Type=String,Description=\"d\">\n" +
          UNGATED_RULE_HEADER +
          "chr1\t1\t.\tA\tT\t.\t.\tAA=a\u000Bb\n";
      assertThrows(VcfFormatException.class, () -> {
        try (BufferedReader reader = new BufferedReader(new StringReader(vcf));
             VcfParser parser = new VcfParser.Builder()
                 .fromReader(reader)
                 .parseWith((metadata, record) -> record.getPosition().getInfo())
                 .build()) {
          parser.parse();
        }
      }, version);
    }
  }

  @Test
  void testInfoDeclarationIdGrammarIsCheckedAtEveryDeclaredVersion() throws IOException {
    // VCFv4.3.tex line 368's INFO tag-name grammar; VCFv4.2 states none at all.
    assertWarnsAtEveryVersion(version -> "##fileformat=" + version + "\n" +
            "##INFO=<ID=1BAD,Number=1,Type=String,Description=\"d\">\n" + UNGATED_RULE_HEADER,
        "does not match the required tag-name grammar");
  }

  @Test
  void testContigIdCharacterSetIsCheckedAtEveryDeclaredVersion() throws IOException {
    // VCFv4.3.tex lines 2136-2138's SAM-aligned contig character set; VCFv4.2.tex line 179 states only "no
    // whitespace permitted", and says nothing about ##contig IDs at all.
    assertWarnsAtEveryVersion(version -> "##fileformat=" + version + "\n" +
            "##contig=<ID=c(tg1>\n" + UNGATED_RULE_HEADER,
        "contains the invalid character");
  }

  @Test
  void testZeroLengthFieldEntryIsRecoveredAtEveryDeclaredVersion() throws IOException {
    // VCFv4.3.tex line 39, "Zero length fields are not allowed, a dot (\".\") must be used instead"; VCFv4.2 is
    // silent. See EMPTY_FIELD_HANDLING.md for the per-field normalization this warning accompanies.
    assertWarnsAtEveryVersion(version -> "##fileformat=" + version + "\n" + UNGATED_RULE_HEADER +
            "chr1\t1\trs1;;rs2\tA\tT\t.\t.\t.\n",
        "ID contains an empty entry");
  }

  @Test
  void testDuplicateStructuredDeclarationIdIsCheckedAtEveryDeclaredVersion() throws IOException {
    // VCFv4.3.tex line 121, "All structured lines require an ID which must be unique within their type";
    // VCFv4.2 is silent. See DUPLICATE_METADATA_ID_HANDLING.md for the keep-the-first behavior.
    assertWarnsAtEveryVersion(version -> "##fileformat=" + version + "\n" +
            "##INFO=<ID=DP,Number=1,Type=Integer,Description=\"a\">\n" +
            "##INFO=<ID=DP,Number=1,Type=Integer,Description=\"b\">\n" + UNGATED_RULE_HEADER,
        "Duplicate ID \"DP\"");
  }

  @Test
  void testPhaseSet32BitRangeIsCheckedAtEveryDeclaredVersion() throws IOException {
    // VCFv4.3.tex line 100 states Integer is 32-bit signed generally; VCFv4.2 states it for exactly one key,
    // FORMAT/PS (VCFv4.2.tex line 230, "Non-negative 32-bit Integer"), which is the one place this library
    // range-checks today. Either way the check is version-independent.
    assertWarnsAtEveryVersion(version -> "##fileformat=" + version + "\n" +
            "##FORMAT=<ID=PS,Number=1,Type=Integer,Description=\"d\">\n" +
            "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tS1\n" +
            "chr1\t1\t.\tA\tT\t.\t.\t.\tPS\t4294967296\n",
        "is not a non-negative 32-bit integer");
  }

  @Test
  void testByteOrderMarkIsRejected() throws IOException {
    // VCFv4.3.tex line 80, "VCF files must not contain a byte order mark" (VCFv4.4.tex line 80, VCFv4.5.tex
    // line 78); VCFv4.2 never names an encoding at all. Deliberately NOT looped over UNGATED_RULE_VERSIONS the
    // way the other rules in this group are: a BOM makes the first line something other than a well-formed
    // ##fileformat declaration, so parsing fails before any version is known. This rule is version-independent
    // by construction rather than by choice, and looping would only assert the same thing three times.
    String vcf = "\uFEFF##fileformat=VCFv4.2\n" + UNGATED_RULE_HEADER;
    assertThrows(VcfFormatException.class, () -> parseMetadataOf(vcf));
  }

  @Test
  void bracketValuesOnlyProtectCommasForMetaValues() throws Exception {
    // bracket-list quoting is scoped to ##META's Values -- see VCF_SPEC_AMBIGUITIES.md's "General
    // bracket-list quoting beyond ##META's Values" entry. metadata_brackets.vcf carries the spec's own shape
    // on ##META's Values (protected) alongside the identical shape on ##contig's/##SAMPLE's OWN attributes
    // (not protected -- the comma still splits them, exactly like an unquoted '<' would).
    VcfMetadata[] holder = new VcfMetadata[1];
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(
        VcfParserTest.class.getResourceAsStream("/metadata_brackets.vcf")))) {
      new VcfParser.Builder().fromReader(reader).parseWith(new VcfLineParser() {
        @Override
        public void parseLine(VcfMetadata metadata, VcfRecord record) {}
        @Override
        public void parseMetadata(VcfMetadata metadata) {
          holder[0] = metadata;
        }
      }).build().parse();
    }
    assertEquals("[WholeGenome, Exome]", holder[0].getMetas().get("Assay").getPropertyRaw("Values"));
    // the comma inside "[a, b]" is NOT protected here -- 'x' is not ##META's Values, so it splits at the
    // first comma, and the unnamed " b]" remainder has no '=' and is dropped. The truncated value has no
    // closing ']' of its own, so it is quoted for safety rather than stored raw (writing "x=[a" back out
    // verbatim would manufacture a value bcftools cannot read at all -- see AttributeListTokenizerTest
    // #bracketValueDoesNotProtectCommasElsewhereButStaysBcftoolsSafe for the regression this pins).
    assertEquals("\"[a\"", holder[0].getContigs().get("c1").getPropertyRaw("x"));
    assertEquals("999", holder[0].getContigs().get("c1").getPropertyRaw("length"));
    assertEquals("\"[c\"", holder[0].getSamples().get("s1").getPropertyRaw("Assay"));
  }

  @Test
  void aFabricatedDuplicateIdDoesNotStealAContigDeclarationsIdentity() throws Exception {
    // Before keep-first, the truncation tail "ID=fake>B" displaced the real ID, so contig c1 ceased to exist and
    // every record with CHROM=c1 lost its declaration.
    String vcf = "##fileformat=VCFv4.3\n"
        + "##contig=<ID=c1,x=A<1,ID=fake>B,length=999>\n"
        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n";
    try (VcfParser parser = new VcfParser.Builder()
        .fromReader(new BufferedReader(new StringReader(vcf)))
        .parseWith((position, samples) -> { })
        .build()) {
      VcfMetadata metadata = parser.parseMetadata();
      parser.parse();
      assertTrue(metadata.getContigs().containsKey("c1"));
      assertFalse(metadata.getContigs().containsKey("fake>B"));
    }
  }

}
