package org.pharmgkb.parser.vcf;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.invoke.MethodHandles;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.apache.commons.io.IOUtils;
import org.jspecify.annotations.Nullable;
import org.pharmgkb.parser.vcf.model.BaseMetadata;
import org.pharmgkb.parser.vcf.model.FormatMetadata;
import org.pharmgkb.parser.vcf.model.InfoMetadata;
import org.pharmgkb.parser.vcf.model.VcfMetadata;
import org.pharmgkb.parser.vcf.model.VcfPosition;
import org.pharmgkb.parser.vcf.model.VcfSample;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Writes to VCF format from a {@link VcfSample}, {@link VcfPosition VcfPositions}, and {@link VcfMetadata}.
 * For now, this class performs little validation of its own, relying on {@link VcfParser} instead. For that reason, it
 * is currently package-accessible only.
 *
 * @author Douglas Myers-Turnbull
 * @see TransformingVcfLineParser TransformingVcfLineParser - a read-transform-write streamer that is publicly accessible
 */
public class VcfWriter implements Closeable {

  private static final Logger sf_logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private final @Nullable Path m_file;
  private final PrintWriter m_writer;
  private int m_lineNumber;

  private VcfWriter(@Nullable Path file, PrintWriter writer) {
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

  public void writeLine(VcfMetadata metadata, VcfPosition position,
      List<VcfSample> samples) {

    StringBuilder sb = new StringBuilder();

    sb.append(position.getChromosome()).append("\t");
    sb.append(position.getPosition()).append("\t");
    addListOrElse(position.getIds(), ";", ".", sb);
    if (position.getRef().isEmpty()) {
      sf_logger.warn("No REF bases, but the column is required (on line {})", m_lineNumber);
    }
    addListOrElse(Arrays.asList(position.getRef()), ",", ".", sb);
    addListOrElse(position.getAltBases(), ",", ".", sb);
    addStringOrElse(position.getQuality(), ".", sb);
    if (position.getFilterStatus() == VcfPosition.FilterStatus.NONE) {
      sb.append(".\t"); // filters not applied: write the missing value rather than PASS
    } else {
      addListOrElse(position.getFilters(), ";", "PASS", sb);
    }
    addInfoOrDot(metadata, position, sb);

    position.getFilters().stream().filter(key -> !metadata.getFilters().containsKey(key)).forEach(key ->
        sf_logger.warn("Position {}:{} has FILTER {}, but there is no FILTER metadata with that name (on line {})",
            position.getChromosome(), position.getPosition(), key, m_lineNumber));

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

  private void addSampleConditionally(VcfMetadata metadata, int sampleIndex,
      VcfPosition position, VcfSample sample, StringBuilder sb) {

    List<String> formatKeys = position.getFormat();
    if (sample.getPropertyKeys().isEmpty() && formatKeys.isEmpty()) {
      return;
    }

    for (int i = 0; i < formatKeys.size(); i++) {
      String key = formatKeys.get(i);

      if (!metadata.getFormats().containsKey(key)) {
        sf_logger.warn("Sample #{} for {}:{} contains FORMAT {}, but there is no FORMAT metadata with that name " +
                "(on line {})",
            sampleIndex, position.getChromosome(), position.getPosition(), key, m_lineNumber);
      }

      String value = sample.getProperty(key);
      if (value == null) {
        sf_logger.warn("Sample #{} is missing property {}" +
            " (on line {})", sampleIndex, key, m_lineNumber);
        value = ".";
      }

      FormatMetadata format = metadata.getFormats().get(key);
      if (format != null) {
        Integer number = null;
        try {
          number = Integer.parseInt(format.getNumber());
        } catch (NumberFormatException ignored) {}
        if (number != null && number == 1 && format.getType() != null) {
          try {
            VcfUtils.convertProperty(format.getType(), value);
          } catch (IllegalArgumentException e) {
            sf_logger.warn("Property {} for sample #{} is not of type {}" +
                " (on line {})", key, sampleIndex, format.getType(), m_lineNumber);
          }
        }
      }

      sb.append(value);
      if (i < formatKeys.size() - 1) {
        sb.append(":");
      }
    }

    // now make sure the sample doesn't contain extra keys
    sample.getPropertyKeys().stream().filter(key -> !position.getFormat().contains(key)).forEach(key -> {
      sf_logger.warn("Sample #{} contains extra property {} " +
          "(on line {})", sampleIndex, key, m_lineNumber);
    });
    sb.append("\t");
  }

  private void addInfoOrDot(VcfMetadata metadata, VcfPosition position, StringBuilder sb) {

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
        InfoMetadata info = metadata.getInfo().get(key);
        for (String value : values) {
          Integer number = null;
          try {
            number = Integer.parseInt(info.getNumber());
          } catch (NumberFormatException ignored) {}
          // if the number is anything but 1, it might be a list of something else, represented as a string
          // in that case, we can't compare
          if (number != null && number == 1 && info.getType() != null) {
            try {
              VcfUtils.convertProperty(info.getType(), value); // just test
            } catch (IllegalArgumentException e) {
              sf_logger.warn("Property {} is not of type {} (on line {})", key, info.getType(), m_lineNumber);
            }
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

  private void addStringOrElse(@Nullable Object object, String missingValue, StringBuilder sb) {
    if (object == null || object.toString().isEmpty()) {
      sb.append(missingValue);
    } else {
      sb.append(object.toString());
    }
    sb.append("\t");
  }

  private void addListOrElse(List<String> list, String delimiter, String missingValue,
      StringBuilder sb) {
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

  // the spec's default structured-metadata fields, in order; any other fields follow these in a deterministic order
  private static final List<String> METADATA_FIELD_ORDER =
      Arrays.asList("ID", "Number", "Type", "Description", "Source", "Version");

  private String getAllProperties(String name, BaseMetadata metadata) {
    StringBuilder sb = new StringBuilder("##");
    sb.append(name).append("=<");
    Map<String, String> props = metadata.getPropertiesRaw();
    List<String> keys = new ArrayList<>(props.keySet());
    // emit the known fields in spec order, then any remaining fields alphabetically, so output does not depend on the
    // backing map's iteration order
    keys.sort(Comparator.comparingInt((String k) -> {
      int idx = METADATA_FIELD_ORDER.indexOf(k);
      return idx >= 0 ? idx : METADATA_FIELD_ORDER.size();
    }).thenComparing(Comparator.naturalOrder()));
    int i = 0;
    for (String key : keys) {
      if (i > 0) {
        sb.append(",");
      }
      sb.append(key).append("=").append(props.get(key));
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

  private void printLine(Object line) {
    String string = line.toString();
    if (string.contains("\n")) {
      throw new RuntimeException("Something went wrong writing line #" + m_lineNumber + ": [[[" + string +
          "]]] contains more than one line");
    }
    // always terminate with LF (not the platform separator from println) so output is deterministic across platforms
    m_writer.print(string);
    m_writer.print('\n');
    m_lineNumber++;
    if (m_lineNumber % 1000 == 0) {
      sf_logger.info("Wrote {} lines{}", m_lineNumber, (m_file == null ? "" : " to " + m_file));
    }
  }

}
