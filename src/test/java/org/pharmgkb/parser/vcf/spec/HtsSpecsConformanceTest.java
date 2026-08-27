package org.pharmgkb.parser.vcf.spec;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.pharmgkb.parser.vcf.VcfFormatException;
import org.pharmgkb.parser.vcf.VcfParser;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;


/**
 * Parses every fixture in hts-specs' official VCF conformance suite (fetched by the htsSpecsCheckout Gradle task)
 * and checks this library's behavior against it. Tagged so it only runs under the opt-in `htsSpecsTest` Gradle
 * task, never under `test`, since it requires that fixture checkout to already exist.
 *
 * <p>Every check's outcome is compared against the committed {@code hts-specs-baseline.tsv}: a dynamic test only
 * fails on a NEW divergence (not yet baselined) or a STALE baseline entry (no longer reproduces) -- see
 * {@link Baseline}. Run {@code ./gradlew htsSpecsUpdateBaseline} after a deliberate change to regenerate the
 * baseline file from current results.
 *
 * @author Douglas Myers-Turnbull
 */
@Tag("hts-specs")
class HtsSpecsConformanceTest {

  // every version this library can represent must at least parse its own passed/ fixtures without error, per
  // AGENTS.md ("The library accepts VCF 4.x input from before and after the target version")
  private static final List<String> PASSED_VERSIONS = List.of("4.1", "4.2", "4.3", "4.5");

  // failed/ fixtures are capped at this library's spec target (VCFv4.3): a later version's "failure" can be a rule
  // that version introduced which VCFv4.3 leaves silent -- content this library correctly, silently accepts as
  // genuinely valid under 4.3 semantics, not merely tolerated. Testing those against "throw or warn" would be a
  // false failure, not a real bug. 4.4/4.5 stay out for exactly that reason; raising this list is a target-version
  // change, not a test tweak.
  private static final List<String> FAILED_VERSIONS = List.of("4.1", "4.2", "4.3");

  private static final String CAUSE_OF_FAILURE_PREFIX = "##CauseOfFailure=";

  // These two warnings fire whenever #CHROM declares sample columns with no matching ##SAMPLE metadata line --
  // true for the vast majority of failed/ fixtures regardless of their actual violation, since most real-world
  // VCFs never declare ##SAMPLE at all. Treating either as "this fixture's invalidity was detected" produced a
  // near-universal false positive that hid real gaps from HTS_SPECS_BASELINE_STATUS.md's catalog: parsing e.g.
  // failed_body_duplicated_000.vcf, failed_body_unsorted_000.vcf, or failed_body_chrom_000.vcf each produced
  // exactly one of these two warnings and nothing else, regardless of each fixture's own distinct documented
  // cause. Extend this set if a future investigation finds another generic, content-independent warning with
  // the same masking effect.
  private static final Set<String> GENERIC_NOISE_WARNINGS = Set.of(
      "Sample {} is missing in the metadata",
      "There are {} samples in the header but {} in the metadata");

