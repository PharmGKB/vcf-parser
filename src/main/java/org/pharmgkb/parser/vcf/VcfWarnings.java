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
 * Model classes (see {@code org.pharmgkb.parser.vcf.model}) hold a mutable reference to whichever instance is
 * currently responsible for their warnings, explicitly re-claimed via each class's own {@code setWarningsSink}
 * method -- there is no ambient/thread-local dispatch. See {@code VcfParser.Builder#warningsToFile()} and
 * {@code VcfWriter.Builder#warningsToFile()} for the common way an instance gets built and wired up; construct
 * one directly (or use {@link #DEFAULT}) around manual/standalone model construction.
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

  /**
   * @param sink where {@link #warn} writes, or {@code null} to always use the shared SLF4J logger (matching
   * {@link #DEFAULT}'s own behavior -- {@code new VcfWarnings(null)} and {@link #DEFAULT} behave identically,
   * but {@link #DEFAULT} avoids a redundant allocation for the common case)
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
