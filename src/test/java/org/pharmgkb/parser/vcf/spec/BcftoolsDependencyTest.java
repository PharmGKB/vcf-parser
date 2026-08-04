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
    assertTrue(result.output().contains("Could not parse the header line"),
        "Expected a header-parse error, got: " + result.output());
  }

  @Test
  void testGenericPropertyBracketedNoEqualsBcftoolsRejects() throws IOException, InterruptedException {
    // round-trip-critical: same as above, for a non-empty bracket-wrapped value with no '=' at all
    String vcf = "##fileformat=VCFv4.2\n##pedigreeDB=<http://foo.com>\n##contig=<ID=chr1,length=1000000>\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\nchr1\t1\trs1\tA\tT\t.\tPASS\t.\n";
    BcftoolsResult result = runBcftools(vcf, "view", "-h");
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

  private record BcftoolsResult(int exitCode, String output) {}
}
