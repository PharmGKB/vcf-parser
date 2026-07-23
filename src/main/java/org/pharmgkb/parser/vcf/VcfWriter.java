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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.io.IOUtils;
import org.jspecify.annotations.Nullable;
import org.pharmgkb.parser.vcf.model.BaseMetadata;
import org.pharmgkb.parser.vcf.model.FormatMetadata;
import org.pharmgkb.parser.vcf.model.FormatType;
import org.pharmgkb.parser.vcf.model.InfoMetadata;
import org.pharmgkb.parser.vcf.model.InfoType;
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
  private final boolean m_validateBeforeWrite;
  private int m_lineNumber;
  private boolean m_headerWritten;
  private @Nullable VcfMetadata m_headerMetadata;

  private VcfWriter(@Nullable Path file, PrintWriter writer, boolean validateBeforeWrite) {
    m_file = file;
    m_writer = writer;
    m_validateBeforeWrite = validateBeforeWrite;
  }

  public void writeHeader(VcfMetadata metadata) {

    if (m_validateBeforeWrite) {
      if (m_headerWritten) {
        throw new VcfFormatException("writeHeader() was already called; a VCF file must have exactly one header");
      }
      validateMetadata(metadata);
    }

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
    m_headerWritten = true;
    m_headerMetadata = metadata;
    sf_logger.info("Wrote {} lines of header{}", m_lineNumber, (m_file == null ? "" : " to " + m_file));
  }

  /**
   * Writes a single data line.
   * <p>
   * By default, this does not fully revalidate metadata, the position, or samples, to keep the direct
   * parse-and-write path fast. If this writer was built with {@link Builder#validateBeforeWrite()}, it rejects
   * structurally invalid output and warns about detected semantic non-compliance before writing.
   */
  public void writeLine(VcfMetadata metadata, VcfPosition position,
      List<VcfSample> samples) {

    if (m_validateBeforeWrite) {
      if (!m_headerWritten) {
        throw new VcfFormatException("writeLine() was called before writeHeader(); every VCF file must have a " +
            "header before any data line");
      }
      if (metadata != m_headerMetadata) {
        throw new VcfFormatException("writeLine() was given different metadata than writeHeader() used; the " +
            "emitted header and this record would be inconsistent");
      }
      position.validate();
      validateMetadata(metadata);
      validateInfo(metadata, position);
      validateSamples(metadata, position, samples);
    }

    int numSamples = metadata.getNumSamples();
    if (numSamples == 0) {
      if (!samples.isEmpty() || !position.getFormat().isEmpty()) {
        throw new VcfFormatException("Position " + position.getChromosome() + ":" + position.getPosition() +
            " has FORMAT or sample data, but the header declares no samples");
      }
    } else if (samples.size() != numSamples) {
      throw new VcfFormatException("Position " + position.getChromosome() + ":" + position.getPosition() +
          " has " + samples.size() + " sample(s), but the header declares " + numSamples);
    } else if (position.getFormat().isEmpty()) {
      // without a FORMAT, addFormatConditionally/addSampleConditionally write nothing at all for these columns
      // (not even a missing-value placeholder), silently producing a line with fewer columns than the header
      // declares and discarding any sample data outright
      throw new VcfFormatException("Position " + position.getChromosome() + ":" + position.getPosition() +
          " has no FORMAT, but the header declares " + numSamples + " sample(s)");
    }
    if (position.getRef().isEmpty()) {
      // REF has no missing-value sentinel in the spec ("." means something else entirely for other columns); writing
      // "." here would not actually be valid VCF, just a placeholder that avoids crashing on a mutated position
      throw new VcfFormatException("Position " + position.getChromosome() + ":" + position.getPosition() +
          " has an empty REF, which the VCF spec does not allow (REF has no missing-value sentinel)");
    }

    StringBuilder sb = new StringBuilder();

    sb.append(position.getChromosome()).append("\t");
    sb.append(position.getPosition()).append("\t");
    addListOrElse(position.getIds(), ";", ".", sb);
    sb.append(position.getRef()).append("\t");
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

  private void validateMetadata(VcfMetadata metadata) {
    if (!VcfUtils.FILE_FORMAT_PATTERN.matcher(metadata.getFileFormat()).matches()) {
      throw new VcfFormatException("VCF file format must be VCF 4.x: " + metadata.getFileFormat());
    }
    validateKeyedMetadataEntries("INFO", metadata.getInfo());
    validateKeyedMetadataEntries("FORMAT", metadata.getFormats());
    validateKeyedMetadataEntries("FILTER", metadata.getFilters());
    validateKeyedMetadataEntries("ALT", metadata.getAlts());
    validateKeyedMetadataEntries("contig", metadata.getContigs());
    validateKeyedMetadataEntries("SAMPLE", metadata.getSamples());
    // unlike the six kinds above, PEDIGREE entries aren't stored in a map keyed by ID, so there's no map key to
    // compare against; duplicate IDs across entries must still be checked directly here
    Set<String> pedigreeIds = new HashSet<>();
    for (BaseMetadata pedigree : metadata.getPedigrees()) {
      pedigree.validate();
      Map<String, String> properties = pedigree.getPropertiesRaw();
      validateMetadataProperties("PEDIGREE", properties);
      String id = properties.get("ID");
      if (id != null && !pedigreeIds.add(id)) {
        throw new VcfFormatException("Duplicate ID " + id + " for PEDIGREE metadata");
      }
    }
    Set<String> sampleNames = new HashSet<>();
    for (int i = 0; i < metadata.getNumSamples(); i++) {
      String sampleName = metadata.getSampleName(i);
      VcfUtils.checkNoLineTerminator("sample name", sampleName);
      if (sampleName.isEmpty() || sampleName.contains("\t") || !sampleNames.add(sampleName)) {
        throw new VcfFormatException("Invalid or duplicate sample name in header: " + sampleName);
      }
    }
    for (String key : metadata.getRawPropertyKeys()) {
      for (String value : metadata.getRawValuesOfProperty(key)) {
        VcfUtils.checkNoLineTerminator(key, value);
      }
    }
  }

  /**
   * Validates every entry of a metadata map (INFO/FORMAT/FILTER/ALT/contig/SAMPLE), including that each entry's own
   * {@code ID} property still matches the map key it's stored under. That invariant normally holds automatically
   * (these maps are keyed by {@code getId()} when built), but {@link VcfMetadata#getInfo()} and its siblings return
   * the backing map directly, so a caller can mutate an entry's raw {@code ID} property (via
   * {@link BaseMetadata#getPropertiesRaw()}) without it being reflected in the map key. Left undetected, the header
   * would then declare a different ID than what data lines actually reference by that entry's map key, and a
   * duplicate-ID check across entries would also be unable to catch a real collision this way (see below).
   */
  private void validateKeyedMetadataEntries(String kind, Map<String, ? extends BaseMetadata> entriesByKey) {
    for (Map.Entry<String, ? extends BaseMetadata> mapEntry : entriesByKey.entrySet()) {
      String mapKey = mapEntry.getKey();
      BaseMetadata entry = mapEntry.getValue();
      entry.validate();
      Map<String, String> properties = entry.getPropertiesRaw();
      validateMetadataProperties(kind, properties);
      String id = properties.get("ID");
      // duplicate IDs across different entries can't occur once every entry's ID is confirmed to match its own
      // (necessarily unique) map key, so no separate "duplicate ID" check is needed here
      if (id != null && !mapKey.equals(id)) {
        throw new VcfFormatException(kind + " metadata is stored under key \"" + mapKey + "\" but its own ID " +
            "property is \"" + id + "\"; the header would declare a different ID than records referencing this " +
            "entry by its map key");
      }
    }
  }

  private void validateMetadataProperties(String kind, Map<String, String> properties) {
    for (Map.Entry<String, String> property : properties.entrySet()) {
      String propertyName = property.getKey();
      if (propertyName == null || propertyName.isEmpty() || propertyName.contains(",") ||
          propertyName.contains("=")) {
        throw new VcfFormatException(kind + " metadata contains an invalid property name: " + property.getKey());
      }
      String value = property.getValue();
      if (value == null) {
        throw new VcfFormatException(kind + " metadata property " + propertyName + " has a null value");
      }
      VcfUtils.checkNoLineTerminator(propertyName, value);
      if (!isQuoted(value) && (value.contains(",") || value.contains("="))) {
        throw new VcfFormatException(kind + " metadata property " + propertyName +
            " contains an unquoted structural delimiter");
      }
    }
  }

  private static boolean isQuoted(String value) {
    if (value.length() < 2 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') {
      return false;
    }
    boolean escaped = false;
    for (int i = 1; i < value.length() - 1; i++) {
      char c = value.charAt(i);
      if (c == '"' && !escaped) {
        return false;
      }
      if (c == '\\') {
        escaped = !escaped;
      } else {
        escaped = false;
      }
    }
    return !escaped;
  }

  private void validateInfo(VcfMetadata metadata, VcfPosition position) {
    for (String key : position.getInfoKeys()) {
      List<String> values = position.getInfo(key);
      InfoMetadata info = metadata.getInfo().get(key);
      if (info == null) {
        sf_logger.warn("Position {}:{} contains INFO {}, but there is no INFO metadata with that name",
            position.getChromosome(), position.getPosition(), key);
      } else {
        validateInfoValue(info, values, key, position.getAltBases().size());
      }
    }
  }

  private void validateSamples(VcfMetadata metadata, VcfPosition position, List<VcfSample> samples) {
    List<String> formatKeys = position.getFormat();
    for (int sampleIndex = 0; sampleIndex < samples.size(); sampleIndex++) {
      VcfSample sample = samples.get(sampleIndex);
      sample.validate();
      for (String key : sample.getPropertyKeys()) {
        if (!formatKeys.contains(key)) {
          throw new VcfFormatException("Sample #" + sampleIndex + " contains property " + key +
              ", but it is not declared in FORMAT");
        }
      }
      int ploidy = getPloidy(position, sample, sampleIndex);
      for (String key : formatKeys) {
        String value = sample.getProperty(key);
        FormatMetadata format = metadata.getFormats().get(key);
        if (value == null) {
          sf_logger.warn("Sample #{} is missing property {}", sampleIndex, key);
        } else if (format == null) {
          sf_logger.warn("Sample #{} contains FORMAT {}, but there is no FORMAT metadata with that name", sampleIndex, key);
        } else {
          validateFormatValue(format, value, key, sampleIndex, position.getAltBases().size(), ploidy);
        }
      }
    }
  }

  private void validateInfoValue(InfoMetadata info, List<String> values, String key, int numAltAlleles) {
    InfoType type = info.getType();
    if (type == InfoType.Flag) {
      if (values.size() != 1 || !values.get(0).isEmpty()) {
        sf_logger.warn("INFO {} has Type=Flag but has a value", key);
      }
      return;
    }
    if (values.stream().anyMatch(String::isEmpty)) {
      sf_logger.warn("INFO {} contains an empty value", key);
    }
    String number = info.getNumber();
    warnIfWrongCardinality("INFO " + key, number, values.size(), numAltAlleles, 2);
    if (type == null) {
      return;
    }
    for (String value : values) {
      try {
        VcfUtils.convertProperty(type, value);
      } catch (VcfFormatException e) {
        sf_logger.warn("INFO {} value {} is not of type {}", key, value, type);
      }
    }
  }

  private void validateFormatValue(FormatMetadata format, String value, String key, int sampleIndex,
      int numAltAlleles, int ploidy) {
    if (value.equals(".")) {
      return;
    }
    if (!value.isEmpty()) {
      // an empty value is already reported generically below (for any FORMAT key, not just FT/PS); avoid warning
      // about it twice via checkReservedFormatConstraints's own "empty filter code" check. The returned normalized
      // value is discarded: this writer preserves raw content rather than rewriting it, consistent with how a
      // reserved list-typed FORMAT value's empty entry is also only normalized on read, not on write.
      VcfUtils.checkReservedFormatConstraints(key, value);
    }
    String[] values = key.equals("GLE") ? new String[] { value } : value.split(",", -1);
    for (String element : values) {
      if (element.isEmpty()) {
        sf_logger.warn("FORMAT {} for sample #{} contains an empty value", key, sampleIndex);
      }
    }
    String number = format.getNumber();
    warnIfWrongCardinality("FORMAT " + key + " for sample #" + sampleIndex, number, values.length, numAltAlleles,
        ploidy);
    FormatType type = format.getType();
    if (type == null) {
      return;
    }
    for (String element : values) {
      try {
        VcfUtils.convertProperty(type, element);
      } catch (VcfFormatException e) {
        sf_logger.warn("FORMAT {} for sample #{} value {} is not of type {}", key, sampleIndex, element, type);
      }
    }
  }

  private void warnIfWrongCardinality(String field, @Nullable String number, int actual, int numAltAlleles,
      int ploidy) {
    Long expected = getExpectedCardinality(number, numAltAlleles, ploidy);
    if (expected != null && expected != actual) {
      sf_logger.warn("{} has {} value(s), but Number={} requires {}", field, actual, number, expected);
    }
  }

  static @Nullable Long getExpectedCardinality(@Nullable String number, int numAltAlleles, int ploidy) {
    if (number == null || number.equals(".")) {
      return null;
    }
    if (number.equals("A")) {
      return (long) numAltAlleles;
    }
    if (number.equals("R")) {
      return (long) numAltAlleles + 1;
    }
    if (number.equals("G")) {
      Long combinations = combinationsWithRepetition(numAltAlleles + 1, ploidy);
      if (combinations == null) {
        sf_logger.warn("Number=G cardinality for {} allele(s) and ploidy {} is too large to validate", numAltAlleles + 1,
            ploidy);
      }
      return combinations;
    }
    try {
      return Long.parseLong(number);
    } catch (NumberFormatException e) {
      sf_logger.warn("Number={} is too large to validate cardinality", number);
      return null;
    }
  }

  /**
   * @return {@code null} if the result overflows a {@code long}, consistent with {@link #getExpectedCardinality}'s
   * other branches skipping validation (rather than reporting a sentinel value) when a cardinality can't be computed
   */
  private static @Nullable Long combinationsWithRepetition(int numAlleles, int ploidy) {
    int k = Math.min(ploidy, numAlleles - 1);
    long result = 1;
    for (int i = 1; i <= k; i++) {
      try {
        result = Math.multiplyExact(result, numAlleles + ploidy - 1L - k + i) / i;
      } catch (ArithmeticException e) {
        return null;
      }
    }
    return result;
  }

  private int getPloidy(VcfPosition position, VcfSample sample, int sampleIndex) {
    String gt = sample.getProperty("GT");
    if (gt == null) {
      return 2;
    }
    String[] alleles = gt.split("[/|]", -1);
    boolean valid = alleles.length > 0;
    for (String allele : alleles) {
      if (allele.equals(".")) {
        continue;
      }
      try {
        int index = Integer.parseInt(allele);
        if (index < 0 || index > position.getAltBases().size()) {
          valid = false;
        }
      } catch (NumberFormatException e) {
        valid = false;
      }
    }
    if (!valid) {
      sf_logger.warn("Sample #{} has invalid GT {}", sampleIndex, gt);
    }
    return Math.max(1, alleles.length);
  }

  private void addSampleConditionally(VcfMetadata metadata, int sampleIndex,
      VcfPosition position, VcfSample sample, StringBuilder sb) {

    List<String> formatKeys = position.getFormat();
    if (sample.getPropertyKeys().isEmpty() && formatKeys.isEmpty()) {
      return;
    }

    for (int i = 0; i < formatKeys.size(); i++) {
      String key = formatKeys.get(i);

      if (!m_validateBeforeWrite && !metadata.getFormats().containsKey(key)) {
        sf_logger.warn("Sample #{} for {}:{} contains FORMAT {}, but there is no FORMAT metadata with that name " +
                "(on line {})",
            sampleIndex, position.getChromosome(), position.getPosition(), key, m_lineNumber);
      }

      String value = sample.getProperty(key);
      if (value == null) {
        if (!m_validateBeforeWrite) {
          sf_logger.warn("Sample #{} is missing property {}" +
              " (on line {})", sampleIndex, key, m_lineNumber);
        }
        value = ".";
      }

      FormatMetadata format = metadata.getFormats().get(key);
      if (!m_validateBeforeWrite && format != null) {
        Integer number = null;
        try {
          number = Integer.parseInt(format.getNumber());
        } catch (NumberFormatException ignored) {}
        if (number != null && number == 1 && format.getType() != null) {
          try {
            VcfUtils.convertProperty(format.getType(), value);
          } catch (VcfFormatException e) {
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
    if (!m_validateBeforeWrite) {
      sample.getPropertyKeys().stream().filter(key -> !position.getFormat().contains(key)).forEach(key -> {
        sf_logger.warn("Sample #{} contains extra property {} " +
            "(on line {})", sampleIndex, key, m_lineNumber);
      });
    }
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

      if (!m_validateBeforeWrite && !metadata.getInfo().containsKey(key)) {
        sf_logger.warn("Position {}:{} contains INFO {}, but there is no INFO metadata with that name (on line {})",
            position.getChromosome(), position.getPosition(), key, m_lineNumber);
      } else if (!m_validateBeforeWrite) {
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
            } catch (VcfFormatException e) {
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
    private boolean m_validateBeforeWrite;

    public Builder toFile(Path file) {
      m_file = file;
      return this;
    }

    public Builder toWriter(PrintWriter writer) {
      m_writer = writer;
      return this;
    }

    /**
     * Enables full-output diagnostics. Before writing, the writer validates metadata, positions, INFO, FORMAT, and
     * sample values. It rejects content that would produce structurally invalid VCF and warns about detected semantic
     * non-compliance. This is off by default to keep the direct parse-and-write path fast.
     */
    public Builder validateBeforeWrite() {
      m_validateBeforeWrite = true;
      return this;
    }

    public VcfWriter build() throws IOException {
      if (m_file != null) {
        m_writer = new PrintWriter(new BufferedWriter(new FileWriter(m_file.toFile()), 65536));
      }
      if (m_writer == null) {
        throw new IllegalStateException("Must specify either file or writer");
      }
      return new VcfWriter(m_file, m_writer, m_validateBeforeWrite);
    }

  }

  private void printLine(Object line) {
    String string = line.toString();
    if (string.contains("\n") || string.contains("\r")) {
      throw new RuntimeException("Something went wrong writing line #" + m_lineNumber + ": [[[" + string +
          "]]] contains a line terminator");
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
