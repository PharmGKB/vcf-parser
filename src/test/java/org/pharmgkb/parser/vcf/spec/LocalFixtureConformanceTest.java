package org.pharmgkb.parser.vcf.spec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.pharmgkb.parser.vcf.VcfFormatException;
import org.pharmgkb.parser.vcf.VcfParser;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;


/**
 * Parses every fixture in this project's own locally-authored conformance corpus
 * ({@code src/test/resources/org/pharmgkb/parser/vcf/spec/local-fixtures/{passed,failed}/*.vcf}) and checks
 * this library's behavior against each fixture's self-declared expectation (see {@link LocalFixtureMetadata}).
 *
 * <p>Unlike {@link HtsSpecsConformanceTest}, this corpus is fully authored by this project, not fetched from
 * an external repo, so there is no baseline-divergence file: every fixture's expected outcome is declared
 * inline in its own header comments (see {@link LocalFixtureMetadata} for what each key means), and a
 * mismatch is simply a test failure. This class is not tagged -- it runs under the default {@code test} task,
 * since every fixture is a committed resource with no network dependency.
 */
class LocalFixtureConformanceTest {

  private static final Path FIXTURES_ROOT =
      Path.of("src/test/resources/org/pharmgkb/parser/vcf/spec/local-fixtures");

  private static final Set<String> VALID_EXPECT_PARSER_VALUES = Set.of("throw", "warn", "silent");

  // Fire for almost any fixture with sample columns and no ##SAMPLE metadata line -- the same two message
  // templates HtsSpecsConformanceTest's own GENERIC_NOISE_WARNINGS excludes, for the identical reason (see
  // that constant's comment): they come from the same parser code, so treating either as "a warning fired"
  // would produce a false positive on any future local fixture that adds sample columns.
  private static final Set<String> GENERIC_NOISE_WARNINGS = Set.of(
      "Sample {} is missing in the metadata",
      "There are {} samples in the header but {} in the metadata");

  @TestFactory
  Stream<DynamicTest> localFixtureHeadersAreWellFormed() throws IOException {
    List<Path> passedFiles = new ArrayList<>();
    collectVcfFiles(FIXTURES_ROOT.resolve("passed"), passedFiles);
    List<Path> failedFiles = new ArrayList<>();
    collectVcfFiles(FIXTURES_ROOT.resolve("failed"), failedFiles);
    List<DynamicTest> emptyDirGuards = new ArrayList<>();
    emptyDirGuards.addAll(requireNonEmpty(FIXTURES_ROOT.resolve("passed"), passedFiles));
    emptyDirGuards.addAll(requireNonEmpty(FIXTURES_ROOT.resolve("failed"), failedFiles));
    if (!emptyDirGuards.isEmpty()) {
      return emptyDirGuards.stream();
    }
    List<Path> files = new ArrayList<>(passedFiles);
    files.addAll(failedFiles);
    return files.stream().map(file -> DynamicTest.dynamicTest(relativeName(file),
        () -> assertHeaderWellFormed(file)));
  }

  @TestFactory
  Stream<DynamicTest> localPassedFixturesParseCleanly() throws IOException {
    Path dir = FIXTURES_ROOT.resolve("passed");
    List<Path> files = new ArrayList<>();
    collectVcfFiles(dir, files);
    List<DynamicTest> emptyDirGuards = requireNonEmpty(dir, files);
    if (!emptyDirGuards.isEmpty()) {
      return emptyDirGuards.stream();
    }
    return files.stream().map(file -> DynamicTest.dynamicTest(relativeName(file),
        () -> assertParsesCleanly(file)));
  }

  @TestFactory
  Stream<DynamicTest> localPassedFixturesRoundTripSemantically() throws IOException {
    Path dir = FIXTURES_ROOT.resolve("passed");
    List<Path> files = new ArrayList<>();
    collectVcfFiles(dir, files);
    List<DynamicTest> emptyDirGuards = requireNonEmpty(dir, files);
    if (!emptyDirGuards.isEmpty()) {
      return emptyDirGuards.stream();
    }
    return files.stream().map(file -> DynamicTest.dynamicTest(relativeName(file),
        () -> checkRoundTripSemantic(file)));
  }

