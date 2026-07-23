package org.pharmgkb.parser.vcf.model;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.regex.Pattern;
import com.google.errorprone.annotations.Immutable;
import org.jspecify.annotations.Nullable;
import org.pharmgkb.parser.vcf.VcfFormatException;


/**
 * A VCF {@code Float} value: either a finite decimal number, or one of the IEEE-754-style special values NaN,
 * {@code +Infinity}, or {@code -Infinity}.
 * <p>
 * VCFv4.2 does not define {@code Float}'s literal grammar at all (it's only named as a type). VCFv4.5 clarifies it:
 * a value matches either a normal decimal number, or (case-insensitively) {@code NAN}, {@code INF}, or
 * {@code INFINITY} with an optional leading sign. A plain {@link BigDecimal} cannot represent the special values at
 * all, so this class holds either a {@link BigDecimal} or one of {@link Special}'s markers.
 *
 * @author Douglas Myers-Turnbull
 */
@Immutable
public final class VcfFloat {

  private static final Pattern SPECIAL_PATTERN = Pattern.compile("[-+]?(?:INF|INFINITY|NAN)", Pattern.CASE_INSENSITIVE);

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
    return new VcfFloat(value, null);
  }

  public static VcfFloat ofSpecial(Special special) {
    return new VcfFloat(null, special);
  }

  /**
   * Parses a VCF {@code Float} value, accepting {@code NAN}/{@code INF}/{@code INFINITY} (case-insensitive,
   * optionally signed) per VCFv4.5's clarification of the {@code Float} grammar, in addition to a normal decimal
   * number.
   *
   * @throws VcfFormatException if {@code value} is neither a normal decimal number nor one of the special values
   */
  public static VcfFloat parse(String value) {
    if (SPECIAL_PATTERN.matcher(value).matches()) {
      if (value.toUpperCase().endsWith("NAN")) {
        return ofSpecial(Special.NAN);
      }
      return ofSpecial(value.startsWith("-") ? Special.NEGATIVE_INFINITY : Special.POSITIVE_INFINITY);
    }
    try {
      return of(new BigDecimal(value));
    } catch (NumberFormatException e) {
      throw new VcfFormatException("Expected float; got " + value);
    }
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

  @Override
  public int hashCode() {
    return m_special != null ? Objects.hash(m_special) : Objects.hashCode(m_value);
  }
}
