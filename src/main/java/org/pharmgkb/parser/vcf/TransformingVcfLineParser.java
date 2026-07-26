package org.pharmgkb.parser.vcf;

import java.io.Closeable;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.pharmgkb.parser.vcf.model.VcfMetadata;
import org.pharmgkb.parser.vcf.model.VcfPosition;
import org.pharmgkb.parser.vcf.model.VcfSample;


/**
 * Applies a transformation to a VCF file.
 * This is a streaming {@link VcfLineParser} that applies a {@link VcfTransformation} and writes the VCF.
 * More generally, can apply to a single VCF stream a set of transformations and corresponding writers.
 * <p>
 * By default, every registered transformation is given each data line's own metadata as an independent copy
 * (so one transformation's header/metadata changes are never visible to another), but all registered
 * transformations share one mutable {@link VcfPosition}/sample-data list per data line, applied in
 * registration order -- an earlier transformation's edits to {@code position}/{@code sampleData} ARE visible to
 * later ones. Call {@link Builder#isolateTransformations()} for full per-transformation isolation of position
 * and sample data too; this costs one additional {@link VcfPosition} copy and one additional sample-list copy
 * (with a {@link VcfSample} copy per sample) for each of the {@code N-1} non-primary transformations, on every
 * data line -- for a VCF with many samples per line, this can be a meaningful cost, so it's opt-in rather than
 * the default.
 * <p>
 * Example:
 * <pre>{@code
 *   VcfTransformation transformation1 = new VcfTransformation() {
 *       public void transformMetadata(VcfMetadata metadata) {
 *           metadata.getRawProperties().put("Test", "123"); // adds ##Test=123
 *       }
 *   };
 *   VcfTransformation transformation2 = new VcfTransformation() {
 *       public void transformDataLine(VcfMetadata metadata, VcfPosition position, List<VcfSample> sampleData) {
 *           position.setQuality(new BigDecimal("0"));
 *       }
 *   };
 *   TransformingVcfLineParser.Builder builder = new TransformingVcfLineParser.Builder();
 *   builder.addTransformation(transformation1, file1);
 *   builder.addTransformation(transformation2, file2);
 *   try (TransformingVcfLineParser lineParser = builder.build()) {
 *       try (VcfParser parser = new VcfParser.Builder().fromFile(input.toPath()).parseWith(lineParser).build()) {
 *           parser.parse(); // prints transformed VCFs to file1 and file2
 *       }
 *   }
 * }
 * </pre>
 */
public class TransformingVcfLineParser implements VcfLineParser, Closeable {

  private boolean m_headerWritten;
  private final List<VcfTransformation> m_transformations;
  private final List<VcfWriter> m_writers;
  private final boolean m_isolateTransformations;
  private List<VcfMetadata> m_metadataPerPair;

  private TransformingVcfLineParser(List<VcfTransformation> transformations, List<VcfWriter> writer,
      boolean isolateTransformations) {
    m_transformations = transformations;
    m_writers = writer;
    m_isolateTransformations = isolateTransformations;
  }

  @Override
  public void parseMetadata(VcfMetadata metadata) {
    writeHeaders(metadata);
  }

  public void parseLine(VcfMetadata metadata, VcfPosition position,
      List<VcfSample> sampleData) {
    // fallback in case parseMetadata was not invoked (e.g. parseLine called directly, not through VcfParser); this
    // always happens before transformDataLine, so transformDataLine can't change the metadata output
    writeHeaders(metadata);
    int n = m_transformations.size();
    if (n == 1) {
      // fast path: nothing else to isolate against, so skip all per-pair bookkeeping entirely
      VcfMetadata metadataForPair = m_metadataPerPair.get(0);
      boolean keep = m_transformations.get(0).transformDataLine(metadataForPair, position, sampleData);
      if (keep) {
        m_writers.get(0).writeLine(metadataForPair, position, sampleData);
      }
      return;
    }
    // build every pair's position/sample-data reference BEFORE running any transformation, so a copy (when
    // isolating) always reflects the pristine, pre-transformation state -- never another pair's already-applied
    // mutation
    List<VcfPosition> positionPerPair = new ArrayList<>(n);
    List<List<VcfSample>> sampleDataPerPair = new ArrayList<>(n);
    positionPerPair.add(position);
    sampleDataPerPair.add(sampleData);
    for (int i = 1; i < n; i++) {
      positionPerPair.add(m_isolateTransformations ? new VcfPosition(position) : position);
      sampleDataPerPair.add(m_isolateTransformations ? copySamples(sampleData) : sampleData);
    }
    for (int i = 0; i < n; i++) {
      VcfMetadata metadataForPair = m_metadataPerPair.get(i);
      VcfPosition positionForPair = positionPerPair.get(i);
      List<VcfSample> sampleDataForPair = sampleDataPerPair.get(i);
      boolean keep = m_transformations.get(i).transformDataLine(metadataForPair, positionForPair, sampleDataForPair);
      if (keep) {
        m_writers.get(i).writeLine(metadataForPair, positionForPair, sampleDataForPair);
      }
    }
  }

