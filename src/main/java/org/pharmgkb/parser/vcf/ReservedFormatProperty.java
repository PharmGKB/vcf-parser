package org.pharmgkb.parser.vcf;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;


/**
 * A FORMAT field specified as reserved in the VCF specification.
 * @author Douglas Myers-Turnbull
 */
public enum ReservedFormatProperty implements ReservedProperty {

  Genotype("GT", "Genotype, encoded as allele values separated by either / or |.", String.class, false, "1"),
  // Number=R (one value per allele, including REF, unlike Number=A's ALT-only count) -- VCFv4.3.tex's Reserved
  // genotype keys table (table:reserved-genotypes); VCFv4.2 does not table FORMAT AD/ADF/ADR at all
  AlleleDepth("AD", "Read depth for each allele", Long.class, true, "R"),
  AlleleDepthForward("ADF", "Read depth for each allele on the forward strand", Long.class, true, "R"),
  AlleleDepthReverse("ADR", "Read depth for each allele on the reverse strand", Long.class, true, "R"),
  Depth("DP", "Read depth at this position for this sample.", Long.class, false, "1"),
  // expectedNumber sourced from VCFv4.3's Reserved genotype keys table (table:reserved-genotypes); VCFv4.2
  // describes FT only in prose
  Filter("FT", "Sample genotype filter indicating if this genotype was called.", String.class, false, "1"),
  GenotypeLikelihoods("GL", "Genotype likelihoods comprised of comma separated floating point log10-scaled likelihoods"
      + " for all possible genotypes given the set of alleles defined in the REF and ALT fields.",
      VcfFloat.class, true, "G"),
  // isList=false: the spec's own example (GLE=0:-75.22,1:-223.42,0/0:-323.03,...) is one opaque String that uses
  // commas internally as part of its own genotype:likelihood encoding, not a delimited list of independent values.
  // expectedNumber is a SEPARATE concern from isList (isList is about how the VALUE is parsed; expectedNumber is
  // about what the DECLARED header Number should say) and the two intentionally diverge here: GLE has no formal
  // Number in VCFv4.1.tex or VCFv4.2.tex (both prose-only) and VCFv4.3 removed the key entirely ("Removed unused
  // and ill-defined GLE FORMAT tag"). No spec table exists. Sourced instead directly from hts-specs' own fixtures,
  // which consistently expect Number=G: test/vcf/4.1/failed/failed_meta_format_030.vcf and
  // test/vcf/4.2/failed/failed_meta_format_029.vcf both have ##CauseOfFailure=FORMAT GLE Number is not G.
  // This is the one entry in this enum that is reserved only up to a version: GLE is a reserved FORMAT key in
  // VCFv4.1.tex line 208 and VCFv4.2.tex line 225 ("GLE : genotype likelihoods of heterogeneous ploidy ...
  // (String)") and was REMOVED by VCFv4.3 (VCFv4.3.tex line 2168, "Removed unused and ill-defined GLE FORMAT
  // tag", restated in VCFv4.4.tex line 2630 and VCFv4.5.tex line 2812; it appears nowhere else in any of those
  // three files, in particular not in VCFv4.3's Reserved genotype keys table). Every special case this library
  // gives GLE is therefore gated to <=4.2 -- see fromId(String, VcfVersion) below for the classification gate,
  // and InternalUtils.splitFormatValue/isExemptFromGenotypeOrderingCardinalityCheck/convertProperty,
  // VcfSample.checkNoStructuralDelimiter/containsProperty and VcfParser.toSampleValues for the rest. The
  // constant itself remains, since a 4.1/4.2 file may still declare and use the key.
  GenotypeLikelihoodsOfHeterogenousPloidy("GLE",
      "genotype likelihoods of heterogeneous ploidy, used in presence of uncertain copy number.",
      String.class, false, "G"),
  PhredScaledGenotypeLikelihoods("PL", "the phred-scaled genotype likelihoods rounded to the closest integer " +
      "(and otherwise defined precisely as the GL field)", Long.class, true, "G"),
  // Version-dependent numeric interpretation, unlike every other entry in this enum: phred-scaled under
  // <=4.2 (VCFv4.2.tex line 227, "the phred-scaled genotype posterior probabilities ... (Floats)") and
  // 0-1-scaled probabilities under 4.3+ (VCFv4.3.tex line 491, "Genotype posterior probabilities in the
  // range 0 to 1 ..."; confirmed by VCFv4.3.tex's own changelog, line 2156, "Both CNP and GP use 0 to 1
  // encoding, which is a change from previous phred-scaled GP"). The constant name is therefore
  // version-neutral -- unlike PhredScaledGenotypeLikelihoods (PL) and PhredScaledPosteriorProbabilities (PP)
  // below, GP no longer has one fixed numeric meaning across every version this library targets, so a name
  // asserting "PhredScaled" would be wrong under 4.3+. InternalUtils.checkReservedFormatConstraints applies
  // a [0, 1] range check under VcfVersion.isAtLeast(VcfVersion.V4_3); no such check applies under <=4.2,
  // where any Float is a legitimate phred value.
  GenotypePosterior("GP", "The genotype posterior probabilities (and otherwise defined precisely as the GL " +
      "field); phred-scaled under VCFv4.2 and earlier, 0-to-1-scaled probabilities under VCFv4.3 and later. " +
      "Intended to store imputed genotype probabilities.", VcfFloat.class, true, "G"),
  // Integer/phred-scaled sibling of GP, the same relationship PL has to GL -- VCFv4.3.tex's Reserved genotype
  // keys table (table:reserved-genotypes); not in VCFv4.2, which does not table PP at all.
  // NOT interchangeable with GP above, despite the analogy: VCFv4.3.tex's own changelog (line 2143, "Added PP
  // tag which is the phred-scaled analogue to GP") and line 2156 ("Both CNP and GP use 0 to 1 encoding, which
  // is a change from previous phred-scaled GP") confirm VCFv4.3 redefined GP from 4.2's phred-scaled encoding
  // to a linear 0-to-1 probability, and introduced PP to take over GP's old phred-scaled role. GP above (see
  // its own comment) tracks that version-dependent meaning directly rather than fixing on one of the two;
  // this entry is the newer, separate, always-phred-scaled 4.3+ key (Long, Number=G) -- the constant name
  // intentionally avoids reusing GP's own word order (Genotype/PosteriorProbabilities/PhredScaled) to keep
  // the two from reading as interchangeable at a call site.
  PhredScaledPosteriorProbabilities("PP",
      "Phred-scaled genotype posterior probabilities rounded to the closest integer", Long.class, true, "G"),
  GenotypeQualityConditional("GQ", "conditional genotype quality, encoded as a phred quality " +
      "−10log10p(genotype call is wrong, conditioned on the site’s being variant)", Long.class, false, "1"),
  // fixed count of 2 (VCFv4.2.tex's own ##FORMAT=<ID=HQ,...> example), not a variable-list code
  HaplotypeQualities("HQ", "Haplotype qualities, two comma separated phred qualities", Long.class, true, "2"),
  PhaseSet("PS", "Phase set; see specification.", Long.class, false, "1"),
  PhasingQuality("PQ", "Phasing quality, the phred-scaled probability that alleles are ordered incorrectly in a " +
      "heterozygote (against all other members in the phase set).", Long.class, false, "1"),
  ExpectedAlleleCounts("EC", "List of expected alternate allele counts for each alternate allele in the same order " +
      "as listed in the ALT field (typically used in association analyses)", Long.class, true, "A"),
  // isList=false: unlike GL/PL/GP/HQ/EC (explicitly described as comma-separated in the spec), MQ has no such
  // qualifier -- a single value
  MappingQuality("MQ", "RMS mapping quality, similar to the version in the INFO field.", Long.class, false, "1"),

