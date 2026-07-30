package org.pharmgkb.parser.vcf;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.jspecify.annotations.Nullable;
import org.pharmgkb.parser.vcf.model.FormatType;
import org.pharmgkb.parser.vcf.model.InfoType;
import org.pharmgkb.parser.vcf.model.ReservedFormatProperty;
import org.pharmgkb.parser.vcf.model.ReservedProperty;
import org.pharmgkb.parser.vcf.model.VcfFloat;


/**
 * Contains static methods for handling properties in INFO and FORMAT fields.
 * @author Douglas Myers-Turnbull
 */
public class VcfUtils {

  // The "+" below deliberately allows repeating/concatenating the nucleotides-or-symbolic-ID alternative (e.g.
  // "C<ctg1>"), not just one instance of either. This is confirmed necessary for VCFv4.2's own "Large Insertions"
  // shorthand ("13 321682 INS0 T C<ctg1> ... SVTYPE=INS", a base run directly followed by a symbolic ID with no
  // breakend bracket between them) -- see VcfUtilsTest#testAltBasePatternAcceptsLargeInsertionShorthand, which
  // exists specifically because a prior review round mistakenly "fixed" this as over-permissive and rejected it.
  //
  // The "+" is broader than that one confirmed case requires, though: it also accepts forms with no known
  // precedent either way, such as "AC<ctg1>GT" (bases before AND after a symbolic ID) or "<ctg1><ctg2>" (multiple
  // concatenated symbolic IDs). Checked and found no evidence to justify tightening this further:
  //   - VCFv4.2/4.3/4.4/4.5 spec text: silent: no example or grammar statement covers these forms either way.
  //   - hts-specs' own conformance test suite (test/vcf/{4.1,4.2,4.3}/{passed,failed}/*.vcf): zero test files
  //     reference "ctg" at all, so this exact question has no official pass/fail fixture in either direction.
  //   - EBI vcf_validator 0.10.2 (github.com/ebivariation/vcf-validator): rejects ALL of "C<ctg1>", "AC<ctg1>GT",
  //     and "<ctg1><ctg2>" with the identical generic error, so it can't distinguish the confirmed-valid case from
  //     the unconfirmed ones.
  //   - bcftools 1.23.1: accepts all of the above -- but also accepts outright invalid ALT text like "XYZ123!!!"
  //     with no complaint, so its acceptance carries no evidentiary weight here; it does not validate ALT syntax.
  // Given no source can confirm or deny the broader forms, and a previous attempt to narrow this regex incorrectly
  // broke the one form that IS confirmed valid, this is intentionally left as-is rather than risked again.
  private static final String sf_simpleAltPattern =
      "(?:"                    + // wrap the whole expression
        "(?:"                  + // allow nucleotides, symbolic IDs, or both
          "(?:[AaCcGgTtNn]++)" + // nucleotides -- possessive quantifier: without it, this is nested inside the
                                 // outer "+" below, the classic (x+)+ catastrophic-backtracking shape (verified:
                                 // O(N^2) blowup on a long nucleotide run followed by one invalid trailing
                                 // character). Safe to make possessive because a run of [AaCcGgTtNn] never needs
                                 // to backtrack into the sibling alternative below (symbolic IDs always start
                                 // with '<', which is never a nucleotide character)
          "|(?:<[^\\s,<>]+>)"  + // symbolic IDs (no whitespace, commas, or angle brackets inside)
          ")+"                 + // see the note above for why this repeats
        "|\\*"                 + // indicates that the position doesn't exist due to an upstream deletion
      ")";

  // the mate breakend location "chr:pos" (VCF 4.2: "a string of the form `chr:pos'"): chr and pos are both always
  // required together -- there is no valid breakend form that gives a chromosome without a position (a truly
  // mate-less breakend uses the unrelated "G." / ".A" single-breakend shorthand, matched elsewhere in
  // ALT_BASE_PATTERN, not this pattern). chr may be any non-symbolic CHROM-like value, not just digits: the spec's
  // own examples happen to use only numeric chromosome names (plus one symbolic contig ID), which is why this was
  // wrongly restricted to \d+ before and never caught by tests built from those same examples.
  private static final String sf_mateLocation =
      "(?:"                                     + // wrap the whole expression
        "(?:[^\\s,\\[\\]:]+|(?:<[^\\s,<>]+>))"   + // chromosome: CHROM-like (no whitespace, comma, brackets, or colon), or a symbolic ID
        ":\\d+"                                  + // position (required)
      ")";

