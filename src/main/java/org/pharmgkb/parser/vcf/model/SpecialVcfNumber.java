package org.pharmgkb.parser.vcf.model;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A reserved value for the "Number" field in INFO and FORMAT metadata entries.
 * @author Douglas Myers-Turnbull
 */
public enum SpecialVcfNumber {

  ONE_PER_ALT("A"),
  ONE_PER_ALT_OR_REF("R"),
  ONE_PER_GENOTYPE("G"),
  UNKNOWN_OR_UNBOUNDED(".");

  @Nullable
  public static SpecialVcfNumber fromId(@Nonnull String id) {
    switch(id) {
      case "A": return ONE_PER_ALT;
      case "R": return ONE_PER_ALT_OR_REF;
      case "G": return ONE_PER_GENOTYPE;
      case ".": return UNKNOWN_OR_UNBOUNDED;
    }
    return null;
  }

  private final String m_id;

  SpecialVcfNumber(String id) {
    m_id = id;
  }

  @Nonnull
  public String getId() {
    return m_id;
  }
}
