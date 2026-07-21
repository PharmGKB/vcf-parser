package org.pharmgkb.parser.vcf.model;

import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;


/**
 * A reserved identifier for VCF ALT codes of structural variants.
 * Each reserved identifier has a {@link #getLevelInSpecification level}.
 * @see AltStructuralVariant
 */
public enum ReservedStructuralVariantCode {

  Deletion("DEL"),
  Insertion("INS"),
  Duplication("DUP"),
  Inversion("INV"),
  Cnv("CNV"),
  Tandem("TANDEM", 1, Duplication),
  MobileElement("ME", 1, Insertion, Deletion);

  private final String m_id;
  private final int m_level;
  private final List<ReservedStructuralVariantCode> m_parentCodes;

  @Nullable
  public static ReservedStructuralVariantCode fromId(String id) {
    switch(id) {
      case "DEL":
        return Deletion;
      case "INS":
        return Insertion;
      case "DUP":
        return Duplication;
      case "INV":
        return Inversion;
      case "TANDEM":
        return Tandem;
      case "ME":
        return MobileElement;
      default:
        return null;
    }
  }

  private ReservedStructuralVariantCode(String id) {
    this(id, 0);
  }

  private ReservedStructuralVariantCode(String id, int level,
      ReservedStructuralVariantCode... parentCodes) {
    m_id = id;
    m_level = level;
    m_parentCodes = Arrays.asList(parentCodes);
  }

  /**
   * @return The code (e.g. CNV)
   */
  public String getId() {
    return m_id;
  }

  public int getLevelInSpecification() {
    return m_level;
  }

  public List<ReservedStructuralVariantCode> getParentCodes() {
    return m_parentCodes;
  }
}
