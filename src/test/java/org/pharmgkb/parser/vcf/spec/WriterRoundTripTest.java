package org.pharmgkb.parser.vcf.spec;

import java.io.IOException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link WriterRoundTrip}.
 */
class WriterRoundTripTest {

  private static final String SAMPLE_VCF =
      "##fileformat=VCFv4.2\n" +
      "##INFO=<ID=DP,Number=1,Type=Integer,Description=\"Depth\">\n" +
      "##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n" +
      "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tS1\n" +
      "1\t100\trs1\tA\tT\t.\tPASS\tDP=10\tGT\t0/1\n";

  @Test
  void testByteDiffIdenticalTextsMatch() {
    assertEquals(Optional.empty(), WriterRoundTrip.byteDiff("A\nB\n", "A\nB\n"));
  }

  @Test
  void testByteDiffDifferentTextsDetected() {
    Optional<String> diff = WriterRoundTrip.byteDiff("A\nB\n", "A\nC\n");
    assertTrue(diff.isPresent());
    assertTrue(diff.get().contains("line 2"));
  }

  @Test
  void testByteDiffIgnoresLineEndingDifferences() {
    assertEquals(Optional.empty(), WriterRoundTrip.byteDiff("A\r\nB\r\n", "A\nB\n"));
  }

  @Test
  void testParseWriteReparseRoundTripOfSimpleFixtureMatchesSemantically() throws IOException {
    WriterRoundTrip.ParsedFile before = WriterRoundTrip.parseText(SAMPLE_VCF);
    String written = WriterRoundTrip.write(before);
    WriterRoundTrip.ParsedFile after = WriterRoundTrip.parseText(written);
    assertEquals(Optional.empty(), WriterRoundTrip.semanticDiff(before, after));
  }

  @Test
  void testSemanticDiffDetectsRefMismatch() throws IOException {
    WriterRoundTrip.ParsedFile before = WriterRoundTrip.parseText(SAMPLE_VCF);
    WriterRoundTrip.ParsedFile after = WriterRoundTrip.parseText(SAMPLE_VCF.replace("\tA\tT\t", "\tA\tC\t"));
    Optional<String> diff = WriterRoundTrip.semanticDiff(before, after);
    assertTrue(diff.isPresent());
    assertTrue(diff.get().contains("ALT"));
  }

  @Test
  void testSampleDeclarationAttributeOrderRoundTripsByteIdentical() throws IOException {
    String vcf =
        "##fileformat=VCFv4.2\n" +
        "##SAMPLE=<ID=Sample_ok,Genomes=G1_ID;G2_ID;GK_ID,Mixture=N1;N2;NK,Description=\"S1;S2;SK\">\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "1\t100\t.\tA\tT\t.\tPASS\t.\n";
    WriterRoundTrip.ParsedFile parsed = WriterRoundTrip.parseText(vcf);
    String written = WriterRoundTrip.write(parsed);
    assertEquals(Optional.empty(), WriterRoundTrip.byteDiff(vcf, written));
  }

  @Test
  void testMultipleInfoDeclarationOrderRoundTripsByteIdentical() throws IOException {
    String vcf =
        "##fileformat=VCFv4.2\n" +
        "##INFO=<ID=ZED,Number=1,Type=String,Description=\"d\">\n" +
        "##INFO=<ID=YAK,Number=1,Type=String,Description=\"d\">\n" +
        "##INFO=<ID=XEN,Number=1,Type=String,Description=\"d\">\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "1\t100\t.\tA\tT\t.\tPASS\t.\n";
    WriterRoundTrip.ParsedFile parsed = WriterRoundTrip.parseText(vcf);
    String written = WriterRoundTrip.write(parsed);
    assertEquals(Optional.empty(), WriterRoundTrip.byteDiff(vcf, written));
  }

  @Test
  void testInfoKeyOrderRoundTripsByteIdentical() throws IOException {
    String vcf =
        "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "1\t100\t.\tA\tT\t.\tPASS\tAN=2184;AC=249;AF=0.11\n";
    WriterRoundTrip.ParsedFile parsed = WriterRoundTrip.parseText(vcf);
    String written = WriterRoundTrip.write(parsed);
    assertEquals(Optional.empty(), WriterRoundTrip.byteDiff(vcf, written));
  }

