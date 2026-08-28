package org.pharmgkb.parser.vcf;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.MessageFormatter;


/**
 * Consolidated destination for every VCF-content warning this library emits. An instance either writes to a
 * caller-supplied {@link Writer} sink, or -- for {@link #DEFAULT}, or any instance after {@link #close()} has
 * run -- falls back to this class's own shared SLF4J logger.
 * <p>
 * Every model class in this package (a {@code VcfPosition}, {@code VcfSample}, {@code VcfMetadata}, or metadata
 * declaration) is handed a {@link VcfContext} at construction, and that context's
 * instance of this class is its warnings destination for the rest of its lifetime -- fixed, not
 * reassignable, and never re-pointed by anything constructed later, including a {@code VcfWriter} it is later
 * handed to. There is no ambient/thread-local dispatch and no re-claim mechanism. See {@code
 * VcfParser.Builder#warningsToFile()} and {@code VcfWriter.Builder#warningsToFile()} for the common way an
 * instance gets built and wired into a context; construct one directly (or use {@link #DEFAULT}) around
 * manual/standalone model construction.
 * <p>
 * This fixed-at-construction rule has a real consequence for a parse-then-write flow: a {@code VcfPosition}/
 * {@code VcfSample}/declaration parsed under one {@code VcfContext} (e.g. a {@code VcfParser} with its own
 * {@code warningsToFile()}) and then handed to a {@code VcfWriter}'s {@code writeHeader}/{@code writeLine} keeps
 * warning to that original context's sink -- never to the writer's own {@code warningsToFile()} destination,
 * even while the writer is actively processing it. Only diagnostics the writer generates itself (e.g. an
 * undeclared INFO/FORMAT key, or a header/sample mismatch) reach the writer's own destination. A caller who
 * needs every warning collected in one place must build every model object under the same context up front,
 * rather than relying on the writer to collect them.
 * <p>
 * Not for non-VCF-content log output (e.g. I/O housekeeping, progress logging) -- those stay on SLF4J directly,
 * unconditionally.
 */
public final class VcfWarnings implements Closeable {

  private static final Logger sf_logger = LoggerFactory.getLogger(VcfWarnings.class);

  /** Always warns to the shared SLF4J logger. Stateless and immutable; safe to share/reference from anywhere. */
  public static final VcfWarnings DEFAULT = new VcfWarnings(null);

  // guards m_closed and every access to m_sink -- this class explicitly supports being shared across threads
  // (a VcfPosition/VcfSample built under one instance can be handed to any thread), so both concurrent warn()
  // calls (which must not interleave partial writes into one sink) and a warn() racing a close() (which must
  // never let warn() attempt to write to an already-closing/closed sink) need a common lock, not just a
  // volatile flag -- a plain volatile boolean makes each individual read/write atomic, but not the
  // check-then-write sequence across the two.
  private final Object m_lock = new Object();
  private final @Nullable Writer m_sink;
  private boolean m_closed;
  // see warn()'s own comment on FLUSH_INTERVAL for why this counts down instead of flushing on every call
  private int m_warningsSinceFlush;

  // matches VcfWriter's own periodic write-failure-check interval (every 1000 lines) -- the identical
  // "detect a failure promptly without paying a syscall on every single call" tradeoff, for the same reason.
  private static final int FLUSH_INTERVAL = 1000;

  /**
   * @param sink where {@link #warn} writes, or {@code null} to always use the shared SLF4J logger (matching
   * {@link #DEFAULT}'s own behavior -- {@code new VcfWarnings(null)} and {@link #DEFAULT} behave identically,
   * but {@link #DEFAULT} avoids a redundant allocation for the common case). {@link #warn} writes to this sink
   * synchronously, with no volume limit -- a slow sink (e.g. a remote or heavily-contended stream) directly
   * gates parsing/writing throughput, and a very warning-heavy file grows an in-memory sink (e.g.
   * {@link java.io.StringWriter}) without bound. Choosing a fast, appropriately-buffered sink is the caller's
   * responsibility.
   */
  public VcfWarnings(@Nullable Writer sink) {
    m_sink = sink;
  }