  // Multi-line failed/ fixtures where "any non-generic warning fired" (see GENERIC_NOISE_WARNINGS above) isn't
  // enough to prove THIS fixture's own documented ##CauseOfFailure= was actually detected, since a single parse
  // could legitimately produce more than one warning. Each entry's substring(s) are hand-verified against the
  // fixture's actual captured warnings (visible in a hard-fail's message below -- no separate scratch harness
  // needed) and its ##CauseOfFailure= text. Content matching (not line-number correlation) is used because the
  // corpus's multi-line fixtures are either relational ordering defects spanning adjacent records (no single
  // "correct" line exists) or repeat the same defect on every affected line (any one line's warning is equally
  // valid evidence).
  //
  // PROTOCOL for a new multi-line failed/ fixture with no entry here (e.g. after an htsSpecsPinnedSha bump):
  // assertThrowsOrWarns hard-fails directly on it (see below) -- deliberate, not a bug. The failure message
  // includes the actual captured warnings, so read those, cross-check against the fixture's ##CauseOfFailure=,
  // pick a substring specific enough to prove the right defect fired, and add an entry here. See
  // HTS_SPECS_BASELINE_STATUS.md's re-verification section for the rest of the pin-bump checklist, including
  // pruning entries here that a pin bump has orphaned (fixture renamed/removed upstream).
  private static final Map<String, List<String>> EXPECTED_WARNING_SUBSTRINGS = Map.ofEntries(
      Map.entry("test/vcf/4.1/failed/failed_body_unsorted_000.vcf", List.of("out of order")),
      Map.entry("test/vcf/4.2/failed/failed_body_unsorted_000.vcf", List.of("out of order")),
      Map.entry("test/vcf/4.1/failed/failed_body_contiguous_000.vcf", List.of("CHROM 1 is not contiguous")),
      Map.entry("test/vcf/4.1/failed/failed_body_contiguous_001.vcf", List.of("CHROM 2 is not contiguous")),
      Map.entry("test/vcf/4.2/failed/failed_body_contiguous_000.vcf", List.of("CHROM 1 is not contiguous")),
      Map.entry("test/vcf/4.2/failed/failed_body_contiguous_001.vcf", List.of("CHROM 2 is not contiguous")),
      Map.entry("test/vcf/4.3/failed/failed_body_unsorted_000.vcf", List.of("out of order")),
      Map.entry("test/vcf/4.3/failed/failed_body_contiguous_000.vcf", List.of("CHROM 1 is not contiguous")),
      Map.entry("test/vcf/4.3/failed/failed_body_contiguous_001.vcf", List.of("CHROM 2 is not contiguous")),
      Map.entry("test/vcf/4.3/failed/failed_body_format_007.vcf",
          List.of("FORMAT ID \"G%3AS\" does not match the required tag-name grammar"))
  );

  // literal, project-relative path to the committed baseline file -- used both to locate it as a classpath
  // resource for reading (works regardless of working directory) and, in update mode, as a real filesystem path
  // to overwrite (classpath resources aren't writable; this only matters for htsSpecsUpdateBaseline)
  private static final Path BASELINE_FILE =
      Path.of("src/test/resources/org/pharmgkb/parser/vcf/spec/hts-specs-baseline.tsv");

  private static final Baseline BASELINE = createBaseline();

