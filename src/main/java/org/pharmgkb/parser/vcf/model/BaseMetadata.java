package org.pharmgkb.parser.vcf.model;

import org.pharmgkb.parser.vcf.VcfUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.invoke.MethodHandles;
import java.util.*;

/**
 * VCF metadata in the format XXX=&lt;key=value,key=value,...&gt;.
 * The properties <em>are case-sensitive</em>.
 * @author Douglas Myers-Turnbull
 */
public class BaseMetadata {

  private static final Logger sf_logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private LinkedHashMap<String, String> m_properties;

  protected BaseMetadata() {
    m_properties = new LinkedHashMap<>();
  }

  protected void init() {
    for (Map.Entry<String, String> entry : m_properties.entrySet()) {
      if (entry.getKey().contains("\n") || entry.getValue().contains("\n")) {
        throw new IllegalArgumentException("INFO [[[" + entry.getKey() + "=" + entry.getValue() + "]]] contains a newline");
      }
    }
  }

  @Nullable
  public String getPropertyUnquoted(@Nonnull String key) {
    String got = m_properties.get(key);
    if (got == null) {
      return null;
    }
    return VcfUtils.unquote(got);
  }

  @Nullable
  public String getPropertyRaw(@Nonnull String key) {
    return m_properties.get(key);
  }

  @Nonnull
  public Map<String, String> getPropertiesUnquoted() {
    Map<String, String> map = new LinkedHashMap<>();
    for (Map.Entry<String, String>  entry : m_properties.entrySet()) {
      map.put(entry.getKey(), VcfUtils.unquote(entry.getValue()));
    }
    return map;
  }

  @Nonnull
  public Map<String, String> getPropertiesRaw() {
    return m_properties;
  }

  @Nonnull
  public Set<String> getPropertyKeys() {
    return m_properties.keySet();
  }

  public void putAndQuoteProperty(@Nonnull String key, @Nullable String value) {
    if (value == null) {
      m_properties.remove(key);
    } else {
      m_properties.put(key, VcfUtils.quote(value));
    }
  }

  public void putPropertyRaw(@Nonnull String key, @Nullable String value) {
    m_properties.put(key, value);
  }

  /**
   * Should be used only for base classes.
   * Logs a warning if this metadata contains a property key not in the array passed.
   * @param names An array of permitted property keys
   */
  protected void ensureNoExtras(@Nonnull String... names) {
    Set<String> set = new HashSet<>();
    Collections.addAll(set, names);
    m_properties.keySet().stream().filter(property -> !set.contains(property)).forEach(property -> {
      sf_logger.warn("Metadata line contains unexpected property {}", property);
    });
  }

  @Nonnull
  public String asVcfString(@Nonnull String metadataTypeName) {
    // note that this gets overriden in RawMetadata
    StringBuilder sb = new StringBuilder("##");
    sb.append(metadataTypeName).append("=<");
    int i = 0;
    for (Map.Entry<String, String> entry : getPropertiesRaw().entrySet()) {
      if (i > 0) {
        sb.append(",");
      }
      sb.append(entry.getKey()).append("=").append(entry.getValue());
      i++;
    }
    sb.append(">");
    return sb.toString();
  }

}
