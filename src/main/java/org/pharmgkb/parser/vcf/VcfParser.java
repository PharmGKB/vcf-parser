package org.pharmgkb.parser.vcf;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import org.apache.commons.io.IOUtils;
import org.pharmgkb.parser.vcf.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;


/**
 * This class parses a VCF file.
 *
 * @author Mark Woon
 */
public class VcfParser implements AutoCloseable {
  private static final Logger sf_logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private static final Pattern sf_refBasePattern = Pattern.compile("[AaCcGgTtNn]+");
  private static final Pattern sf_altBasePattern = Pattern.compile("(?:[AaCcGgTtNn\\*]+|<.+>)");
  // from http://stackoverflow.com/questions/1757065/java-splitting-a-comma-separated-string-but-ignoring-commas-in-quotes
  private static final Pattern sf_metadataPattern = Pattern.compile(",(?=([^\"]*\"[^\"]*\")*[^\"]*$)");
  private static final Splitter sf_tabSplitter = Splitter.on("\t").trimResults();
  private static final Splitter sf_commaSplitter = Splitter.on(",").trimResults();
  private static final Splitter sf_colonSplitter = Splitter.on(":").trimResults();
  private static final Splitter sf_semicolonSplitter = Splitter.on(";").trimResults();
  private static final Pattern sf_rsidPattern = Pattern.compile("rs\\d+");

  private boolean m_rsidsOnly;
  private BufferedReader m_reader;
  private VcfMetadata m_vcfMetadata;
  private VcfLineParser m_vcfLineParser;



  private VcfParser(@Nonnull BufferedReader reader, boolean rsidsOnly, @Nonnull VcfLineParser lineParser) {
    m_reader = reader;
    m_rsidsOnly = rsidsOnly;
    m_vcfLineParser = lineParser;
  }


  /**
   * Parses metadata only.
   */
  public VcfMetadata parseMetadata() throws IOException {

    if (m_vcfMetadata != null) {
      throw new IllegalStateException("Metadata has already been parsed.");
    }
    VcfMetadata.Builder mdBuilder = new VcfMetadata.Builder();
    String line;
    while ((line = m_reader.readLine()) != null) {
      if (line.startsWith("##")) {
        parseMetadata(mdBuilder, line);
      } else if (line.startsWith("#")) {
        parseColumnInfo(mdBuilder, line);
        break;
      }
    }
    m_vcfMetadata = mdBuilder.build();
    return m_vcfMetadata;
  }


  /**
   * Gets VCF metadata (if it has already been parsed).
   */
  public @Nullable VcfMetadata getMetadata() {
    return m_vcfMetadata;
  }


  /**
   * Parses entire VCF file.
   */
  public void parse() throws IOException {

    if (m_vcfMetadata == null) {
      parseMetadata();
    }
    String line;
    while ((line = m_reader.readLine()) != null) {
      List<String> data = sf_tabSplitter.splitToList(line);

      List<String> ids = null;
      if (!data.get(2).equals(".")) {
        if (m_rsidsOnly && !sf_rsidPattern.matcher(data.get(2)).find()) {
          continue;
        }
        ids = sf_commaSplitter.splitToList(data.get(2));
      } else if (m_rsidsOnly) {
        continue;
      }

      List<String> ref = sf_commaSplitter.splitToList(data.get(3));
      for (String base : ref) {
        if (!sf_refBasePattern.matcher(base).matches()) {
          throw new IllegalArgumentException("Invalid reference base '" + base + "' (must be [AaGgCcTtNn]+)");
        }
      }

      List<String> alt = null;
      if (!data.get(7).equals("") && !data.get(4).equals(".")) {
        alt = sf_commaSplitter.splitToList(data.get(4));
        for (String base : alt) {
          if (!sf_altBasePattern.matcher(base).matches()) {
            throw new IllegalArgumentException("Invalid alternate base '" + base + "' (must be [AaGgCcTtNn\\*]+ or <.+>)");
          }
        }
      }

      ListMultimap<String, String> info = null;
      if (!data.get(7).equals("") && !data.get(7).equals(".")) {
        info = ArrayListMultimap.create();
        List<String> props = sf_semicolonSplitter.splitToList(data.get(7));
        for (String prop : props) {
          int idx = prop.indexOf('=');
          if (idx == -1) {
            info.put(prop, "");
          } else {
            String key = prop.substring(0, idx);
            String value = prop.substring(idx + 1);
            info.putAll(key, sf_commaSplitter.split(value));
          }
        }
      }

      List<String> format = null;
      if (data.size() >= 9 && data.get(8) != null) {
        format = sf_colonSplitter.splitToList(data.get(8));
      }

      VcfPosition pos = new VcfPosition(data.get(0), Long.parseLong(data.get(1)), ids, ref, alt,
          data.get(5), data.get(6), info, format);
      List<VcfSample> samples = new ArrayList<>();
      for (int x = 9; x < data.size(); x++) {
        samples.add(new VcfSample(format, sf_colonSplitter.splitToList(data.get(x))));
      }
      m_vcfLineParser.parseLine(m_vcfMetadata, pos, samples);
    }
    IOUtils.closeQuietly(m_reader);
  }


