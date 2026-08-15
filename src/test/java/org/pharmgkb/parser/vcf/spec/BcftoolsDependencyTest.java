package org.pharmgkb.parser.vcf.spec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;


/**
 * Runs a real, installed {@code bcftools} binary against hand-built VCF content to verify every decision this
 * library has made based on bcftools's specific current behavior. Tagged so it only runs under the opt-in
 * {@code bcftoolsDependencyTest} Gradle task, never under {@code test}, since it requires {@code bcftools} on
 * {@code PATH}. See {@code BCFTOOLS_VERSION_DEPENDENCIES.md} (repo root) for the index of what each test method
 * verifies and why.
 *
 * <p>Unlike an environment-optional check, a missing {@code bcftools} binary fails this whole class loudly
 * (via {@link #checkBcftoolsIsInstalled()}) rather than skipping silently: this task is already opt-in, so
 * there's no risk of breaking an environment that never invited this check, and a silent skip could be mistaken
 * for "everything passed." A *version* mismatch, by contrast, does not block the other tests -- see
 * {@link #testBcftoolsIsExpectedVersion()}.
 */
@Tag("bcftools-dependency")
class BcftoolsDependencyTest {

  private static final String EXPECTED_BCFTOOLS_VERSION = "1.23.1";

  @BeforeAll
  static void checkBcftoolsIsInstalled() {
    BcftoolsResult result;
    try {
      result = runBcftoolsRaw("--version");
    } catch (IOException | InterruptedException e) {
      fail("bcftools not found on PATH; install bcftools to run this task", e);
      return;
    }
    if (result.exitCode() != 0) {
      fail("bcftools --version exited " + result.exitCode() + "; install bcftools to run this task");
    }
  }

  @Test
  void testBcftoolsIsExpectedVersion() throws IOException, InterruptedException {
    // deliberately independent of checkBcftoolsIsInstalled() above: a version mismatch here must not prevent
    // every other @Test in this class from running, since seeing exactly which specific behaviors changed under
    // a new bcftools version is the whole point of this suite -- only a @BeforeAll failure blocks the whole
    // class, a @Test failure does not block its siblings
    BcftoolsResult result = runBcftoolsRaw("--version");
    assertTrue(result.output().contains("bcftools " + EXPECTED_BCFTOOLS_VERSION),
        "Expected bcftools " + EXPECTED_BCFTOOLS_VERSION + " but got: " + result.output());
  }

  @Test
  void testGenericPropertyEmptyBracketsBcftoolsRejects() throws IOException, InterruptedException {
    // round-trip-critical: VcfMetadata.collidesWithAttributeListGrammar must reject this, or this library
    // would write out a header line bcftools cannot parse at all
    String vcf = "##fileformat=VCFv4.2\n##assembly=<>\n##contig=<ID=chr1,length=1000000>\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\nchr1\t1\trs1\tA\tT\t.\tPASS\t.\n";
    BcftoolsResult result = runBcftools(vcf, "view", "-h");
    // NOTE: bcftools 1.23.1 exits 0 here -- it emits the error below to stderr and silently drops the
    // offending header line, but does not fail the process -- confirmed live; do not assert exitCode != 0
    assertTrue(result.output().contains("Could not parse the header line"),
        "Expected a header-parse error, got: " + result.output());
  }

  @Test
  void testGenericPropertyBracketedNoEqualsBcftoolsRejects() throws IOException, InterruptedException {
    // round-trip-critical: same as above, for a non-empty bracket-wrapped value with no '=' at all
    String vcf = "##fileformat=VCFv4.2\n##pedigreeDB=<http://foo.com>\n##contig=<ID=chr1,length=1000000>\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\nchr1\t1\trs1\tA\tT\t.\tPASS\t.\n";
    BcftoolsResult result = runBcftools(vcf, "view", "-h");
    // NOTE: bcftools 1.23.1 exits 0 here -- same as testGenericPropertyEmptyBracketsBcftoolsRejects above --
    // confirmed live; do not assert exitCode != 0
    assertTrue(result.output().contains("Could not parse the header line"),
        "Expected a header-parse error, got: " + result.output());
  }

  @Test
  void testGenericPropertyBracketedWithEqualsBcftoolsAccepts() throws IOException, InterruptedException {
    // sanity-checks the safe side too, so a future regression that starts over-rejecting is caught, not just
    // under-rejecting
    String vcf = "##fileformat=VCFv4.2\n##pedigreeDB=<ID=foo>\n##contig=<ID=chr1,length=1000000>\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\nchr1\t1\trs1\tA\tT\t.\tPASS\t.\n";
    BcftoolsResult result = runBcftools(vcf, "view", "-h");
    assertEquals(0, result.exitCode(), result.output());
    assertTrue(result.output().contains("##pedigreeDB=<ID=foo>"), result.output());
  }

  @Test
  void testPercentEncodingBcftoolsDecodesNothingAnywhere() throws IOException, InterruptedException {
    // round-trip-critical: VcfPercentEncoding's whole design (Task 4) rests on there being no tool precedent
    // for automatic percent-decoding -- confirmed here for both a structured metadata attribute value (##INFO's
    // own Description) and a data-line INFO value, in the same file
    String vcf = "##fileformat=VCFv4.3\n" +
        "##INFO=<ID=XY,Number=1,Type=String,Description=\"pct%3Btest\">\n" +
        "##contig=<ID=chr1,length=1000000>\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "chr1\t1\trs1\tA\tT\t.\tPASS\tXY=a%3Bb%2Cc%3Ad\n";
    BcftoolsResult headerResult = runBcftools(vcf, "view", "-h");
    assertEquals(0, headerResult.exitCode(), headerResult.output());
    assertTrue(headerResult.output().contains("Description=\"pct%3Btest\""), headerResult.output());

    BcftoolsResult queryResult = runBcftools(vcf, "query", "-f", "%INFO/XY\n");
    assertEquals("a%3Bb%2Cc%3Ad\n", queryResult.output());
  }

