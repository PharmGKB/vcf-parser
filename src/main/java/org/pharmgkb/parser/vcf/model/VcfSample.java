package org.pharmgkb.parser.vcf.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.pharmgkb.parser.vcf.VcfFormatException;
import org.pharmgkb.parser.vcf.VcfUtils;


/**
 * This class contains sample data for a VCF position line.
 *
 * <p>To avoid allocating a map for every sample on every line (the common parse-then-read path), the FORMAT keys and
 * their values are kept as parallel lists and read by a short linear scan. A {@link LinkedHashMap} is materialized only
 * when the sample is mutated or its keys/entries are iterated.</p>
 *
 * @author Mark Woon
 */
public class VcfSample {

  // Lean representation used on the parse path; both null once m_properties has been materialized.
  private @Nullable List<String> m_keys;
  private @Nullable List<String> m_values;
  // Materialized on demand (mutation / key or entry iteration), or supplied directly via the map constructor.
  private @Nullable LinkedHashMap<String, String> m_properties;

  public VcfSample(@Nullable List<String> keys, @Nullable List<String> values) {
    if (keys == null) {
      if (values == null || values.isEmpty()) {
        m_keys = Collections.emptyList();
        m_values = Collections.emptyList();
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
      if (keys.get(x).contains("\n") || values.get(x).contains("\n")) {
        throw new VcfFormatException("FORMAT [[[" + keys.get(x) + "=" + values.get(x) + "]]] contains a newline");
      }
    }
    m_keys = keys;
    m_values = values;
  }

  public VcfSample(LinkedHashMap<String, String> properties) {
    m_properties = properties;
    for (Map.Entry<String, String> entry : m_properties.entrySet()) {
      if (entry.getKey().contains("\n") || entry.getValue().contains("\n")) {
        throw new VcfFormatException("FORMAT [[[" + entry.getKey() + "=" + entry.getValue() + "]]] contains a newline");
      }
    }
  }

  /**
   * Materializes (and caches) the property map, dropping the lean parallel-list representation.
   */
  private LinkedHashMap<String, String> properties() {
    if (m_properties == null) {
      LinkedHashMap<String, String> map = new LinkedHashMap<>();
      List<String> keys = m_keys;
      List<String> values = m_values;
      assert keys != null && values != null;
      for (int x = 0; x < keys.size(); x++) {
        map.put(keys.get(x), values.get(x));
      }
      m_properties = map;
      m_keys = null;
      m_values = null;
    }
    return m_properties;
  }

  public @Nullable String getProperty(String key) {
    if (m_properties != null) {
      return m_properties.get(key);
    }
    List<String> keys = m_keys;
    assert keys != null && m_values != null;
    for (int x = 0; x < keys.size(); x++) {
      if (keys.get(x).equals(key)) {
        return m_values.get(x);
      }
    }
    return null;
  }

  /**
   * Returns the value for the reserved property as the type specified by both {@link ReservedFormatProperty#getType()}
   * and {@link ReservedFormatProperty#isList()}.
   *
   * @param <T> The type specified by {@code ReservedInfoProperty.getType()} if {@code ReservedFormatProperty.isList()}
   *           is false;
   *           otherwise {@code List<V>} where V is the type specified by {@code ReservedFormatProperty.getType()}.
   */
  public @Nullable <T> T getProperty(ReservedFormatProperty key) {
    return VcfUtils.convertProperty(key, getProperty(key.getId()));
  }

  public boolean containsProperty(String key) {
    if (m_properties != null) {
      return m_properties.containsKey(key);
    }
    List<String> keys = m_keys;
    assert keys != null;
    return keys.contains(key);
  }

  public boolean containsProperty(ReservedFormatProperty key) {
    return containsProperty(key.getId());
  }

  public void putProperty(String key, @Nullable String value) {
    properties().put(key, value);
  }

  public void putProperty(ReservedFormatProperty key, @Nullable String value) {
    properties().put(key.getId(), value);
  }

  public void removeProperty(String key) {
    properties().remove(key);
  }

  public void removeProperty(ReservedFormatProperty key) {
    properties().remove(key.getId());
  }

  public void clearProperties() {
    properties().clear();
  }

  /**
   * @return A set of the property keys, which has guaranteed order
   */
  public Set<String> getPropertyKeys() {
    // LinkedHashMap.keySet() returns a LinkedKeySet, which has guaranteed order
    return properties().keySet();
  }

  public Set<Map.Entry<String, String>> propertyEntrySet() {
    return properties().entrySet();
  }
}
