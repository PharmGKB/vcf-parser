package org.pharmgkb.parser.vcf.model;

import com.google.common.base.Preconditions;
import org.pharmgkb.parser.vcf.VcfUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * This class contains sample data for a VCF position line.
 *
 * @author Mark Woon
 */
public class VcfSample {

  private Map<String, String> m_properties = new HashMap<>();

  public VcfSample(@Nullable List<String> keys, @Nullable List<String> values) {
    if (keys == null) {
      if (values == null || values.size() == 0) {
        return;
      }
      throw new IllegalArgumentException("keys is null but values is not");
    } else if (values == null) {
      throw new IllegalArgumentException("values is null but keys is not");
    }
    Preconditions.checkArgument(keys.size() == values.size(), "Number of keys does not match number of values");
    for (int x = 0; x < keys.size(); x++) {
      m_properties.put(keys.get(x), values.get(x));
    }
    init();
  }

  public VcfSample(@Nonnull Map<String, String> properties) {
    m_properties = properties;
    init();
  }

  private void init() {
    for (Map.Entry<String, String> entry : m_properties.entrySet()) {
      if (entry.getKey().contains("\n") || entry.getValue().contains("\n")) {
        throw new IllegalArgumentException("FORMAT [[[" + entry.getKey() + "=" + entry.getValue() + "]]] contains a newline");
      }
    }
  }

  public @Nullable String getProperty(@Nonnull String key) {
    return m_properties.get(key);
  }

  /**
   * Returns the value for the reserved property as the type specified by both {@link ReservedFormatProperty#getType()}
   * and {@link ReservedFormatProperty#isList()}.
   * @param <T> The type specified by {@code ReservedInfoProperty.getType()} if {@code ReservedFormatProperty.isList()}
   *           is false;
   *           otherwise {@code List<V>} where V is the type specified by {@code ReservedFormatProperty.getType()}.
   */
  @SuppressWarnings("InfiniteRecursion")
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

  @Nonnull
  public Set<String> getPropertyKeys() {
    return m_properties.keySet();
  }

  @Nonnull
  public Set<Map.Entry<String, String>> propertyEntrySet() {
    return m_properties.entrySet();
  }

}
