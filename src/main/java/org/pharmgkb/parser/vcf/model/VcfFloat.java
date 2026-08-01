package org.pharmgkb.parser.vcf.model;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import com.google.errorprone.annotations.Immutable;
import com.google.errorprone.annotations.RestrictedApi;
import org.jspecify.annotations.Nullable;
import org.pharmgkb.parser.vcf.VcfFormatException;
import org.pharmgkb.parser.vcf.VcfWarnings;


/**
 * A VCF {@code Float} value: either a finite decimal number, or one of the IEEE-754-style special values NaN,
 * {@code +Infinity}, or {@code -Infinity}.
 * <p>
 * VCFv4.2 does not define {@code Float}'s literal grammar at all (it's only named as a type). VCFv4.3.tex line 100
 * clarifies it (the earliest version to do so -- the identical sentence carries through unchanged to
 * VCFv4.4.tex line 100 and VCFv4.5.tex line 98): a value matches either a normal decimal number, or
 * (case-insensitively) {@code NAN}, {@code INF}, or {@code INFINITY} with an optional leading sign. A plain
 * {@link BigDecimal} cannot represent the special values at all, so this class holds either a {@link BigDecimal}
 * or one of {@link Special}'s markers.
 *
 * @author Douglas Myers-Turnbull
 */
@Immutable
public final class VcfFloat {

  // keep in sync with InternalUtils.RESTRICTED_API_EXPLANATION (this string is intentionally duplicated,
  // not shared, since sharing it would require making InternalUtils's copy public, defeating its own purpose)
  private static final String RESTRICTED_API_EXPLANATION =
      "Internal helper; public only because Java requires it for cross-package use within this library. Not "
          + "part of this library's supported public API and may change or be removed without notice.";

  private static final Pattern SPECIAL_PATTERN = Pattern.compile("[-+]?(?:INF|INFINITY|NAN)", Pattern.CASE_INSENSITIVE);
  // VCFv4.3.tex line 100's non-special-case Float grammar (see this class's own Javadoc for why VCFv4.3, not
  // VCFv4.5, is cited). BigDecimal's own string grammar is looser than this -- e.g. it accepts "5." (a trailing
  // decimal point with no digits after it), which this pattern rejects -- so parseDecimal() below warns rather
  // than rejecting when BigDecimal accepts a value this pattern doesn't match, per this project's permissive
  // warn-and-preserve parser policy.
  private static final Pattern FLOAT_PATTERN = Pattern.compile("[-+]?[0-9]*\\.?[0-9]+([eE][-+]?[0-9]+)?");

  public enum Special {
    NAN,
    POSITIVE_INFINITY,
    NEGATIVE_INFINITY
  }

  private final @Nullable BigDecimal m_value;
  private final @Nullable Special m_special;

  private VcfFloat(@Nullable BigDecimal value, @Nullable Special special) {
    m_value = value;
    m_special = special;
  }

  public static VcfFloat of(BigDecimal value) {
    // value is typed non-@Nullable, but nothing enforces that at runtime; without this, of(null) would
    // silently build an instance with both m_value and m_special null -- a state isSpecial()/getValue() can't
    // represent -- and the raw NullPointerException would only surface later, in toString()
    if (value == null) {
      throw new VcfFormatException("Cannot create a VcfFloat from a null value");
    }
    return new VcfFloat(value, null);
  }

  public static VcfFloat ofSpecial(Special special) {
    if (special == null) {
      throw new VcfFormatException("Cannot create a VcfFloat from a null special value");
    }
    return new VcfFloat(null, special);
  }

  /**
   * Parses a VCF {@code Float} value, accepting {@code NAN}/{@code INF}/{@code INFINITY} (case-insensitive,
   * optionally signed) per VCFv4.3.tex line 100's clarification of the {@code Float} grammar (see this class's
   * own Javadoc for why VCFv4.3, not VCFv4.5, is cited as the source), in addition to a normal decimal number.
   *
   * @throws VcfFormatException if {@code value} is neither a normal decimal number nor one of the special values
   */
  public static VcfFloat parse(String value) {
    return parse(value, VcfWarnings.DEFAULT);
  }