  @Test
  void testGenericPropertySquareBracketSubListBcftoolsAccepts() throws IOException, InterruptedException {
    // the specific regression guard for the bug Task 1 fixed: the exact hts-specs
    // test/vcf/4.3/passed/passed_meta_meta.vcf fixture content, which a prior over-broad version of
    // collidesWithAttributeListGrammar incorrectly rejected
    String vcf = "##fileformat=VCFv4.3\n" +
        "##META=<ID=Assay,Number=.,Type=String,Values=[WholeGenome, Exome]>\n" +
        "##contig=<ID=chr1,length=1000000>\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "chr1\t1\trs1\tA\tT\t.\tPASS\t.\n";
    BcftoolsResult result = runBcftools(vcf, "view", "-h");
    assertEquals(0, result.exitCode(), result.output());
    assertTrue(result.output().contains("##META=<ID=Assay,Number=.,Type=String,Values=[WholeGenome, Exome]>"),
        result.output());
  }

  /**
   * Writes {@code vcfContent} to a temp file, runs {@code bcftools <args> <tempFile>}, and returns the combined
   * stdout+stderr and exit code. stdout/stderr are combined (not kept separate) specifically to avoid the
   * classic subprocess I/O deadlock risk of blocking on one stream while the other's OS pipe buffer fills --
   * every test in this class only needs to search the combined text for a substring, never to distinguish which
   * stream it came from.
   */
  private static BcftoolsResult runBcftools(String vcfContent, String... args) throws IOException, InterruptedException {
    Path tempFile = Files.createTempFile("bcftools-dependency-test-", ".vcf");
    try {
      Files.writeString(tempFile, vcfContent, StandardCharsets.UTF_8);
      List<String> command = new ArrayList<>();
      command.add("bcftools");
      command.addAll(Arrays.asList(args));
      command.add(tempFile.toAbsolutePath().toString());
      return execute(command);
    } finally {
      Files.deleteIfExists(tempFile);
    }
  }

  /** Like {@link #runBcftools(String, String...)}, but for a bcftools invocation that takes no VCF file input
   * (e.g. {@code bcftools --version}). */
  private static BcftoolsResult runBcftoolsRaw(String... args) throws IOException, InterruptedException {
    List<String> command = new ArrayList<>();
    command.add("bcftools");
    command.addAll(Arrays.asList(args));
    return execute(command);
  }

  private static BcftoolsResult execute(List<String> command) throws IOException, InterruptedException {
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    int exitCode = process.waitFor();
    return new BcftoolsResult(exitCode, output);
  }

  @Test
  void testMalformedMetadataAttributeBcftoolsDropsDeclarationAndContinues() throws IOException, InterruptedException {
    // confirms this project's own citation: bcftools never aborts the whole file for a malformed attribute
    // inside a metadata declaration's <...> list -- it drops just the one malformed declaration and continues
    // (exit 0). This library is more lenient still: it drops only the one malformed ATTRIBUTE, keeping the
    // rest of the same declaration -- see VCF_SPEC_AMBIGUITIES.md's "Malformed attribute inside a metadata
    // declaration's <...> list" entry.
    String vcf = "##fileformat=VCFv4.2\n" +
        "##INFO=<ID=AF,,Number=1,Type=Float,Description=\"Allele frequency\">\n" +
        "##contig=<ID=chr1,length=1000000>\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "chr1\t1\trs1\tA\tT\t.\tPASS\t.\n";
    BcftoolsResult result = runBcftools(vcf, "view", "-h");
    assertEquals(0, result.exitCode(), result.output());
    assertTrue(result.output().contains("Could not parse the header line"), result.output());
  }

  @Test
  void testFormatDeclarationIdRejectsAsteriskButFilterAccepts() throws IOException, InterruptedException {
    // confirms the stricter ##FORMAT/##INFO tag-name grammar does not extend to ##FILTER/##SAMPLE/##ALT
    String formatVcf = "##fileformat=VCFv4.2\n##FORMAT=<ID=A*B,Number=1,Type=String,Description=\"bad\">\n" +
        "##contig=<ID=chr1,length=1000000>\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "chr1\t1\trs1\tA\tT\t.\tPASS\t.\n";
    BcftoolsResult formatResult = runBcftools(formatVcf, "view", "-h");
    assertTrue(formatResult.output().contains("Invalid tag name"), formatResult.output());

    String filterVcf = "##fileformat=VCFv4.2\n##FILTER=<ID=A*B,Description=\"bad\">\n" +
        "##contig=<ID=chr1,length=1000000>\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "chr1\t1\trs1\tA\tT\t.\tPASS\t.\n";
    BcftoolsResult filterResult = runBcftools(filterVcf, "view", "-h");
    assertEquals(0, filterResult.exitCode(), filterResult.output());
    assertTrue(filterResult.output().contains("##FILTER=<ID=A*B,Description=\"bad\">"), filterResult.output());
  }

  @Test
  void testInfo1000GLegacyTagNameBcftoolsAccepts() throws IOException, InterruptedException {
    String vcf = "##fileformat=VCFv4.2\n##INFO=<ID=1000G,Number=1,Type=Integer,Description=\"legacy\">\n" +
        "##contig=<ID=chr1,length=1000000>\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "chr1\t1\trs1\tA\tT\t.\tPASS\t1000G=5\n";
    BcftoolsResult result = runBcftools(vcf, "view", "-h");
    assertFalse(result.output().contains("Invalid tag name"), result.output());
    assertTrue(result.output().contains("ID=1000G"), result.output());
  }

