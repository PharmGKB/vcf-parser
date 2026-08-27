package org.pharmgkb.parser.vcf.spec;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.Test;
import org.pharmgkb.parser.vcf.FormatType;
import org.pharmgkb.parser.vcf.VcfFormatException;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
  void testByteDiffDetectsLineEndingDifferences() {
    Optional<String> diff = WriterRoundTrip.byteDiff("A\r\nB\r\n", "A\nB\n");
    assertTrue(diff.isPresent());
    assertTrue(diff.get().contains("line 1"));
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
  void testSemanticDiffDetectsInfoMetadataDescriptionMismatch() throws IOException {
    // regression test for the Phase 3 writer-compliance review's Path B kickoff (finding #7): semanticDiff
    // never referenced before.metadata()/after.metadata() at all, so a metadata-level round-trip regression
    // was invisible to every check built on this method. Changing only the ##INFO Description (not any
    // position/sample field) between "before" and "after" must still be caught.
    WriterRoundTrip.ParsedFile before = WriterRoundTrip.parseText(SAMPLE_VCF);
    WriterRoundTrip.ParsedFile after =
        WriterRoundTrip.parseText(SAMPLE_VCF.replace("Description=\"Depth\"", "Description=\"Different\""));
    Optional<String> diff = WriterRoundTrip.semanticDiff(before, after);
    assertTrue(diff.isPresent());
    assertTrue(diff.get().contains("INFO metadata"), diff.get());
    assertTrue(diff.get().contains("Depth") && diff.get().contains("Different"), diff.get());
  }

  @Test
  void testSemanticDiffDetectsSampleColumnNameMismatch() throws IOException {
    // same finding as above: a corrupted/mismatched #CHROM sample column name (exactly finding #2's own
    // failure mode -- a sample name silently gaining or losing a column on write) must be caught too, not
    // just the seven keyed declaration types.
    WriterRoundTrip.ParsedFile before = WriterRoundTrip.parseText(SAMPLE_VCF);
    WriterRoundTrip.ParsedFile after = WriterRoundTrip.parseText(SAMPLE_VCF.replace("\tS1\n", "\tS2\n"));
    Optional<String> diff = WriterRoundTrip.semanticDiff(before, after);
    assertTrue(diff.isPresent());
    assertTrue(diff.get().contains("sample column"), diff.get());
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
    // not just no rewriting, no warning at all: reproduces failed_meta_info_003.vcf's declared order, which is
    // now a permanently-accepted divergence -- attribute-order checking was removed from every keyed
    // declaration type (see VCF_SPEC_AMBIGUITIES.md's "##META attribute order" entry), so this warns about
    // nothing at all, not just "warn but preserve."
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
      assertFalse(appender.hasWarnOrHigher());
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
    // pattern for the seven keyed types, applied to PEDIGREE's conditional (ID-present-only) dedup rule
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
  void testUnescapedQuoteInDescriptionCanonicalizesTrailingJunkOnWrite() throws IOException {
    // warn-and-canonicalize, not reject-and-preserve-verbatim: reproduces failed_meta_001.vcf's
    // Description="Description with "non-escaped quote" (an odd count of unescaped '"' characters). The
    // attribute-list tokenizer (InternalUtils.tokenizeAttributeList) finds a quoted value's closing quote by
    // POSITION -- the first unescaped '"' after the opening one -- rather than by unbalanced-quote parity, so
    // the value closes right after "with " and the remaining "non-escaped quote\"" is trailing content, warned
    // about and discarded rather than preserved verbatim (see STRUCTURAL_DELIMITER_HANDLING.md's junk-after-
    // close recovery). This still satisfies AGENTS.md's Writer Round-Trip Policy: the recovered value is
    // syntactically valid and its usable meaning ("Description with ") is preserved, even though the exact
    // input bytes are not.
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
    String expected =
        "##fileformat=VCFv4.2\n" +
        "##ALT=<ID=DEL,Number=1,Type=String,Description=\"Description with \">\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "1\t100\t.\tA\t<DEL>\t.\tPASS\t.\n";
    assertEquals(Optional.empty(), WriterRoundTrip.byteDiff(expected, written));
  }

  @Test
  void testMetaValuesBracketRoundTripsByteIdenticallyThroughDefaultWrite() throws IOException {
    // AGENTS.md: "For parser-to-writer changes, add a round-trip test showing that accepted input can be
    // written and parsed again without column loss or delimiter corruption." Bracket-list quoting is scoped
    // to ##META's Values (see VCF_SPEC_AMBIGUITIES.md's "General bracket-list quoting beyond ##META's
    // Values" entry) -- this pins that the spec's own worked example (VcfParserTest
    // #bracketValuesOnlyProtectCommasForMetaValues, which only checks the parsed-in-memory value) also
    // survives the default write path byte-for-byte, not just an in-memory getPropertyRaw() check.
    String vcf =
        "##fileformat=VCFv4.3\n" +
        "##META=<ID=Assay,Type=String,Number=.,Values=[WholeGenome, Exome]>\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "1\t5\t.\tA\tT\t.\t.\t.\n";
    WriterRoundTrip.ParsedFile parsed = WriterRoundTrip.parseText(vcf);
    String written = WriterRoundTrip.write(parsed);
    assertEquals(Optional.empty(), WriterRoundTrip.byteDiff(vcf, written));
  }

  @Test
  void testNonMetaValuesBracketTruncationStaysBcftoolsSafeOnWrite() throws IOException {
    // Regression test for a real bug caught by external review: outside ##META's Values, a comma inside
    // "[...]" is not protected (VCF_SPEC_AMBIGUITIES.md's "General bracket-list quoting beyond ##META's
    // Values" entry), so "x=[a, b]" truncates at the comma to just "x=[a" -- a value with a dangling '[' and
    // no ']' anywhere. Writing that back out RAW would manufacture a value bcftools cannot read at all (exit
    // 255) from an input that was perfectly well-formed; this is not carrying forward already-malformed
    // content, it is this library's own truncation creating new fatal content. The byte-identical guarantee
    // does NOT apply here -- the input is not byte-preserved, it is defensively re-quoted -- so this test
    // instead pins that the WRITTEN output (a) contains no dangling '[' and (b) is stable under a second
    // parse-and-write (idempotent), i.e. genuinely well-formed rather than merely happening to work once.
    // BcftoolsDependencyTest#testNonMetaValuesBracketTruncationDoesNotManufactureAnUnreadableHeader is the
    // stronger, tool-verified twin of this test.
    String vcf =
        "##fileformat=VCFv4.3\n" +
        "##contig=<ID=c1,x=[a, b],length=999>\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "c1\t5\t.\tA\tT\t.\t.\t.\n";
    WriterRoundTrip.ParsedFile parsed = WriterRoundTrip.parseText(vcf);
    String written = WriterRoundTrip.write(parsed);
    assertTrue(written.contains("x=\"[a\""), "expected the truncated value to be re-quoted for safety: " + written);
    assertFalse(written.contains("x=[a,"), "must not write the dangling, unquoted '[' back out verbatim");
    // stable under a second round trip -- re-parsing the (already-safe) written output and writing it again
    // must reproduce the exact same bytes, since nothing about "\"[a\"" needs further correction
    WriterRoundTrip.ParsedFile reparsed = WriterRoundTrip.parseText(written);
    String rewritten = WriterRoundTrip.write(reparsed);
    assertEquals(Optional.empty(), WriterRoundTrip.byteDiff(written, rewritten));
  }

  @Test
  void testDuplicateEmptyFormatKeyThrowsInsteadOfSilentlyDroppingASampleValue() throws IOException {
    // FORMAT=GT:: has two empty sub-field names -- regression test, updated by the Phase 3 writer-
    // compliance review's Task 5 first re-verification round (finding B): VcfPosition.checkFormat used to
    // exclude an empty key from its duplicate-key rejection unconditionally (see EMPTY_FIELD_HANDLING.md's
    // "duplicate FORMAT keys" section, also updated by this fix), so this used to parse successfully into a
    // VcfSample with two "" properties ("aa" then "bb") and silently lose "bb" on write -- the identical
    // defect class finding #4 closed for a duplicated, grammar-invalid key. An empty key that appears once
    // is unaffected (still kept, not dropped, per EMPTY_FIELD_HANDLING.md's positional-index reasoning); a
    // SECOND occurrence of it now throws the same as any other duplicate key.
    String vcf =
        "##fileformat=VCFv4.2\n" +
        "##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tS1\n" +
        "1\t100\t.\tA\tT\t.\tPASS\t.\tGT::\t0/1:aa:bb\n";
    VcfFormatException e = assertThrows(VcfFormatException.class, () -> WriterRoundTrip.parseText(vcf));
    assertTrue(e.getMessage().contains("Duplicate FORMAT key"), e.getMessage());
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

  private record MatrixCase(String key, String description, String number, FormatType type, String value,
      boolean includeGt, String expectedWarningSubstring) {}

  private static final List<MatrixCase> PRE_43_CASES = List.of(
      new MatrixCase("GLE", "Genotype likelihoods", "G", FormatType.String,
          "0:-75.22,1:-223.42,0/0:-323.03,1/0:-99.29,1/1:-802.53", true, null),
      new MatrixCase("CNL", "Copy number genotype likelihood", ".", FormatType.Float, "-0.1,-0.2,-0.3", true, null),
      new MatrixCase("CNP", "custom", "1", FormatType.Float, "0.5", true, null),
      new MatrixCase("PP", "custom", "1", FormatType.String, "xyz", true, null),
      new MatrixCase("AD", "custom", "1", FormatType.String, "xyz", true, null),
      new MatrixCase("ADF", "custom", "1", FormatType.String, "xyz", true, null),
      new MatrixCase("ADR", "custom", "1", FormatType.String, "xyz", true, null),
      new MatrixCase("GP", "d", "G", FormatType.Float, "5.0,5.0,5.0", false, null));

  private static final List<MatrixCase> AT_OR_AFTER_43_CASES = List.of(
      new MatrixCase("GLE", "custom", "1", FormatType.String, "abc", true, null),
      new MatrixCase("CNL", "Copy number genotype likelihood", "G", FormatType.Float,
          "-0.1,-0.2,-0.3,-0.2,-0.2", true, null),
      new MatrixCase("CNP", "Copy number posterior probabilities", "G", FormatType.Float,
          "0.1,0.2,0.3,0.2,0.2", true, null),
      new MatrixCase("PP", "Phred-scaled genotype posterior probabilities", "G", FormatType.Integer,
          "0,3,10", true, null),
      new MatrixCase("AD", "Total read depth for each allele", "R", FormatType.Integer, "10,5", true, null),
      new MatrixCase("ADF", "Read depth for each allele on the forward strand", "R", FormatType.Integer,
          "6,3", true, null),
      new MatrixCase("ADR", "Read depth for each allele on the reverse strand", "R", FormatType.Integer,
          "4,2", true, null),
      new MatrixCase("GP", "d", "G", FormatType.Float, "5.0,5.0,5.0", false, "does not lie in the interval"));

  private static String buildVersionMatrixVcf(String fileFormat, MatrixCase c) {
    StringBuilder sb = new StringBuilder();
    sb.append("##fileformat=").append(fileFormat).append('\n');
    // ##contig/##SAMPLE declare the record's own CHROM ("1") and #CHROM sample column ("S1") -- without them,
    // VcfParser's/VcfWriter's own unrelated cross-checks ("samples in the header but not in the metadata",
    // "CHROM has no ##contig metadata") would warn on every case, masking whether the reserved-key mechanism
    // under test warned or not. Same pattern as testCorrectlyDeclaredReservedKeysRoundTripWithoutWarning above.
    sb.append("##contig=<ID=1,length=1000000>\n");
    sb.append("##SAMPLE=<ID=S1,Description=\"S1\">\n");
    if (c.includeGt()) {
      sb.append("##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n");
    }
    sb.append("##FORMAT=<ID=").append(c.key()).append(",Number=").append(c.number())
        .append(",Type=").append(c.type()).append(",Description=\"").append(c.description()).append("\">\n");
    sb.append("#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tS1\n");
    String format = c.includeGt() ? "GT:" + c.key() : c.key();
    String sample = c.includeGt() ? "0/1:" + c.value() : c.value();
    sb.append("1\t100\t.\tA\tT\t.\t.\t.\t").append(format).append('\t').append(sample).append('\n');
    return sb.toString();
  }

  // raw (pre-substitution) SLF4J message templates for the version resolver's/writer's own fallback warnings --
  // used with WarnCapturingAppender.hasWarnOrHigherExcluding so assertRoundTripsCleanly's warning-silence
  // assertion can tolerate exactly the fallback warnings the "declared VCFv4.4" dimension legitimately expects
  // and nothing else. See VcfVersion.resolve and VcfWriter.writeHeader for the source of each.
  private static final String READING_FALLBACK_RAW_TEMPLATE =
      "Reading ##fileformat={} as VCF {} -- this library does not implement {} semantics";
  private static final String WRITING_FALLBACK_RAW_TEMPLATE =
      "Writing ##fileformat={} verbatim, but its content was accepted and normalized under " +
          "VCF {} rules; validity under the declared version is not guaranteed";

  private static void assertRoundTripsCleanly(String fileFormat, MatrixCase c) throws IOException {
    assertRoundTripsCleanly(fileFormat, c, null);
  }

  private static void assertRoundTripsCleanly(String fileFormat, MatrixCase c,
      String expectedFallbackWarningSubstring) throws IOException {
    Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    WriterRoundTrip.ParsedFile before;
    String written;
    try {
      before = WriterRoundTrip.parseText(buildVersionMatrixVcf(fileFormat, c));
      // validateBeforeWrite=true: the reserved-key content checks this test targets (checkReservedFormatConstraints,
      // e.g. GP's [0,1] range check) run only under VcfWriter's validateBeforeWrite() diagnostics path -- see
      // VcfWriter.validateSamples, called only when m_validateBeforeWrite -- never on the parse path or the default
      // write path (deferred until a getter, per AGENTS.md's Parser Validation Policy). The plan's original
      // WriterRoundTrip.write(before) (default, non-validating writer) could never observe any of these warnings;
      // this is fixed to match the mechanism this test (and its cited source, VcfWriterTest
      // .testValidateBeforeWriteGenotypePosteriorRangeCheckIsVersionGated, which itself uses validateBeforeWrite())
      // actually exercises.
      written = WriterRoundTrip.write(before, true);
      Set<String> ignoredTemplates = new HashSet<>();
      if (expectedFallbackWarningSubstring != null) {
        assertTrue(appender.anyMessageContains(expectedFallbackWarningSubstring),
            c.key() + ": expected the fallback warning");
        ignoredTemplates.add(READING_FALLBACK_RAW_TEMPLATE);
        ignoredTemplates.add(WRITING_FALLBACK_RAW_TEMPLATE);
      }
      if (c.expectedWarningSubstring() == null) {
        assertFalse(appender.hasWarnOrHigherExcluding(ignoredTemplates),
            () -> c.key() + " (" + fileFormat + "): unexpected warning(s): " +
                appender.getRelevantWarnOrHigherMessages(ignoredTemplates));
      } else {
        assertTrue(appender.anyMessageContains(c.expectedWarningSubstring()),
            c.key() + " (" + fileFormat + "): expected a warning containing \"" + c.expectedWarningSubstring() +
                "\"");
      }
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    WriterRoundTrip.ParsedFile after = WriterRoundTrip.parseText(written);
    Optional<String> diff = WriterRoundTrip.semanticDiff(before, after);
    assertTrue(diff.isEmpty(), () -> c.key() + " (" + fileFormat + "): semantic mismatch: " + diff.orElse(""));
  }

  @Test
  void testVersionSensitiveReservedKeysRoundTripAcrossThePre43And43Boundary() throws IOException {
    // R8: every version-sensitive reserved FORMAT key (GLE removed at 4.3; CNL/CNP/PP/AD/ADF/ADR added at
    // 4.3; GP's [0,1] range check added at 4.3) previously had zero round-trip coverage in this file. Each
    // case's value is reused verbatim from an already-passing unit test elsewhere in this codebase (see
    // e.g. VcfSampleTest.testAlleleDepthAndPosteriorProbabilityKeysAreListTyped, VcfWriterTest
    // .testValidateBeforeWriteGenotypePosteriorRangeCheckIsVersionGated), so this test's correctness rests
    // on already-verified behavior, not a newly hand-simulated expectation.
    assertEquals(PRE_43_CASES.size(), AT_OR_AFTER_43_CASES.size());
    for (int i = 0; i < PRE_43_CASES.size(); i++) {
      MatrixCase pre = PRE_43_CASES.get(i);
      MatrixCase post = AT_OR_AFTER_43_CASES.get(i);
      assertEquals(pre.key(), post.key());
      assertRoundTripsCleanly("VCFv4.2", pre);
      assertRoundTripsCleanly("VCFv4.3", post);
    }
  }

  @Test
  void testVersionSensitiveReservedKeysBehaveAs43WhenDeclaredFileformatFallsBackFrom44() throws IOException {
    // R8: the "fallback" dimension named by TODO.md's original R8 title -- a declared VCFv4.4 (outside
    // 4.1-4.3) resolves to VcfVersion.HIGHEST_IMPLEMENTED (V4_3) via VcfVersion.resolve, with a warning (see
    // VcfVersion.resolve's own Javadoc). Reuses AT_OR_AFTER_43_CASES unchanged except the declared
    // ##fileformat, confirming the fallback genuinely produces the same 4.3 behavior end to end. Delegates to
    // assertRoundTripsCleanly's fallback-aware overload rather than reimplementing its body, which also gives
    // this dimension the same full warning-silence assertion the plain 4.3-only dimension gets.
    for (MatrixCase post : AT_OR_AFTER_43_CASES) {
      assertRoundTripsCleanly("VCFv4.4", post,
          "Reading ##fileformat=VCFv4.4 as VCF 4.3 -- this library does not implement VCFv4.4 semantics");
    }
  }
}
