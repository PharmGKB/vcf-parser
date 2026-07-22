package org.pharmgkb.parser.vcf.model;

import java.lang.invoke.MethodHandles;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
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
      VcfUtils.checkNoLineTerminator(entry.getKey(), entry.getValue());
    }
    m_properties = properties;
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
      VcfUtils.checkNoLineTerminator(key, value);
      m_properties.put(key, VcfUtils.quote(value));
    }
  }

  public void putPropertyRaw(String key, @Nullable String value) {
    VcfUtils.checkNoLineTerminator(key, value);
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

  /**
   * Logs a warning (using the caller's own logger, so the warning is attributed to the caller's class) if
   * {@code value} is null, i.e. the required property {@code propertyName} is absent.
   */
  protected static void warnIfMissing(Logger logger, String propertyName, @Nullable String value) {
    if (value == null) {
      logger.warn("Required metadata property \"{}\" is missing", propertyName);
    }
  }

  /**
   * Validates a VCF {@code Number} property value, warning (via the caller's own logger) if it is missing or does not
   * match {@link VcfUtils#NUMBER_PATTERN}. Shared by the metadata types ({@code INFO}, {@code FORMAT}) that declare a
   * {@code Number} field with identical semantics.
   */
  protected static void checkNumberProperty(Logger logger, @Nullable String number) {
    warnIfMissing(logger, "Number", number);
    if (number != null && !VcfUtils.NUMBER_PATTERN.matcher(number).matches()) {
      logger.warn("Number is not a valid VCF number: '{}'", number);
    }
  }

}
