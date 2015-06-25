package org.pharmgkb.parser.vcf.model;

import com.google.common.base.Preconditions;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import org.pharmgkb.parser.vcf.VcfUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;


/**
 * This class captures all of the VCF metadata from a VCF file.
 *
 * @author Mark Woon
 */
public class VcfMetadata {
  private String m_fileFormat;
  private Map<String, IdDescriptionMetadata> m_alt;
  private Map<String, InfoMetadata> m_info;
  private Map<String, IdDescriptionMetadata> m_filter;
  private Map<String, FormatMetadata> m_format;
  private List<String> m_columns;
  private ListMultimap<String, String> m_properties;
  private Map<String, ContigMetadata> m_contig;
  private Map<String, IdDescriptionMetadata> m_sample;
  private List<BaseMetadata> m_pedigree;


  private VcfMetadata(@Nonnull String fileFormat, @Nullable Map<String, IdDescriptionMetadata> alt,
      @Nullable Map<String, InfoMetadata> info, @Nullable Map<String, IdDescriptionMetadata> filter,
      @Nullable Map<String, FormatMetadata> format, @Nullable Map<String, ContigMetadata> contig,
      @Nullable Map<String, IdDescriptionMetadata> sample, @Nullable List<BaseMetadata> pedigree,
      @Nonnull List<String> columns, @Nullable ListMultimap<String, String> properties) {
    Preconditions.checkNotNull(fileFormat);
    Preconditions.checkNotNull(columns);
    m_fileFormat = fileFormat;
    if (alt == null) {
      m_alt = new HashMap<>();
    } else {
      m_alt = alt;
    }
    if (info == null) {
      m_info = new HashMap<>();
    } else {
      m_info = info;
    }
    if (filter == null) {
      m_filter = new HashMap<>();
    } else {
      m_filter = filter;
    }
    if (format == null) {
      m_format = new HashMap<>();
    } else {
      m_format = format;
    }
    if (contig == null) {
      m_contig = new HashMap<>();
    } else {
      m_contig = contig;
    }
    if (sample == null) {
      m_sample = new HashMap<>();
    } else {
      m_sample = sample;
    }
    if (pedigree == null) {
      m_pedigree = new ArrayList<>();
    } else {
      m_pedigree = pedigree;
    }
    m_columns = columns;
    if (properties == null) {
      m_properties = ArrayListMultimap.create();
    } else {
      m_properties = properties;
    }
  }


  public @Nonnull String getFileFormat() {
    return m_fileFormat;
  }

  public void setFileFormat(@Nonnull String fileFormat) {
    if (!VcfUtils.FILE_FORMAT_PATTERN.matcher(fileFormat).matches()) {
      throw new IllegalArgumentException("VCF format must look like ex: VCFv4.2; was " + fileFormat);
    }
    m_fileFormat = fileFormat;
  }

  public @Nonnull Map<String, IdDescriptionMetadata> getAlts() {
    return m_alt;
  }

  /**
   * Gets the ALT metadata for the given ID.
   *
   * @param id the ID to lookup, will unwrap ID's enclosed in angle brackets (e.g. &lt;CN1&gt; will get converted to CN1)
   */
  @Nullable
  public IdDescriptionMetadata getAlt(@Nonnull String id) {
    IdDescriptionMetadata md = m_alt.get(id);
    if (md == null && id.startsWith("<") && id.endsWith(">")) {
      md = m_alt.get(id.substring(1, id.length() - 1));
    }
    return md;
  }


  public @Nonnull Map<String, InfoMetadata> getInfo() {
    return m_info;
  }

  public @Nonnull Map<String, IdDescriptionMetadata> getFilters() {
    return m_filter;
  }

  public @Nonnull Map<String, FormatMetadata> getFormats() {
    return m_format;
  }

  public @Nonnull Map<String, ContigMetadata> getContigs() {
    return m_contig;
  }

  public @Nonnull List<BaseMetadata> getPedigrees() {
    return m_pedigree;
  }

  public @Nonnull Map<String, IdDescriptionMetadata> getSamples() {
    return m_sample;
  }

