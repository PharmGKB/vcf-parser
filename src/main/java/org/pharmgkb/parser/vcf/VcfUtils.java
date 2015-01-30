package org.pharmgkb.parser.vcf;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.pharmgkb.parser.vcf.model.ReservedProperty;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Contains static methods for handling properties in INFO and FORMAT fields.
 * @author Douglas Myers-Turnbull
 */
public class VcfUtils {

  public static Map<String, String> extractProperties(@Nonnull String... props) {
    Map<String, String> map = new HashMap<>();
    for (String prop : props) {
      Pair<String, String> pair;
      try {
        pair = splitProperty(prop, null);
      } catch (RuntimeException e) {
        throw new IllegalArgumentException("Error parsing property \"" + prop + "\"", e);
      }
      map.put(pair.getKey(), pair.getValue());
    }
    return map;
  }

  /**
   * Splits a property into a key-value pair.
   *
   * @param isStringValue if true, the value is a string that needs to be unwrapped (i.e remove
   * quotes). If set to null, decides based on the presence or absence of quotation marks before and after
   */
  public static Pair<String, String> splitProperty(@Nonnull String prop, Boolean isStringValue) {
    int idx = prop.indexOf("=");
    String key = prop.substring(0, idx);
    String value = prop.substring(idx + 1);
    boolean removeWrapper;
    if (isStringValue == null) {
      removeWrapper = value.startsWith("\"") && value.endsWith("\"");
      if (value.startsWith("\"") ^ value.endsWith("\"")) {
        throw new IllegalArgumentException("Quotation marks not matched for property " + prop);
      }
    } else {
      removeWrapper = isStringValue;
    }
    if (removeWrapper) {
      value = removeWrapper(value);
    }
    return Pair.of(key, value);
  }

  /**
   * Removes the wrapper around a string (e.g. quotes).
   */
  public static @Nonnull String removeWrapper(@Nonnull String value) {
    return value.substring(1, value.length() - 1);
  }

  /**
   * Converts a String representation of a property into a more useful type.
   * Specifically, can return:
   * <ul>
   *   <li>String</li>
   *   <li>Long</li>
   *   <li>BigDecimal</li>
   *   <li>The Boolean true (for flags)</li>
   *   <li>A List of any of the above types</li>
   * </ul>
   */
  public static @Nullable <T> T convertProperty(@Nonnull ReservedProperty key, @Nullable String value) {
   return convertProperty(key.getType(), value, key.isList());
  }

  /**
   * @see #convertProperty(ReservedProperty, String)
   */
  @SuppressWarnings("unchecked")
  public static @Nullable <T> T convertProperty(Class<?> clas, String value, boolean isList) {
    if (value == null || ".".equals(value)) {
      return null;
    }
    if (!isList) {
      try {
        return (T) convertElement(clas, value);
      } catch (ClassCastException e) {
        throw new IllegalArgumentException("Wrong type specified", e);
      }
    }
    List<Object> list = new ArrayList<>();
    for (String part : value.split(",")) {
      list.add(convertElement(clas, part));
    }
    try {
      return (T) list;
    } catch (ClassCastException e) {
      throw new IllegalArgumentException("Wrong type specified", e);
    }
  }

  private static @Nullable Object convertElement(Class<?> clas, String value) {
    if (value == null || ".".equals(value)) {
      return null;
    }
    if (clas == String.class) {
      return value;
    } else if (clas == Boolean.class) {
      value = StringUtils.stripToNull(value);
      if (value == null) {
        return true;
      }
      if (value.equals("0") || value.equalsIgnoreCase("false")) {
        return false;
      }
      if (value.equals("1") || value.equalsIgnoreCase("true")) {
        return true;
      }
      throw new IllegalArgumentException("Invalid boolean value: '" + value + "'");

    } else if (clas == BigDecimal.class) {
      try {
        return new BigDecimal(value);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("Expected float; got " + value);
      }
    } else if (clas == Long.class) {
      try {
        return Long.parseLong(value);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("Expected integer; got " + value);
      }
    }
    throw new UnsupportedOperationException("Type " + clas + " unrecognized");
  }
}
