package org.pharmgkb.parser.vcf.model;

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
  GenotypeLikelihoodsOfHeterogenousPloidy("GLE",
      "genotype likelihoods of heterogeneous ploidy, used in presence of uncertain copy number.",
      String.class, false, "G"),
  PhredScaledGenotypeLikelihoods("PL", "the phred-scaled genotype likelihoods rounded to the closest integer " +
      "(and otherwise defined precisely as the GL field)", Long.class, true, "G"),
  GenotypePosteriorProbabilitiesPhredScaled("GP", "The phred-scaled genotype posterior probabilities (and otherwise defined " +
      "precisely as the GL field); intended to store imputed genotype probabilities", VcfFloat.class, true, "G"),
  // Integer/phred-scaled sibling of GP, the same relationship PL has to GL -- VCFv4.3.tex's Reserved genotype
  // keys table (table:reserved-genotypes); not in VCFv4.2, which does not table PP at all.
  // NOT interchangeable with GP above, despite the analogy: VCFv4.3.tex's own changelog (line 2143, "Added PP
  // tag which is the phred-scaled analogue to GP") and line 2156 ("Both CNP and GP use 0 to 1 encoding, which
  // is a change from previous phred-scaled GP") confirm VCFv4.3 redefined GP from 4.2's phred-scaled encoding
  // to a linear 0-to-1 probability, and introduced PP to take over GP's old phred-scaled role. This library
  // targets VCFv4.2 semantics, so GenotypePosteriorProbabilitiesPhredScaled above deliberately keeps
  // VCFv4.2's phred-scaled GP (VcfFloat, per VCFv4.2.tex line 227); this entry is the newer, separate 4.3+
  // key (Long, Number=G) -- the constant name intentionally avoids reusing GP's own word order (Genotype/
  // PosteriorProbabilities/PhredScaled) to keep the two from reading as interchangeable at a call site.
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

  @Nullable
  public static ReservedFormatProperty fromId(@Nullable String id) {
    return id == null ? null : BY_ID.get(id);
  }

}
