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
import java.io.Closeable;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;


/**
 * This class parses a VCF file.
 *
 * @author Mark Woon
 */
public class VcfParser implements Closeable {

  private static final Logger sf_logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static final Splitter sf_tabSplitter = Splitter.on("\t").trimResults();
  private static final Splitter sf_commaSplitter = Splitter.on(",").trimResults();
  private static final Splitter sf_colonSplitter = Splitter.on(":").trimResults();
  private static final Splitter sf_semicolonSplitter = Splitter.on(";").trimResults();

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
    int lineNumber = 1;
    while ((line = m_reader.readLine()) != null) {
      if (line.startsWith("##")) {
        try {
          parseMetadata(mdBuilder, line);
        } catch (RuntimeException e) {
          throw new IllegalArgumentException("Error parsing metadata on line #" + lineNumber + ": " + line, e);
        }
      } else if (line.startsWith("#")) {
        try {
          parseColumnInfo(mdBuilder, line);
        } catch (RuntimeException e) {
          throw new IllegalArgumentException("Error parsing column (# header) on line #" + lineNumber + ": " + line, e);
        }
        break;
      }
      lineNumber++;
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
    int lineNumber = 1;
    while ((line = m_reader.readLine()) != null) {

      try {

        List<String> data = sf_tabSplitter.splitToList(line);

        // CHROM
        String chromosome = data.get(0);

        // POS
        long position;
        try {
          position = Long.parseLong(data.get(1));
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException("Position " + data.get(1) + " is not numerical");
        }

        // ID
        List<String> ids = null;
        if (!data.get(2).equals(".")) {
          if (m_rsidsOnly && !VcfUtils.RSID_PATTERN.matcher(data.get(2)).find()) {
            continue;
          }
          ids = sf_commaSplitter.splitToList(data.get(2));
        } else if (m_rsidsOnly) {
          continue;
        }

        // REF
        List<String> ref = sf_commaSplitter.splitToList(data.get(3));

        // ALT
        List<String> alt = null;
        if (!data.get(7).isEmpty() && !data.get(4).equals(".")) {
          alt = sf_commaSplitter.splitToList(data.get(4));
        }

        // QUAL
        BigDecimal quality = null;
        if (!data.get(5).isEmpty() && !data.get(5).equals(".")) {
          quality = new BigDecimal(data.get(5));
        }

        // FILTER
        List<String> filters = null;
        if (!data.get(6).equals("PASS")) {
          filters = sf_semicolonSplitter.splitToList(data.get(6));
        }

        // INFO
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

        // FORMAT
        List<String> format = null;
        if (data.size() >= 9 && data.get(8) != null) {
          format = sf_colonSplitter.splitToList(data.get(8));
        }

        VcfPosition pos = new VcfPosition(chromosome, position, ids, ref, alt,
            quality, filters, info, format);
        List<VcfSample> samples = new ArrayList<>();
        for (int x = 9; x < data.size(); x++) {
          samples.add(new VcfSample(format, sf_colonSplitter.splitToList(data.get(x))));
        }
        m_vcfLineParser.parseLine(m_vcfMetadata, pos, samples);

        lineNumber++;

      } catch (RuntimeException e) {
        throw new IllegalArgumentException("Error parsing VCF data line #" + lineNumber + ": " + line, e);
      }

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
      case "contig":
      case "sample":
      case "pedigree":
        parseMetadataProperty(mdBuilder, propName, VcfUtils.removeWrapper(propValue));
        break;

      case "assembly":
      case "pedigreedb":
      default:
        mdBuilder.addRawProperty(propName, propValue);
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
    String[] cols = VcfUtils.METADATA_PATTERN.split(unescapedValue);
    if (wasEscaped) {
      for (int x = 0; x < cols.length; x++) {
        cols[x] = cols[x].replaceAll("~~~~", "\\");
        cols[x] = cols[x].replaceAll("~!~!", "\"");
      }
    }
    switch (propName.toLowerCase()) {
      case "alt":
        mdBuilder.addAlt(new IdDescriptionMetadata(VcfUtils.extractProperties(VcfUtils.Quoted.Unknown, cols), true));
        break;
      case "filter":
        mdBuilder.addFilter(new IdDescriptionMetadata(VcfUtils.extractProperties(VcfUtils.Quoted.Unknown, cols), true));
        break;
      case "info":
        mdBuilder.addInfo(new InfoMetadata(VcfUtils.extractProperties(VcfUtils.Quoted.Unknown, cols)));
        break;
      case "format":
        mdBuilder.addFormat(new FormatMetadata(VcfUtils.extractProperties(VcfUtils.Quoted.Unknown, cols)));
        break;
      case "contig":
        mdBuilder.addContig(new ContigMetadata(VcfUtils.extractProperties(VcfUtils.Quoted.Unknown, cols)));
        break;
      case "sample":
        mdBuilder.addSample(new IdDescriptionMetadata(VcfUtils.extractProperties(VcfUtils.Quoted.Unknown, cols), true));
        break;
      case "pedigree":
        mdBuilder.addPedigree(new BaseMetadata(VcfUtils.extractProperties(VcfUtils.Quoted.Unknown, cols)));
        break;
    }
  }


  private void parseColumnInfo(@Nonnull VcfMetadata.Builder mdBuilder, @Nonnull String line) {
    mdBuilder.setColumns(sf_tabSplitter.splitToList(line));
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