  // The breakend replacement string "t" is mandatory -- never zero-length -- but VCFv4.2's own "Telomeres" section
  // (e.g. "1 0 bnd_X N .[13:123457[") uses a literal "." as t itself, for a virtual telomeric breakend with no
  // real reference sequence. So t is bases/symbolic-ID/"*" (sf_simpleAltPattern) OR literally ".", never absent.
  // hts-specs' own conformance suite confirms the "absent" case is invalid: test/vcf/4.2/failed/failed_body_alt_005.vcf,
  // CauseOfFailure="Breakend without bases at either side", ALT="[1:100[" (t truly missing on both sides, not
  // even a "."). Every passing breakend example (test/vcf/4.1/passed/passed_body_alt.vcf) has content on exactly
  // one side (e.g. "]1:100]AGT", "[1:100[AGT", "AGT]1:100]", "AGT[1:100["), never zero.
  private static final String sf_breakendReplacement = "(?:" + sf_simpleAltPattern + "|\\.)";

  // No outer optional dot wraps these breakpoint types: t (sf_breakendReplacement) already accepts a literal "."
  // itself for the telomere case, so an outer wrapper would let a stray extra dot sit beside t with no basis in
  // any spec example (e.g. "..[1:1[" or "A]1:1]." would wrongly match). Confirmed live that removing it does not
  // affect any of this class's existing breakend test cases, all of which are covered by t accepting "." directly.
  private static final Pattern sf_breakpointAltPattern = Pattern.compile(
      "(?:"                                                         + // start breakpoint types
        "(?:" + sf_breakendReplacement + "\\[" + sf_mateLocation + "\\[)"  + // breakpoint type 1: t[p[
        "|(?:" + sf_breakendReplacement + "]" + sf_mateLocation + "])" + // breakpoint type 2: t]p]
        "|(?:\\]" + sf_mateLocation + "]" + sf_breakendReplacement + ")" + // breakpoint type 3: ]p]t
        "|(?:\\[" + sf_mateLocation + "\\[" + sf_breakendReplacement + ")" + // breakpoint type 4: [p[t
      ")"                                                             // end breakpoint types
  );

  public static final Pattern ALT_BASE_PATTERN = Pattern.compile(
      "\\.|" +                                 // means no variant
      "(?:\\.?" + sf_simpleAltPattern + ")"  + // ex: .A
      "|(?:" + sf_simpleAltPattern + "\\.?)" + // ex: A.
      "|" + sf_breakpointAltPattern            // ex: C[2[
  );

  public static final Pattern REF_BASE_PATTERN = Pattern.compile("[AaCcGgTtNn]+");
  // VCFv4.2 (this project's target spec) does not table a formal FORMAT-key grammar -- it only describes the
  // FORMAT field informally as a "colon-separated alphanumeric String" (VCFv4.2.tex, Genotype fields
  // subsection). VCFv4.3.tex gives this exact regex verbatim, unchanged in VCFv4.4/4.5: "colon-separated FORMAT
  // keys matching the regular expression ^[A-Za-z_][0-9A-Za-z_.]*$, duplicate keys are not allowed"
  // (VCFv4.3.tex:429; repeated in the VCFv4.2→4.3 changelog at VCFv4.3.tex:2150). Used here as VCFv4.2-silent
  // clarifying guidance per AGENTS.md. Not reused for INFO keys, which VCFv4.3.tex:368 gives a different regex
  // with a "1000G" legacy exception -- INFO keys are intentionally left unvalidated against any ID pattern here,
  // per this project's parser leniency policy for INFO.
  public static final Pattern FORMAT_PATTERN = Pattern.compile("^[A-Za-z_][0-9A-Za-z_.]*$");
  public static final Pattern RSID_PATTERN = Pattern.compile("rs\\d+");
  public static final Pattern NUMBER_PATTERN = Pattern.compile("(?:\\d+|[ARG.])");

  // VCF 4.x only; also rejects malformed versions like VCFv4..2
  public static final Pattern FILE_FORMAT_PATTERN = Pattern.compile("VCFv4\\.\\d+");

  private static final Pattern sf_whitespacePattern = Pattern.compile("\\s");

