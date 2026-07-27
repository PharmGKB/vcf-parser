package org.pharmgkb.parser.vcf;

import java.io.Closeable;
import java.io.IOException;
import java.io.PrintWriter;
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
 * {@link #isValid()} delegates directly to the underlying {@link VcfWriter}'s own {@link VcfWriter#isValid()} --
 * once a write failure permanently invalidates the writer (see {@link VcfWriter}'s class Javadoc), it invalidates
 * this instance too: {@link #parseMetadata} and {@link #parseLine} both throw {@link IllegalStateException}
 * instead of doing anything further. A {@link VcfTransformation} exception or a {@link VcfWriter}
 * {@link VcfFormatException} content rejection, by contrast, does not invalidate the writer -- nothing corrupting
 * was written, so {@link #parseMetadata}/{@link #parseLine} can be called again (for a {@link VcfTransformation}
 * failure) or simply continue processing the next record (for a rejected record) without issue. A retry made
 * this way, before the header has actually been written, re-invokes {@link VcfTransformation#transformMetadata}
 * on the same metadata object -- implementations that mutate metadata non-idempotently (e.g. adding a raw
 * property to a multi-valued key, which appends rather than replaces) must account for being invoked more than
 * once on the same object. A transformation that instead corrects its own prior mutation on each call (e.g.
 * removing then re-adding a declaration) is a safe pattern for this, and is exactly how this class's own test
 * suite exercises a retried, self-correcting transformation.
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
  private final VcfTransformation m_transformation;
  private final VcfWriter m_writer;

  private TransformingVcfLineParser(VcfTransformation transformation, VcfWriter writer) {
    m_transformation = transformation;
    m_writer = writer;
  }

  /**
   * @return {@code false} once a write failure has permanently invalidated the underlying {@link VcfWriter} (see
   * its class Javadoc); {@code true} otherwise, including while a retryable {@link VcfTransformation} failure is
   * being retried.
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
      throw new IllegalStateException("This TransformingVcfLineParser is no longer usable after a write failure");
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