  /**
   * @return The URLs from the field(s) in the <em>assembly</em> metadata line(s)
   */
  public @Nonnull List<String> getAssemblies() {
    // spec says: ##assembly=url (without angle brackets)
    return m_properties.get("assembly");
  }

  /**
   * @return The URLs from the field(s) in the <em>pedigreeDB</em> metadata line(s), including angle brackets if any
   */
  public @Nonnull List<String> getPedigreeDatabases() {
    // spec says: ##pedigreeDB=<url> (with angle brackets)
    return m_properties.get("pedigreeDB");
  }

  public void putAlt(@Nonnull IdDescriptionMetadata value) {
    m_alt.put(value.getId(), value);
  }

  public void putInfo(@Nonnull InfoMetadata value) {
    m_info.put(value.getId(), value);
  }

  public void putFormat(@Nonnull FormatMetadata value) {
    m_format.put(value.getId(), value);
  }

  public void putContig(@Nonnull ContigMetadata value) {
    m_contig.put(value.getId(), value);
  }

  public void putFilter(@Nonnull IdDescriptionMetadata value) {
    m_filter.put(value.getId(), value);
  }

  public void putAssembly(@Nonnull String value) {
    m_properties.put("assembly", value);
  }

  /**
   * @param value Must be wrapped in angle brakcets
   */
  public void addPedigreeDatabase(@Nonnull String value) {
    if (value.startsWith("<") && value.endsWith(">")) {
      m_properties.put("pedigreeDB", value);
    } else {
      throw new IllegalArgumentException("pedigreeDB string " + value + " should be enclosed in angle brackets according to spec");
    }
  }

  /**
   * Returns a map from every property key to each of its values.
   * Call {@link ListMultimap#asMap} to get a Map&lt;String, Collection&lt;String&gt;&gt;.
   * @return <em>Contains every property except those contained in:</em>
   * <ul>
   *   <li>{@link #getInfo}</li>
   *   <li>{@link #getFilters}</li>
   *   <li>{@link #getFormats}</li>
   *   <li>{@link #getContigs}</li>
   *   <li>{@link #getPedigrees}</li>
   *   <li>{@link #getInfo}</li>
   *   <li>{@link #getSamples}</li>
   * </ul>
   * However, contains any in {@link #getAssemblies} and {@link #getPedigreeDatabases}.
   */
  public @Nonnull ListMultimap<String, String> getRawProperties() {
    return m_properties;
  }

  /**
   * Returns the value of a property, or null if the property is not set or has no value.
   * <strong>This method will return null for a reserved property of the form XX=&lt;ID=value,ID=value,...&gt;;
   * {@code assembly} and {@code pedigreeDB} are still included.</strong>
   */
  public @Nonnull List<String> getRawValuesOfProperty(@Nonnull String propertyKey) {
    return m_properties.get(propertyKey);
  }

  /**
   * Returns a list of the properties defined.
   * <strong>Reserved properties of the form XX=&lt;ID=value,ID=value,...&gt; are excluded, though {@code assembly}
   * and {@code pedigreeDB} are still included.</strong>
   * @return <em>Contains every property except those contained in:</em>
   * <ul>
   *   <li>{@link #getInfo}</li>
   *   <li>{@link #getFilters}</li>
   *   <li>{@link #getFormats}</li>
   *   <li>{@link #getContigs}</li>
   *   <li>{@link #getPedigrees}</li>
   *   <li>{@link #getInfo}</li>
   *   <li>{@link #getSamples}</li>
   * </ul>
   * However, contains any in {@link #getAssemblies} and {@link #getPedigreeDatabases}.
   */
  public @Nonnull Set<String> getRawPropertyKeys() {
    return m_properties.keySet();
  }

  public int getColumnIndex(@Nonnull String column) {
    return m_columns.indexOf(column);
  }

  /**
   * Sample numbering starts at 0.
   */
  public int getSampleIndex(@Nonnull String sampleId) {
    return m_columns.indexOf(sampleId) - 9;
  }

