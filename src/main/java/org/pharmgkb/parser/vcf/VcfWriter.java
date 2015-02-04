package org.pharmgkb.parser.vcf;

import org.apache.commons.io.IOUtils;
import org.pharmgkb.parser.vcf.model.*;

import javax.annotation.Nonnull;
import java.io.*;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Writes to VCF format from a {@link VcfSample}, {@link VcfPosition VcfPositions}, and {@link VcfMetadata}.
 * For now, this class performs little validation of its own, relying on {@link VcfParser} instead. For that reason, it
 * is currently package-accessible only.
 * @author Douglas Myers-Turnbull
 * @see {@link TransformingVcfLineParser} Is a read-transform-write streamer that is publically accessible
 */
class VcfWriter implements Closeable, AutoCloseable {

  private final Path m_file;
  private final PrintWriter m_writer;

  private int m_lineNumber;

  private VcfWriter(Path file, PrintWriter writer) {
    m_file = file;
    m_writer = writer;
  }

  public void writeHeader(VcfMetadata metadata) {

    // file format
    printLine("##fileformat=" + metadata.getFileFormat());

    // metadata, in order from spec
    printLines("INFO", metadata.getInfo().values());
    printLines("FILTER", metadata.getFilters().values());
    printLines("FORMAT", metadata.getFormats().values());
    printLines("ALT", metadata.getAlts().values());
    printLines("contig", metadata.getContigs().values());
    printLines("SAMPLE", metadata.getSamples().values());
    printLines("PEDIGREE", metadata.getPedigrees());

    for (String key : metadata.getRawPropertyKeys()) {
      printPropertyLines(key, metadata.getRawValuesOfProperty(key));
    }

    // header line
    StringBuilder sb = new StringBuilder("#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT");
    for (IdDescriptionMetadata sample : metadata.getSamples().values()) {
      sb.append("\t").append(sample.getId());
    }
    printLine(sb);

    m_writer.flush();
  }

  public void writeLine(VcfMetadata metadata, VcfPosition position, List<VcfSample> samples) {

    StringBuilder sb = new StringBuilder();

    sb.append(position.getChromosome()).append("\t");
    sb.append(position.getPosition()).append("\t");
    addListOrElse(position.getIds(), ";", ".", sb);
    if (position.getRefBases().isEmpty()) {
      throw new IllegalArgumentException("No REF bases, but the column is required (on line " + m_lineNumber + ")");
    }
    addListOrElse(position.getRefBases(), ",", ".", sb);
    addListOrElse(position.getAltBases(), ",", ".", sb);
    addStringOrElse(position.getQuality(), ".", sb);
    addListOrElse(position.getFilters(), ";", "PASS", sb);
    addInfoOrDot(metadata, position, sb);

    for (String key : position.getFilters()) {
      if (!metadata.getFilters().containsKey(key)) {
        throw new IllegalArgumentException("Position " + position.getChromosome() + " " + position.getPosition() +
            " has FILTER " + key + ", but there is no FILTER metadata with that name (on line " + m_lineNumber + ")");
      }
    }

    if (samples.size() > metadata.getNumSamples()) {
      throw new IllegalArgumentException("Position " + position.getChromosome() + " " + position.getPosition() +
          " contains " + samples.size() + " samples, but the metadata only declares " + metadata.getNumSamples() +
          " (on line " + m_lineNumber + ")");
    }

    // these columns can be skipped completely
    addFormatConditionally(position, sb);
    int sampleIndex = 0;
    for (VcfSample sample : samples) {
      addSampleConditionally(metadata, sampleIndex, position, sample, sb);
      sampleIndex++;
    }

    String line = sb.toString();
    if (line.endsWith("\t")) line = line.substring(0, line.length() - 1);
    printLine(line);
    m_writer.flush();
  }

  @Override
  public void close() {
    IOUtils.closeQuietly(m_writer);
  }

  private void addFormatConditionally(VcfPosition position, StringBuilder sb) {
    Iterator<String> formats = position.getFormat().iterator();
    if (!formats.hasNext()) {
      return;
    }
    while (formats.hasNext()) {
      sb.append(formats.next());
      if (formats.hasNext()) {
        sb.append(":");
      }
    }
    sb.append("\t");
  }

