package org.pharmgkb.parser.vcf;

import java.io.Closeable;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import com.google.common.base.Preconditions;
import org.jspecify.annotations.Nullable;


/**
 * Applies a {@link VcfTransformation} to a VCF file.
 * This is a streaming {@link VcfLineParser} that applies a single transformation and writes the transformed VCF.
 * <p>
 * {@link #isValid()} delegates directly to the underlying {@link VcfWriter}'s own {@link VcfWriter#isValid()} --
 * once a write failure or a {@link VcfWriter#close()} call permanently invalidates the writer (see
 * {@link VcfWriter}'s class Javadoc), it invalidates this instance too: {@link #parseMetadata} and
 * {@link #parseLine} both throw {@link IllegalStateException}
 * instead of doing anything further. A {@link VcfTransformation} exception or a {@link VcfWriter}
 * {@link VcfFormatException} content rejection, by contrast, does not invalidate the writer -- nothing corrupting
 * was written, so {@link #parseMetadata}/{@link #parseLine} can be called again (for a {@link VcfTransformation}
 * failure) or simply continue processing the next record (for a rejected record) without issue. A retry made
 * this way, before the header has actually been written, re-invokes {@link VcfTransformation#transformMetadata}
 * on the same metadata object -- implementations that mutate metadata non-idempotently (e.g. adding a raw
 * property to a multi-valued key, which appends rather than replaces) must account for being invoked more than
 * once on the same object. A transformation that instead corrects its own prior mutation on each call (e.g.
 * removing then re-adding a declaration) is a safe pattern for this, and is exactly how this class's own test
 * suite exercises a retried, self-correcting transformation. The same hazard applies to
 * {@link VcfTransformation#transformDataLine}: if {@code m_writer.writeLine()} rejects a record and the caller
 * retries that same record, {@code transformDataLine} runs again on the same {@link VcfPosition}/sample objects,
 * so a non-idempotent mutation there (e.g. {@code position.getFilters().add(...)}, which would append) has the
 * same risk.
 * <p>
 * Example:
 * <pre>{@code
 *   VcfTransformation transformation = new VcfTransformation() {
 *       public void transformMetadata(VcfMetadata metadata) {
 *           metadata.getRawProperties().replaceValues("Test", List.of("123")); // sets ##Test=123 (replaces any prior value(s) for "Test", so a retry doesn't duplicate it)
 *       }
 *       public boolean transformDataLine(VcfMetadata metadata, VcfPosition position, List<VcfSample> sampleData) {
 *           position.setQuality(new BigDecimal("0"));
 *           return true;
 *       }
 *   };
 *   TransformingVcfLineParser.Builder builder = new TransformingVcfLineParser.Builder();
 *   builder.transformation(transformation, outputFile);
 *   try (TransformingVcfLineParser lineParser = builder.build()) {
 *       try (VcfParser parser = new VcfParser.Builder().fromFile(input.toPath()).parseWith(lineParser).build()) {
 *           parser.parse(); // prints the transformed VCF to outputFile
 *       }
 *   }
 * }
 * </pre>
 */
public class TransformingVcfLineParser implements VcfLineParser, Closeable {

  private boolean m_headerWritten;
  private final VcfTransformation m_transformation;
  private final VcfWriter m_writer;

  private TransformingVcfLineParser(VcfTransformation transformation, VcfWriter writer) {
    m_transformation = transformation;
    m_writer = writer;
  }

  /**
   * @return {@code false} once a write failure has permanently invalidated the underlying {@link VcfWriter}, or
   * once it has been closed (see its class Javadoc); {@code true} otherwise, including while a retryable
   * {@link VcfTransformation} failure is being retried.
   */
  public boolean isValid() {
    return m_writer.isValid();
  }

  @Override
  public void parseMetadata(VcfMetadata metadata) {
    checkValid();
    writeHeader(metadata);
  }

  public void parseLine(VcfMetadata metadata, VcfPosition position,
      List<VcfSample> sampleData) {
    checkValid();
    // fallback in case parseMetadata was not invoked (e.g. parseLine called directly, not through VcfParser); this
    // always happens before transformDataLine, so transformDataLine can't change the metadata output
    writeHeader(metadata);
    boolean keep = m_transformation.transformDataLine(metadata, position, sampleData);
    if (keep) {
      m_writer.writeLine(metadata, position, sampleData);
    }
  }

  private void checkValid() {
    if (!m_writer.isValid()) {
      throw new IllegalStateException(
          "This TransformingVcfLineParser is no longer usable (the underlying VcfWriter is no longer valid)");
    }
  }

