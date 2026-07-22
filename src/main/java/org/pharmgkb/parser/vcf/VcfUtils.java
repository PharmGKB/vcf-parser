package org.pharmgkb.parser.vcf;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.jspecify.annotations.Nullable;
import org.pharmgkb.parser.vcf.model.FormatType;
import org.pharmgkb.parser.vcf.model.InfoType;
import org.pharmgkb.parser.vcf.model.ReservedProperty;


/**
 * Contains static methods for handling properties in INFO and FORMAT fields.
 * @author Douglas Myers-Turnbull
 */
public class VcfUtils {

  private static final String sf_simpleAltPattern =
      "(?:"                   + // wrap the whole expression
        "(?:"                 + // allow nucleotides, symbolic IDs, or both
          "(?:[AaCcGgTtNn]+)" + // nucleotides
          "|(?:<[^\\s,<>]+>)" + // symbolic IDs (no whitespace, commas, or angle brackets inside)
          ")+"                + // allow things like C<ctg1> (apparently)
        "|\\*"                + // indicates that the position doesn't exist due to an upstream deletion
      ")";

  private static final String sf_number =
      "(?:"                 + // wrap the whole expression
        "(?:\\d+|(?:<[^\\s,<>]+>))" + // numbers or symbolic IDs (no whitespace, commas, or angle brackets inside)
        "(?::\\d+)?"        + // optional insertion
      ")";                    // ends the nc group of the first line

  private static final Pattern sf_breakpointAltPattern = Pattern.compile(
      "(?:"                                                         + // wrap the whole expression
        "\\.?"                                                      + // optional opening dot
        "(?:"                                                       + // start breakpoint types
          "(?:" + sf_simpleAltPattern + "?\\[" + sf_number + "\\[)"  + // breakpoint type 1: t[p[
          "|(?:" + sf_simpleAltPattern + "?]" + sf_number + "])" + // breakpoint type 2: t]p]
          "|(?:\\]" + sf_number + "]" + sf_simpleAltPattern + "?)" + // breakpoint type 3: ]p]t
          "|(?:\\[" + sf_number + "\\[" + sf_simpleAltPattern + "?)" + // breakpoint type 4: [p[t
        ")"                                                         + // end breakpoint types
        "\\.?"                                                      + // optional closing dot
      ")"                                                             // ends the nc group of the first line
  );

  public static final Pattern ALT_BASE_PATTERN = Pattern.compile(
      "\\.|" +                                 // means no variant
      "(?:\\.?" + sf_simpleAltPattern + ")"  + // ex: .A
      "|(?:" + sf_simpleAltPattern + "\\.?)" + // ex: A.
      "|" + sf_breakpointAltPattern            // ex: C[2[
  );

  public static final Pattern REF_BASE_PATTERN = Pattern.compile("[AaCcGgTtNn]+");
  public static final Pattern FORMAT_PATTERN = Pattern.compile("^[A-Za-z_][0-9A-Za-z_.]*$");
  public static final Pattern RSID_PATTERN = Pattern.compile("rs\\d+");
  public static final Pattern NUMBER_PATTERN = Pattern.compile("(?:\\d+|[ARG.])");

  // VCFv<major>.<minor> with major >= 4 (VCF 4.0 is the supported floor); also rejects malformed versions like VCFv4..2
  public static final Pattern FILE_FORMAT_PATTERN = Pattern.compile("VCFv(?:[4-9]|[1-9]\\d+)\\.\\d+");

  public static Map<String, String> extractPropertiesFromLine(String value) {
    // split on top-level commas (those not inside a double-quoted value); splitTopLevel preserves escaped characters
    return extractProperties(splitTopLevel(value, ',').toArray(new String[0]));
  }

  public static Map<String, String> extractProperties(String... props) {
    Map<String, String> map = new HashMap<>();
    for (String prop : props) {
      Pair<String, String> pair;
      try {
        pair = splitProperty(prop);
      } catch (RuntimeException e) {
        throw new VcfFormatException("Error parsing property \"" + prop + "\"", e);
      }
      map.put(pair.getKey(), pair.getValue());
    }
    return map;
  }

  /**
   * Splits a property into a key-value pair.
   * @param prop In the form "key=value"
   */
  public static Pair<String, String> splitProperty(String prop) {
    List<String> parts = splitTopLevel(prop, '=');
    if (parts.size() != 2) {
      throw new VcfFormatException("There were " + (parts.size() - 1) + " equals signs for: " + prop);
    }
    return Pair.of(parts.get(0), parts.get(1));
  }