  @Test
  void testContigIdCharacterSetBcftoolsRejectsCommaSameAsChromDoes() throws IOException, InterruptedException {
    // a comma is one of the characters VCFv4.3.tex's "Changes to VCFv4.3" errata invalidates for a VCF contig
    // name (shared by CHROM values and ##contig IDs alike -- see InternalUtils.INVALID_CONTIG_NAME_CHAR_PATTERN)
    String vcf = "##fileformat=VCFv4.2\n##contig=<ID=chr,1,length=1000000>\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\nchr1\t1\trs1\tA\tT\t.\tPASS\t.\n";
    BcftoolsResult result = runBcftools(vcf, "view", "-h");
    assertTrue(result.output().contains("Could not parse the header line"), result.output());
  }

  @Test
  void testContigExtraAttributeBcftoolsAcceptsSilently() throws IOException, InterruptedException {
    String vcf = "##fileformat=VCFv4.2\n##contig=<ID=chr1,length=1000000,accession=foo>\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\nchr1\t1\trs1\tA\tT\t.\tPASS\t.\n";
    BcftoolsResult result = runBcftools(vcf, "view", "-h");
    assertEquals(0, result.exitCode(), result.output());
    assertFalse(result.output().toLowerCase(java.util.Locale.ROOT).contains("warn"), result.output());
    assertTrue(result.output().contains("accession=foo"), result.output());
  }

  @Test
  void testGenericPropertyEmptyValueBcftoolsPreservesButWeAreStricter() throws IOException, InterruptedException {
    // documents a deliberate divergence: bcftools silently preserves a fully empty generic property value
    // unchanged, but this library drops it with a warning instead (see VcfMetadata.isEmptyMetadataValue)
    String vcf = "##fileformat=VCFv4.2\n##reference=\n##contig=<ID=chr1,length=1000000>\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\nchr1\t1\trs1\tA\tT\t.\tPASS\t.\n";
    BcftoolsResult result = runBcftools(vcf, "view", "-h");
    assertEquals(0, result.exitCode(), result.output());
    assertTrue(result.output().contains("##reference=\n") || result.output().contains("##reference=\r\n"),
        result.output());
  }

  @Test
  void testPedigreeNoIdDuplicatesBcftoolsKeepsBoth() throws IOException, InterruptedException {
    String vcf = "##fileformat=VCFv4.2\n##PEDIGREE=<Name_0=A,Name_1=B>\n##PEDIGREE=<Name_0=A,Name_1=B>\n" +
        "##contig=<ID=chr1,length=1000000>\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "chr1\t1\trs1\tA\tT\t.\tPASS\t.\n";
    BcftoolsResult result = runBcftools(vcf, "view", "-h");
    assertEquals(0, result.exitCode(), result.output());
    long count = result.output().lines().filter(line -> line.startsWith("##PEDIGREE=")).count();
    assertEquals(2, count, result.output());
  }

  @Test
  void testPedigreeEmptyIdDuplicatesBcftoolsKeepsFirst() throws IOException, InterruptedException {
    // contrast case for the test above: an *empty* ID= (not merely absent) is treated as a real,
    // dedupe-worthy value by bcftools, unlike the six keyed types' own "blank ID == no ID" leniency -- see
    // WONT_FIX.md's "PedigreeMetadata duplicate-ID dedup treats a present-but-blank ID the same as a real ID"
    String vcf = "##fileformat=VCFv4.2\n##PEDIGREE=<ID=,Name_0=G0-A>\n##PEDIGREE=<ID=,Name_0=G0-B>\n" +
        "##contig=<ID=chr1,length=1000000>\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "chr1\t1\trs1\tA\tT\t.\tPASS\t.\n";
    BcftoolsResult result = runBcftools(vcf, "view", "-h");
    assertEquals(0, result.exitCode(), result.output());
    assertTrue(result.output().contains("##PEDIGREE=<ID=,Name_0=G0-A>"), result.output());
    assertFalse(result.output().contains("G0-B"), result.output());
  }

  @Test
  void testMetaMissingIdBcftoolsKeepsSilently() throws IOException, InterruptedException {
    // deliberate divergence: vcf-parser warns and drops an ID-less ##META (DUPLICATE_METADATA_ID_HANDLING.md's
    // "Missing or empty ID" section -- the required-ID rule is version-independent for ##META), but bcftools
    // keeps it unchanged with no warning and exit 0. Measured live: bcftools 1.23.1 emits nothing to stderr.
    String vcf = "##fileformat=VCFv4.2\n##META=<Type=String,Number=.,Values=[A, B]>\n" +
        "##contig=<ID=chr1,length=1000000>\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "chr1\t1\trs1\tA\tT\t.\tPASS\t.\n";
    BcftoolsResult result = runBcftools(vcf, "view", "-h");
    assertEquals(0, result.exitCode(), result.output());
    assertTrue(result.output().contains("##META=<Type=String,Number=.,Values=[A, B]>"), result.output());
  }

  @Test
  void testDuplicateContigIdBcftoolsKeepsFirst() throws IOException, InterruptedException {
    // representative case from DUPLICATE_METADATA_ID_HANDLING.md; that doc's own table is the fuller reference
    // covering all six keyed declaration types
    String vcf = "##fileformat=VCFv4.2\n##contig=<ID=chr1,length=1>\n##contig=<ID=chr1,length=999>\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\nchr1\t1\trs1\tA\tT\t.\tPASS\t.\n";
    BcftoolsResult result = runBcftools(vcf, "view", "-h");
    assertEquals(0, result.exitCode(), result.output());
    assertTrue(result.output().contains("##contig=<ID=chr1,length=1>"), result.output());
    assertFalse(result.output().contains("length=999"), result.output());
  }

  @Test
  void testFormatDeclaredNoSampleColumnsBcftoolsHardErrors() throws IOException, InterruptedException {
    String vcf = "##fileformat=VCFv4.2\n##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n" +
        "##contig=<ID=chr1,length=1000000>\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\n" +
        "chr1\t1\trs1\tA\tT\t.\tPASS\t.\tGT\n";
    BcftoolsResult result = runBcftools(vcf, "view");
    assertTrue(result.exitCode() != 0 &&
        result.output().contains("either FORMAT is missing or spaces are present instead of tabs"),
        result.output());
  }