  /**
   * Applies the transformation's metadata step and writes the header, exactly once. Either step throwing
   * propagates directly, with {@link #m_headerWritten} left {@code false} -- nothing about this attempt
   * succeeded, so a later call retries the whole thing from scratch. If {@link VcfWriter#writeHeader} throws
   * because it's now invalid, {@link #checkValid()} will block all future calls anyway.
   */
  private void writeHeader(VcfMetadata metadata) {
    if (m_headerWritten) {
      return;
    }
    m_transformation.transformMetadata(metadata);
    m_writer.writeHeader(metadata);
    m_headerWritten = true;
  }

  /**
   * Delegates to the underlying {@link VcfWriter}'s {@link VcfWriter#close()}, which permanently invalidates
   * this instance too (see this class's own Javadoc): {@link #isValid()} becomes {@code false} afterward, and
   * further {@link #parseMetadata}/{@link #parseLine} calls throw {@link IllegalStateException}.
   */
  @Override
  public void close() {
    m_writer.close();
  }

  public static class Builder {

    private @Nullable VcfTransformation m_transformation;
    private @Nullable VcfWriter m_writer;

    public Builder transformation(VcfTransformation transformation, Path outputFile)
        throws IOException {
      checkNotAlreadySet();
      // must run before new VcfWriter.Builder().toFile(outputFile).build() below: that call opens a real OS
      // file handle (creating/truncating outputFile) as soon as it runs, since Java evaluates a method's
      // arguments before invoking it -- checking transformation only inside the 3-argument overload below
      // (which it still does, redundantly) is too late to prevent that open from ever happening. A null
      // transformation here would otherwise leak that handle and leave a stray empty file on disk, with
      // nothing left holding a reference to close it. Ordered after checkNotAlreadySet() (rather than
      // before) so an already-configured Builder called again with a null transformation still reports
      // "Transformation already set" -- the more specific, pre-existing misuse diagnostic -- rather than a
      // bare NullPointerException; only ordering relative to build() below actually matters for the leak.
      Preconditions.checkNotNull(transformation);
      return transformation(transformation, new VcfWriter.Builder().toFile(outputFile).build());
    }

    public Builder transformation(VcfTransformation transformation, PrintWriter writer)
        throws IOException {
      checkNotAlreadySet();
      // see the Path overload's identical comment above -- this overload doesn't open a NEW OS file handle
      // (the caller already owns and opened writer), but the wrapping VcfWriter this constructs would still
      // be silently discarded on a null transformation, for the same reason.
      Preconditions.checkNotNull(transformation);
      return transformation(transformation, new VcfWriter.Builder().toWriter(writer).build());
    }

    public Builder transformation(VcfTransformation transformation, VcfWriter writer) {
      // this looks redundant with the Path/PrintWriter overloads' own checkNotAlreadySet() calls above, but
      // isn't: this is the only guard for a caller invoking this overload directly, while the outer calls
      // exist purely to fail before a Path/PrintWriter overload opens a file (see their own comment) --
      // removing either one reopens a real gap the other doesn't cover
      checkNotAlreadySet();
      // writer is a caller-configuration object (like VcfParser.Builder's Path/Reader/VcfLineParser
      // arguments), never VCF content -- Preconditions.checkNotNull's raw NullPointerException is this
      // codebase's established convention for that category (see AGENTS.md's Public API Null-Safety Policy
      // and WONT_FIX.md's VcfParser.Builder entries). Without this, build() would succeed with a null
      // m_writer, and the failure would only surface later as a bare NullPointerException inside
      // writeHeader()/close() instead of a fail-fast diagnostic here.
      Preconditions.checkNotNull(writer);
      // transformation is the same category of caller-configuration object as writer above, and needs the
      // identical guard for a distinct reason: checkNotAlreadySet() (below) tests m_transformation != null to
      // detect a second call to this method, so a null transformation here would leave m_transformation
      // indistinguishable from "never called" -- silently defeating that guard for every subsequent call,
      // including the Path/PrintWriter overloads, which open a real VcfWriter/file handle before delegating
      // here (exactly the leaked-file-handle scenario checkNotAlreadySet()'s own comment warns against).
      Preconditions.checkNotNull(transformation);
      m_transformation = transformation;
      m_writer = writer;
      return this;
    }

    // must run before a Path/PrintWriter overload opens its VcfWriter -- a silent overwrite would otherwise leak
    // the file handle the first call already opened, since nothing would hold a reference to close it
    private void checkNotAlreadySet() {
      if (m_transformation != null) {
        throw new IllegalStateException("Transformation already set");
      }
    }

    public TransformingVcfLineParser build() {
      if (m_transformation == null) {
        throw new IllegalStateException("Must set a transformation");
      }
      return new TransformingVcfLineParser(m_transformation, m_writer);
    }
  }
}
