package org.pharmgkb.parser.vcf.model;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A reserved value for the "Number" field in INFO metadata entries.
 * @author Douglas Myers-Turnbull
 */
public enum ReservedInfoNumber {

  ONE_PER_ALT("A"),
  ONE_PER_ALT_OR_REF("R"),
  ONE_PER_GENOTYPE("G"),
  UNKNOWN_OR_UNBOUNDED(".");

  @Nullable
  public static ReservedInfoNumber fromId(@Nonnull String id) {
    switch(id) {
      case "A": return ONE_PER_ALT;
      case "R": return ONE_PER_ALT_OR_REF;
      case "G": return ONE_PER_GENOTYPE;
      case ".": return UNKNOWN_OR_UNBOUNDED;
    }
    return null;
  }

  private final String m_id;

  ReservedInfoNumber(String id) {
    m_id = id;
  }

  public String getId() {
    return m_id;
  }
}