  @TestFactory
  Stream<DynamicTest> localPassedFixturesRoundTripByteIdentical() throws IOException {
    Path dir = FIXTURES_ROOT.resolve("passed");
    List<Path> files = new ArrayList<>();
    collectVcfFiles(dir, files);
    List<DynamicTest> emptyDirGuards = requireNonEmpty(dir, files);
    if (!emptyDirGuards.isEmpty()) {
      return emptyDirGuards.stream();
    }
    return files.stream().map(file -> DynamicTest.dynamicTest(relativeName(file),
        () -> checkRoundTripByteDiff(file)));
  }

  @TestFactory
  Stream<DynamicTest> localFailedFixturesMatchExpectation() throws IOException {
    Path dir = FIXTURES_ROOT.resolve("failed");
    List<Path> files = new ArrayList<>();
    collectVcfFiles(dir, files);
    List<DynamicTest> emptyDirGuards = requireNonEmpty(dir, files);
    if (!emptyDirGuards.isEmpty()) {
      return emptyDirGuards.stream();
    }
    return files.stream().map(file -> DynamicTest.dynamicTest(relativeName(file),
        () -> assertMatchesExpectation(file)));
  }

  private static void collectVcfFiles(Path dir, List<Path> into) throws IOException {
    if (!Files.isDirectory(dir)) {
      return;
    }
    try (Stream<Path> stream = Files.list(dir)) {
      stream.filter(p -> p.toString().endsWith(".vcf")).sorted().forEach(into::add);
    }
  }

  /**
   * @return a single failing {@link DynamicTest} if {@code files} is empty, so an empty {@code passed/} or
   * {@code failed/} directory alone is caught by whichever {@code @TestFactory} checked it -- an empty {@link
   * Stream} of dynamic tests is a vacuous JUnit pass, not a failure. Empty list (not {@code null}/{@code
   * Optional}) signals "not empty, proceed as normal" so callers can {@code addAll}/check {@code isEmpty()}
   * uniformly.
   */
  private static List<DynamicTest> requireNonEmpty(Path dir, List<Path> files) {
    if (!files.isEmpty()) {
      return List.of();
    }
    String label = FIXTURES_ROOT.relativize(dir).toString();
    return List.of(DynamicTest.dynamicTest("no fixtures found in " + label, () ->
        fail("No fixtures found under " + dir)));
  }

  private static void assertHeaderWellFormed(Path file) throws IOException {
    boolean isFailed = file.toString().replace('\\', '/').contains("/failed/");
    LocalFixtureMetadata metadata = LocalFixtureMetadata.parse(file);
    List<String> lines = Files.readAllLines(file);
    assertTrue(!metadata.authorities().isEmpty(),
        relativeName(file) + " has no ##Authority= line -- every fixture must cite an external authority");
    assertAtMostOneLine(file, lines, "##CauseOfFailure=");
    assertAtMostOneLine(file, lines, "##ExpectParser=");
    assertAtMostOneLine(file, lines, "##RoundTripDivergenceReason=");
    if (isFailed) {
      assertTrue(metadata.causeOfFailure().isPresent(),
          relativeName(file) + " is a failed/ fixture but has no ##CauseOfFailure= line");
      assertTrue(metadata.expectParser().isPresent(),
          relativeName(file) + " is a failed/ fixture but has no ##ExpectParser= line");
      assertTrue(VALID_EXPECT_PARSER_VALUES.contains(metadata.expectParser().get()),
          relativeName(file) + " has ##ExpectParser=" + metadata.expectParser().get() +
              ", must be one of " + VALID_EXPECT_PARSER_VALUES);
      if ("warn".equals(metadata.expectParser().get())) {
        assertTrue(!metadata.expectWarnContains().isEmpty(),
            relativeName(file) + " has ##ExpectParser=warn but no ##ExpectWarnContains= line -- required so " +
                "a generic noise warning (see GENERIC_NOISE_WARNINGS) can't stand in for this fixture's own " +
                "expected warning");
      }
      assertTrue(metadata.roundTripDivergenceReason().isEmpty(),
          relativeName(file) + " is a failed/ fixture but has a ##RoundTripDivergenceReason= line " +
              "(a passed/-only key)");
    } else {
      assertTrue(metadata.causeOfFailure().isEmpty(),
          relativeName(file) + " is a passed/ fixture but has a ##CauseOfFailure= line");
      assertTrue(metadata.expectParser().isEmpty(),
          relativeName(file) + " is a passed/ fixture but has an ##ExpectParser= line");
    }
  }

