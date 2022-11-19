package org.pharmgkb.parser.vcf;

import java.io.Closeable;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;
import org.pharmgkb.parser.vcf.model.VcfMetadata;
import org.pharmgkb.parser.vcf.model.VcfPosition;
import org.pharmgkb.parser.vcf.model.VcfSample;

/**
 * Applies a transformation to a VCF file.
 * This is a streaming {@link VcfLineParser} that applies a {@link VcfTransformation} and writes the VCF.
 * More generally, can apply to a single VCF stream a set of transformations and corresponding writers.
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
 *           position.setQuality("0");
 *       }
 *   };
 *   TransformingVcfLineParser.Builder builder = new TransformingVcfLineParser.Builder();
 *   builder.addTransformation(transformation1, file1);
 *   builder.addTransformation(transformation2, file2);
 *   try (TransformingVcfLineParser lineParser = builder.build()) {
 *       try (VcfParser parser = new VcfParser.Builder().fromFile(input.toPath()).parseWith(transformer).build()) {
 *           parser.parse(); // prints transformed VCFs to file1 and file2
 *       }
 *   }
 * }
 * </pre>
 */
public class TransformingVcfLineParser implements VcfLineParser, Closeable {

  private int m_lines;
  private final List<VcfTransformation> m_transformations;
  private final List<VcfWriter> m_writers;

  private TransformingVcfLineParser(@Nonnull List<VcfTransformation> transformations, @Nonnull List<VcfWriter> writer) {
    m_transformations = transformations;
    m_writers = writer;
  }

  public void parseLine(@Nonnull VcfMetadata metadata, @Nonnull VcfPosition position,
      @Nonnull List<VcfSample> sampleData) {
    for (int i = 0; i < m_transformations.size(); i++) {

      // notice that this always happens before transformDataLine gets called
      // this means that transformDataLine can't change the metadata output
      if (m_lines == 0) {
        m_transformations.get(i).transformMetadata(metadata);
        m_writers.get(i).writeHeader(metadata);
      }
      boolean keep = m_transformations.get(i).transformDataLine(metadata, position, sampleData);
      if (keep) {
        m_writers.get(i).writeLine(metadata, position, sampleData);
      }
    }
    m_lines++;
  }

  @Override
  public void close() {
    m_writers.forEach(org.pharmgkb.parser.vcf.VcfWriter::close);
  }

  public static class Builder {

    private final List<VcfTransformation> m_transformations = new ArrayList<>();
    private final List<VcfWriter> m_writers = new ArrayList<>();

    @Nonnull
    public Builder addTransformation(@Nonnull VcfTransformation transformation, @Nonnull Path outputFile)
        throws IOException {
      m_transformations.add(transformation);
      m_writers.add(new VcfWriter.Builder().toFile(outputFile).build());
      return this;
    }

    @Nonnull
    public Builder addTransformation(@Nonnull VcfTransformation transformation, @Nonnull PrintWriter writer)
        throws IOException {
      m_transformations.add(transformation);
      m_writers.add(new VcfWriter.Builder().toWriter(writer).build());
      return this;
    }

    @Nonnull
    public Builder addTransformation(@Nonnull VcfTransformation transformation, @Nonnull VcfWriter writer)
        throws IOException {
      m_transformations.add(transformation);
      m_writers.add(writer);
      return this;
    }

    @Nonnull
    public TransformingVcfLineParser build() {
      if (m_transformations.isEmpty()) {
        throw new IllegalStateException("Must add at least one transformation");
      }
      return new TransformingVcfLineParser(m_transformations, m_writers);
    }
  }

}