  /**
   * Matched with {@code find()}, not {@code matches()}: a wrapping {@code ".*\s.*"} pattern would fail to
   * detect whitespace in a string containing 2+ line-terminator characters, since {@code "."} does not match
   * line terminators without {@code DOTALL}. ASCII-only ({@code \s}'s default meaning without
   * {@code UNICODE_CHARACTER_CLASS}) -- does not detect non-ASCII Unicode whitespace.
   */
  public static boolean containsWhitespace(String s) {
    return sf_whitespacePattern.matcher(s).find();
  }

  public static Map<String, String> extractPropertiesFromLine(String value) {
    if (hasUnbalancedQuotes(value)) {
      // reproduces test/vcf/{4.1,4.2}/failed/failed_meta_001.vcf (##CauseOfFailure=Description contains
      // non-escaped quotes). Checked on the raw, unsplit string rather than per-attribute after splitTopLevel:
      // an unescaped quote can make splitTopLevel itself mis-split at the wrong comma (its open/close toggle
      // flips on every unescaped '"', so a stray one can make a comma meant to stay inside a value look
      // top-level), so checking post-split could miss or misattribute the very problem being detected here.
      VcfWarnings.warn("Metadata declaration attribute list \"{}\" contains an unescaped quote, making attribute " +
          "boundaries ambiguous; parsing it best-effort", value);
    }
    // split on top-level commas (those not inside a double-quoted value); splitTopLevel preserves escaped characters
    return extractProperties(splitTopLevel(value, ',').toArray(new String[0]));
  }

