package org.pharmgkb.parser.vcf.spec;

import java.io.IOException;
import java.util.List;
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

  @Test
  void testDuplicateEmptyFormatKeyRoundTripsWithoutColumnCorruption() throws IOException {
    // FORMAT=GT:: has two empty sub-field names -- VcfPosition.checkFormat excludes an empty key from its
    // duplicate-key rejection (see EMPTY_FIELD_HANDLING.md's "duplicate FORMAT keys" section), so this parses
    // successfully into a VcfSample with two "" properties ("aa" then "bb"). The guarantee here is structural,
    // not completeness: the second empty-keyed value ("bb") is not recoverable and is lost on write (this
    // matches the repo's existing accepted trade-off for FORMAT sub-fields beyond the header declaration) -- but
    // the column count and delimiter placement stay correct, so the line remains well-formed and re-parseable.
    String vcf =
        "##fileformat=VCFv4.2\n" +
        "##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tS1\n" +
        "1\t100\t.\tA\tT\t.\tPASS\t.\tGT::\t0/1:aa:bb\n";
    WriterRoundTrip.ParsedFile parsed = WriterRoundTrip.parseText(vcf);

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    String written;
    try {
      written = WriterRoundTrip.write(parsed);
      // the duplicate-empty-key materialization warning fires on write (VcfWriter.addSampleConditionally's
      // extra-property check is what triggers VcfSample's lazy materialization), not on parse
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    // verified actual output: the writer reads each FORMAT key with sample.getProperty(key), which -- both before
    // and after materialization -- resolves the empty key "" to its FIRST occurrence's value ("aa"); "bb" is
    // dropped entirely, and "aa" is written for BOTH empty-keyed columns, not just the first
    String expected =
        "##fileformat=VCFv4.2\n" +
        "##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tS1\n" +
        "1\t100\t.\tA\tT\t.\tPASS\t.\tGT::\t0/1:aa:aa\n";
    assertEquals(Optional.empty(), WriterRoundTrip.byteDiff(expected, written));
  }

  // --- Task 4 / D4 regression: the model layer never percent-decodes or percent-encodes a value on either the
  // parse or write path. These matter more than VcfPercentEncodingTest's own codec unit tests -- they are what
  // would catch someone reintroducing transparent decoding later, this task's whole residual risk.

  @Test
  void testInfoValueWithPercentEncodedSequencesRoundTripsByteIdenticalWithoutWarning() throws IOException {
    // %3B (canonical case) and %3b (lowercase -- still a valid tabled sequence) must survive parse -> getInfo()
    // -> write byte-for-byte with no warning; %03A is NOT one of the eight tabled sequences (see
    // VcfPercentEncodingTest/hts-specs' own test/vcf/4.3/passed/passed_body_info.vcf:63) and %41 is not a
    // tabled byte value either, so both must likewise pass through untouched rather than being decoded or
    // rejected as containing a literal delimiter.
    String vcf =
        "##fileformat=VCFv4.3\n" +
        "##INFO=<ID=XY,Number=1,Type=String,Description=\"test\">\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "1\t100\t.\tA\tT\t.\tPASS\tXY=a%3Bb%3bc%03Ad%41e\n";
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
    assertEquals(List.of("a%3Bb%3bc%03Ad%41e"), parsed.positions().get(0).getInfo("XY"));
    String written = WriterRoundTrip.write(parsed);
    assertEquals(Optional.empty(), WriterRoundTrip.byteDiff(vcf, written));
  }

  @Test
  void testSampleValueWithPercentEncodedSequencesRoundTripsByteIdenticalWithoutWarning() throws IOException {
    // same guarantee as the INFO case above, for a FORMAT (sample) value. ##SAMPLE declares S1 (matching the
    // #CHROM sample column) so VcfParser's own "samples in the header but not in the metadata" cross-check
    // (unrelated to the mechanism under test) doesn't also warn here -- see
    // testCorrectlyDeclaredReservedKeysRoundTripWithoutWarning above for the same pattern.
    String vcf =
        "##fileformat=VCFv4.3\n" +
        "##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n" +
        "##FORMAT=<ID=XZ,Number=1,Type=String,Description=\"test\">\n" +
        "##SAMPLE=<ID=S1,Description=\"S1\">\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tS1\n" +
        "1\t100\t.\tA\tT\t.\tPASS\t.\tGT:XZ\t0/1:a%3Bb%3bc%03Ad%41e\n";
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
    assertEquals("a%3Bb%3bc%03Ad%41e", parsed.samples().get(0).get(0).getProperty("XZ"));
    String written = WriterRoundTrip.write(parsed);
    assertEquals(Optional.empty(), WriterRoundTrip.byteDiff(vcf, written));
  }

  @Test
  void testStructuredMetadataAttributeValueWithPercentEncodedSequencesRoundTripsByteIdenticalWithoutWarning()
      throws IOException {
    // same guarantee as the two cases above, for a structured metadata declaration's own attribute value
    // (##FILTER's Description here; the mechanism is shared across every declaration type via BaseMetadata)
    String vcf =
        "##fileformat=VCFv4.3\n" +
        "##FILTER=<ID=q10,Description=\"qual below 10%3Bnote%3bmore%03Aand%41end\">\n" +
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
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertEquals("\"qual below 10%3Bnote%3bmore%03Aand%41end\"",
        parsed.metadata().getFilters().get("q10").getPropertyRaw("Description"));
    String written = WriterRoundTrip.write(parsed);
    assertEquals(Optional.empty(), WriterRoundTrip.byteDiff(vcf, written));
  }
}
