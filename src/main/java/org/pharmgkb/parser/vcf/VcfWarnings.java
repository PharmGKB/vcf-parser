package org.pharmgkb.parser.vcf;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.MessageFormatter;


/**
 * Consolidated dispatch for every VCF-content warning this library emits. By default, warnings go to this
 * class's own shared SLF4J logger; {@link #activate(Writer)}/{@link #openFile(Path)} redirect them, for the
 * calling thread only, to a file instead -- see {@code VcfParser.Builder#warningsToFile()} and
 * {@code VcfWriter.Builder#warningsToFile()} for the common way to use this, or call {@link #openFile(Path)}
 * directly around manual/standalone model construction.
 * <p>
 * Not for non-VCF-content log output (e.g. I/O housekeeping, progress logging) -- those stay on SLF4J directly,
 * unconditionally.
 * <p>
 * As of this class's introduction, every warning that isn't file-redirected logs under this one shared
 * {@code org.pharmgkb.parser.vcf.VcfWarnings} category, replacing what were previously ~10 distinct per-class
 * logger names (e.g. {@code org.pharmgkb.parser.vcf.model.VcfPosition}, {@code org.pharmgkb.parser.vcf.model.InfoMetadata}).
 * Logging configuration scoped to one of those old per-class names no longer matches; scope it to this class,
 * or to the {@code org.pharmgkb.parser.vcf} package as a whole, instead.
 */
public final class VcfWarnings {

  private static final Logger sf_logger = LoggerFactory.getLogger(VcfWarnings.class);
  private static final ThreadLocal<Deque<Writer>> sf_activeWriters = new ThreadLocal<>();

  private VcfWarnings() {}

  /**
   * A handle that deactivates a warnings sink when closed, without closing the sink itself -- the caller
   * retains ownership of the sink's own lifecycle. Narrows {@link AutoCloseable#close()} to declare no checked
   * exception (deactivation is only a thread-local stack pop), so a try-with-resources using this type imposes
   * no exception-handling burden on the caller.
   */
  @FunctionalInterface
  public interface Deactivator extends AutoCloseable {
    @Override
    void close();
  }

  /**
   * Pushes {@code sink} onto the calling thread's active-warnings stack. Every {@link #warn} call on this
   * thread, until the returned handle is closed, writes to {@code sink} instead of the shared SLF4J logger.
   * Nested activation is supported: closing an inner handle restores whichever sink (if any) was active before
   * it. Does not open or close {@code sink} -- the caller owns that lifecycle.
   */
  public static Deactivator activate(Writer sink) {
    Deque<Writer> stack = sf_activeWriters.get();
    if (stack == null) {
      stack = new ArrayDeque<>();
      sf_activeWriters.set(stack);
    }
    stack.push(sink);
    return () -> sf_activeWriters.get().pop();
  }

  /**
   * Opens {@code file} (creating it, or overwriting it if it already exists), activates it for the calling
   * thread via {@link #activate}, and closes it when the returned handle is closed.
   */
  public static Closeable openFile(Path file) throws IOException {
    Writer writer = Files.newBufferedWriter(file);
    Deactivator deactivator = activate(writer);
    return () -> {
      deactivator.close();
      writer.close();
    };
  }

  /**
   * Formats {@code message} with {@code args} (SLF4J {@code {}}-placeholder syntax) and either writes the
   * formatted message, alone with a trailing newline and no other decoration, to the calling thread's active
   * warnings sink if one exists, or forwards to the shared SLF4J logger otherwise.
   */
  public static void warn(String message, Object... args) {
    Deque<Writer> stack = sf_activeWriters.get();
    Writer active = stack == null ? null : stack.peek();
    if (active == null) {
      sf_logger.warn(message, args);
      return;
    }
    String formatted = MessageFormatter.arrayFormat(message, args).getMessage();
    try {
      active.write(formatted);
      active.write('\n');
      active.flush();
    } catch (IOException e) {
      throw new UncheckedIOException("Error writing to the active VCF warnings file", e);
    }
  }
}
