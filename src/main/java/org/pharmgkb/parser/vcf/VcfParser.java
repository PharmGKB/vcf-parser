package org.pharmgkb.parser.vcf;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.google.common.base.Preconditions;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.pharmgkb.parser.vcf.model.BaseMetadata;
import org.pharmgkb.parser.vcf.model.ContigMetadata;
import org.pharmgkb.parser.vcf.model.FormatMetadata;
import org.pharmgkb.parser.vcf.model.IdDescriptionMetadata;
import org.pharmgkb.parser.vcf.model.InfoMetadata;
import org.pharmgkb.parser.vcf.model.VcfMetadata;
import org.pharmgkb.parser.vcf.model.VcfPosition;
import org.pharmgkb.parser.vcf.model.VcfSample;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * This class parses a VCF file.
 *
 * @author Mark Woon
 */
public class VcfParser implements Closeable {
  private static final Logger sf_logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static final char TAB = '\t';
  private static final char COMMA = ',';
  private static final char COLON = ':';
  private static final char SEMICOLON = ';';
  // the mandatory fixed columns, in order, that every VCF header line must start with
  private static final List<String> REQUIRED_COLUMNS =
      List.of("#CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO");
  // the mandatory fixed data fields, in order (same as REQUIRED_COLUMNS without the leading '#')
  private static final List<String> FIXED_FIELD_NAMES =
      List.of("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO");

  private final boolean m_rsidsOnly;
  private final BufferedReader m_reader;
  private @Nullable VcfMetadata m_vcfMetadata;
  private final VcfLineParser m_vcfLineParser;

  private int m_lineNumber;
  private boolean m_alreadyFinished;



  private VcfParser(BufferedReader reader, boolean rsidsOnly, VcfLineParser lineParser) {
    m_reader = reader;
    m_rsidsOnly = rsidsOnly;
    m_vcfLineParser = lineParser;
  }


  /**
   * Parses metadata only.
   * This method should be if only the metadata is needed; otherwise, {@link #parse()} is preferred.
   */
  public VcfMetadata parseMetadata() throws IOException {

    if (m_vcfMetadata != null) {
      throw new IllegalStateException("Metadata has already been parsed.");
    }
    VcfMetadata.Builder mdBuilder = new VcfMetadata.Builder();
    String line;
    boolean foundHeader = false;
    boolean seenFileFormat = false;
    boolean seenOtherMetadata = false;
    while ((line = m_reader.readLine()) != null) {
      m_lineNumber++;
      if (line.startsWith("##")) {
        if (line.startsWith("##fileformat=")) {
          if (seenFileFormat) {
            throw new VcfFormatException("Duplicate ##fileformat line", m_lineNumber);
          }
          if (seenOtherMetadata) {
            throw new VcfFormatException("##fileformat must be the first line", m_lineNumber);
          }
          seenFileFormat = true;
        } else {
          seenOtherMetadata = true;
        }
        try {
          parseMetadata(mdBuilder, line);
        } catch (VcfFormatException ex) {
          ex.addMetadata(m_lineNumber, "metadata");
          throw ex;
        } catch (RuntimeException e) {
          throw new VcfFormatException(m_lineNumber, "metadata", e);
        }
      } else if (line.startsWith("#")) {
        try {
          parseColumnInfo(mdBuilder, line);
        } catch (VcfFormatException ex) {
          ex.addMetadata(m_lineNumber, "column (# header)");
          throw ex;
        } catch (RuntimeException e) {
          throw new VcfFormatException(m_lineNumber, "column (# header)", e);
        }
        foundHeader = true;
        break;
      }
    }
    // build() validates the ##fileformat line first, so an entirely non-VCF file reports that before this
    m_vcfMetadata = mdBuilder.build();
    if (!foundHeader) {
      throw new VcfFormatException("No column header line (starting with #CHROM) was found before the end of the file",
          m_lineNumber);
    }

    // check sample lists
    if (m_vcfMetadata.getNumSamples() == m_vcfMetadata.getSamples().size()) {
      for (int i = 0; i < m_vcfMetadata.getNumSamples(); i++) {
        String sampleName = m_vcfMetadata.getSampleName(i);
        if (!m_vcfMetadata.getSamples().containsKey(sampleName)) {
          sf_logger.warn("Sample {} is missing in the metadata", sampleName);
        }
      }
    } else {
      sf_logger.warn("There are {} samples in the header but {} in the metadata", m_vcfMetadata.getNumSamples(),
          m_vcfMetadata.getSamples().size());
    }

    // deliver the metadata to the line parser once, before any data lines (this method runs at most once)
    m_vcfLineParser.parseMetadata(m_vcfMetadata);

    return m_vcfMetadata;
  }


