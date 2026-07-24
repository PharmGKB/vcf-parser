package org.pharmgkb.parser.vcf.spec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;


/**
 * Test-only Logback appender that collects logged events, so a test can assert whether a warning fired during some
 * operation. This repo has no SLF4J provider bound outside tests (see build.gradle's testImplementation
 * logback-classic and src/test/resources/logback-test.xml), so this is the only way to observe a warning.
 */
class WarnCapturingAppender extends AppenderBase<ILoggingEvent> {

  private final List<ILoggingEvent> m_events = Collections.synchronizedList(new ArrayList<>());

  @Override
  protected void append(ILoggingEvent event) {
    m_events.add(event);
  }

  boolean hasWarnOrHigher() {
    return m_events.stream().anyMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN));
  }
}
