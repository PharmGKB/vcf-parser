package org.pharmgkb.parser.vcf.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.pharmgkb.parser.vcf.VcfFormatException;
import org.pharmgkb.parser.vcf.VcfUtils;

/**
 * This class contains sample data for a VCF position line.
 *
 * @author Mark Woon
 */
public class VcfSample {

  private LinkedHashMap<String, String> m_properties = new LinkedHashMap<>();

  public VcfSample(@Nullable List<String> keys, @Nullable List<String> values) {
    if (keys == null) {
      if (values == null || values.size() == 0) {
        return;
      }
      throw new VcfFormatException("Sample keys is null but values is not");
    } else if (values == null) {
      throw new VcfFormatException("Sample values is null but keys is not");
    }
    if (keys.size() != values.size()) {
        throw new VcfFormatException("Number of FORMAT entries does not match number of sample entries");
    }
    for (int x = 0; x < keys.size(); x++) {
      m_properties.put(keys.get(x), values.get(x));
    }
    init();
  }

  public VcfSample(@Nonnull LinkedHashMap<String, String> properties) {
    m_properties = properties;
    init();
  }

  private void init() {
    for (Map.Entry<String, String> entry : m_properties.entrySet()) {
      if (entry.getKey().contains("\n") || entry.getValue().contains("\n")) {
        throw new VcfFormatException("FORMAT [[[" + entry.getKey() + "=" + entry.getValue() + "]]] contains a newline");
      }
    }
  }

  public @Nullable String getProperty(@Nonnull String key) {
    return m_properties.get(key);
  }

  /**
   * Returns the value for the reserved property as the type specified by both {@link ReservedFormatProperty#getType()}
   * and {@link ReservedFormatProperty#isList()}.
   *
   * @param <T> The type specified by {@code ReservedInfoProperty.getType()} if {@code ReservedFormatProperty.isList()}
   *           is false;
   *           otherwise {@code List<V>} where V is the type specified by {@code ReservedFormatProperty.getType()}.
   */
  public @Nullable <T> T getProperty(@Nonnull ReservedFormatProperty key) {
    return VcfUtils.convertProperty(key, getProperty(key.getId()));
  }

  public boolean containsProperty(@Nonnull String key) {
    return m_properties.containsKey(key);
  }

  public boolean containsProperty(@Nonnull ReservedFormatProperty key) {
    return m_properties.containsKey(key.getId());
  }

  public void putProperty(@Nonnull String key, @Nullable String value) {
    m_properties.put(key, value);
  }

  public void putProperty(@Nonnull ReservedFormatProperty key, @Nullable String value) {
    m_properties.put(key.getId(), value);
  }

  public void removeProperty(@Nonnull String key) {
    m_properties.remove(key);
  }

  public void removeProperty(@Nonnull ReservedFormatProperty key) {
    m_properties.remove(key.getId());
  }

  public void clearProperties() {
    m_properties.clear();
  }

  /**
   * @return A set of the property keys, which has guaranteed order
   */
  @Nonnull
  public Set<String> getPropertyKeys() {
    // LinkedHashMap.keySet() returns a LinkedKeySet, which has guaranteed order
    return m_properties.keySet();
  }

  @Nonnull
  public Set<Map.Entry<String, String>> propertyEntrySet() {
    return m_properties.entrySet();
  }

}