  private static void assertAtMostOneLine(Path file, List<String> lines, String prefix) {
    long count = lines.stream().filter(line -> line.startsWith(prefix)).count();
    assertTrue(count <= 1,
        relativeName(file) + " has " + count + " " + prefix + " lines, expected at most 1");
  }

  private static void assertParsesCleanly(Path file) throws IOException {
    LocalFixtureMetadata metadata = LocalFixtureMetadata.parse(file);
    Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try (VcfParser parser = new VcfParser.Builder().fromFile(file).parseWith((md, record) -> { }).build()) {
      parser.parse();
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    for (String expected : metadata.expectWarnContains()) {
      assertTrue(appender.anyMessageContains(expected),
          relativeName(file) + ": expected a warning containing \"" + expected + "\", got none matching");
    }
  }

  private static void checkRoundTripSemantic(Path file) throws IOException {
    WriterRoundTrip.ParsedFile before = WriterRoundTrip.parseFile(file);
    String written = WriterRoundTrip.write(before);
    WriterRoundTrip.ParsedFile after = WriterRoundTrip.parseText(written);
    Optional<String> diff = WriterRoundTrip.semanticDiff(before, after);
    assertTrue(diff.isEmpty(), relativeName(file) + " (semantic round trip): " + diff.orElse(""));
  }

  private static void checkRoundTripByteDiff(Path file) throws IOException {
    LocalFixtureMetadata metadata = LocalFixtureMetadata.parse(file);
    if (metadata.roundTripDivergenceReason().isPresent()) {
      // deliberately not byte-identical -- see the fixture's own ##RoundTripDivergenceReason=. Reports as
      // SKIPPED with the reason visible, rather than silently PASSED, per the design spec's requirement that
      // a skip here be visible.
      Assumptions.abort(metadata.roundTripDivergenceReason().get());
    }
    String original = Files.readString(file);
    WriterRoundTrip.ParsedFile before = WriterRoundTrip.parseFile(file);
    String written = WriterRoundTrip.write(before);
    Optional<String> diff = WriterRoundTrip.byteDiff(original, written);
    assertTrue(diff.isEmpty(), relativeName(file) + " (byte-identical round trip): " + diff.orElse(""));
  }

  private static void assertMatchesExpectation(Path file) throws IOException {
    LocalFixtureMetadata metadata = LocalFixtureMetadata.parse(file);
    String expectParser = metadata.expectParser().orElseThrow(() ->
        new IllegalStateException(relativeName(file) + " has no ##ExpectParser= -- should have been caught " +
            "by localFixtureHeadersAreWellFormed"));
    Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    boolean threw = false;
    try {
      try (VcfParser parser = new VcfParser.Builder().fromFile(file).parseWith((md, record) -> { }).build()) {
        parser.parse();
      } catch (VcfFormatException e) {
        threw = true;
      }
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    boolean warned = appender.hasWarnOrHigherExcluding(GENERIC_NOISE_WARNINGS);
    switch (expectParser) {
      case "throw" -> assertTrue(threw, relativeName(file) + ": expected a VcfFormatException, none thrown");
      case "warn" -> {
        assertFalse(threw, relativeName(file) + ": expected a warning, but parsing threw instead");
        assertTrue(warned, relativeName(file) + ": expected a warning, none logged");
      }
      case "silent" -> {
        assertFalse(threw, relativeName(file) + ": expected silent acceptance, but parsing threw");
        assertFalse(warned, relativeName(file) + ": expected silent acceptance, but a warning was logged");
      }
      default -> fail(relativeName(file) + " has unrecognized ##ExpectParser=" + expectParser);
    }
    for (String expected : metadata.expectWarnContains()) {
      assertTrue(appender.anyMessageContains(expected),
          relativeName(file) + ": expected a warning containing \"" + expected + "\", got none matching");
    }
  }

  private static String relativeName(Path file) {
    return FIXTURES_ROOT.getParent().relativize(file).toString().replace('\\', '/');
  }
}
