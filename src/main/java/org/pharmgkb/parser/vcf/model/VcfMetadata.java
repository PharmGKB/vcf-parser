package org.pharmgkb.parser.vcf.model;

import com.google.common.base.Preconditions;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import org.pharmgkb.parser.vcf.VcfParser;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.stream.Collectors;


/**
 * This class captures all of the VCF metadata from a VCF file.
 *
 * @author Mark Woon
 */
public class VcfMetadata {
  private String m_fileFormat;
  private Map<String, IdDescriptionMetadata> m_alt;
  private List<InfoMetadata> m_info;
  private List<IdDescriptionMetadata> m_filter;
  private List<FormatMetadata> m_format;
  private List<String> m_columns;
  private ListMultimap<String, String> m_properties;
  private List<ContigMetadata> m_contig = new ArrayList<>();
  private List<IdDescriptionMetadata> m_sample;
  private List<IdDescriptionMetadata> m_pedigree;


  private VcfMetadata(@Nonnull String fileFormat, @Nullable Map<String, IdDescriptionMetadata> alt,
      @Nullable List<InfoMetadata> info, @Nullable List<IdDescriptionMetadata> filter,
      @Nullable List<FormatMetadata> format, @Nullable List<ContigMetadata> contig,
      @Nullable List<IdDescriptionMetadata> sample, @Nullable List<IdDescriptionMetadata> pedigree,
      @Nonnull List<String> columns, @Nullable ListMultimap<String, String> properties) {
    Preconditions.checkNotNull(fileFormat);
    Preconditions.checkNotNull(columns);
    m_fileFormat = fileFormat;
    if (alt == null) {
      m_alt = Collections.emptyMap();
    } else {
      m_alt = alt;
    }
    if (info == null) {
      m_info = Collections.emptyList();
    } else {
      m_info = info;
    }
    if (filter == null) {
      m_filter = Collections.emptyList();
    } else {
      m_filter = filter;
    }
    if (format == null) {
      m_format = Collections.emptyList();
    } else {
      m_format = format;
    }
    if (contig == null) {
      m_contig = Collections.emptyList();
    } else {
      m_contig = contig;
    }
    if (sample == null) {
      m_sample = Collections.emptyList();
    } else {
      m_sample = sample;
    }
    if (pedigree == null) {
      m_pedigree = Collections.emptyList();
    } else {
      m_pedigree = pedigree;
    }
    m_columns = columns;
    if (m_properties == null) {
      m_properties = ArrayListMultimap.create();
    } else {
      m_properties = properties;
    }
  }


  public @Nonnull String getFileFormat() {
    return m_fileFormat;
  }


  public @Nonnull Collection<IdDescriptionMetadata> getAlt() {
    return m_alt.values();
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


  public @Nonnull List<InfoMetadata> getInfo() {
    return m_info;
  }

  public @Nonnull List<IdDescriptionMetadata> getFilter() {
    return m_filter;
  }

  public @Nonnull List<FormatMetadata> getFormat() {
    return m_format;
  }

  public @Nonnull List<ContigMetadata> getContig() {
    return m_contig;
  }

  public @Nonnull List<IdDescriptionMetadata> getPedigree() {
    return m_pedigree;
  }

  public @Nonnull List<IdDescriptionMetadata> getSample() {
    return m_sample;
  }

  /**
   * @return The URLs from the field(s) in the <em>assembly</em> metadata line(s)
   */
  public @Nullable List<String> getAssemblies() {
    // spec says: ##assembly=url (without angle brackets)
    return m_properties.get("assembly");
  }

  /**
   * @return The URLs from the field(s) in the <em>pedigreeDB</em> metadata line(s), without angle brackets
   */
  public @Nullable List<String> getPedigreeDatabases() {
    // spec says: ##pedigreeDB=<url> (with angle brackets)
    List<String> properties = m_properties.get("pedigreeDB");
    List<String> urls = new ArrayList<>(properties.size());
    urls.addAll(properties.stream().map(VcfParser::removeWrapper).collect(Collectors.toList()));
    return urls;
  }

  public @Nonnull List<String> getProperty(String name) {
    return m_properties.get(name);
  }

  public int getColumnIndex(String col) {
    return m_columns.indexOf(col);
  }

  /**
   * Sample numbering starts at 0.
   */
  public int getSampleIndex(String sampleId) {
    return m_columns.indexOf(sampleId) - 9;
  }

  /**
   * Gets the number of samples in the VCF file.
   */
  public int getNumSamples() {
    return m_columns.size() - 9;
  }

  /**
   * Gets the sample name (column name).
   *
   * @param idx sample index, first sample is at index 0
   */
  public String getSampleName(int idx) {
    return m_columns.get(9 + idx);
  }



  public static class Builder {
    private String m_fileFormat;
    private Map<String, IdDescriptionMetadata> m_alt = new HashMap<>();
    private List<InfoMetadata> m_info = new ArrayList<>();
    private List<IdDescriptionMetadata> m_filter = new ArrayList<>();
    private List<FormatMetadata> m_format = new ArrayList<>();
    private List<ContigMetadata> m_contig = new ArrayList<>();
    private List<IdDescriptionMetadata> m_sample;
    private List<IdDescriptionMetadata> m_pedigree;
    private List<String> m_columns;
    private ListMultimap<String, String> m_properties = ArrayListMultimap.create();

    public Builder setFileFormat(String fileFormat) {
      m_fileFormat = fileFormat;
      return this;
    }

    public Builder addAlt(IdDescriptionMetadata md) {
      m_alt.put(md.getId(), md);
      return this;
    }

    public Builder addInfo(InfoMetadata md) {
      m_info.add(md);
      return this;
    }

    public Builder addFilter(IdDescriptionMetadata md) {
      m_filter.add(md);
      return this;
    }

    public Builder addFormat(FormatMetadata md) {
      m_format.add(md);
      return this;
    }

    public Builder addContig(ContigMetadata md) {
      m_contig.add(md);
      return this;
    }

    public Builder addSample(IdDescriptionMetadata md) {
      m_sample.add(md);
      return this;
    }

    public Builder addPedigree(IdDescriptionMetadata md) {
      m_pedigree.add(md);
      return this;
    }

    public Builder addProperty(String name, String value) {
      m_properties.put(name, value);
      return this;
    }

    public Builder setColumns(@Nonnull List<String> cols) {
      m_columns = cols;
      return this;
    }

    public VcfMetadata build() {
      if (m_fileFormat == null || !m_fileFormat.startsWith("VCF")) {
        throw new IllegalStateException("Not a VCF file");
      }
      return new VcfMetadata(m_fileFormat, m_alt, m_info, m_filter, m_format, m_contig, m_sample, m_pedigree,
          m_columns, m_properties);
    }
  }
}