  private static Baseline createBaseline() {
    try (InputStream in = HtsSpecsConformanceTest.class.getResourceAsStream("hts-specs-baseline.tsv")) {
      return new Baseline(in, Boolean.getBoolean("htsSpecsUpdateBaseline"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @AfterAll
  static void writeReports() throws IOException {
    Path reportsDir = Path.of("build/reports/hts-specs-conformance");
    if (Boolean.getBoolean("htsSpecsUpdateBaseline")) {
      BASELINE.writeUpdatedBaseline(BASELINE_FILE);
    } else {
      BASELINE.writeFindings(reportsDir.resolve("findings.tsv"));
      if (BASELINE.hasFindings()) {
        System.out.println(BASELINE.findingsCount() + " new/stale divergence(s) -- see " +
            reportsDir.resolve("findings.tsv") + ", or run htsSpecsUpdateBaseline if these are expected");
      }
    }
    BASELINE.writeSummary(reportsDir.resolve("summary.md"));
  }

  @Test
  void testAppenderCapturesWarnEvents() {
    Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      assertFalse(appender.hasWarnOrHigher());
      LoggerFactory.getLogger(HtsSpecsConformanceTest.class).warn("sanity check warning");
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void testHasWarnOrHigherExcludingIgnoresOnlyExactTemplateMatches() {
    Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      Set<String> ignored = Set.of("known noise: {}");
      org.slf4j.Logger testLogger = LoggerFactory.getLogger(HtsSpecsConformanceTest.class);

      // no events at all -> nothing relevant
      assertFalse(appender.hasWarnOrHigherExcluding(ignored));

      // only an ignored-template event, WITH a real interpolated value -> still nothing relevant. This proves
      // ILoggingEvent.getMessage() returns the pre-substitution template ("known noise: {}"), not the
      // formatted text ("known noise: 42") -- if it returned the formatted text instead, this assertion
      // would fail, since "known noise: 42" is not in the ignored set.
      testLogger.warn("known noise: {}", 42);
      assertFalse(appender.hasWarnOrHigherExcluding(ignored));

      // a second, non-ignored warning -> now something relevant fired
      testLogger.warn("a genuinely relevant warning");
      assertTrue(appender.hasWarnOrHigherExcluding(ignored));

      // getRelevantWarnOrHigherMessages() must report only the non-ignored, formatted message
      assertEquals(List.of("a genuinely relevant warning"), appender.getRelevantWarnOrHigherMessages(ignored));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @TestFactory
  Stream<DynamicTest> passedFixturesParseWithoutError() throws IOException {
    List<Path> files = new ArrayList<>();
    for (String version : PASSED_VERSIONS) {
      collectVcfFiles(fixtureDir(version, "passed"), files);
    }
    if (files.isEmpty()) {
      return Stream.of(DynamicTest.dynamicTest("hts-specs checkout missing", () ->
          fail("No passed/ fixtures found under " + htsSpecsDir() + " -- run `./gradlew htsSpecsCheckout` first")));
    }
    return files.stream().map(file -> DynamicTest.dynamicTest(relativeName(file), () -> assertParsesCleanly(file)));
  }

  @TestFactory
  Stream<DynamicTest> failedFixturesThrowOrWarn() throws IOException {
    List<Path> files = new ArrayList<>();
    for (String version : FAILED_VERSIONS) {
      collectVcfFiles(fixtureDir(version, "failed"), files);
    }
    if (files.isEmpty()) {
      return Stream.of(DynamicTest.dynamicTest("hts-specs checkout missing", () ->
          fail("No failed/ fixtures found under " + htsSpecsDir() + " -- run `./gradlew htsSpecsCheckout` first")));
    }
    return files.stream().map(file -> {
      String cause = readCauseOfFailure(file);
      return DynamicTest.dynamicTest(relativeName(file) + " (" + cause + ")", () -> assertThrowsOrWarns(file, cause));
    });
  }

  @TestFactory
  Stream<DynamicTest> passedFixturesRoundTripSemantically() throws IOException {
    List<Path> files = new ArrayList<>();
    for (String version : PASSED_VERSIONS) {
      collectVcfFiles(fixtureDir(version, "passed"), files);
    }
    if (files.isEmpty()) {
      return Stream.of(DynamicTest.dynamicTest("hts-specs checkout missing", () ->
          fail("No passed/ fixtures found under " + htsSpecsDir() + " -- run `./gradlew htsSpecsCheckout` first")));
    }
    return files.stream().map(file -> DynamicTest.dynamicTest(relativeName(file),
        () -> checkRoundTripSemantic(file)));
  }

  @TestFactory
  Stream<DynamicTest> passedFixturesRoundTripByteIdentical() throws IOException {
    List<Path> files = new ArrayList<>();
    for (String version : PASSED_VERSIONS) {
      collectVcfFiles(fixtureDir(version, "passed"), files);
    }
    if (files.isEmpty()) {
      return Stream.of(DynamicTest.dynamicTest("hts-specs checkout missing", () ->
          fail("No passed/ fixtures found under " + htsSpecsDir() + " -- run `./gradlew htsSpecsCheckout` first")));
    }
    return files.stream().map(file -> DynamicTest.dynamicTest(relativeName(file),
        () -> checkRoundTripByteDiff(file)));
  }

  @TestFactory
  Stream<DynamicTest> passedFixturesRoundTripWithoutNewWarnings() throws IOException {
    List<Path> files = new ArrayList<>();
    for (String version : PASSED_VERSIONS) {
      collectVcfFiles(fixtureDir(version, "passed"), files);
    }
    if (files.isEmpty()) {
      return Stream.of(DynamicTest.dynamicTest("hts-specs checkout missing", () ->
          fail("No passed/ fixtures found under " + htsSpecsDir() + " -- run `./gradlew htsSpecsCheckout` first")));
    }
    return files.stream().map(file -> DynamicTest.dynamicTest(relativeName(file),
        () -> checkRoundTripWarningInvariant(file)));
  }

  // Phase 3 writer-compliance review's Path B kickoff (finding #6): failed/ fixtures were never round-tripped
  // at all, even though the ones this library accepts leniently (a warning, not a throw -- see
  // failedFixturesThrowOrWarn above) parse successfully and have real data to write back out, exactly the
  // "lenient-recovery content" AGENTS.md's Writer Validation and Round-Trip Policy governs. checkRoundTripSemantic
  // already returns early for a fixture that doesn't even parse (see its own comment), so reusing it unchanged
  // here is safe: a fixture this library genuinely rejects contributes no dynamic-test failure, only the ones
  // actually accepted (silently or with a warning) get exercised. Baseline-driven, exactly like the passed/
  // round-trip check above -- a genuine divergence (e.g. a recovered value that doesn't survive
  // write-then-reparse, or one that throws on write for an already-documented reason such as HTS_SPECS_BASELINE_
  // STATUS.md's "INFO contains a whitespace" row) is recorded, not a hard failure, since this corpus's failed/
  // fixtures are deliberately non-compliant content and the point of this factory is coverage, not a
  // zero-divergence guarantee.
  //
  // Deliberately semantic-only, no byte-identical counterpart: measured directly (running this factory before
  // adding it) that a byte-diff version would fail on the overwhelming majority of accepted failed/ fixtures --
  // hundreds of near-universal divergences from declaration reordering and the recovered value itself being
  // normalized on write (the exact "recovery" this corpus's content is defined by), which would bloat the
  // baseline with noise rather than tracking a meaningful exception. The semantic check is what AGENTS.md's
  // policy actually requires ("without losing fields or producing malformed column structure"); byte-identity
  // was never a guarantee this corpus's non-compliant content could satisfy.

  @TestFactory
  Stream<DynamicTest> failedFixturesRoundTripSemantically() throws IOException {
    List<Path> files = new ArrayList<>();
    for (String version : FAILED_VERSIONS) {
      collectVcfFiles(fixtureDir(version, "failed"), files);
    }
    if (files.isEmpty()) {
      return Stream.of(DynamicTest.dynamicTest("hts-specs checkout missing", () ->
          fail("No failed/ fixtures found under " + htsSpecsDir() + " -- run `./gradlew htsSpecsCheckout` first")));
    }
    return files.stream().map(file -> DynamicTest.dynamicTest(relativeName(file),
        () -> checkRoundTripSemantic(file)));
  }

  private static void collectVcfFiles(Path dir, List<Path> into) throws IOException {
    if (!Files.isDirectory(dir)) {
      return;
    }
    try (Stream<Path> stream = Files.list(dir)) {
      stream.filter(p -> p.toString().endsWith(".vcf")).sorted().forEach(into::add);
    }
  }

  private static void assertParsesCleanly(Path file) throws IOException {
    String fixturePath = relativeName(file);
    boolean diverged = false;
    String reason = "";
    try (VcfParser parser = new VcfParser.Builder()
        .fromFile(file)
        .parseWith((metadata, record) -> { })
        .build()) {
      parser.parse();
    } catch (RuntimeException e) {
      diverged = true;
      reason = String.valueOf(e.getMessage());
    }
    boolean acceptable = BASELINE.check(fixturePath, "parser-no-throw", diverged, "parse-exception", reason);
    boolean finalDiverged = diverged;
    String finalReason = reason;
    assertTrue(acceptable, () -> finalDiverged
        ? fixturePath + " (parser-no-throw): " + finalReason
        : "baseline entry for " + fixturePath +
            " (parser-no-throw) no longer reproduces -- remove it from hts-specs-baseline.tsv");
  }

  private static String readCauseOfFailure(Path file) {
    try {
      for (String line : Files.readAllLines(file)) {
        if (line.startsWith(CAUSE_OF_FAILURE_PREFIX)) {
          return line.substring(CAUSE_OF_FAILURE_PREFIX.length());
        }
        if (!line.startsWith("##")) {
          break;
        }
      }
    } catch (IOException e) {
      return "unreadable: " + e.getMessage();
    }
    return "unknown";
  }

  /**
   * @return the number of non-blank lines in {@code file} strictly after its {@code #CHROM} header line (i.e.
   * the number of actual VCF data records). Deliberately NOT "any line not starting with #" -- some fixtures
   * inject a malformed non-#-prefixed line into the header block itself (failed_meta_004.vcf,
   * failed_meta_005.vcf), and a trailing blank line at EOF (failed_body_format_000.vcf) would otherwise also be
   * miscounted as a second data line when there's really only one. Used by EXPECTED_WARNING_SUBSTRINGS below to
   * decide which failed/ fixtures have enough real data lines that "some warning fired somewhere" could
   * plausibly be the wrong warning.
   */
  private static long countDataLines(Path file) throws IOException {
    try (Stream<String> lines = Files.lines(file)) {
      return lines
          .dropWhile(line -> !line.startsWith("#CHROM"))
          .skip(1)
          .filter(line -> !line.isBlank())
          .count();
    }
  }

  private static void assertThrowsOrWarns(Path file, String causeOfFailure) throws IOException {
    String fixturePath = relativeName(file);
    Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    boolean threw = false;
    try {
      try (VcfParser parser = new VcfParser.Builder()
          .fromFile(file)
          .parseWith((metadata, record) -> { })
          .build()) {
        parser.parse();
      } catch (VcfFormatException e) {
        threw = true;
      }
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    boolean silentlyAccepted = !threw && !appender.hasWarnOrHigherExcluding(GENERIC_NOISE_WARNINGS);
    boolean acceptable = BASELINE.check(fixturePath, "parser-throw-or-warn", silentlyAccepted, "silently-accepted",
        "CauseOfFailure: " + causeOfFailure);
    assertTrue(acceptable, () -> silentlyAccepted
        ? fixturePath + " (CauseOfFailure: " + causeOfFailure +
            ") was accepted with no VcfFormatException and no logged warning -- invalid content silently accepted"
        : "baseline entry for " + fixturePath +
            " (parser-throw-or-warn) no longer reproduces -- remove it from hts-specs-baseline.tsv");
    if (!threw && !silentlyAccepted) {
      long dataLines = countDataLines(file);
      if (dataLines > 1) {
        List<String> expectedSubstrings = EXPECTED_WARNING_SUBSTRINGS.get(fixturePath);
        List<String> actualMessages = appender.getRelevantWarnOrHigherMessages(GENERIC_NOISE_WARNINGS);
        if (expectedSubstrings == null) {
          fail(fixturePath + " has " + dataLines + " data lines but no entry in EXPECTED_WARNING_SUBSTRINGS -- " +
              "see that constant's comment for how to add one. Captured warnings were: " + actualMessages);
        } else {
          boolean contentMismatch = expectedSubstrings.stream().anyMatch(s -> !appender.anyMessageContains(s));
          boolean contentAcceptable = BASELINE.check(fixturePath, "parser-warn-content", contentMismatch,
              "expected-substring-missing", "CauseOfFailure: " + causeOfFailure);
          assertTrue(contentAcceptable, () -> contentMismatch
              ? fixturePath + ": expected warning substring(s) " + expectedSubstrings +
                  " not all found in captured warnings: " + actualMessages
              : "baseline entry for " + fixturePath +
                  " (parser-warn-content) no longer reproduces -- remove it from hts-specs-baseline.tsv");
        }
      }
    }
  }

  private static void checkRoundTripSemantic(Path file) throws IOException {
    String fixturePath = relativeName(file);
    WriterRoundTrip.ParsedFile before;
    try {
      before = WriterRoundTrip.parseFile(file);
    } catch (RuntimeException e) {
      // this fixture already fails parser-no-throw (or is baselined there) -- there's no successfully-parsed
      // data to write, so there's nothing to test about the writer here. Reports as SKIPPED with the reason
      // visible (matching LocalFixtureConformanceTest.checkRoundTripByteDiff's identical pattern for a
      // not-applicable round-trip check), rather than silently PASSED -- a plain `return` here would make a
      // fixture that later starts failing parser-no-throw look, in this dynamic test's own report, identical
      // to one that was actually round-tripped and found clean, rather than one round-tripping never checked.
      // Currently never reached: no passed/failed fixture in the checked-out corpus is baselined for
      // parser-no-throw today (2026-08-25), so this is a not-yet-exercised safety net, not a live divergence.
      Assumptions.abort(fixturePath + " (writer-semantic): does not parse (see parser-no-throw) -- nothing to " +
          "round-trip");
      return;
    }
    String written;
    try {
      written = WriterRoundTrip.write(before);
    } catch (RuntimeException e) {
      boolean acceptable = BASELINE.check(fixturePath, "writer-semantic", true, "write-exception",
          String.valueOf(e.getMessage()));
      assertTrue(acceptable, () -> fixturePath + " (writer-semantic): unexpectedly threw while writing: " +
          e.getMessage());
      return;
    }
    WriterRoundTrip.ParsedFile after;
    try {
      after = WriterRoundTrip.parseText(written);
    } catch (RuntimeException e) {
      boolean acceptable = BASELINE.check(fixturePath, "writer-semantic", true, "reparse-exception",
          String.valueOf(e.getMessage()));
      assertTrue(acceptable, () -> fixturePath +
          " (writer-semantic): unexpectedly threw while re-parsing written output: " + e.getMessage());
      return;
    }
    Optional<String> diff;
    try {
      diff = WriterRoundTrip.semanticDiff(before, after);
    } catch (RuntimeException e) {
      // matches the parseFile/write/parseText phases above: semanticDiff calls typed getters (e.g. getQuality())
      // on both `before` and `after` to compare them, and those can throw too (deferred validation) -- previously
      // uncaught here, so any such exception escaped as a hard, unbaselined test failure instead of a tracked
      // divergence, for any fixture, not just this one.
      boolean acceptable = BASELINE.check(fixturePath, "writer-semantic", true, "semantic-diff-exception",
          String.valueOf(e.getMessage()));
      assertTrue(acceptable, () -> fixturePath +
          " (writer-semantic): unexpectedly threw while comparing before/after: " + e.getMessage());
      return;
    }
    boolean acceptable = BASELINE.check(fixturePath, "writer-semantic", diff.isPresent(), "semantic-mismatch",
        diff.orElse(""));
    assertTrue(acceptable, () -> diff.isPresent()
        ? fixturePath + " (writer-semantic): " + diff.get()
        : "baseline entry for " + fixturePath +
            " (writer-semantic) no longer reproduces -- remove it from hts-specs-baseline.tsv");
  }

  private static void checkRoundTripByteDiff(Path file) throws IOException {
    String fixturePath = relativeName(file);
    String original = Files.readString(file);
    WriterRoundTrip.ParsedFile before;
    try {
      before = WriterRoundTrip.parseFile(file);
    } catch (RuntimeException e) {
      // see checkRoundTripSemantic's identical branch for why this is Assumptions.abort(), not a plain return
      Assumptions.abort(fixturePath + " (writer-bytediff): does not parse (see parser-no-throw) -- nothing to " +
          "byte-diff");
      return;
    }
    String written;
    try {
      written = WriterRoundTrip.write(before);
    } catch (RuntimeException e) {
      boolean acceptable = BASELINE.check(fixturePath, "writer-bytediff", true, "write-exception",
          String.valueOf(e.getMessage()));
      assertTrue(acceptable, () -> fixturePath + " (writer-bytediff): unexpectedly threw while writing: " +
          e.getMessage());
      return;
    }
    Optional<String> diff = WriterRoundTrip.byteDiff(original, written);
    boolean acceptable = BASELINE.check(fixturePath, "writer-bytediff", diff.isPresent(), "byte-diff",
        diff.orElse(""));
    assertTrue(acceptable, () -> diff.isPresent()
        ? fixturePath + " (writer-bytediff): " + diff.get()
        : "baseline entry for " + fixturePath +
            " (writer-bytediff) no longer reproduces -- remove it from hts-specs-baseline.tsv");
  }

  private static void checkRoundTripWarningInvariant(Path file) throws IOException {
    String fixturePath = relativeName(file);
    Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    WarnCapturingAppender beforeAppender = new WarnCapturingAppender();
    beforeAppender.start();
    root.addAppender(beforeAppender);
    WriterRoundTrip.ParsedFile before;
    Set<String> beforeMessages;
    try {
      before = WriterRoundTrip.parseFile(file);
      // TreeSet, not Set.copyOf: Set.copyOf's iteration order is randomized per JVM run (JDK's hash-flood
      // protection for its immutable collections), which would make the "before=...after=..." reason string
      // baselined by one run fail to string-match the same run's re-verification in a later JVM invocation.
      beforeMessages = new TreeSet<>(beforeAppender.getRelevantWarnOrHigherMessages(GENERIC_NOISE_WARNINGS));
    } catch (RuntimeException e) {
      Assumptions.abort(fixturePath + " (writer-warning-invariant): does not parse (see parser-no-throw) -- " +
          "nothing to check");
      return;
    } finally {
      root.detachAppender(beforeAppender);
      beforeAppender.stop();
    }
    // R8: no longer aborts here for a fixture that already warned on its original parse -- comparing the
    // FULL before/after warning set (not just "did any new warning appear") lets a fixture that already
    // warns still be checked, catching e.g. "warning A silently became warning B on reparse" (found by R7's
    // final whole-branch review as this check's own weaker-than-ideal gate).

    String written;
    try {
      written = WriterRoundTrip.write(before);
    } catch (RuntimeException e) {
      boolean acceptable = BASELINE.check(fixturePath, "writer-warning-invariant", true, "write-exception",
          String.valueOf(e.getMessage()));
      assertTrue(acceptable, () -> fixturePath + " (writer-warning-invariant): unexpectedly threw while " +
          "writing: " + e.getMessage());
      return;
    }

    WarnCapturingAppender afterAppender = new WarnCapturingAppender();
    afterAppender.start();
    root.addAppender(afterAppender);
    boolean reparseThrew = false;
    String reparseMessage = null;
    Set<String> afterMessages = new TreeSet<>();
    try {
      WriterRoundTrip.parseText(written);
      afterMessages = new TreeSet<>(afterAppender.getRelevantWarnOrHigherMessages(GENERIC_NOISE_WARNINGS));
    } catch (RuntimeException e) {
      reparseThrew = true;
      reparseMessage = String.valueOf(e.getMessage());
    } finally {
      root.detachAppender(afterAppender);
      afterAppender.stop();
    }
    if (reparseThrew) {
      boolean acceptable = BASELINE.check(fixturePath, "writer-warning-invariant", true, "reparse-exception",
          reparseMessage);
      String finalReparseMessage = reparseMessage;
      assertTrue(acceptable, () -> fixturePath + " (writer-warning-invariant): unexpectedly threw while " +
          "re-parsing written output: " + finalReparseMessage);
      return;
    }

    boolean warningSetChanged = !beforeMessages.equals(afterMessages);
    Set<String> finalAfterMessages = afterMessages;
    boolean acceptable = BASELINE.check(fixturePath, "writer-warning-invariant", warningSetChanged,
        "warning-set-changed",
        warningSetChanged ? "before=" + beforeMessages + " after=" + finalAfterMessages : "");
    assertTrue(acceptable, () -> warningSetChanged
        ? fixturePath + " (writer-warning-invariant): round trip changed the warning set -- before=" +
            beforeMessages + " after=" + finalAfterMessages
        : "baseline entry for " + fixturePath +
            " (writer-warning-invariant) no longer reproduces -- remove it from hts-specs-baseline.tsv");
  }

  private static String htsSpecsDir() {
    return System.getProperty("htsSpecsDir", "build/hts-specs-checkout");
  }

  private static Path fixtureDir(String version, String kind) {
    return Path.of(htsSpecsDir(), "test", "vcf", version, kind);
  }

  private static String relativeName(Path file) {
    return Path.of(htsSpecsDir()).relativize(file).toString().replace('\\', '/');
  }
}
