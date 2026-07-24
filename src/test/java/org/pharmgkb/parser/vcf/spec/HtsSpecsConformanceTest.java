package org.pharmgkb.parser.vcf.spec;

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
import org.pharmgkb.parser.vcf.VcfFormatException;
import org.pharmgkb.parser.vcf.VcfParser;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;


/**
 * Parses every fixture in hts-specs' official VCF conformance suite (fetched by the htsSpecsCheckout Gradle task)
 * and checks this library's behavior against it. Tagged so it only runs under the opt-in `htsSpecsTest` Gradle
 * task, never under `test`, since it requires that fixture checkout to already exist.
 *
 * @author Douglas Myers-Turnbull
 */
@Tag("hts-specs")
class HtsSpecsConformanceTest {

  // every version this library can represent must at least parse its own passed/ fixtures without error, per
  // AGENTS.md ("The library accepts VCF 4.x input from before and after the target version")
  private static final List<String> PASSED_VERSIONS = List.of("4.1", "4.2", "4.3", "4.5");

  // failed/ fixtures are capped at this library's spec target (VCFv4.2): a later version's "failure" can be a rule
  // that version introduced which VCFv4.2 leaves silent (e.g. VCFv4.3's explicit "duplicate keys are not allowed"
  // for INFO) -- content this library correctly, silently accepts as genuinely valid under 4.2 semantics, not
  // merely tolerated. Testing those against "throw or warn" would be a false failure, not a real bug.
  private static final List<String> FAILED_VERSIONS = List.of("4.1", "4.2");

  private static final String CAUSE_OF_FAILURE_PREFIX = "##CauseOfFailure=";

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

  private static void assertThrowsOrWarns(Path file, String causeOfFailure) throws IOException {
    Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    boolean threw = false;
    try {
      try (VcfParser parser = new VcfParser.Builder()
          .fromFile(file)
          .parseWith((metadata, position, sampleData) -> { })
          .build()) {
        parser.parse();
      } catch (VcfFormatException e) {
        threw = true;
      }
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    if (!threw) {
      assertTrue(appender.hasWarnOrHigher(), () -> relativeName(file) + " (CauseOfFailure: " + causeOfFailure +
          ") was accepted with no VcfFormatException and no logged warning -- invalid content silently accepted");
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