  /**
   * Gets VCF metadata (if it has already been parsed).
   */
  public @Nullable VcfMetadata getMetadata() {
    return m_vcfMetadata;
  }


  /**
   * Parses the entire VCF file (including the metadata).
   * <p>
   * This is the preferred way to read a VCF file.
   */
  public void parse() throws IOException {
    boolean hasNext = true;
    while (hasNext) {
      hasNext = parseNextLine();
    }
  }

  /**
   * Parses just the next data line available, also reading all the metadata if it has not been read.
   * This is a specialized method; in general calling {@link #parse()} to parse the entire stream is preferred.
   *
   * @return Whether another line may be available to read; false only if and only if this is the last line available
   * @throws IllegalStateException If the stream was already fully parsed
   * @throws VcfFormatException If a VCF formatting error is found
   */
  public boolean parseNextLine() throws IOException, VcfFormatException {

    if (m_alreadyFinished) {
      // prevents user errors from causing infinite loops
      throw new IllegalStateException("Already finished reading the stream");
    }

    if (m_vcfMetadata == null) {
      parseMetadata();
    }

    String line = m_reader.readLine();
    if (line == null) {
      m_alreadyFinished = true;
      return false;
    }
    m_lineNumber++;
    if (line.startsWith("#")) {
      return true;
    }

    try {
      if (StringUtils.stripToNull(line) == null) {
        throw new VcfFormatException("Empty line", m_lineNumber);
      }
      List<String> data = toList(TAB, line);
      if (data.size() != m_vcfMetadata.getNumColumns()) {
        throw new VcfFormatException("Data line does not have expected number of columns (got " + data.size() +
            " vs. " + m_vcfMetadata.getNumColumns() + ")", m_lineNumber);
      }

      // every fixed field is mandatory; an empty field is invalid (the missing value must be ".")
      for (int i = 0; i < FIXED_FIELD_NAMES.size(); i++) {
        if (data.get(i).isEmpty()) {
          throw new VcfFormatException(FIXED_FIELD_NAMES.get(i) + " field is empty; the missing value must be '.'",
              m_lineNumber);
        }
      }

      // CHROM
      String chromosome = data.get(0);

      // POS
      long position;
      try {
        position = Long.parseLong(data.get(1));
      } catch (NumberFormatException e) {
        throw new VcfFormatException("POS '" + data.get(1) + "' is not a number");
      }

      // ID
      List<String> ids = null;
      if (!data.get(2).equals(".")) {
        if (m_rsidsOnly && !VcfUtils.RSID_PATTERN.matcher(data.get(2)).find()) {
          return true;
        }
        ids = toList(SEMICOLON, data.get(2));
      } else if (m_rsidsOnly) {
        return true;
      }

      // REF
      String ref = data.get(3);

      // ALT
      List<String> alt = null;
      if (!data.get(4).isEmpty() && !data.get(4).equals(".")) {
        alt = toList(COMMA, data.get(4));
      }

      // FILTER
      List<String> filters = null;
      if (!data.get(6).equals("PASS")) {
        filters = toList(SEMICOLON, data.get(6));
      }

      // FORMAT
      List<String> format = null;
      if (data.size() >= 9 && data.get(8) != null) {
        format = toList(COLON, data.get(8));
      }

      // QUAL and INFO are parsed lazily by VcfPosition (see setRawQuality/setRawInfo); many consumers never read them.
      VcfPosition pos = new VcfPosition(chromosome, position, ids, ref, alt,
          null, filters, null, format);
      pos.setRawQuality(data.get(5));
      pos.setRawInfo(data.get(7));
      List<VcfSample> samples = new ArrayList<>();
      for (int x = 9; x < data.size(); x++) {
        List<String> values = toList(COLON, data.get(x));
        // per the VCF spec, trailing FORMAT sub-fields may be dropped from a sample; pad any missing ones with the
        // missing value so the sample's value count matches the FORMAT key count
        if (format != null) {
          while (values.size() < format.size()) {
            values.add(".");
          }
        }
        samples.add(new VcfSample(format, values));
      }

      m_vcfLineParser.parseLine(m_vcfMetadata, pos, samples);
      return true;

    } catch (VcfFormatException ex) {
      ex.addMetadata(m_lineNumber, "data");
      throw ex;
    } catch (RuntimeException e) {
      throw new VcfFormatException(m_lineNumber, "data", e);
    }
  }