  private static List<VcfSample> copySamples(List<VcfSample> sampleData) {
    List<VcfSample> copy = new ArrayList<>(sampleData.size());
    for (VcfSample sample : sampleData) {
      copy.add(new VcfSample(sample));
    }
    return copy;
  }

  /**
   * Applies each transformation's metadata step and writes each header, exactly once. Every transformation
   * (except the first, which uses {@code metadata} directly) gets its own independent {@link VcfMetadata} copy,
   * unconditionally -- this happens once per file, not once per line, so it's always worth the negligible cost.
   */
  private void writeHeaders(VcfMetadata metadata) {
    if (m_headerWritten) {
      return;
    }
    m_headerWritten = true;
    int n = m_transformations.size();
    m_metadataPerPair = new ArrayList<>(n);
    m_metadataPerPair.add(metadata);
    for (int i = 1; i < n; i++) {
      m_metadataPerPair.add(new VcfMetadata(metadata));
    }
    for (int i = 0; i < n; i++) {
      VcfMetadata metadataForPair = m_metadataPerPair.get(i);
      m_transformations.get(i).transformMetadata(metadataForPair);
      m_writers.get(i).writeHeader(metadataForPair);
    }
  }

  @Override
  public void close() {
    UncheckedIOException failure = null;
    for (VcfWriter writer : m_writers) {
      try {
        writer.close();
      } catch (UncheckedIOException e) {
        if (failure == null) {
          failure = e;
        } else {
          failure.addSuppressed(e);
        }
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  public static class Builder {

    private final List<VcfTransformation> m_transformations = new ArrayList<>();
    private final List<VcfWriter> m_writers = new ArrayList<>();
    private boolean m_isolateTransformations;

    public Builder addTransformation(VcfTransformation transformation, Path outputFile)
        throws IOException {
      m_transformations.add(transformation);
      m_writers.add(new VcfWriter.Builder().toFile(outputFile).build());
      return this;
    }

    public Builder addTransformation(VcfTransformation transformation, PrintWriter writer)
        throws IOException {
      m_transformations.add(transformation);
      m_writers.add(new VcfWriter.Builder().toWriter(writer).build());
      return this;
    }

    public Builder addTransformation(VcfTransformation transformation, VcfWriter writer)
        throws IOException {
      m_transformations.add(transformation);
      m_writers.add(writer);
      return this;
    }

    /**
     * Gives each registered transformation its own independent copy of the position and sample data for every
     * data line, so mutations made by one transformation are never visible to another. Off by default: without
     * this, registered transformations share one mutable {@link VcfPosition}/sample-data list per line and see
     * each other's changes, in registration order (see the class Javadoc). Metadata is always isolated
     * regardless of this setting.
     * <p>
     * Turning this on adds real cost for VCFs with many samples per line: with {@code N} registered
     * transformations, each data line requires {@code N-1} additional {@link VcfPosition} copies and {@code N-1}
     * additional sample-list copies (each of which copies every {@link VcfSample} in it).
     */
    public Builder isolateTransformations() {
      m_isolateTransformations = true;
      return this;
    }

    public TransformingVcfLineParser build() {
      if (m_transformations.isEmpty()) {
        throw new IllegalStateException("Must add at least one transformation");
      }
      return new TransformingVcfLineParser(m_transformations, m_writers, m_isolateTransformations);
    }
  }
}
