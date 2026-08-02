package org.pharmgkb.parser.vcf.spec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;


/**
 * Test-only Logback appender that collects logged events, so a test can assert whether a warning fired during some
 * operation. This repo has no SLF4J provider bound outside tests (see build.gradle's testImplementation
 * logback-classic and src/test/resources/logback-test.xml), so this is the only way to observe a warning.
 */
public class WarnCapturingAppender extends AppenderBase<ILoggingEvent> {

  private final List<ILoggingEvent> m_events = Collections.synchronizedList(new ArrayList<>());

  @Override
  protected void append(ILoggingEvent event) {
    m_events.add(event);
  }

  public boolean hasWarnOrHigher() {
    return m_events.stream().anyMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN));
  }

  /**
   * @return true if any captured event's formatted message contains {@code substring}
   */
  public boolean anyMessageContains(String substring) {
    return m_events.stream().anyMatch(event -> event.getFormattedMessage().contains(substring));
  }

  /**
   * @return the number of captured events whose formatted message contains {@code substring}
   */
  public long countMessagesContaining(String substring) {
    return m_events.stream().filter(event -> event.getFormattedMessage().contains(substring)).count();
  }

  /**
   * @return true if any captured WARN-or-higher event's raw (pre-substitution) message is not in
   * {@code ignoredMessageTemplates} -- use this instead of {@link #hasWarnOrHigher()} when some known,
   * content-independent warning would otherwise mask whether a MORE SPECIFIC warning also fired
   */
  public boolean hasWarnOrHigherExcluding(Set<String> ignoredMessageTemplates) {
    return m_events.stream()
        .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
        .anyMatch(event -> !ignoredMessageTemplates.contains(event.getMessage()));
  }

  /**
   * @return the formatted (post-substitution) messages of every captured WARN-or-higher event not in
   * {@code ignoredMessageTemplates}, for diagnostic reporting
   */
  public List<String> getRelevantWarnOrHigherMessages(Set<String> ignoredMessageTemplates) {
    return m_events.stream()
        .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
        .filter(event -> !ignoredMessageTemplates.contains(event.getMessage()))
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }

}
