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
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.pharmgkb.common.util.PathUtils;
import org.pharmgkb.parser.vcf.model.IdDescriptionMetadata;
import org.pharmgkb.parser.vcf.model.ReservedFormatProperty;
import org.pharmgkb.parser.vcf.model.ReservedInfoProperty;
import org.pharmgkb.parser.vcf.model.VcfFloat;
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
               assertEquals(Arrays.asList("PASS"), position.getFilters());
               assertTrue(position.getInfo().isEmpty());
             })
             .build()) {
      parser.parse();
    }
  }

  @Test
  void testDeferredInfoValidationExceptionCarriesLineNumberAfterParseReturns() throws IOException {
    // regression test for findings19.md's line-number gap: a VcfFormatException from VcfPosition.getInfo()'s
    // lazy-parse path, thrown AFTER parser.parse() has already returned (not from inside the parseWith callback),
    // must still carry the correct originating line number -- this was previously silently lost, since
    // VcfParser's own line-tracking call stack is gone by the time a caller inspects a collected position later
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "chr1\t1\t.\tA\tT\t.\t.\t.;DP=1\n";
    List<VcfPosition> positions = new ArrayList<>();
    try (VcfParser parser = new VcfParser.Builder()
        .fromReader(new BufferedReader(new StringReader(vcf)))
        .parseWith((metadata, position, sampleData) -> positions.add(position))
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
    // regression test: VcfPosition.getInfo(ReservedInfoProperty)'s call to VcfUtils.convertProperty(...) is a
    // fourth deferred throw site distinct from getInfo()'s lazy-parse path covered above -- DP (Depth) is declared
    // as a Long-typed reserved property, so a non-numeric raw value fails type conversion only when this typed
    // accessor is used, after parser.parse() has already returned.
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "chr1\t1\t.\tA\tT\t.\t.\tDP=abc\n";
    List<VcfPosition> positions = new ArrayList<>();
    try (VcfParser parser = new VcfParser.Builder()
        .fromReader(new BufferedReader(new StringReader(vcf)))
        .parseWith((metadata, position, sampleData) -> positions.add(position))
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
             .parseWith((metadata, position, sampleData) -> {

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
                   List<VcfFloat> d = sample.getProperty(ReservedFormatProperty.GenotypePosteriorProbabilitiesPhredScaled);
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
  void testMissingHeaderLeavesMetadataNullAndAllowsRetry() throws IOException {
    // a file that runs out of lines entirely right after ##fileformat (not even a stray non-metadata line)
    // reaches VcfParser.parseMetadata()'s "m_vcfMetadata = mdBuilder.build()" line before the "!foundHeader"
    // check fires. getMetadata() must stay null after the throw (not a structurally-broken non-null object with
    // getNumColumns() == 0), and a retry must not be blocked by the "already parsed" guard.
    String vcf = "##fileformat=VCFv4.2\n";
    try (BufferedReader reader = new BufferedReader(new StringReader(vcf));
         VcfParser parser = new VcfParser.Builder()
             .fromReader(reader)
             .parseWith((metadata, position, sampleData) -> { })
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
             .parseWith((metadata, position, sampleData) -> captured[0] = metadata)
             .build()) {
      parser.parse();
    }
    assertEquals(1, captured[0].getInfo().size());
    assertTrue(captured[0].getInfo().containsKey("DP"));
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
             .parseWith((metadata, position, sampleData) -> captured[0] = metadata)
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
             .parseWith((metadata, position, sampleData) -> positions.add(position))
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
             .parseWith((metadata, position, sampleData) -> {
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
             .parseWith((metadata, position, sampleData) -> captured.addAll(sampleData))
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
  void testMutatingFormatDoesNotCorruptSampleProperties() throws IOException {
    // regression test: VcfParser used to hand the identical FORMAT List<String> object to both VcfPosition and
    // every VcfSample on the same line. VcfPosition.getFormat() is documented and tested as safe to mutate in
    // place, but VcfSample's own parallel keys/values lists require keys.size() == values.size() -- mutating the
    // shared list via getFormat() used to desync VcfSample's internal state and misdirect getProperty() lookups
    // to the wrong value, or throw IndexOutOfBoundsException, with no warning.
    List<VcfSample> rsbSamples = new ArrayList<>();
    List<VcfPosition> rsbPositions = new ArrayList<>();
    try (BufferedReader reader = Files.newBufferedReader(PathUtils.getPathToResource("/vcfposition.vcf"));
         VcfParser parser = new VcfParser.Builder()
             .fromReader(reader)
             .parseWith((metadata, position, sampleData) -> {
               if (position.getIds().contains("rsb")) {
                 position.getFormat().remove("DP");
                 rsbSamples.addAll(sampleData);
                 rsbPositions.add(position);
               }
             })
             .build()) {
      parser.parse();
    }
    assertEquals(1, rsbSamples.size());
    VcfSample sample = rsbSamples.get(0);
    // FORMAT for the "rsb" line (before mutation) is GT:DP:FT:GL:GLE:PL:GP:GQ:HQ:PS:PQ:EC:MQ with values
    // 0|1:5:ftstring:0.3,0.4:glestring:5,50:0.05,0.06:30:10,20:1:45:2,2:30 -- FT is the 3rd key/value, unaffected
    // by removing the 2nd key (DP) from VcfPosition's own copy, if VcfSample's copy is properly independent.
    assertEquals("ftstring", sample.getProperty("FT"));
    assertEquals("0|1", sample.getProperty("GT"));
    assertEquals("5", sample.getProperty("DP"));
    // VcfPosition's own copy must actually reflect the mutation -- it's the independent VcfSample copy being
    // unaffected by it that this test is about.
    assertFalse(rsbPositions.get(0).getFormat().contains("DP"));
  }

  @Test
  void testAddingFormatKeyDoesNotCorruptSampleProperties() throws IOException {
    // companion to testMutatingFormatDoesNotCorruptSampleProperties: covers the other mutation direction
    // (growing the list instead of shrinking it), which used to throw IndexOutOfBoundsException the first time
    // VcfSample materializes its property map (getPropertyKeys()/getProperty()/containsProperty() all route
    // through the same materialization once any of them is called).
    List<VcfSample> rsbSamples = new ArrayList<>();
    List<VcfPosition> rsbPositions = new ArrayList<>();
    try (BufferedReader reader = Files.newBufferedReader(PathUtils.getPathToResource("/vcfposition.vcf"));
         VcfParser parser = new VcfParser.Builder()
             .fromReader(reader)
             .parseWith((metadata, position, sampleData) -> {
               if (position.getIds().contains("rsb")) {
                 position.getFormat().add("NEWKEY");
                 rsbSamples.addAll(sampleData);
                 rsbPositions.add(position);
               }
             })
             .build()) {
      parser.parse();
    }
    assertEquals(1, rsbSamples.size());
    VcfSample sample = rsbSamples.get(0);
    // getPropertyKeys() is called first because it's the call that actually triggers materialization (and thus
    // the crash, pre-fix) -- containsProperty()/getProperty() alone would not have reached the corrupted index.
    assertEquals(13, sample.getPropertyKeys().size());
    assertFalse(sample.containsProperty("NEWKEY"));
    assertEquals("ftstring", sample.getProperty("FT"));
    assertEquals("0|1", sample.getProperty("GT"));
    // VcfPosition's own copy must actually reflect the mutation -- it's the independent VcfSample copy being
    // unaffected by it that this test is about.
    assertTrue(rsbPositions.get(0).getFormat().contains("NEWKEY"));
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
             .parseWith((metadata, position, sampleData) -> positions.add(position))
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

  @Test
  void testEmptyInputReportsNoLineNumber() throws IOException {
    // regression test: a completely empty input previously reported "[Line #1]" even though no line was
    // ever actually read -- m_lineNumber was incremented unconditionally before checking whether
    // readLine() returned anything. Now uses -1 (the "no line number attached" sentinel).
    try (BufferedReader reader = new BufferedReader(new StringReader(""));
         VcfParser parser = new VcfParser.Builder()
             .fromReader(reader)
             .parseWith((metadata, position, sampleData) -> {})
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
             .parseWith((metadata, position, sampleData) -> {})
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
    // Regression test for findings19.md Important #1: VcfParser.parseMetadataProperty passes a literal label
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
}
