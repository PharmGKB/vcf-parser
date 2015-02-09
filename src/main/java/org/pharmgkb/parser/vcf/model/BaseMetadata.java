package org.pharmgkb.parser.vcf.model;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * VCF metadata in the format XXX=&lt;key=value,key=value,...&gt;.
 * The properties <em>are case-sensitive</em>.
 * @author Douglas Myers-Turnbull
 */
public class BaseMetadata {

  private Map<String, String> m_properties;

  public BaseMetadata(@Nonnull Map<String, String> properties) {
    for (Map.Entry<String, String> entry : properties.entrySet()) {
      if (entry.getKey().contains("\n") || entry.getValue().contains("\n")) {
        throw new IllegalArgumentException("INFO [[[" + entry.getKey() + "=" + entry.getValue() + "]]] contains a newline");
      }
    }
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

  /**
   * Should be used only for base classes.
   * @param names An array of permitted property keys
   * @throws IllegalArgumentException If this metadata contains a property key not in the array passed
   */
  protected void ensureNoExtras(@Nonnull String... names) {
    Set<String> set = new HashSet<>();
    Collections.addAll(set, names);
    for (String property : m_properties.keySet()) {
      if (!set.contains(property)) {
        throw new IllegalArgumentException("Metadata line contains unexpected property " + property);
      }
    }
  }

}
