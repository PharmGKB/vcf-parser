package org.pharmgkb.parser.vcf;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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

  // The full set of "reserved symbolic allele names" (VCFv4.3.tex:266, "The contig names must not use a
  // reserved symbolic allele name.") -- a bare level-0 code (e.g. "DEL"), or a level-1 reserved subtype
  // combined with each of ITS reserved parents (e.g. "DUP:TANDEM", never a bare "TANDEM": VCFv4.3.tex:213-215
  // only ever reserves the subtype in combination with a parent, and AltStructuralVariant's own level-mismatch
  // check already rejects a bare level-1 code used at level 0). Deliberately NOT every string
  // AltStructuralVariant would accept without throwing -- a custom subtype under a reserved parent (e.g.
  // "INS:ME:LINE") is a valid, non-reserved contig name, since VCFv4.4.tex:221 ("Implementations are free to
  // define their own subtypes") confirms VCF doesn't reserve the space of valid subtypes; only the codes this
  // spec passage itself enumerates as reserved are checked against.
  private static final Set<String> RESERVED_SYMBOLIC_ALLELE_NAMES = Arrays.stream(values())
      .flatMap(code -> code.getLevelInSpecification() == 0
          ? Stream.of(code.getId())
          : code.getParentCodes().stream().map(parent -> parent.getId() + ":" + code.getId()))
      .collect(Collectors.toUnmodifiableSet());

  /**
   * @return {@code true} if {@code id} is exactly one of the reserved symbolic allele names this enum models
   * (see {@link #RESERVED_SYMBOLIC_ALLELE_NAMES}'s own comment for why this is narrower than "every ID
   * {@link AltStructuralVariant} would accept")
   */
  public static boolean isReservedSymbolicAlleleName(@Nullable String id) {
    return id != null && RESERVED_SYMBOLIC_ALLELE_NAMES.contains(id);
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
