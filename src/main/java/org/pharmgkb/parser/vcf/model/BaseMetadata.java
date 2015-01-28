package org.pharmgkb.parser.vcf.model;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;

/**
 * VCF metadata in the format XXX=&lt;key=value,key=value,...&gt;.
 * The properties <em>are case-sensitive</em>.
 * @author Douglas Myers-Turnbull
 */
public class BaseMetadata {

  private Map<String, String> m_properties;

  public BaseMetadata(@Nonnull Map<String, String> properties) {
    m_properties = properties;
  }

  @Nullable
  public String getProperty(@Nonnull String name) {
    return m_properties.get(name);
  }

  @Nonnull
  public Map<String, String> getProperties() {
    return m_properties;
  }

}
