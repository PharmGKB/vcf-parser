package org.pharmgkb.parser.vcf;

import java.io.Closeable;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pharmgkb.parser.vcf.spec.WarnCapturingAppender;

import static org.junit.jupiter.api.Assertions.*;

public class VcfWarningsTest {

  @Test
  public void testWarnWithNoActiveSinkGoesToSlf4j() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      VcfWarnings.warn("plain {} message", "test");
      assertTrue(appender.hasWarnOrHigher());
      assertTrue(appender.anyMessageContains("plain test message"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testActivateRedirectsToWriterInsteadOfSlf4j() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    StringWriter sink = new StringWriter();
    try {
      try (VcfWarnings.Deactivator ignored = VcfWarnings.activate(sink)) {
        VcfWarnings.warn("redirected {} message", "test");
      }
      assertFalse(appender.hasWarnOrHigher());
      assertEquals("redirected test message\n", sink.toString());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testFileLineFormatIsMessageOnly() {
    StringWriter sink = new StringWriter();
    try (VcfWarnings.Deactivator ignored = VcfWarnings.activate(sink)) {
      VcfWarnings.warn("exact message with no decoration");
    }
    assertEquals("exact message with no decoration\n", sink.toString());
  }

  @Test
  public void testDeactivateRestoresPriorSinkNotSilence() {
    StringWriter outer = new StringWriter();
    StringWriter inner = new StringWriter();
    try (VcfWarnings.Deactivator outerHandle = VcfWarnings.activate(outer)) {
      VcfWarnings.warn("to outer");
      try (VcfWarnings.Deactivator innerHandle = VcfWarnings.activate(inner)) {
        VcfWarnings.warn("to inner");
      }
      VcfWarnings.warn("back to outer");
    }
    assertEquals("to outer\nback to outer\n", outer.toString());
    assertEquals("to inner\n", inner.toString());
  }

  @Test
  public void testOpenFileCreatesWritesAndClosesFile(@TempDir Path tempDir) throws IOException {
    Path file = tempDir.resolve("test.warn.log");
    try (Closeable ignored = VcfWarnings.openFile(file)) {
      VcfWarnings.warn("written to file");
    }
    assertEquals("written to file\n", Files.readString(file));
  }

  @Test
  public void testOpenFileOverwritesExistingContent(@TempDir Path tempDir) throws IOException {
    Path file = tempDir.resolve("test.warn.log");
    Files.writeString(file, "stale content that must be gone\n");
    try (Closeable ignored = VcfWarnings.openFile(file)) {
      VcfWarnings.warn("fresh content");
    }
    assertEquals("fresh content\n", Files.readString(file));
  }

  @Test
  public void testThreadLocalIsolationAcrossTwoThreads() throws Exception {
    Path threadAFile = Files.createTempFile("vcfwarnings-a", ".log");
    Path threadBFile = Files.createTempFile("vcfwarnings-b", ".log");
    try {
      CountDownLatch aReady = new CountDownLatch(1);
      CountDownLatch bReady = new CountDownLatch(1);
      CountDownLatch bothWarned = new CountDownLatch(2);
      AtomicReference<Exception> failure = new AtomicReference<>();

      Thread threadA = new Thread(() -> {
        try (Closeable ignored = VcfWarnings.openFile(threadAFile)) {
          aReady.countDown();
          bReady.await();
          VcfWarnings.warn("from thread A");
          bothWarned.countDown();
        } catch (Exception e) {
          failure.set(e);
        }
      });
      Thread threadB = new Thread(() -> {
        try (Closeable ignored = VcfWarnings.openFile(threadBFile)) {
          bReady.countDown();
          aReady.await();
          VcfWarnings.warn("from thread B");
          bothWarned.countDown();
        } catch (Exception e) {
          failure.set(e);
        }
      });
      threadA.start();
      threadB.start();
      threadA.join();
      threadB.join();
      assertNull(failure.get());
      assertEquals("from thread A\n", Files.readString(threadAFile));
      assertEquals("from thread B\n", Files.readString(threadBFile));
    } finally {
      Files.deleteIfExists(threadAFile);
      Files.deleteIfExists(threadBFile);
    }
  }
}
