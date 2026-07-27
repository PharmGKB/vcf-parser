package org.pharmgkb.parser.vcf;

import java.io.Closeable;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.pharmgkb.parser.vcf.model.VcfMetadata;
import org.pharmgkb.parser.vcf.model.VcfPosition;
import org.pharmgkb.parser.vcf.model.VcfSample;


/**
 * Applies a {@link VcfTransformation} to a VCF file.
 * This is a streaming {@link VcfLineParser} that applies a single transformation and writes the transformed VCF.
 * <p>
 * A write failure -- an {@link java.io.UncheckedIOException} from the underlying {@link VcfWriter}, as opposed to
 * a {@link VcfFormatException} it throws for ordinary per-record content rejections (e.g. a sample-count mismatch),
 * or any exception thrown by the {@link VcfTransformation} itself -- permanently invalidates this instance: the
 * underlying writer may be left in a partially-written or otherwise inconsistent state, so no further work is
 * attempted. Call {@link #isValid()} to check without triggering an exception; once invalid, {@link #parseMetadata}
 * and {@link #parseLine} both throw {@link IllegalStateException} (with the original write failure as the cause)
 * instead of doing anything further. A {@link VcfTransformation} exception or a {@link VcfWriter}
 * {@link VcfFormatException} rejection, by contrast, does not invalidate this instance -- nothing corrupting was
 * written, so {@link #parseMetadata}/{@link #parseLine} can be called again (for a {@link VcfTransformation}
 * failure) or simply continue processing the next record (for a rejected record) without issue.
 * <p>
 * Example:
 * <pre>{@code
 *   VcfTransformation transformation = new VcfTransformation() {
 *       public void transformMetadata(VcfMetadata metadata) {
 *           metadata.getRawProperties().put("Test", "123"); // adds ##Test=123
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
  private boolean m_valid = true;
  private @Nullable Exception m_invalidatingCause;
  private final VcfTransformation m_transformation;
  private final VcfWriter m_writer;

  private TransformingVcfLineParser(VcfTransformation transformation, VcfWriter writer) {
    m_transformation = transformation;
    m_writer = writer;
  }

  /**
   * @return {@code false} once a write failure has permanently invalidated this instance (see the class Javadoc);
   * {@code true} otherwise, including while a retryable {@link VcfTransformation} failure is being retried.
   */
  public boolean isValid() {
    return m_valid;
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
      try {
        m_writer.writeLine(metadata, position, sampleData);
      } catch (UncheckedIOException e) {
        m_valid = false;
        m_invalidatingCause = e;
        throw e;
      }
    }
  }

  private void checkValid() {
    if (!m_valid) {
      throw new IllegalStateException(
          "This TransformingVcfLineParser is no longer usable after a write failure", m_invalidatingCause);
    }
  }

  /**
   * Applies the transformation's metadata step and writes the header, exactly once. A {@link VcfTransformation}
   * failure here propagates without setting {@link #m_headerWritten} -- nothing was written yet, so a later call
   * retries the whole attempt. A {@link VcfWriter} failure invalidates this instance (see the class Javadoc).
   */
  private void writeHeader(VcfMetadata metadata) {
    if (m_headerWritten) {
      return;
    }
    m_transformation.transformMetadata(metadata);
    m_headerWritten = true;
    try {
      m_writer.writeHeader(metadata);
    } catch (UncheckedIOException e) {
      m_valid = false;
      m_invalidatingCause = e;
      throw e;
    }
  }

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
      return transformation(transformation, new VcfWriter.Builder().toFile(outputFile).build());
    }

    public Builder transformation(VcfTransformation transformation, PrintWriter writer)
        throws IOException {
      checkNotAlreadySet();
      return transformation(transformation, new VcfWriter.Builder().toWriter(writer).build());
    }

    public Builder transformation(VcfTransformation transformation, VcfWriter writer) {
      checkNotAlreadySet();
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
