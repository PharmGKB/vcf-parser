package org.pharmgkb.parser.vcf.model;

import org.pharmgkb.parser.vcf.VcfParser;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;


/**
 * This class represents a single VCF metadata line with an id and description.
 *
 * In 4.1/4.2:
 * <code>
 * ##ALT=<ID=type,Description="description">
 * ##FILTER=<ID=ID,Description="description">
 * </code>
 *
 * @author Mark Woon
 */
public class IdDescriptionMetadata {
  private String m_id;
  private String m_description;
  private Map<String, String> m_properties;


  public IdDescriptionMetadata(@Nonnull String[] props) {
    this(props, 1);
  }

  IdDescriptionMetadata(@Nonnull String[] props, int descCol) {
    m_id = VcfParser.splitProperty(props[0], false)[1];
    m_description = VcfParser.splitProperty(props[descCol], true)[1];
    addProperties(props, descCol + 1);
  }


  @Nonnull
  public String getId() {
    return m_id;
  }

  @Nonnull
  public String getDescription() {
    return m_description;
  }


  @Nullable
  public String getProperty(String name) {
    if (m_properties == null) {
      return null;
    }
    return m_properties.get(name.toLowerCase());
  }


  protected void addProperties(String[] props, int startIdx) {
    if (startIdx >= props.length) {
      return;
    }
    m_properties = new HashMap<>();
    for (int x = startIdx; x < props.length; x++) {
      String[] data = VcfParser.splitProperty(props[x], true);
      m_properties.put(data[0].toLowerCase(), data[1]);
    }
  }
}
