package org.pharmgkb.parser.vcf;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pharmgkb.parser.vcf.spec.WarnCapturingAppender;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;


class VcfWarningsTest {

  @Test
  void testWarnWithNoSinkGoesToSharedLogger() {
    Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      VcfWarnings.DEFAULT.warn("test message {}", "x");
      assertTrue(appender.anyMessageContains("test message x"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void testWarnWithSinkWritesFormattedMessageWithNewline() {
    StringWriter sink = new StringWriter();
    VcfWarnings warnings = new VcfWarnings(sink);
    warnings.warn("test message {}", "x");
    assertEquals("test message x\n", sink.toString());
  }

  @Test
  void testDefaultIsStatelessAndReusableAcrossCalls() {
    // DEFAULT must never accumulate per-call or per-thread state -- it's a shared static constant
    Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      VcfWarnings.DEFAULT.warn("first {}", "a");
      VcfWarnings.DEFAULT.warn("second {}", "b");
      assertTrue(appender.anyMessageContains("first a"));
      assertTrue(appender.anyMessageContains("second b"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void testOpenFileWritesAndClosesFile(@TempDir Path tempDir) throws IOException {
    Path file = tempDir.resolve("warnings.log");
    VcfWarnings warnings = VcfWarnings.openFile(file);
    warnings.warn("hello {}", "world");
    warnings.close();
    List<String> lines = Files.readAllLines(file);
    assertEquals(List.of("hello world"), lines);
  }

  @Test
  void testWarnAfterCloseFallsBackToSharedLoggerInsteadOfThrowing(@TempDir Path tempDir) throws IOException {
    // regression test: a VcfPosition/VcfSample built under a warningsToFile()-backed VcfWarnings can outlive the
    // parse() call that built it (that's the whole point of parsing); a lazy getter (getQuality(), info()) firing
    // after that file has been closed must not throw UncheckedIOException -- it must fall back to SLF4J, matching
    // the old ThreadLocal design's accidental safety here (deactivation made warn() fall through to SLF4J once
    // parse() returned)
    Path file = tempDir.resolve("warnings.log");
    VcfWarnings warnings = VcfWarnings.openFile(file);
    warnings.close();
    Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      assertDoesNotThrow(() -> warnings.warn("after close {}", "x"));
      assertTrue(appender.anyMessageContains("after close x"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void testCloseIsIdempotent(@TempDir Path tempDir) throws IOException {
    Path file = tempDir.resolve("warnings.log");
    VcfWarnings warnings = VcfWarnings.openFile(file);
    warnings.close();
    assertDoesNotThrow(warnings::close);
  }

  @Test
  void testCloseOnDefaultIsNoOp() {
    assertDoesNotThrow(VcfWarnings.DEFAULT::close);
    // DEFAULT must remain usable after close() -- it's a shared static constant, never really "closed"
    assertDoesNotThrow(() -> VcfWarnings.DEFAULT.warn("still works"));
  }

  @Test
  void testWriteFailureDuringWarnThrowsUncheckedIOException() throws IOException {
    Writer failingSink = new Writer() {
      @Override public void write(char[] cbuf, int off, int len) throws IOException {
        throw new IOException("disk full");
      }
      @Override public void flush() {}
      @Override public void close() {}
    };
    VcfWarnings warnings = new VcfWarnings(failingSink);
    assertThrows(UncheckedIOException.class, () -> warnings.warn("test"));
  }

  @Test
  void testCloseSwallowsAndLogsUnderlyingCloseFailureInsteadOfThrowing() {
    // regression test for finding #3 of this plan's external review: close() must not declare a checked
    // exception a caller (e.g. VcfParser's finally block, VcfWriter.closeWarningsWriterQuietly()) would have to
    // handle -- a close-time IOException from the underlying sink is caught and logged, matching VcfWriter's
    // own pre-existing precedent for this exact failure mode. Calling close() again afterward must also not
    // re-attempt closing the sink (true idempotency, not just "happens to not throw a second time because the
    // underlying Writer tolerates a double close").
    java.util.concurrent.atomic.AtomicInteger closeAttempts = new java.util.concurrent.atomic.AtomicInteger();
    Writer failingSink = new Writer() {
      @Override public void write(char[] cbuf, int off, int len) {}
      @Override public void flush() {}
      @Override public void close() throws IOException {
        closeAttempts.incrementAndGet();
        throw new IOException("disk full");
      }
    };
    VcfWarnings warnings = new VcfWarnings(failingSink);
    assertDoesNotThrow(warnings::close);
    assertDoesNotThrow(warnings::close);
    assertEquals(1, closeAttempts.get());
  }

  @Test
  void testConcurrentWarnCallsDoNotInterleaveOutput() throws InterruptedException {
    // regression test for finding #4 of this plan's external review: this design explicitly supports a
    // VcfWarnings instance being shared across threads (see VcfParserTest's cross-thread test) -- multiple
    // threads calling warn() concurrently on a shared Writer sink must not interleave partial writes into
    // garbled/corrupted lines
    StringWriter sink = new StringWriter();
    VcfWarnings warnings = new VcfWarnings(sink);
    int threadCount = 8;
    int perThread = 200;
    ExecutorService pool = Executors.newFixedThreadPool(threadCount);
    List<Future<?>> futures = new ArrayList<>();
    for (int t = 0; t < threadCount; t++) {
      futures.add(pool.submit(() -> {
        for (int i = 0; i < perThread; i++) {
          warnings.warn("message");
        }
      }));
    }
    for (Future<?> f : futures) {
      assertDoesNotThrow(() -> f.get());
    }
    pool.shutdown();
    String[] lines = sink.toString().split("\n");
    assertEquals(threadCount * perThread, lines.length);
    for (String line : lines) {
      assertEquals("message", line);
    }
  }

  @Test
  void testCloseNeverRunsWhileAnInProgressWarnIsStillWriting() throws Exception {
    // regression test for finding #4 of this plan's first review round (synchronization), rewritten per
    // finding #3 of the second round: the original version used StringWriter, whose close() is a documented
    // no-op that never makes a later write() fail -- it could pass even with no synchronization at all, since
    // there was nothing for an unsynchronized implementation to actually get wrong. This version uses a sink
    // whose write() blocks (via a latch) until told to proceed, and whose close() records whether a write was
    // in progress when it ran -- so it directly proves warn() and close() are mutually exclusive, not just that
    // neither happened to throw.
    // Fifth-round correction (finding #1): the fourth-round version removed the timing-based
    // Thread.sleep(200)/isDone() check (correctly, per round 3's finding #4) but didn't replace it with
    // anything that actually forces closeTask to attempt close() before the write is released -- without that,
    // an unsynchronized implementation could still pass by pure luck, if the scheduler happens to run closeTask
    // only after releaseWrite fires and the write has already finished (no contention ever attempted, so
    // overlapDetected never had a chance to fire either way). Fixed with a "closeAttempted" latch that
    // closeTask signals immediately before calling warnings.close(): the main thread waits on it before
    // releasing the write, guaranteeing the close attempt has already begun (and, under correct synchronization,
    // is therefore already blocked on the lock the in-progress warn() call holds) at the moment the write is
    // released -- not just hoped-for by scheduling luck.
    java.util.concurrent.CountDownLatch writeStarted = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch releaseWrite = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch closeAttempted = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicBoolean writeInProgress = new java.util.concurrent.atomic.AtomicBoolean(false);
    java.util.concurrent.atomic.AtomicBoolean overlapDetected = new java.util.concurrent.atomic.AtomicBoolean(false);
    Writer sink = new Writer() {
      @Override public void write(char[] cbuf, int off, int len) {
        writeInProgress.set(true);
        writeStarted.countDown();
        try {
          if (!releaseWrite.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new AssertionError("releaseWrite was never signaled within 5 seconds");
          }
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
        writeInProgress.set(false);
      }
      @Override public void flush() {}
      @Override public void close() {
        if (writeInProgress.get()) {
          overlapDetected.set(true);
        }
      }
    };
    VcfWarnings warnings = new VcfWarnings(sink);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> warnTask = pool.submit(() -> warnings.warn("message"));
      assertTrue(writeStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
      Future<?> closeTask = pool.submit(() -> {
        closeAttempted.countDown();
        warnings.close();
      });
      // wait for closeTask to signal it's about to call close() -- this is the deterministic handshake finding
      // #1 required; only once we know the close attempt has begun do we release the write, guaranteeing a
      // correct implementation's lock forces closeTask to actually block here, not just happen to run later
      assertTrue(closeAttempted.await(5, java.util.concurrent.TimeUnit.SECONDS));
      releaseWrite.countDown();
      assertDoesNotThrow(() -> warnTask.get(5, java.util.concurrent.TimeUnit.SECONDS));
      assertDoesNotThrow(() -> closeTask.get(5, java.util.concurrent.TimeUnit.SECONDS));
    } finally {
      pool.shutdown();
    }
    assertFalse(overlapDetected.get(), "close() must never run while a warn() call is still writing");
  }
}