  @Test
  void testEmptySampleNameInHeaderBcftoolsHardErrors() throws IOException, InterruptedException {
    String vcf = "##fileformat=VCFv4.2\n##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n" +
        "##contig=<ID=chr1,length=1000000>\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\t\ts2\n" +
        "chr1\t1\trs1\tA\tT\t.\tPASS\t.\tGT\t0/1\t0/1\n";
    BcftoolsResult result = runBcftools(vcf, "view");
    assertTrue(result.exitCode() != 0 && result.output().contains("Empty sample name"), result.output());
  }

  @Test
  void testChromContiguityViolationBcftoolsDoesNotReject() throws IOException, InterruptedException {
    // justifies this library's own warn-not-throw choice: bcftools doesn't reject a CHROM block interrupted by
    // a different CHROM and then resumed
    String vcf = "##fileformat=VCFv4.2\n##contig=<ID=chr1,length=1000000>\n##contig=<ID=chr2,length=1000000>\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\nchr1\t1\trs1\tA\tT\t.\tPASS\t.\n" +
        "chr2\t1\trs2\tA\tT\t.\tPASS\t.\nchr1\t2\trs3\tA\tT\t.\tPASS\t.\n";
    BcftoolsResult result = runBcftools(vcf, "view");
    assertEquals(0, result.exitCode(), result.output());
  }

  @Test
  void testTrailingBlankLineAtEofBcftoolsRejects() throws IOException, InterruptedException {
    String vcf = "##fileformat=VCFv4.2\n##contig=<ID=chr1,length=1000000>\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\nchr1\t1\trs1\tA\tT\t.\tPASS\t.\n\n";
    BcftoolsResult result = runBcftools(vcf, "view");
    assertEquals(8, result.exitCode(), result.output());
  }

  @Test
  void testDuplicateChromSampleColumnNameBcftoolsHardErrors() throws IOException, InterruptedException {
    // the contrast case from DUPLICATE_METADATA_ID_HANDLING.md: unlike a duplicate declaration ID, a duplicate
    // #CHROM sample-column name is a hard error for bcftools too, since sample columns are positional
    String vcf = "##fileformat=VCFv4.2\n##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n" +
        "##contig=<ID=chr1,length=1000000>\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tS1\tS1\n" +
        "chr1\t1\trs1\tA\tT\t.\tPASS\t.\tGT\t0/1\t0/1\n";
    BcftoolsResult result = runBcftools(vcf, "view");
    assertTrue(result.exitCode() != 0 && result.output().contains("Duplicated sample name"), result.output());
  }

  @Test
  void testFilterPassCombinedWithOtherCodesBcftoolsAcceptsSilently() throws IOException, InterruptedException {
    String vcf = "##fileformat=VCFv4.2\n##FILTER=<ID=q10,Description=\"qual below 10\">\n" +
        "##contig=<ID=chr1,length=1000000>\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "chr1\t1\trs1\tA\tT\t.\tq10;PASS\t.\n";
    BcftoolsResult result = runBcftools(vcf, "view");
    assertEquals(0, result.exitCode(), result.output());
    assertTrue(result.output().contains("q10;PASS"), result.output());
  }

  @Test
  void testDuplicateFilterCodeBcftoolsAcceptsSilently() throws IOException, InterruptedException {
    String vcf = "##fileformat=VCFv4.2\n##FILTER=<ID=hp23,Description=\"test\">\n" +
        "##contig=<ID=chr1,length=1000000>\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "chr1\t1\trs1\tA\tT\t.\thp23;hp23\t.\n";
    BcftoolsResult result = runBcftools(vcf, "view");
    assertEquals(0, result.exitCode(), result.output());
    assertTrue(result.output().contains("hp23;hp23"), result.output());
  }

  @Test
  void testDuplicateIdBcftoolsAcceptsSilently() throws IOException, InterruptedException {
    String vcf = "##fileformat=VCFv4.2\n##contig=<ID=chr1,length=1000000>\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\nchr1\t1\trs1;rs1\tA\tT\t.\tPASS\t.\n";
    BcftoolsResult result = runBcftools(vcf, "view");
    assertEquals(0, result.exitCode(), result.output());
    assertTrue(result.output().contains("rs1;rs1"), result.output());
  }

  @Test
  void testFilterClearNeverPromotesToPassBcftoolsConvention() throws IOException, InterruptedException {
    // bcftools annotate -x FILTER converts every FILTER value, including a real failing code, to "." when
    // clearing it, never to "PASS" -- the convention VcfPosition's FilterStatus derivation matches
    String vcf = "##fileformat=VCFv4.2\n##FILTER=<ID=q10,Description=\"qual below 10\">\n" +
        "##contig=<ID=chr1,length=1000000>\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "chr1\t1\trs1\tA\tT\t.\tq10\t.\n";
    BcftoolsResult result = runBcftools(vcf, "annotate", "-x", "FILTER");
    assertEquals(0, result.exitCode(), result.output());
    // QUAL=. FILTER=. INFO=. -- the failing code "q10" is cleared to ".", never promoted to "PASS"
    assertTrue(result.output().contains("chr1\t1\trs1\tA\tT\t.\t.\t."), result.output());
    assertFalse(result.output().contains("PASS"), result.output());
  }

  @Test
  void testInfoValueContainingBareEqualsBcftoolsAcceptsSilently() throws IOException, InterruptedException {
    String vcf = "##fileformat=VCFv4.2\n##INFO=<ID=XY,Number=1,Type=String,Description=\"depth-ish\">\n" +
        "##contig=<ID=chr1,length=1000000>\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "chr1\t1\trs1\tA\tT\t.\tPASS\tXY=1=2\n";
    BcftoolsResult result = runBcftools(vcf, "query", "-f", "%INFO/XY\n");
    assertEquals("1=2\n", result.output());
  }

