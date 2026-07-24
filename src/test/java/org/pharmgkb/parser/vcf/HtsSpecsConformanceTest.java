package org.pharmgkb.parser.vcf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;


/**
 * Parses every fixture in hts-specs' official VCF conformance suite (fetched by the htsSpecsCheckout Gradle task;
 * see docs/superpowers/specs/2026-07-23-hts-specs-conformance-test-design.md) and checks this library's behavior
 * against it. Tagged so it only runs under the opt-in `htsSpecsTest` Gradle task, never under `test`, since it
 * requires that fixture checkout to already exist.
 *
 * @author Douglas Myers-Turnbull
 */
@Tag("hts-specs")
class HtsSpecsConformanceTest {

  // every version this library can represent must at least parse its own passed/ fixtures without error, per
  // AGENTS.md ("The library accepts VCF 4.x input from before and after the target version")
  private static final List<String> PASSED_VERSIONS = List.of("4.1", "4.2", "4.3", "4.5");

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

  private static void collectVcfFiles(Path dir, List<Path> into) throws IOException {
    if (!Files.isDirectory(dir)) {
      return;
    }
    try (Stream<Path> stream = Files.list(dir)) {
      stream.filter(p -> p.toString().endsWith(".vcf")).sorted().forEach(into::add);
    }
  }

  private static void assertParsesCleanly(Path file) throws IOException {
    try (VcfParser parser = new VcfParser.Builder()
        .fromFile(file)
        .parseWith((metadata, position, sampleData) -> { })
        .build()) {
      parser.parse();
    }
  }

  private static String htsSpecsDir() {
    return System.getProperty("htsSpecsDir", "build/hts-specs-checkout");
  }

  private static Path fixtureDir(String version, String kind) {
    return Path.of(htsSpecsDir(), "test", "vcf", version, kind);
  }

  private static String relativeName(Path file) {
    return Path.of(htsSpecsDir()).relativize(file).toString();
  }
}
