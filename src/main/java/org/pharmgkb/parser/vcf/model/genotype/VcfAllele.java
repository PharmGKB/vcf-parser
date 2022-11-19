package org.pharmgkb.parser.vcf.model.genotype;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import org.pharmgkb.parser.vcf.VcfFormatException;
import org.pharmgkb.parser.vcf.VcfUtils;

/**
 * An Allele matching the VCF 4.2 specification for the ALT and REF columns.
 * For example:
 * <ul>
 *     <li>ATgggcN</li>
 *     <li>&lt;IDxx&gt;</li>
 *     <li>]34]ATGC</li>
 *     <li>]34:55]&lt;IDxx&gt;</li>
 * </ul>
 * @author Douglas Myers-Turnbull
 */
@Immutable
public class VcfAllele {

  private final String m_string;

  /**
   * @param string A string following the VCF specification for the REF or ALT columns
   */
  public VcfAllele(@Nonnull String string) {
    if (!VcfUtils.ALT_BASE_PATTERN.matcher(string).matches()) {
      throw new VcfFormatException(string + " does not look like an allele");
    }
    m_string = string;
  }

  /**
   * @return The allele string from the constructor
   */
  @Nonnull
  @Override
  public String toString() {
    return m_string;
  }

  /**
   * <strong>Before calling, verify that this VcfAllele is not a breakpoint or a symbolic name.</strong>
   * @return The number of bases in this Allele
   * @throws VcfFormatException If this Allele is a breakpoint or symbolic name
   */
  public int length() throws IllegalArgumentException {
    if (isSymbolic() || isBreakpoint() || isDeleted()) {
      throw new VcfFormatException("Length could not be determined because the allele '" + m_string + "' is " +
          "symbolic, deleted upstream, or a breakpoint");
    }
    return m_string.length();
  }

  /**
   * @return Whether this Allele is defined using breakpoints (see the VCF spec)
   */
  public boolean isBreakpoint() {
    return m_string.contains("[") || m_string.contains("]");
  }

  /**
   * @return Whether this Allele contains a symbolic name like &lt;IDxx&gt;
   */
  public boolean isSymbolic() {
    return m_string.contains("<");
  }

  /**
   * @return Whether this Allele contains 'N' or 'n' as a base (does not include symbolic names)
   */
  public boolean isAmbigious() {
    return containsBase('N', 'n');
  }

  /**
   * @return Whether this Allele is '*', indicating a deletion upstream
   */
  public boolean isDeleted() {
    return m_string.equals("*");
  }

  /**
   * @return Whether this Allele is not symbolic, deleted, or a breakpoint
   */
  public boolean isSimple() {
    return !isBreakpoint() && !isSymbolic() && !isDeleted();
  }

  public PrimaryType getPrimaryType() {
    if (isBreakpoint()) {
      return PrimaryType.BREAKPOINT;
    }
    if (isDeleted()) {
      return PrimaryType.DELETED;
    }
    if (isSymbolic()) {
      return PrimaryType.SYMBOLIC;
    }
      int length = length();
      if (length == 0) {
        return PrimaryType.NO_VARIATION;
      }
      if (length == 1) {
        return PrimaryType.SINGLE_BASE;
      }
      return PrimaryType.MULTI_BASE;
  }

  /**
   * @return A string where every base is in lowercase; symbolic names (e.g. &lt;IDxx&gt;) will not be lowercased
   */
  @Nonnull
  public String withLowercaseBases() {

    if (!isSymbolic()) {
      return m_string.toLowerCase();
    }

    // if it's symbolic, we have to deal with case-sensitivity in (e.g.) <IDxx>
    // we don't care whether it's a breakpoint, though
    boolean isInside = false;
    char[] lowercase = new char[m_string.length()];
    for (int i = 0; i < m_string.length(); i++) {
      char c = m_string.charAt(i);
      if (c == '<') {
        isInside = true;
      } else if (c == '>') {
        isInside = false;
      }
      if (isInside) {
        lowercase[i] = c;
      } else {
        lowercase[i] = Character.toLowerCase(c);
      }
    }
    return new String(lowercase);
  }

  public boolean equalsIgnoreCase(@Nullable VcfAllele allele) {
    return allele != null && withLowercaseBases().equals(allele.withLowercaseBases());
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    VcfAllele allele = (VcfAllele) o;
    return m_string.equals(allele.m_string);
  }

  @Override
  public int hashCode() {
    return m_string.hashCode();
  }

  /**
   * @return Whether this Allele contains one or more of the characters (case-sensitive) in {@code bases},
   * restricted to bases (i.e. symbolic names like &lt;IDxx&gt; are excluded)
   */
  public boolean containsBase(@Nonnull char... bases) {
    boolean isInside = false;
    for (char c : m_string.toCharArray()) {
      if (c == '<') {
        isInside = true;
      } else if (c == '>') {
        isInside = false;
      } else if (!isInside) {
        for (char base : bases) {
          if (c == base) {
            return true;
          }
        }
      }
    }
    return false;
  }

  public enum PrimaryType {
    SINGLE_BASE, MULTI_BASE, SYMBOLIC, BREAKPOINT, DELETED, NO_VARIATION
  }

}