  @Test
  void testDuplicateInfoKeyBcftoolsKeepsFirstOccurrence() throws IOException, InterruptedException {
    String vcf = "##fileformat=VCFv4.2\n##INFO=<ID=DP,Number=1,Type=Integer,Description=\"depth\">\n" +
        "##contig=<ID=chr1,length=1000000>\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "chr1\t1\trs1\tA\tT\t.\tPASS\tDP=1;DP=2\n";
    BcftoolsResult result = runBcftools(vcf, "query", "-f", "%INFO/DP\n");
    assertEquals("1\n", result.output());
  }

  @Test
  void testFormatDotMeansNoPerSampleFieldsBcftoolsAcceptsAndInterprets() throws IOException, InterruptedException {
    String vcf = "##fileformat=VCFv4.2\n##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n" +
        "##contig=<ID=chr1,length=1000000>\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\ts1\n" +
        "chr1\t1\trs1\tA\tT\t.\tPASS\t.\t.\tgarbage\n";
    BcftoolsResult viewResult = runBcftools(vcf, "view");
    assertEquals(0, viewResult.exitCode(), viewResult.output());
    // bcftools silently normalizes the sample's ignored raw content to "." on write, not just on read
    assertTrue(viewResult.output().lines().anyMatch(line -> line.endsWith("\t.\t.")), viewResult.output());

    BcftoolsResult queryResult = runBcftools(vcf, "query", "-f", "[%GT]\n");
    assertEquals(".\n", queryResult.output());
  }

  @Test
  void testDataLineFormatKeyInvalidTagNameBcftoolsWarnsAndContinues() throws IOException, InterruptedException {
    // 1KG starts with a digit, failing InternalUtils.FORMAT_PATTERN -- confirms bcftools warns and keeps
    // parsing every record rather than aborting the whole file (VCF_SPEC_AMBIGUITIES.md, "Data-line FORMAT
    // key fails tag-name grammar")
    String vcf = "##fileformat=VCFv4.2\n##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n" +
        "##contig=<ID=chr1,length=1000000>\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\ts1\n" +
        "chr1\t1\trs1\tA\tT\t.\tPASS\t.\tGT:1KG\t0/1:5\n" +
        "chr1\t2\trs2\tA\tT\t.\tPASS\t.\tGT\t0/1\n";
    BcftoolsResult result = runBcftools(vcf, "view");
    assertEquals(0, result.exitCode(), result.output());
    assertTrue(result.output().contains("Invalid tag name"), result.output());
    // both records are still parsed and re-emitted, not just the well-formed one
    assertTrue(result.output().lines().anyMatch(line -> line.equals("chr1\t1\trs1\tA\tT\t.\tPASS\t.\tGT:1KG\t0/1:5")),
        result.output());
    assertTrue(result.output().lines().anyMatch(line -> line.equals("chr1\t2\trs2\tA\tT\t.\tPASS\t.\tGT\t0/1")),
        result.output());
  }

  @Test
  void testUndeclaredFormatNumberBcftoolsAssumesDot() throws IOException, InterruptedException {
    String vcf = "##fileformat=VCFv4.2\n##FORMAT=<ID=XX,Type=String,Description=\"no Number\">\n" +
        "##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n" +
        "##contig=<ID=chr1,length=1000000>\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\ts1\n" +
        "chr1\t1\trs1\tA\tT\t.\tPASS\t.\tGT:XX\t0/1:a,b,c\n";
    BcftoolsResult result = runBcftools(vcf, "query", "-f", "[%XX{0} | %XX{1} | %XX{2}]\n");
    // bcftools's own warning here (visible in result.output(), not in this source) is itself confirmation of
    // the behavior under test, not a weakened assertion
    assertTrue(result.output().endsWith("a | b | c\n"), result.output());
  }

  @Test
  void testNumberGCardinalityNotValidatedByBcftoolsForAnyKey() throws IOException, InterruptedException {
    // the CNP process-lesson: bcftools's silence on a specific check is not evidence it endorses either side of
    // a disagreement -- confirmed here for PL specifically (a deliberately-mismatched cardinality: 2 values
    // given for a triallelic Number=G, which combinatorially expects 6)
    String vcf = "##fileformat=VCFv4.2\n##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n" +
        "##FORMAT=<ID=PL,Number=G,Type=Integer,Description=\"PL\">\n" +
        "##contig=<ID=chr1,length=1000000>\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\ts1\n" +
        "chr1\t1\trs1\tA\tT,C\t.\tPASS\t.\tGT:PL\t0/1:1,2\n";
    BcftoolsResult result = runBcftools(vcf, "view");
    assertEquals(0, result.exitCode(), result.output());
    assertFalse(result.output().toLowerCase(java.util.Locale.ROOT).contains("warn"), result.output());
  }

  @Test
  void testEmptyAltEntryBcftoolsSubstitutesDotMatchingThisLibrarysOwnChoice() throws IOException, InterruptedException {
    // confirms parity with EMPTY_FIELD_HANDLING.md's own choice: bcftools substitutes an empty ALT entry with
    // "." (T,,C -> T,.,C), and VcfPosition.checkAltBases does the same (fixed by e763eeb to substitute rather
    // than drop, specifically to match this bcftools behavior -- dropping would shift every later allele's GT
    // index, which substituting "." avoids)
    String vcf = "##fileformat=VCFv4.2\n##contig=<ID=chr1,length=1000000>\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\nchr1\t1\trs1\tA\tT,,C\t.\tPASS\t.\n";
    BcftoolsResult result = runBcftools(vcf, "view");
    assertEquals(0, result.exitCode(), result.output());
    assertTrue(result.output().contains("A\tT,.,C\t"), result.output());
  }