  /**
   * Gets the number of samples in the VCF file.
   */
  public int getNumSamples() {
    if (m_columns.size() < 9) {
      return 0; // necessary because if we have no samples, we'll be missing FORMAT
    }
    return m_columns.size() - 9;
  }

  /**
   * Gets the sample name (column name).
   *
   * @param idx sample index, first sample is at index 0
   *
   * @throws ArrayIndexOutOfBoundsException If the sample doesn't exist
   */
  public @Nonnull String getSampleName(int idx) {
    return m_columns.get(9 + idx);
  }



  public static class Builder {
    private String m_fileFormat;
    private Map<String, IdDescriptionMetadata> m_alt = new HashMap<>();
    private Map<String, InfoMetadata> m_info = new HashMap<>();
    private Map<String, IdDescriptionMetadata> m_filter = new HashMap<>();
    private Map<String, FormatMetadata> m_format = new HashMap<>();
    private Map<String, ContigMetadata> m_contig = new HashMap<>();
    private Map<String, IdDescriptionMetadata> m_sample = new HashMap<>();
    private List<BaseMetadata> m_pedigree = new ArrayList<>();
    private List<String> m_columns = new ArrayList<>();
    private ListMultimap<String, String> m_properties = ArrayListMultimap.create();

    /**
     * Sets the VCF version string.
     * @param fileFormat Ex: "VCFv4.2"
     */
    public Builder setFileFormat(@Nonnull String fileFormat) {
      m_fileFormat = fileFormat;
      if (!VcfUtils.FILE_FORMAT_PATTERN.matcher(fileFormat).matches()) {
        throw new IllegalStateException("Not a VCF file: fileformat is " + m_fileFormat);
      }
      return this;
    }

    public Builder addAlt(@Nonnull IdDescriptionMetadata md) {
      if (m_alt.containsKey(md.getId())) {
        throw new IllegalArgumentException("Duplicate ID " + md.getId() + " for ALT");
      }
      m_alt.put(md.getId(), md);
      return this;
    }

    public Builder addInfo(@Nonnull InfoMetadata md) {
      if (m_info.containsKey(md.getId())) {
        throw new IllegalArgumentException("Duplicate ID " + md.getId() + " for INFO");
      }
      m_info.put(md.getId(), md);
      return this;
    }

    public Builder addFilter(@Nonnull IdDescriptionMetadata md) {
      if (m_filter.containsKey(md.getId())) {
        throw new IllegalArgumentException("Duplicate ID " + md.getId() + " for FILTER");
      }
      m_filter.put(md.getId(), md);
      return this;
    }

    public Builder addFormat(@Nonnull FormatMetadata md) {
      if (m_format.containsKey(md.getId())) {
        throw new IllegalArgumentException("Duplicate ID " + md.getId() + " for FORMAT");
      }
      m_format.put(md.getId(), md);
      return this;
    }

    public Builder addContig(@Nonnull ContigMetadata md) {
      if (m_contig.containsKey(md.getId())) {
        throw new IllegalArgumentException("Duplicate ID " + md.getId() + " for CONTIG");
      }
      m_contig.put(md.getId(), md);
      return this;
    }

    public Builder addSample(@Nonnull IdDescriptionMetadata md) {
      if (m_sample.containsKey(md.getId())) {
        throw new IllegalArgumentException("Duplicate ID " + md.getId() + " for SAMPLE");
      }
      m_sample.put(md.getId(), md);
      return this;
    }

    public Builder addPedigree(@Nonnull BaseMetadata md) {
      m_pedigree.add(md);
      return this;
    }

    public Builder addRawProperty(@Nonnull String name, @Nonnull String value) {
      m_properties.put(name, value);
      return this;
    }

    public Builder setColumns(@Nonnull List<String> cols) {
      m_columns = cols;
      return this;
    }

    @Nonnull
    public VcfMetadata build() {
      if (m_fileFormat == null) {
        throw new IllegalStateException("Not a VCF file: no ##fileformat line");
      }
      return new VcfMetadata(m_fileFormat, m_alt, m_info, m_filter, m_format, m_contig, m_sample, m_pedigree,
          m_columns, m_properties);
    }
  }
}