  /**
   * Splits {@code s} on top-level occurrences of {@code delim} — those not inside a double-quoted substring. Only
   * {@code \\} and {@code \"} are recognized escape sequences (per the VCF spec; matches {@link #unquote}): {@code \"}
   * does not toggle quoting and {@code \\} is a literal backslash, and both are preserved verbatim in the output. A
   * backslash before any other character (or at the end of the string) has no special meaning and does not consume or
   * protect the following character, so a delimiter or quote immediately after it is still recognized.
   */
  private static List<String> splitTopLevel(String s, char delim) {
    List<String> parts = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean inQuotes = false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\\' && i + 1 < s.length() && (s.charAt(i + 1) == '\\' || s.charAt(i + 1) == '"')) {
        cur.append(c).append(s.charAt(i + 1));
        i++;
      } else if (c == '"') {
        inQuotes = !inQuotes;
        cur.append(c);
      } else if (c == delim && !inQuotes) {
        parts.add(cur.toString());
        cur.setLength(0);
      } else {
        cur.append(c);
      }
    }
    parts.add(cur.toString());
    return parts;
  }

  /**
   * Adds double quotation marks around a string, escaping any embedded backslash or double-quote character (as the VCF
   * spec requires: {@code \} becomes {@code \\}, {@code "} becomes {@code \"}). Reverse with {@link #unquote}.
   */
  public static String quote(String string) {
    StringBuilder sb = new StringBuilder(string.length() + 2).append('"');
    for (int i = 0; i < string.length(); i++) {
      char c = string.charAt(i);
      if (c == '\\' || c == '"') {
        sb.append('\\');
      }
      sb.append(c);
    }
    return sb.append('"').toString();
  }

  /**
   * Removes double quotation marks around a string if they are present, decoding any escaped backslash ({@code \\}) or
   * double-quote ({@code \"}) inside. Reverse of {@link #quote}.
   */
  public static String unquote(String string) {
    if (string.length() < 2 || !string.startsWith("\"") || !string.endsWith("\"")) {
      return string;
    }
    String inner = string.substring(1, string.length() - 1);
    StringBuilder sb = new StringBuilder(inner.length());
    for (int i = 0; i < inner.length(); i++) {
      char c = inner.charAt(i);
      if (c == '\\' && i + 1 < inner.length() && (inner.charAt(i + 1) == '\\' || inner.charAt(i + 1) == '"')) {
        sb.append(inner.charAt(++i));
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
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
  public static @Nullable <T> T convertProperty(ReservedProperty key, @Nullable String value) {
   return convertProperty(key.getType(), value, key.isList());
  }

  /**
   * @see #convertProperty(ReservedProperty, String)
   */
  @SuppressWarnings("unchecked")
  public static @Nullable <T> T convertProperty(Class<?> clas, @Nullable String value, boolean isList) {
    if (value == null || ".".equals(value)) {
      return null;
    }
    if (!isList) {
      try {
        return (T) convertElement(clas, value);
      } catch (ClassCastException e) {
        throw new VcfFormatException("Wrong type specified", e);
      }
    }
    List<Object> list = new ArrayList<>();
    for (String part : value.split(",")) {
      list.add(convertElement(clas, part));
    }
    try {
      return (T) list;
    } catch (ClassCastException e) {
      throw new VcfFormatException("Wrong type specified", e);
    }
  }

  public static @Nullable <T> T convertProperty(FormatType type, @Nullable String value) {
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
        throw new VcfFormatException(FormatType.class.getSimpleName() + " " + type + " isn't covered?!");
    }
    return convertProperty(clas, value, false);
  }

  public static @Nullable <T> T convertProperty(InfoType type, @Nullable String value) {
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
        break;
      default:
        throw new VcfFormatException(InfoType.class.getSimpleName() + " " + type + " isn't covered?!");
    }
    return convertProperty(clas, value, false);
  }

  private static @Nullable Object convertElement(Class<?> clas, @Nullable String value) {
    if (value == null || ".".equals(value)) {
      return null;
    }
    if (clas == String.class) {
      return value;
    } else if (clas == Character.class) {
      if (value.length() == 1) {
        return value;
      } else {
        throw new VcfFormatException("Invalid character value '" + value + "'");
      }
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
      throw new VcfFormatException("Invalid boolean value: '" + value + "'");

    } else if (clas == BigDecimal.class) {
      try {
        return new BigDecimal(value);
      } catch (NumberFormatException e) {
        throw new VcfFormatException("Expected float; got " + value);
      }
    } else if (clas == Long.class) {
      try {
        return Long.parseLong(value);
      } catch (NumberFormatException e) {
        throw new VcfFormatException("Expected integer; got " + value);
      }
    }
    throw new VcfFormatException("Type " + clas + " unrecognized");
  }

}