  @Test
  void testContigIdWithNonLeadingAsteriskBcftoolsAccepts() throws IOException, InterruptedException {
    // round-trip-critical: this library accepts a non-leading '*' in a contig name and the default writer
    // emits it, so if bcftools ever rejects the form, our output stops being readable by a real consumer.
    // VCFv4.3's own errata says '*' is valid when not first. hts-specs' failed_body_chrom_004.vcf and
    // failed_meta_contig_003.vcf disagree via vcf_validator. bcftools is the tie-breaker per AGENTS.md.
    // NOTE: every invocation below exits 0, including the "invalid" one -- assert on OUTPUT, never exitCode.
    String headerProbe = "##fileformat=VCFv4.3\n##contig=<ID=1.*,length=123456>\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n";
    BcftoolsResult header = runBcftools(headerProbe, "view", "-h");
    assertTrue(header.output().contains("##contig=<ID=1.*"),
        "Expected bcftools to retain a non-leading-asterisk contig ID, got: " + header.output());
    // preservation alone is not acceptance: bcftools could start warning while still echoing the header,
    // and this decision rests on SILENT acceptance. Verified live -- this invocation emits nothing to stderr.
    assertFalse(header.output().contains("Invalid contig name"),
        "Expected no invalid-contig-name warning for a non-leading asterisk, got: " + header.output());

    // body-consuming probe: 'view' WITHOUT -h, or the CHROM rule is never exercised. The contig is declared
    // so the record does not trip an unrelated "Contig 'chr*1' is not defined in the header" warning.
    String bodyProbe = "##fileformat=VCFv4.3\n##contig=<ID=chr*1,length=123456>\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\nchr*1\t100\t.\tC\tT\t.\t.\t.\n";
    BcftoolsResult body = runBcftools(bodyProbe, "view");
    assertTrue(body.output().contains("chr*1\t100"),
        "Expected bcftools to emit the chr*1 record, got: " + body.output());
    assertFalse(body.output().contains("Invalid contig name"),
        "Expected no invalid-contig-name warning for a non-leading asterisk, got: " + body.output());
  }

  @Test
  void testContigIdWithLeadingAsteriskBcftoolsWarnsButPreserves() throws IOException, InterruptedException {
    // the control that pins the boundary the decision above rests on. This is NOT a rejection assertion:
    // bcftools 1.23.1 warns via bcf_hrec_check and still exits 0 with the content preserved. The warning is
    // a HEADER check, so 'view -h' is enough to observe it.
    String vcf = "##fileformat=VCFv4.3\n##contig=<ID=*chr,length=123456>\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n*chr\t100\t.\tC\tT\t.\t.\t.\n";
    BcftoolsResult result = runBcftools(vcf, "view", "-h");
    assertTrue(result.output().contains("Invalid contig name: \"*chr\""),
        "Expected a leading-asterisk warning, got: " + result.output());
    assertTrue(result.output().contains("##contig=<ID=*chr"),
        "bcftools warns but preserves the declaration; if it now drops it, this decision needs re-checking: "
            + result.output());
  }

  @Test
  void testMetaAttributeOrderBcftoolsIgnoresCanonicalOrdering() throws IOException, InterruptedException {
    // control for the spec-order test below: the canonical ID,Number,Type,... order. If a future bcftools
    // started treating attribute order as significant, silence here (not just for the out-of-order shape
    // below) would flip too -- this is the boundary the decision rests on, the same reason the contig-asterisk
    // pair above carries both a positive and a control probe.
    String vcf = "##fileformat=VCFv4.3\n##META=<ID=Assay,Number=.,Type=String,Values=[a, b]>\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n";
    BcftoolsResult result = runBcftools(vcf, "view", "-h");
    assertEquals(0, result.exitCode(), result.output());
    assertTrue(result.output().contains("##META=<ID=Assay,Number=.,Type=String,Values=[a, b]>"),
        "Expected bcftools to retain the canonically-ordered ##META declaration, got: " + result.output());
    assertFalse(result.output().contains("[W::") || result.output().contains("[E::"),
        "Expected no bcftools warning/error at all, got: " + result.output());
  }

  @Test
  void testMetaAttributeOrderBcftoolsAcceptsTheSpecsOwnWorkedExample() throws IOException, InterruptedException {
    // round-trip-critical shape: VCFv4.3.tex:273's own worked example uses ID,Type,Number -- not the
    // ID,Number,Type,Description,Source,Version order this library's typed constructors follow for output
    // readability. No declaration type attribute-order checks any more (see VCF_SPEC_AMBIGUITIES.md's "##META
    // attribute order" entry), so this pins the bcftools side of that decision: if a future bcftools upgrade
    // started warning on ordering, that would silently rot the justification for the ten permanently-accepted
    // hts-specs-baseline.tsv rows this decision covers (failed_meta_{meta_003,info_003,format_003,alt_004},
    // three spec versions each) without anything else catching it (htslib never consults ##fileformat, and
    // htsSpecsTest never runs bcftools).
    String vcf = "##fileformat=VCFv4.3\n##META=<ID=Assay,Type=String,Number=.,Values=[a, b]>\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n";
    BcftoolsResult result = runBcftools(vcf, "view", "-h");
    assertEquals(0, result.exitCode(), result.output());
    assertTrue(result.output().contains("##META=<ID=Assay,Type=String,Number=.,Values=[a, b]>"),
        "Expected bcftools to retain the spec's own out-of-canonical-order ##META declaration verbatim, got: "
            + result.output());
    assertFalse(result.output().contains("[W::") || result.output().contains("[E::"),
        "Expected no bcftools warning/error at all, got: " + result.output());
  }

