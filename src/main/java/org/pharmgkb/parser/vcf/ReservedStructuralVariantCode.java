package org.pharmgkb.parser.vcf;

import java.util.Arrays;
import java.util.Collections;
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
  // VCFv4.2.tex lines 107-111's top-level list is exactly DEL/INS/DUP/INV/CNV; VCFv4.3.tex line 208
  // (unchanged through 4.5) adds BND (Breakend) as a sixth. Accepted here even though the target spec
  // (4.2) doesn't list it: this is a purely additive extension of the recognized-code vocabulary, not a
  // conflict with any 4.2 rule, and breakend notation is near-universal in real-world structural-variant
  // VCFs -- matching this library's general "accept 4.x before/after the target where representable" policy.
  Breakend("BND"),
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
    m_parentCodes = Collections.unmodifiableList(Arrays.asList(parentCodes));
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

  /**
   * @return An unmodifiable view of this code's reserved parent codes (empty for a level-0 code)
   */
  public List<ReservedStructuralVariantCode> getParentCodes() {
    return m_parentCodes;
  }
}
