package org.pharmgkb.parser.vcf;

import org.pharmgkb.parser.vcf.model.VcfMetadata;
import org.pharmgkb.parser.vcf.model.VcfPosition;
import org.pharmgkb.parser.vcf.model.VcfSample;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;

/**
 * Applies a transformation to a VCF file.
 * This is a streaming {@link VcfLineParser} that applies a {@link VcfTransformation} and writes the VCF.
 */
public class TransformingVcfLineParser implements VcfLineParser {

  private int m_lines;
  private VcfTransformation m_transformation;
  private VcfWriter m_writer;

  private TransformingVcfLineParser(@Nonnull VcfTransformation transformation, @Nonnull VcfWriter writer) {
    m_transformation = transformation;
    m_writer = writer;
  }

  public void parseLine(@Nonnull VcfMetadata metadata, @Nonnull VcfPosition position,
      @Nonnull List<VcfSample> sampleData) {
    if (m_lines == 0) {
      m_transformation.transformMetadata(metadata);
      m_writer.writeHeader(metadata);
    }
    m_transformation.transformDataLine(metadata, position, sampleData);
    m_writer.writeLine(metadata, position, sampleData);
    m_lines++;
  }

  public static class Builder {

    private VcfTransformation m_transformation;
    private VcfWriter m_writer;

    public Builder(@Nonnull VcfTransformation transformation) {
      m_transformation = transformation;
    }

    @Nonnull
    public Builder toFile(@Nonnull Path file) throws IOException {
      m_writer = new VcfWriter.Builder().toFile(file).build();
      return this;
    }

    @Nonnull
    public Builder toWriter(@Nonnull PrintWriter writer) throws IOException {
      m_writer = new VcfWriter.Builder().toWriter(writer).build();
      return this;
    }

    @Nonnull
    public Builder toWriter(@Nonnull VcfWriter writer) throws IOException {
      m_writer = writer;
      return this;
    }

    @Nonnull
    public TransformingVcfLineParser build() {
      return new TransformingVcfLineParser(m_transformation, m_writer);
    }
  }

}