  @Test
  void testInfoAttributeOrderBcftoolsIgnoresIdPosition() throws IOException, InterruptedException {
    // Generalizes the two ##META tests above to a non-##META type, per BEHAVIOR_TABLE_STANDARD.md's
    // "Preserving the evidence" tier 2: the decision to remove attribute-order checking from every keyed type
    // (not just ##META) rests partly on this exact shape -- VCF_SPEC_AMBIGUITIES.md's "##META attribute
    // order" table's measured control row, ID declared LAST -- which previously had no dedicated
    // bcftoolsDependencyTest case of its own, only the table's prose. bcftools doesn't privilege ID's position
    // any more than any other attribute's.
    String vcf = "##fileformat=VCFv4.2\n##INFO=<Number=1,Type=Integer,Description=\"d\",ID=DP>\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n";
    BcftoolsResult result = runBcftools(vcf, "view", "-h");
    assertEquals(0, result.exitCode(), result.output());
    assertTrue(result.output().contains("##INFO=<Number=1,Type=Integer,Description=\"d\",ID=DP>"),
        "Expected bcftools to retain the ID-last ##INFO declaration verbatim, got: " + result.output());
    assertFalse(result.output().contains("[W::") || result.output().contains("[E::"),
        "Expected no bcftools warning/error at all, got: " + result.output());
  }

  @Test
  void testMetadataAttributeNameOutsideAllowlistBcftoolsDropsDeclarationSilently() throws IOException, InterruptedException {
    // AGENTS.md's own worked example for InternalUtils.METADATA_ATTRIBUTE_NAME_PATTERN: bcftools discards the
    // WHOLE declaration for an attribute name outside ^[A-Za-z_][0-9A-Za-z_.]*$, but the file itself stays
    // readable (exit 0, every other declaration and every data line still emitted) -- why
    // checkMetadataPropertyEncodingForWrite warns rather than throws for this case
    String vcf = "##fileformat=VCFv4.2\n##contig=<ID=1,a-b=v,length=100>\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n1\t1\t.\tA\tT\t.\t.\t.\n";
    BcftoolsResult result = runBcftools(vcf, "view", "-h");
    assertEquals(0, result.exitCode(), result.output());
    assertTrue(result.output().contains("Could not parse the header line"), result.output());
    // line-based, not a substring .contains() check: the error message above ITSELF quotes the whole
    // offending "##contig=..." line verbatim for diagnostics, so a plain .contains("##contig=") would match
    // that quoted text and pass even if no such line were actually emitted as real output
    assertFalse(result.output().lines().anyMatch(line -> line.startsWith("##contig=")), result.output());
  }

  @Test
  void testUnterminatedBracketValueBcftoolsHardErrors() throws IOException, InterruptedException {
    // round-trip-critical: a bracket value with no closing ']' before end of line makes bcftools abort the
    // ENTIRE file, unlike every other malformed shape in this class, which bcftools recovers from at exit 0.
    // This is what InternalUtils.findEncodingProblems's UNCONDITIONAL "no ']' anywhere" check throws for
    // (not the narrower, ##META's-Values-only isWellFormedMetaValuesBracket check) -- it fires regardless of
    // attribute name or declaration type, per VCF_SPEC_AMBIGUITIES.md's "General bracket-list quoting beyond
    // ##META's Values" entry: bcftools' OWN generic bracket handling is unaffected by this library's
    // Values-only quoting scope, so this floor stays universal even though ACCEPTANCE of a well-formed
    // bracket value does not. This specific shape (##contig's own "x" attribute, not ##META's Values)
    // deliberately pins that universality. bcftools' failure here is arguably its own defect -- filed
    // upstream at https://github.com/samtools/htslib/issues/2065 -- but this library does not attempt to
    // route around it via a version check or a spec-compliance argument. It DOES, however, defensively
    // requote any dangling '['-with-no-']' at parse time regardless of attribute/declaration type
    // (InternalUtils.parseOneAttribute's literal branch), for a narrower reason than working around
    // bcftools' bug: outside ##META's Values, splitting at a comma can turn a WELL-FORMED bracket value into
    // one that looks unterminated once truncated (see the same entry's worked example), and this library
    // must not manufacture new bcftools-fatal output from input that was never malformed to begin with. The
    // hand-built input here is never fed through this library's own parser (a real "[a=b" from a file, or a
    // truncation producing one, is always requoted before it could reach bcftools as-is) -- it exists purely
    // to pin what bcftools itself does with this byte sequence, independent of this library.
    String vcf = "##fileformat=VCFv4.2\n##contig=<ID=1,length=100,x=[a=b>\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n1\t1\t.\tA\tT\t.\t.\t.\n";
    BcftoolsResult result = runBcftools(vcf, "view", "-h");
    assertTrue(result.exitCode() != 0 && result.output().contains("Missing ']' in header line"),
        "Expected a fatal missing-']' error, got: " + result.output());
  }

  @Test
  void testNonMetaValuesBracketTruncationDoesNotManufactureAnUnreadableHeader()
      throws IOException, InterruptedException {
    // Regression test for a real bug caught by external review, using this library's OWN parser and writer
    // (not just a hand-built string, unlike every other test in this class) -- this is the strongest possible
    // verification that the fix in InternalUtils.parseOneAttribute's literal branch actually closes the gap.
    // "##contig=<ID=c1,x=[a, b],length=999>" is a perfectly well-formed input: '[' is not comma-protecting
    // outside ##META's Values, so it truncates to "x=[a" at parse time -- a value with a dangling '[' and no
    // ']' anywhere. Before the fix, writing that back out RAW manufactured a value bcftools could not read at
    // all (exit 255) from an input that was never malformed to begin with. The fix requotes it
    // ("x=\"[a\""), which this test proves bcftools accepts.
    String vcf = "##fileformat=VCFv4.3\n##contig=<ID=c1,x=[a, b],length=999>\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "c1\t5\t.\tA\tT\t.\t.\t.\n";
    WriterRoundTrip.ParsedFile parsed = WriterRoundTrip.parseText(vcf);
    String written = WriterRoundTrip.write(parsed);
    assertTrue(written.contains("x=\"[a\""), "expected the truncated value to be re-quoted: " + written);
    BcftoolsResult result = runBcftools(written, "view", "-h");
    assertEquals(0, result.exitCode(), "this library's own output must be readable by bcftools: " + result.output());
  }