  @Test
  void testGenericPropertyLineOrderRoundTripsByteIdentical() throws IOException {
    String vcf =
        "##fileformat=VCFv4.2\n" +
        "##phasing=partial\n" +
        "##source=myImputationProgramV3.1\n" +
        "##assembly=file://somewhere\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "1\t100\t.\tA\tT\t.\tPASS\t.\n";
    WriterRoundTrip.ParsedFile parsed = WriterRoundTrip.parseText(vcf);
    String written = WriterRoundTrip.write(parsed);
    assertEquals(Optional.empty(), WriterRoundTrip.byteDiff(vcf, written));
  }

  @Test
  void testCorrectlyDeclaredReservedKeysRoundTripWithoutWarning() throws IOException {
    // ##SAMPLE declares S1 (matching the #CHROM sample column) so VcfParser's own "samples in the header but not in
    // the metadata" cross-check (unrelated to the reserved-key mechanism under test) doesn't also warn here
    String vcf =
        "##fileformat=VCFv4.2\n" +
        "##INFO=<ID=AC,Number=A,Type=Integer,Description=\"Allele count\">\n" +
        "##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n" +
        "##SAMPLE=<ID=S1,Description=\"S1\">\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tS1\n" +
        "1\t100\trs1\tA\tT\t.\tPASS\tAC=1\tGT\t0/1\n";
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    WriterRoundTrip.ParsedFile parsed;
    try {
      parsed = WriterRoundTrip.parseText(vcf);
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    String written = WriterRoundTrip.write(parsed);
    assertEquals(Optional.empty(), WriterRoundTrip.byteDiff(vcf, written));
  }

  @Test
  void testInvalidAltIdRoundTripsWithoutRewriting() throws IOException {
    // warn-and-preserve, not reject-and-rewrite: THIS:IS-NOT-VALID is not a valid structural variant code (its
    // colon-delimited structure invokes the SV-code grammar, but "THIS" is not a reserved top-level code, see
    // AltStructuralVariantTest/VcfMetadataTest), but the declaration must still round-trip byte-for-byte unchanged.
    // (A bare single-token ID like "DEL1" is deliberately NOT used here -- see VcfMetadata.validateAlt -- since it
    // has legitimate non-SV uses and must not warn at all.)
    String vcf =
        "##fileformat=VCFv4.2\n" +
        "##ALT=<ID=THIS:IS-NOT-VALID,Description=\"Not a real code\">\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "1\t100\t.\tA\t<THIS:IS-NOT-VALID>\t.\tPASS\t.\n";
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    WriterRoundTrip.ParsedFile parsed;
    try {
      parsed = WriterRoundTrip.parseText(vcf);
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    String written = WriterRoundTrip.write(parsed);
    assertEquals(Optional.empty(), WriterRoundTrip.byteDiff(vcf, written));
  }

  @Test
  void testOutOfOrderInfoDeclarationRoundTripsWithoutRewriting() throws IOException {
    // warn-and-preserve, not reject-and-rewrite: reproduces failed_meta_info_003.vcf's declared order
    String vcf =
        "##fileformat=VCFv4.2\n" +
        "##INFO=<Number=1,Type=Integer,Description=\"Raw read depth\",ID=DP>\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "1\t100\t.\tA\tT\t.\tPASS\tDP=10\n";
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    WriterRoundTrip.ParsedFile parsed;
    try {
      parsed = WriterRoundTrip.parseText(vcf);
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    String written = WriterRoundTrip.write(parsed);
    assertEquals(Optional.empty(), WriterRoundTrip.byteDiff(vcf, written));
  }

  @Test
  void testEmptyGenericPropertyValueIsDroppedNotPreserved() throws IOException {
    // unlike every other warn-and-preserve case in this test class, an empty generic property value is warn-and-
    // DROP (see VcfMetadata.Builder.addRawProperty) -- the written output omits the line entirely, so this is not
    // a byte-diff-empty assertion like the other round-trip tests here
    String vcf =
        "##fileformat=VCFv4.2\n" +
        "##reference=\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "1\t100\t.\tA\tT\t.\tPASS\t.\n";
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    WriterRoundTrip.ParsedFile parsed;
    try {
      parsed = WriterRoundTrip.parseText(vcf);
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    String written = WriterRoundTrip.write(parsed);
    assertFalse(written.contains("##reference="));
  }

  @Test
  void testDuplicateContigDeclarationRoundTripsKeepingFirstOnly() throws IOException {
    // warn-and-drop-duplicate, not reject: reproduces passed_meta_contig.vcf's repeated ##contig ID=1 declaration
    String vcf =
        "##fileformat=VCFv4.2\n" +
        "##contig=<ID=1,length=100>\n" +
        "##contig=<ID=1,length=200>\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "1\t100\t.\tA\tT\t.\tPASS\t.\n";
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    WriterRoundTrip.ParsedFile parsed;
    try {
      parsed = WriterRoundTrip.parseText(vcf);
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    String written = WriterRoundTrip.write(parsed);
    assertTrue(written.contains("##contig=<ID=1,length=100>"));
    assertFalse(written.contains("length=200"));
  }

  @Test
  void testFilterPassCombinedWithOtherCodeRoundTripsWithoutRewriting() throws IOException {
    // warn-and-preserve, not reject: reproduces complexfile_passed_000.vcf's FILTER=STD_FILTER;PASS
    String vcf =
        "##fileformat=VCFv4.2\n" +
        "##FILTER=<ID=STD_FILTER,Description=\"Standard filter\">\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "1\t100\t.\tA\tT\t.\tSTD_FILTER;PASS\t.\n";
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    WriterRoundTrip.ParsedFile parsed;
    try {
      parsed = WriterRoundTrip.parseText(vcf);
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    String written = WriterRoundTrip.write(parsed);
    assertEquals(Optional.empty(), WriterRoundTrip.byteDiff(vcf, written));
  }

  @Test
  void testDuplicatePedigreeIdRoundTripsKeepingFirstOnly() throws IOException {
    // warn-and-drop-duplicate, not reject: mirrors testDuplicateContigDeclarationRoundTripsKeepingFirstOnly's
    // pattern for the six keyed types, applied to PEDIGREE's conditional (ID-present-only) dedup rule
    String vcf =
        "##fileformat=VCFv4.2\n" +
        "##PEDIGREE=<ID=Child,Father=Dad>\n" +
        "##PEDIGREE=<ID=Child,Mother=Mom>\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "1\t100\t.\tA\tT\t.\tPASS\t.\n";
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    WriterRoundTrip.ParsedFile parsed;
    try {
      parsed = WriterRoundTrip.parseText(vcf);
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    String written = WriterRoundTrip.write(parsed);
    assertTrue(written.contains("Father=Dad"));
    assertFalse(written.contains("Mother=Mom"));
  }

  @Test
  void testEmptyAltEntryRoundTripsAsSubstitutedDot() throws IOException {
    // warn-and-substitute, not drop: dropping would shift every subsequent GT index (see VcfPositionTest's
    // testGetAlleleAfterAltDotSubstitutionPreservesIndex for the underlying bug this prevents)
    String vcf =
        "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "1\t100\t.\tA\tT,,C\t.\tPASS\t.\n";
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    WriterRoundTrip.ParsedFile parsed;
    try {
      parsed = WriterRoundTrip.parseText(vcf);
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    String written = WriterRoundTrip.write(parsed);
    String expected =
        "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "1\t100\t.\tA\tT,.,C\t.\tPASS\t.\n";
    assertEquals(Optional.empty(), WriterRoundTrip.byteDiff(expected, written));
  }

  @Test
  void testUnescapedQuoteInDescriptionRoundTripsWithoutRewriting() throws IOException {
    // warn-and-preserve, not reject-and-rewrite: reproduces failed_meta_001.vcf's
    // Description="Description with "non-escaped quote" (an odd count of unescaped '"' characters)
    String vcf =
        "##fileformat=VCFv4.2\n" +
        "##ALT=<ID=DEL,Number=1,Type=String,Description=\"Description with \"non-escaped quote\">\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "1\t100\t.\tA\t<DEL>\t.\tPASS\t.\n";
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    WriterRoundTrip.ParsedFile parsed;
    try {
      parsed = WriterRoundTrip.parseText(vcf);
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    String written = WriterRoundTrip.write(parsed);
    assertEquals(Optional.empty(), WriterRoundTrip.byteDiff(vcf, written));
  }
}