  /**
   * @return Whether {@code value} has an odd number of unescaped {@code "} characters. An odd count means
   * {@link #splitTopLevel} would finish with its internal {@code inQuotes} toggle left {@code true}, guaranteeing
   * some quote is unmatched and making attribute boundaries ambiguous. An even count does not guarantee all quotes
   * are correctly paired (e.g., two separate stray quotes in different attributes can cancel out to an even total);
   * this is a best-effort heuristic, not a per-attribute validation. Respects the same {@code \\}/{@code \"} escape
   * convention as {@link #splitTopLevel} and {@link #unquote}, so a properly-escaped inner quote is not miscounted.
   */
  private static boolean hasUnbalancedQuotes(String value) {
    int unescapedQuoteCount = 0;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '\\' && i + 1 < value.length() && (value.charAt(i + 1) == '\\' || value.charAt(i + 1) == '"')) {
        i++; // skip the escaped character, matching splitTopLevel's own escape handling
      } else if (c == '"') {
        unescapedQuoteCount++;
      }
    }
    return unescapedQuoteCount % 2 != 0;
  }

  public static Map<String, String> extractProperties(String... props) {
    Map<String, String> map = new LinkedHashMap<>();
    for (String prop : props) {
      Pair<String, String> pair;
      try {
        pair = splitProperty(prop);
      } catch (RuntimeException e) {
        throw new VcfFormatException("Error parsing property \"" + prop + "\"", e);
      }
      if (map.containsKey(pair.getKey())) {
        // Keeping the LAST value here is deliberate and differs from every other duplicate-key case in this
        // codebase (which keep the first) -- this handles a duplicate ATTRIBUTE within one metadata declaration's
        // own <ID=...,Number=...,...> structure, a different scenario from a duplicate declaration ID across
        // separate lines or a duplicate INFO/FORMAT key within a data record. hts-specs' test/vcf/{4.1,4.2,4.3}
        // conformance suite has no fixture for a repeated attribute within one declaration. Confirmed live against
        // bcftools 1.23.1: declaring Type twice (e.g. Type=Integer,...,Type=String) with a value only valid under
        // one of the two, then forcing conversion to binary BCF (which enforces the declared type, unlike
        // plain-text VCF), shows bcftools always resolves to the LAST-declared value regardless of which type came
        // first -- e.g. Type=String declared last preserves a non-numeric value as-is; Type=Integer declared last
        // silently coerces the same non-numeric value to the missing sentinel. vcf_validator 0.10.2 rejects this
        // case outright, but per this project's tie-breaker hierarchy its stricter structural-field behavior is
        // non-authoritative when it disagrees with bcftools.
        VcfWarnings.warn("Metadata declaration contains duplicate attribute \"{}\"; keeping the last value", pair.getKey());
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
   * Rejects a key or value containing a line terminator: such a property would corrupt the single-line structure of a
   * written {@code ##} metadata line or data line (see {@link VcfWriter}), so this should be checked at the point of
   * construction or mutation rather than deferred to write time.
   *
   * @param value if there is no value to check (e.g. a single-string property with no separate key), pass the same
   *              string as both {@code key} and {@code value}
   */
  public static void checkNoLineTerminator(String key, @Nullable String value) {
    if (key.contains("\n") || key.contains("\r") || (value != null && (value.contains("\n") || value.contains("\r")))) {
      throw new VcfFormatException("Property [[[" + key + "=" + value + "]]] contains a line terminator");
    }
  }

  private static final Set<String> RESERVED_METADATA_KEYS =
      Set.of("fileformat", "ALT", "FILTER", "INFO", "FORMAT", "contig", "SAMPLE", "PEDIGREE");

  /**
   * @return true if {@code name} is one of the structured metadata declaration keywords (e.g. "INFO",
   * "FORMAT") that {@link VcfParser#parseMetadata} always routes to its own structured-parsing path -- a raw
   * property using one of these names could never be re-read as what it was written, since reparsing would
   * route it through the wrong path entirely
   */
  public static boolean isReservedMetadataKey(@Nullable String name) {
    return name != null && RESERVED_METADATA_KEYS.contains(name);
  }

  /**
   * Warns about and drops any empty string in {@code list}, for a position-independent delimited field (e.g. ID,
   * FILTER) where an empty entry has nothing salvageable. See {@code EMPTY_FIELD_HANDLING.md} for the rationale.
   *
   * @return {@code list} unchanged if it contained no empty entries, or a new list with them removed
   */
  public static List<String> dropEmptyEntries(String fieldName, List<String> list) {
    List<String> result = list;
    for (int i = 0; i < result.size(); i++) {
      if (result.get(i).isEmpty()) {
        if (result == list) {
          result = new ArrayList<>(list);
        }
        VcfWarnings.warn("{} contains an empty entry (VCF does not allow zero-length fields); dropping it", fieldName);
        result.remove(i);
        i--;
      }
    }
    return result;
  }

  /**
   * Warns about and replaces, in place, any empty string in {@code list} with {@code "."} (the VCF missing-value
   * sentinel), for a position-dependent delimited field (e.g. a sample's FORMAT-keyed values) where dropping an
   * entry would misalign it with its corresponding key. See {@code EMPTY_FIELD_HANDLING.md} for the rationale.
   */
  public static void fillEmptyEntriesWithDot(String fieldName, List<String> list) {
    for (int i = 0; i < list.size(); i++) {
      if (list.get(i).isEmpty()) {
        VcfWarnings.warn("{} contains an empty entry (VCF does not allow zero-length fields); treating it as the " +
            "missing value '.'", fieldName);
        list.set(i, ".");
      }
    }
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
    // FT/PS are FORMAT-only reserved IDs (see checkReservedFormatConstraints's Javadoc) -- a future
    // ReservedInfoProperty whose ID happens to collide with one must not get FORMAT-only semantics applied
    String normalized = key instanceof ReservedFormatProperty ? checkReservedFormatConstraints(key.getId(), value)
        : value;
    return convertProperty(key.getType(), normalized, key.isList());
  }

  /**
   * Warns if {@code value} violates a constraint that VCFv4.2 places on a reserved FORMAT key beyond its declared
   * {@code Type} -- constraints that generic {@code Type}-based conversion can't express. Currently:
   * <ul>
   *   <li>{@link ReservedFormatProperty#Filter FT} must be {@code PASS}, {@code .}, or a semicolon-separated list of
   *   codes, each containing no whitespace, and {@code PASS}/{@code .} may not be combined with other codes -- the
   *   same grammar as the FILTER column.</li>
   *   <li>{@link ReservedFormatProperty#PhaseSet PS} must be a non-negative 32-bit integer.</li>
   * </ul>
   *
   * @return the value to use for subsequent type conversion: unchanged, except for FT, whose empty
   * semicolon-separated codes are dropped -- the same "warn and drop" convention already used for an empty entry in
   * FILTER (FT's grammar is explicitly modeled on FILTER's) and every other position-independent list in this
   * library. A caller that only wants the validation side effect (e.g. a writer in diagnostics mode, which preserves
   * raw content rather than rewriting it) may discard the return value.
   */
  static @Nullable String checkReservedFormatConstraints(String key, @Nullable String value) {
    if (value == null || value.equals(".")) {
      return value;
    }
    if (key.equals(ReservedFormatProperty.Filter.getId())) {
      return checkFilterLikeValue(key, value);
    } else if (key.equals(ReservedFormatProperty.PhaseSet.getId())) {
      checkNonNegative32BitInteger(key, value);
    }
    return value;
  }

  private static String checkFilterLikeValue(String key, String value) {
    String[] codes = value.split(";", -1);
    List<String> kept = new ArrayList<>(codes.length);
    boolean hasPassOrMissing = false;
    for (String code : codes) {
      if (code.isEmpty()) {
        VcfWarnings.warn("FORMAT {} value \"{}\" contains an empty filter code (VCF does not allow zero-length " +
            "fields); dropping it", key, value);
        continue;
      }
      if (containsWhitespace(code)) {
        VcfWarnings.warn("FORMAT {} value \"{}\" contains whitespace in filter code \"{}\"", key, value, code);
      }
      if (code.equals("PASS") || code.equals(".")) {
        hasPassOrMissing = true;
      }
      kept.add(code);
    }
    if (kept.size() > 1 && hasPassOrMissing) {
      VcfWarnings.warn("FORMAT {} value \"{}\" combines PASS or '.' with other filter codes", key, value);
    }
    return String.join(";", kept);
  }

  private static void checkNonNegative32BitInteger(String key, String value) {
    long parsed;
    try {
      parsed = Long.parseLong(value);
    } catch (NumberFormatException e) {
      // not a number at all; the generic Long conversion reports this on its own
      return;
    }
    if (parsed < 0 || parsed > Integer.MAX_VALUE) {
      VcfWarnings.warn("FORMAT {} value \"{}\" is not a non-negative 32-bit integer", key, value);
    }
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
      // an unchecked cast to this method's own unbounded generic type parameter T is erased to a no-op by javac
      // (no checkcast bytecode is emitted), so it can never throw ClassCastException here -- a real type mismatch
      // only surfaces later, in the caller's own stack frame when the value is used as the wrong type
      return (T) convertElement(clas, value);
    }
    List<Object> list = new ArrayList<>();
    // limit -1 preserves an empty entry (e.g. a trailing ','), handled below rather than silently dropped; VCF does
    // not allow zero-length fields. See EMPTY_FIELD_HANDLING.md.
    for (String part : value.split(",", -1)) {
      if (part.isEmpty()) {
        VcfWarnings.warn("List value \"{}\" contains an empty entry (VCF does not allow zero-length fields); " +
            "treating it as the missing value '.'", value);
        list.add(null);
      } else {
        list.add(convertElement(clas, part));
      }
    }
    return (T) list;
  }

  public static Class<?> toJavaType(FormatType type) {
    switch (type) {
      case Integer:
        return Long.class;
      case Float:
        return VcfFloat.class;
      case Character:
        return Character.class;
      case String:
        return String.class;
      default:
        throw new VcfFormatException(FormatType.class.getSimpleName() + " " + type + " isn't covered?!");
    }
  }

  public static @Nullable <T> T convertProperty(FormatType type, @Nullable String value) {
    return convertProperty(toJavaType(type), value, false);
  }

  public static Class<?> toJavaType(InfoType type) {
    switch (type) {
      case Integer:
        return Long.class;
      case Float:
        return VcfFloat.class;
      case Character:
        return Character.class;
      case String:
        return String.class;
      case Flag:
        return Boolean.class;
      default:
        throw new VcfFormatException(InfoType.class.getSimpleName() + " " + type + " isn't covered?!");
    }
  }

  public static @Nullable <T> T convertProperty(InfoType type, @Nullable String value) {
    return convertProperty(toJavaType(type), value, false);
  }

  private static @Nullable Object convertElement(Class<?> clas, @Nullable String value) {
    if (value == null || ".".equals(value)) {
      return null;
    }
    if (clas == String.class) {
      return value;
    } else if (clas == Character.class) {
      if (value.length() == 1) {
        return value.charAt(0);
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
    } else if (clas == VcfFloat.class) {
      // unlike plain BigDecimal, this also accepts NAN/INF/INFINITY (case-insensitive, optionally signed), which
      // VCFv4.2 leaves undefined but VCFv4.5 clarifies are valid Float literals
      return VcfFloat.parse(value);
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