  // structural variants -- declared natively in VCFv4.2.tex itself, no VCFv4.3 clarification needed (except
  // CNP; see its own comment below)

  CopyNumber("CN", "Copy number genotype for imprecise events", Long.class, false, "1"),
  CopyNumberGenotypeQuality("CNQ", "Copy number genotype quality for imprecise events", VcfFloat.class, false, "1"),
  CopyNumberLikelihood("CNL", "Copy number genotype likelihood for imprecise events", VcfFloat.class, true, "."),
  // has no VCFv4.2 declaration at all (introduced by VCFv4.3, "FORMAT keys used for structural variants",
  // sv-format-keys, unchanged through 4.5), unlike CNL directly above, so "G" (the literal, only spec value)
  // is kept here rather than following CNL's own VCFv4.2-native "." -- correctly rejecting/warning about a
  // declared Number that isn't "G" still matters (see FormatMetadata's reserved-Number check). But CNL/CNP's
  // own prose ("a list of ... likelihoods/probabilities for each potential copy number, starting from zero")
  // means their "G" is not the standard ploidy-derived genotype-ordering list GT/GL/PL/GP/PP's "G" is -- it's
  // an application-defined dimension nothing in a VCF record actually states, so there is no correct expected
  // *value*-cardinality to check a sample's CNP value against. CNP (not CNL, whose own "." already disables
  // this check) is therefore added to InternalUtils's generic-Number=G-cardinality-check exemption set
  // alongside GLE, in both VcfSample.getProperty(VcfPosition, VcfMetadata, String) and
  // VcfWriter.validateFormatValue -- see InternalUtils.isExemptFromGenotypeOrderingCardinalityCheck.
  CopyNumberPosteriorProbabilities("CNP", "Copy number posterior probabilities", VcfFloat.class, true, "G"),
  PhredScoreForNovelty("NQ", "Phred style probability score that the variant is novel", Long.class, false, "1"),
  HaplotypeId("HAP", "Unique haplotype identifier", Long.class, false, "1"),
  AncestralHaplotypeId("AHAP", "Unique identifier of ancestral haplotype", Long.class, false, "1");