  private void addSampleConditionally(VcfMetadata metadata, int sampleIndex, VcfPosition position, VcfSample sample, StringBuilder sb) {

    Iterator<String> keys = sample.getPropertyKeys().iterator();
    if (!keys.hasNext() && position.getFormat().isEmpty()) {
      return;
    }

    for (String key : position.getFormat()) {

      keys.next();

      if (!metadata.getFormats().containsKey(key)) {
        throw new IllegalArgumentException("Sample #" + sampleIndex + " " + position.getPosition() +
            " contains FORMAT " + key + ", but there is no FORMAT metadata with that name (on line " + m_lineNumber + ")");
      }

      if (!sample.containsProperty(key)) {
        throw new IllegalArgumentException("Sample #" + sampleIndex + " is missing property " + key +
            " (on line " + m_lineNumber + ")");
      }

      String value = sample.getProperty(key);

      FormatType type = metadata.getFormats().get(key).getType();
      try {
        VcfUtils.convertProperty(type, value);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("Property " + key + " for sample #" + sampleIndex + " is not of type " +
            type + " (on line " + m_lineNumber + ")");
      }

      sb.append(value);
      if (keys.hasNext()) {
        sb.append(":");
      }
    }

    // now make sure the sample doesn't contain extra keys
    for (String key : sample.getPropertyKeys()) {
      if (!position.getFormat().contains(key)) {
        throw new IllegalArgumentException("Sample #" + sampleIndex + " contains extra property " + key +
            " (on line " + m_lineNumber + ")");
      }
    }
    sb.append("\t");
  }

  @SuppressWarnings("ConstantConditions")
  private void addInfoOrDot(VcfMetadata metadata, VcfPosition position, StringBuilder sb) {

    Iterator<String> keys = position.getInfoKeys().iterator();
    if (!keys.hasNext()) {
      sb.append(".");
    }

    while (keys.hasNext()) {
      String key = keys.next();

      if (!metadata.getInfo().containsKey(key)) {
        throw new IllegalArgumentException("Position " + position.getChromosome() + " " + position.getPosition() +
        " contains INFO " + key + ", but there is no INFO metadata with that name (on line " + m_lineNumber + ")");
      }

      List<String> values = position.getInfo(key);

      InfoType type = metadata.getInfo().get(key).getType();
      for (String value : values) {
        try {
          VcfUtils.convertProperty(type, value);
        } catch (IllegalArgumentException e) {
          throw new IllegalArgumentException("Property " + key + " is not of type " +
              type + " (on line " + m_lineNumber + ")");
        }
      }

      sb.append(key);
      if (!values.isEmpty()) {
        sb.append("=").append(values.get(0));
        for (int i = 1; i < values.size(); i++) {
          sb.append(",").append(values.get(i));
        }
      }
      if (keys.hasNext()) {
        sb.append(";");
      }
    }
    sb.append("\t");
  }

  private void addStringOrElse(String string, String missingValue, StringBuilder sb) {
    if (string == null || string.isEmpty()) {
      sb.append(missingValue);
    } else {
      sb.append(string);
    }
    sb.append("\t");
  }

  private void addListOrElse(List<String> list, String delimiter, String missingValue, StringBuilder sb) {
    if (list.isEmpty()) {
      sb.append(missingValue);
    } else {
      sb.append(list.get(0));
      for (int i = 1; i < list.size(); i++) {
        sb.append(delimiter).append(list.get(i));
      }
    }
    sb.append("\t");
  }

  private void printPropertyLines(String name, Collection<String> list) {
    for (String string : list) {
      printLine("##" + name + "=" + string);
    }
  }

  private void printLines(String name, Collection<? extends BaseMetadata> list) {
    for (BaseMetadata metadata : list) {
      printLine(getAllProperties(name, metadata));
    }
  }

  private String getAllProperties(String name, BaseMetadata metadata) {
    StringBuilder sb = new StringBuilder("##");
    sb.append(name).append("=<");
    int i = 0;
    for (Map.Entry<String, String> entry : metadata.getProperties().entrySet()) {
      if (i > 0) {
        sb.append(",");
      }
      sb.append(entry.getKey()).append("=").append(entry.getValue());
      i++;
    }
    sb.append(">");
    return sb.toString();
  }

  public static class Builder {

    private Path m_file;
    private PrintWriter m_writer;

    public Builder toFile(Path file) {
      m_file = file;
      return this;
    }

    public Builder toWriter(PrintWriter writer) {
      m_writer = writer;
      return this;
    }

    public VcfWriter build() throws IOException {
      if (m_file != null) {
        m_writer = new PrintWriter(new BufferedWriter(new FileWriter(m_file.toFile()), 65536));
      }
      if (m_writer == null) {
        throw new IllegalStateException("Must specify either file or writer");
      }
      return new VcfWriter(m_file, m_writer);
    }

  }

  private void printLine(@Nonnull Object line) {
    m_writer.println(line);
    m_lineNumber++;
  }

}
