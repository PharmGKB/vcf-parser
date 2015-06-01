package org.pharmgkb.parser.vcf;

import org.apache.commons.io.IOUtils;
import org.pharmgkb.parser.vcf.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.*;
import java.lang.invoke.MethodHandles;
import java.nio.file.Path;
import java.util.*;

/**
 * Writes to VCF format from a {@link VcfSample}, {@link VcfPosition VcfPositions}, and {@link VcfMetadata}.
 * For now, this class performs little validation of its own, relying on {@link VcfParser} instead. For that reason, it
 * is currently package-accessible only.
 * @author Douglas Myers-Turnbull
 * @see {@link TransformingVcfLineParser} Is a read-transform-write streamer that is publically accessible
 */
public class VcfWriter implements Closeable {

  private static final Logger sf_logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private final Path m_file;
  private final PrintWriter m_writer;
  private int m_lineNumber;

  private VcfWriter(@Nullable Path file, @Nonnull PrintWriter writer) {
    m_file = file;
    m_writer = writer;
  }

  public void writeHeader(@Nonnull VcfMetadata metadata) {

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
    StringBuilder sb = new StringBuilder("#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO");
    if (metadata.getNumSamples() > 0) {
      sb.append("\tFORMAT");
    }
    for (int i = 0; i < metadata.getNumSamples(); i++) {
      sb.append("\t").append(metadata.getSampleName(i));
    }
    printLine(sb);

    m_writer.flush();
    sf_logger.info("Wrote {} lines of header{}", m_lineNumber, (m_file == null ? "" : " to " + m_file));
  }

  public void writeLine(@Nonnull VcfMetadata metadata, @Nonnull VcfPosition position,
      @Nonnull List<VcfSample> samples) {

    StringBuilder sb = new StringBuilder();

    sb.append(position.getChromosome()).append("\t");
    sb.append(position.getPosition()).append("\t");
    addListOrElse(position.getIds(), ";", ".", sb);
    if (position.getRef().isEmpty()) {
      throw new IllegalArgumentException("No REF bases, but the column is required (on line " + m_lineNumber + ")");
    }
    addListOrElse(Arrays.asList(position.getRef()), ",", ".", sb);
    addListOrElse(position.getAltBases(), ",", ".", sb);
    addStringOrElse(position.getQuality(), ".", sb);
    addListOrElse(position.getFilters(), ";", "PASS", sb);
    addInfoOrDot(metadata, position, sb);

    position.getFilters().stream().filter(key -> !metadata.getFilters().containsKey(key)).forEach(key -> {
      sf_logger.warn("Position {}:{} has FILTER {}, but there is no FILTER metadata with that name (on line {})",
          position.getChromosome(), position.getPosition(), key, m_lineNumber);
    });

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

  private void addFormatConditionally(@Nonnull VcfPosition position, @Nonnull StringBuilder sb) {
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

  private void addSampleConditionally(@Nonnull VcfMetadata metadata, int sampleIndex,
      @Nonnull VcfPosition position, @Nonnull VcfSample sample, @Nonnull StringBuilder sb) {

    Iterator<String> keys = sample.getPropertyKeys().iterator();
    if (!keys.hasNext() && position.getFormat().isEmpty()) {
      return;
    }

    for (String key : position.getFormat()) {

      keys.next();

      if (!metadata.getFormats().containsKey(key)) {
        sf_logger.warn("Sample #{} for {}:{} contains FORMAT {}, but there is no FORMAT metadata with that name (on line {})",
            sampleIndex, position.getChromosome(), position.getPosition(), key, m_lineNumber);
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

  private void addInfoOrDot(@Nonnull VcfMetadata metadata, @Nonnull VcfPosition position, @Nonnull StringBuilder sb) {

    Iterator<String> keys = position.getInfoKeys().iterator();
    if (!keys.hasNext()) {
      sb.append(".");
    }

    while (keys.hasNext()) {
      String key = keys.next();

      List<String> values = position.getInfo(key);
      assert values != null;

      if (!metadata.getInfo().containsKey(key)) {
        sf_logger.warn("Position {}:{} contains INFO {}, but there is no INFO metadata with that name (on line {})",
            position.getChromosome(), position.getPosition(), key, m_lineNumber);
      } else {
        InfoType type = metadata.getInfo().get(key).getType();
        for (String value : values) {
          try {
            VcfUtils.convertProperty(type, value); // just test
          } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Property " + key + " is not of type " +
                type + " (on line " + m_lineNumber + ")");
          }
        }
      }

      sb.append(key);
      if (!values.isEmpty() && !(values.size() == 1 && values.get(0).isEmpty())) {
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

  private void addStringOrElse(@Nullable Object object, @Nonnull String missingValue, @Nonnull StringBuilder sb) {
    if (object == null || object.toString().isEmpty()) {
      sb.append(missingValue);
    } else {
      sb.append(object.toString());
    }
    sb.append("\t");
  }

  private void addListOrElse(@Nonnull List<String> list, @Nonnull String delimiter, @Nonnull String missingValue,
      @Nonnull StringBuilder sb) {
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

  private void printPropertyLines(@Nonnull String name, @Nonnull Collection<String> list) {
    for (String string : list) {
      printLine("##" + name + "=" + string);
    }
  }

  private void printLines(@Nonnull String name, @Nonnull Collection<? extends BaseMetadata> list) {
    for (BaseMetadata metadata : list) {
      printLine(getAllProperties(name, metadata));
    }
  }

  private String getAllProperties(@Nonnull String name, @Nonnull BaseMetadata metadata) {
    StringBuilder sb = new StringBuilder("##");
    sb.append(name).append("=<");
    int i = 0;
    for (Map.Entry<String, String> entry : metadata.getPropertiesRaw().entrySet()) {
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
    String string = line.toString();
    if (string.contains("\n")) {
      throw new RuntimeException("Something went wrong writing line #" + m_lineNumber + ": [[[" + string +
          "]]] contains more than one line");
    }
    m_writer.println(line);
    m_lineNumber++;
    if (m_lineNumber % 1000 == 0) {
      sf_logger.info("Wrote {} lines{}", m_lineNumber, (m_file == null ? "" : " to " + m_file));
    }
  }

}