  private final String m_id;

  private final String m_description;

  private final Class m_type;

  private final boolean m_isList;

  private final String m_expectedNumber;

  ReservedFormatProperty(String id, String description, Class type, boolean isList, String expectedNumber) {
    m_id = id;
    m_description = description;
    m_type = type;
    m_isList = isList;
    m_expectedNumber = expectedNumber;
  }

  public String getId() {
    return m_id;
  }

  public String getDescription() {
    return m_description;
  }

  public Class getType() {
    return m_type;
  }

  public boolean isList() {
    return m_isList;
  }

  public String getExpectedNumber() {
    return m_expectedNumber;
  }

  private static final Map<String, ReservedFormatProperty> BY_ID = Arrays.stream(values())
      .collect(Collectors.toUnmodifiableMap(ReservedFormatProperty::getId, Function.identity()));

  /**
   * The version-unaware form: classifies {@code id} as a reserved FORMAT key if it is reserved under
   * <em>any</em> version this library targets. Use this when no {@link VcfVersion} is in scope, or when the
   * caller intentionally wants the version-independent classification. Prefer
   * {@link #fromId(String, VcfVersion)} whenever a version is available -- it additionally returns
   * {@code null} for {@link #GenotypeLikelihoodsOfHeterogenousPloidy GLE} at VCFv4.3 and later, since VCFv4.3
   * removed that key (see that method's own Javadoc for the citation).
   */
  @Nullable
  public static ReservedFormatProperty fromId(@Nullable String id) {
    return id == null ? null : BY_ID.get(id);
  }

  /**
   * The version-aware form of {@link #fromId(String)}: {@code id} is classified as a reserved FORMAT key only if
   * it is reserved <em>under {@code version}</em>.
   * <p>
   * Deliberately <b>not</b> a general version-aware reserved-key table -- it is a single hardcoded check for
   * {@link #GenotypeLikelihoodsOfHeterogenousPloidy GLE}, the only reserved FORMAT key any version this library
   * interprets removes (VCFv4.3.tex line 2168, "Removed unused and ill-defined GLE FORMAT tag"; see that
   * constant's own comment). A real per-version table is a separate, deferred project -- see
   * {@code InternalUtils.checkReservedInfoConstraints}'s TODO and {@code TODO.md}'s "Investigate bumping the
   * target VCF spec to 4.4 or 4.5" entry -- so a second version-dependent key must not be bolted on here.
   *
   * @return the reserved FORMAT property {@code id} names under {@code version}, or {@code null} if {@code id}
   * names none (including a key reserved under an older version but not under {@code version})
   */
  @Nullable
  static ReservedFormatProperty fromId(@Nullable String id, VcfVersion version) {
    ReservedFormatProperty property = fromId(id);
    if (property == GenotypeLikelihoodsOfHeterogenousPloidy && version.isAtLeast(VcfVersion.V4_3)) {
      return null;
    }
    return property;
  }

}
