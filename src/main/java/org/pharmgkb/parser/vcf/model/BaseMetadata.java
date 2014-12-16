package org.pharmgkb.parser.vcf.model;

import org.pharmgkb.parser.vcf.VcfParser;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * VCF metadata in the format XXX=&lt;key=value,key=value,...&gt;.
 * @author Douglas Myers-Turnbull
 */
public class BaseMetadata {

  private Map<String, String> m_properties;

  public BaseMetadata(@Nonnull String[] props) {
    addProperties(props);
  }

  @Nullable
  public String getProperty(String name) {
    if (m_properties == null) {
      return null;
    }
    return m_properties.get(name.toLowerCase());
  }

  @Nonnull
  public Map<String, String> getProperties() {
    return m_properties;
  }

  protected void addProperties(@Nonnull String... props) {
    if (props.length == 0) {
      return;
    }
    m_properties = new HashMap<>();
    for (String prop : props) {
      String[] data = VcfParser.splitProperty(prop, null);
      m_properties.put(data[0].toLowerCase(), data[1]);
    }
  }

}
