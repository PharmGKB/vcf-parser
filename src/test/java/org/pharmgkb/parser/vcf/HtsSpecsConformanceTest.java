package org.pharmgkb.parser.vcf;

import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


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
}