  @Test
  void testBracketValueWithTrailingJunkBcftoolsDropsDeclarationSilently() throws IOException, InterruptedException {
    // the OTHER malformed-bracket shape: a bracket value that DOES close, but is followed by non-comma junk
    // before the next attribute (or the declaration's own '>'). Unlike the unterminated case above, bcftools
    // survives this at exit 0 -- but drops the whole declaration, the same non-fatal-but-silent-loss outcome
    // as the invalid-name case above. On ##contig's own "x" attribute (as here, outside ##META's Values),
    // findEncodingProblems now classifies this the same as any other unquoted structural delimiter
    // (unreadableOutput=false, since it is non-fatal) rather than as a malformed bracket specifically --
    // construction still throws regardless (checkMetadataPropertyEncoding throws for ANY problem), only
    // validateBeforeWrite()'s throw-vs-warn split reads the flag. On ##META's Values specifically, the same
    // shape stays unreadableOutput=true (isWellFormedMetaValuesBracket), since the tokenizer can never
    // produce it as a stored value on the parse path either, so the extra caution there costs nothing.
    String vcf = "##fileformat=VCFv4.2\n##contig=<ID=1,length=100,x=[a]b]>\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n1\t1\t.\tA\tT\t.\t.\t.\n";
    BcftoolsResult result = runBcftools(vcf, "view", "-h");
    assertEquals(0, result.exitCode(), result.output());
    assertTrue(result.output().contains("Could not parse the header line"), result.output());
    // line-based for the same reason as testMetadataAttributeNameOutsideAllowlistBcftoolsDropsDeclarationSilently
    assertFalse(result.output().lines().anyMatch(line -> line.startsWith("##contig=")), result.output());
  }

  @Test
  void testUnescapedSemicolonInMetadataValueBcftoolsTreatsAsOrdinaryContent() throws IOException, InterruptedException {
    // confirms this library correctly does NOT treat ';' as structural in an unquoted metadata attribute value:
    // htslib 1.23.1's bcf_hdr_parse_line only special-cases '<', '>', and ',' while scanning an unquoted value
    // (vcf.c:730-733) -- ';' is never checked, so it is ordinary content, exactly as this library's own
    // InternalUtils.findEncodingProblems treats it (no branch for ';' at all). Not to be confused with the
    // UNRELATED ';'-separated-codes grammar this library enforces for reserved FORMAT/FT and the FILTER column
    // (InternalUtils.checkFilterLikeValue) -- that is a different field's own value grammar, not this
    // declaration-attribute-list grammar, and must not be mistaken for precedent here.
    String vcf = "##fileformat=VCFv4.2\n##contig=<ID=1,length=100,description=a;b>\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n1\t1\t.\tA\tT\t.\t.\t.\n";
    BcftoolsResult result = runBcftools(vcf, "view", "-h");
    assertEquals(0, result.exitCode(), result.output());
    assertTrue(result.output().contains("description=a;b"), result.output());
    assertFalse(result.output().toLowerCase(java.util.Locale.ROOT).contains("warn"), result.output());
  }

  @Test
  void testUnescapedLessThanInMetadataValueBcftoolsMisparsesButSurvives() throws IOException, InterruptedException {
    // one of the "</> truncation shapes" InternalUtils.findEncodingProblems marks unreadableOutput=false (warn,
    // not throw, at write time): an unescaped '<' inside an unquoted value increments bcf_hdr_parse_line's own
    // `nopen` bracket-nesting counter (vcf.c:730, "if ( *q=='<' ) nopen++;"), which can make it scan past this
    // declaration's own closing '>' looking for a matching close that never legitimately arrives. The file
    // still reads (exit 0) -- just with a warning and a garbled declaration -- which is why this is warn-at-
    // write rather than throw-at-write, unlike the malformed-bracket shapes above.
    String vcf = "##fileformat=VCFv4.2\n##contig=<ID=1,description=a<b,length=100>\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n1\t1\t.\tA\tT\t.\t.\t.\n";
    BcftoolsResult result = runBcftools(vcf, "view", "-h");
    assertEquals(0, result.exitCode(), result.output());
    assertTrue(result.output().contains("Incomplete header line"), result.output());
  }

  @Test
  void testUnescapedGreaterThanInMetadataValueBcftoolsTruncatesDeclaration() throws IOException, InterruptedException {
    // the other "</> truncation shape": an unescaped '>' inside an unquoted value decrements bcf_hdr_parse_line's
    // `nopen` counter (vcf.c:731, "if ( *q=='>' ) nopen--;"), which can prematurely reach 0 and close the whole
    // declaration early. The file still reads (exit 0), but the declaration is silently truncated -- confirming
    // why this shape is also warn-at-write, not throw-at-write, same as the '<' case above
    String vcf = "##fileformat=VCFv4.2\n##contig=<ID=1,description=a>b,length=100>\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n1\t1\t.\tA\tT\t.\t.\t.\n";
    BcftoolsResult result = runBcftools(vcf, "view", "-h");
    assertEquals(0, result.exitCode(), result.output());
    assertTrue(result.output().contains("Dropped trailing junk"), result.output());
    // line-based, not a substring .contains() check: the warning above ITSELF quotes the whole original
    // (untruncated) line for diagnostics, so a plain .contains("length=100") would match that quoted text
    // even though the real, truncated "##contig=" line emitted below it never mentions "length" at all
    assertTrue(result.output().lines().anyMatch(line -> line.equals("##contig=<ID=1,description=a>")),
        result.output());
    assertFalse(result.output().lines().anyMatch(line -> line.startsWith("##contig=") && line.contains("length")),
        result.output());
  }

  private record BcftoolsResult(int exitCode, String output) {}
}
