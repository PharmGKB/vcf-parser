package org.pharmgkb.parser.vcf.model;

import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Contains static methods for handling properties in INFO and FORMAT fields.
 * @author Douglas Myers-Turnbull
 */
public class PropertyUtils {

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
  @SuppressWarnings("unchecked")
  public static @Nullable <T> T convertProperty(@Nonnull ReservedProperty key, @Nullable String value) {
    if (value == null || ".".equals(value)) {
      return null;
    }
    if (!key.isList()) {
      try {
        return (T) convertElement(key, value);
      } catch (ClassCastException e) {
        throw new IllegalArgumentException("Wrong type specified", e);
      }
    }
    List<Object> list = new ArrayList<>();
    for (String part : value.split(",")) {
      list.add(convertElement(key, part));
    }
    try {
      return (T) list;
    } catch (ClassCastException e) {
      throw new IllegalArgumentException("Wrong type specified", e);
    }
  }

  private static @Nullable Object convertElement(ReservedProperty key, String value) {
    if (value == null || ".".equals(value)) {
      return null;
    }
    if (key.getType() == String.class) {
      return value;
    } else if (key.getType() == Boolean.class) {
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

    } else if (key.getType() == BigDecimal.class) {
      try {
        return new BigDecimal(value);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("Expected float; got " + value);
      }
    } else if (key.getType() == Long.class) {
      try {
        return Long.parseLong(value);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("Expected integer; got " + value);
      }
    }
    throw new UnsupportedOperationException("Type " + key.getType() + " unrecognized");
  }
}