  @Override
  public void close() {
    try {
      m_reader.close();
    } catch (Exception ex) {
      sf_logger.info("Error closing reader", ex);
    }
  }


  /**
   * Parses a metadata line (starts with ##).
   */
  private void parseMetadata(VcfMetadata.Builder mdBuilder, String line) {

    int idx = line.indexOf("=");
    String propName = line.substring(2, idx).trim();
    String propValue = line.substring(idx + 1).trim();

    sf_logger.debug("{} : {}", propName, propValue);

    switch (propName) {
      case "fileformat":
        mdBuilder.setFileFormat(propValue);
        break;

      case "ALT":
      case "FILTER":
      case "INFO":
      case "FORMAT":
      case "contig":
      case "SAMPLE":
      case "PEDIGREE":
        parseMetadataProperty(mdBuilder, propName, removeAngleBrackets(propValue));
        break;

      case "assembly":
      case "pedigreeDB":
      default:
        mdBuilder.addRawProperty(propName, propValue);
    }
  }

  /**
   * Removes double quotation marks around a string.
   * @throws IllegalArgumentException If angle brackets are not present
   */
  private static String removeAngleBrackets(String string) throws VcfFormatException {
    if (string.startsWith("<") && string.endsWith(">")) {
      return string.substring(1, string.length() - 1);
    }
    throw new VcfFormatException("Angle brackets not present for: '" + string + "'");
  }

  /**
   * Converts metadata name-value pair into object.
   */
  private void parseMetadataProperty(VcfMetadata.Builder mdBuilder,
      String propName, String value) {
    Map<String, String> props = VcfUtils.extractPropertiesFromLine(value);
    switch (propName.toLowerCase()) {
      case "alt":
        mdBuilder.addAlt(new IdDescriptionMetadata(props, true));
        break;
      case "filter":
        mdBuilder.addFilter(new IdDescriptionMetadata(props, true));
        break;
      case "info":
        mdBuilder.addInfo(new InfoMetadata(props));
        break;
      case "format":
        mdBuilder.addFormat(new FormatMetadata(props));
        break;
      case "contig":
        mdBuilder.addContig(new ContigMetadata(props));
        break;
      case "sample":
        mdBuilder.addSample(new IdDescriptionMetadata(props, true));
        break;
      case "pedigree":
        mdBuilder.addPedigree(new BaseMetadata(props));
        break;
    }
  }