  /**
   * Opens {@code file} (creating it, or overwriting it if it already exists) and returns an instance that
   * writes to it until {@link #close()} is called.
   */
  public static VcfWarnings openFile(Path file) throws IOException {
    return new VcfWarnings(Files.newBufferedWriter(file));
  }

  /**
   * Formats {@code message} with {@code args} (SLF4J {@code {}}-placeholder syntax) and writes the formatted
   * message, alone with a trailing newline and no other decoration, to this instance's sink -- or forwards to
   * the shared SLF4J logger if this instance has no sink ({@link #DEFAULT}), or if {@link #close()} has already
   * run on this instance. The close-fallback exists because a {@code VcfPosition}/{@code VcfSample} built while
   * this instance was active can outlive the {@code parse()}/write call that built it; a lazy getter firing
   * after this instance's file has since been closed must not crash, it must behave as if no sink were ever
   * configured. Safe to call from multiple threads concurrently, including concurrently with {@link #close()}.
   */
  public void warn(String message, Object... args) {
    synchronized (m_lock) {
      if (m_sink == null || m_closed) {
        sf_logger.warn(message, args);
        return;
      }
      String formatted = MessageFormatter.arrayFormat(message, args).getMessage();
      try {
        m_sink.write(formatted);
        m_sink.write('\n');
        // Flushed periodically (every FLUSH_INTERVAL warnings), not on every call: openFile() wraps its file
        // in a BufferedWriter, whose default ~8KB buffer would otherwise silently absorb every write() call
        // below that threshold with no error at all, even when the underlying file is already unwritable
        // (e.g. disk full, permission revoked, fd closed) -- contradicting this method's own documented
        // "writes to this sink synchronously" contract and the callers (VcfParser/VcfWriter) that depend on a
        // sink failure surfacing here, not being silently lost until some later, unrelated write() call
        // happens to cross the buffer boundary or close() (which only logs, never rethrows). Flushing on
        // EVERY call (as an earlier fix did) closes that gap completely but costs one write(2) syscall per
        // warning -- measured at a ~10x throughput collapse for a file with many violations, 16M warnings
        // producing a 1.84 GB byte-identical log. Matching VcfWriter's own periodic (every-1000-line)
        // write-failure check instead keeps detection prompt without paying that cost on every single call;
        // close() (via the underlying Writer's own close() contract) still always flushes whatever remains
        // buffered, so nothing is silently lost at end of parse/write regardless of where the counter sits.
        if (++m_warningsSinceFlush >= FLUSH_INTERVAL) {
          m_sink.flush();
          m_warningsSinceFlush = 0;
        }
      } catch (IOException e) {
        throw new UncheckedIOException("Error writing to the active VCF warnings file", e);
      }
    }
  }

  /**
   * Closes this instance's sink, if it has one. Idempotent -- a second call is a complete no-op and never
   * re-attempts closing the sink, regardless of whether the first call's own close succeeded. Calling this on
   * {@link #DEFAULT} (or any instance constructed with a {@code null} sink) is also a no-op -- there is nothing
   * to close, and the instance remains fully usable afterward. Declares no checked exception: a close-time
   * {@link IOException} from the underlying sink is caught and logged (via the shared SLF4J logger directly,
   * not {@link #warn} -- this is I/O housekeeping, not VCF-content), matching how this same failure was already
   * handled before this class existed. Safe to call concurrently with {@link #warn}.
   */
  @Override
  public void close() {
    synchronized (m_lock) {
      if (m_sink != null && !m_closed) {
        m_closed = true;
        try {
          m_sink.close();
        } catch (IOException e) {
          sf_logger.warn("Error closing VCF warnings file", e);
        }
      }
    }
  }
}