  /**
   * Parses a VCF {@code Float} value, accepting {@code NAN}/{@code INF}/{@code INFINITY} (case-insensitive,
   * optionally signed) per VCFv4.3.tex line 100's clarification of the {@code Float} grammar (see this class's
   * own Javadoc for why VCFv4.3, not VCFv4.5, is cited as the source), in addition to a normal decimal number.
   * Routes grammar-mismatch warnings to {@code warnings} instead of the shared SLF4J logger.
   * <p>Public only because {@link org.pharmgkb.parser.vcf.InternalUtils} needs cross-package access; not part of
   * this library's supported public API.
   *
   * @throws VcfFormatException if {@code value} is neither a normal decimal number nor one of the special values
   * @hidden
   */
  @RestrictedApi(explanation = RESTRICTED_API_EXPLANATION)
  public static VcfFloat parse(String value, VcfWarnings warnings) {
    if (value == null) {
      throw new VcfFormatException("Cannot parse a null Float value");
    }
    if (SPECIAL_PATTERN.matcher(value).matches()) {
      if (value.toUpperCase(Locale.ROOT).endsWith("NAN")) {
        return ofSpecial(Special.NAN);
      }
      return ofSpecial(value.startsWith("-") ? Special.NEGATIVE_INFINITY : Special.POSITIVE_INFINITY);
    }
    try {
      return of(parseDecimal("Float", value, warnings));
    } catch (NumberFormatException e) {
      throw new VcfFormatException("Expected float; got " + value);
    }
  }

  /**
   * Parses {@code value} as a plain (non-special) decimal number, warning -- via {@code fieldName} in the
   * message, matching {@link org.pharmgkb.parser.vcf.InternalUtils#dropEmptyEntries}'s established
   * shared-warning-with-a-field-name-parameter convention -- rather than rejecting if {@code value} doesn't
   * match VCFv4.3.tex line 100's Float grammar but {@link BigDecimal} accepts it anyway (see {@link
   * #FLOAT_PATTERN}'s own comment for why, e.g. {@code "5."}). Package-private: shared by this class's own
   * {@link #parse} and {@link VcfPosition#getQuality()}, which need the
   * identical grammar check but different unparseable-input handling ({@link #parse} throws {@link
   * VcfFormatException}; {@code getQuality()} warns and falls back to the missing value) -- left as a bare
   * {@link NumberFormatException} here so each caller applies its own policy instead of this method picking
   * one for both.
   *
   * @throws NumberFormatException if {@code value} is not a valid decimal number at all
   */
  static BigDecimal parseDecimal(String fieldName, String value, VcfWarnings warnings) {
    BigDecimal decimal = new BigDecimal(value);
    if (!FLOAT_PATTERN.matcher(value).matches()) {
      warnings.warn("{} value '{}' does not match the VCF Float grammar (VCFv4.3.tex line 100); " +
          "accepting it anyway", fieldName, value);
    }
    return decimal;
  }

  public boolean isSpecial() {
    return m_special != null;
  }

  /**
   * @return the special value this represents, or {@code null} if this is a normal finite number
   */
  @Nullable
  public Special getSpecial() {
    return m_special;
  }

  /**
   * @return the finite decimal value, or {@code null} if this represents a special value (NaN/Infinity)
   */
  @Nullable
  public BigDecimal getValue() {
    return m_value;
  }

  @Override
  public String toString() {
    if (m_special != null) {
      return switch (m_special) {
        case NAN -> "NaN";
        case POSITIVE_INFINITY -> "INFINITY";
        case NEGATIVE_INFINITY -> "-INFINITY";
      };
    }
    assert m_value != null;
    return m_value.toString();
  }

  /**
   * For two non-special values, this delegates to {@link BigDecimal#equals}, which is scale-sensitive:
   * {@code VcfFloat.parse("1.5")} and {@code VcfFloat.parse("1.50")} are numerically equal but not
   * {@code equals()} to each other. Use {@code getValue().compareTo(...)} for scale-insensitive numeric
   * comparison of the non-special case.
   */
  @Override
  public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof VcfFloat vcfFloat)) {
      return false;
    }
    return m_special == vcfFloat.m_special &&
        (m_special != null || Objects.equals(m_value, vcfFloat.m_value));
  }

  /**
   * Scale-sensitive, matching {@link #equals}; see its Javadoc.
   */
  @Override
  public int hashCode() {
    return m_special != null ? Objects.hash(m_special) : Objects.hashCode(m_value);
  }
}