  /**
   * Splits {@code string} on the single character {@code delim}, matching
   * {@code Pattern.compile(String.valueOf(delim)).split(string)} with the default limit: leading and interior empty
   * fields are kept, trailing empty fields are removed, and a string containing no delimiter yields a single-element
   * list. Avoids the regex engine, which is a meaningful per-line cost when parsing large VCFs.
   */
  // package-private for differential testing against Pattern.split
  static List<String> toList(char delim, String string) {
    int idx = string.indexOf(delim);
    if (idx < 0) {
      List<String> single = new ArrayList<>(1);
      single.add(string);
      return single;
    }
    List<String> list = new ArrayList<>();
    int start = 0;
    while (idx >= 0) {
      list.add(string.substring(start, idx));
      start = idx + 1;
      idx = string.indexOf(delim, start);
    }
    list.add(string.substring(start));
    // drop trailing empty fields to match Pattern.split with the default limit of 0
    for (int i = list.size() - 1; i >= 0 && list.get(i).isEmpty(); i -= 1) {
      list.remove(i);
    }
    return list;
  }

  public int getLineNumber() {
    return m_lineNumber;
  }

  private void parseColumnInfo(VcfMetadata.Builder mdBuilder, String line) {
    List<String> cols = toList(TAB, line);
    if (cols.size() < 8) {
      throw new VcfFormatException("Header line does not have mandatory (tab-delimited) columns", m_lineNumber);
    }
    for (int i = 0; i < REQUIRED_COLUMNS.size(); i++) {
      if (!cols.get(i).equals(REQUIRED_COLUMNS.get(i))) {
        throw new VcfFormatException("Header column " + (i + 1) + " must be '" + REQUIRED_COLUMNS.get(i) +
            "' but was '" + cols.get(i) + "'", m_lineNumber);
      }
    }
    if (cols.size() > 8 && !cols.get(8).equals("FORMAT")) {
      throw new VcfFormatException("Header column 9 must be 'FORMAT' when sample columns are present but was '" +
          cols.get(8) + "'", m_lineNumber);
    }
    Set<String> sampleNames = new HashSet<>();
    for (int i = 9; i < cols.size(); i++) {
      if (!sampleNames.add(cols.get(i))) {
        throw new VcfFormatException("Duplicate sample name '" + cols.get(i) + "' in header", m_lineNumber);
      }
    }
    mdBuilder.setColumns(cols);
  }


  public static class Builder {
    private BufferedReader m_reader;
    private Path m_vcfFile;
    private boolean m_rsidsOnly;
    private VcfLineParser m_vcfLineParser;


    /**
     * Provides the {@link Path} to the VCF file to parse.
     */
    public Builder fromFile(Path dataFile) {
      Preconditions.checkNotNull(dataFile);
      if (m_reader != null) {
        throw new IllegalStateException("Already loading from reader");
      }
      if (!dataFile.toString().endsWith(".vcf")) {
        throw new IllegalArgumentException("Not a VCF file (doesn't end with .vcf extension");
      }
      m_vcfFile = dataFile;
      return this;
    }

    /**
     * Provides a {@link BufferedReader} to the beginning of the VCF file to parse.
     */
    public Builder fromReader(BufferedReader reader) {
      Preconditions.checkNotNull(reader);
      if (m_vcfFile != null) {
        throw new IllegalStateException("Already loading from file");
      }
      m_reader = reader;
      return this;
    }

    /**
     * Tells parser to ignore data lines that are not associated with an RSID.
     */
    public Builder rsidsOnly() {
      m_rsidsOnly = true;
      return this;
    }

    public Builder parseWith(VcfLineParser lineParser) {
      Preconditions.checkNotNull(lineParser);
      m_vcfLineParser = lineParser;
      return this;
    }


    public VcfParser build() throws IOException {
      if (m_vcfLineParser == null) {
        throw new IllegalStateException("Missing VcfLineParser");
      }
      if (m_vcfFile != null) {
        m_reader = Files.newBufferedReader(m_vcfFile);
      }
      if (m_reader == null) {
        throw new IllegalStateException("Must specify either file or reader to parse");
      }
      return new VcfParser(m_reader, m_rsidsOnly, m_vcfLineParser);
    }
  }
}
