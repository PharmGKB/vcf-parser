package org.pharmgkb.parser.vcf.model;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;


/**
 * This class captures all of the VCF metadata from a VCF file.
 *
 * @author Mark Woon
 */
public class VcfMetadata {
  private String m_fileFormat;
  private List<IdDescriptionMetadata> m_alt;
  private List<InfoMetadata> m_info;
  private List<IdDescriptionMetadata> m_filter;
  private List<FormatMetadata> m_format;
  private List<String> m_columns;
  private ListMultimap<String, String> m_properties;


  private VcfMetadata(String fileFormat, List<IdDescriptionMetadata> alt, List<InfoMetadata> info,
      List<IdDescriptionMetadata> filter, List<FormatMetadata> format, @Nonnull List<String> columns,
      ListMultimap<String, String> properties) {
    m_fileFormat = fileFormat;
    m_alt = alt;
    m_info = info;
    m_filter = filter;
    m_format = format;
    m_columns = columns;
    m_properties = properties;
  }


  public String getFileFormat() {
    return m_fileFormat;
  }

  public List<IdDescriptionMetadata> getAlt() {
    return m_alt;
  }

  public List<InfoMetadata> getInfo() {
    return m_info;
  }

  public List<IdDescriptionMetadata> getFilter() {
    return m_filter;
  }

  public List<FormatMetadata> getFormat() {
    return m_format;
  }

  public List<String> getProperty(String name) {
    return m_properties.get(name);
  }

  public int getColumnIndex(String col) {
    return m_columns.indexOf(col);
  }


  /**
   * Gets the number of samples in the VCF file.
   */
  public int getNumSamples() {
    return m_columns.size() - 8;
  }



  public static class Builder {
    private String m_fileFormat;
    private List<IdDescriptionMetadata> m_alt = new ArrayList<>();
    private List<InfoMetadata> m_info = new ArrayList<>();
    private List<IdDescriptionMetadata> m_filter = new ArrayList<>();
    private List<FormatMetadata> m_format = new ArrayList<>();
    private List<String> m_columns;
    private ListMultimap<String, String> m_properties = ArrayListMultimap.create();

    public Builder setFileFormat(String fileFormat) {
      m_fileFormat = fileFormat;
      return this;
    }

    public Builder addAlt(IdDescriptionMetadata md) {
      m_alt.add(md);
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

    public Builder addProperty(String name, String value) {
      m_properties.put(name, value);
      return this;
    }

    public Builder setColumns(@Nonnull List<String> cols) {
      m_columns = cols;
      return this;
    }

    public VcfMetadata build() {
      return new VcfMetadata(m_fileFormat, m_alt, m_info, m_filter, m_format, m_columns, m_properties);
    }
  }
}
