package org.pharmgkb.parser.vcf;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.pharmgkb.parser.vcf.model.FormatType;
import org.pharmgkb.parser.vcf.model.InfoType;
import org.pharmgkb.parser.vcf.model.ReservedProperty;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Contains static methods for handling properties in INFO and FORMAT fields.
 * @author Douglas Myers-Turnbull
 */
public class VcfUtils {

  private static final String sf_simpleAltPattern = "(?:[AaCcGgTtNn]+" +
      "|\\*" + // indicates that the position doesn't exist due to an upstream deletion
      "|<.+>)"; // symbolic alts for structural variants (declared in ALT metadata)

  private static final String sf_number = "(?:(?:\\d+|(?:<.+>))(?::\\d+)?)";

  public static final Pattern REF_BASE_PATTERN = Pattern.compile("[AaCcGgTtNn]+");
  public static final Pattern ALT_BASE_PATTERN = Pattern.compile("\\.?(?:" + // notice the optional opening dot
      "(?:[AaCcGgTtNn]+|\\*|<.+>)" + // simple
      "|(?:" + sf_simpleAltPattern + "\\[" + sf_number + "\\[)" + // breakpoint type 1
      "|(?:" + sf_simpleAltPattern + "\\]" + sf_number + "\\])" + // breakpoint type 2
      "|(?:\\]" + sf_number + "\\]" + sf_simpleAltPattern + ")" + // breakpoint type 3
      "|(?:\\[" + sf_number + "\\[" + sf_simpleAltPattern + ")" + // breakpoint type 4
      ")\\.?"); // notice the optional ending dot
  public static final Pattern METADATA_PATTERN = Pattern.compile(",(?=([^\"]*\"[^\"]*\")*[^\"]*$)");
  public static final Pattern FORMAT_PATTERN = Pattern.compile("[A-Z0-9:]+");
  public static final Pattern RSID_PATTERN = Pattern.compile("rs\\d+");
  public static final Pattern NUMBER_PATTERN = Pattern.compile("(?:\\d+|[\\.AaGgRr])");

  public static @Nonnull Map<String, String> extractProperties(@Nonnull String... props) {
    Map<String, String> map = new HashMap<>();
    for (String prop : props) {
      Pair<String, String> pair;
      try {
        pair = splitProperty(prop);
      } catch (RuntimeException e) {
        throw new IllegalArgumentException("Error parsing property \"" + prop + "\"", e);
      }
      map.put(pair.getKey(), pair.getValue());
    }
    return map;
  }

  /**
   * Splits a property into a key-value pair.
   * @param prop In the form "key=value"
   */
  public static @Nonnull Pair<String, String> splitProperty(@Nonnull String prop) {
    String[] parts = prop.split("=");
    if (parts.length != 2) {
      throw new IllegalArgumentException("There were " + (parts.length - 1) + " equals signs for: " + prop);
    }
    return Pair.of(parts[0], parts[1]);
  }

  /**
   * Adds double quotation marks around a string.
   */
  @Nonnull
  public static String quote(@Nonnull String string) {
    return "\"" + string + "\"";
  }

  /**
   * Removes double quotation marks around a string if they are present.
   */
  @Nonnull
  public static String unquote(@Nonnull String string) {
    if (string.startsWith("\"") && string.endsWith("\"")) {
      return string.substring(1, string.length() - 1);
    }
    return string;
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
  public static @Nullable <T> T convertProperty(@Nonnull Class<?> clas, @Nullable String value, boolean isList) {
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

  public static @Nullable <T> T convertProperty(@Nonnull FormatType type, @Nullable String value) {
    Class<?> clas;
    switch (type) {
      case Integer:
        clas = Long.class;
        break;
      case Float:
        clas = BigDecimal.class;
        break;
      case Character:
        clas = Character.class;
        break;
      case String:
        clas = String.class;
        break;
      default:
        throw new RuntimeException(FormatType.class.getSimpleName() + " " + type + " isn't covered?!");
    }
    return convertProperty(clas, value, false);
  }

  public static @Nullable <T> T convertProperty(@Nonnull InfoType type, @Nullable String value) {
    Class<?> clas;
    switch (type) {
      case Integer:
        clas = Long.class;
        break;
      case Float:
        clas = BigDecimal.class;
        break;
      case Character:
        clas = Character.class;
        break;
      case String:
        clas = String.class;
        break;
      case Flag:
        clas = Boolean.class;
      default:
        throw new RuntimeException(InfoType.class.getSimpleName() + " " + type + " isn't covered?!");
    }
    return convertProperty(clas, value, false);
  }

  private static @Nullable Object convertElement(@Nonnull Class<?> clas, @Nullable String value) {
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

  public static enum Quoted {
    True, False, Unknown;
  }

}