  @Override
  public void close() {
    IOUtils.closeQuietly(m_reader);
  }


  /**
   * Parses a metadata line (starts with ##).
   */
  private void parseMetadata(@Nonnull VcfMetadata.Builder mdBuilder, @Nonnull String line) {
    int idx = line.indexOf("=");
    String propName = line.substring(2, idx).trim();
    String propValue = line.substring(idx + 1).trim();

    sf_logger.debug("{} : {}", propName, propValue);

    switch (propName.toLowerCase()) {
      case "fileformat":
        mdBuilder.setFileFormat(propValue);
        break;

      case "alt":
      case "filter":
      case "info":
      case "format":
        parseMetadataProperty(mdBuilder, propName, removeWrapper(propValue));
        break;

      case "assembly":
      case "contig":
      case "sample":
      case "pedigree":
      case "pedigreedb":
      default:
        mdBuilder.addProperty(propName, propValue);
    }
  }

  /**
   * Converts metadata name-value pair into object.
   */
  private void parseMetadataProperty(@Nonnull VcfMetadata.Builder mdBuilder,
      @Nonnull String propName, @Nonnull String value) {
    String unescapedValue = value.replaceAll("\\\\", "~~~~");
    unescapedValue = unescapedValue.replaceAll("\\\\\"", "~!~!");
    boolean wasEscaped = !unescapedValue.equals(value);
    String[] cols = sf_metadataPattern.split(unescapedValue);
    if (wasEscaped) {
      for (int x = 0; x < cols.length; x++) {
        cols[x] = cols[x].replaceAll("~~~~", "\\");
        cols[x] = cols[x].replaceAll("~!~!", "\"");
      }
    }
    switch (propName.toLowerCase()) {
      case "alt":
        mdBuilder.addAlt(new IdDescriptionMetadata(cols));
        break;
      case "filter":
        mdBuilder.addFilter(new IdDescriptionMetadata(cols));
        break;
      case "info":
        mdBuilder.addInfo(new InfoMetadata(cols));
        break;
      case "format":
        mdBuilder.addFormat(new FormatMetadata(cols));
        break;
    }
  }


  private void parseColumnInfo(@Nonnull VcfMetadata.Builder mdBuilder, @Nonnull String line) {
    mdBuilder.setColumns(sf_tabSplitter.splitToList(line));
  }


  /**
   * Splits a property into a key-value pair.
   *
   * @param isStringValue if true, the value is a string that needs to be unwrapped (i.e remove
   * quotes)
   */
  public static String[] splitProperty(@Nonnull String prop, boolean isStringValue) {
    int idx = prop.indexOf("=");
    String[] data = new String[2];
    data[0] = prop.substring(0, idx);
    data[1] = prop.substring(idx + 1);
    if (isStringValue) {
      data[1] = removeWrapper(data[1]);
    }
    return data;
  }

  /**
   * Removes the wrapper around a string (e.g. quotes).
   */
  public static @Nonnull String removeWrapper(@Nonnull String value) {
    return value.substring(1, value.length() - 1);
  }



  public static class Builder {
    private BufferedReader m_reader;
    private Path m_vcfFile;
    private boolean m_rsidsOnly;
    private VcfLineParser m_vcfLineParser;


    /**
     * Provides the {@link Path} to the VCF file to parse.
     */
    public Builder fromFile(@Nonnull Path dataFile) {
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
    public Builder fromReader(@Nonnull BufferedReader reader) {
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

    public Builder parseWith(@Nonnull VcfLineParser lineParser) {
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
