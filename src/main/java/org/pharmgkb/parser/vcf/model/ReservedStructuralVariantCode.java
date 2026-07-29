package org.pharmgkb.parser.vcf.model;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
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

  private static final Map<String, ReservedStructuralVariantCode> BY_ID = Arrays.stream(values())
      .collect(Collectors.toUnmodifiableMap(ReservedStructuralVariantCode::getId, Function.identity()));

  @Nullable
  public static ReservedStructuralVariantCode fromId(@Nullable String id) {
    return id == null ? null : BY_ID.get(id);
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
