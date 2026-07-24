package org.pharmgkb.parser.vcf.model;

import org.jspecify.annotations.Nullable;


/**
 * A FORMAT field specified as reserved in the VCF specification.
 * @author Douglas Myers-Turnbull
 */
public enum ReservedFormatProperty implements ReservedProperty {

  Genotype("GT", "Genotype, encoded as allele values separated by either / or |.", String.class, false, "1"),
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

  // structural variants -- declared natively in VCFv4.2.tex itself, no VCFv4.3 clarification needed

  CopyNumber("CN", "Copy number genotype for imprecise events", Long.class, false, "1"),
  CopyNumberGenotypeQuality("CNQ", "Copy number genotype quality for imprecise events", VcfFloat.class, false, "1"),
  CopyNumberLikelihood("CNL", "Copy number genotype likelihood for imprecise events", VcfFloat.class, true, "."),
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

  @Nullable
  public static ReservedFormatProperty fromId(String id) {
    switch (id) {
      case "GT":
        return Genotype;
      case "DP":
        return Depth;
      case "FT":
        return Filter;
      case "GL":
        return GenotypeLikelihoods;
      case "GLE":
        return GenotypeLikelihoodsOfHeterogenousPloidy;
      case "PL":
        return PhredScaledGenotypeLikelihoods;
      case "GP":
        return GenotypePosteriorProbabilitiesPhredScaled;
      case "GQ":
        return GenotypeQualityConditional;
      case "HQ":
        return HaplotypeQualities;
      case "PS":
        return PhaseSet;
      case "PQ":
        return PhasingQuality;
      case "EC":
        return ExpectedAlleleCounts;
      case "MQ":
        return MappingQuality;
      case "CN":
        return CopyNumber;
      case "CNQ":
        return CopyNumberGenotypeQuality;
      case "CNL":
        return CopyNumberLikelihood;
      case "NQ":
        return PhredScoreForNovelty;
      case "HAP":
        return HaplotypeId;
      case "AHAP":
        return AncestralHaplotypeId;
      default:
        return null;
    }
  }

}
