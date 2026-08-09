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

  /** Converts {@code vcfContent} to BCF, then runs {@code bcftools query -f <queryFormat>} against it, since
   * plain-text VCF doesn't enforce a FORMAT/INFO key's declared Type the way binary BCF does. */
  private static String convertToBcfThenQuery(String vcfContent, String queryFormat)
      throws IOException, InterruptedException {
    Path vcfFile = Files.createTempFile("bcftools-dependency-test-", ".vcf");
    Path bcfFile = Files.createTempFile("bcftools-dependency-test-", ".bcf");
    try {
      Files.writeString(vcfFile, vcfContent, StandardCharsets.UTF_8);
      BcftoolsResult convert = execute(List.of("bcftools", "view", "-Ob", "-o", bcfFile.toAbsolutePath().toString(),
          vcfFile.toAbsolutePath().toString()));
      assertEquals(0, convert.exitCode(), "VCF-to-BCF conversion failed: " + convert.output());
      BcftoolsResult query = execute(List.of("bcftools", "query", "-f", queryFormat,
          bcfFile.toAbsolutePath().toString()));
      return query.output();
    } finally {
      Files.deleteIfExists(vcfFile);
      Files.deleteIfExists(bcfFile);
    }
  }

  private static BcftoolsResult execute(List<String> command) throws IOException, InterruptedException {
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    int exitCode = process.waitFor();
    return new BcftoolsResult(exitCode, output);
  }

  @Test
  void testDuplicateAttributeWithinDeclarationBcftoolsKeepsLast() throws IOException, InterruptedException {
    // a repeated attribute within one declaration (distinct from a duplicate declaration ID across separate
    // lines -- see DUPLICATE_METADATA_ID_HANDLING.md for that case) resolves to the LAST-declared value once
    // forced through actual BCF binary encoding, which enforces the declared Type unlike plain-text VCF
    String typeStringLast = "##fileformat=VCFv4.2\n" +
        "##FORMAT=<ID=XX,Type=Integer,Type=String,Number=1,Description=\"dup type\">\n" +
        "##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n" +
        "##contig=<ID=chr1,length=1000000>\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\ts1\n" +
        "chr1\t1\trs1\tA\tT\t.\tPASS\t.\tGT:XX\t0/1:abc\n";
    assertEquals("abc\n", convertToBcfThenQuery(typeStringLast, "[%XX]\n"));

    String typeIntegerLast = "##fileformat=VCFv4.2\n" +
        "##FORMAT=<ID=XX,Type=String,Type=Integer,Number=1,Description=\"dup type\">\n" +
        "##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n" +
        "##contig=<ID=chr1,length=1000000>\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\ts1\n" +
        "chr1\t1\trs1\tA\tT\t.\tPASS\t.\tGT:XX\t0/1:abc\n";
    // with Type=Integer resolved as the last-declared type, "abc" is not a valid Integer -- bcftools hard-errors
    // converting to BCF rather than silently coercing it, confirming the last-declared type genuinely governs
    Path vcfFile = Files.createTempFile("bcftools-dependency-test-", ".vcf");
    Path bcfFile = Files.createTempFile("bcftools-dependency-test-", ".bcf");
    try {
      Files.writeString(vcfFile, typeIntegerLast, StandardCharsets.UTF_8);
      BcftoolsResult result = execute(List.of("bcftools", "view", "-Ob", "-o", bcfFile.toAbsolutePath().toString(),
          vcfFile.toAbsolutePath().toString()));
      assertTrue(result.exitCode() != 0 && result.output().contains("Invalid character"),
          "Expected a hard parse error for a non-Integer value under Type=Integer, got: " + result.output());
    } finally {
      Files.deleteIfExists(vcfFile);
      Files.deleteIfExists(bcfFile);
    }
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

  private record BcftoolsResult(int exitCode, String output) {}
}
