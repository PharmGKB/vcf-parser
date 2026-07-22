package org.pharmgkb.parser.vcf.model;

import java.lang.invoke.MethodHandles;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.pharmgkb.parser.vcf.VcfFormatException;
import org.pharmgkb.parser.vcf.VcfUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * VCF metadata in the format XXX=&lt;key=value,key=value,...&gt;.
 * The properties <em>are case-sensitive</em>.
 * @author Douglas Myers-Turnbull
 */
public class BaseMetadata {

  private static final Logger sf_logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private final Map<String, String> m_properties;

  public BaseMetadata(Map<String, String> properties) {
    for (Map.Entry<String, String> entry : properties.entrySet()) {
      checkNoLineTerminator(entry.getKey(), entry.getValue());
    }
    m_properties = properties;
  }

  /**
   * Rejects a key or value containing a line terminator: such a property would corrupt the single-line structure of a
   * written {@code ##} metadata line (see {@link org.pharmgkb.parser.vcf.VcfWriter}), so this is checked here rather
   * than deferred to write time.
   */
  private static void checkNoLineTerminator(String key, @Nullable String value) {
    if (key.contains("\n") || key.contains("\r") || (value != null && (value.contains("\n") || value.contains("\r")))) {
      throw new VcfFormatException("Metadata property [[[" + key + "=" + value + "]]] contains a line terminator");
    }
  }

  @Nullable
  public String getPropertyUnquoted(String key) {
    String got = m_properties.get(key);
    if (got == null) {
      return null;
    }
    return VcfUtils.unquote(got);
  }

  @Nullable
  public String getPropertyRaw(String key) {
    return m_properties.get(key);
  }

  public Map<String, String> getPropertiesUnquoted() {
    Map<String, String> map = new HashMap<>();
    for (Map.Entry<String, String>  entry : m_properties.entrySet()) {
      map.put(entry.getKey(), VcfUtils.unquote(entry.getValue()));
    }
    return map;
  }

  public Map<String, String> getPropertiesRaw() {
    return m_properties;
  }

  public Set<String> getPropertyKeys() {
    return m_properties.keySet();
  }

  public void putAndQuoteProperty(String key, @Nullable String value) {
    if (value == null) {
      m_properties.remove(key);
    } else {
      checkNoLineTerminator(key, value);
      m_properties.put(key, VcfUtils.quote(value));
    }
  }

  public void putPropertyRaw(String key, @Nullable String value) {
    checkNoLineTerminator(key, value);
    m_properties.put(key, value);
  }

  /**
   * Should be used only for base classes.
   * Logs a warning if this metadata contains a property key not in the array passed.
   * @param names An array of permitted property keys
   */
  protected void ensureNoExtras(String... names) {
    Set<String> set = new HashSet<>();
    Collections.addAll(set, names);
    m_properties.keySet().stream().filter(property -> !set.contains(property)).forEach(property -> {
      sf_logger.warn("Metadata line contains unexpected property {}", property);
    });
  }

}
